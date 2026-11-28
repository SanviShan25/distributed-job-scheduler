import { useEffect } from "react";

const SITE_NAME = "PulseCheck";

/** The description in index.html, used by every page that doesn't give its own. */
const DEFAULT_DESCRIPTION =
  typeof document === "undefined"
    ? ""
    : (document.querySelector<HTMLMetaElement>('meta[name="description"]')?.content ?? "");

/**
 * Sets the browser tab title, which is also the first thing screen readers announce on a new page, and
 * the page's meta description (and their Open Graph copies) for search engines that run the app. The
 * canonical URL is this page's own address without query or fragment (index.html is served for every
 * route, so it can't carry one).
 */
export function usePageTitle(title: string, description?: string): void {
  useEffect(() => {
    const fullTitle = `${title} · ${SITE_NAME}`;
    document.title = fullTitle;
    setMeta('meta[name="description"]', description ?? DEFAULT_DESCRIPTION);
    setMeta('meta[property="og:title"]', fullTitle);
    setMeta('meta[property="og:description"]', description ?? DEFAULT_DESCRIPTION);
    const url = window.location.origin + window.location.pathname;
    upsert("link", "rel", "canonical", "href", url);
    upsert("meta", "property", "og:url", "content", url);
    // Open Graph wants an absolute image URL; index.html can only say "/og-image.png".
    upsert("meta", "property", "og:image", "content", `${window.location.origin}/og-image.png`);
  }, [title, description]);
}

function upsert(tag: "link" | "meta", key: string, value: string, attr: string, content: string) {
  let element = document.head.querySelector(`${tag}[${key}="${value}"]`);
  if (!element) {
    element = document.createElement(tag);
    element.setAttribute(key, value);
    document.head.append(element);
  }
  element.setAttribute(attr, content);
}

function setMeta(selector: string, content: string): void {
  document.querySelector<HTMLMetaElement>(selector)?.setAttribute("content", content);
}
