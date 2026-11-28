import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { Outlet } from "react-router";
import { refreshCsrfToken } from "../../api/client";
import { sessionEnd } from "../../auth/session";
import { clearPrivateData, meQueryKey, type User } from "../../auth/user";

export function RootLayout() {
  const queryClient = useQueryClient();

  // Get a CSRF cookie on start-up, so the first form submit doesn't need an extra round trip. If it
  // fails (e.g. offline), the API client fetches one before the first state-changing request.
  useEffect(() => {
    refreshCsrfToken().catch(() => undefined);
  }, []);

  // When an API call reports that the session has ended, forget the user and their data. The route
  // guard then sends them to log in.
  useEffect(
    () =>
      sessionEnd.subscribe(() => {
        if (sessionEnd.get() !== "expired") return;
        // A demo account's session ends when the demo does: say so, rather than "please log in".
        if (queryClient.getQueryData<User | null>(meQueryKey)?.demo) {
          sessionEnd.set("demo-ended"); // notifies this listener again, which then does nothing
        }
        clearPrivateData(queryClient);
      }),
    [queryClient],
  );

  return <Outlet />;
}
