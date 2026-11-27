import { CircleCheck, TriangleExclamationPoint } from "lucide-react";
import { lazy, Suspense, useState, type ReactNode } from "react";
import { Alert, Button, Card, Pager, Spinner } from "../components/ui";
import { cn } from "../lib/cn";
import { formatDateTime, formatRelative, useNow } from "../lib/time";
import {
  describeCause,
  formatDuration,
  incidentSeconds,
  RANGE_LABELS,
  summarize,
  useChecks,
  useIncidents,
  useResponseTimes,
  type ChartRange,
} from "./history";
import { formatResponseTime, formatUptime, type Monitor } from "./monitors";

const ResponseTimeChart = lazy(() => import("./ResponseTimeChart"));

/** Uptime for 24 h / 7 d / 30 d and the 24 h average response time. */
export function UptimeSummary({ monitor }: { monitor: Monitor }) {
  const uptime = monitor.uptime;
  const noData = "No checks yet";
  return (
    <Card title="Uptime">
      <dl className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Stat label="Last 24 hours">
          {uptime?.last24h != null ? formatUptime(uptime.last24h) : noData}
        </Stat>
        <Stat label="Last 7 days">
          {uptime?.last7d != null ? formatUptime(uptime.last7d) : noData}
        </Stat>
        <Stat label="Last 30 days">
          {uptime?.last30d != null ? formatUptime(uptime.last30d) : noData}
        </Stat>
        <Stat label="Average response time (24 hours)">
          {uptime?.avgResponseMs24h != null ? formatResponseTime(uptime.avgResponseMs24h) : noData}
        </Stat>
      </dl>
    </Card>
  );
}

function Stat({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-xl bg-canvas p-4">
      <dt className="text-sm text-ink-muted">{label}</dt>
      <dd className="text-xl font-bold tracking-tight">{children}</dd>
    </div>
  );
}

/** The response-time chart with a 24 h / 7 d switch. */
export function ResponseTimeCard({ monitorId }: { monitorId: string }) {
  const [range, setRange] = useState<ChartRange>("24h");
  const { data, error, isPending, refetch, isRefetching } = useResponseTimes(monitorId, range);

  let body: ReactNode;
  if (data) {
    const summary = summarize(data.buckets, range);
    body =
      summary.checks === 0 ? (
        <p className="text-ink-muted">No checks in the last {RANGE_LABELS[range]} yet.</p>
      ) : (
        <figure className="flex flex-col gap-3">
          <figcaption className="text-sm text-ink-muted">{summary.text}</figcaption>
          <Suspense fallback={<Spinner label="Loading chart…" />}>
            <ResponseTimeChart
              range={data.range}
              bucketSeconds={data.bucketSeconds}
              buckets={data.buckets}
              description={summary.text}
            />
          </Suspense>
          <p className="flex flex-wrap gap-x-5 gap-y-1 text-sm text-ink-muted" aria-hidden="true">
            <span className="flex items-center gap-2">
              <span className="inline-block h-0.5 w-5 bg-brand-600" /> Average response time
            </span>
            <span className="flex items-center gap-2">
              <TriangleExclamationPoint className="size-4 text-down-700" /> Failed checks
            </span>
          </p>
        </figure>
      );
  } else if (isPending) {
    body = <Spinner label="Loading response times…" />;
  } else {
    body = (
      <Alert
        variant="error"
        title="We couldn't load the response times"
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

  const rangeSwitch = (
    <div role="group" aria-label="Time range" className="flex gap-1 rounded-lg bg-canvas p-1">
      {(["24h", "7d"] as const).map((r) => (
        <button
          key={r}
          type="button"
          aria-pressed={range === r}
          onClick={() => {
            setRange(r);
          }}
          className={cn(
            "min-h-11 rounded-md px-3 text-sm font-semibold",
            range === r
              ? "bg-surface text-ink shadow-sm ring-1 ring-line-strong"
              : "text-ink-muted hover:text-ink",
          )}
        >
          {RANGE_LABELS[r]}
        </button>
      ))}
    </div>
  );

  return (
    <Card title="Response time" actions={rangeSwitch}>
      {body}
    </Card>
  );
}

/** Outages, newest first, with paging. */
export function IncidentHistory({ monitorId }: { monitorId: string }) {
  const [page, setPage] = useState(0);
  const { data, error, isPending, isPlaceholderData, refetch, isRefetching } = useIncidents(
    monitorId,
    page,
  );
  const now = useNow();

  let body: ReactNode;
  if (data) {
    body =
      data.totalElements === 0 ? (
        <p className="text-ink-muted">No outages so far.</p>
      ) : (
        <>
          <ol
            className={cn("flex flex-col divide-y divide-line", isPlaceholderData && "opacity-60")}
            aria-busy={isPlaceholderData || undefined}
          >
            {data.content.map((incident) => {
              const duration = formatDuration(incidentSeconds(incident, now));
              return (
                <li key={incident.id} className="flex flex-col gap-1 py-4 first:pt-0 last:pb-0">
                  <p className="flex flex-wrap items-center gap-2 font-semibold">
                    {incident.ongoing ? (
                      <>
                        <span className="inline-flex items-center gap-1.5 rounded-full border border-down-200 bg-down-50 px-2 py-0.5 text-xs font-semibold text-down-700">
                          <TriangleExclamationPoint aria-hidden="true" className="size-3.5" />
                          Ongoing
                        </span>
                        Down for {duration} so far
                      </>
                    ) : (
                      <>Down for {duration}</>
                    )}
                  </p>
                  <p className="text-sm text-ink-muted">
                    Started <Time value={incident.startedAt} />
                    {incident.resolvedAt && (
                      <>
                        {" "}
                        · Back up <Time value={incident.resolvedAt} />
                      </>
                    )}
                  </p>
                  <p>{describeCause(incident.cause)}</p>
                </li>
              );
            })}
          </ol>
          <Pager
            page={page}
            totalPages={data.totalPages}
            onChange={setPage}
            label="Outage history pages"
          />
        </>
      );
  } else if (isPending) {
    body = <Spinner label="Loading outages…" />;
  } else {
    body = (
      <LoadError what="outages" message={error.message} retrying={isRefetching} retry={refetch} />
    );
  }

  return <Card title="Outages">{body}</Card>;
}

/** Individual checks, newest first, with paging. */
export function RecentChecks({ monitorId }: { monitorId: string }) {
  const [page, setPage] = useState(0);
  const { data, error, isPending, isPlaceholderData, refetch, isRefetching } = useChecks(
    monitorId,
    page,
  );
  const now = useNow();

  let body: ReactNode;
  if (data) {
    body =
      data.totalElements === 0 ? (
        <p className="text-ink-muted">No checks yet.</p>
      ) : (
        <>
          {/* Scrolls sideways on narrow screens instead of the whole page; focusable so keyboard users can scroll it. */}
          <div
            className="-mx-5 overflow-x-auto px-5 sm:mx-0 sm:px-0"
            tabIndex={0}
            role="region"
            aria-label="Recent checks table"
          >
            <table
              className={cn("w-full text-left text-sm", isPlaceholderData && "opacity-60")}
              aria-busy={isPlaceholderData || undefined}
            >
              <caption className="sr-only">Recent checks, newest first</caption>
              <thead className="border-b border-line text-ink-muted">
                <tr>
                  <th scope="col" className="py-2 pr-4 font-semibold">
                    Time
                  </th>
                  <th scope="col" className="py-2 pr-4 font-semibold">
                    Result
                  </th>
                  <th scope="col" className="py-2 pr-4 font-semibold whitespace-nowrap">
                    Response time
                  </th>
                  <th scope="col" className="py-2 font-semibold">
                    Details
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {data.content.map((check, index) => (
                  <tr key={`${check.checkedAt}-${String(index)}`}>
                    <td className="py-2.5 pr-4 align-top whitespace-nowrap">
                      <time dateTime={check.checkedAt} title={formatDateTime(check.checkedAt)}>
                        {formatRelative(check.checkedAt, now)}
                      </time>
                    </td>
                    <td className="py-2.5 pr-4 align-top">
                      {check.up ? (
                        <span className="inline-flex items-center gap-1.5 font-semibold text-up-700">
                          <CircleCheck aria-hidden="true" className="size-4" /> Up
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1.5 font-semibold text-down-700">
                          <TriangleExclamationPoint aria-hidden="true" className="size-4" /> Failed
                        </span>
                      )}
                    </td>
                    <td className="py-2.5 pr-4 align-top whitespace-nowrap">
                      {check.responseMs !== null && check.up
                        ? formatResponseTime(check.responseMs)
                        : "—"}
                    </td>
                    <td className="min-w-48 py-2.5 align-top">
                      {check.up ? "Responded normally" : describeCause(check.error)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={page}
            totalPages={data.totalPages}
            onChange={setPage}
            label="Recent checks pages"
          />
        </>
      );
  } else if (isPending) {
    body = <Spinner label="Loading checks…" />;
  } else {
    body = (
      <LoadError what="checks" message={error.message} retrying={isRefetching} retry={refetch} />
    );
  }

  return <Card title="Recent checks">{body}</Card>;
}

function Time({ value }: { value: string }) {
  return <time dateTime={value}>{formatDateTime(value)}</time>;
}

function LoadError({
  what,
  message,
  retrying,
  retry,
}: {
  what: string;
  message: string;
  retrying: boolean;
  retry: () => Promise<unknown>;
}) {
  return (
    <Alert
      variant="error"
      title={`We couldn't load the ${what}`}
      action={
        <Button variant="secondary" loading={retrying} onClick={() => void retry()}>
          Try again
        </Button>
      }
    >
      {message}
    </Alert>
  );
}
