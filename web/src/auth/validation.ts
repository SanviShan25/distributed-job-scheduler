// Client-side checks that mirror the backend exactly, so people see problems before submitting.
// The backend still checks everything.

/** Same limits as AccountService (Java String.length() and JS .length both count UTF-16 units). */
export const PASSWORD_MIN_LENGTH = 10;
export const PASSWORD_MAX_LENGTH = 128;
export const PASSWORD_RULE = `Use ${PASSWORD_MIN_LENGTH} to ${PASSWORD_MAX_LENGTH} characters.`;

/** Same pattern as EmailAddresses.FORMAT, applied to the trimmed, lower-cased address. */
const EMAIL_FORMAT =
  /^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$/;
const EMAIL_MAX_LENGTH = 254;

export function normalizeEmail(value: string): string {
  return value.trim().toLowerCase();
}

export function validateEmail(value: string): string | undefined {
  const email = normalizeEmail(value);
  if (!email) return "Enter your email address.";
  if (email.length > EMAIL_MAX_LENGTH || !EMAIL_FORMAT.test(email)) {
    return "Enter a valid email address, like name@example.com.";
  }
  return undefined;
}

/** For choosing a new password (sign-up, reset, change). */
export function validateNewPassword(value: string): string | undefined {
  if (!value) return "Enter a password.";
  if (value.length < PASSWORD_MIN_LENGTH) {
    return `Use at least ${PASSWORD_MIN_LENGTH} characters. You've typed ${value.length}.`;
  }
  if (value.length > PASSWORD_MAX_LENGTH) {
    return `Use no more than ${PASSWORD_MAX_LENGTH} characters. You've typed ${value.length}.`;
  }
  return undefined;
}

/** For typing an existing password (login, confirming a change): only "not empty". */
export function validateCurrentPassword(value: string): string | undefined {
  return value ? undefined : "Enter your password.";
}

/** The rule, plus a live count once typing starts. */
export function passwordHint(value: string): string {
  if (!value) return PASSWORD_RULE;
  const n = value.length;
  const ok = n >= PASSWORD_MIN_LENGTH && n <= PASSWORD_MAX_LENGTH;
  return `${PASSWORD_RULE} ${n} ${n === 1 ? "character" : "characters"} so far${ok ? " ✓" : ""}.`;
}
