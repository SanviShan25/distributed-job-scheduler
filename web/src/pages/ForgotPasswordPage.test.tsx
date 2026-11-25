import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { loggedOut, renderApp } from "../test/renderApp";

describe("ForgotPasswordPage", () => {
  it("checks the email before sending anything", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/forgot-password");
    const user = userEvent.setup();

    await user.click(await screen.findByRole("button", { name: "Send reset link" }));

    expect(screen.getByText("Enter your email address.")).toBeVisible();
    expect(screen.getByLabelText("Email")).toHaveFocus();
    expect(backend.apiRequests().filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("always shows the same 'if there is an account' answer", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/forgot-password": () =>
        json(202, {
          message:
            "If an account exists for that address, we've emailed a link to reset the password.",
        }),
    });
    const app = renderApp("/forgot-password");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "Someone@Example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Check your inbox" }),
    ).toBeVisible();
    expect(app.location()).toBe("/check-inbox");
    expect(screen.getByText(/If there is an account for/)).toHaveTextContent(
      "If there is an account for someone@example.com, we've emailed it a link to choose a new password.",
    );
    expect(screen.getByRole("link", { name: "Request another link" })).toHaveAttribute(
      "href",
      "/forgot-password",
    );
    expect(backend.requests.find((r) => r.path === "/api/auth/forgot-password")?.body).toEqual({
      email: "someone@example.com",
    });
  });

  it("says how long to wait after too many requests", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/forgot-password": () =>
        problem(429, "Too many requests. Please try again in 20 seconds.", { "Retry-After": "20" }),
    });
    renderApp("/forgot-password");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "someone@example.com");
    await user.click(screen.getByRole("button", { name: "Send reset link" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Please try again in 20 seconds.",
    );
  });
});
