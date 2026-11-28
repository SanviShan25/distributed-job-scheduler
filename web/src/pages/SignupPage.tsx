import { useState, type SyntheticEvent } from "react";
import { useNavigate } from "react-router";
import { ApiError } from "../api/client";
import { useSignup } from "../auth/account";
import { useAppConfig } from "../auth/demo";
import { DemoOffer } from "../demo/DemoOffer";
import {
  normalizeEmail,
  passwordHint,
  validateEmail,
  validateNewPassword,
} from "../auth/validation";
import { Alert, Button, Card, Input, PasswordInput, TextLink } from "../components/ui";
import { focusFirstError, hasErrors, type FieldErrors } from "../lib/forms";
import { usePageTitle } from "../lib/usePageTitle";
import type { CheckInboxState } from "./CheckInboxPage";

const fieldIds = { email: "signup-email", password: "signup-password" };

export function SignupPage() {
  usePageTitle("Create an account");
  const navigate = useNavigate();
  const signup = useSignup();
  const { data: config } = useAppConfig();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors<keyof typeof fieldIds>>({});

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = { email: validateEmail(email), password: validateNewPassword(password) };
    setErrors(found);
    if (hasErrors(found)) {
      focusFirstError(found, fieldIds);
      return;
    }
    const address = normalizeEmail(email);
    signup.mutate(
      { email: address, password },
      {
        onSuccess: () => {
          const state: CheckInboxState = { reason: "signup", email: address };
          void navigate("/check-inbox", { state });
        },
        onError: (error) => {
          // The backend's own checks, in case they differ from ours: show them on the field.
          if (error instanceof ApiError && error.status === 400) {
            const field = /password/i.test(error.message) ? "password" : "email";
            const next = { [field]: error.message };
            setErrors(next);
            focusFirstError(next, fieldIds);
          }
        },
      },
    );
  }

  const fieldError = signup.error instanceof ApiError && signup.error.status === 400;

  return (
    <Card
      titleAs="h1"
      title="Create an account"
      description="Free. We'll email you when your website goes down."
    >
      <div className="flex flex-col gap-5">
        {config?.signupOpen === false && (
          <Alert variant="info" title="Sign-ups are limited right now">
            Only invited email addresses can create an account on this site.
            {config.demoEnabled
              ? " You can still try everything in a private demo, no sign-up needed."
              : " If you were invited, sign up with the address you were invited with."}
          </Alert>
        )}
        {signup.error && !fieldError && <Alert variant="error">{signup.error.message}</Alert>}

        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <Input
            id={fieldIds.email}
            label="Email"
            type="email"
            autoComplete="email"
            inputMode="email"
            hint="We'll send a link to this address to confirm it's yours."
            value={email}
            onChange={(e) => {
              setEmail(e.target.value);
            }}
            error={errors.email}
          />
          <PasswordInput
            id={fieldIds.password}
            label="Password"
            autoComplete="new-password"
            hint={passwordHint(password)}
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
            }}
            error={errors.password}
          />
          <Button
            type="submit"
            fullWidth
            loading={signup.isPending}
            loadingText="Creating your account…"
          >
            Create account
          </Button>
        </form>

        <p className="text-ink-muted">
          Already have an account? <TextLink to="/login">Log in</TextLink>
        </p>

        <DemoOffer />
      </div>
    </Card>
  );
}
