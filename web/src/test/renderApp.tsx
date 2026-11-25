import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { StrictMode } from "react";
import { createMemoryRouter, RouterProvider } from "react-router";
import type { User } from "../auth/user";
import { createQueryClient } from "../lib/queryClient";
import { routes } from "../routes";
import { json, problem, type Handler } from "./fakeBackend";

/** Renders the whole app at a path, as the browser would load it. */
export function renderApp(path: string) {
  const queryClient = createQueryClient();
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  // StrictMode like main.tsx, so effects run twice as in development (e.g. a one-time token must
  // still be sent only once).
  const view = render(
    <StrictMode>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </StrictMode>,
  );
  const location = () =>
    router.state.location.pathname + router.state.location.search + router.state.location.hash;
  return { ...view, router, queryClient, location };
}

export const alice: User = {
  id: "7d1c5a52-1f43-4a8e-9a3a-0c6f0d1f8a11",
  email: "alice@example.com",
  emailVerified: true,
  createdAt: "2026-11-20T10:00:00Z",
};

export const loggedIn =
  (user: User = alice): Handler =>
  () =>
    json(200, user);

export const loggedOut: Handler = () => problem(401, "Please log in");
