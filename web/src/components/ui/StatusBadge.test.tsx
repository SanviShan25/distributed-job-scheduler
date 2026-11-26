import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { StatusBadge, type MonitorStatus } from "./StatusBadge";

const cases: [MonitorStatus, string][] = [
  ["UP", "Up"],
  ["DOWN", "Down"],
  ["UNKNOWN", "Checking"],
  ["PAUSED", "Paused"],
];

describe("StatusBadge", () => {
  it.each(cases)("shows %s as the word %s with a hidden icon", (status, word) => {
    const { container } = render(<StatusBadge status={status} />);
    expect(screen.getByText(word)).toBeVisible();
    const icon = container.querySelector("svg");
    expect(icon).toHaveAttribute("aria-hidden", "true");
  });

  it("uses a different icon shape for each status, not just a different colour", () => {
    const shapes = cases.map(([status]) => {
      const { container, unmount } = render(<StatusBadge status={status} />);
      const svg = container.querySelector("svg")?.innerHTML ?? "";
      unmount();
      return svg;
    });
    expect(new Set(shapes).size).toBe(cases.length);
  });
});
