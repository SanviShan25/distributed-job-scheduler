import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json } from "../test/fakeBackend";
import { loggedOut, renderApp } from "../test/renderApp";

const config = (demoEnabled: boolean, signupOpen: boolean) => () =>
  json(200, { demoEnabled, signupOpen });

describe("the demo on the login and sign-up pages", () => {
  it.each(["/login", "/signup"])("%s offers the demo when it's on", async (path) => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": config(true, true) });
    renderApp(path);
    expect(await screen.findByRole("button", { name: "Try the demo" })).toBeVisible();
    expect(screen.getByText(/Just looking\?/)).toBeVisible();
  });

  it.each(["/login", "/signup"])("%s doesn't mention a demo when there is none", async (path) => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": config(false, true) });
    renderApp(path);
    await screen.findByRole("heading", { level: 1 });
    await screen.findByRole("button", { name: /Log in|Create account/ });
    expect(screen.queryByRole("button", { name: "Try the demo" })).not.toBeInTheDocument();
    expect(screen.queryByText(/Just looking\?/)).not.toBeInTheDocument();
  });

  it("sign-up explains limited sign-ups and points to the demo", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": config(true, false) });
    renderApp("/signup");

    expect(await screen.findByText("Sign-ups are limited right now")).toBeVisible();
    expect(
      screen.getByText(/Only invited email addresses can create an account on this site/),
    ).toHaveTextContent("You can still try everything in a private demo, no sign-up needed.");
    expect(screen.getByRole("button", { name: "Try the demo" })).toBeVisible();
    // Invited people can still sign up.
    expect(screen.getByRole("button", { name: "Create account" })).toBeEnabled();
  });

  it("with limited sign-ups and no demo, tells invited people what to do", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": config(false, false) });
    renderApp("/signup");
    expect(await screen.findByText(/sign up with the address you were invited with/)).toBeVisible();
  });

  it("shows the server's answer for an address that isn't invited", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "GET /api/config": config(true, false),
      "POST /api/auth/signup": () =>
        json(403, {
          title: "Sign-ups are limited",
          status: 403,
          detail:
            "Sign-ups are limited on this site, and this email address isn't on the list. You can try the demo account instead.",
          code: "signup_closed",
        }),
    });
    renderApp("/signup");

    await userEvent.type(await screen.findByLabelText("Email"), "someone@example.com");
    await userEvent.type(screen.getByLabelText("Password"), "a long enough password");
    await userEvent.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "this email address isn't on the list. You can try the demo account instead.",
    );
  });
});
