import { Link } from "react-router";
import { Card } from "../components/ui";

export function NotFoundPage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center px-4 py-10">
      <Card titleAs="h1" title="Page not found">
        <p className="text-ink-muted">
          We couldn&apos;t find that page. The link may be old or mistyped.
        </p>
        <Link
          to="/"
          className="mt-4 inline-block font-semibold text-brand-700 underline underline-offset-4 hover:text-brand-800"
        >
          Go to the home page
        </Link>
      </Card>
    </main>
  );
}
