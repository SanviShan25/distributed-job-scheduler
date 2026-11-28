import { BellRing, ChartLine, LockKeyhole, Timer } from "lucide-react";
import dashboardScreenshot from "../assets/dashboard.png";
import type { ReactNode } from "react";
import { Link, useLocation } from "react-router";
import { useAppConfig } from "../auth/demo";
import { Logo } from "../components/Logo";
import { Alert, ButtonLink } from "../components/ui";
import { TryDemoButton } from "../demo/TryDemoButton";
import { usePageTitle } from "../lib/usePageTitle";

export const GITHUB_URL = "https://github.com/SanviShan25/distributed-job-scheduler";

const STEPS = [
  {
    title: "Add your website",
    text: "Type its address, for example example.com. That's all the setup there is.",
  },
  {
    title: "We check it around the clock",
    text: "Every minute, day and night, we visit your site the way a customer would.",
  },
  {
    title: "Get an email if it goes down",
    text: "We tell you the moment it stops working, and again when it's back up.",
  },
];

const FEATURES: { icon: typeof Timer; title: string; text: string }[] = [
  {
    icon: Timer,
    title: "Checks every minute",
    text: "Find out about problems in minutes, not when a customer complains.",
  },
  {
    icon: BellRing,
    title: "Email alerts",
    text: "One email when your site goes down and one when it recovers. No noise in between.",
  },
  {
    icon: ChartLine,
    title: "Uptime charts",
    text: "See how often your site was up and how fast it answered over the last day and week.",
  },
  {
    icon: LockKeyhole,
    title: "Private by default",
    text: "Only you can see your websites. Nobody else gets your alerts unless they agree to.",
  },
];

interface LandingState {
  demoEnded?: boolean;
}

/** The public home page: what PulseCheck is, for people who have never heard of it. */
export function LandingPage() {
  usePageTitle(
    "Website uptime monitoring with email alerts",
    "PulseCheck checks your website every minute and emails you the moment it goes down, and again when it's back. Free, private, and set up in a minute.",
  );
  const state = useLocation().state as LandingState | null;
  const { data: config } = useAppConfig();
  // If the settings can't be loaded, offer sign-up: the sign-up page explains any limits itself.
  const signupOpen = config?.signupOpen !== false;

  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-30 rounded-lg bg-surface px-4 py-2 font-semibold focus:not-sr-only focus:absolute focus:top-2 focus:left-2"
      >
        Skip to main content
      </a>
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex h-16 max-w-5xl items-center justify-between gap-4 px-4">
          <Link to="/" className="rounded-lg">
            <Logo />
          </Link>
          <ButtonLink to="/login" variant="secondary">
            Log in
          </ButtonLink>
        </div>
      </header>

      <main id="main" tabIndex={-1} className="flex-1">
        <section className="mx-auto flex max-w-5xl flex-col gap-8 px-4 pt-12 pb-16 sm:pt-16">
          {state?.demoEnded && (
            <Alert variant="success" className="max-w-2xl">
              Your demo has ended and its data has been deleted. You can start a new one any time.
            </Alert>
          )}
          <div className="flex max-w-2xl flex-col gap-5">
            <h1 className="text-4xl font-bold tracking-tight text-balance sm:text-5xl">
              Know when your website goes down
            </h1>
            <p className="text-lg text-ink-muted sm:text-xl">
              PulseCheck checks your website every minute and emails you the moment it stops
              working, and again when it&apos;s back.
            </p>
            <div className="flex flex-col gap-3 sm:flex-row sm:items-start">
              <TryDemoButton size="lg" />
              {signupOpen && (
                <ButtonLink
                  to="/signup"
                  variant={config?.demoEnabled ? "secondary" : "primary"}
                  className="min-h-12 px-6 text-lg"
                >
                  Create a free account
                </ButtonLink>
              )}
              <ButtonLink to="/login" variant="secondary" className="min-h-12 px-6 text-lg">
                Log in
              </ButtonLink>
            </div>
            {config?.demoEnabled && (
              <p className="text-sm text-ink-muted">
                The demo is private to you, needs no sign-up and is deleted after 3 hours.
              </p>
            )}
          </div>
          <DashboardPreview />
        </section>

        <Section id="how-it-works" title="How it works">
          <ol className="grid gap-4 sm:grid-cols-3">
            {STEPS.map((step, index) => (
              <li key={step.title} className="rounded-2xl border border-line bg-surface p-6">
                <span
                  aria-hidden="true"
                  className="mb-4 inline-flex size-9 items-center justify-center rounded-full bg-brand-600 font-bold text-white"
                >
                  {index + 1}
                </span>
                <h3 className="text-lg font-semibold">
                  <span className="sr-only">Step {index + 1}: </span>
                  {step.title}
                </h3>
                <p className="mt-1 text-ink-muted">{step.text}</p>
              </li>
            ))}
          </ol>
        </Section>

        <Section id="features" title="What you get">
          <ul className="grid gap-4 sm:grid-cols-2">
            {FEATURES.map(({ icon: Icon, title, text }) => (
              <li key={title} className="flex gap-4 rounded-2xl border border-line bg-surface p-6">
                <Icon aria-hidden="true" className="size-7 shrink-0 text-brand-700" />
                <div>
                  <h3 className="text-lg font-semibold">{title}</h3>
                  <p className="mt-1 text-ink-muted">{text}</p>
                </div>
              </li>
            ))}
          </ul>
        </Section>
      </main>

      <footer className="border-t border-line bg-surface">
        <div className="mx-auto flex max-w-5xl flex-col gap-2 px-4 py-6 text-sm text-ink-muted sm:flex-row sm:items-center sm:justify-between">
          <p>PulseCheck: website uptime monitoring.</p>
          <a
            href={GITHUB_URL}
            className="font-semibold text-brand-700 underline underline-offset-4 hover:text-brand-800"
          >
            Source code on GitHub
          </a>
        </div>
      </footer>
    </div>
  );
}

function Section({ id, title, children }: { id: string; title: string; children: ReactNode }) {
  const headingId = `${id}-heading`;
  return (
    <section aria-labelledby={headingId} className="mx-auto max-w-5xl px-4 pb-16">
      <h2 id={headingId} className="mb-6 text-2xl font-bold tracking-tight sm:text-3xl">
        {title}
      </h2>
      {children}
    </section>
  );
}

/**
 * The dashboard, captured by the end-to-end screenshot run (npm run e2e:screenshots). Width and height are
 * set so the page doesn't shift while it loads; it's below the fold on phones, so it loads lazily.
 */
function DashboardPreview() {
  return (
    <figure className="flex flex-col gap-3">
      <img
        src={dashboardScreenshot}
        width={1040}
        height={480}
        loading="lazy"
        decoding="async"
        alt="The PulseCheck dashboard: three websites, each with its status (Up), uptime over the last 24 hours and average response time."
        className="h-auto w-full rounded-2xl border border-line shadow-sm"
      />
      <figcaption className="text-center text-sm text-ink-muted">
        Your dashboard: every website, whether it&apos;s up, and its uptime at a glance.
      </figcaption>
    </figure>
  );
}
