import { ExternalLink, Pause, Pencil, Play, Trash2 } from "lucide-react";
import { useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate, useParams } from "react-router";
import { ApiError } from "../api/client";
import {
  Alert,
  Button,
  ButtonLink,
  Card,
  Dialog,
  Spinner,
  StatusBadge,
  TextLink,
} from "../components/ui";
import { formatDateTime, formatRelative, useNow } from "../lib/time";
import { usePageTitle } from "../lib/usePageTitle";
import { AlertRecipients } from "../monitors/AlertRecipients";
import {
  IncidentHistory,
  RecentChecks,
  ResponseTimeCard,
  UptimeSummary,
} from "../monitors/MonitorHistory";
import { MonitorForm } from "../monitors/MonitorForm";
import {
  displayStatus,
  formatInterval,
  isCheckPending,
  forgetMonitor,
  isMonitorId,
  useDeleteMonitor,
  useMonitor,
  useSetPaused,
  useUpdateMonitor,
  type Monitor,
  type MonitorInput,
} from "../monitors/monitors";

/** Shown on the dashboard after a website is deleted (router state). */
export interface DashboardNotice {
  notice: string;
}

/**
 * One monitor: /monitors/:monitorId. The "View monitor" link in alert emails opens this page, and
 * adding a website ends here, showing its first check arriving.
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
  } else if (monitor) {
    content = <MonitorDetails monitor={monitor} />;
  } else if (isPending) {
    content = <Spinner label="Loading website…" />;
  } else {
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

function MonitorDetails({ monitor }: { monitor: Monitor }) {
  const now = useNow();
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [saved, setSaved] = useState(false);
  const setPaused = useSetPaused(monitor.id);

  return (
    <>
      <header className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold tracking-tight break-all sm:text-3xl">
            {monitor.name}
          </h1>
          <StatusBadge status={displayStatus(monitor)} />
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="secondary"
            icon={<Pencil aria-hidden="true" className="size-4" />}
            onClick={() => {
              setSaved(false);
              setEditing(true);
            }}
          >
            Edit
          </Button>
          <Button
            variant="secondary"
            icon={
              monitor.enabled ? (
                <Pause aria-hidden="true" className="size-4" />
              ) : (
                <Play aria-hidden="true" className="size-4" />
              )
            }
            loading={setPaused.isPending}
            loadingText={monitor.enabled ? "Pausing…" : "Resuming…"}
            onClick={() => {
              setPaused.mutate(monitor.enabled);
            }}
          >
            {monitor.enabled ? "Pause checks" : "Resume checks"}
          </Button>
          <Button
            variant="secondary"
            className="text-down-700"
            icon={<Trash2 aria-hidden="true" className="size-4" />}
            onClick={() => {
              setDeleting(true);
            }}
          >
            Delete
          </Button>
        </div>
      </header>

      {setPaused.error && <Alert variant="error">{setPaused.error.message}</Alert>}
      {saved && <Alert variant="success">Your changes have been saved.</Alert>}
      <CheckProgress monitor={monitor} />

      <Card>
        <dl className="grid gap-4 sm:grid-cols-[max-content_1fr] sm:gap-x-8">
          <dt className="text-sm text-ink-muted">Address</dt>
          <dd className="font-semibold break-all">
            <WebsiteLink url={monitor.url} />
          </dd>
          <dt className="text-sm text-ink-muted">Checked</dt>
          <dd>{monitor.enabled ? formatInterval(monitor.intervalSeconds) : "Paused"}</dd>
          <dt className="text-sm text-ink-muted">Last check</dt>
          <dd>
            {monitor.lastCheckedAt ? (
              <time dateTime={monitor.lastCheckedAt} title={formatDateTime(monitor.lastCheckedAt)}>
                {formatRelative(monitor.lastCheckedAt, now)}
              </time>
            ) : (
              "Not yet"
            )}
          </dd>
        </dl>
      </Card>

      <UptimeSummary monitor={monitor} />
      <ResponseTimeCard monitorId={monitor.id} />
      <IncidentHistory monitorId={monitor.id} />
      <RecentChecks monitorId={monitor.id} />
      <AlertRecipients monitor={monitor} />

      <EditMonitorDialog
        monitor={monitor}
        open={editing}
        onClose={() => {
          setEditing(false);
        }}
        onSaved={() => {
          setEditing(false);
          setSaved(true);
        }}
      />
      <DeleteMonitorDialog
        monitor={monitor}
        open={deleting}
        onClose={() => {
          setDeleting(false);
        }}
      />
    </>
  );
}

/**
 * The monitored address, opening in a new tab. rel="noopener noreferrer": the site gets no handle on
 * this page (window.opener) and no Referer naming the monitor page. Only http(s) addresses become
 * links (the backend accepts nothing else, but a javascript: URL must never be clickable).
 */
function WebsiteLink({ url }: { url: string }) {
  if (!/^https?:\/\//i.test(url)) return <span>{url}</span>;
  return (
    <a
      href={url}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-baseline gap-1.5 rounded-sm text-brand-700 underline underline-offset-4 hover:text-brand-800"
    >
      <span>{url}</span>
      <ExternalLink aria-hidden="true" className="size-4 shrink-0 self-center" />
      <span className="sr-only">(opens in a new tab)</span>
    </a>
  );
}

/**
 * What is happening with checks right now: the first check on its way (after adding, resuming or
 * changing the address), then its result.
 */
function CheckProgress({ monitor }: { monitor: Monitor }) {
  const pending = isCheckPending(monitor);
  const [wasPending, setWasPending] = useState(pending);
  const [firstResult, setFirstResult] = useState<"up" | null>(null);
  if (pending !== wasPending) {
    setWasPending(pending);
    setFirstResult(wasPending && monitor.status === "UP" ? "up" : null);
  }

  if (pending) {
    return (
      <Alert variant="info" title="Checking your website…">
        <span className="flex items-center gap-2">
          <Spinner size="sm" decorative />
          This usually takes a few seconds. This page updates by itself.
        </span>
      </Alert>
    );
  }
  if (monitor.enabled && monitor.status === "UNKNOWN" && monitor.lastCheckedAt) {
    return (
      <Alert variant="warning" title="The last check didn't succeed">
        We&apos;ll check once more before we mark it as down and email you.
      </Alert>
    );
  }
  if (firstResult === "up" && monitor.status === "UP") {
    return <Alert variant="success">Check complete: your website is up.</Alert>;
  }
  return null;
}

function EditMonitorDialog({
  monitor,
  open,
  onClose,
  onSaved,
}: {
  monitor: Monitor;
  open: boolean;
  onClose: () => void;
  onSaved: () => void;
}) {
  const update = useUpdateMonitor(monitor.id);

  function save(input: MonitorInput) {
    // Send only what changed: a new address starts the status over, so it must not be sent by accident.
    const changes: Partial<MonitorInput> = {};
    if (input.name !== monitor.name) changes.name = input.name;
    if (input.url !== monitor.url) changes.url = input.url;
    if (input.intervalSeconds !== monitor.intervalSeconds) {
      changes.intervalSeconds = input.intervalSeconds;
    }
    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }
    update.mutate(changes, { onSuccess: onSaved });
  }

  return (
    <Dialog
      open={open}
      onClose={() => {
        update.reset();
        onClose();
      }}
      dismissible={!update.isPending}
      title={`Edit ${monitor.name}`}
    >
      <MonitorForm
        initial={monitor}
        submitLabel="Save changes"
        savingLabel="Saving…"
        saving={update.isPending}
        error={update.error}
        onSubmit={save}
        urlHint="Changing the address starts its status over with a new check."
        secondaryActions={
          <Button
            variant="ghost"
            disabled={update.isPending}
            onClick={() => {
              update.reset();
              onClose();
            }}
          >
            Cancel
          </Button>
        }
      />
    </Dialog>
  );
}

function DeleteMonitorDialog({
  monitor,
  open,
  onClose,
}: {
  monitor: Monitor;
  open: boolean;
  onClose: () => void;
}) {
  const remove = useDeleteMonitor(monitor.id);
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  return (
    <Dialog
      open={open}
      onClose={() => {
        remove.reset();
        onClose();
      }}
      dismissible={!remove.isPending}
      title={`Delete ${monitor.name} and its history?`}
      description="We'll stop checking it and permanently delete its uptime history, incidents and alert recipients. This can't be undone."
    >
      <div className="flex flex-col gap-4">
        {remove.error && <Alert variant="error">{remove.error.message}</Alert>}
        <div className="flex flex-wrap gap-3">
          <Button
            variant="danger"
            loading={remove.isPending}
            loadingText="Deleting…"
            onClick={() => {
              remove.mutate(undefined, {
                onSuccess: () => {
                  const state: DashboardNotice = {
                    notice: `${monitor.name} and its history have been deleted.`,
                  };
                  void Promise.resolve(navigate("/dashboard", { state })).then(() => {
                    forgetMonitor(queryClient, monitor.id);
                  });
                },
              });
            }}
          >
            Delete website
          </Button>
          <Button
            variant="secondary"
            data-autofocus
            disabled={remove.isPending}
            onClick={() => {
              remove.reset();
              onClose();
            }}
          >
            Cancel
          </Button>
        </div>
      </div>
    </Dialog>
  );
}
