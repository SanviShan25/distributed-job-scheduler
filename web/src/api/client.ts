import { sessionEnd } from "../auth/session";

/**
 * The one way the web app talks to the backend.
 *
 * - Every request sends cookies (`credentials: "include"`).
 * - State-changing requests send the CSRF token in X-XSRF-TOKEN, read from the XSRF-TOKEN cookie right
 *   before the request (the backend rotates it at login and logout). If the cookie is missing, it is
 *   fetched first from GET /api/auth/csrf; a 403 for a bad CSRF token gets one fresh token and one retry.
 * - Errors become an ApiError whose message is plain English and safe to show to the user.
 * - A 401 means the session has ended (unless the caller says a 401 is an expected answer, as for
 *   login), which sends the user to the login page and back afterwards.
 */

export const CSRF_COOKIE = "XSRF-TOKEN";
export const CSRF_HEADER = "X-XSRF-TOKEN";
const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

export type HttpMethod = "GET" | "POST" | "PATCH" | "PUT" | "DELETE";

/** RFC 9457 problem details, as the backend sends them. */
export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: { field: string; message: string }[];
}

export class ApiError extends Error {
  /** HTTP status, or 0 when the server could not be reached. */
  readonly status: number;
  /** For 429: how long to wait before trying again. */
  readonly retryAfterSeconds: number | undefined;
  /** For 400 validation errors: field name to the backend's message (for logging, not display). */
  readonly fieldErrors: Readonly<Record<string, string>>;

  constructor(
    message: string,
    status: number,
    extra: { retryAfterSeconds?: number; fieldErrors?: Record<string, string> } = {},
  ) {
    super(message);
    this.name = "ApiError";
    this.status = status;
    this.retryAfterSeconds = extra.retryAfterSeconds;
    this.fieldErrors = extra.fieldErrors ?? {};
  }
}

export interface RequestOptions {
  method?: HttpMethod;
  /** Sent as JSON. */
  body?: unknown;
  signal?: AbortSignal;
  /**
   * What a 401 means. "session-expired" (default): the session ended, so the user is sent to log in.
   * "error": a normal answer the caller handles, e.g. wrong password at login, or "not logged in" from
   * GET /api/auth/me.
   */
  on401?: "session-expired" | "error";
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const method = options.method ?? "GET";
  const response = await send(path, method, options, true);

  if (response.ok) {
    if (response.status === 204) return undefined as T;
    const text = await response.text();
    return (text ? JSON.parse(text) : undefined) as T;
  }

  const problem = await readProblem(response);
  if (response.status === 401 && options.on401 !== "error") {
    sessionEnd.set("expired");
    throw new ApiError("Your session has ended. Please log in again.", 401);
  }
  throw toApiError(response, problem);
}

export const api = {
  get: <T>(path: string, options?: Omit<RequestOptions, "method" | "body">) =>
    apiRequest<T>(path, { ...options, method: "GET" }),
  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    apiRequest<T>(path, { ...options, method: "POST", body }),
  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    apiRequest<T>(path, { ...options, method: "PATCH", body }),
  delete: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    apiRequest<T>(path, { ...options, method: "DELETE", body }),
};

let csrfInFlight: Promise<void> | null = null;

/**
 * Asks the backend for a CSRF token, which it sets as the XSRF-TOKEN cookie. Called on start-up and
 * after login and logout. Concurrent callers share one request.
 */
export function refreshCsrfToken(): Promise<void> {
  csrfInFlight ??= (async () => {
    try {
      const response = await fetch("/api/auth/csrf", {
        credentials: "include",
        headers: { Accept: "application/json" },
      });
      if (!response.ok) throw toApiError(response, await readProblem(response));
    } catch (error) {
      throw error instanceof ApiError ? error : networkError();
    } finally {
      csrfInFlight = null;
    }
  })();
  return csrfInFlight;
}

export function readCookie(name: string): string | undefined {
  for (const part of document.cookie.split(";")) {
    const separator = part.indexOf("=");
    if (separator === -1) continue;
    if (part.slice(0, separator).trim() === name) {
      return decodeURIComponent(part.slice(separator + 1).trim());
    }
  }
  return undefined;
}

async function send(
  path: string,
  method: HttpMethod,
  options: RequestOptions,
  mayRetryCsrf: boolean,
): Promise<Response> {
  const headers: Record<string, string> = {
    Accept: "application/json, application/problem+json",
  };
  if (options.body !== undefined) headers["Content-Type"] = "application/json";

  const needsCsrf = !SAFE_METHODS.has(method);
  if (needsCsrf) {
    if (readCookie(CSRF_COOKIE) === undefined) await refreshCsrfToken();
    const token = readCookie(CSRF_COOKIE);
    if (token !== undefined) headers[CSRF_HEADER] = token;
  }

  let response: Response;
  try {
    response = await fetch(path, {
      method,
      headers,
      credentials: "include",
      body: options.body === undefined ? null : JSON.stringify(options.body),
      signal: options.signal ?? null,
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw networkError();
  }

  if (needsCsrf && mayRetryCsrf && response.status === 403) {
    const problem = await readProblem(response.clone());
    if (isCsrfProblem(problem)) {
      await refreshCsrfToken();
      return send(path, method, options, false);
    }
  }
  return response;
}

async function readProblem(response: Response): Promise<ProblemDetail> {
  try {
    const body: unknown = await response.json();
    return typeof body === "object" && body !== null ? body : {};
  } catch {
    return {};
  }
}

/**
 * Only the backend's CSRF rejection ("Missing or invalid CSRF token: …") is worth a fresh token and a
 * retry. Every other 403 (wrong password, email not verified) is a real answer and is never retried.
 */
function isCsrfProblem(problem: ProblemDetail): boolean {
  return /csrf token/i.test(problem.detail ?? "");
}

function networkError(): ApiError {
  return new ApiError(
    "We couldn't reach PulseCheck. Check your internet connection and try again.",
    0,
  );
}

/**
 * Errors identified by their ProblemDetail title whose detail is aimed at API users (it names
 * endpoints), with the wording to show instead.
 */
const MESSAGES_BY_TITLE: Record<string, string> = {
  "Email not verified":
    "Please verify your email address first. Check your inbox for the link, or use “Resend link” above.",
};

const FALLBACK_MESSAGES: Record<number, string> = {
  400: "Please check your details and try again.",
  401: "Please log in.",
  403: "You don't have permission to do that.",
  404: "We couldn't find that. It may have been deleted.",
  409: "That clashes with something that already exists.",
};

/**
 * Backend details are mostly written for people ("Your password is incorrect"), but some are aimed
 * at developers (CSRF instructions, framework errors). Those are replaced by a plain fallback.
 */
const TECHNICAL_DETAIL =
  /csrf|x-xsrf|header|\/api\/|request validation failed|failed to read|no static resource|no endpoint|content[- ]type|json|method '|exception/i;

function toApiError(response: Response, problem: ProblemDetail): ApiError {
  const status = response.status;

  if (status === 429) {
    const seconds = retryAfterSeconds(response, problem);
    const message =
      seconds === undefined
        ? "Too many attempts. Please wait a little and try again."
        : `Too many attempts. Please try again in ${formatWait(seconds)}.`;
    return new ApiError(
      message,
      status,
      seconds === undefined ? {} : { retryAfterSeconds: seconds },
    );
  }

  if (status >= 500) {
    return new ApiError("Something went wrong on our side. Please try again in a moment.", status);
  }

  const fieldErrors = Object.fromEntries(
    (problem.errors ?? []).map(({ field, message }) => [field, message]),
  );
  if (status === 403 && isCsrfProblem(problem)) {
    return new ApiError("Your page is out of date. Please reload the page and try again.", status, {
      fieldErrors,
    });
  }

  const byTitle = problem.title === undefined ? undefined : MESSAGES_BY_TITLE[problem.title];
  if (byTitle !== undefined) return new ApiError(byTitle, status, { fieldErrors });

  const detail = problem.detail?.trim();
  const message =
    detail && detail.length <= 300 && !TECHNICAL_DETAIL.test(detail)
      ? detail
      : (FALLBACK_MESSAGES[status] ?? "Something went wrong. Please try again.");
  return new ApiError(message, status, { fieldErrors });
}

function retryAfterSeconds(response: Response, problem: ProblemDetail): number | undefined {
  const header = response.headers.get("Retry-After");
  if (header !== null) {
    const seconds = Number(header);
    if (Number.isFinite(seconds) && seconds >= 0) return Math.max(1, Math.ceil(seconds));
    const date = Date.parse(header);
    if (!Number.isNaN(date)) return Math.max(1, Math.ceil((date - Date.now()) / 1000));
  }
  const match = /in (\d+) seconds?/i.exec(problem.detail ?? "");
  return match?.[1] ? Number(match[1]) : undefined;
}

/** "45 seconds", "1 second", "5 minutes" (minutes from 90 seconds up, rounded up). */
export function formatWait(seconds: number): string {
  if (seconds <= 90) return seconds === 1 ? "1 second" : `${seconds} seconds`;
  const minutes = Math.ceil(seconds / 60);
  return `${minutes} minutes`;
}
