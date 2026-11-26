import { Globe, Plus } from "lucide-react";
import { useEffect, useId, useState } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import { useCurrentUser } from "../auth/user";
import { Alert, Button, ButtonLink, Card, StatusBadge } from "../components/ui";
import { formatDateTime, formatRelative, useNow } from "../lib/time";
import { usePageTitle } from "../lib/usePageTitle";
import type { DashboardNotice } from "./MonitorPage";
import {
  displayStatus,
  formatResponseTime,
  formatUptime,
  shortUrl,
  sortForDashboard,
  useMonitors,
  type Monitor,
} from "../monitors/monitors";

const ADD_WEBSITE_PATH = "/monitors/new";

export function DashboardPage() {
  usePageTitle("Your websites");
  const { data: user } = useCurrentUser();
  const { data: monitors, error, isPending, isRefetching, refetch, dataUpdatedAt } = useMonitors();
  const now = useNow();
  const notice = useNotice();

  const limit = user?.monitorLimit;
  const count = monitors?.length;
  const atLimit = limit !== undefined && count !== undefined && count >= limit;

  let content;
  if (monitors) {
    content =
      monitors.length === 0 ? (
        <EmptyState />
      ) : (
        <>
          {error && (
            <Alert variant="warning" title="We couldn't refresh your websites">
              Showing what we knew {formatRelative(dataUpdatedAt, now)}. We&apos;ll keep trying.
            </Alert>
          )}
          <ul className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
            {sortForDashboard(monitors).map((monitor) => (
              <li key={monitor.id} className="flex">
                <MonitorCard monitor={monitor} now={now} />
              </li>
            ))}
          </ul>
        </>
      );
  } else if (isPending) {
    content = <LoadingCards />;
  } else {
    content = (
      <Alert
        variant="error"
        title="We couldn't load your websites"
        action={
          <Button variant="secondary" loading={isRefetching} onClick={() => void refetch()}>
            Try again
          </Button>
        }
      >
        {error.message}
      </Alert>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="flex flex-col gap-1">
          <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Your websites</h1>
          {monitors && monitors.length > 0 && (
            <p className="text-sm text-ink-muted">
              {limit === undefined
                ? `${String(monitors.length)} ${monitors.length === 1 ? "website" : "websites"}`
                : `${String(monitors.length)} of ${String(limit)} websites`}
              <span aria-hidden="true"> · </span>
              <time dateTime={new Date(dataUpdatedAt).toISOString()}>
                Updated {formatRelative(dataUpdatedAt, now)}
              </time>
            </p>
          )}
        </div>
        {monitors && monitors.length > 0 && <AddWebsiteButton atLimit={atLimit} limit={limit} />}
      </div>
      {notice && <Alert variant="success">{notice}</Alert>}
      {content}
    </div>
  );
}

/**
 * A one-time message passed in router state (e.g. "Shop and its history have been deleted."). It is
 * read once and then removed from the history entry, so reloading or going back doesn't repeat it.
 */
function useNotice(): string | undefined {
  const location = useLocation();
  const navigate = useNavigate();
  const [notice] = useState(() => (location.state as Partial<DashboardNotice> | null)?.notice);
  const hasState = location.state !== null;
  useEffect(() => {
    if (hasState) void navigate(".", { replace: true, state: null });
  }, [hasState, navigate]);
  return notice;
}

function AddWebsiteButton({ atLimit, limit }: { atLimit: boolean; limit: number | undefined }) {
  const hintId = useId();
  if (!atLimit) {
    return (
      <ButtonLink to={ADD_WEBSITE_PATH} className="gap-2">
        <Plus aria-hidden="true" className="size-5" />
        Add website
      </ButtonLink>
    );
  }
  return (
    <div className="flex flex-col items-start gap-1 sm:items-end">
      <Button
        disabled
        aria-describedby={hintId}
        icon={<Plus aria-hidden="true" className="size-5" />}
      >
        Add website
      </Button>
      <p id={hintId} className="text-sm text-ink-muted">
        You&apos;ve reached the limit of {limit} websites. Delete one to add another.
      </p>
    </div>
  );
}

function MonitorCard({ monitor, now }: { monitor: Monitor; now: number }) {
  const status = displayStatus(monitor);
  const uptime = monitor.uptime;
  const nameId = useId();
  return (
    // The name is the card's link; its ::after covers the whole card, so the card is one big click
    // target while screen readers hear a single link named after the website.
    <article
      aria-labelledby={nameId}
      className={
        "relative flex w-full flex-col gap-4 rounded-2xl border bg-surface p-5 shadow-sm transition-shadow " +
        "focus-within:ring-3 focus-within:ring-brand-600 hover:shadow-md " +
        (status === "DOWN" ? "border-down-200" : "border-line")
      }
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 id={nameId} className="text-lg font-bold tracking-tight break-words">
            <Link
              to={`/monitors/${monitor.id}`}
              className="rounded-sm after:absolute after:inset-0 after:rounded-2xl focus-visible:outline-none"
            >
              {monitor.name}
            </Link>
          </h2>
          <p className="truncate text-sm text-ink-muted" title={monitor.url}>
            {shortUrl(monitor.url)}
          </p>
        </div>
        <StatusBadge status={status} size="sm" className="shrink-0" />
      </div>
      <dl className="grid grid-cols-2 gap-4">
        <div className="flex flex-col gap-1">
          <dt className="text-sm text-ink-muted">Uptime (24 h)</dt>
          <dd className="text-lg font-semibold">
            {uptime?.last24h == null ? <NoData /> : formatUptime(uptime.last24h)}
          </dd>
        </div>
        <div className="flex flex-col gap-1">
          <dt className="text-sm text-ink-muted">Avg. response</dt>
          <dd className="text-lg font-semibold">
            {uptime?.avgResponseMs24h == null ? (
              <NoData />
            ) : (
              formatResponseTime(uptime.avgResponseMs24h)
            )}
          </dd>
        </div>
      </dl>
      <p className="mt-auto text-sm text-ink-muted">
        <LastChecked monitor={monitor} now={now} />
      </p>
    </article>
  );
}

function LastChecked({ monitor, now }: { monitor: Monitor; now: number }) {
  if (!monitor.lastCheckedAt) {
    return monitor.enabled ? "First check on its way…" : "Paused before the first check";
  }
  const when = (
    <time dateTime={monitor.lastCheckedAt} title={formatDateTime(monitor.lastCheckedAt)}>
      {formatRelative(monitor.lastCheckedAt, now)}
    </time>
  );
  return monitor.enabled ? <>Last checked {when}</> : <>Paused · last checked {when}</>;
}

function NoData() {
  return <span className="text-base font-normal text-ink-muted">No data yet</span>;
}

function EmptyState() {
  return (
    <Card className="flex flex-col items-center text-center">
      <Globe aria-hidden="true" className="size-12 text-brand-600" />
      <h2 className="mt-4 text-xl font-bold tracking-tight sm:text-2xl">Add your first website</h2>
      <p className="mt-2 max-w-md text-ink-muted">
        Tell us which website to watch. We&apos;ll check it around the clock and email you as soon
        as it goes down, and again when it&apos;s back.
      </p>
      <ButtonLink to={ADD_WEBSITE_PATH} className="mt-6 gap-2">
        <Plus aria-hidden="true" className="size-5" />
        Add your first website
      </ButtonLink>
    </Card>
  );
}

function LoadingCards() {
  return (
    <div>
      <p role="status" className="sr-only">
        Loading your websites…
      </p>
      <ul aria-hidden="true" className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
        {[0, 1, 2].map((i) => (
          <li
            key={i}
            className="flex animate-pulse flex-col gap-4 rounded-2xl border border-line bg-surface p-5"
          >
            <div className="flex justify-between gap-3">
              <div className="flex flex-col gap-2">
                <div className="h-5 w-32 rounded bg-unknown-200" />
                <div className="h-4 w-44 rounded bg-unknown-50" />
              </div>
              <div className="h-6 w-14 rounded-full bg-unknown-50" />
            </div>
            <div className="grid grid-cols-2 gap-4">
              <div className="h-10 rounded bg-unknown-50" />
              <div className="h-10 rounded bg-unknown-50" />
            </div>
            <div className="h-4 w-36 rounded bg-unknown-50" />
          </li>
        ))}
      </ul>
    </div>
  );
}
