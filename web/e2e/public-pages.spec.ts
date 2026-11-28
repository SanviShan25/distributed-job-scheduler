import { expect, test } from "@playwright/test";
import { collectPageErrors } from "./support";

test.describe("logged-out visitors", () => {
  for (const path of ["/dashboard", "/monitors/new", "/settings/account"]) {
    test(`are sent to log in from ${path}, with the page remembered`, async ({ page }) => {
      await page.goto(path);
      await expect(page).toHaveURL(`/login?next=${encodeURIComponent(path)}`);
      await expect(page.getByRole("heading", { level: 1, name: "Log in" })).toBeVisible();
    });
  }

  test("see the landing page, which runs under the strict Content-Security-Policy", async ({
    page,
  }) => {
    const errors = collectPageErrors(page);
    const response = await page.goto("/");
    expect(response?.headers()["content-security-policy"]).toContain("script-src 'self'");

    await expect(
      page.getByRole("heading", { level: 1, name: "Know when your website goes down" }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Try the demo" })).toBeVisible();
    await expect(
      page.getByRole("region", { name: "How it works" }).getByRole("listitem"),
    ).toHaveCount(3);
    await expect(page).toHaveTitle("Website uptime monitoring with email alerts · PulseCheck");
    expect(errors).toEqual([]);
  });

  test("are told that sign-ups are limited, and pointed to the demo", async ({ page }) => {
    await page.goto("/signup");
    await expect(page.getByText("Sign-ups are limited right now")).toBeVisible();
    await expect(page.getByRole("button", { name: "Try the demo" })).toBeVisible();

    await page.getByLabel("Email").fill("someone@example.com");
    await page.getByLabel("Password", { exact: true }).fill("a long enough password");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("alert")).toContainText("this email address isn't on the list");
  });

  test("robots.txt allows the landing page and blocks the API and the app", async ({ request }) => {
    const robots = await (await request.get("/robots.txt")).text();
    expect(robots).toContain("Allow: /$");
    for (const path of ["/api/", "/dashboard", "/monitors/", "/settings/"]) {
      expect(robots).toContain(`Disallow: ${path}`);
    }
  });
});
