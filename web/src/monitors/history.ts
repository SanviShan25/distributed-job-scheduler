import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import { formatDateTime } from "../lib/time";
import { DASHBOARD_REFRESH_MS, formatResponseTime, monitorsQueryKey } from "./monitors";

/** A page of results, as the backend's PageResponse returns it. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** One check, as GET /api/monitors/{id}/checks returns it. */
export interface Check {
  checkedAt: string;
  up: boolean;
  /** Null when no HTTP answer arrived (timeout, unknown host...). */
  statusCode: number | null;
  responseMs: number | null;
  /** The checker's reason for a failed check, e.g. "HTTP 503" or "timed out after 10s". */
  error: string | null;
}

/** One outage, as GET /api/monitors/{id}/incidents returns it. */
export interface Incident {
  id: string;
  startedAt: string;
  resolvedAt: string | null;
  ongoing: boolean;
  /** Up to the resolution, or up to when the server answered for an ongoing incident. */
  durationSeconds: number;
  cause: string | null;
}

export type ChartRange = "24h" | "7d";

export const RANGE_LABELS: Record<ChartRange, string> = { "24h": "24 hours", "7d": "7 days" };

export interface ResponseTimeBucket {
  start: string;
  checks: number;
  failures: number;
  /** Average of the successful checks in the bucket; null when there were none. */
  avgResponseMs: number | null;
  maxResponseMs: number | null;
}

export interface ResponseTimes {
  range: ChartRange;
  bucketSeconds: number;
  buckets: ResponseTimeBucket[];
}

export const CHECKS_PAGE_SIZE = 20;
export const INCIDENTS_PAGE_SIZE = 10;
/** The chart's buckets are 5 minutes or longer, so refreshing it once a minute is plenty. */
const CHART_REFRESH_MS = 60_000;

// Keys sit under the monitor's own key, so forgetMonitor() drops them with it.
const historyKey = (id: string, ...rest: (string | number)[]) =>
  [...monitorsQueryKey, id, ...rest] as const;

export function useChecks(id: string, page: number) {
  return useQuery({
    queryKey: historyKey(id, "checks", page),
    queryFn: ({ signal }) =>
      api.get<Page<Check>>(
        `/api/monitors/${id}/checks?page=${String(page)}&size=${String(CHECKS_PAGE_SIZE)}`,
        { signal },
      ),
    // Only the newest page changes by itself; older pages would shift under the reader.
    refetchInterval: page === 0 ? DASHBOARD_REFRESH_MS : false,
    placeholderData: keepPreviousData,
  });
}

export function useIncidents(id: string, page: number) {
  return useQuery({
    queryKey: historyKey(id, "incidents", page),
    queryFn: ({ signal }) =>
      api.get<Page<Incident>>(
        `/api/monitors/${id}/incidents?page=${String(page)}&size=${String(INCIDENTS_PAGE_SIZE)}`,
        { signal },
      ),
    refetchInterval: page === 0 ? DASHBOARD_REFRESH_MS : false,
    placeholderData: keepPreviousData,
  });
}

export function useResponseTimes(id: string, range: ChartRange) {
  return useQuery({
    queryKey: historyKey(id, "response-times", range),
    queryFn: ({ signal }) =>
      api.get<ResponseTimes>(`/api/monitors/${id}/response-times?range=${range}`, { signal }),
    refetchInterval: CHART_REFRESH_MS,
  });
}

const MINUTE = 60;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

function plural(n: number, unit: string): string {
  return `${String(n)} ${unit}${n === 1 ? "" : "s"}`;
}

/**
 * How long an outage lasted, in words: "less than a minute", "12 minutes", "2 hours 5 minutes",
 * "3 days 4 hours". Rounded to the nearest minute, like the alert emails.
 */
export function formatDuration(seconds: number): string {
  const minutes = Math.round(seconds / MINUTE);
  if (minutes < 1) return "less than a minute";
  if (minutes < 60) return plural(minutes, "minute");
  if (minutes < DAY / MINUTE) {
    const h = Math.floor(minutes / 60);
    const m = minutes % 60;
    return m === 0 ? plural(h, "hour") : `${plural(h, "hour")} ${plural(m, "minute")}`;
  }
  // From a day up, to the nearest hour.
  const hours = Math.round(seconds / HOUR);
  const d = Math.floor(hours / 24);
  const h = hours % 24;
  return h === 0 ? plural(d, "day") : `${plural(d, "day")} ${plural(h, "hour")}`;
}

/**
 * Seconds an incident has lasted: fixed once resolved, still growing while ongoing (so the page
 * keeps counting between refreshes).
 */
export function incidentSeconds(incident: Incident, now: number): number {
  if (!incident.ongoing) return incident.durationSeconds;
  return Math.max(0, (now - Date.parse(incident.startedAt)) / 1000);
}

/**
 * The checker's reason for a failed check (written for operators: "HTTP 503", "timed out after
 * 10s", "TLS error: …") in plain words for website owners. Never shows a raw status code.
 */
export function describeCause(cause: string | null): string {
  const text = (cause ?? "").trim();
  const http = /^HTTP (\d{3})$/.exec(text);
  if (http?.[1]) return describeHttpStatus(Number(http[1]));

  const timeout = /^timed out after (\d+)s$/i.exec(text);
  if (timeout?.[1]) return `The website took longer than ${timeout[1]} seconds to respond.`;

  if (/could not resolve host/i.test(text)) {
    return "The website's address couldn't be found. Its domain may have expired or its DNS settings may be wrong.";
  }
  if (/^connection refused/i.test(text)) return "The website's server refused the connection.";
  if (/^host unreachable/i.test(text)) return "The website's server couldn't be reached.";
  if (/^TLS error/i.test(text)) {
    return "There's a problem with the website's security certificate (HTTPS).";
  }
  if (/^redirect blocked/i.test(text)) {
    return "The website redirected to a private network address, which we don't follow.";
  }
  if (/^blocked/i.test(text)) {
    return "The address points to a private network, which we can't check.";
  }
  if (/too many redirects/i.test(text)) return "The website redirected too many times.";
  if (/redirect to an invalid/i.test(text)) {
    return "The website redirected to an address we can't check.";
  }
  if (/^connection error/i.test(text)) return "The connection to the website failed.";
  return "The website didn't respond normally.";
}

function describeHttpStatus(status: number): string {
  if (status === 404 || status === 410) return "The page wasn't found on the website.";
  if (status === 401 || status === 403) return "The website refused access to the page.";
  if (status === 429) return "The website said it was receiving too many requests.";
  if (status === 503) return "The website said it was temporarily unavailable.";
  if (status === 502 || status === 504) {
    return "The website's server didn't get an answer from the service behind it.";
  }
  if (status >= 500) return "The website's server had an internal error.";
  return "The website rejected the request.";
}

/** A one-sentence summary of the chart, shown above it and used as its screen-reader description. */
export function summarize(
  buckets: readonly ResponseTimeBucket[],
  range: ChartRange,
): { checks: number; text: string } {
  let checks = 0;
  let failures = 0;
  let upChecks = 0;
  let weightedSum = 0;
  let slowest: ResponseTimeBucket | undefined;
  for (const b of buckets) {
    checks += b.checks;
    failures += b.failures;
    if (b.avgResponseMs !== null) {
      const up = b.checks - b.failures;
      upChecks += up;
      weightedSum += b.avgResponseMs * up;
      if (slowest?.avgResponseMs == null || b.avgResponseMs > slowest.avgResponseMs) slowest = b;
    }
  }
  const parts: string[] = [];
  if (upChecks > 0) {
    parts.push(
      `Average ${formatResponseTime(weightedSum / upChecks)} over the last ${RANGE_LABELS[range]}.`,
    );
  }
  if (slowest?.avgResponseMs != null) {
    const when =
      range === "24h"
        ? new Date(slowest.start).toLocaleTimeString(undefined, {
            hour: "2-digit",
            minute: "2-digit",
          })
        : formatDateTime(slowest.start);
    parts.push(`Slowest around ${when} (${formatResponseTime(slowest.avgResponseMs)}).`);
  }
  if (failures > 0) {
    parts.push(failures === 1 ? "1 check failed." : `${String(failures)} checks failed.`);
  } else if (checks > 0) {
    parts.push("No failed checks.");
  }
  return { checks, text: parts.join(" ") };
}
