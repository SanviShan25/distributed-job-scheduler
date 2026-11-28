import { PlayCircle } from "lucide-react";
import { useId } from "react";
import { useNavigate } from "react-router";
import { useAppConfig, useStartDemo } from "../auth/demo";
import { Button, type ButtonProps } from "../components/ui";
import { cn } from "../lib/cn";

/**
 * "Try the demo": starts a private demo account and opens the dashboard. Renders nothing when the site
 * has no demo (or its settings could not be loaded). Errors (e.g. "The demo is busy") appear under it.
 */
export function TryDemoButton({
  className,
  variant = "primary",
  size,
  fullWidth,
  label = "Try the demo",
}: Pick<ButtonProps, "variant" | "size" | "fullWidth" | "className"> & { label?: string }) {
  const { data: config } = useAppConfig();
  const start = useStartDemo();
  const navigate = useNavigate();
  const errorId = useId();
  if (!config?.demoEnabled) return null;

  return (
    <div className={cn("flex flex-col gap-2", fullWidth && "w-full", className)}>
      <Button
        variant={variant}
        size={size}
        fullWidth={fullWidth}
        icon={<PlayCircle aria-hidden="true" className="size-5" />}
        loading={start.isPending}
        loadingText="Starting your demo…"
        aria-describedby={start.error ? errorId : undefined}
        onClick={() => {
          start.mutate(undefined, {
            onSuccess: () => {
              void navigate("/dashboard", { replace: true });
            },
          });
        }}
      >
        {label}
      </Button>
      {start.error && (
        <p id={errorId} role="alert" className="text-sm font-medium text-down-700">
          {start.error.message}
        </p>
      )}
    </div>
  );
}
