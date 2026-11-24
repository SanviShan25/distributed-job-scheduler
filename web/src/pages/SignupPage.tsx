import { Link } from "react-router";
import { Card } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

// The sign-up form itself is added with the other account pages.
export function SignupPage() {
  usePageTitle("Create an account");
  return (
    <Card titleAs="h1" title="Create an account">
      <p className="text-ink-muted">
        Already have an account?{" "}
        <Link to="/login" className="font-semibold text-brand-700 underline underline-offset-4">
          Log in
        </Link>
      </p>
    </Card>
  );
}
