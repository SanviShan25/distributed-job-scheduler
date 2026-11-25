import { cn } from "../../lib/cn";

const sizes = {
  sm: "size-4",
  md: "size-6",
  lg: "size-10",
} as const;

export interface SpinnerProps {
  size?: keyof typeof sizes;
  /** What is loading, read by screen readers (and shown when showLabel is set). */
  label?: string;
  showLabel?: boolean;
  /** Hide from assistive technology, e.g. inside a button that already says what is happening. */
  decorative?: boolean;
  className?: string;
}

export function Spinner({
  size = "md",
  label = "Loading…",
  showLabel = false,
  decorative = false,
  className,
}: SpinnerProps) {
  const icon = (
    <svg
      aria-hidden="true"
      viewBox="0 0 24 24"
      fill="none"
      className={cn("shrink-0 animate-spin", sizes[size], className)}
    >
      <circle cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" className="opacity-25" />
      <path
        d="M22 12a10 10 0 0 0-10-10"
        stroke="currentColor"
        strokeWidth="4"
        strokeLinecap="round"
      />
    </svg>
  );

  if (decorative) return icon;

  return (
    <span role="status" className="inline-flex items-center gap-2 text-ink-muted">
      {icon}
      <span className={showLabel ? "text-sm" : "sr-only"}>{label}</span>
    </span>
  );
}
