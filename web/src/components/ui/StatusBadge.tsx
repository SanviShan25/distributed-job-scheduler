import {
  CircleCheck,
  CirclePause,
  CircleQuestionMark,
  TriangleExclamationPoint,
} from "lucide-react";
import { cn } from "../../lib/cn";

export type MonitorStatus = "UP" | "DOWN" | "UNKNOWN" | "PAUSED";

// Colour is never the only signal: each status has its own icon shape and a word, so it reads
// the same for colour-blind users and in greyscale.
const statuses = {
  UP: { label: "Up", icon: CircleCheck, classes: "border-up-200 bg-up-50 text-up-700" },
  DOWN: {
    label: "Down",
    icon: TriangleExclamationPoint,
    classes: "border-down-200 bg-down-50 text-down-700",
  },
  UNKNOWN: {
    label: "Unknown",
    icon: CircleQuestionMark,
    classes: "border-unknown-200 bg-unknown-50 text-unknown-700",
  },
  PAUSED: {
    label: "Paused",
    icon: CirclePause,
    classes: "border-unknown-200 bg-unknown-50 text-unknown-700",
  },
} as const;

export interface StatusBadgeProps {
  status: MonitorStatus;
  size?: "sm" | "md";
  className?: string;
}

export function StatusBadge({ status, size = "md", className }: StatusBadgeProps) {
  const { label, icon: Icon, classes } = statuses[status];
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border font-semibold",
        size === "sm" ? "px-2 py-0.5 text-xs" : "px-3 py-1 text-sm",
        classes,
        className,
      )}
    >
      <Icon aria-hidden="true" className={size === "sm" ? "size-3.5" : "size-4"} />
      {label}
    </span>
  );
}
