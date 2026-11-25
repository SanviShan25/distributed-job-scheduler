import { useEffect } from "react";

/** Sets the browser tab title, which is also the first thing screen readers announce on a new page. */
export function usePageTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · PulseCheck`;
  }, [title]);
}
