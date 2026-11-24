export const DEFAULT_AFTER_LOGIN = "/dashboard";

/**
 * The page to go to after logging in, from the ?next= parameter. Only same-site paths are accepted:
 * "//evil.example" or "/\evil.example" would be read by browsers as another site (an open redirect).
 */
export function safeNextPath(next: string | null | undefined): string {
  if (!next?.startsWith("/") || next.startsWith("//") || next.startsWith("/\\")) {
    return DEFAULT_AFTER_LOGIN;
  }
  if (next === "/login" || next.startsWith("/login?") || next === "/signup") {
    return DEFAULT_AFTER_LOGIN;
  }
  return next;
}

export function loginPathFor(next: string): string {
  return `/login?next=${encodeURIComponent(next)}`;
}
