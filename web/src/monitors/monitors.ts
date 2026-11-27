import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api, ApiError } from "../api/client";
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
const listKey = [...monitorsQueryKey, "list"] as const;
const monitorKey = (id: string | undefined) => [...monitorsQueryKey, id] as const;

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
    queryKey: listKey,
    queryFn: ({ signal }) => api.get<Monitor[]>("/api/monitors", { signal }),
    refetchInterval: DASHBOARD_REFRESH_MS,
  });
}

/** While a check is on its way (new, resumed, new address), look often; give up after 2 minutes. */
const PENDING_CHECK_POLL_MS = 3_000;
const PENDING_CHECK_MAX_MS = 2 * 60_000;

/**
 * True when the monitor is waiting for its first check since it was created, resumed or given a new
 * address. A check sets updatedAt to its own time, so lastCheckedAt is older than updatedAt only while
 * a check is still owed. (After one failed check the status also stays UNKNOWN, but then the two match.)
 */
export function isCheckPending(monitor: Monitor): boolean {
  return (
    monitor.enabled &&
    monitor.status === "UNKNOWN" &&
    (monitor.lastCheckedAt === null ||
      Date.parse(monitor.lastCheckedAt) < Date.parse(monitor.updatedAt))
  );
}

export function useMonitor(id: string | undefined) {
  return useQuery({
    queryKey: monitorKey(id),
    queryFn: ({ signal }) => api.get<Monitor>(`/api/monitors/${id ?? ""}`, { signal }),
    enabled: isMonitorId(id),
    refetchInterval: (query) => {
      const monitor = query.state.data;
      if (
        monitor &&
        isCheckPending(monitor) &&
        Date.now() - Date.parse(monitor.updatedAt) < PENDING_CHECK_MAX_MS
      ) {
        return PENDING_CHECK_POLL_MS;
      }
      return DASHBOARD_REFRESH_MS;
    },
  });
}

export interface MonitorInput {
  name: string;
  url: string;
  intervalSeconds: number;
}

/**
 * Stores a monitor the backend just returned. Write endpoints leave `uptime` out (null), so the figures
 * already on screen are kept until the next refresh brings new ones.
 */
function storeMonitor(queryClient: QueryClient, monitor: Monitor): void {
  queryClient.setQueryData<Monitor>(monitorKey(monitor.id), (old) => ({
    ...monitor,
    uptime: monitor.uptime ?? old?.uptime ?? null,
  }));
  void queryClient.invalidateQueries({ queryKey: listKey });
}

export function useCreateMonitor() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: MonitorInput) => api.post<Monitor>("/api/monitors", input),
    onSuccess: (monitor) => {
      storeMonitor(queryClient, monitor);
    },
  });
}

/** What PATCH /api/monitors/{id} accepts; fields left out are unchanged. */
export type MonitorChanges = Partial<MonitorInput> & { notifyOwner?: boolean };

export function useUpdateMonitor(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (changes: MonitorChanges) => api.patch<Monitor>(`/api/monitors/${id}`, changes),
    onSuccess: (monitor) => {
      storeMonitor(queryClient, monitor);
    },
  });
}

/**
 * Pause and resume wait for the server instead of updating the screen first: resuming starts a check
 * and changes the status, which only the server knows, and the button shows progress meanwhile.
 */
export function useSetPaused(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (paused: boolean) =>
      api.post<Monitor>(`/api/monitors/${id}/${paused ? "pause" : "resume"}`),
    onSuccess: (monitor) => {
      storeMonitor(queryClient, monitor);
    },
  });
}

export function useDeleteMonitor(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      try {
        await api.delete<undefined>(`/api/monitors/${id}`);
      } catch (error) {
        // Already gone (deleted in another tab): that is what was asked for.
        if (!(error instanceof ApiError && error.status === 404)) throw error;
      }
    },
    onSuccess: () => {
      queryClient.setQueryData<Monitor[]>(listKey, (old) => old?.filter((m) => m.id !== id));
      void queryClient.invalidateQueries({ queryKey: listKey });
    },
  });
}

/** Changes the cached copy of one monitor (e.g. its recipients after adding one), if it is cached. */
export function updateCachedMonitor(
  queryClient: QueryClient,
  id: string,
  change: (monitor: Monitor) => Monitor,
): void {
  queryClient.setQueryData<Monitor>(monitorKey(id), (old) => (old ? change(old) : old));
  void queryClient.invalidateQueries({ queryKey: listKey });
}

/**
 * Drops a deleted monitor's cached details. Call it after leaving its page: removing the query while
 * the page still shows it would refetch it and flash "Website not found".
 */
export function forgetMonitor(queryClient: QueryClient, id: string): void {
  queryClient.removeQueries({ queryKey: monitorKey(id) });
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
