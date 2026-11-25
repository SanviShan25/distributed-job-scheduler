import { useParams } from "react-router";
import { ApiError } from "../api/client";
import { Alert, Button, ButtonLink, Card, Spinner, StatusBadge, TextLink } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";
import { isMonitorId, useMonitor } from "../monitors/monitors";

/**
 * One monitor: /monitors/:monitorId. The "View monitor" link in alert emails opens this page, so it
 * must exist before the full monitor screens do. For now it shows the monitor's current state.
 */
export function MonitorPage() {
  const { monitorId } = useParams();
  const { data: monitor, error, isPending, refetch, isRefetching } = useMonitor(monitorId);
  usePageTitle(monitor?.name ?? "Website");

  const notFound =
    !isMonitorId(monitorId) ||
    (error instanceof ApiError && (error.status === 404 || error.status === 400));

  let content;
  if (notFound) {
    content = (
      <Card titleAs="h1" title="Website not found">
        <p className="text-ink-muted">
          We couldn&apos;t find this website in your account. It may have been deleted, or the link
          belongs to a different account.
        </p>
        <ButtonLink to="/dashboard" className="mt-5">
          Go to your websites
        </ButtonLink>
      </Card>
    );
  } else if (error) {
    content = (
      <Alert
        variant="error"
        title="We couldn't load this website"
        action={
          <Button variant="secondary" loading={isRefetching} onClick={() => void refetch()}>
            Try again
          </Button>
        }
      >
        {error.message}
      </Alert>
    );
  } else if (isPending) {
    content = <Spinner label="Loading website…" />;
  } else {
    content = (
      <>
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight break-all sm:text-3xl">
            {monitor.name}
          </h1>
          <StatusBadge status={monitor.status} />
        </div>
        <Card>
          <dl className="grid gap-4 sm:grid-cols-[max-content_1fr] sm:gap-x-8">
            <dt className="text-sm text-ink-muted">Address</dt>
            <dd className="font-semibold break-all">{monitor.url}</dd>
            <dt className="text-sm text-ink-muted">Checked</dt>
            <dd>{monitor.enabled ? formatInterval(monitor.intervalSeconds) : "Paused"}</dd>
            <dt className="text-sm text-ink-muted">Last check</dt>
            <dd>
              {monitor.lastCheckedAt ? new Date(monitor.lastCheckedAt).toLocaleString() : "Not yet"}
            </dd>
          </dl>
        </Card>
      </>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <p>
        <TextLink to="/dashboard">← All websites</TextLink>
      </p>
      {content}
    </div>
  );
}

function formatInterval(seconds: number): string {
  if (seconds < 60) return `Every ${seconds} seconds`;
  const minutes = Math.round(seconds / 60);
  return minutes === 1 ? "Every minute" : `Every ${minutes} minutes`;
}
