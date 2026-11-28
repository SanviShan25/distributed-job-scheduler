import { Link, Outlet } from "react-router";
import { accountLabel, useCurrentUser } from "../../auth/user";
import { DemoBanner } from "../../demo/DemoBanner";
import { Logo } from "../Logo";
import { UserMenu } from "./UserMenu";
import { VerifyEmailBanner } from "./VerifyEmailBanner";

/** Header, verification banner and page content for logged-in pages (inside RequireAuth). */
export function AppLayout() {
  const { data: user } = useCurrentUser();
  if (!user) return null; // RequireAuth renders this only with a user

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
          <Link to="/dashboard" className="rounded-lg">
            <Logo />
          </Link>
          <UserMenu email={accountLabel(user)} demo={user.demo} />
        </div>
      </header>
      <main id="main" tabIndex={-1} className="mx-auto w-full max-w-5xl flex-1 px-4 py-6 sm:py-8">
        {user.demo && (
          <div className="mb-6">
            <DemoBanner user={user} />
          </div>
        )}
        {!user.emailVerified && (
          <div className="mb-6">
            <VerifyEmailBanner email={user.email} />
          </div>
        )}
        <Outlet />
      </main>
    </div>
  );
}
