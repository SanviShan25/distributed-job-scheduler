import { useEffect } from "react";
import { Link, useLocation } from "react-router";
import { sessionEnd } from "../auth/session";
import { Alert, Card } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

interface LoginLocationState {
  sessionExpired?: boolean;
  loggedOut?: boolean;
}

// The login form itself is added with the other account pages.
export function LoginPage() {
  usePageTitle("Log in");
  const state = useLocation().state as LoginLocationState | null;

  useEffect(() => {
    sessionEnd.set(null);
  }, []);

  return (
    <Card titleAs="h1" title="Log in">
      <div className="flex flex-col gap-4">
        {state?.sessionExpired && (
          <Alert variant="info">Your session has ended. Please log in again.</Alert>
        )}
        {state?.loggedOut && <Alert variant="success">You&apos;ve been logged out.</Alert>}
        <p className="text-ink-muted">
          New to PulseCheck?{" "}
          <Link to="/signup" className="font-semibold text-brand-700 underline underline-offset-4">
            Create an account
          </Link>
        </p>
      </div>
    </Card>
  );
}
