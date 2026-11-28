import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against the real stack (Spring Boot serving the built web app, Postgres, Redis and a
 * worker), started with e2e/stack/stack.sh. They test what unit tests can't: the real backend, cookies,
 * CSRF, the strict Content-Security-Policy and the worker actually checking websites.
 *
 * - "e2e": the tests. `npm run e2e`.
 * - "screenshots": captures the README and landing page images. `npm run e2e:screenshots`.
 */
export default defineConfig({
  testDir: "./e2e",
  outputDir: "./test-results",
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  // One test at a time. The whole suite takes well under a minute, and in parallel the browsers compete
  // for CPU with the two JVMs, Postgres and Redis (a CI runner has 4 cores), which made the API answer in
  // seconds instead of milliseconds and tests time out.
  workers: 1,
  timeout: 120_000,
  expect: { timeout: 10_000 },
  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: process.env.E2E_BASE_URL ?? "http://localhost:8080",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    {
      name: "e2e",
      testIgnore: /screenshots\.spec\.ts/,
      use: { ...devices["Desktop Chrome"] },
    },
    {
      name: "screenshots",
      testMatch: /screenshots\.spec\.ts/,
      use: { ...devices["Desktop Chrome"], viewport: { width: 1280, height: 800 } },
    },
  ],
});
