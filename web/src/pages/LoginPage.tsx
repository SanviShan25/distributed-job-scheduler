import { useEffect, useState, type SyntheticEvent } from "react";
import { useLocation } from "react-router";
import { useLogin } from "../auth/account";
import { sessionEnd } from "../auth/session";
import { validateCurrentPassword, validateEmail } from "../auth/validation";
import { Alert, Button, Card, Checkbox, Input, PasswordInput, TextLink } from "../components/ui";
import { focusFirstError, hasErrors, type FieldErrors } from "../lib/forms";
import { usePageTitle } from "../lib/usePageTitle";
import { DemoOffer } from "../demo/DemoOffer";

interface LoginLocationState {
  sessionExpired?: boolean;
  loggedOut?: boolean;
  accountDeleted?: boolean;
}

const fieldIds = { email: "login-email", password: "login-password" };

export function LoginPage() {
  usePageTitle("Log in");
  const state = useLocation().state as LoginLocationState | null;
  const login = useLogin();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [rememberMe, setRememberMe] = useState(false);
  const [errors, setErrors] = useState<FieldErrors<keyof typeof fieldIds>>({});

  useEffect(() => {
    sessionEnd.set(null);
  }, []);

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = { email: validateEmail(email), password: validateCurrentPassword(password) };
    setErrors(found);
    if (hasErrors(found)) {
      focusFirstError(found, fieldIds);
      return;
    }
    login.mutate({ email: email.trim(), password, rememberMe });
  }

  return (
    <Card titleAs="h1" title="Log in">
      <div className="flex flex-col gap-5">
        {state?.sessionExpired && (
          <Alert variant="info">Your session has ended. Please log in again.</Alert>
        )}
        {state?.loggedOut && <Alert variant="success">You&apos;ve been logged out.</Alert>}
        {state?.accountDeleted && (
          <Alert variant="success">Your account and all its data have been deleted.</Alert>
        )}
        {login.error && <Alert variant="error">{login.error.message}</Alert>}

        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <Input
            id={fieldIds.email}
            label="Email"
            type="email"
            autoComplete="email"
            inputMode="email"
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
            }}
            error={errors.email}
          />
          <PasswordInput
            id={fieldIds.password}
            label="Password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
            }}
            error={errors.password}
          />
          <Checkbox
            label="Keep me logged in"
            hint="For 30 days on this device. Don't use on shared computers."
            checked={rememberMe}
            onChange={(e) => {
              setRememberMe(e.target.checked);
            }}
          />
          <Button type="submit" fullWidth loading={login.isPending} loadingText="Logging in…">
            Log in
          </Button>
        </form>

        <div className="flex flex-col gap-2 text-ink-muted">
          <p>
            <TextLink to="/forgot-password">Forgot your password?</TextLink>
          </p>
          <p>
            New to PulseCheck? <TextLink to="/signup">Create an account</TextLink>
          </p>
        </div>

        <DemoOffer />
      </div>
    </Card>
  );
}
