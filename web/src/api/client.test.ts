import { describe, expect, it } from "vitest";
import { sessionEnd } from "../auth/session";
import { fakeBackend, json, noContent, problem, setCookie } from "../test/fakeBackend";
import { api, ApiError, formatWait } from "./client";

async function failure(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise;
  } catch (error) {
    if (error instanceof ApiError) return error;
    throw error;
  }
  throw new Error("expected the request to fail");
}

describe("API client: cookies and CSRF", () => {
  it("sends the current XSRF-TOKEN cookie as X-XSRF-TOKEN on every state-changing request", async () => {
    const backend = fakeBackend({ "POST /api/thing": () => noContent() });

    setCookie("XSRF-TOKEN", "first");
    await api.post("/api/thing", { a: 1 });
    // The backend rotates the token at login/logout; the next request must use the new cookie.
    setCookie("XSRF-TOKEN", "second");
    await api.post("/api/thing");

    const posts = backend.apiRequests();
    expect(posts.map((r) => r.headers.get("X-XSRF-TOKEN"))).toEqual(["first", "second"]);
    expect(posts.every((r) => r.credentials === "include")).toBe(true);
    expect(posts[0]?.body).toEqual({ a: 1 });
    expect(posts[0]?.headers.get("Content-Type")).toBe("application/json");
  });

  it("sends cookies but no CSRF header on GET", async () => {
    setCookie("XSRF-TOKEN", "abc");
    const backend = fakeBackend({ "GET /api/thing": () => json(200, { ok: true }) });

    await expect(api.get("/api/thing")).resolves.toEqual({ ok: true });

    const [request] = backend.requests;
    expect(request?.credentials).toBe("include");
    expect(request?.headers.has("X-XSRF-TOKEN")).toBe(false);
  });

  it("fetches a CSRF token first when there is no cookie yet", async () => {
    const backend = fakeBackend({ "POST /api/thing": () => noContent() });

    await api.post("/api/thing");

    expect(backend.requests.map((r) => `${r.method} ${r.path}`)).toEqual([
      "GET /api/auth/csrf",
      "POST /api/thing",
    ]);
    expect(backend.requests[1]?.headers.get("X-XSRF-TOKEN")).toBe(document.cookie.split("=")[1]);
  });

  it("gets a fresh token and retries once when the backend rejects the CSRF token", async () => {
    setCookie("XSRF-TOKEN", "stale");
    const backend = fakeBackend({
      "POST /api/thing": (request) =>
        request.headers.get("X-XSRF-TOKEN") === "stale"
          ? problem(403, "Missing or invalid CSRF token: call GET /api/auth/csrf …")
          : json(200, { done: true }),
    });

    await expect(api.post("/api/thing")).resolves.toEqual({ done: true });
    expect(backend.requests.map((r) => r.method)).toEqual(["POST", "GET", "POST"]);
  });

  it("does not retry other 403s", async () => {
    setCookie("XSRF-TOKEN", "t");
    const backend = fakeBackend({
      "DELETE /api/auth/account": () => problem(403, "Your password is incorrect"),
    });

    const error = await failure(api.delete("/api/auth/account", { password: "wrong-password" }));
    expect(error.message).toBe("Your password is incorrect");
    expect(backend.requests).toHaveLength(1);
  });
});

describe("API client: CSRF retry is only for CSRF rejections", () => {
  it("does not retry a 403 for an unverified email, and explains it plainly", async () => {
    setCookie("XSRF-TOKEN", "valid");
    const backend = fakeBackend({
      "POST /api/monitors": () =>
        new Response(
          JSON.stringify({
            type: "about:blank",
            title: "Email not verified",
            status: 403,
            detail:
              "Please verify your email address before creating monitors. Check your inbox for the link, or request a new one (POST /api/auth/resend-verification).",
          }),
          { status: 403, headers: { "Content-Type": "application/problem+json" } },
        ),
    });

    const error = await failure(api.post("/api/monitors", { name: "Shop" }));

    expect(backend.requests.map((r) => `${r.method} ${r.path}`)).toEqual(["POST /api/monitors"]);
    expect(error.status).toBe(403);
    expect(error.message).toBe(
      "Please verify your email address first. Check your inbox for the link, or use “Resend link” above.",
    );
    expect(error.message).not.toMatch(/\/api\//);
  });

  it("retries a CSRF rejection only once", async () => {
    setCookie("XSRF-TOKEN", "t");
    const backend = fakeBackend({
      "POST /api/thing": () =>
        problem(403, "Missing or invalid CSRF token: call GET /api/auth/csrf …"),
    });

    const error = await failure(api.post("/api/thing"));

    expect(backend.requests.map((r) => r.method)).toEqual(["POST", "GET", "POST"]);
    expect(error.status).toBe(403);
    expect(error.message).toBe("Your page is out of date. Please reload the page and try again.");
  });
});

describe("API client: errors in plain English", () => {
  it("shows the backend's message when it is written for people", async () => {
    setCookie("XSRF-TOKEN", "t");
    fakeBackend({
      "POST /api/auth/verify": () =>
        problem(400, "This link is invalid or has expired. Please request a new one."),
    });

    const error = await failure(api.post("/api/auth/verify", { token: "x" }));
    expect(error.status).toBe(400);
    expect(error.message).toBe("This link is invalid or has expired. Please request a new one.");
  });

  it("replaces technical messages with a plain one", async () => {
    setCookie("XSRF-TOKEN", "t");
    fakeBackend({
      "POST /api/auth/login": () =>
        new Response(
          JSON.stringify({
            status: 400,
            detail: "Request validation failed",
            errors: [{ field: "password", message: "must not be null" }],
          }),
          { status: 400, headers: { "Content-Type": "application/problem+json" } },
        ),
    });

    const error = await failure(api.post("/api/auth/login", {}));
    expect(error.message).toBe("Please check your details and try again.");
    expect(error.fieldErrors).toEqual({ password: "must not be null" });
  });

  it("never shows server errors as they are", async () => {
    fakeBackend({ "GET /api/thing": () => problem(500, "NullPointerException at Foo.java:12") });

    const error = await failure(api.get("/api/thing"));
    expect(error.status).toBe(500);
    expect(error.message).toBe("Something went wrong on our side. Please try again in a moment.");
  });

  it("explains when the server can't be reached", async () => {
    fakeBackend({
      "GET /api/thing": () => {
        throw new TypeError("Failed to fetch");
      },
    });

    const error = await failure(api.get("/api/thing"));
    expect(error.status).toBe(0);
    expect(error.message).toBe(
      "We couldn't reach PulseCheck. Check your internet connection and try again.",
    );
  });

  it("handles a response without a body", async () => {
    fakeBackend({ "GET /api/thing": () => new Response(null, { status: 404 }) });

    const error = await failure(api.get("/api/thing"));
    expect(error.message).toBe("We couldn't find that. It may have been deleted.");
  });
});

describe("API client: 429 Too Many Requests", () => {
  it("says how long to wait, from Retry-After", async () => {
    setCookie("XSRF-TOKEN", "t");
    fakeBackend({
      "POST /api/auth/login": () =>
        problem(429, "Too many login attempts. Please try again in 30 seconds.", {
          "Retry-After": "30",
        }),
    });

    const error = await failure(api.post("/api/auth/login", {}, { on401: "error" }));
    expect(error.status).toBe(429);
    expect(error.retryAfterSeconds).toBe(30);
    expect(error.message).toBe("Too many attempts. Please try again in 30 seconds.");
  });

  it("uses minutes for long waits", async () => {
    setCookie("XSRF-TOKEN", "t");
    fakeBackend({
      "POST /api/auth/resend-verification": () =>
        problem(429, "Too many requests.", { "Retry-After": "1150" }),
    });

    const error = await failure(api.post("/api/auth/resend-verification"));
    expect(error.message).toBe("Too many attempts. Please try again in 20 minutes.");
  });

  it("formats waits", () => {
    expect(formatWait(1)).toBe("1 second");
    expect(formatWait(45)).toBe("45 seconds");
    expect(formatWait(90)).toBe("90 seconds");
    expect(formatWait(91)).toBe("2 minutes");
  });
});

describe("API client: 401", () => {
  it("ends the session by default", async () => {
    fakeBackend({ "GET /api/monitors": () => problem(401, "Please log in") });

    const error = await failure(api.get("/api/monitors"));
    expect(error.status).toBe(401);
    expect(error.message).toBe("Your session has ended. Please log in again.");
    expect(sessionEnd.get()).toBe("expired");
  });

  it("is a normal error where the caller expects it, e.g. a wrong password at login", async () => {
    setCookie("XSRF-TOKEN", "t");
    fakeBackend({ "POST /api/auth/login": () => problem(401, "Invalid email or password") });

    const error = await failure(api.post("/api/auth/login", {}, { on401: "error" }));
    expect(error.message).toBe("Invalid email or password");
    expect(sessionEnd.get()).toBeNull();
  });
});
