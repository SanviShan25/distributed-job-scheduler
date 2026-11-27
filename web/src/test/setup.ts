import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";
import { sessionEnd } from "../auth/session";

// findBy*/waitFor give up after 1 s by default, which a busy machine (CI, or the backend tests running
// alongside) can exceed while rendering a whole page. A longer limit only costs time when a test fails.
configure({ asyncUtilTimeout: 5000 });

// jsdom has no modal dialogs. This stand-in does what the components rely on: showModal() opens the
// dialog, and Escape inside it fires a cancelable "cancel" event, closing it unless cancelled. It does
// not make the rest of the page inert; tests check that dialogs are labelled and close on Escape.
if (!("showModal" in HTMLDialogElement.prototype)) {
  Object.assign(HTMLDialogElement.prototype, {
    showModal(this: HTMLDialogElement) {
      this.setAttribute("open", "");
    },
    close(this: HTMLDialogElement) {
      if (!this.hasAttribute("open")) return;
      this.removeAttribute("open");
      this.dispatchEvent(new Event("close"));
    },
  });
  document.addEventListener("keydown", (event) => {
    if (event.key !== "Escape") return;
    const dialog = event.target instanceof Element ? event.target.closest("dialog[open]") : null;
    if (!(dialog instanceof HTMLDialogElement)) return;
    if (dialog.dispatchEvent(new Event("cancel", { cancelable: true }))) dialog.close();
  });
}

// jsdom has no ResizeObserver, which the chart's ResponsiveContainer uses to measure itself. Without
// layout there is nothing to measure, so this one never reports a size.
if (!("ResizeObserver" in globalThis)) {
  const noop = () => undefined;
  globalThis.ResizeObserver = class {
    observe = noop;
    unobserve = noop;
    disconnect = noop;
  };
}

afterEach(() => {
  cleanup();
  sessionEnd.set(null);
  for (const cookie of document.cookie.split(";")) {
    const name = cookie.split("=")[0]?.trim();
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
});
