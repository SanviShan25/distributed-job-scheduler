import { cn } from "../lib/cn";

export interface LogoProps {
  /** Show only the mark (e.g. on small screens); the name is still available to screen readers. */
  markOnly?: boolean;
  className?: string;
}

/** The PulseCheck mark: a pulse line in a rounded square, plus the name. */
export function Logo({ markOnly = false, className }: LogoProps) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <svg aria-hidden="true" viewBox="0 0 32 32" className="size-8 shrink-0">
        <rect width="32" height="32" rx="8" className="fill-brand-700" />
        <path
          d="M5 17h5l3-7 5 13 3-6h6"
          fill="none"
          stroke="white"
          strokeWidth="2.75"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      <span className={cn("text-lg font-bold tracking-tight text-ink", markOnly && "sr-only")}>
        PulseCheck
      </span>
    </span>
  );
}
