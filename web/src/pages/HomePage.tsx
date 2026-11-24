import { Link } from "react-router";
import { Card } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

export function HomePage() {
  usePageTitle("Website monitoring made simple");
  return (
    <Card titleAs="h1" title="Know when your website goes down">
      <p className="text-ink-muted">
        PulseCheck checks your website around the clock and emails you the moment it stops working,
        and again when it is back.
      </p>
      <div className="mt-6 flex flex-col gap-3 sm:flex-row">
        <Link
          to="/signup"
          className="inline-flex min-h-11 items-center justify-center rounded-lg bg-brand-600 px-4 font-semibold text-white shadow-sm hover:bg-brand-700"
        >
          Create a free account
        </Link>
        <Link
          to="/login"
          className="inline-flex min-h-11 items-center justify-center rounded-lg border border-line-strong bg-surface px-4 font-semibold text-ink shadow-sm hover:bg-canvas"
        >
          Log in
        </Link>
      </div>
    </Card>
  );
}
