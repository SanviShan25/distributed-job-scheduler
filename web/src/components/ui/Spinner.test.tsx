import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { Spinner } from "./Spinner";

describe("Spinner", () => {
  it("tells screen readers what is loading", () => {
    render(<Spinner label="Loading your websites…" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading your websites…");
  });

  it("can be purely decorative", () => {
    render(<Spinner decorative />);
    expect(screen.queryByRole("status")).not.toBeInTheDocument();
  });
});
