import { useNavigate } from "react-router";
import { useCurrentUser, useResendVerification } from "../auth/user";
import { Alert, Button, ButtonLink, Card, TextLink } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";
import { MonitorForm } from "../monitors/MonitorForm";
import { useCreateMonitor, useMonitors } from "../monitors/monitors";

/** /monitors/new: add a website. Afterwards the website's page shows its first check arriving. */
export function AddMonitorPage() {
  usePageTitle("Add a website");
  const navigate = useNavigate();
  const { data: user } = useCurrentUser();
  const { data: monitors } = useMonitors();
  const create = useCreateMonitor();

  const limit = user?.monitorLimit;
  const atLimit = limit !== undefined && monitors !== undefined && monitors.length >= limit;

  return (
    <div className="flex max-w-2xl flex-col gap-6">
      <p>
        <TextLink to="/dashboard">← All websites</TextLink>
      </p>
      {atLimit ? (
        <Card titleAs="h1" title="You've reached the limit">
          <p className="text-ink-muted">
            You can check up to {limit} websites. Delete one you no longer need to add another.
          </p>
          <ButtonLink to="/dashboard" className="mt-5">
            Go to your websites
          </ButtonLink>
        </Card>
      ) : (
        <Card
          titleAs="h1"
          title="Add a website"
          description="We'll check it around the clock and email you if it goes down."
        >
          <MonitorForm
            submitLabel="Add website"
            savingLabel="Adding…"
            saving={create.isPending}
            error={create.error}
            onSubmit={(input) => {
              create.mutate(input, {
                onSuccess: (monitor) => {
                  void navigate(`/monitors/${monitor.id}`);
                },
              });
            }}
            renderErrorAction={(error) =>
              error.kind === "email-not-verified" ? <ResendVerificationButton /> : null
            }
          />
        </Card>
      )}
    </div>
  );
}

function ResendVerificationButton() {
  const resend = useResendVerification();
  if (resend.isSuccess) {
    return <p className="font-semibold">Sent! Check your inbox.</p>;
  }
  return (
    <div className="flex flex-col gap-2">
      <Button
        variant="secondary"
        loading={resend.isPending}
        loadingText="Sending…"
        onClick={() => {
          resend.mutate();
        }}
      >
        Send a new link
      </Button>
      {resend.error && <Alert variant="error">{resend.error.message}</Alert>}
    </div>
  );
}
