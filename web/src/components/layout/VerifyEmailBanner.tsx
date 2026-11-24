import { useResendVerification } from "../../auth/user";
import { Alert, Button } from "../ui";

/** Shown on every app page until the email address is verified. */
export function VerifyEmailBanner({ email }: { email: string }) {
  const resend = useResendVerification();

  return (
    <div className="flex flex-col gap-2">
      <Alert
        variant="warning"
        title="Please verify your email"
        action={
          <Button
            variant="secondary"
            loading={resend.isPending}
            loadingText="Sending…"
            onClick={() => {
              resend.mutate();
            }}
          >
            Resend link
          </Button>
        }
      >
        We sent a link to <strong className="break-all">{email}</strong>. Click it to confirm your
        address. You need to do this before you can add websites.
      </Alert>
      {resend.isSuccess && <Alert variant="success">{resend.data.message} Check your inbox.</Alert>}
      {resend.error && <Alert variant="error">{resend.error.message}</Alert>}
    </div>
  );
}
