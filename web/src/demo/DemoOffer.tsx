import { useAppConfig } from "../auth/demo";
import { TryDemoButton } from "./TryDemoButton";

/** "Just looking?" with the demo button, under the login and sign-up forms. Nothing without a demo. */
export function DemoOffer() {
  const { data: config } = useAppConfig();
  if (!config?.demoEnabled) return null;
  return (
    <div className="flex flex-col gap-3 border-t border-line pt-5">
      <p className="text-ink-muted">
        Just looking? Try PulseCheck in a private demo. No sign-up, nothing to install.
      </p>
      <TryDemoButton variant="secondary" fullWidth />
    </div>
  );
}
