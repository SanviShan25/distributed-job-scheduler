import type { ReactNode } from "react";
import { cn } from "../../lib/cn";

export interface CardProps {
  title?: ReactNode;
  /** Heading level of the title, to keep the page outline correct. */
  titleAs?: "h1" | "h2" | "h3";
  description?: ReactNode;
  footer?: ReactNode;
  children?: ReactNode;
  className?: string;
}

export function Card({
  title,
  titleAs: Heading = "h2",
  description,
  footer,
  children,
  className,
}: CardProps) {
  return (
    <section
      className={cn("rounded-2xl border border-line bg-surface p-5 shadow-sm sm:p-8", className)}
    >
      {(title ?? description) && (
        <header className="mb-5 flex flex-col gap-1.5">
          {title && (
            <Heading className="text-xl font-bold tracking-tight text-ink sm:text-2xl">
              {title}
            </Heading>
          )}
          {description && <p className="text-base text-ink-muted">{description}</p>}
        </header>
      )}
      {children}
      {footer && <footer className="mt-6 border-t border-line pt-5">{footer}</footer>}
    </section>
  );
}
