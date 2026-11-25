import { MailCheck } from "lucide-react";
import { useLocation } from "react-router";
import { ButtonLink, Card, TextLink } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

export interface CheckInboxState {
  reason: "signup" | "reset";
  email: string;
}

/** After sign-up and after "forgot password". The wording never says whether an account exists. */
export function CheckInboxPage() {
  usePageTitle("Check your inbox");
  const state = useLocation().state as CheckInboxState | null;

  return (
    <Card titleAs="h1" title="Check your inbox">
      <div className="flex flex-col gap-5">
        <MailCheck aria-hidden="true" className="size-12 text-brand-600" />
        {state?.reason === "signup" && (
          <>
            <p>
              We&apos;ve sent an email to <strong className="break-all">{state.email}</strong>.
              Click the link in it to confirm your address, then log in.
            </p>
            <p className="text-ink-muted">The link works for 24 hours.</p>
          </>
        )}
        {state?.reason === "reset" && (
          <>
            <p>
              If there is an account for <strong className="break-all">{state.email}</strong>,
              we&apos;ve emailed it a link to choose a new password.
            </p>
            <p className="text-ink-muted">The link works for 1 hour.</p>
          </>
        )}
        {!state && <p>We&apos;ve sent you an email. Click the link in it to continue.</p>}

        <div className="rounded-xl bg-canvas p-4 text-sm text-ink-muted">
          <p className="font-semibold text-ink">No email after a few minutes?</p>
          <ul className="mt-1 list-disc pl-5">
            <li>Look in your spam or junk folder.</li>
            <li>Check the address is spelled correctly.</li>
            {state?.reason === "reset" && (
              <li>
                <TextLink to="/forgot-password">Request another link</TextLink>
              </li>
            )}
          </ul>
        </div>

        <ButtonLink to="/login">Go to log in</ButtonLink>
      </div>
    </Card>
  );
}
