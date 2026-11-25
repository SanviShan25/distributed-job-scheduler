import { useQuery } from "@tanstack/react-query";
import { api } from "../api/client";
import type { MonitorStatus } from "../components/ui";

/** GET /api/monitors/{id} (the fields the app uses so far). */
export interface Monitor {
  id: string;
  name: string;
  url: string;
  intervalSeconds: number;
  enabled: boolean;
  status: MonitorStatus;
  lastCheckedAt: string | null;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Monitor ids are UUIDs; anything else cannot exist, so there is no need to ask the backend. */
export function isMonitorId(id: string | undefined): id is string {
  return id !== undefined && UUID.test(id);
}

export function useMonitor(id: string | undefined) {
  return useQuery({
    queryKey: ["monitors", id],
    queryFn: ({ signal }) => api.get<Monitor>(`/api/monitors/${id ?? ""}`, { signal }),
    enabled: isMonitorId(id),
  });
}
