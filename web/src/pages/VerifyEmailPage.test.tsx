import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { alice, loggedIn, loggedOut, renderApp } from "../test/renderApp";

const INVALID = "This link is invalid or has expired. Please request a new one.";

describe("VerifyEmailPage", () => {
  it("sends the token once, removes it from the address bar, and confirms", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/verify": () => json(200, { message: "Your email address is verified." }),
    });
    const app = renderApp("/verify-email?token=abc123");

    expect(await screen.findByText("Thanks, your email address is verified.")).toBeVisible();
    expect(app.location()).toBe("/verify-email");
    const verifies = backend.requests.filter((r) => r.path === "/api/auth/verify");
    expect(verifies).toHaveLength(1); // even under StrictMode's double effects
    expect(verifies[0]?.body).toEqual({ token: "abc123" });
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/login");
  });

  it("when logged in, refreshes the account (so the banner goes) and links to the app", async () => {
    let verified = false;
    const backend = fakeBackend({
      "GET /api/auth/me": () => json(200, { ...alice, emailVerified: verified }),
      "POST /api/auth/verify": () => {
        verified = true;
        return json(200, { message: "Your email address is verified." });
      },
    });
    renderApp("/verify-email?token=abc123");

    expect(await screen.findByRole("link", { name: "Go to your websites" })).toHaveAttribute(
      "href",
      "/dashboard",
    );
    expect(backend.requests.filter((r) => r.path === "/api/auth/me").length).toBeGreaterThan(1);
  });

  it("explains an expired or used link and how to get a new one (logged out)", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/verify": () => problem(400, INVALID),
    });
    renderApp("/verify-email?token=old");

    expect(await screen.findByText("This link has expired or was already used")).toBeVisible();
    expect(screen.getByRole("link", { name: "Log in to request a new link" })).toHaveAttribute(
      "href",
      "/login",
    );
  });

  it("offers a new link right away when logged in", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, emailVerified: false }),
      "POST /api/auth/verify": () => problem(400, INVALID),
      "POST /api/auth/resend-verification": () =>
        json(200, { message: "We've sent you a new verification link." }),
    });
    renderApp("/verify-email?token=old");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Request a new link" }));

    expect(
      await screen.findByText("We've sent you a new verification link. Check your inbox."),
    ).toBeVisible();
    expect(backend.requests.some((r) => r.path === "/api/auth/resend-verification")).toBe(true);
  });

  it("treats a link without a token as invalid, without calling the API", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/verify-email");

    expect(await screen.findByText("This link has expired or was already used")).toBeVisible();
    expect(backend.requests.some((r) => r.path === "/api/auth/verify")).toBe(false);
  });

  it("lets the user try again after a network problem", async () => {
    let attempts = 0;
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/verify": () => {
        attempts += 1;
        if (attempts === 1) throw new TypeError("Failed to fetch");
        return json(200, { message: "Your email address is verified." });
      },
    });
    renderApp("/verify-email?token=abc123");
    const user = userEvent.setup();

    expect(await screen.findByRole("alert")).toHaveTextContent("We couldn't reach PulseCheck");
    await user.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByText("Thanks, your email address is verified.")).toBeVisible();
  });
});
