import "@testing-library/jest-dom/vitest";
import { cleanup, configure } from "@testing-library/react";
import { afterEach } from "vitest";
import { sessionEnd } from "../auth/session";

// findBy*/waitFor give up after 1 s by default, which a busy machine (CI, or the backend tests running
// alongside) can exceed while rendering a whole page. A longer limit only costs time when a test fails.
configure({ asyncUtilTimeout: 5000 });

afterEach(() => {
  cleanup();
  sessionEnd.set(null);
  for (const cookie of document.cookie.split(";")) {
    const name = cookie.split("=")[0]?.trim();
    if (name) document.cookie = `${name}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
  }
});
