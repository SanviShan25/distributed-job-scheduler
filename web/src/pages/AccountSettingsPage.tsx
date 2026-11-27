import { useState, type SyntheticEvent } from "react";
import { isWrongPasswordError, useChangePassword, useDeleteAccount } from "../auth/account";
import { useCurrentUser } from "../auth/user";
import { passwordHint, validateCurrentPassword, validateNewPassword } from "../auth/validation";
import { Alert, Button, Card, Input, PasswordInput } from "../components/ui";
import { focusFirstError, hasErrors, type FieldErrors } from "../lib/forms";
import { usePageTitle } from "../lib/usePageTitle";

export function AccountSettingsPage() {
  usePageTitle("Account settings");
  const { data: user } = useCurrentUser();

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Account settings</h1>
      <Card title="Your account">
        <dl className="grid gap-1">
          <dt className="text-sm text-ink-muted">Email</dt>
          <dd className="font-semibold break-all">{user?.email}</dd>
        </dl>
      </Card>
      <ChangePasswordCard email={user?.email} />
      <DeleteAccountCard />
    </div>
  );
}

const changeIds = { currentPassword: "current-password", newPassword: "new-password" };

function ChangePasswordCard({ email }: { email: string | undefined }) {
  const change = useChangePassword();
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors<keyof typeof changeIds>>({});

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    change.reset();
    const found = {
      currentPassword: validateCurrentPassword(currentPassword),
      newPassword: validateNewPassword(newPassword),
    };
    setErrors(found);
    if (hasErrors(found)) {
      focusFirstError(found, changeIds);
      return;
    }
    change.mutate(
      { currentPassword, newPassword },
      {
        onSuccess: () => {
          setCurrentPassword("");
          setNewPassword("");
        },
        onError: (error) => {
          if (isWrongPasswordError(error)) {
            const next = { currentPassword: error.message };
            setErrors(next);
            focusFirstError(next, changeIds);
          }
        },
      },
    );
  }

  return (
    <Card title="Change password">
      <div className="flex flex-col gap-4">
        {change.isSuccess && (
          <Alert variant="success">
            {change.data.message} You&apos;ve been logged out on your other devices.
          </Alert>
        )}
        {change.error && !isWrongPasswordError(change.error) && (
          <Alert variant="error">{change.error.message}</Alert>
        )}
        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          {/*
            The account a password manager should update. Without a username in the form, it can't tell
            which saved login the new password belongs to. Hidden: the address is shown above.
          */}
          {email && <input type="email" autoComplete="username" value={email} readOnly hidden />}
          <PasswordInput
            id={changeIds.currentPassword}
            label="Current password"
            autoComplete="current-password"
            value={currentPassword}
            onChange={(e) => {
              setCurrentPassword(e.target.value);
            }}
            error={errors.currentPassword}
          />
          <PasswordInput
            id={changeIds.newPassword}
            label="New password"
            autoComplete="new-password"
            hint={passwordHint(newPassword)}
            value={newPassword}
            onChange={(e) => {
              setNewPassword(e.target.value);
            }}
            error={errors.newPassword}
          />
          <Button
            type="submit"
            className="self-start"
            loading={change.isPending}
            loadingText="Saving…"
          >
            Change password
          </Button>
        </form>
      </div>
    </Card>
  );
}

const CONFIRM_WORD = "DELETE";
const deleteIds = { confirmation: "delete-confirmation", password: "delete-password" };

function DeleteAccountCard() {
  const remove = useDeleteAccount();
  const [confirmation, setConfirmation] = useState("");
  const [password, setPassword] = useState("");
  const [errors, setErrors] = useState<FieldErrors<keyof typeof deleteIds>>({});

  function onSubmit(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    const found = {
      confirmation:
        confirmation.trim().toUpperCase() === CONFIRM_WORD
          ? undefined
          : `Type ${CONFIRM_WORD} to confirm.`,
      password: validateCurrentPassword(password),
    };
    setErrors(found);
    if (hasErrors(found)) {
      focusFirstError(found, deleteIds);
      return;
    }
    remove.mutate(password, {
      onError: (error) => {
        if (isWrongPasswordError(error)) {
          const next = { password: error.message };
          setErrors(next);
          focusFirstError(next, deleteIds);
        }
      },
    });
  }

  return (
    <Card
      title="Delete account"
      className="border-down-200"
      description="This permanently deletes your account, all your monitored websites and their history. It can't be undone."
    >
      <div className="flex flex-col gap-4">
        {remove.error && !isWrongPasswordError(remove.error) && (
          <Alert variant="error">{remove.error.message}</Alert>
        )}
        <form noValidate onSubmit={onSubmit} className="flex flex-col gap-4">
          <Input
            id={deleteIds.confirmation}
            label={`Type ${CONFIRM_WORD} to confirm`}
            autoComplete="off"
            autoCapitalize="characters"
            spellCheck={false}
            value={confirmation}
            onChange={(e) => {
              setConfirmation(e.target.value);
            }}
            error={errors.confirmation}
          />
          <PasswordInput
            id={deleteIds.password}
            label="Your password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => {
              setPassword(e.target.value);
            }}
            error={errors.password}
          />
          <Button
            type="submit"
            variant="danger"
            className="self-start"
            loading={remove.isPending}
            loadingText="Deleting…"
          >
            Delete my account
          </Button>
        </form>
      </div>
    </Card>
  );
}
