import { useSyncExternalStore } from "react";
import { Navigate, Outlet, useLocation, useSearchParams } from "react-router";
import { FullPageError, FullPageSpinner } from "../components/layout/FullPageStatus";
import { loginPathFor, safeNextPath } from "./redirects";
import { sessionEnd } from "./session";
import { useCurrentUser } from "./user";

/** Pages for logged-in users. Anyone else is sent to log in, and back here afterwards. */
export function RequireAuth() {
  const { data: user, isPending, error, refetch } = useCurrentUser();
  const endReason = useSyncExternalStore(sessionEnd.subscribe, sessionEnd.get);
  const location = useLocation();

  if (isPending) return <FullPageSpinner />;
  if (user === undefined) {
    return <FullPageError message={error.message} onRetry={() => void refetch()} />;
  }
  if (user === null) {
    if (endReason === "logged-out") {
      return <Navigate to="/login" replace state={{ loggedOut: true }} />;
    }
    if (endReason === "account-deleted") {
      return <Navigate to="/login" replace state={{ accountDeleted: true }} />;
    }
    if (endReason === "demo-ended") {
      return <Navigate to="/" replace state={{ demoEnded: true }} />;
    }
    const here = location.pathname + location.search + location.hash;
    return (
      <Navigate
        to={loginPathFor(here)}
        replace
        state={{ sessionExpired: endReason === "expired" }}
      />
    );
  }
  return <Outlet />;
}

/** Login, sign-up and the home page: logged-in users go straight on to the app (or ?next=). */
export function PublicOnly() {
  const { data: user, isPending } = useCurrentUser();
  const [params] = useSearchParams();

  if (isPending) return <FullPageSpinner />;
  if (user) return <Navigate to={safeNextPath(params.get("next"))} replace />;
  // Also shown if the user could not be loaded: these pages work without it.
  return <Outlet />;
}
