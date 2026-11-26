import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { MonitorStatus } from "../components/ui";

export type RecipientStatus = "PENDING" | "CONFIRMED" | "UNSUBSCRIBED";

export interface Recipient {
  id: string;
  email: string;
  status: RecipientStatus;
  createdAt: string;
  confirmedAt: string | null;
  unsubscribedAt: string | null;
}

/**
 * Uptime percentages (0–100) and the average response time of successful checks. A value is null when
 * there were no checks in that window. The monitor list fills in the 24 h figures only.
 */
export interface Uptime {
  last24h: number | null;
  last7d: number | null;
  last30d: number | null;
  avgResponseMs24h: number | null;
  checks24h: number;
}

/** A monitor as GET /api/monitors and GET /api/monitors/{id} return it. */
export interface Monitor {
  id: string;
  name: string;
  url: string;
  intervalSeconds: number;
  enabled: boolean;
  status: MonitorStatus;
  lastCheckedAt: string | null;
  notifyOwner: boolean;
  recipients: Recipient[];
  createdAt: string;
  updatedAt: string;
  uptime: Uptime | null;
}

export const monitorsQueryKey = ["monitors"] as const;

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Monitor ids are UUIDs; anything else cannot exist, so there is no need to ask the backend. */
export function isMonitorId(id: string | undefined): id is string {
  return id !== undefined && UUID.test(id);
}

/**
 * How often the dashboard refreshes. TanStack Query pauses interval refetches while the tab is hidden
 * (refetchIntervalInBackground is false by default) and refetches when it becomes visible again.
 */
export const DASHBOARD_REFRESH_MS = 30_000;

export function useMonitors() {
  return useQuery({
    queryKey: [...monitorsQueryKey, "list"],
    queryFn: ({ signal }) => api.get<Monitor[]>("/api/monitors", { signal }),
    refetchInterval: DASHBOARD_REFRESH_MS,
  });
}

export function useMonitor(id: string | undefined) {
  return useQuery({
    queryKey: [...monitorsQueryKey, id],
    queryFn: ({ signal }) => api.get<Monitor>(`/api/monitors/${id ?? ""}`, { signal }),
    enabled: isMonitorId(id),
  });
}

/** What to show: a paused monitor is "Paused" whatever its last status was. */
export function displayStatus(monitor: Pick<Monitor, "enabled" | "status">): MonitorStatus {
  return monitor.enabled ? monitor.status : "PAUSED";
}

const byName = new Intl.Collator(undefined, { sensitivity: "base", numeric: true });

/** Down sites first (they need attention), then alphabetically by name. */
export function sortForDashboard(monitors: readonly Monitor[]): Monitor[] {
  return [...monitors].sort((a, b) => {
    const aDown = displayStatus(a) === "DOWN" ? 0 : 1;
    const bDown = displayStatus(b) === "DOWN" ? 0 : 1;
    return aDown - bDown || byName.compare(a.name, b.name);
  });
}

/** "100%", "99.5%", "99.95%" (the backend rounds to 2 decimals). */
export function formatUptime(percent: number): string {
  return `${String(Number(percent.toFixed(2)))}%`;
}

/** "245 ms", "1.2 s". */
export function formatResponseTime(ms: number): string {
  if (ms < 1000) return `${String(Math.round(ms))} ms`;
  return `${(ms / 1000).toFixed(1)} s`;
}

export function formatInterval(seconds: number): string {
  if (seconds < 60) return `Every ${String(seconds)} seconds`;
  if (seconds === 3600) return "Every hour";
  const minutes = Math.round(seconds / 60);
  return minutes === 1 ? "Every minute" : `Every ${String(minutes)} minutes`;
}

/** The address without "https://" and a bare trailing "/", for compact display. */
export function shortUrl(url: string): string {
  return url.replace(/^https?:\/\//i, "").replace(/\/$/, "");
}
