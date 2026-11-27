import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { fakeBackend } from "../test/fakeBackend";
import { loggedIn, loggedOut, renderApp } from "../test/renderApp";

/**
 * Browsers and password managers fill and save logins from these attributes, so every auth form
 * has to have them right.
 */
describe("autocomplete on account forms", () => {
  it.each([
    ["/login", { Email: "email", Password: "current-password" }],
    ["/signup", { Email: "email", Password: "new-password" }],
    ["/forgot-password", { Email: "email" }],
    ["/reset-password?token=abc", { "New password": "new-password" }],
  ])("%s", async (path, fields: Record<string, string>) => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp(path);
    for (const [label, value] of Object.entries(fields)) {
      const input = await screen.findByLabelText(label);
      expect(input).toHaveAttribute("autocomplete", value);
      if (value === "email") {
        expect(input).toHaveAttribute("type", "email");
        expect(input).toHaveAttribute("autocapitalize", "none");
        expect(input).toHaveAttribute("spellcheck", "false");
      }
    }
  });

  it("account settings: current and new passwords, with the username for password managers", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    const { container } = renderApp("/settings/account");

    expect(await screen.findByLabelText("Current password")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );
    expect(screen.getByLabelText("New password")).toHaveAttribute("autocomplete", "new-password");
    expect(screen.getByLabelText("Your password")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );
    const username = container.querySelector('input[autocomplete="username"]');
    expect(username).toHaveValue("alice@example.com");
    expect(username).not.toBeVisible();
  });
});
