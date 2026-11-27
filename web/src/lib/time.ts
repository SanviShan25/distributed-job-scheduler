import { useEffect, useState } from "react";

const relative = new Intl.RelativeTimeFormat("en", { numeric: "auto" });

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/**
 * "just now", "2 minutes ago", "3 hours ago", "yesterday", "4 days ago"; older than a week, the
 * date in the user's time zone. Times slightly in the future (clock differences) count as just now.
 */
export function formatRelative(time: string | number | Date, now: number = Date.now()): string {
  const then = new Date(time).getTime();
  const ago = now - then;
  if (ago < 45_000) return "just now";
  if (ago < HOUR) return relative.format(-Math.max(1, Math.round(ago / MINUTE)), "minute");
  if (ago < DAY) return relative.format(-Math.round(ago / HOUR), "hour");
  if (ago < 7 * DAY) return relative.format(-Math.round(ago / DAY), "day");
  return formatDate(then);
}

/** "26 Nov 2026, 14:05" in the user's own locale and time zone. */
export function formatDateTime(time: string | number | Date): string {
  return new Date(time).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" });
}

export function formatDate(time: string | number | Date): string {
  return new Date(time).toLocaleDateString(undefined, { dateStyle: "medium" });
}

/** The current time, updated every `intervalMs`, so relative times ("2 minutes ago") keep moving. */
export function useNow(intervalMs = 15_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now());
    }, intervalMs);
    return () => {
      clearInterval(timer);
    };
  }, [intervalMs]);
  return now;
}
