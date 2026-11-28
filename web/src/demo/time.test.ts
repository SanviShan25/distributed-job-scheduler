import { describe, expect, it } from "vitest";
import { formatTimeLeft } from "./time";

describe("formatTimeLeft", () => {
  it.each([
    [0, "less than a minute"],
    [59_000, "less than a minute"],
    [60_000, "1 minute"],
    [61_000, "2 minutes"], // rounds up: never claims less time than is left
    [45 * 60_000, "45 minutes"],
    [60 * 60_000, "1 hour"],
    [61 * 60_000, "1 hour 1 minute"],
    [(2 * 60 + 30) * 60_000, "2 hours 30 minutes"],
    [3 * 60 * 60_000, "3 hours"],
  ])("%i ms is %s", (ms, text) => {
    expect(formatTimeLeft(ms)).toBe(text);
  });
});
