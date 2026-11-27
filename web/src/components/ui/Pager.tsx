import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "./Button";

export interface PagerProps {
  /** Zero-based. */
  page: number;
  totalPages: number;
  onChange: (page: number) => void;
  /** Names the navigation landmark, e.g. "Recent checks pages". */
  label: string;
}

/**
 * "Newer" / "Older" paging for lists that are newest first. The page number is announced politely
 * when it changes. At either end the button is aria-disabled rather than disabled, so the button that
 * was just pressed keeps keyboard focus instead of dropping it to the page body.
 */
export function Pager({ page, totalPages, onChange, label }: PagerProps) {
  if (totalPages <= 1) return null;
  const atNewest = page === 0;
  const atOldest = page >= totalPages - 1;
  return (
    <nav aria-label={label} className="mt-4 flex flex-wrap items-center justify-between gap-3">
      <Button
        variant="secondary"
        icon={<ChevronLeft aria-hidden="true" className="size-4" />}
        aria-disabled={atNewest || undefined}
        className="aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
        onClick={() => {
          if (!atNewest) onChange(page - 1);
        }}
      >
        Newer
      </Button>
      <p className="text-sm text-ink-muted" aria-live="polite">
        Page {page + 1} of {totalPages}
      </p>
      <Button
        variant="secondary"
        aria-disabled={atOldest || undefined}
        className="flex-row-reverse aria-disabled:cursor-not-allowed aria-disabled:opacity-50"
        icon={<ChevronRight aria-hidden="true" className="size-4" />}
        onClick={() => {
          if (!atOldest) onChange(page + 1);
        }}
      >
        Older
      </Button>
    </nav>
  );
}
