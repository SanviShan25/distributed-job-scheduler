import { Link, Outlet } from "react-router";
import { Logo } from "../Logo";

/** Centred single-column layout for the home, login, sign-up and email-link pages. */
export function AuthLayout() {
  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-md flex-col justify-center gap-6 px-4 py-10">
      <Link to="/" className="self-center rounded-lg">
        <Logo />
      </Link>
      <Outlet />
    </main>
  );
}
