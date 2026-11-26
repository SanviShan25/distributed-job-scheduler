import { useEffect, useId, useRef, type ReactNode } from "react";
import { cn } from "../../lib/cn";

export interface DialogProps {
  open: boolean;
  /** Called when the user closes the dialog (Escape, or a Cancel button that calls it). */
  onClose: () => void;
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  /** False while something is being saved: Escape then does nothing, so the result isn't missed. */
  dismissible?: boolean;
  className?: string;
}

/**
 * A modal dialog on the native <dialog> element. showModal() makes the rest of the page inert (focus
 * cannot leave the dialog, screen readers see only the dialog) and closes it on Escape, without any
 * script for focus trapping. On open, focus goes to the element marked `data-autofocus`, or the first
 * field or button; on close it returns to whatever had it before (usually the button that opened it).
 */
export function Dialog({
  open,
  onClose,
  title,
  description,
  children,
  dismissible = true,
  className,
}: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  const onCloseRef = useRef(onClose);
  // Set while this component closes the dialog itself, to tell that apart from the browser closing it.
  const closingRef = useRef(false);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  useEffect(() => {
    const dialog = ref.current;
    if (!dialog || !open) return;
    const returnFocusTo =
      document.activeElement instanceof HTMLElement ? document.activeElement : null;
    if (!dialog.open) dialog.showModal();
    dialog.querySelector<HTMLElement>("[data-autofocus]")?.focus();
    return () => {
      if (dialog.open) {
        closingRef.current = true;
        dialog.close();
      }
      if (returnFocusTo?.isConnected) returnFocusTo.focus();
    };
  }, [open]);

  return (
    <dialog
      ref={ref}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onCancel={(event) => {
        // Escape: let React state decide, so the dialog and `open` never disagree.
        event.preventDefault();
        if (dismissible) onCloseRef.current();
      }}
      onClose={() => {
        // Browsers may close a dialog even when "cancel" is prevented (Chrome does on repeated Escape).
        // Keep React's state in step, or the dialog could never be opened again.
        if (closingRef.current) closingRef.current = false;
        else onCloseRef.current();
      }}
      className={cn(
        "m-auto max-h-[calc(100dvh-2rem)] w-[calc(100%-2rem)] max-w-lg overflow-y-auto rounded-2xl",
        "border border-line bg-surface p-6 text-ink shadow-xl backdrop:bg-ink/50 sm:p-8",
        className,
      )}
    >
      {open && (
        <>
          <h2 id={titleId} className="text-xl font-bold tracking-tight break-words sm:text-2xl">
            {title}
          </h2>
          {description && (
            <div id={descriptionId} className="mt-2 text-ink-muted">
              {description}
            </div>
          )}
          <div className="mt-6">{children}</div>
        </>
      )}
    </dialog>
  );
}
