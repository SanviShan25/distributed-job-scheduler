import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { meQueryKey } from "../auth/user";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { alice, loggedIn, loggedOut, renderApp } from "../test/renderApp";

const INVALID = "This link is invalid or has expired. Please request a new one.";

describe("ResetPasswordPage", () => {
  it("removes the token from the address bar and checks the new password first", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    const app = renderApp("/reset-password?token=reset-1");
    const user = userEvent.setup();

    const password = await screen.findByLabelText("New password");
    expect(app.location()).toBe("/reset-password");
    expect(password).toHaveAccessibleDescription("Use 10 to 128 characters.");

    await user.type(password, "short");
    await user.click(screen.getByRole("button", { name: "Save new password" }));
    expect(screen.getByText("Use at least 10 characters. You've typed 5.")).toBeVisible();
    expect(password).toHaveFocus();
    expect(backend.requests.some((r) => r.path === "/api/auth/reset-password")).toBe(false);
  });

  it("sets the new password, forgets any logged-in user, and offers to log in", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(alice),
      "POST /api/auth/reset-password": () =>
        json(200, {
          message: "Your password has been changed. Please log in with your new password.",
        }),
    });
    const app = renderApp("/reset-password?token=reset-1");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New password"), "my new long password");
    await user.click(screen.getByRole("button", { name: "Save new password" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Password changed" }),
    ).toBeVisible();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Your password has been changed. Please log in with your new password. For your safety, you've been logged out on all devices.",
    );
    expect(screen.getByRole("link", { name: "Log in" })).toHaveAttribute("href", "/login");
    expect(backend.requests.find((r) => r.path === "/api/auth/reset-password")?.body).toEqual({
      token: "reset-1",
      newPassword: "my new long password",
    });
    expect(app.queryClient.getQueryData(meQueryKey)).toBeNull();
  });

  it("explains an expired or used link and links to request a new one", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/reset-password": () => problem(400, INVALID),
    });
    renderApp("/reset-password?token=old");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New password"), "my new long password");
    await user.click(screen.getByRole("button", { name: "Save new password" }));

    expect(await screen.findByText("This link has expired or was already used")).toBeVisible();
    expect(screen.getByRole("link", { name: "Request a new link" })).toHaveAttribute(
      "href",
      "/forgot-password",
    );
  });

  it("treats a link without a token as invalid", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/reset-password");
    expect(await screen.findByRole("link", { name: "Request a new link" })).toBeVisible();
    expect(screen.queryByLabelText("New password")).not.toBeInTheDocument();
  });

  it("shows a password the backend rejects on the field (the link still works)", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/reset-password": () =>
        problem(400, "Password must be between 10 and 128 characters"),
    });
    renderApp("/reset-password?token=reset-1");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New password"), "0123456789");
    await user.click(screen.getByRole("button", { name: "Save new password" }));

    expect(await screen.findByText("Password must be between 10 and 128 characters")).toBeVisible();
    expect(screen.getByLabelText("New password")).toHaveAttribute("aria-invalid", "true");
  });

  it("says how long to wait after too many attempts", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/reset-password": () =>
        problem(429, "Too many requests.", { "Retry-After": "15" }),
    });
    renderApp("/reset-password?token=reset-1");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("New password"), "my new long password");
    await user.click(screen.getByRole("button", { name: "Save new password" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Please try again in 15 seconds.",
    );
  });
});
