import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, noContent, problem } from "../test/fakeBackend";
import { loggedIn, loggedOut, renderApp } from "../test/renderApp";

function card(name: string) {
  const heading = screen.getByRole("heading", { level: 2, name });
  const section = heading.closest("section");
  if (!section) throw new Error(`no card ${name}`);
  return within(section);
}

describe("AccountSettingsPage", () => {
  it("is only for logged-in users", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    const app = renderApp("/settings/account");
    await screen.findByRole("heading", { level: 1, name: "Log in" });
    expect(app.location()).toBe("/login?next=%2Fsettings%2Faccount");
  });

  it("is reachable from the account menu", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    renderApp("/dashboard");
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: /Account menu/ }));
    await user.click(screen.getByRole("link", { name: "Account settings" }));
    expect(
      await screen.findByRole("heading", { level: 1, name: "Account settings" }),
    ).toBeVisible();
    expect(screen.getByText("alice@example.com", { selector: "dd" })).toBeVisible();
  });

  describe("change password", () => {
    it("checks both fields first", async () => {
      const backend = fakeBackend({ "GET /api/auth/me": loggedIn() });
      renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Change password");

      await user.type(form.getByLabelText("New password"), "short");
      await user.click(form.getByRole("button", { name: "Change password" }));

      expect(form.getByText("Enter your password.")).toBeVisible();
      expect(form.getByText("Use at least 10 characters. You've typed 5.")).toBeVisible();
      expect(form.getByLabelText("Current password")).toHaveFocus();
      expect(backend.requests.some((r) => r.path === "/api/auth/change-password")).toBe(false);
    });

    it("changes the password and clears the form", async () => {
      const backend = fakeBackend({
        "GET /api/auth/me": loggedIn(),
        "POST /api/auth/change-password": () =>
          json(200, { message: "Your password has been changed." }),
      });
      renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Change password");

      await user.type(form.getByLabelText("Current password"), "old long password");
      await user.type(form.getByLabelText("New password"), "new long password");
      await user.click(form.getByRole("button", { name: "Change password" }));

      expect(
        await form.findByText(
          "Your password has been changed. You've been logged out on your other devices.",
        ),
      ).toBeVisible();
      expect(form.getByLabelText("Current password")).toHaveValue("");
      expect(form.getByLabelText("New password")).toHaveValue("");
      expect(backend.requests.find((r) => r.path === "/api/auth/change-password")?.body).toEqual({
        currentPassword: "old long password",
        newPassword: "new long password",
      });
    });

    it("marks a wrong current password on its field", async () => {
      fakeBackend({
        "GET /api/auth/me": loggedIn(),
        "POST /api/auth/change-password": () => problem(403, "Your password is incorrect"),
      });
      const app = renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Change password");

      await user.type(form.getByLabelText("Current password"), "not my password");
      await user.type(form.getByLabelText("New password"), "new long password");
      await user.click(form.getByRole("button", { name: "Change password" }));

      const current = form.getByLabelText("Current password");
      expect(await form.findByText("Your password is incorrect")).toBeVisible();
      expect(current).toHaveAttribute("aria-invalid", "true");
      expect(current).toHaveFocus();
      // 403 is an answer, not a logout.
      expect(app.location()).toBe("/settings/account");
    });
  });

  describe("delete account", () => {
    it("needs the typed confirmation and the password", async () => {
      const backend = fakeBackend({ "GET /api/auth/me": loggedIn() });
      renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Delete account");

      await user.type(form.getByLabelText("Type DELETE to confirm"), "delet");
      await user.click(form.getByRole("button", { name: "Delete my account" }));

      expect(form.getByText("Type DELETE to confirm.")).toBeVisible();
      expect(form.getByText("Enter your password.")).toBeVisible();
      expect(form.getByLabelText("Type DELETE to confirm")).toHaveFocus();
      expect(backend.requests.some((r) => r.method === "DELETE")).toBe(false);
    });

    it("marks a wrong password on its field", async () => {
      fakeBackend({
        "GET /api/auth/me": loggedIn(),
        "DELETE /api/auth/account": () => problem(403, "Your password is incorrect"),
      });
      renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Delete account");

      await user.type(form.getByLabelText("Type DELETE to confirm"), "DELETE");
      await user.type(form.getByLabelText("Your password"), "not my password");
      await user.click(form.getByRole("button", { name: "Delete my account" }));

      expect(await form.findByText("Your password is incorrect")).toBeVisible();
      expect(form.getByLabelText("Your password")).toHaveAttribute("aria-invalid", "true");
    });

    it("deletes the account and says so on the login page", async () => {
      const backend = fakeBackend({
        "GET /api/auth/me": loggedIn(),
        "DELETE /api/auth/account": () => noContent(),
      });
      const app = renderApp("/settings/account");
      const user = userEvent.setup();
      await screen.findByRole("heading", { level: 1, name: "Account settings" });
      const form = card("Delete account");

      await user.type(form.getByLabelText("Type DELETE to confirm"), "delete");
      await user.type(form.getByLabelText("Your password"), "my long password");
      await user.click(form.getByRole("button", { name: "Delete my account" }));

      expect(
        await screen.findByText("Your account and all its data have been deleted."),
      ).toBeVisible();
      expect(app.location()).toBe("/login");
      const del = backend.requests.find((r) => r.method === "DELETE");
      expect(del?.path).toBe("/api/auth/account");
      expect(del?.body).toEqual({ password: "my long password" });
      expect(del?.headers.get("X-XSRF-TOKEN")).toMatch(/^csrf-/);
    });
  });
});
