import type { ButtonHTMLAttributes, MouseEvent, ReactNode, Ref } from "react";
import { cn } from "../../lib/cn";
import { Spinner } from "./Spinner";

const variants = {
  primary: "bg-brand-600 text-white shadow-sm hover:bg-brand-700 active:bg-brand-800",
  secondary:
    "border border-line-strong bg-surface text-ink shadow-sm hover:bg-canvas active:bg-unknown-50",
  danger: "bg-down-700 text-white shadow-sm hover:bg-down-800",
  ghost: "text-brand-700 hover:bg-brand-50 active:bg-brand-100",
} as const;

const sizes = {
  md: "min-h-11 px-4 text-base", // 44px: comfortable touch target
  lg: "min-h-12 px-6 text-lg",
} as const;

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: keyof typeof variants;
  size?: keyof typeof sizes;
  /** Shows a spinner and ignores clicks, but keeps the button focusable. */
  loading?: boolean;
  /** Text shown while loading, e.g. "Saving…". Defaults to the normal label. */
  loadingText?: string;
  fullWidth?: boolean;
  icon?: ReactNode;
  ref?: Ref<HTMLButtonElement>;
}

export function Button({
  variant = "primary",
  size = "md",
  loading = false,
  loadingText,
  fullWidth = false,
  icon,
  type = "button",
  className,
  children,
  onClick,
  ...rest
}: ButtonProps) {
  // While loading the button stays enabled (aria-disabled) so keyboard focus is not lost, and
  // clicks are cancelled. Cancelling the click also cancels a form submit, including the implicit
  // one from pressing Enter in a field, which browsers deliver as a click on the submit button.
  function handleClick(event: MouseEvent<HTMLButtonElement>) {
    if (loading) {
      event.preventDefault();
      return;
    }
    onClick?.(event);
  }

  return (
    <button
      type={type}
      aria-disabled={loading || undefined}
      aria-busy={loading || undefined}
      onClick={handleClick}
      className={cn(
        "inline-flex items-center justify-center gap-2 rounded-lg font-semibold transition-colors",
        "disabled:cursor-not-allowed disabled:opacity-50 aria-disabled:cursor-wait",
        variants[variant],
        sizes[size],
        fullWidth && "w-full",
        className,
      )}
      {...rest}
    >
      {loading ? <Spinner size="sm" decorative /> : icon}
      <span>{loading && loadingText ? loadingText : children}</span>
    </button>
  );
}
