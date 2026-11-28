import { screen, waitFor } from "@testing-library/react";
import type { RouteObject } from "react-router";
import { describe, expect, it } from "vitest";
import { routes } from "./routes";
import { fakeBackend, json } from "./test/fakeBackend";
import { aMonitor } from "./test/monitors";
import { loggedIn, loggedOut, renderApp } from "./test/renderApp";

const MONITOR_ID = "3f6c2a10-5b7e-4c1d-9e2f-8a9b0c1d2e3f";

/** Every route, the address to open it at, who is looking, and the tab title it must have. */
const PAGES: { route: string; path: string; as: "visitor" | "user"; title: string }[] = [
  { route: "/", path: "/", as: "visitor", title: "Website uptime monitoring with email alerts" },
  { route: "/login", path: "/login", as: "visitor", title: "Log in" },
  { route: "/signup", path: "/signup", as: "visitor", title: "Create an account" },
  { route: "/check-inbox", path: "/check-inbox", as: "visitor", title: "Check your inbox" },
  {
    route: "/verify-email",
    path: "/verify-email?token=t",
    as: "visitor",
    title: "Verify your email",
  },
  {
    route: "/forgot-password",
    path: "/forgot-password",
    as: "visitor",
    title: "Reset your password",
  },
  {
    route: "/reset-password",
    path: "/reset-password?token=t",
    as: "visitor",
    title: "Choose a new password",
  },
  { route: "/dashboard", path: "/dashboard", as: "user", title: "Your websites" },
  { route: "/monitors/new", path: "/monitors/new", as: "user", title: "Add a website" },
  { route: "/monitors/:monitorId", path: `/monitors/${MONITOR_ID}`, as: "user", title: "Shop" },
  { route: "/settings/account", path: "/settings/account", as: "user", title: "Account settings" },
  { route: "*", path: "/no-such-page", as: "visitor", title: "Page not found" },
];

/** All paths in the route table ("/a/b"), so a new page can't be added without a title check here. */
function routePaths(list: RouteObject[], parent = ""): string[] {
  return list.flatMap((route) => {
    const here = route.index
      ? parent || "/"
      : route.path === undefined
        ? parent
        : route.path === "*"
          ? "*"
          : `${parent}/${route.path}`;
    const own = route.index || route.path !== undefined ? [here] : [];
    return [...own, ...routePaths(route.children ?? [], here === "/" ? "" : here)];
  });
}

describe("page titles", () => {
  it("every route in the app is checked below (the development-only style guide aside)", () => {
    const checked = PAGES.map((page) => page.route).sort();
    const all = routePaths(routes)
      .filter((path) => path !== "/styleguide")
      .sort();
    expect(checked).toEqual(all);
  });

  it.each(PAGES)("$path has the title “$title · PulseCheck”", async ({ path, as, title }) => {
    fakeBackend({
      "GET /api/auth/me": as === "user" ? loggedIn() : loggedOut,
      "GET /api/monitors": () => json(200, []),
      [`GET /api/monitors/${MONITOR_ID}`]: () =>
        json(200, aMonitor({ id: MONITOR_ID, name: "Shop" })),
      "POST /api/auth/verify": () => json(200, { message: "Your email address is verified." }),
    });
    renderApp(path);

    await waitFor(() => {
      expect(document.title).toBe(`${title} · PulseCheck`);
    });
    expect(await screen.findAllByRole("heading", { level: 1 })).toHaveLength(1);
  });

  it("asks search engines not to index “Page not found”, and only that page", async () => {
    fakeBackend({ "GET /api/auth/me": loggedOut });
    const app = renderApp("/no-such-page");

    expect(await screen.findByRole("heading", { level: 1, name: "Page not found" })).toBeVisible();
    expect(screen.getByText("/no-such-page")).toBeVisible();
    expect(screen.getByRole("link", { name: "Go to the home page" })).toHaveAttribute("href", "/");
    expect(document.querySelector('meta[name="robots"]')).toHaveAttribute("content", "noindex");

    await app.router.navigate("/login");
    await screen.findByRole("heading", { level: 1, name: "Log in" });
    expect(document.querySelector('meta[name="robots"]')).toBeNull();
  });

  it("offers a logged-in user their websites from “Page not found”", async () => {
    fakeBackend({ "GET /api/auth/me": loggedIn() });
    renderApp("/monitors/new/oops");
    expect(await screen.findByRole("link", { name: "Go to your websites" })).toHaveAttribute(
      "href",
      "/dashboard",
    );
  });
});
