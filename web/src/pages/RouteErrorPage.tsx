import { Alert, Button } from "../components/ui";

/** Shown if a page crashes, instead of a blank screen or a stack trace. */
export function RouteErrorPage() {
  return (
    <main className="mx-auto flex min-h-dvh max-w-md flex-col justify-center px-4 py-10">
      <Alert
        variant="error"
        title="Something went wrong"
        action={
          <Button
            variant="secondary"
            onClick={() => {
              window.location.reload();
            }}
          >
            Reload the page
          </Button>
        }
      >
        Sorry, this page stopped working. Reloading usually fixes it.
      </Alert>
    </main>
  );
}
