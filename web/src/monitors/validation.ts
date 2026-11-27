/** The check intervals the backend accepts (MonitorSchedules.ALLOWED_INTERVALS), in seconds. */
export const INTERVALS = [
  { seconds: 60, label: "Every minute" },
  { seconds: 300, label: "Every 5 minutes" },
  { seconds: 900, label: "Every 15 minutes" },
  { seconds: 3600, label: "Every hour" },
] as const;

export const DEFAULT_INTERVAL = 300;
export const MAX_NAME_LENGTH = 200;
const MAX_URL_LENGTH = 2048;

// "https:", "mailto:"; but not "example.com:8080", where digits after the colon make it a port.
const SCHEME = /^[a-z][a-z0-9+.-]*:(?!\d)/i;

/** True if the address already says how to reach it ("https://…", "ftp://…"). */
export function hasScheme(input: string): boolean {
  return SCHEME.test(input.trim());
}
const IPV4 = /^\d{1,3}(\.\d{1,3}){3}$/;

/**
 * What the user typed, as a full address: trimmed, with https:// added when there is no scheme
 * ("example.com" → "https://example.com"). Not validated; see validateUrl.
 */
export function normalizeUrl(input: string): string {
  const trimmed = input.trim();
  if (trimmed === "" || hasScheme(trimmed)) return trimmed;
  return `https://${trimmed.replace(/^\/+/, "")}`;
}

/**
 * The obvious mistakes, caught before asking the server. The server has the final say (it also
 * refuses private addresses and names that don't exist).
 */
export function validateUrl(input: string): string | undefined {
  const url = normalizeUrl(input);
  if (url === "") return "Enter your website's address, for example example.com.";
  if (url.length > MAX_URL_LENGTH) return "That address is too long.";
  let parsed: URL;
  try {
    parsed = new URL(url);
  } catch {
    return "That doesn't look like a web address. Try something like example.com.";
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return "Only website addresses (starting with http:// or https://) can be checked.";
  }
  if (parsed.username || parsed.password) {
    return "Please remove the username and password from the address.";
  }
  const host = parsed.hostname;
  const isIp = IPV4.test(host) || host.startsWith("[");
  if (!isIp && !host.includes(".")) {
    return "Enter the full address, including the ending, for example example.com.";
  }
  return undefined;
}

export function validateName(name: string): string | undefined {
  if (name.trim().length > MAX_NAME_LENGTH) {
    return `Please keep the name under ${String(MAX_NAME_LENGTH)} characters.`;
  }
  return undefined;
}

/** The name to save: what was typed, or the website's host ("example.com") if left empty. */
export function nameOrHost(name: string, url: string): string {
  const trimmed = name.trim();
  if (trimmed) return trimmed;
  try {
    return new URL(normalizeUrl(url)).hostname;
  } catch {
    return url.trim();
  }
}
