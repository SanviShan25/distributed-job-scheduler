import { ChevronDown, CircleUser, LogOut } from "lucide-react";
import { useEffect, useId, useRef, useState } from "react";
import { useLogout } from "../../auth/user";
import { cn } from "../../lib/cn";
import { Spinner } from "../ui";

/** The account button in the header: shows who is logged in and offers "Log out". */
export function UserMenu({ email }: { email: string }) {
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);
  const panelId = useId();
  const logout = useLogout();

  useEffect(() => {
    if (!open) return;
    function onPointerDown(event: PointerEvent) {
      if (!containerRef.current?.contains(event.target as Node)) setOpen(false);
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setOpen(false);
        buttonRef.current?.focus();
      }
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      <button
        ref={buttonRef}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          setOpen((value) => !value);
        }}
        className="inline-flex min-h-11 items-center gap-2 rounded-lg px-2 text-ink hover:bg-canvas sm:px-3"
      >
        <CircleUser aria-hidden="true" className="size-6 text-ink-muted" />
        <span className="sr-only">Account menu</span>
        <span className="hidden max-w-56 truncate text-sm font-medium sm:inline">{email}</span>
        <ChevronDown
          aria-hidden="true"
          className={cn("size-4 text-ink-muted transition-transform", open && "rotate-180")}
        />
      </button>

      {open && (
        <div
          id={panelId}
          className="absolute right-0 z-20 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-xl border border-line bg-surface py-2 shadow-lg"
        >
          <p className="border-b border-line px-4 pt-1 pb-3 text-sm text-ink-muted">
            Logged in as
            <span className="block font-semibold break-all text-ink">{email}</span>
          </p>
          <ul className="pt-2">
            <li>
              <button
                type="button"
                onClick={() => {
                  logout.mutate();
                }}
                aria-busy={logout.isPending || undefined}
                className="flex min-h-11 w-full items-center gap-3 px-4 text-left font-medium text-ink hover:bg-canvas"
              >
                {logout.isPending ? (
                  <Spinner size="sm" decorative />
                ) : (
                  <LogOut aria-hidden="true" className="size-5 text-ink-muted" />
                )}
                {logout.isPending ? "Logging out…" : "Log out"}
              </button>
            </li>
          </ul>
          {logout.error && (
            <p role="alert" className="px-4 pt-2 text-sm text-down-700">
              {logout.error.message}
            </p>
          )}
        </div>
      )}
    </div>
  );
}
