import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, noContent, problem } from "./test/fakeBackend";
import { alice, loggedIn, loggedOut, renderApp } from "./test/renderApp";

describe("route guards", () => {
  it("sends logged-out users to log in, remembering the page they wanted", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    const app = renderApp("/dashboard?tab=all");

    expect(await screen.findByRole("heading", { level: 1, name: "Log in" })).toBeInTheDocument();
    expect(app.location()).toBe("/login?next=%2Fdashboard%3Ftab%3Dall");
    // Not "session ended": they were never logged in.
    expect(screen.queryByText(/session has ended/)).not.toBeInTheDocument();
  });

  it("shows the home page to logged-out users", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/");
    expect(
      await screen.findByRole("heading", { level: 1, name: "Know when your website goes down" }),
    ).toBeInTheDocument();
  });

  it.each(["/", "/login", "/signup"])(
    "sends logged-in users from %s to the dashboard",
    async (path) => {
      fakeBackend({ "GET /api/auth/me": loggedIn(), "GET /api/monitors": () => json(200, []) });
      const app = renderApp(path);
      expect(await screen.findByRole("heading", { level: 1, name: "Your websites" })).toBeVisible();
      expect(await screen.findByRole("heading", { name: "Add your first website" })).toBeVisible();
      expect(app.location()).toBe("/dashboard");
    },
  );

  it("after login goes to the remembered page, but never to another site", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    const app = renderApp("/login?next=%2F%2Fevil.example%2F");
    await screen.findByRole("heading", { level: 1, name: "Your websites" });
    expect(app.location()).toBe("/dashboard");
  });

  it("offers to try again when the backend can't be reached", async () => {
    let calls = 0;
    fakeBackend({
      "GET /api/auth/me": () => {
        calls += 1;
        if (calls <= 3) throw new TypeError("Failed to fetch"); // first try + 2 retries
        return json(200, alice);
      },
    });
    renderApp("/dashboard");

    const alert = await screen.findByRole("alert", {}, { timeout: 10_000 });
    expect(alert).toHaveTextContent("We couldn't reach PulseCheck");
    await userEvent.click(within(alert).getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("heading", { level: 1, name: "Your websites" })).toBeVisible();
  }, 15_000);
});

describe("session expiry (401)", () => {
  it("sends the user to log in with a message, and remembers the page", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, emailVerified: false }),
      "POST /api/auth/resend-verification": () => problem(401, "Please log in"),
    });
    const app = renderApp("/dashboard");

    await userEvent.click(await screen.findByRole("button", { name: "Resend link" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Log in" })).toBeVisible();
    expect(screen.getByText("Your session has ended. Please log in again.")).toBeVisible();
    expect(app.location()).toBe("/login?next=%2Fdashboard");
  });
});

describe("app layout", () => {
  it("shows the logged-in user's email in the account menu", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    renderApp("/dashboard");

    const menuButton = await screen.findByRole("button", { name: /Account menu/ });
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
    await userEvent.click(menuButton);
    expect(menuButton).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Logged in as")).toHaveTextContent("alice@example.com");

    await userEvent.keyboard("{Escape}");
    expect(menuButton).toHaveAttribute("aria-expanded", "false");
    expect(menuButton).toHaveFocus();
  });

  it("logs out with the CSRF token, gets a new token, and shows the login page", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "POST /api/auth/logout": () => noContent(),
    });
    const app = renderApp("/dashboard");

    await userEvent.click(await screen.findByRole("button", { name: /Account menu/ }));
    await userEvent.click(screen.getByRole("button", { name: "Log out" }));

    expect(await screen.findByText("You've been logged out.")).toBeVisible();
    expect(app.location()).toBe("/login");

    const logoutIndex = backend.requests.findIndex((r) => r.path === "/api/auth/logout");
    const logout = backend.requests[logoutIndex];
    expect(logout?.headers.get("X-XSRF-TOKEN")).toMatch(/^csrf-/);
    expect(backend.requests.slice(logoutIndex + 1).map((r) => r.path)).toContain("/api/auth/csrf");
  });

  it("asks unverified users to verify, and resends the link", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, emailVerified: false }),
      "POST /api/auth/resend-verification": () =>
        json(200, { message: "We've sent you a new verification link." }),
    });
    renderApp("/dashboard");

    const banner = await screen.findByRole("alert");
    expect(banner).toHaveTextContent("Please verify your email");
    expect(banner).toHaveTextContent("alice@example.com");

    await userEvent.click(within(banner).getByRole("button", { name: "Resend link" }));
    expect(
      await screen.findByText("We've sent you a new verification link. Check your inbox."),
    ).toBeVisible();
    const resend = backend.requests.find((r) => r.path === "/api/auth/resend-verification");
    expect(resend?.headers.get("X-XSRF-TOKEN")).toMatch(/^csrf-/);
  });

  it("shows how long to wait when resending too often (429)", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, emailVerified: false }),
      "POST /api/auth/resend-verification": () =>
        problem(429, "Too many verification emails. Please try again in 42 seconds.", {
          "Retry-After": "42",
        }),
    });
    renderApp("/dashboard");

    await userEvent.click(await screen.findByRole("button", { name: "Resend link" }));
    expect(
      await screen.findByText("Too many attempts. Please try again in 42 seconds."),
    ).toBeVisible();
  });

  it("does not show the banner to verified users", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    renderApp("/dashboard");
    await screen.findByRole("heading", { level: 1, name: "Your websites" });
    expect(screen.queryByText("Please verify your email")).not.toBeInTheDocument();
  });

  it("gets a CSRF token on start-up", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/");
    await waitFor(() => {
      expect(backend.requests.some((r) => r.path === "/api/auth/csrf")).toBe(true);
    });
  });
});
