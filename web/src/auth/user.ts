import { QueryClient, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, refreshCsrfToken } from "../api/client";
import { sessionEnd } from "./session";

export interface User {
  id: string;
  email: string;
  emailVerified: boolean;
  createdAt: string;
}

export const meQueryKey = ["auth", "me"] as const;

/** The logged-in user, or null when nobody is logged in. */
export async function fetchCurrentUser(): Promise<User | null> {
  try {
    return await api.get<User>("/api/auth/me", { on401: "error" });
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null;
    throw error;
  }
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
      sessionEnd.set("logged-out");
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
