import { describe, expect, it } from "vitest";
import { formatRelative } from "./time";

const NOW = Date.parse("2026-11-26T12:00:00Z");
const ago = (ms: number) => new Date(NOW - ms).toISOString();

describe("formatRelative", () => {
  it.each([
    [0, "just now"],
    [44_000, "just now"],
    [-5_000, "just now"], // a little in the future: clocks differ
    [50_000, "1 minute ago"],
    [2 * 60_000, "2 minutes ago"],
    [59 * 60_000, "59 minutes ago"],
    [60 * 60_000, "1 hour ago"],
    [5 * 3_600_000, "5 hours ago"],
    [24 * 3_600_000, "yesterday"],
    [3 * 86_400_000, "3 days ago"],
  ])("%i ms ago is %s", (ms, expected) => {
    expect(formatRelative(ago(ms), NOW)).toBe(expected);
  });

  it("shows a date for anything older than a week", () => {
    expect(formatRelative(ago(30 * 86_400_000), NOW)).toMatch(/2026/);
  });
});
