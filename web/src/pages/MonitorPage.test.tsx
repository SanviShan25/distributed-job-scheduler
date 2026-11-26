import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import type { Monitor } from "../monitors/monitors";
import { fakeBackend, json, noContent, problem } from "../test/fakeBackend";
import { aMonitor } from "../test/monitors";
import { loggedIn, loggedOut, renderApp } from "../test/renderApp";

const ID = "3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f";

describe("MonitorPage (the 'View monitor' link in alert emails)", () => {
  it("shows the monitor's name, status and address", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      [`GET /api/monitors/${ID}`]: () =>
        json(200, {
          id: ID,
          name: "Shop",
          url: "https://shop.example.com/",
          intervalSeconds: 300,
          enabled: true,
          status: "DOWN",
          lastCheckedAt: "2026-11-25T08:00:00Z",
        }),
    });
    renderApp(`/monitors/${ID}`);

    expect(await screen.findByRole("heading", { level: 1, name: "Shop" })).toBeVisible();
    expect(screen.getByText("Down")).toBeVisible();
    expect(screen.getByText("https://shop.example.com/")).toBeVisible();
    expect(screen.getByText("Every 5 minutes")).toBeVisible();
    expect(document.title).toContain("Shop");
  });

  it("says when the monitor does not exist (or belongs to someone else)", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      [`GET /api/monitors/${ID}`]: () => problem(404, `Monitor ${ID} not found`),
    });
    renderApp(`/monitors/${ID}`);

    expect(await screen.findByRole("heading", { name: "Website not found" })).toBeVisible();
    expect(screen.getByRole("link", { name: "Go to your websites" })).toHaveAttribute(
      "href",
      "/dashboard",
    );
  });

  it("does not ask the backend about ids that cannot exist", async () => {
    const backend = fakeBackend({ "GET /api/auth/me": loggedIn() });
    renderApp("/monitors/not-a-uuid");

    expect(await screen.findByRole("heading", { name: "Website not found" })).toBeVisible();
    expect(backend.apiRequests().map((r) => r.path)).toEqual(["/api/auth/me"]);
  });

  it("sends logged-out visitors to log in and back", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    const app = renderApp(`/monitors/${ID}`);

    expect(await screen.findByRole("heading", { name: /log in/i })).toBeVisible();
    expect(app.location()).toBe(`/login?next=${encodeURIComponent(`/monitors/${ID}`)}`);
  });
});

describe("MonitorPage actions", () => {
  /** A backend holding one monitor that PATCH, pause and resume change like the real one. */
  function backendWith(initial: Monitor) {
    let current = initial;
    const backend = fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "GET /api/monitors": () => json(200, [current]),
      [`GET /api/monitors/${initial.id}`]: () => json(200, current),
      [`PATCH /api/monitors/${initial.id}`]: (request) => {
        current = { ...current, ...(request.body as Partial<Monitor>), uptime: null };
        return json(200, current);
      },
      [`POST /api/monitors/${initial.id}/pause`]: () => {
        current = { ...current, enabled: false, status: "PAUSED", uptime: null };
        return json(200, current);
      },
      [`POST /api/monitors/${initial.id}/resume`]: () => {
        current = { ...current, enabled: true, status: "UNKNOWN", uptime: null };
        return json(200, current);
      },
      [`DELETE /api/monitors/${initial.id}`]: () => noContent(),
    });
    return backend;
  }

  it("edits only what changed, in a labelled dialog", async () => {
    const shop = aMonitor({ name: "Shop", url: "https://shop.example.com/" });
    const backend = backendWith(shop);
    renderApp(`/monitors/${shop.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const dialog = screen.getByRole("dialog", { name: "Edit Shop" });
    expect(within(dialog).getByLabelText("Website address")).toHaveValue(
      "https://shop.example.com/",
    );
    expect(within(dialog).getByLabelText("Every 5 minutes")).toBeChecked();

    const name = within(dialog).getByLabelText(/^Name/);
    await userEvent.clear(name);
    await userEvent.type(name, "Online shop");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save changes" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Online shop" })).toBeVisible();
    expect(screen.getByText("Your changes have been saved.")).toBeVisible();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(backend.requests.find((r) => r.method === "PATCH")?.body).toEqual({
      name: "Online shop",
    });
  });

  it("closes the edit dialog on Escape and returns focus to the Edit button", async () => {
    const shop = aMonitor({ name: "Shop" });
    const backend = backendWith(shop);
    renderApp(`/monitors/${shop.id}`);

    const edit = await screen.findByRole("button", { name: "Edit" });
    await userEvent.click(edit);
    expect(screen.getByRole("dialog", { name: "Edit Shop" })).toBeVisible();
    await userEvent.type(screen.getByLabelText("Website address"), "{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(edit).toHaveFocus();
    expect(backend.requests.some((r) => r.method === "PATCH")).toBe(false);
  });

  it("shows the backend's reason in plain words when an edit is refused", async () => {
    const shop = aMonitor({ name: "Shop" });
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      [`GET /api/monitors/${shop.id}`]: () => json(200, shop),
      [`PATCH /api/monitors/${shop.id}`]: () =>
        problem(400, "This URL can't be monitored: 10.0.0.1 is a private or reserved address"),
    });
    renderApp(`/monitors/${shop.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Edit" }));
    const address = screen.getByLabelText("Website address");
    await userEvent.clear(address);
    await userEvent.type(address, "http://10.0.0.1");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

    expect(await screen.findByText(/private or local network/)).toBeVisible();
    expect(screen.getByRole("dialog")).toBeVisible();
  });

  it("pauses and resumes checks", async () => {
    const shop = aMonitor({ name: "Shop" });
    const backend = backendWith(shop);
    renderApp(`/monitors/${shop.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Pause checks" }));
    expect(await screen.findByRole("button", { name: "Resume checks" })).toBeVisible();
    expect(screen.getAllByText("Paused").length).toBeGreaterThan(0);

    await userEvent.click(screen.getByRole("button", { name: "Resume checks" }));
    expect(await screen.findByRole("button", { name: "Pause checks" })).toBeVisible();
    expect(backend.requests.filter((r) => r.method === "POST").map((r) => r.path)).toEqual([
      `/api/monitors/${shop.id}/pause`,
      `/api/monitors/${shop.id}/resume`,
    ]);
  });

  it("asks before deleting, with Cancel focused, then goes to the dashboard", async () => {
    const shop = aMonitor({ name: "example.com" });
    const backend = backendWith(shop);
    const app = renderApp(`/monitors/${shop.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Delete" }));
    const dialog = screen.getByRole("dialog", { name: "Delete example.com and its history?" });
    expect(dialog).toHaveAccessibleDescription(/can't be undone/);
    expect(within(dialog).getByRole("button", { name: "Cancel" })).toHaveFocus();

    await userEvent.click(within(dialog).getByRole("button", { name: "Delete website" }));

    expect(await screen.findByText("example.com and its history have been deleted.")).toBeVisible();
    expect(app.location()).toBe("/dashboard");
    expect(backend.requests.some((r) => r.method === "DELETE")).toBe(true);
  });

  it("deletes nothing when cancelled", async () => {
    const shop = aMonitor({ name: "Shop" });
    const backend = backendWith(shop);
    renderApp(`/monitors/${shop.id}`);

    await userEvent.click(await screen.findByRole("button", { name: "Delete" }));
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Delete" })).toHaveFocus();
    expect(backend.requests.some((r) => r.method === "DELETE")).toBe(false);
  });
});
