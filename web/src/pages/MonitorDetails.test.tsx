import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Check, Incident, Page, ResponseTimeBucket } from "../monitors/history";
import type { Monitor, Recipient } from "../monitors/monitors";
import { fakeBackend, json, noContent, problem, type Handler } from "../test/fakeBackend";
import { aMonitor } from "../test/monitors";
import { alice, loggedIn, renderApp } from "../test/renderApp";

function page<T>(content: T[], pageNumber = 0, totalElements = content.length, size = 20): Page<T> {
  return {
    content,
    page: pageNumber,
    size,
    totalElements,
    totalPages: Math.ceil(totalElements / size),
  };
}

function buckets(count: number, fill: (i: number) => Partial<ResponseTimeBucket> = () => ({})) {
  const start = Date.parse("2026-11-27T00:00:00Z");
  return Array.from({ length: count }, (_, i) => ({
    start: new Date(start + i * 300_000).toISOString(),
    checks: 0,
    failures: 0,
    avgResponseMs: null,
    maxResponseMs: null,
    ...fill(i),
  }));
}

function recipient(overrides: Partial<Recipient>): Recipient {
  return {
    id: crypto.randomUUID(),
    email: "someone@example.com",
    status: "PENDING",
    createdAt: "2026-11-26T10:00:00Z",
    confirmedAt: null,
    unsubscribedAt: null,
    ...overrides,
  };
}

/** A backend with one monitor and its history; any handler can be replaced. */
function backendFor(monitor: Monitor, handlers: Record<string, Handler> = {}) {
  const base = `/api/monitors/${monitor.id}`;
  return fakeBackend({
    "GET /api/auth/me": loggedIn(),
    "GET /api/monitors": () => json(200, [monitor]),
    [`GET ${base}`]: () => json(200, monitor),
    [`GET ${base}/response-times`]: () =>
      json(200, { range: "24h", bucketSeconds: 300, buckets: buckets(288) }),
    [`GET ${base}/incidents`]: () => json(200, page<Incident>([], 0, 0, 10)),
    [`GET ${base}/checks`]: () => json(200, page<Check>([])),
    ...handlers,
  });
}

describe("Monitor detail page: uptime and chart", () => {
  it("shows uptime for 24 hours, 7 days and 30 days and the average response time", async () => {
    const shop = aMonitor({
      name: "Shop",
      uptime: {
        last24h: 99.5,
        last7d: 99.95,
        last30d: null,
        avgResponseMs24h: 245,
        checks24h: 288,
      },
    });
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    const uptime = await screen.findByRole("region", { name: "Uptime" });
    expect(within(uptime).getByText("Last 24 hours").nextSibling).toHaveTextContent("99.5%");
    expect(within(uptime).getByText("Last 7 days").nextSibling).toHaveTextContent("99.95%");
    expect(within(uptime).getByText("Last 30 days").nextSibling).toHaveTextContent("No checks yet");
    expect(
      within(uptime).getByText("Average response time (24 hours)").nextSibling,
    ).toHaveTextContent("245 ms");
  });

  it("opens the website in a new tab without giving it access to this page", async () => {
    const shop = aMonitor({ url: "https://shop.example.com/" });
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    const link = await screen.findByRole("link", { name: /shop\.example\.com.*new tab/ });
    expect(link).toHaveAttribute("href", "https://shop.example.com/");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  it("summarises the response times and switches between 24 hours and 7 days", async () => {
    const shop = aMonitor();
    const backend = backendFor(shop, {
      [`GET /api/monitors/${shop.id}/response-times`]: (request) =>
        request.path.endsWith("range=7d")
          ? json(200, {
              range: "7d",
              bucketSeconds: 3600,
              buckets: buckets(168, () => ({ checks: 12, avgResponseMs: 400, maxResponseMs: 900 })),
            })
          : json(200, {
              range: "24h",
              bucketSeconds: 300,
              buckets: buckets(288, (i) =>
                i === 5 ? { checks: 5, failures: 2, avgResponseMs: 300, maxResponseMs: 310 } : {},
              ),
            }),
    });
    renderApp(`/monitors/${shop.id}`);

    expect(
      await screen.findByText(/^Average 300 ms over the last 24 hours\..*2 checks failed\.$/),
    ).toBeVisible();
    const day = screen.getByRole("button", { name: "24 hours" });
    const week = screen.getByRole("button", { name: "7 days" });
    expect(day).toHaveAttribute("aria-pressed", "true");

    await userEvent.click(week);
    expect(week).toHaveAttribute("aria-pressed", "true");
    expect(
      await screen.findByText(/^Average 400 ms over the last 7 days\..*No failed checks\.$/),
    ).toBeVisible();
    expect(backend.requests.map((r) => r.path)).toContain(
      `/api/monitors/${shop.id}/response-times?range=7d`,
    );
  });

  it("says so when there are no checks to chart yet", async () => {
    const shop = aMonitor();
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    expect(await screen.findByText("No checks in the last 24 hours yet.")).toBeVisible();
  });
});

describe("Monitor detail page: outages and checks", () => {
  it("lists outages with their duration and cause in plain words", async () => {
    const shop = aMonitor({ status: "DOWN" });
    backendFor(shop, {
      [`GET /api/monitors/${shop.id}/incidents`]: () =>
        json(
          200,
          page<Incident>(
            [
              {
                id: "a",
                startedAt: new Date(Date.now() - 5 * 60_000).toISOString(),
                resolvedAt: null,
                ongoing: true,
                durationSeconds: 300,
                cause: "timed out after 10s",
              },
              {
                id: "b",
                startedAt: "2026-11-20T08:00:00Z",
                resolvedAt: "2026-11-20T08:12:00Z",
                ongoing: false,
                durationSeconds: 720,
                cause: "HTTP 503",
              },
            ],
            0,
            2,
            10,
          ),
        ),
    });
    renderApp(`/monitors/${shop.id}`);

    const outages = await screen.findByRole("region", { name: "Outages" });
    expect(await within(outages).findByText("Down for 5 minutes so far")).toBeVisible();
    expect(within(outages).getByText("Ongoing")).toBeVisible();
    expect(
      within(outages).getByText("The website took longer than 10 seconds to respond."),
    ).toBeVisible();
    expect(within(outages).getByText("Down for 12 minutes")).toBeVisible();
    expect(
      within(outages).getByText("The website said it was temporarily unavailable."),
    ).toBeVisible();
    expect(outages).not.toHaveTextContent("503");
    expect(within(outages).getAllByText(/Back up/)).toHaveLength(1);
  });

  it("says when there have been no outages", async () => {
    const shop = aMonitor();
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    expect(await screen.findByText("No outages so far.")).toBeVisible();
  });

  it("shows recent checks in a table and pages through older ones", async () => {
    const shop = aMonitor();
    const check = (minutesAgo: number, up: boolean): Check => ({
      checkedAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
      up,
      statusCode: up ? 200 : null,
      responseMs: up ? 245 : 10_000,
      error: up ? null : "connection refused",
    });
    const backend = backendFor(shop, {
      [`GET /api/monitors/${shop.id}/checks`]: (request) =>
        request.path.includes("page=1")
          ? json(200, page([check(120, true)], 1, 21))
          : json(200, page([check(2, true), check(7, false)], 0, 21)),
    });
    renderApp(`/monitors/${shop.id}`);

    const table = await screen.findByRole("table", { name: "Recent checks, newest first" });
    const rows = await within(table).findAllByRole("row");
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent(/2 minutes ago.*Up.*245 ms.*Responded normally/);
    expect(rows[2]).toHaveTextContent(/7 minutes ago.*Failed.*—.*refused the connection/);

    const pager = screen.getByRole("navigation", { name: "Recent checks pages" });
    expect(within(pager).getByText("Page 1 of 2")).toBeVisible();
    const newer = within(pager).getByRole("button", { name: "Newer" });
    expect(newer).toHaveAttribute("aria-disabled", "true");

    const older = within(pager).getByRole("button", { name: "Older" });
    await userEvent.click(older);
    expect(await within(pager).findByText("Page 2 of 2")).toBeVisible();
    expect(await within(table).findByText("2 hours ago")).toBeVisible();
    expect(older).toHaveAttribute("aria-disabled", "true");
    expect(older).toHaveFocus();
    expect(backend.requests.map((r) => r.path)).toContain(
      `/api/monitors/${shop.id}/checks?page=1&size=20`,
    );
  });
});

describe("Monitor detail page: alert emails", () => {
  it("lists the owner and extra recipients with their status in plain words", async () => {
    const shop = aMonitor({
      recipients: [
        recipient({ email: "pending@example.com", status: "PENDING" }),
        recipient({ email: "confirmed@example.com", status: "CONFIRMED" }),
        recipient({ email: "left@example.com", status: "UNSUBSCRIBED" }),
      ],
    });
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    const items = within(card).getAllByRole("listitem");
    expect(items[0]).toHaveTextContent(`${alice.email}You (account owner)Receiving alerts`);
    expect(items[1]).toHaveTextContent("pending@example.comWaiting for confirmation");
    expect(items[2]).toHaveTextContent("confirmed@example.comReceiving alerts");
    expect(items[3]).toHaveTextContent("left@example.comUnsubscribed");
    expect(card).not.toHaveTextContent(/PENDING|CONFIRMED|UNSUBSCRIBED/);
    // Only waiting people can be sent a new confirmation.
    expect(
      within(card).getAllByRole("button", { name: /^Resend confirmation email to/ }),
    ).toHaveLength(1);
    expect(within(card).getByText(/You can add 3 more\./)).toBeVisible();
  });

  it("adds a recipient, who then waits for confirmation", async () => {
    const shop = aMonitor();
    const backend = backendFor(shop, {
      [`POST /api/monitors/${shop.id}/recipients`]: (request) =>
        json(201, recipient({ email: (request.body as { email: string }).email })),
    });
    renderApp(`/monitors/${shop.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    await userEvent.type(within(card).getByLabelText("Add someone"), "  Bob@Example.com ");
    await userEvent.click(within(card).getByRole("button", { name: "Add recipient" }));

    expect(
      await within(card).findByText(/We've emailed bob@example.com a link to confirm/),
    ).toBeVisible();
    expect(within(card).getAllByRole("listitem")[1]).toHaveTextContent(
      "bob@example.comWaiting for confirmation",
    );
    expect(within(card).getByLabelText("Add someone")).toHaveValue("");
    expect(backend.requests.find((r) => r.method === "POST")?.body).toEqual({
      email: "bob@example.com",
    });
  });

  it("explains refusals in plain words", async () => {
    const shop = aMonitor();
    backendFor(shop, {
      [`POST /api/monitors/${shop.id}/recipients`]: () =>
        problem(409, "bob@example.com is already a recipient (PENDING)"),
    });
    renderApp(`/monitors/${shop.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    const input = within(card).getByLabelText("Add someone");
    await userEvent.click(within(card).getByRole("button", { name: "Add recipient" }));
    expect(input).toHaveAccessibleDescription(/Enter the email address of the person to add\./);

    await userEvent.type(input, "bob@example.com");
    await userEvent.click(within(card).getByRole("button", { name: "Add recipient" }));
    expect(await within(card).findByText("This person is already on the list.")).toBeVisible();
    expect(card).not.toHaveTextContent("PENDING");
  });

  it("hides the form once 5 people are receiving or waiting", async () => {
    const shop = aMonitor({
      recipients: [
        ...Array.from({ length: 5 }, (_, i) =>
          recipient({ email: `p${String(i)}@example.com`, status: "CONFIRMED" }),
        ),
        recipient({ email: "left@example.com", status: "UNSUBSCRIBED" }),
      ],
    });
    backendFor(shop);
    renderApp(`/monitors/${shop.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    expect(within(card).getByText(/You've added 5 people/)).toBeVisible();
    expect(within(card).queryByLabelText("Add someone")).not.toBeInTheDocument();
  });

  it("removes a recipient and resends a confirmation", async () => {
    const waiting = recipient({ email: "wait@example.com", status: "PENDING" });
    const gone = recipient({ email: "gone@example.com", status: "CONFIRMED" });
    const shop = aMonitor({ recipients: [waiting, gone] });
    const backend = backendFor(shop, {
      [`POST /api/monitors/${shop.id}/recipients/${waiting.id}/resend`]: () => json(200, waiting),
      [`DELETE /api/monitors/${shop.id}/recipients/${gone.id}`]: () => noContent(),
    });
    renderApp(`/monitors/${shop.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    await userEvent.click(
      within(card).getByRole("button", { name: "Resend confirmation email to wait@example.com" }),
    );
    expect(
      await within(card).findByText(/We've sent a new confirmation email to wait@example.com/),
    ).toBeVisible();

    await userEvent.click(within(card).getByRole("button", { name: "Remove gone@example.com" }));
    expect(
      await within(card).findByText("gone@example.com won't get alerts for this website any more."),
    ).toBeVisible();
    expect(within(card).queryByText("gone@example.com")).not.toBeInTheDocument();
    expect(backend.requests.filter((r) => r.method === "DELETE")).toHaveLength(1);
  });

  it("lets the owner turn their own alerts back on after unsubscribing", async () => {
    let current = aMonitor({ notifyOwner: false });
    const backend = backendFor(current, {
      [`GET /api/monitors/${current.id}`]: () => json(200, current),
      [`PATCH /api/monitors/${current.id}`]: () => {
        current = { ...current, notifyOwner: true, uptime: null };
        return json(200, current);
      },
    });
    renderApp(`/monitors/${current.id}`);

    const card = await screen.findByRole("region", { name: "Alert emails" });
    expect(within(card).getAllByRole("listitem")[0]).toHaveTextContent("Unsubscribed");
    await userEvent.click(within(card).getByRole("button", { name: "Send me alerts again" }));

    await waitFor(() => {
      expect(within(card).getAllByRole("listitem")[0]).toHaveTextContent("Receiving alerts");
    });
    expect(backend.requests.find((r) => r.method === "PATCH")?.body).toEqual({
      notifyOwner: true,
    });
  });
});
