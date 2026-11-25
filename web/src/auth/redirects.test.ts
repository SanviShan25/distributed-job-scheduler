import { describe, expect, it } from "vitest";
import { safeNextPath } from "./redirects";

describe("safeNextPath", () => {
  it.each([
    ["/dashboard", "/dashboard"],
    ["/monitors/42?tab=history#x", "/monitors/42?tab=history#x"],
    [null, "/dashboard"],
    ["", "/dashboard"],
    ["https://evil.example/", "/dashboard"],
    ["//evil.example/", "/dashboard"],
    ["/\\evil.example/", "/dashboard"],
    ["javascript:alert(1)", "/dashboard"],
    ["/login", "/dashboard"],
    ["/login?next=/x", "/dashboard"],
  ])("%s → %s", (next, expected) => {
    expect(safeNextPath(next)).toBe(expected);
  });
});
