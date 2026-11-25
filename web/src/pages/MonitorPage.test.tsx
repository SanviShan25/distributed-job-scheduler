import { screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, problem } from "../test/fakeBackend";
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
