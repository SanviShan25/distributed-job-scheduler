import { useEffect, useRef } from "react";
import { isInvalidLinkError, useVerifyEmail } from "../auth/account";
import { useTokenFromUrl } from "../auth/useTokenFromUrl";
import { useCurrentUser, useResendVerification } from "../auth/user";
import { Alert, Button, ButtonLink, Card, Spinner } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

/** The link from the verification email: /verify-email?token=… Works logged in or out. */
export function VerifyEmailPage() {
  usePageTitle("Verify your email");
  const token = useTokenFromUrl();
  const { data: user } = useCurrentUser();
  const { mutate, status, error, data } = useVerifyEmail();
  const sent = useRef(false);

  // Send the token exactly once. Tokens are single-use, so a second request (React's development
  // double-run of effects, or a re-render) would turn a success into "link already used".
  useEffect(() => {
    if (token && !sent.current) {
      sent.current = true;
      mutate(token);
    }
  }, [token, mutate]);

  const loggedIn = Boolean(user);
  const nextStep = loggedIn ? (
    <ButtonLink to="/dashboard">Go to your websites</ButtonLink>
  ) : (
    <ButtonLink to="/login">Log in</ButtonLink>
  );

  let content;
  if (!token || (status === "error" && isInvalidLinkError(error))) {
    content = <InvalidLink loggedIn={loggedIn} />;
  } else if (status === "success") {
    content = (
      <>
        <Alert variant="success" title="Thanks, your email address is verified.">
          {data.message === "Your email address is verified."
            ? "You can now add websites to monitor."
            : data.message}
        </Alert>
        {nextStep}
      </>
    );
  } else if (status === "error") {
    content = (
      <Alert
        variant="error"
        action={
          <Button
            variant="secondary"
            onClick={() => {
              mutate(token);
            }}
          >
            Try again
          </Button>
        }
      >
        {error.message}
      </Alert>
    );
  } else {
    content = <Spinner showLabel label="Verifying your email address…" />;
  }

  return (
    <Card titleAs="h1" title="Verify your email">
      <div className="flex flex-col gap-5">{content}</div>
    </Card>
  );
}

function InvalidLink({ loggedIn }: { loggedIn: boolean }) {
  const resend = useResendVerification();
  return (
    <>
      <Alert variant="warning" title="This link has expired or was already used">
        Verification links work once, for 24 hours. If you&apos;ve already verified your address,
        there&apos;s nothing more to do.
      </Alert>
      {loggedIn ? (
        <>
          <Button
            loading={resend.isPending}
            loadingText="Sending…"
            onClick={() => {
              resend.mutate();
            }}
          >
            Request a new link
          </Button>
          {resend.isSuccess && (
            <Alert variant="success">{resend.data.message} Check your inbox.</Alert>
          )}
          {resend.error && <Alert variant="error">{resend.error.message}</Alert>}
        </>
      ) : (
        <>
          <p className="text-ink-muted">
            To get a new link, log in and choose &ldquo;Resend link&rdquo;.
          </p>
          <ButtonLink to="/login">Log in to request a new link</ButtonLink>
        </>
      )}
    </>
  );
}
