import { CircleCheck, CircleX, Info, TriangleExclamationPoint } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

const variants = {
  info: { icon: Info, classes: "border-brand-200 bg-brand-50 text-brand-800", role: "status" },
  success: { icon: CircleCheck, classes: "border-up-200 bg-up-50 text-up-800", role: "status" },
  warning: {
    icon: TriangleExclamationPoint,
    classes: "border-warning-200 bg-warning-50 text-warning-800",
    role: "alert",
  },
  error: { icon: CircleX, classes: "border-down-200 bg-down-50 text-down-800", role: "alert" },
} as const;

export interface AlertProps {
  variant?: keyof typeof variants;
  title?: ReactNode;
  children?: ReactNode;
  /** A button or link offering the next step, e.g. "Resend link". */
  action?: ReactNode;
  className?: string;
}

/**
 * A message box. Errors and warnings use role="alert" (announced immediately); info and success
 * use role="status" (announced politely).
 */
export function Alert({ variant = "info", title, children, action, className }: AlertProps) {
  const { icon: Icon, classes, role } = variants[variant];
  return (
    <div
      role={role}
      className={cn("flex gap-3 rounded-xl border p-4 text-sm sm:text-base", classes, className)}
    >
      <Icon aria-hidden="true" className="mt-0.5 size-5 shrink-0" />
      <div className="flex min-w-0 flex-1 flex-col gap-2 sm:flex-row sm:items-center">
        <div className="min-w-0 flex-1">
          {title && <p className="font-semibold">{title}</p>}
          {children && <div className={cn(title ? "mt-0.5" : undefined)}>{children}</div>}
        </div>
        {action && <div className="shrink-0">{action}</div>}
      </div>
    </div>
  );
}
