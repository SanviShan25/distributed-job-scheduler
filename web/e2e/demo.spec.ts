import { expect, test } from "@playwright/test";
import {
  collectPageErrors,
  endDemo,
  SAMPLE_WEBSITES,
  startDemo,
  waitForChart,
  websiteCard,
} from "./support";

test.describe("the demo", () => {
  test.afterEach(async ({ page }) => {
    await endDemo(page);
  });

  test("a visitor tries the demo, adds a website, looks at its chart, pauses, resumes and deletes it", async ({
    page,
  }) => {
    const errors = collectPageErrors(page);

    // Landing page → Try the demo → dashboard with the sample websites.
    await startDemo(page);
    for (const name of SAMPLE_WEBSITES) {
      await expect(websiteCard(page, name)).toBeVisible();
    }
    await expect(page.getByRole("region", { name: "Demo account" })).toContainText(
      /Time left: 2 hours 5\d minutes|Time left: 3 hours/,
    );

    // Add a website.
    await page.getByRole("link", { name: "Add website" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Add a website" })).toBeVisible();
    await page.getByLabel("Website address").fill("example.org");
    await page.getByLabel("Name").fill("Example Org");
    await page.getByRole("button", { name: "Add website" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Example Org" })).toBeVisible();
    const detailsUrl = page.url();

    // It's on the dashboard.
    await page.getByRole("link", { name: "PulseCheck" }).click();
    await expect(websiteCard(page, "Example Org")).toBeVisible();
    await expect(page.getByText("3 of 3 websites")).toBeVisible();
    await expect(page.getByRole("link", { name: "Add website" })).toHaveCount(0); // at the demo's limit

    // Its page, with the response-time chart once the worker has checked it.
    await websiteCard(page, "Example Org").getByRole("link", { name: "Example Org" }).click();
    await expect(page).toHaveURL(detailsUrl);
    await waitForChart(page);
    await expect(page.getByRole("heading", { name: "Uptime" })).toBeVisible();
    await page.getByRole("button", { name: "7 days" }).click();
    await expect(page.getByRole("button", { name: "7 days" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );

    // Pause and resume.
    await page.getByRole("button", { name: "Pause checks" }).click();
    await expect(page.getByRole("button", { name: "Resume checks" })).toBeVisible();
    await expect(page.getByText("Paused", { exact: true }).first()).toBeVisible();
    await page.getByRole("button", { name: "Resume checks" }).click();
    await expect(page.getByRole("button", { name: "Pause checks" })).toBeVisible();

    // Delete.
    await page.getByRole("button", { name: "Delete" }).click();
    const dialog = page.getByRole("dialog", { name: "Delete Example Org and its history?" });
    await dialog.getByRole("button", { name: "Delete website" }).click();
    await expect(page).toHaveURL("/dashboard");
    await expect(page.getByText("Example Org and its history have been deleted.")).toBeVisible();
    await expect(websiteCard(page, "Example Org")).toHaveCount(0);
    await expect(page.getByText("2 of 3 websites")).toBeVisible();

    expect(errors).toEqual([]);
  });

  test("restricted actions are not offered in the demo", async ({ page }) => {
    await startDemo(page);

    // No password to change, no account to delete.
    await page.goto("/settings/account");
    await expect(page.getByRole("heading", { name: "Demo account" })).toBeVisible();
    await expect(page.getByRole("heading", { name: "Change password" })).toHaveCount(0);
    await expect(page.getByRole("heading", { name: "Delete account" })).toHaveCount(0);
    await expect(page.getByLabel(/password/i)).toHaveCount(0);

    // No alert recipients to manage.
    await page.goto("/dashboard");
    await websiteCard(page, "Example Domain").getByRole("link").click();
    await expect(page.getByText("No emails in the demo")).toBeVisible();
    await expect(page.getByLabel("Add someone")).toHaveCount(0);

    // Checks every 5 minutes at most.
    await page.goto("/monitors/new");
    await expect(page.getByRole("radio", { name: /Every minute/ })).toBeDisabled();
    await expect(page.getByRole("radio", { name: "Every 5 minutes" })).toBeChecked();

    // The generated address is never shown.
    await expect(page.getByText(/pulsecheck\.invalid/)).toHaveCount(0);
  });

  test("each visitor's demo is private", async ({ browser }) => {
    const alice = await browser.newPage();
    const bob = await browser.newPage();
    try {
      await startDemo(alice);
      await startDemo(bob);

      await alice.goto("/monitors/new");
      await alice.getByLabel("Website address").fill("example.net");
      await alice.getByRole("button", { name: "Add website" }).click();
      await expect(alice.getByRole("heading", { level: 1, name: "example.net" })).toBeVisible();
      const aliceWebsite = alice.url();

      await bob.reload();
      await expect(websiteCard(bob, SAMPLE_WEBSITES[0])).toBeVisible();
      await expect(websiteCard(bob, "example.net")).toHaveCount(0);
      // Not even by address.
      await bob.goto(aliceWebsite);
      await expect(bob.getByRole("heading", { level: 1, name: "Website not found" })).toBeVisible();
    } finally {
      await endDemo(alice);
      await endDemo(bob);
      await alice.context().close();
      await bob.context().close();
    }
  });

  test("ending the demo deletes it and returns to the home page", async ({ page }) => {
    await startDemo(page);
    await page.getByRole("button", { name: /Account menu/ }).click();
    await page.getByRole("button", { name: "End the demo" }).click();

    await expect(page).toHaveURL("/");
    await expect(
      page.getByText("Your demo has ended and its data has been deleted."),
    ).toBeVisible();
    // The session is gone too.
    await page.goto("/dashboard");
    await expect(page).toHaveURL("/login?next=%2Fdashboard");
  });
});
