import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { User } from "../auth/user";
import { fakeBackend, json, noContent, problem } from "../test/fakeBackend";
import { aMonitor } from "../test/monitors";
import { demoNow, demoVisitor, loggedIn, renderApp } from "../test/renderApp";

const MONITOR_ID = "3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f";

/** A demo account, with only Date faked (timers run normally) so the time left is predictable. */
function inDemo(user: User = demoVisitor, extra: Parameters<typeof fakeBackend>[0] = {}) {
  return fakeBackend({
    "GET /api/auth/me": loggedIn(user),
    "GET /api/monitors": () => json(200, []),
    ...extra,
  });
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ["Date"] });
  vi.setSystemTime(demoNow);
});

afterEach(() => {
  vi.useRealTimers();
});

describe("in a demo account", () => {
  it("every page says it's a private demo and how much time is left", async () => {
    inDemo();
    renderApp("/dashboard");

    const banner = await screen.findByRole("region", { name: "Demo account" });
    expect(banner).toHaveTextContent("This is your private demo. It's deleted after 3 hours.");
    expect(banner).toHaveTextContent("Time left: 2 hours 30 minutes");
  });

  it("shows “Demo account” instead of the generated address", async () => {
    inDemo();
    renderApp("/dashboard");

    const menuButton = await screen.findByRole("button", { name: /Account menu/ });
    expect(menuButton).toHaveTextContent("Demo account");
    expect(screen.queryByText(/pulsecheck\.invalid/)).not.toBeInTheDocument();
    await userEvent.click(menuButton);
    expect(screen.getByRole("button", { name: "End the demo" })).toBeVisible();
  });

  it("account settings explain why there's no password to change and nothing to delete", async () => {
    inDemo();
    renderApp("/settings/account");

    expect(await screen.findByRole("heading", { name: "Demo account" })).toBeVisible();
    expect(screen.getByText(/It has no password/)).toBeVisible();
    expect(screen.queryByRole("heading", { name: "Change password" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Delete account" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/password/i)).not.toBeInTheDocument();
  });

  it("a website's alert emails explain that the demo sends none, with nothing to manage", async () => {
    inDemo(demoVisitor, {
      [`GET /api/monitors/${MONITOR_ID}`]: () =>
        json(200, aMonitor({ id: MONITOR_ID, name: "Example Domain", intervalSeconds: 300 })),
    });
    renderApp(`/monitors/${MONITOR_ID}`);

    expect(await screen.findByRole("heading", { name: "Alert emails" })).toBeVisible();
    expect(await screen.findByText("No emails in the demo")).toBeVisible();
    expect(screen.queryByLabelText("Add someone")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add recipient" })).not.toBeInTheDocument();
  });

  it("checks faster than every 5 minutes are shown but can't be chosen", async () => {
    inDemo();
    renderApp("/monitors/new");

    const every = await screen.findByRole("radio", { name: /Every minute/ });
    expect(every).toBeDisabled();
    expect(every).toHaveAccessibleName("Every minute, not available in the demo");
    expect(screen.getByRole("radio", { name: "Every 5 minutes" })).toBeEnabled();
    expect(screen.getByRole("radio", { name: "Every 5 minutes" })).toBeChecked();
    expect(
      screen.getByRole("group", { name: "How often should we check?" }),
    ).toHaveAccessibleDescription("In the demo, the fastest choice is every 5 minutes.");
  });

  it("“End the demo” deletes it and goes back to the home page", async () => {
    const backend = inDemo(demoVisitor, {
      "POST /api/auth/logout": () => noContent(),
      "GET /api/config": () => json(200, { demoEnabled: true, signupOpen: false }),
    });
    const app = renderApp("/dashboard");

    await userEvent.click(await screen.findByRole("button", { name: /Account menu/ }));
    await userEvent.click(screen.getByRole("button", { name: "End the demo" }));

    expect(
      await screen.findByText("Your demo has ended and its data has been deleted.", {
        exact: false,
      }),
    ).toBeVisible();
    expect(app.location()).toBe("/");
    expect(backend.apiRequests().some((r) => r.path === "/api/auth/logout")).toBe(true);
    expect(await screen.findByRole("button", { name: "Try the demo" })).toBeVisible();
  });

  it("when the time is up, the demo ends and the visitor goes back to the home page", async () => {
    inDemo({ ...demoVisitor, demoExpiresAt: new Date(demoNow - 1000).toISOString() });
    const app = renderApp("/dashboard");

    expect(
      await screen.findByText("Your demo has ended and its data has been deleted.", {
        exact: false,
      }),
    ).toBeVisible();
    expect(app.location()).toBe("/");
  });

  it("a 401 from the server (demo deleted) also says the demo has ended, not “log in again”", async () => {
    inDemo(demoVisitor, {
      "GET /api/monitors": () => problem(401, "Please log in"),
    });
    const app = renderApp("/dashboard");

    expect(
      await screen.findByText("Your demo has ended and its data has been deleted.", {
        exact: false,
      }),
    ).toBeVisible();
    await waitFor(() => {
      expect(app.location()).toBe("/");
    });
    expect(screen.queryByText(/session has ended/)).not.toBeInTheDocument();
  });

  it("normal accounts see none of this", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn(), "GET /api/monitors": () => json(200, []) });
    renderApp("/dashboard");
    await screen.findByRole("heading", { level: 1, name: "Your websites" });
    expect(screen.queryByRole("region", { name: "Demo account" })).not.toBeInTheDocument();
  });
});
