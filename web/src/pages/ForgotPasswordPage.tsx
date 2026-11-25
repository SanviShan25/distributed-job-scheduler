import { useState, type SyntheticEvent } from "react";
import { useNavigate } from "react-router";
import { useForgotPassword } from "../auth/account";
import { normalizeEmail, validateEmail } from "../auth/validation";
import { Alert, Button, Card, Input, TextLink } from "../components/ui";
import { focusFirstError, type FieldErrors } from "../lib/forms";
import { usePageTitle } from "../lib/usePageTitle";
import type { CheckInboxState } from "./CheckInboxPage";

const fieldIds = { email: "forgot-email" };

export function ForgotPasswordPage() {
  usePageTitle("Reset your password");
  const navigate = useNavigate();
  const forgot = useForgotPassword();
  const [email, setEmail] = useState("");
  const [errors, setErrors] = useState<FieldErrors<"email">>({});

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = { email: validateEmail(email) };
    setErrors(found);
    if (found.email) {
      focusFirstError(found, fieldIds);
      return;
    }
    const address = normalizeEmail(email);
    forgot.mutate(address, {
      onSuccess: () => {
        const state: CheckInboxState = { reason: "reset", email: address };
        void navigate("/check-inbox", { state });
      },
    });
  }

  return (
    <Card
      titleAs="h1"
      title="Reset your password"
      description="Enter the email address you signed up with and we'll send you a link to choose a new password."
    >
      <div className="flex flex-col gap-5">
        {forgot.error && <Alert variant="error">{forgot.error.message}</Alert>}
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
          <Button type="submit" fullWidth loading={forgot.isPending} loadingText="Sending…">
            Send reset link
          </Button>
        </form>
        <p className="text-ink-muted">
          Remembered it? <TextLink to="/login">Log in</TextLink>
        </p>
      </div>
    </Card>
  );
}
