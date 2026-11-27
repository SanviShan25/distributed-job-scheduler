import { describe, expect, it } from "vitest";
import { ApiError } from "../api/client";
import { monitorFormError } from "./errors";

function backend(status: number, detail: string, title = "Error") {
  return new ApiError("(client wording)", status, { problem: { status, title, detail } });
}

describe("monitorFormError", () => {
  it.each([
    [
      "This URL can't be monitored: localhost resolves to a private or reserved address (127.0.0.1)",
      /private or local network/,
    ],
    ["This URL can't be monitored: 169.254.169.254 is a private or reserved address", /private/],
    [
      "This URL can't be monitored: could not resolve host nosuchsite.example",
      /couldn't find a website at nosuchsite\.example/,
    ],
    ["This URL can't be monitored: only http and https URLs are allowed", /doesn't look like/],
    [
      "This URL can't be monitored: URLs with a username or password are not allowed",
      /username and password/,
    ],
  ])("puts “%s” on the address field in plain words", (detail, message) => {
    const result = monitorFormError(backend(400, detail));
    expect(result.field).toBe("url");
    expect(result.message).toMatch(message);
    expect(result.message).not.toMatch(/127\.0\.0\.1|URL|reserved/);
  });

  it("says a duplicate address is already being checked", () => {
    expect(
      monitorFormError(backend(409, "You already have a monitor for https://example.com/")),
    ).toEqual({ field: "url", message: "You're already checking this address." });
  });

  it("explains the limit", () => {
    expect(monitorFormError(backend(409, "You can have at most 10 monitors"))).toEqual({
      kind: "limit-reached",
      message: "You've reached the limit of 10 websites. Delete one to add another.",
    });
  });

  it("recognises an unverified email by its title, not its wording", () => {
    const result = monitorFormError(
      backend(403, "Please verify … (POST /api/auth/resend-verification).", "Email not verified"),
    );
    expect(result.kind).toBe("email-not-verified");
    expect(result.message).not.toContain("/api/");
  });

  it("never shows the API's field names", () => {
    const result = monitorFormError(
      backend(400, "intervalSeconds must be one of 60, 300, 900 or 3600"),
    );
    expect(result).toEqual({ field: "interval", message: "Please choose how often to check." });
  });

  it("falls back to the client's own wording", () => {
    const error = new ApiError("Something went wrong on our side.", 500);
    expect(monitorFormError(error)).toEqual({ message: "Something went wrong on our side." });
  });
});
