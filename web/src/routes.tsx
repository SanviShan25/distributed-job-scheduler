import type { RouteObject } from "react-router";
import { HomePage } from "./pages/HomePage";
import { NotFoundPage } from "./pages/NotFoundPage";

// The style guide is a development tool only. import.meta.env.DEV is replaced with false in
// production builds, so the page and its import are left out of the bundle entirely.
const devRoutes: RouteObject[] = import.meta.env.DEV
  ? [
      {
        path: "/styleguide",
        lazy: async () => {
          const { StyleguidePage } = await import("./pages/StyleguidePage");
          return { Component: StyleguidePage };
        },
      },
    ]
  : [];

export const routes: RouteObject[] = [
  { path: "/", element: <HomePage /> },
  ...devRoutes,
  { path: "*", element: <NotFoundPage /> },
];
