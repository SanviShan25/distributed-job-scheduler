import { execFileSync } from "node:child_process";
import { copyFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { expect, test, type Page } from "@playwright/test";
import { startDemo, waitForChart, websiteCard } from "./support";

/**
 * Captures the images used by the README (docs/images/) and the web app (the landing page's dashboard
 * screenshot and the link-preview image). Run with `npm run e2e:screenshots` against the e2e stack.
 *
 * A demo is brand new, so its charts would hold a single check. The screenshots use 24 hours of sample
 * check history (with one short outage) written straight into the e2e stack's database for this demo
 * only. The README says the images show sample data.
 */

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../..");
const docsImages = resolve(repo, "docs/images");
const webAssets = resolve(repo, "web/src/assets");
const webPublic = resolve(repo, "web/public");

// The screenshots hide the demo banner (it's about the visitor's session, not the product), which needs a
// style the strict CSP would block.
test.use({ bypassCSP: true });

test("capture the dashboard and website screenshots", async ({ page }) => {
  await startDemo(page);
  // A third website, so the dashboard shows a full row.
  await page.goto("/monitors/new");
  await page.getByLabel("Website address").fill("example.org");
  await page.getByLabel("Name").fill("Example Org");
  await page.getByRole("button", { name: "Add website" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Example Org" })).toBeVisible();

  const me = (await (await page.request.get("/api/auth/me")).json()) as { id: string };
  seedSampleHistory(me.id);
  mkdirSync(docsImages, { recursive: true });
  mkdirSync(webAssets, { recursive: true });

  // Dashboard, with the history's uptime and response times. Cropped below the cards.
  await page.goto("/dashboard");
  await hideBanner(page);
  for (const name of ["Example Domain", "Example Org", "PulseCheck test page"]) {
    await expect(websiteCard(page, name)).toContainText("%");
    await expect(websiteCard(page, name)).toContainText(/\d ms/);
  }
  await page.setViewportSize({ width: 1280, height: 480 });
  // Just the content column (the page centres it with wide margins), so it stays legible when scaled down.
  await page.screenshot({
    path: resolve(docsImages, "dashboard.png"),
    animations: "disabled",
    clip: { x: 120, y: 0, width: 1040, height: 480 },
  });
  copyFileSync(resolve(docsImages, "dashboard.png"), resolve(webAssets, "dashboard.png"));

  // The link-preview image: 1200 × 630 is what most sites show without cropping.
  await page.setViewportSize({ width: 1200, height: 630 });
  await page.screenshot({ path: resolve(webPublic, "og-image.png"), animations: "disabled" });

  // A website's page: status, uptime, chart and the outage (not the long table of checks below).
  await page.setViewportSize({ width: 1280, height: 1230 });
  await websiteCard(page, "PulseCheck test page").getByRole("link").click();
  await waitForChart(page);
  await hideBanner(page);
  await expect(page.getByText("Down for 20 minutes")).toBeVisible();
  await page.screenshot({
    path: resolve(docsImages, "monitor-details.png"),
    animations: "disabled",
  });
});

async function hideBanner(page: Page): Promise<void> {
  await page.addStyleTag({ content: '[aria-label="Demo account"] { display: none !important; }' });
}

/**
 * 24 hours of checks every 5 minutes for this demo's websites, response times varying through the day,
 * and a 20-minute outage (HTTP 503) six hours ago on "PulseCheck test page", with its incident.
 */
function seedSampleHistory(userId: string): void {
  if (!/^[0-9a-f-]{36}$/.test(userId)) throw new Error(`unexpected user id ${userId}`);
  const sql = `
    with m as (
      select id, name from monitors where user_id = '${userId}'
    ), slots as (
      select m.id, m.name, t,
             m.name = 'PulseCheck test page'
               and t >= now() - interval '6 hours' and t < now() - interval '5 hours 40 minutes' as down
      from m, generate_series(now() - interval '24 hours', now() - interval '6 minutes', interval '5 minutes') t
    )
    insert into check_results (monitor_id, checked_at, up, status_code, response_ms, error)
    select id, t, not down, case when down then 503 else 200 end,
           (case name when 'Example Domain' then 140 when 'Example Org' then 95 else 260 end + 45 * sin(extract(epoch from t) / 5400.0) + random() * 35)::int,
           case when down then 'HTTP 503' end
    from slots;

    insert into incidents (id, monitor_id, started_at, resolved_at, cause)
    select gen_random_uuid(), id, now() - interval '6 hours', now() - interval '5 hours 40 minutes', 'HTTP 503'
    from monitors where user_id = '${userId}' and name = 'PulseCheck test page';
  `;
  execFileSync(resolve(here, "stack/stack.sh"), ["psql"], {
    input: sql,
    stdio: ["pipe", "inherit", "inherit"],
  });
}
