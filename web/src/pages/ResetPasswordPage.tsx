import { useState, type SyntheticEvent } from "react";
import { ApiError } from "../api/client";
import { isInvalidLinkError, useResetPassword } from "../auth/account";
import { useTokenFromUrl } from "../auth/useTokenFromUrl";
import { passwordHint, validateNewPassword } from "../auth/validation";
import { Alert, Button, ButtonLink, Card, PasswordInput } from "../components/ui";
import { focusFirstError, type FieldErrors } from "../lib/forms";
import { usePageTitle } from "../lib/usePageTitle";

const fieldIds = { password: "reset-password" };

/** The link from the password-reset email: /reset-password?token=… */
export function ResetPasswordPage() {
  usePageTitle("Choose a new password");
  const token = useTokenFromUrl();
  const reset = useResetPassword();
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors<"password">>({});

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) return;
    const found = { password: validateNewPassword(password) };
    setErrors(found);
    if (found.password) {
      focusFirstError(found, fieldIds);
      return;
    }
    reset.mutate(
      { token, newPassword: password },
      {
        onError: (error) => {
          // A password the backend rejects (the link is not used up in that case).
          if (error instanceof ApiError && error.status === 400 && !isInvalidLinkError(error)) {
            setErrors({ password: error.message });
            focusFirstError({ password: error.message }, fieldIds);
          }
        },
      },
    );
  }

  if (!token || isInvalidLinkError(reset.error)) {
    return (
      <Card titleAs="h1" title="Choose a new password">
        <div className="flex flex-col gap-5">
          <Alert variant="warning" title="This link has expired or was already used">
            Password reset links work once, for 1 hour. You can ask for a new one.
          </Alert>
          <ButtonLink to="/forgot-password">Request a new link</ButtonLink>
        </div>
      </Card>
    );
  }

  if (reset.isSuccess) {
    return (
      <Card titleAs="h1" title="Password changed">
        <div className="flex flex-col gap-5">
          <Alert variant="success">
            {reset.data.message} For your safety, you&apos;ve been logged out on all devices.
          </Alert>
          <ButtonLink to="/login">Log in</ButtonLink>
        </div>
      </Card>
    );
  }

  const otherError =
    reset.error && !(reset.error instanceof ApiError && reset.error.status === 400);

  return (
    <Card titleAs="h1" title="Choose a new password">
      <div className="flex flex-col gap-5">
        {otherError && <Alert variant="error">{reset.error.message}</Alert>}
        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <PasswordInput
            id={fieldIds.password}
            label="New password"
            autoComplete="new-password"
            hint={passwordHint(password)}
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
            }}
            error={errors.password}
          />
          <Button type="submit" fullWidth loading={reset.isPending} loadingText="Saving…">
            Save new password
          </Button>
        </form>
      </div>
    </Card>
  );
}
