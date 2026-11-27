import type { Monitor } from "../monitors/monitors";

let nextId = 1;

/** A monitor as the backend returns it: up, checked 2 minutes ago, unless overridden. */
export function aMonitor(overrides: Partial<Monitor> = {}): Monitor {
  const id = `00000000-0000-4000-8000-${String(nextId++).padStart(12, "0")}`;
  return {
    id,
    name: "Site",
    url: "https://site.example.com/",
    intervalSeconds: 300,
    enabled: true,
    status: "UP",
    lastCheckedAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    notifyOwner: true,
    recipients: [],
    createdAt: "2026-11-20T10:00:00Z",
    updatedAt: new Date(Date.now() - 2 * 60_000).toISOString(),
    uptime: { last24h: 100, last7d: null, last30d: null, avgResponseMs24h: 245, checks24h: 288 },
    ...overrides,
  };
}
