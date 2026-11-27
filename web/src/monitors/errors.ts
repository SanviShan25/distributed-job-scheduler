import { ApiError } from "../api/client";

export interface MonitorFormError {
  /** The field the error belongs to, if any; otherwise it is shown above the form. */
  field?: "name" | "url" | "interval";
  message: string;
  /** Errors that come with a next step in the UI. */
  kind?: "email-not-verified" | "limit-reached";
}

/**
 * Turns the backend's answer to creating or changing a monitor into plain words. The backend's
 * details are written for API users ("This URL can't be monitored: localhost resolves to a private
 * or reserved address (127.0.0.1)"); this says the same thing for website owners.
 */
export function monitorFormError(error: Error): MonitorFormError {
  if (!(error instanceof ApiError)) return { message: error.message };
  const detail = error.problemDetail ?? "";

  if (error.status === 403 && error.problemTitle === "Email not verified") {
    return {
      kind: "email-not-verified",
      message:
        "Please verify your email address before adding websites. Check your inbox for the link, or send a new one.",
    };
  }

  if (error.status === 409) {
    const limit = /at most (\d+) monitors/i.exec(detail);
    if (limit) {
      return {
        kind: "limit-reached",
        message: `You've reached the limit of ${limit[1] ?? ""} websites. Delete one to add another.`,
      };
    }
    if (/already have a monitor/i.test(detail)) {
      return { field: "url", message: "You're already checking this address." };
    }
  }

  if (error.status === 400) {
    const unsafe = /can't be monitored: (.*)$/i.exec(detail)?.[1] ?? "";
    if (/private or reserved/i.test(unsafe)) {
      return {
        field: "url",
        message:
          "This address points to a private or local network, which we can't reach. Use your website's public address.",
      };
    }
    if (/could not resolve host/i.test(unsafe)) {
      const host = /could not resolve host (\S+)/i.exec(unsafe)?.[1];
      return {
        field: "url",
        message: `We couldn't find a website at ${host ?? "this address"}. Check it for typos.`,
      };
    }
    if (/username or password/i.test(unsafe)) {
      return { field: "url", message: "Please remove the username and password from the address." };
    }
    if (/longer than/i.test(unsafe)) {
      return { field: "url", message: "That address is too long." };
    }
    if (unsafe) {
      return {
        field: "url",
        message: "That doesn't look like a web address. Try something like example.com.",
      };
    }
    if (/intervalSeconds/i.test(detail) || "intervalSeconds" in error.fieldErrors) {
      return { field: "interval", message: "Please choose how often to check." };
    }
    if (/^name /i.test(detail) || "name" in error.fieldErrors) {
      return { field: "name", message: "Please check the name." };
    }
    if ("url" in error.fieldErrors) {
      return { field: "url", message: "Please check the address." };
    }
  }

  return { message: error.message };
}
