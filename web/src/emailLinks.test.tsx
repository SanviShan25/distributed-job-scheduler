import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { fakeBackend, json } from "./test/fakeBackend";
import { aMonitor } from "./test/monitors";
import { loggedIn, renderApp } from "./test/renderApp";

/**
 * Every link the backend puts in an email (EmailComposer) must open a real page. The alert-recipient
 * confirm/unsubscribe links are pages rendered by the backend itself, so they are not listed here.
 */
const EMAIL_LINKS = [
  "/verify-email?token=t", // verification
  "/reset-password?token=t", // password reset
  "/forgot-password", // "you already have an account"
  "/monitors/3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f", // "View monitor" in DOWN/UP alerts
];

describe("links in emails", () => {
  it.each(EMAIL_LINKS)("%s opens a page", async (path) => {
    fakeBackend({
      "GET /api/auth/me": loggedIn(),
      "POST /api/auth/verify": () => json(200, { message: "Your email address is verified." }),
      "GET /api/monitors/3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f": () =>
        json(
          200,
          aMonitor({
            id: "3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f",
            name: "Shop",
            lastCheckedAt: null,
          }),
        ),
    });
    renderApp(path);

    expect(await screen.findByRole("heading", { level: 1 })).toBeVisible();
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "Page not found" })).not.toBeInTheDocument();
    });
  });
});
