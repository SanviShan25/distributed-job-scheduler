import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, formatWait } from "../api/client";
import { updateCachedMonitor, type Recipient, type RecipientStatus } from "./monitors";

/** The backend's limit on extra recipients per website (PENDING + CONFIRMED). */
export const MAX_EXTRA_RECIPIENTS = 5;

export const RECIPIENT_STATUS_LABELS: Record<RecipientStatus, string> = {
  PENDING: "Waiting for confirmation",
  CONFIRMED: "Receiving alerts",
  UNSUBSCRIBED: "Unsubscribed",
};

/** Recipients that count towards the limit: unsubscribed people are listed but do not count. */
export function activeRecipients(recipients: readonly Recipient[]): number {
  return recipients.filter((r) => r.status !== "UNSUBSCRIBED").length;
}

export function useAddRecipient(monitorId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (email: string) =>
      api.post<Recipient>(`/api/monitors/${monitorId}/recipients`, { email }),
    onSuccess: (added) => {
      // Re-adding an unsubscribed address reuses its row, so replace rather than append.
      updateCachedMonitor(queryClient, monitorId, (monitor) => ({
        ...monitor,
        recipients: [...monitor.recipients.filter((r) => r.id !== added.id), added],
      }));
    },
  });
}

export function useResendConfirmation(monitorId: string) {
  return useMutation({
    mutationFn: (recipientId: string) =>
      api.post<Recipient>(`/api/monitors/${monitorId}/recipients/${recipientId}/resend`),
  });
}

export function useRemoveRecipient(monitorId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (recipientId: string) => {
      try {
        await api.delete<undefined>(`/api/monitors/${monitorId}/recipients/${recipientId}`);
      } catch (error) {
        // Already removed (e.g. in another tab): that is what was asked for.
        if (!(error instanceof ApiError && error.status === 404)) throw error;
      }
    },
    onSuccess: (_, recipientId) => {
      updateCachedMonitor(queryClient, monitorId, (monitor) => ({
        ...monitor,
        recipients: monitor.recipients.filter((r) => r.id !== recipientId),
      }));
    },
  });
}

/**
 * The backend's answer to adding a recipient or resending a confirmation, in plain words. Its
 * details name API fields ("see notifyOwner") or internal states ("already a recipient (PENDING)").
 */
export function recipientError(error: Error): string {
  if (!(error instanceof ApiError)) return error.message;
  const detail = error.problemDetail ?? "";
  if (error.status === 429) {
    return error.retryAfterSeconds === undefined
      ? "Too many confirmation emails have been sent. Please try again later."
      : `Too many confirmation emails have been sent. Please try again in ${formatWait(error.retryAfterSeconds)}.`;
  }
  if (error.status === 503) {
    return "We can't send confirmation emails right now. Please try again in a few minutes.";
  }
  if (error.status === 400 && /your account's address/i.test(detail)) {
    return "That's your own address. You already get this website's alerts.";
  }
  if (error.status === 400 && /valid email/i.test(detail)) {
    return "Please enter a valid email address, like name@example.com.";
  }
  if (error.status === 409 && /already a recipient/i.test(detail)) {
    return "This person is already on the list.";
  }
  if (error.status === 409 && /at most \d+ extra recipients/i.test(detail)) {
    return `You can add up to ${String(MAX_EXTRA_RECIPIENTS)} people. Remove someone to add another.`;
  }
  if (error.status === 409 && /only pending recipients/i.test(detail)) {
    return "This person has already confirmed or unsubscribed, so there's nothing to resend.";
  }
  if (error.status === 404) {
    return "This person is no longer on the list. Reload the page to see the latest.";
  }
  return error.message;
}
