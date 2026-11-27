import { CircleCheck, CircleMinus, Clock, Mail, Trash2 } from "lucide-react";
import { useRef, useState, type SyntheticEvent } from "react";
import { normalizeEmail, validateEmail } from "../auth/validation";
import { useCurrentUser } from "../auth/user";
import { Alert, Button, Card, Input } from "../components/ui";
import { useUpdateMonitor, type Monitor, type Recipient, type RecipientStatus } from "./monitors";
import {
  activeRecipients,
  MAX_EXTRA_RECIPIENTS,
  RECIPIENT_STATUS_LABELS,
  recipientError,
  useAddRecipient,
  useRemoveRecipient,
  useResendConfirmation,
} from "./recipients";

const STATUS_STYLES: Record<RecipientStatus, { icon: typeof Clock; classes: string }> = {
  PENDING: { icon: Clock, classes: "text-warning-700" },
  CONFIRMED: { icon: CircleCheck, classes: "text-up-700" },
  UNSUBSCRIBED: { icon: CircleMinus, classes: "text-ink-muted" },
};

/**
 * Who gets DOWN/UP emails for this website: the account owner, plus up to 5 extra people who must
 * confirm by email first (double opt-in).
 */
export function AlertRecipients({ monitor }: { monitor: Monitor }) {
  const { data: user } = useCurrentUser();
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  // After "Remove", the row and its focused button disappear; focus moves to the message instead of
  // dropping to the page body.
  const messagesRef = useRef<HTMLDivElement>(null);
  const add = useAddRecipient(monitor.id);
  const remove = useRemoveRecipient(monitor.id);
  const resend = useResendConfirmation(monitor.id);
  const turnOn = useUpdateMonitor(monitor.id);
  const active = activeRecipients(monitor.recipients);
  const full = active >= MAX_EXTRA_RECIPIENTS;

  function report(message: string) {
    setActionError(null);
    setNotice(message);
  }
  function fail(error: Error) {
    setNotice(null);
    setActionError(recipientError(error));
  }

  return (
    <Card
      title="Alert emails"
      description="We email these people when the website goes down and when it's back up."
    >
      <div className="flex flex-col gap-5">
        {(notice ?? actionError) && (
          <div ref={messagesRef} tabIndex={-1} className="flex flex-col gap-3 focus:outline-none">
            {notice && <Alert variant="success">{notice}</Alert>}
            {actionError && <Alert variant="error">{actionError}</Alert>}
          </div>
        )}

        <ul className="flex flex-col divide-y divide-line rounded-xl border border-line">
          <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 p-4">
            <div className="min-w-0">
              <p className="font-semibold break-all">{user?.email ?? "Your email address"}</p>
              <p className="text-sm text-ink-muted">You (account owner)</p>
            </div>
            {monitor.notifyOwner ? (
              <RecipientStatusLabel status="CONFIRMED" />
            ) : (
              <div className="flex flex-wrap items-center gap-3">
                <RecipientStatusLabel status="UNSUBSCRIBED" />
                <Button
                  variant="secondary"
                  loading={turnOn.isPending}
                  loadingText="Turning on…"
                  onClick={() => {
                    turnOn.mutate(
                      { notifyOwner: true },
                      {
                        onSuccess: () => {
                          report("You'll get this website's alerts again.");
                        },
                        onError: fail,
                      },
                    );
                  }}
                >
                  Send me alerts again
                </Button>
              </div>
            )}
          </li>
          {monitor.recipients.map((recipient) => (
            <RecipientRow
              key={recipient.id}
              recipient={recipient}
              removing={remove.isPending && remove.variables === recipient.id}
              resending={resend.isPending && resend.variables === recipient.id}
              onRemove={() => {
                remove.mutate(recipient.id, {
                  onSuccess: () => {
                    report(`${recipient.email} won't get alerts for this website any more.`);
                    requestAnimationFrame(() => messagesRef.current?.focus());
                  },
                  onError: fail,
                });
              }}
              onResend={() => {
                resend.mutate(recipient.id, {
                  onSuccess: () => {
                    report(
                      `We've sent a new confirmation email to ${recipient.email}. Earlier links no longer work.`,
                    );
                  },
                  onError: fail,
                });
              }}
            />
          ))}
        </ul>

        {full ? (
          <p className="text-sm text-ink-muted">
            You&apos;ve added {MAX_EXTRA_RECIPIENTS} people, the most a website can have. Remove
            someone to add another.
          </p>
        ) : (
          <AddRecipientForm
            adding={add.isPending}
            error={add.error ? recipientError(add.error) : undefined}
            remaining={MAX_EXTRA_RECIPIENTS - active}
            onAdd={(email, done) => {
              setNotice(null);
              setActionError(null);
              add.mutate(email, {
                onSuccess: (added) => {
                  done();
                  report(
                    `We've emailed ${added.email} a link to confirm. They'll get alerts once they click it.`,
                  );
                },
              });
            }}
            onEdit={() => {
              if (add.error) add.reset();
            }}
          />
        )}
      </div>
    </Card>
  );
}

function RecipientStatusLabel({ status }: { status: RecipientStatus }) {
  const { icon: Icon, classes } = STATUS_STYLES[status];
  return (
    <span className={`inline-flex items-center gap-1.5 text-sm font-semibold ${classes}`}>
      <Icon aria-hidden="true" className="size-4" />
      {RECIPIENT_STATUS_LABELS[status]}
    </span>
  );
}

function RecipientRow({
  recipient,
  removing,
  resending,
  onRemove,
  onResend,
}: {
  recipient: Recipient;
  removing: boolean;
  resending: boolean;
  onRemove: () => void;
  onResend: () => void;
}) {
  return (
    <li className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 p-4">
      <div className="flex min-w-0 flex-col gap-1">
        <p className="font-semibold break-all">{recipient.email}</p>
        <RecipientStatusLabel status={recipient.status} />
      </div>
      <div className="flex flex-wrap gap-2">
        {recipient.status === "PENDING" && (
          <Button
            variant="secondary"
            icon={<Mail aria-hidden="true" className="size-4" />}
            loading={resending}
            loadingText="Sending…"
            aria-label={`Resend confirmation email to ${recipient.email}`}
            onClick={onResend}
          >
            Resend confirmation
          </Button>
        )}
        <Button
          variant="secondary"
          className="text-down-700"
          icon={<Trash2 aria-hidden="true" className="size-4" />}
          loading={removing}
          loadingText="Removing…"
          aria-label={`Remove ${recipient.email}`}
          onClick={onRemove}
        >
          Remove
        </Button>
      </div>
    </li>
  );
}

function AddRecipientForm({
  adding,
  error,
  remaining,
  onAdd,
  onEdit,
}: {
  adding: boolean;
  error: string | undefined;
  remaining: number;
  onAdd: (email: string, done: () => void) => void;
  onEdit: () => void;
}) {
  const [email, setEmail] = useState("");
  const [clientError, setClientError] = useState<string | undefined>();
  const inputRef = useRef<HTMLInputElement>(null);

  function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    // validateEmail's wording ("Enter your email address") is for the user's own address.
    const problem = normalizeEmail(email)
      ? validateEmail(email)
      : "Enter the email address of the person to add.";
    if (problem) {
      setClientError(problem);
      inputRef.current?.focus();
      return;
    }
    onAdd(normalizeEmail(email), () => {
      setEmail("");
    });
  }

  const shown = clientError ?? error;
  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-3">
      <Input
        ref={inputRef}
        label="Add someone"
        type="email"
        autoComplete="email"
        inputMode="email"
        hint={`They'll get an email to confirm before receiving alerts. You can add ${String(remaining)} more.`}
        value={email}
        error={shown}
        onChange={(event) => {
          setEmail(event.target.value);
          setClientError(undefined);
          onEdit();
        }}
      />
      <div>
        <Button type="submit" loading={adding} loadingText="Adding…">
          Add recipient
        </Button>
      </div>
    </form>
  );
}
