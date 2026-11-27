import { act, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { fakeBackend, json, problem, type Handler } from "../test/fakeBackend";
import { aMonitor } from "../test/monitors";
import { alice, loggedIn, renderApp } from "../test/renderApp";

function backendProblem(status: number, title: string, detail: string): Handler {
  return () =>
    new Response(JSON.stringify({ type: "about:blank", title, status, detail }), {
      status,
      headers: { "Content-Type": "application/problem+json" },
    });
}

async function fillIn(address: string, name?: string) {
  await userEvent.type(await screen.findByLabelText("Website address"), address);
  if (name !== undefined) await userEvent.type(screen.getByLabelText(/^Name/), name);
}

const submit = () => userEvent.click(screen.getByRole("button", { name: "Add website" }));

describe("AddMonitorPage", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("adds https:// to a bare address, then shows the first check arriving", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const added = aMonitor({
      name: "example.com",
      url: "https://example.com/",
      status: "UNKNOWN",
      lastCheckedAt: null,
      updatedAt: new Date().toISOString(),
      uptime: null,
    });
    let checked = false;
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
      "POST /api/monitors": () => json(201, added),
      [`GET /api/monitors/${added.id}`]: () =>
        json(
          200,
          checked
            ? {
                ...added,
                status: "UP",
                lastCheckedAt: added.updatedAt,
                uptime: { ...aMonitor().uptime },
              }
            : added,
        ),
    });
    const app = renderApp("/monitors/new");

    expect(await screen.findByRole("heading", { level: 1, name: "Add a website" })).toBeVisible();
    await fillIn("example.com");
    expect(screen.getByText("https://example.com")).toBeVisible(); // "We'll check https://example.com"
    await userEvent.click(screen.getByLabelText("Every minute"));
    await submit();

    expect(
      backend.requests.find((r) => r.method === "POST" && r.path === "/api/monitors")?.body,
    ).toEqual({
      name: "example.com",
      url: "https://example.com",
      intervalSeconds: 60,
    });
    expect(await screen.findByRole("heading", { level: 1, name: "example.com" })).toBeVisible();
    expect(app.location()).toBe(`/monitors/${added.id}`);
    expect(screen.getByText("Checking your website…")).toBeVisible();

    checked = true;
    await act(() => vi.advanceTimersByTimeAsync(3_000));
    expect(await screen.findByText("Check complete: your website is up.")).toBeVisible();
    expect(screen.queryByText("Checking your website…")).not.toBeInTheDocument();
  });

  it("checks the address before sending anything", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
    });
    renderApp("/monitors/new");
    await screen.findByLabelText("Website address");
    await submit();

    const field = screen.getByLabelText("Website address");
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(field).toHaveAccessibleDescription(/Enter your website's address/);
    expect(field).toHaveFocus();
    expect(backend.requests.some((r) => r.method === "POST")).toBe(false);
  });

  it("uses the name typed, and defaults to 5 minutes", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
      "POST /api/monitors": () => json(201, aMonitor({ name: "Shop" })),
    });
    renderApp("/monitors/new");
    await fillIn("shop.example.com/cart", "Shop");
    expect(screen.getByLabelText("Every 5 minutes")).toBeChecked();
    await submit();

    await screen.findByRole("heading", { level: 1, name: "Shop" });
    expect(backend.requests.find((r) => r.method === "POST")?.body).toEqual({
      name: "Shop",
      url: "https://shop.example.com/cart",
      intervalSeconds: 300,
    });
  });

  it.each([
    [
      "a private address",
      problem(
        400,
        "This URL can't be monitored: localhost resolves to a private or reserved address (127.0.0.1)",
      ),
      /private or local network/,
    ],
    [
      "an address already checked",
      problem(409, "You already have a monitor for https://example.com/"),
      /already checking this address/,
    ],
    [
      "an unknown host",
      problem(400, "This URL can't be monitored: could not resolve host nosuch.example"),
      /couldn't find a website at nosuch\.example/,
    ],
  ])("explains %s on the address field", async (_, response, message) => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
      "POST /api/monitors": () => response.clone(),
    });
    renderApp("/monitors/new");
    await fillIn("example.com");
    await submit();

    const field = screen.getByLabelText("Website address");
    await vi.waitFor(() => {
      expect(field).toHaveAccessibleDescription(message);
    });
    expect(field).toHaveAttribute("aria-invalid", "true");
    expect(document.body.textContent).not.toMatch(/127\.0\.0\.1|409|400/);

    // Editing the address clears the server's complaint.
    await userEvent.type(field, "x");
    expect(field).not.toHaveAttribute("aria-invalid");
  });

  it("explains the limit when the server says it was reached", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
      "POST /api/monitors": () => problem(409, "You can have at most 10 monitors"),
    });
    renderApp("/monitors/new");
    await fillIn("example.com");
    await submit();

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "You've reached the limit of 10 websites. Delete one to add another.",
    );
  });

  it("offers a new verification link when the email isn't verified", async () => {
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, emailVerified: false }),
      "GET /api/monitors": () => json(200, []),
      "POST /api/monitors": backendProblem(
        403,
        "Email not verified",
        "Please verify your email address before creating monitors. Check your inbox for the link, or request a new one (POST /api/auth/resend-verification).",
      ),
      "POST /api/auth/resend-verification": () =>
        json(200, { message: "We've sent you a new link." }),
    });
    renderApp("/monitors/new");
    await fillIn("example.com");
    await submit();

    expect(
      await screen.findByText(/Please verify your email address before adding websites/),
    ).toBeVisible();
    expect(document.body.textContent).not.toContain("/api/");
    await userEvent.click(screen.getByRole("button", { name: "Send a new link" }));
    expect(await screen.findByText("Sent! Check your inbox.")).toBeVisible();
    expect(backend.requests.some((r) => r.path === "/api/auth/resend-verification")).toBe(true);
  });

  it("doesn't offer the form at the limit", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, monitorLimit: 1 }),
      "GET /api/monitors": () => json(200, [aMonitor()]),
    });
    renderApp("/monitors/new");

    expect(await screen.findByRole("heading", { name: "You've reached the limit" })).toBeVisible();
    expect(screen.queryByLabelText("Website address")).not.toBeInTheDocument();
  });

  it("is reached from the dashboard", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
    });
    const app = renderApp("/dashboard");
    await userEvent.click(await screen.findByRole("link", { name: "Add your first website" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Add a website" })).toBeVisible();
    expect(app.location()).toBe("/monitors/new");
  });
});
