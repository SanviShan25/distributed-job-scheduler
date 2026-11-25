import { vi } from "vitest";

export interface FakeRequest {
  method: string;
  path: string;
  headers: Headers;
  body: unknown;
  credentials: RequestCredentials | undefined;
}

export type Handler = (request: FakeRequest) => Response | Promise<Response>;

export function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

export function noContent() {
  return new Response(null, { status: 204 });
}

/** A ProblemDetail error response, shaped like the backend's. */
export function problem(status: number, detail: string, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify({ type: "about:blank", title: "Error", status, detail }), {
    status,
    headers: { "Content-Type": "application/problem+json", ...headers },
  });
}

/** The backend's CSRF rejection, exactly as SecurityConfig writes it. */
export function csrfRejection() {
  return new Response(
    JSON.stringify({
      type: "about:blank",
      title: "Forbidden",
      status: 403,
      detail:
        "Missing or invalid CSRF token: call GET /api/auth/csrf and send the token in the X-XSRF-TOKEN header",
      instance: "/api/thing",
      code: "csrf_invalid",
    }),
    { status: 403, headers: { "Content-Type": "application/problem+json" } },
  );
}

export function setCookie(name: string, value: string) {
  document.cookie = `${name}=${value}; path=/`;
}

let csrfCounter = 0;

/** Like the real GET /api/auth/csrf: sets a new XSRF-TOKEN cookie. */
export const csrfHandler: Handler = () => {
  csrfCounter += 1;
  const token = `csrf-${csrfCounter}`;
  setCookie("XSRF-TOKEN", token);
  return json(200, { headerName: "X-XSRF-TOKEN", cookieName: "XSRF-TOKEN", token });
};

/**
 * Replaces fetch with a fake backend. Handlers are keyed "METHOD /path" (no query string).
 * GET /api/auth/csrf works by default; anything unknown is a 404.
 */
export function fakeBackend(handlers: Record<string, Handler>) {
  const requests: FakeRequest[] = [];
  const fetchMock = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const url = new URL(
      input instanceof Request ? input.url : String(input),
      window.location.origin,
    );
    const method = (init.method ?? "GET").toUpperCase();
    const request: FakeRequest = {
      method,
      path: url.pathname + url.search,
      headers: new Headers(init.headers),
      body: typeof init.body === "string" ? (JSON.parse(init.body) as unknown) : undefined,
      credentials: init.credentials,
    };
    requests.push(request);
    const key = `${method} ${url.pathname}`;
    const handler = handlers[key] ?? (key === "GET /api/auth/csrf" ? csrfHandler : undefined);
    return Promise.resolve(handler ? handler(request) : problem(404, "Not found"));
  });
  vi.stubGlobal("fetch", fetchMock);

  return {
    requests,
    /** Requests other than the CSRF token fetches. */
    apiRequests: () => requests.filter((r) => r.path !== "/api/auth/csrf"),
  };
}
