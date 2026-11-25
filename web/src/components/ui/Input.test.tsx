import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import { Input } from "./Input";

describe("Input", () => {
  it("is labelled by its label", async () => {
    render(<Input label="Email" type="email" />);
    const input = screen.getByLabelText("Email");
    await userEvent.type(input, "me@example.com");
    expect(input).toHaveValue("me@example.com");
    expect(input).not.toHaveAttribute("aria-invalid");
  });

  it("is described by its hint", () => {
    render(<Input label="Password" type="password" hint="Use 10 to 128 characters." />);
    expect(screen.getByLabelText("Password")).toHaveAccessibleDescription(
      "Use 10 to 128 characters.",
    );
  });

  it("marks the field invalid and describes it with the error", () => {
    render(<Input label="Password" hint="Use 10 to 128 characters." error="Too short." />);
    const input = screen.getByLabelText("Password");
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAccessibleDescription("Use 10 to 128 characters. Too short.");
    expect(screen.getByText("Too short.")).toBeVisible();
  });

  it("says when a field is optional", () => {
    render(<Input label="Website name" optional />);
    expect(screen.getByLabelText("Website name (optional)")).toBeInTheDocument();
  });

  it("uses a given id", () => {
    render(<Input label="Email" id="login-email" />);
    expect(screen.getByLabelText("Email")).toHaveAttribute("id", "login-email");
  });
});
