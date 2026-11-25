import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { loggedOut, renderApp } from "../test/renderApp";

const created = () =>
  json(201, {
    email: "new@example.com",
    emailVerified: false,
    message: "Account created. Check your inbox to verify your email address.",
  });

describe("SignupPage", () => {
  it("shows the password rule before anything is submitted, with a live count", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/signup");
    const user = userEvent.setup();

    const password = await screen.findByLabelText("Password");
    expect(password).toHaveAccessibleDescription("Use 10 to 128 characters.");
    await user.type(password, "abc");
    expect(password).toHaveAccessibleDescription("Use 10 to 128 characters. 3 characters so far.");
    await user.type(password, "defghij");
    expect(password).toHaveAccessibleDescription(
      "Use 10 to 128 characters. 10 characters so far ✓.",
    );
  });

  it("checks email and password length (10–128) before sending anything", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedOut });
    renderApp("/signup");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "name@");
    await user.type(screen.getByLabelText("Password"), "123456789");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(screen.getByLabelText("Email")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByText("Enter a valid email address, like name@example.com.")).toBeVisible();
    expect(screen.getByText("Use at least 10 characters. You've typed 9.")).toBeVisible();

    await user.clear(screen.getByLabelText("Password"));
    await user.click(screen.getByLabelText("Password"));
    await user.paste("x".repeat(129));
    await user.click(screen.getByRole("button", { name: "Create account" }));
    expect(screen.getByText("Use no more than 128 characters. You've typed 129.")).toBeVisible();

    expect(backend.apiRequests().filter((r) => r.method === "POST")).toHaveLength(0);
  });

  it("creates the account and shows where the email went", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/signup": created,
    });
    const app = renderApp("/signup");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), " New@Example.com ");
    await user.type(screen.getByLabelText("Password"), "a long enough password");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(
      await screen.findByRole("heading", { level: 1, name: "Check your inbox" }),
    ).toBeVisible();
    expect(app.location()).toBe("/check-inbox");
    expect(screen.getByText("new@example.com")).toBeVisible();
    expect(screen.getByText("The link works for 24 hours.")).toBeVisible();
    const signup = backend.requests.find((r) => r.path === "/api/auth/signup");
    expect(signup?.body).toEqual({ email: "new@example.com", password: "a long enough password" });
    expect(signup?.headers.get("X-XSRF-TOKEN")).toMatch(/^csrf-/);
  });

  it("shows the backend's validation message on the field", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/signup": () => problem(400, "Please enter a valid email address"),
    });
    renderApp("/signup");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "odd@example.com");
    await user.type(screen.getByLabelText("Password"), "a long enough password");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    const email = screen.getByLabelText("Email");
    expect(await screen.findByText("Please enter a valid email address")).toBeVisible();
    expect(email).toHaveAttribute("aria-invalid", "true");
    expect(email).toHaveFocus();
  });

  it("says how long to wait after too many sign-ups", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "POST /api/auth/signup": () =>
        problem(429, "Too many sign-ups. Please try again in 1800 seconds.", {
          "Retry-After": "1800",
        }),
    });
    renderApp("/signup");
    const user = userEvent.setup();

    await user.type(await screen.findByLabelText("Email"), "new@example.com");
    await user.type(screen.getByLabelText("Password"), "a long enough password");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Please try again in 30 minutes.",
    );
  });
});
