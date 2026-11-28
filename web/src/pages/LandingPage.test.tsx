import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { fakeBackend, json, noContent } from "../test/fakeBackend";
import { demoVisitor, loggedOut, renderApp } from "../test/renderApp";

const demoOn = () => json(200, { demoEnabled: true, signupOpen: false });
const demoOff = () => json(200, { demoEnabled: false, signupOpen: true });

describe("LandingPage", () => {
  it("explains PulseCheck to someone who has never heard of it", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": demoOff });
    renderApp("/");

    expect(
      await screen.findByRole("heading", { level: 1, name: "Know when your website goes down" }),
    ).toBeVisible();
    expect(screen.getByText(/checks your website every minute and emails you/)).toBeVisible();

    const steps = within(screen.getByRole("region", { name: "How it works" })).getAllByRole(
      "listitem",
    );
    expect(steps.map((step) => within(step).getByRole("heading").textContent)).toEqual([
      "Step 1: Add your website",
      "Step 2: We check it around the clock",
      "Step 3: Get an email if it goes down",
    ]);
    const features = within(screen.getByRole("region", { name: "What you get" }));
    for (const name of [
      "Checks every minute",
      "Email alerts",
      "Uptime charts",
      "Private by default",
    ]) {
      expect(features.getByRole("heading", { name })).toBeVisible();
    }
    expect(
      screen.getByRole("img", { name: /Screenshot of the PulseCheck dashboard/ }),
    ).toBeVisible();
    expect(screen.getByRole("link", { name: "Source code on GitHub" })).toHaveAttribute(
      "href",
      "https://github.com/SanviShan25/distributed-job-scheduler",
    );
    expect(screen.getByRole("contentinfo")).toBeVisible();
  });

  it("sets a title, description and canonical URL for search engines", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": demoOff });
    renderApp("/");
    await screen.findByRole("heading", { level: 1 });

    expect(document.title).toBe("Website uptime monitoring with email alerts · PulseCheck");
    expect(document.querySelector('link[rel="canonical"]')).toHaveAttribute(
      "href",
      "http://localhost:5173/",
    );
    expect(document.querySelector('meta[property="og:url"]')).toHaveAttribute(
      "content",
      "http://localhost:5173/",
    );
  });

  it("without a demo, offers sign-up and log-in only", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": demoOff });
    renderApp("/");
    const main = within(await screen.findByRole("main"));

    expect(await main.findByRole("link", { name: "Create a free account" })).toBeVisible();
    expect(main.getByRole("link", { name: "Log in" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Try the demo" })).not.toBeInTheDocument();
  });

  it("with a demo and limited sign-ups, offers the demo and log-in, not sign-up", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut, "GET /api/config": demoOn });
    renderApp("/");

    expect(await screen.findByRole("button", { name: "Try the demo" })).toBeVisible();
    expect(
      screen.getByText(/private to you, needs no sign-up and is deleted after 3 hours/),
    ).toBeVisible();
    expect(screen.queryByRole("link", { name: "Create a free account" })).not.toBeInTheDocument();
    expect(within(screen.getByRole("main")).getByRole("link", { name: "Log in" })).toBeVisible();
  });

  it("Try the demo starts a private demo and opens the dashboard", async () => {
    let demoStarted = false;
    const backend = fakeBackend({
      "GET /api/auth/me": () => (demoStarted ? json(200, demoVisitor) : noContent()),
      "GET /api/config": demoOn,
      "POST /api/auth/demo": () => {
        demoStarted = true;
        return json(200, demoVisitor);
      },
      "GET /api/monitors": () => json(200, []),
    });
    const app = renderApp("/");

    await userEvent.click(await screen.findByRole("button", { name: "Try the demo" }));

    expect(await screen.findByRole("heading", { level: 1, name: "Your websites" })).toBeVisible();
    expect(app.location()).toBe("/dashboard");
    expect(screen.getByRole("region", { name: "Demo account" })).toHaveTextContent(
      "This is your private demo. It's deleted after 3 hours.",
    );
    const start = backend.apiRequests().find((r) => r.path === "/api/auth/demo");
    expect(start?.method).toBe("POST");
    expect(start?.headers.get("X-XSRF-TOKEN")).toMatch(/^csrf-/);
  });

  it("says so when the demo is busy", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "GET /api/config": demoOn,
      "POST /api/auth/demo": () =>
        json(
          503,
          {
            title: "The demo is busy",
            status: 503,
            detail: "The demo is busy. Please try again in a few minutes.",
            code: "demo_busy",
          },
          { "Retry-After": "300" },
        ),
    });
    renderApp("/");

    const button = await screen.findByRole("button", { name: "Try the demo" });
    await userEvent.click(button);
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The demo is busy. Please try again in a few minutes.");
    expect(button).toHaveAccessibleDescription(
      "The demo is busy. Please try again in a few minutes.",
    );
  });

  it("explains the per-IP limit in plain words", async () => {
    fakeBackend({
      "GET /api/auth/me": loggedOut,
      "GET /api/config": demoOn,
      "POST /api/auth/demo": () =>
        json(
          429,
          { title: "Too Many Requests", status: 429, detail: "Too many demo logins." },
          { "Retry-After": "600" },
        ),
    });
    renderApp("/");

    await userEvent.click(await screen.findByRole("button", { name: "Try the demo" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Too many attempts. Please try again in 10 minutes.",
    );
  });

  it("works even if the site's settings can't be loaded", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut }); // GET /api/config: 404
    renderApp("/");
    expect(await screen.findByRole("link", { name: "Create a free account" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Try the demo" })).not.toBeInTheDocument();
  });
});
