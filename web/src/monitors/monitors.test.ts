import { describe, expect, it } from "vitest";
import {
  formatInterval,
  formatResponseTime,
  formatUptime,
  shortUrl,
  sortForDashboard,
  type Monitor,
} from "./monitors";

describe("monitor formatting", () => {
  it.each([
    [100, "100%"],
    [99.5, "99.5%"],
    [99.95, "99.95%"],
    [0, "0%"],
  ])("uptime %d is %s", (value, text) => {
    expect(formatUptime(value)).toBe(text);
  });

  it.each([
    [12, "12 ms"],
    [999, "999 ms"],
    [1000, "1.0 s"],
    [2449, "2.4 s"],
  ])("response time %d ms is %s", (value, text) => {
    expect(formatResponseTime(value)).toBe(text);
  });

  it.each([
    [60, "Every minute"],
    [300, "Every 5 minutes"],
    [900, "Every 15 minutes"],
    [3600, "Every hour"],
  ])("interval %d s is %s", (value, text) => {
    expect(formatInterval(value)).toBe(text);
  });

  it("shortens addresses for display", () => {
    expect(shortUrl("https://example.com/")).toBe("example.com");
    expect(shortUrl("http://example.com/status.txt")).toBe("example.com/status.txt");
  });
});

describe("sortForDashboard", () => {
  const m = (name: string, status: Monitor["status"], enabled = true) =>
    ({ name, status, enabled }) as Monitor;

  it("puts down sites first, then sorts by name ignoring case and with numbers in order", () => {
    const sorted = sortForDashboard([
      m("zeta", "UP"),
      m("Site 10", "UP"),
      m("Shop", "DOWN"),
      m("alpha", "PAUSED", false),
      m("Site 2", "UNKNOWN"),
      m("Api", "DOWN"),
    ]);
    expect(sorted.map((x) => x.name)).toEqual([
      "Api",
      "Shop",
      "alpha",
      "Site 2",
      "Site 10",
      "zeta",
    ]);
  });

  it("does not count a paused site as down", () => {
    const sorted = sortForDashboard([m("A", "UP"), m("B", "DOWN", false)]);
    expect(sorted.map((x) => x.name)).toEqual(["A", "B"]);
  });
});
