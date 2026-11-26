import { useId, useState, type ReactNode, type SyntheticEvent } from "react";
import { Alert, Button, Input } from "../components/ui";
import { cn } from "../lib/cn";
import { focusFirstError, hasErrors, type FieldErrors } from "../lib/forms";
import { monitorFormError, type MonitorFormError } from "./errors";
import type { MonitorInput } from "./monitors";
import {
  DEFAULT_INTERVAL,
  hasScheme,
  INTERVALS,
  nameOrHost,
  normalizeUrl,
  validateName,
  validateUrl,
} from "./validation";

type Field = "name" | "url" | "interval";

export interface MonitorFormProps {
  initial?: Partial<MonitorInput>;
  submitLabel: string;
  savingLabel: string;
  saving: boolean;
  /** The last save's error, shown on its field or above the form. */
  error: Error | null;
  onSubmit: (input: MonitorInput) => void;
  /** Extra content shown with a form-level error (e.g. "Resend link"), chosen by the error's kind. */
  renderErrorAction?: (error: MonitorFormError) => ReactNode;
  /** Shown below the address field. */
  urlHint?: ReactNode;
  /** Buttons next to the submit button, e.g. Cancel. */
  secondaryActions?: ReactNode;
}

/** Name, address and check interval: the fields for adding and editing a website. */
export function MonitorForm({
  initial,
  submitLabel,
  savingLabel,
  saving,
  error,
  onSubmit,
  renderErrorAction,
  urlHint,
  secondaryActions,
}: MonitorFormProps) {
  const prefix = useId();
  const [name, setName] = useState(initial?.name ?? "");
  const [url, setUrl] = useState(initial?.url ?? "");
  const [interval, setIntervalSeconds] = useState(initial?.intervalSeconds ?? DEFAULT_INTERVAL);
  const intervalId = (seconds: number) => `${prefix}-interval-${String(seconds)}`;
  // In page order, for focusing the first field with an error.
  const ids: Record<Field, string> = {
    url: `${prefix}-url`,
    name: `${prefix}-name`,
    interval: intervalId(interval),
  };
  const [errors, setErrors] = useState<FieldErrors<Field>>({});
  // The server error being shown; cleared when the user edits the field it is about.
  const [serverError, setServerError] = useState<MonitorFormError | null>(null);
  const [lastError, setLastError] = useState<Error | null>(null);

  // A new error from the parent's mutation: show it once, on its field or above the form.
  if (error !== lastError) {
    setLastError(error);
    setServerError(error ? monitorFormError(error) : null);
  }

  function submit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const found: FieldErrors<Field> = { url: validateUrl(url), name: validateName(name) };
    setErrors(found);
    setServerError(null);
    if (hasErrors(found)) {
      focusFirstError(found, ids);
      return;
    }
    onSubmit({ name: nameOrHost(name, url), url: normalizeUrl(url), intervalSeconds: interval });
  }

  const fieldError = (field: Field) =>
    errors[field] ?? (serverError?.field === field ? serverError.message : undefined);
  const formError = serverError && !serverError.field ? serverError : null;
  const preview = url.trim() && !hasScheme(url) ? normalizeUrl(url) : null;

  return (
    <form noValidate onSubmit={submit} className="flex flex-col gap-5">
      {formError && (
        <Alert variant="error" action={renderErrorAction?.(formError)}>
          {formError.message}
        </Alert>
      )}
      <Input
        id={ids.url}
        label="Website address"
        hint={urlHint ?? "For example example.com or https://example.com/shop"}
        inputMode="url"
        type="text"
        autoComplete="url"
        autoCapitalize="none"
        autoCorrect="off"
        spellCheck={false}
        value={url}
        onChange={(e) => {
          setUrl(e.target.value);
          if (serverError?.field === "url") setServerError(null);
        }}
        error={fieldError("url")}
      />
      {preview && !fieldError("url") && (
        <p className="-mt-3 text-sm text-ink-muted">
          We&apos;ll check <span className="font-semibold break-all text-ink">{preview}</span>
        </p>
      )}
      <Input
        id={ids.name}
        label="Name"
        optional
        hint="What you call it, e.g. “Online shop”. Leave it empty to use the address."
        autoComplete="off"
        maxLength={200}
        value={name}
        onChange={(e) => {
          setName(e.target.value);
          if (serverError?.field === "name") setServerError(null);
        }}
        error={fieldError("name")}
      />
      <IntervalField
        value={interval}
        onChange={setIntervalSeconds}
        idFor={intervalId}
        error={fieldError("interval")}
      />
      <div className="flex flex-wrap items-center gap-3">
        <Button type="submit" loading={saving} loadingText={savingLabel}>
          {submitLabel}
        </Button>
        {secondaryActions}
      </div>
    </form>
  );
}

function IntervalField({
  value,
  onChange,
  idFor,
  error,
}: {
  value: number;
  onChange: (seconds: number) => void;
  idFor: (seconds: number) => string;
  error: string | undefined;
}) {
  const errorId = useId();
  return (
    <fieldset aria-describedby={error ? errorId : undefined} className="flex flex-col gap-1.5">
      <legend className="mb-1.5 text-sm font-semibold text-ink">How often should we check?</legend>
      <div className="grid gap-2 sm:grid-cols-2">
        {INTERVALS.map((option) => (
          <label
            key={option.seconds}
            htmlFor={idFor(option.seconds)}
            className={cn(
              "flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border px-3.5 py-2",
              value === option.seconds
                ? "border-brand-600 bg-brand-50 font-semibold"
                : "border-line-strong bg-surface",
            )}
          >
            <input
              type="radio"
              id={idFor(option.seconds)}
              name={idFor(0)}
              value={option.seconds}
              checked={value === option.seconds}
              onChange={() => {
                onChange(option.seconds);
              }}
              className="size-4 accent-brand-600"
            />
            {option.label}
          </label>
        ))}
      </div>
      {error && (
        <p id={errorId} className="text-sm font-medium text-down-700">
          {error}
        </p>
      )}
    </fieldset>
  );
}
