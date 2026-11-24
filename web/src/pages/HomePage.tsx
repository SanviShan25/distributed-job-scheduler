import { Card } from "../components/ui";
import { Logo } from "../components/Logo";

export function HomePage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center gap-6 px-4 py-10">
      <Logo className="self-center" />
      <Card titleAs="h1" title="Know when your website goes down">
        <p className="text-ink-muted">
          PulseCheck checks your website around the clock and emails you the moment it stops
          working, and again when it is back.
        </p>
      </Card>
    </main>
  );
}
