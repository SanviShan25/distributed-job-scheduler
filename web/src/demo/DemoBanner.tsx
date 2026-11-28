import { useQueryClient } from "@tanstack/react-query";
import { Clock } from "lucide-react";
import { useEffect } from "react";
import { sessionEnd } from "../auth/session";
import { clearPrivateData, type User } from "../auth/user";
import { useNow } from "../lib/time";
import { formatTimeLeft } from "./time";

/**
 * Shown on every app page in a demo account: it's private and temporary, and how long is left. When the
 * time is up the demo ends here too (the server deletes it), and the visitor goes back to the home page.
 */
export function DemoBanner({ user }: { user: User }) {
  const now = useNow(30_000);
  const expiresAt = user.demoExpiresAt ? Date.parse(user.demoExpiresAt) : Number.NaN;
  const left = Number.isNaN(expiresAt) ? undefined : Math.max(0, expiresAt - now);
  const ended = left === 0;
  // The configured lifetime (3 hours by default), so the sentence stays true if it changes.
  const lifetimeHours = Number.isNaN(expiresAt)
    ? 3
    : Math.max(1, Math.round((expiresAt - Date.parse(user.createdAt)) / 3_600_000));

  const queryClient = useQueryClient();
  useEffect(() => {
    if (!ended) return;
    // RequireAuth then sends the visitor to the home page, which says the demo has ended.
    sessionEnd.set("demo-ended");
    clearPrivateData(queryClient);
  }, [ended, queryClient]);

  return (
    <section
      aria-label="Demo account"
      className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-brand-200 bg-brand-50 px-4 py-3 text-brand-800"
    >
      <Clock aria-hidden="true" className="size-5 shrink-0" />
      <p className="font-semibold">
        This is your private demo. It&apos;s deleted after{" "}
        {lifetimeHours === 1 ? "1 hour" : `${String(lifetimeHours)} hours`}.
      </p>
      {left !== undefined && (
        // Updated every 30 seconds; not announced each time (no live region), it would interrupt.
        <p className="text-sm">
          Time left: <strong>{formatTimeLeft(left)}</strong>
        </p>
      )}
    </section>
  );
}
