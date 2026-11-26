import { act, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Monitor } from "../monitors/monitors";
import { fakeBackend, json, problem } from "../test/fakeBackend";
import { alice, loggedIn, renderApp } from "../test/renderApp";

let nextId = 1;

function monitor(overrides: Partial<Monitor>): Monitor {
  const id = `00000000-0000-4000-8000-${String(nextId++).padStart(12, "0")}`;
  return {
    id,
    name: "Site",
    url: "https://site.example.com/",
    intervalSeconds: 300,
    enabled: true,
    status: "UP",
    lastCheckedAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    notifyOwner: true,
    recipients: [],
    createdAt: "2026-11-20T10:00:00Z",
    updatedAt: "2026-11-20T10:00:00Z",
    uptime: { last24h: 100, last7d: null, last30d: null, avgResponseMs24h: 245, checks24h: 288 },
    ...overrides,
  };
}

/** Turns off automatic retries so error states show at once (they wait 1 s, 2 s otherwise). */
function noRetries(app: ReturnType<typeof renderApp>) {
  const defaults = app.queryClient.getDefaultOptions();
  app.queryClient.setDefaultOptions({
    ...defaults,
    queries: { ...defaults.queries, retry: false },
  });
}

function cards() {
  return screen.getAllByRole("article");
}

describe("DashboardPage", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("shows each website with its status, uptime, response time and last check, down ones first", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () =>
        json(200, [
          monitor({ name: "blog", url: "https://blog.example.com/" }),
          monitor({
            name: "Shop",
            url: "https://shop.example.com/",
            status: "DOWN",
            uptime: {
              last24h: 97.5,
              last7d: null,
              last30d: null,
              avgResponseMs24h: 1830,
              checks24h: 40,
            },
          }),
          monitor({ name: "Api", status: "UNKNOWN", lastCheckedAt: null, uptime: null }),
          monitor({ name: "Archive", enabled: false, status: "PAUSED" }),
        ]),
    });
    renderApp("/dashboard");

    expect(await screen.findByRole("link", { name: "Shop" })).toBeVisible();
    expect(cards().map((card) => within(card).getByRole("heading").textContent)).toEqual([
      "Shop",
      "Api",
      "Archive",
      "blog",
    ]);

    const card = (name: string) => screen.getByRole("article", { name });
    const [shop, api, archive, blog] = [card("Shop"), card("Api"), card("Archive"), card("blog")];
    expect(within(shop).getByText("Down")).toBeVisible();
    expect(within(shop).getByText("shop.example.com")).toBeVisible();
    expect(within(shop).getByText("97.5%")).toBeVisible();
    expect(within(shop).getByText("1.8 s")).toBeVisible();
    expect(within(shop).getByRole("link", { name: "Shop" })).toHaveAttribute(
      "href",
      expect.stringMatching(/^\/monitors\/[0-9a-f-]{36}$/),
    );

    expect(within(api).getByText("Checking")).toBeVisible();
    expect(within(api).getAllByText("No data yet")).toHaveLength(2);
    expect(within(api).getByText("First check on its way…")).toBeVisible();

    expect(within(archive).getByText("Paused")).toBeVisible();
    expect(within(archive).getByText(/Paused · last checked/)).toBeVisible();

    expect(within(blog).getByText("Up")).toBeVisible();
    expect(within(blog).getByText("100%")).toBeVisible();
    expect(within(blog).getByText("245 ms")).toBeVisible();
    expect(within(blog).getByText("2 minutes ago")).toBeVisible();

    expect(screen.getByText(/4 of 10 websites/)).toBeVisible();
    expect(screen.getByText("Updated just now")).toBeVisible();
    expect(screen.getByRole("link", { name: "Add website" })).toHaveAttribute(
      "href",
      "/monitors/new",
    );
  });

  it("never shows internal states or status codes", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () =>
        json(200, [
          monitor({ name: "One", status: "UNKNOWN" }),
          monitor({ name: "Two", status: "DOWN" }),
        ]),
    });
    renderApp("/dashboard");

    await screen.findByRole("link", { name: "One" });
    expect(document.body.textContent).not.toMatch(/UNKNOWN|PAUSED|SCHEDULED|DEAD|HTTP \d/);
  });

  it("invites the user to add their first website", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, []),
    });
    renderApp("/dashboard");

    expect(await screen.findByRole("heading", { name: "Add your first website" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Add your first website" })).toHaveAttribute(
      "href",
      "/monitors/new",
    );
  });

  it("disables adding at the limit and says why", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn({ ...alice, monitorLimit: 2 }),
      "GET /api/monitors": () => json(200, [monitor({ name: "A" }), monitor({ name: "B" })]),
    });
    renderApp("/dashboard");

    const button = await screen.findByRole("button", { name: "Add website" });
    expect(button).toBeDisabled();
    expect(button).toHaveAccessibleDescription(
      "You've reached the limit of 2 websites. Delete one to add another.",
    );
    expect(screen.queryByRole("link", { name: "Add website" })).not.toBeInTheDocument();
    expect(screen.getByText(/2 of 2 websites/)).toBeVisible();
  });

  it("shows placeholders while loading", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => new Promise<Response>(() => undefined),
    });
    renderApp("/dashboard");

    expect(await screen.findByText("Loading your websites…")).toHaveAttribute("role", "status");
  });

  it("offers to try again when the websites can't be loaded", async () => {
    let fail = true;
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () =>
        fail ? problem(500, "boom") : json(200, [monitor({ name: "Back" })]),
    });
    const app = renderApp("/dashboard");
    noRetries(app);

    expect(await screen.findByText("We couldn't load your websites")).toBeVisible();
    expect(screen.getByText(/Something went wrong on our side/)).toBeVisible();
    expect(document.body.textContent).not.toContain("boom");

    fail = false;
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(await screen.findByRole("link", { name: "Back" })).toBeVisible();
  });

  it("refreshes every 30 seconds while the tab is visible, and not while it is hidden", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, [monitor({ name: "Shop" })]),
    });
    renderApp("/dashboard");
    await screen.findByRole("link", { name: "Shop" });
    // Counted from here: StrictMode (tests and development only) aborts and repeats the first fetch.
    const start = backend.requests.length;
    const listRequests = () =>
      backend.requests.slice(start).filter((r) => r.path === "/api/monitors").length;

    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(listRequests()).toBe(1);

    setVisibility("hidden");
    try {
      await act(() => vi.advanceTimersByTimeAsync(120_000));
      expect(listRequests()).toBe(1);
    } finally {
      setVisibility("visible");
    }
    // Coming back to the tab refreshes straight away (the data is old by now).
    await act(() => vi.advanceTimersByTimeAsync(0));
    expect(listRequests()).toBe(2);
  });
});

function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, "visibilityState", { configurable: true, get: () => state });
  document.dispatchEvent(new Event("visibilitychange", { bubbles: true }));
}
