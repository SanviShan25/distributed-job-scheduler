import { SearchX } from "lucide-react";
import { Link, useLocation } from "react-router";
import { useCurrentUser } from "../auth/user";
import { Logo } from "../components/Logo";
import { ButtonLink, Card } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

/**
 * Any address the app doesn't know. The server answers it with 200 (it can't tell app routes apart), so the
 * page asks search engines not to index it.
 */
export function NotFoundPage() {
  usePageTitle("Page not found", undefined, { noindex: true });
  const { pathname } = useLocation();
  const { data: user } = useCurrentUser();

  return (
    <div className="flex min-h-dvh flex-col">
      <header className="border-b border-line bg-surface">
        <div className="mx-auto flex h-16 max-w-5xl items-center px-4">
          <Link to="/" className="rounded-lg">
            <Logo />
          </Link>
        </div>
      </header>
      <main className="mx-auto flex w-full max-w-md flex-1 flex-col justify-center px-4 py-10">
        <Card>
          <div className="flex flex-col items-center gap-4 text-center">
            <SearchX aria-hidden="true" className="size-12 text-brand-600" />
            <h1 className="text-2xl font-bold tracking-tight">Page not found</h1>
            <p className="text-ink-muted">
              There&apos;s no page at{" "}
              <code className="font-semibold break-all text-ink">{pathname}</code>. The link may be
              old, or the address may have a typo.
            </p>
            <div className="mt-2 flex w-full flex-col gap-3 sm:flex-row sm:justify-center">
              {user ? (
                <ButtonLink to="/dashboard">Go to your websites</ButtonLink>
              ) : (
                <>
                  <ButtonLink to="/">Go to the home page</ButtonLink>
                  <ButtonLink to="/login" variant="secondary">
                    Log in
                  </ButtonLink>
                </>
              )}
            </div>
          </div>
        </Card>
      </main>
    </div>
  );
}
