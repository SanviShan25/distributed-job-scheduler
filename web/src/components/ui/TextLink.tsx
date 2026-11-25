import { Link, type LinkProps } from "react-router";
import { cn } from "../../lib/cn";

export const textLinkClasses =
  "rounded-sm font-semibold text-brand-700 underline underline-offset-4 hover:text-brand-800";

/** An in-app link that looks like a link (underlined), for use inside text. */
export function TextLink({ className, ...props }: LinkProps) {
  return <Link className={cn(textLinkClasses, className)} {...props} />;
}

const buttonLinkVariants = {
  primary: "bg-brand-600 text-white shadow-sm hover:bg-brand-700",
  secondary: "border border-line-strong bg-surface text-ink shadow-sm hover:bg-canvas",
} as const;

/** An in-app link that looks like a button, for a page's main next step ("Log in"). */
export function ButtonLink({
  className,
  variant = "primary",
  ...props
}: LinkProps & { variant?: keyof typeof buttonLinkVariants }) {
  return (
    <Link
      className={cn(
        "inline-flex min-h-11 items-center justify-center rounded-lg px-4 font-semibold",
        buttonLinkVariants[variant],
        className,
      )}
      {...props}
    />
  );
}
