/**
 * Why the user is no longer logged in, if the app ended the session itself. Route guards read it to
 * decide where to send the user and what to tell them:
 * - "expired": an API call answered 401, so the session ended on the server (idle timeout, password
 *   reset elsewhere, account deleted). The user is sent to log in and then back to the same page.
 * - "logged-out": the user chose to log out. They go to the login page without a "next" page.
 * - "account-deleted": the user deleted their account. Like "logged-out", with its own message.
 * - "demo-ended": a demo account ran out of time or its visitor logged out; it is deleted on the
 *   server. The visitor goes back to the home page, where they can start a new demo.
 */
export type SessionEndReason = "expired" | "logged-out" | "account-deleted" | "demo-ended" | null;

let reason: SessionEndReason = null;
const listeners = new Set<() => void>();

export const sessionEnd = {
  get: (): SessionEndReason => reason,
  set: (next: SessionEndReason): void => {
    if (reason === next) return;
    reason = next;
    for (const listener of listeners) listener();
  },
  subscribe: (listener: () => void): (() => void) => {
    listeners.add(listener);
    return () => {
      listeners.delete(listener);
    };
  },
};
