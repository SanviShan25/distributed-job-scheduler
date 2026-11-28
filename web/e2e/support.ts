import { expect, type Locator, type Page } from "@playwright/test";

export const SAMPLE_WEBSITES = ["Example Domain", "PulseCheck test page"] as const;

/** From the home page, "Try the demo": a private demo account with the sample websites. */
export async function startDemo(page: Page): Promise<void> {
  await page.goto("/");
  await page.getByRole("button", { name: "Try the demo" }).click();
  // Generous: creating a demo is the first real work a cold JVM does.
  await expect(page).toHaveURL("/dashboard", { timeout: 30_000 });
  await expect(page.getByRole("heading", { level: 1, name: "Your websites" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Demo account" })).toContainText(
    "This is your private demo. It's deleted after 3 hours.",
  );
}

/**
 * Ends this page's demo if it still has one, which deletes it on the server. Tests call it when they
 * finish, so repeated runs against the same stack don't fill up the 20 demos it allows at once.
 */
export async function endDemo(page: Page): Promise<void> {
  const cookies = await page.context().cookies();
  const csrf = cookies.find((cookie) => cookie.name === "XSRF-TOKEN")?.value;
  if (!csrf || !cookies.some((cookie) => cookie.name === "PULSECHECK_SESSION")) return;
  await page.request.post("/api/auth/logout", { headers: { "X-XSRF-TOKEN": csrf } });
}

/** A website's card on the dashboard. */
export function websiteCard(page: Page, name: string): Locator {
  return page.getByRole("article", { name });
}

/**
 * The drawn response-time chart (recognised by its SVG title, not just any SVG: the loading spinner is one
 * too). It appears once the website has been checked; the worker does that within seconds of adding it,
 * and the page refreshes the chart every minute, so reload rather than wait.
 */
export async function waitForChart(page: Page): Promise<Locator> {
  const chart = page.getByRole("figure").filter({
    has: page.locator("svg title", { hasText: /^Response time, last (24 hours|7 days)$/ }),
  });
  await expect(async () => {
    await page.reload();
    await expect(chart).toBeVisible({ timeout: 5_000 });
  }).toPass({ timeout: 90_000, intervals: [2_000] });
  return chart;
}

/** Fails the test on anything the browser reports as a problem, e.g. a CSP violation or a script error. */
export function collectPageErrors(page: Page): string[] {
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(`page error: ${error.message}`));
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(`console error: ${message.text()}`);
  });
  return errors;
}
