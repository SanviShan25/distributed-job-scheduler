import { describe, expect, it } from "vitest";
import {
  describeCause,
  formatDuration,
  incidentSeconds,
  summarize,
  type Incident,
} from "./history";

describe("formatDuration", () => {
  it.each([
    [0, "less than a minute"],
    [29, "less than a minute"],
    [30, "1 minute"],
    [12 * 60 + 10, "12 minutes"],
    [60 * 60, "1 hour"],
    [2 * 3600 + 5 * 60, "2 hours 5 minutes"],
    [23 * 3600 + 59 * 60 + 50, "1 day"], // rounds up to a whole day, not "23 hours 60 minutes"
    [3 * 86_400 + 4 * 3600, "3 days 4 hours"],
    [3 * 86_400 + 23.6 * 3600, "4 days"],
  ])("%i seconds is %s", (seconds, words) => {
    expect(formatDuration(seconds)).toBe(words);
  });
});

describe("incidentSeconds", () => {
  const base: Incident = {
    id: "i",
    startedAt: "2026-11-27T10:00:00Z",
    resolvedAt: null,
    ongoing: true,
    durationSeconds: 60,
    cause: null,
  };

  it("keeps counting while an incident is ongoing", () => {
    expect(incidentSeconds(base, Date.parse("2026-11-27T10:12:00Z"))).toBe(720);
  });

  it("uses the server's duration once resolved", () => {
    const resolved = { ...base, ongoing: false, resolvedAt: "2026-11-27T10:01:00Z" };
    expect(incidentSeconds(resolved, Date.parse("2026-11-28T00:00:00Z"))).toBe(60);
  });
});

describe("describeCause", () => {
  it.each([
    ["HTTP 503", "The website said it was temporarily unavailable."],
    ["HTTP 500", "The website's server had an internal error."],
    ["HTTP 404", "The page wasn't found on the website."],
    ["HTTP 403", "The website refused access to the page."],
    ["HTTP 418", "The website rejected the request."],
    ["timed out after 10s", "The website took longer than 10 seconds to respond."],
    ["could not resolve host", expect.stringContaining("address couldn't be found") as string],
    ["connection refused", "The website's server refused the connection."],
    [
      "TLS error: PKIX path building failed",
      expect.stringContaining("security certificate") as string,
    ],
    [
      "redirect blocked: 10.0.0.1 is private",
      expect.stringContaining("redirected to a private") as string,
    ],
    ["too many redirects (more than 5)", "The website redirected too many times."],
    [null, "The website didn't respond normally."],
  ])("%s", (cause, words) => {
    expect(describeCause(cause)).toEqual(words);
  });

  it("never shows a raw status code or internal address", () => {
    for (const cause of ["HTTP 502", "HTTP 429", "blocked: 127.0.0.1 is a private address"]) {
      expect(describeCause(cause)).not.toMatch(/\d{3}|127\.0\.0\.1/);
    }
  });
});

describe("summarize", () => {
  const bucket = (start: string, checks: number, failures: number, avg: number | null) => ({
    start,
    checks,
    failures,
    avgResponseMs: avg,
    maxResponseMs: avg,
  });

  it("weights the average by successful checks and counts failures", () => {
    const summary = summarize(
      [
        bucket("2026-11-27T10:00:00Z", 5, 0, 100),
        bucket("2026-11-27T10:05:00Z", 0, 0, null),
        bucket("2026-11-27T10:10:00Z", 5, 4, 600),
      ],
      "24h",
    );
    expect(summary.checks).toBe(10);
    // (5 × 100 + 1 × 600) / 6 ≈ 183 ms
    expect(summary.text).toMatch(
      /^Average 183 ms over the last 24 hours\. Slowest around .* \(600 ms\)\. 4 checks failed\.$/,
    );
  });

  it("says when nothing failed", () => {
    expect(summarize([bucket("2026-11-27T10:00:00Z", 2, 0, 250)], "7d").text).toContain(
      "No failed checks.",
    );
  });
});
