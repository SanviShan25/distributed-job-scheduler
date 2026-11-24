import { CircleX } from "lucide-react";
import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from "react";
import { cn } from "../../lib/cn";

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string;
  /** Help shown between the label and the field, so it is read before typing. */
  hint?: ReactNode;
  /** Error message. Marks the field invalid and is announced with it. */
  error?: string | undefined;
  /** Adds "(optional)" to the label; fields are treated as required otherwise. */
  optional?: boolean;
  ref?: Ref<HTMLInputElement>;
}

export function Input({
  label,
  hint,
  error,
  optional = false,
  id,
  className,
  ...rest
}: InputProps) {
  const generatedId = useId();
  const inputId = id ?? generatedId;
  const hintId = `${inputId}-hint`;
  const errorId = `${inputId}-error`;
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(" ");

  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={inputId} className="text-sm font-semibold text-ink">
        {label}
        {optional && <span className="font-normal text-ink-muted"> (optional)</span>}
      </label>
      {hint && (
        <div id={hintId} className="text-sm text-ink-muted">
          {hint}
        </div>
      )}
      <input
        id={inputId}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy || undefined}
        className={cn(
          // text-base (16px) also stops iOS Safari from zooming in on focus.
          "block min-h-11 w-full rounded-lg border bg-surface px-3.5 py-2 text-base text-ink shadow-sm",
          "placeholder:text-ink-muted/70 disabled:cursor-not-allowed disabled:bg-unknown-50",
          error ? "border-down-700 ring-1 ring-down-700" : "border-line-strong",
          className,
        )}
        {...rest}
      />
      {error && (
        <p id={errorId} className="flex items-start gap-1.5 text-sm font-medium text-down-700">
          <CircleX aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </p>
      )}
    </div>
  );
}
