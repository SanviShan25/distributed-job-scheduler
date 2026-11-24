import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Alert } from "./Alert";

describe("Alert", () => {
  it("announces errors and warnings immediately", () => {
    render(
      <>
        <Alert variant="error">Something went wrong.</Alert>
        <Alert variant="warning">Please verify your email.</Alert>
      </>,
    );
    const alerts = screen.getAllByRole("alert");
    expect(alerts).toHaveLength(2);
    expect(alerts[0]).toHaveTextContent("Something went wrong.");
  });

  it("announces info and success politely", () => {
    render(
      <>
        <Alert>Check your inbox.</Alert>
        <Alert variant="success" title="Password changed" />
      </>,
    );
    expect(screen.getAllByRole("status")).toHaveLength(2);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("shows the title, text and action", () => {
    render(
      <Alert
        variant="warning"
        title="Please verify your email"
        action={<button>Resend link</button>}
      >
        You need to verify your email first.
      </Alert>,
    );
    const alert = screen.getByRole("alert");
    expect(alert).toHaveTextContent("Please verify your email");
    expect(alert).toHaveTextContent("You need to verify your email first.");
    expect(screen.getByRole("button", { name: "Resend link" })).toBeInTheDocument();
  });
});
