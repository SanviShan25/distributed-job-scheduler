import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import type { SyntheticEvent } from "react";
import { describe, expect, it, vi } from "vitest";
import { Button } from "./Button";

describe("Button", () => {
  it("is a plain button by default, so it never submits a form by accident", () => {
    render(<Button>Save</Button>);
    expect(screen.getByRole("button", { name: "Save" })).toHaveAttribute("type", "button");
  });

  it("calls onClick", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Save</Button>);
    await userEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onClick).toHaveBeenCalledOnce();
  });

  it("while loading: shows the loading text, stays focusable and ignores clicks", async () => {
    const onClick = vi.fn();
    render(
      <Button loading loadingText="Saving…" onClick={onClick}>
        Save
      </Button>,
    );
    const button = screen.getByRole("button", { name: "Saving…" });
    expect(button).toHaveAttribute("aria-busy", "true");
    expect(button).toHaveAttribute("aria-disabled", "true");
    expect(button).toBeEnabled();

    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("while loading, does not submit its form (click or Enter in a field)", async () => {
    const onSubmit = vi.fn((event: SyntheticEvent) => {
      event.preventDefault();
    });
    render(
      <form onSubmit={onSubmit}>
        <label>
          Email <input name="email" />
        </label>
        <Button type="submit" loading>
          Log in
        </Button>
      </form>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    await userEvent.type(screen.getByLabelText("Email"), "a@b.c{Enter}");
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("submits its form when not loading", async () => {
    const onSubmit = vi.fn((event: SyntheticEvent) => {
      event.preventDefault();
    });
    render(
      <form onSubmit={onSubmit}>
        <Button type="submit">Log in</Button>
      </form>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Log in" }));
    expect(onSubmit).toHaveBeenCalledOnce();
  });
});
