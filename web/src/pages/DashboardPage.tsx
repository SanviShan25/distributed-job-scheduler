import { Card } from "../components/ui";
import { usePageTitle } from "../lib/usePageTitle";

export function DashboardPage() {
  usePageTitle("Your websites");
  return (
    <div className="flex flex-col gap-6">
      <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">Your websites</h1>
      <Card>
        <p className="text-ink-muted">Your monitors will appear here.</p>
      </Card>
    </div>
  );
}
