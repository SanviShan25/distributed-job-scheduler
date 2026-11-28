import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, refreshCsrfToken } from "../api/client";
import { sessionEnd } from "./session";
import { meQueryKey, type User } from "./user";

/** What the site offers to visitors who are not logged in (public GET /api/config). */
export interface AppConfig {
  /** "Try the demo" is available. */
  demoEnabled: boolean;
  /** Anyone may sign up; false when sign-ups are limited to invited addresses. */
  signupOpen: boolean;
}

export function useAppConfig() {
  return useQuery({
    queryKey: ["config"],
    queryFn: () => api.get<AppConfig>("/api/config"),
    // Changes only when the site is redeployed. Retried like other queries (not on 4xx).
    staleTime: Infinity,
    // If it failed, the pages go on without it (as if there were no demo). Without this, each component
    // that reads it would refetch on mount, which puts a query without data back to "pending"; PublicOnly
    // then shows its spinner, unmounting the page, and the two would loop.
    retryOnMount: false,
  });
}

/**
 * "Try the demo": the backend creates a private demo account for this visitor and logs them in. Like
 * login, the session and CSRF token are new afterwards. Setting the user makes PublicOnly continue to
 * the dashboard.
 */
export function useStartDemo() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      const user = await api.post<User>("/api/auth/demo", undefined, { on401: "error" });
      await refreshCsrfToken().catch(() => undefined);
      return user;
    },
    onSuccess: (user) => {
      sessionEnd.set(null);
      // Anything cached from an earlier demo or account belongs to someone else now.
      queryClient.removeQueries({ predicate: (query) => query.queryKey[0] !== meQueryKey[0] });
      queryClient.setQueryData(meQueryKey, user);
    },
  });
}
