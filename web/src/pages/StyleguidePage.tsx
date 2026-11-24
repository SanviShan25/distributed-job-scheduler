import { Plus } from "lucide-react";
import { useState } from "react";
import { Logo } from "../components/Logo";
import { Alert, Button, Card, Input, Spinner, StatusBadge } from "../components/ui";

const swatches = [
  ["brand-600", "bg-brand-600"],
  ["brand-700", "bg-brand-700"],
  ["ink", "bg-ink"],
  ["ink-muted", "bg-ink-muted"],
  ["line-strong", "bg-line-strong"],
  ["up-700", "bg-up-700"],
  ["down-700", "bg-down-700"],
  ["unknown-700", "bg-unknown-700"],
  ["warning-700", "bg-warning-700"],
] as const;

/** Development-only overview of the design system (/styleguide with `npm run dev`). */
export function StyleguidePage() {
  const [loading, setLoading] = useState(false);

  return (
    <main className="mx-auto flex max-w-3xl flex-col gap-6 px-4 py-8">
      <Logo />
      <h1 className="text-3xl font-bold tracking-tight">Style guide</h1>

      <Card title="Colours">
        <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {swatches.map(([name, bg]) => (
            <li key={name} className="flex items-center gap-2 text-sm">
              <span className={`size-8 rounded-md ${bg}`} aria-hidden="true" />
              {name}
            </li>
          ))}
        </ul>
      </Card>

      <Card title="Typography">
        <div className="flex flex-col gap-2">
          <p className="text-3xl font-bold tracking-tight">Page title (3xl bold)</p>
          <p className="text-xl font-bold tracking-tight">Section title (xl bold)</p>
          <p className="text-base">Body text (base). Short sentences, plain words.</p>
          <p className="text-sm text-ink-muted">Secondary text (sm, ink-muted)</p>
        </div>
      </Card>

      <Card title="Status">
        <div className="flex flex-wrap gap-2">
          <StatusBadge status="UP" />
          <StatusBadge status="DOWN" />
          <StatusBadge status="UNKNOWN" />
          <StatusBadge status="PAUSED" />
          <StatusBadge status="UP" size="sm" />
          <StatusBadge status="DOWN" size="sm" />
        </div>
      </Card>

      <Card title="Buttons">
        <div className="flex flex-wrap gap-3">
          <Button>Primary</Button>
          <Button variant="secondary">Secondary</Button>
          <Button variant="danger">Delete</Button>
          <Button variant="ghost">Ghost</Button>
          <Button icon={<Plus aria-hidden="true" className="size-5" />}>Add website</Button>
          <Button disabled>Disabled</Button>
          <Button
            loading={loading}
            loadingText="Saving…"
            onClick={() => {
              setLoading(true);
              setTimeout(() => {
                setLoading(false);
              }, 2000);
            }}
          >
            Click to load
          </Button>
        </div>
      </Card>

      <Card title="Inputs">
        <form className="flex flex-col gap-4" noValidate>
          <Input label="Email" type="email" autoComplete="email" />
          <Input
            label="Password"
            type="password"
            hint="Use 10 to 128 characters."
            autoComplete="new-password"
          />
          <Input
            label="Email"
            type="email"
            defaultValue="not-an-email"
            error="Enter a valid email address."
          />
          <Input label="Website name" optional />
          <Input label="Disabled" disabled defaultValue="Can't change this" />
        </form>
      </Card>

      <Card title="Alerts">
        <div className="flex flex-col gap-3">
          <Alert title="Check your inbox">We sent you a link to verify your email.</Alert>
          <Alert variant="success" title="Password changed" />
          <Alert
            variant="warning"
            title="Please verify your email"
            action={
              <Button size="md" variant="secondary">
                Resend link
              </Button>
            }
          >
            You need to verify your email before adding websites.
          </Alert>
          <Alert variant="error">Something went wrong. Please try again.</Alert>
        </div>
      </Card>

      <Card title="Spinner">
        <div className="flex items-center gap-6">
          <Spinner size="sm" />
          <Spinner />
          <Spinner size="lg" />
          <Spinner showLabel label="Loading your websites…" />
        </div>
      </Card>
    </main>
  );
}
