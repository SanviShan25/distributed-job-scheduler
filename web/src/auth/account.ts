import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, refreshCsrfToken } from "../api/client";
import { sessionEnd } from "./session";
import { clearPrivateData, meQueryKey, type MessageResponse, type User } from "./user";

// Mutations for the account pages. Messages shown on success come from the backend where it has
// one, so the wording (e.g. the deliberately vague forgot-password answer) stays in one place.

export interface LoginInput {
  email: string;
  password: string;
  rememberMe: boolean;
}

export function useLogin() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: LoginInput) => {
      // A wrong password is a 401 here, which must not count as "session expired".
      const user = await api.post<User>("/api/auth/login", input, { on401: "error" });
      // The backend issued a new CSRF token with the new session.
      await refreshCsrfToken().catch(() => undefined);
      return user;
    },
    onSuccess: (user) => {
      sessionEnd.set(null);
      // PublicOnly sees the user and continues to ?next= (or the dashboard).
      queryClient.setQueryData(meQueryKey, user);
    },
  });
}

export interface SignupResponse {
  email: string;
  emailVerified: boolean;
  message: string;
}

export function useSignup() {
  return useMutation({
    mutationFn: (input: { email: string; password: string }) =>
      api.post<SignupResponse>("/api/auth/signup", input),
  });
}

export function useForgotPassword() {
  return useMutation({
    mutationFn: (email: string) =>
      api.post<MessageResponse>("/api/auth/forgot-password", { email }),
  });
}

export function useVerifyEmail() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (token: string) => api.post<MessageResponse>("/api/auth/verify", { token }),
    // If this browser is logged in, the "please verify" banner should go away.
    onSuccess: () => queryClient.invalidateQueries({ queryKey: meQueryKey }),
  });
}

export function useResetPassword() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { token: string; newPassword: string }) =>
      api.post<MessageResponse>("/api/auth/reset-password", input),
    // The backend ended every session of this account, possibly including this browser's.
    onSuccess: () => {
      clearPrivateData(queryClient);
    },
  });
}

export function useChangePassword() {
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      api.post<MessageResponse>("/api/auth/change-password", input),
  });
}

export function useDeleteAccount() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (password: string) => {
      await api.delete<undefined>("/api/auth/account", { password });
      await refreshCsrfToken().catch(() => undefined);
    },
    onSuccess: () => {
      sessionEnd.set("account-deleted");
      clearPrivateData(queryClient);
    },
  });
}

/** The backend's answer for an expired, used or unknown email link. */
export function isInvalidLinkError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 400 && /link/i.test(error.message);
}

/** "Your password is incorrect" (403) when confirming with the current password. */
export function isWrongPasswordError(error: unknown): boolean {
  return error instanceof ApiError && error.status === 403 && /password/i.test(error.message);
}
