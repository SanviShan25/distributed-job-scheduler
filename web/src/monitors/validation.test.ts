import { describe, expect, it } from "vitest";
import { nameOrHost, normalizeUrl, validateName, validateUrl } from "./validation";

describe("normalizeUrl", () => {
  it.each([
    ["example.com", "https://example.com"],
    ["  example.com/shop  ", "https://example.com/shop"],
    ["www.example.com", "https://www.example.com"],
    ["http://example.com", "http://example.com"],
    ["HTTPS://Example.com", "HTTPS://Example.com"],
    ["//example.com", "https://example.com"],
    ["example.com:8080/x", "https://example.com:8080/x"],
    ["", ""],
  ])("%s → %s", (input, expected) => {
    expect(normalizeUrl(input)).toBe(expected);
  });
});

describe("validateUrl", () => {
  it.each([
    "example.com",
    "https://example.com/status.txt",
    "http://93.184.215.14",
    "sub.example.co.uk:8080/x",
  ])("accepts %s", (input) => {
    expect(validateUrl(input)).toBeUndefined();
  });

  it.each([
    ["", /Enter your website's address/],
    ["   ", /Enter your website's address/],
    ["ftp://example.com", /Only website addresses/],
    ["mailto:someone@example.com", /Only website addresses/],
    ["https://user:secret@example.com", /remove the username and password/],
    ["example", /full address/],
    ["https://exa mple.com", /doesn't look like a web address/],
    [`example.com/${"a".repeat(2050)}`, /too long/],
  ])("refuses %s", (input, message) => {
    expect(validateUrl(input)).toMatch(message);
  });
});

describe("names", () => {
  it("uses the address's host when no name is given", () => {
    expect(nameOrHost("  ", "example.com/shop")).toBe("example.com");
    expect(nameOrHost(" Shop ", "example.com")).toBe("Shop");
  });

  it("limits names to 200 characters", () => {
    expect(validateName("a".repeat(200))).toBeUndefined();
    expect(validateName("a".repeat(201))).toMatch(/under 200 characters/);
  });
});
