import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { sessionEnd } from "../auth/session";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { alice, loggedOut, renderApp } from "../test/renderApp";

async function fillAndSubmit(email: string, password: string) {
  const user = userEvent.setup();
  await user.type(await screen.findByLabelText("Email"), email);
  await user.type(screen.getByLabelText("Password"), password);
  await user.click(screen.getByRole("button", { name: "Log in" }));
  return user;
}

describe("LoginPage", () => {
  it("checks the fields before sending anything", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/login");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Log in" }));

    expect(screen.getByLabelText("Email")).toHaveAccessibleDescription("Enter your email address.");
    expect(screen.getByLabelText("Password")).toHaveAccessibleDescription("Enter your password.");
    expect(screen.getByLabelText("Email")).toHaveFocus();

    await user.type(screen.getByLabelText("Email"), "not-an-email");
    await user.click(screen.getByRole("button", { name: "Log in" }));
    expect(screen.getByLabelText("Email")).toHaveAccessibleDescription(
      "Enter a valid email address, like name@example.com.",
    );
    expect(backend.apiRequests().filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("logs in with 'keep me logged in', gets a new CSRF token, and opens the dashboard", async () => {
    let loggedIn = false;
    const backend = fakeBackend({
      "GET /api/auth/me": () => (loggedIn ? json(200, alice) : problem(401, "Please log in")),
      "POST /api/auth/login": () => {
        loggedIn = true;
        return json(200, alice);
      },
    });
    const app = renderApp("/login");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "  alice@example.com ");
    await user.type(screen.getByLabelText("Password"), "correct horse battery");
    await user.click(screen.getByRole("checkbox", { name: "Keep me logged in" }));
    await user.click(screen.getByRole("button", { name: "Log in" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Your websites" })).toBeVisible();
    expect(app.location()).toBe("/dashboard");

    const loginIndex = backend.requests.findIndex((r) => r.path === "/api/auth/login");
    expect(backend.requests[loginIndex]?.body).toEqual({
      email: "alice@example.com",
      password: "correct horse battery",
      rememberMe: true,
    });
    expect(backend.requests.slice(loginIndex + 1).map((r) => r.path)).toContain("/api/auth/csrf");
  });

  it("returns to the page the user wanted", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/login": () => json(200, alice),
    });
    const app = renderApp("/login?next=%2Fsettings%2Faccount");

    await fillAndSubmit("alice@example.com", "correct horse battery");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Account settings" }),
    ).toBeVisible();
    expect(app.location()).toBe("/settings/account");
  });

  it("shows the backend's generic message for wrong details, without ending any session", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/login": () => problem(401, "Invalid email or password"),
    });
    const app = renderApp("/login");

    await fillAndSubmit("alice@example.com", "wrong password!");

    expect(await screen.findByRole("alert")).toHaveTextContent("Invalid email or password");
    expect(app.location()).toBe("/login");
    expect(sessionEnd.get()).toBeNull();
  });

  it("says how long to wait after too many attempts", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/login": () =>
        problem(429, "Too many login attempts. Please try again in 37 seconds.", {
          "Retry-After": "37",
        }),
    });
    renderApp("/login");

    await fillAndSubmit("alice@example.com", "whatever password");

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Please try again in 37 seconds.",
    );
  });

  it("can show the password", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/login");
    const user = userEvent.setup();

    const password = await screen.findByLabelText("Password");
    expect(password).toHaveAttribute("type", "password");
    const toggle = screen.getByRole("button", { name: "Show password" });
    await user.click(toggle);
    expect(password).toHaveAttribute("type", "text");
    expect(toggle).toHaveAttribute("aria-pressed", "true");
  });

  it("links to password reset and sign-up", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/login");
    expect(await screen.findByRole("link", { name: "Forgot your password?" })).toHaveAttribute(
      "href",
      "/forgot-password",
    );
    await waitFor(() => {
      expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute(
        "href",
        "/signup",
      );
    });
  });
});
