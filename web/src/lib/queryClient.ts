import { QueryClient } from "@tanstack/react-query";
import { ApiError } from "../api/client";

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        // Retry only what might pass on its own (network trouble, server errors), never 4xx answers.
        retry: (failureCount, error) =>
          failureCount < 2 &&
          !(error instanceof ApiError && error.status >= 400 && error.status < 500),
      },
      mutations: {
        retry: false,
      },
    },
  });
}
