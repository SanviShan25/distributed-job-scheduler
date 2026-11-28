import { QueryClient, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, refreshCsrfToken } from "../api/client";
import { sessionEnd } from "./session";

export interface User {
  id: string;
  email: string;
  emailVerified: boolean;
  createdAt: string;
  /** How many websites this account may monitor. */
  monitorLimit: number;
  /** The shortest check interval this account may use, in seconds (longer for demo accounts). */
  minIntervalSeconds: number;
  /** A private, temporary demo account: no password, no emails, some settings unavailable. */
  demo: boolean;
  /** When a demo account is deleted (ISO time); null for normal accounts. */
  demoExpiresAt: string | null;
  /** Same as GET /api/config. */
  demoEnabled: boolean;
  signupOpen: boolean;
}

/** How the app shows the account: demo addresses are generated and mean nothing to the visitor. */
export function accountLabel(user: User): string {
  return user.demo ? "Demo account" : user.email;
}

export const meQueryKey = ["auth", "me"] as const;

/**
 * The logged-in user, or null when nobody is logged in: the backend answers 204 No Content then (not
 * 401), so a logged-out visit leaves no error in the browser console.
 */
export async function fetchCurrentUser(): Promise<User | null> {
  return (await api.get<User | undefined>("/api/auth/me")) ?? null;
}

export function useCurrentUser() {
  return useQuery({
    queryKey: meQueryKey,
    queryFn: fetchCurrentUser,
    staleTime: 5 * 60_000,
  });
}

export function useLogout() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      try {
        await api.post<undefined>("/api/auth/logout", undefined, { on401: "error" });
      } catch (error) {
        // 401: the session had already ended, which is what logging out wants anyway.
        if (!(error instanceof ApiError && error.status === 401)) throw error;
      }
      await refreshCsrfToken().catch(() => undefined);
    },
    onSuccess: () => {
      // Logging out of a demo deletes it on the server.
      const wasDemo = queryClient.getQueryData<User | null>(meQueryKey)?.demo === true;
      sessionEnd.set(wasDemo ? "demo-ended" : "logged-out");
      clearPrivateData(queryClient);
    },
  });
}

/**
 * Marks the user as logged out and drops everything cached about their account. The user query is
 * set to null (not removed) so the route guards watching it react immediately.
 */
export function clearPrivateData(queryClient: QueryClient): void {
  queryClient.setQueryData(meQueryKey, null);
  queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] });
}

export interface MessageResponse {
  message: string;
}

export function useResendVerification() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<MessageResponse>("/api/auth/resend-verification"),
    // The answer may be "already verified" (e.g. verified in another tab): refresh the user.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meQueryKey }),
  });
}
