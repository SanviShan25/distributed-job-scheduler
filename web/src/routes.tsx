import type { RouteObject } from "react-router";
import { PublicOnly, RequireAuth } from "./auth/guards";
import { AppLayout } from "./components/layout/AppLayout";
import { AuthLayout } from "./components/layout/AuthLayout";
import { RootLayout } from "./components/layout/RootLayout";
import { AccountSettingsPage } from "./pages/AccountSettingsPage";
import { CheckInboxPage } from "./pages/CheckInboxPage";
import { DashboardPage } from "./pages/DashboardPage";
import { ForgotPasswordPage } from "./pages/ForgotPasswordPage";
import { HomePage } from "./pages/HomePage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { ResetPasswordPage } from "./pages/ResetPasswordPage";
import { RouteErrorPage } from "./pages/RouteErrorPage";
import { SignupPage } from "./pages/SignupPage";
import { VerifyEmailPage } from "./pages/VerifyEmailPage";

// The style guide is a development tool only. import.meta.env.DEV is replaced with false in
// production builds, so the page and its import are left out of the bundle entirely.
const devRoutes: RouteObject[] = import.meta.env.DEV
  ? [
      {
        path: "styleguide",
        lazy: async () => {
          const { StyleguidePage } = await import("./pages/StyleguidePage");
          return { Component: StyleguidePage };
        },
      },
    ]
  : [];

export const routes: RouteObject[] = [
  {
    element: <RootLayout />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        // Logged-in users skip these and go to the app.
        element: <PublicOnly />,
        children: [
          {
            element: <AuthLayout />,
            children: [
              { index: true, element: <HomePage /> },
              { path: "login", element: <LoginPage /> },
              { path: "signup", element: <SignupPage /> },
            ],
          },
        ],
      },
      {
        // Email-link and "check your inbox" pages work whether or not someone is logged in.
        element: <AuthLayout />,
        children: [
          { path: "check-inbox", element: <CheckInboxPage /> },
          { path: "verify-email", element: <VerifyEmailPage /> },
          { path: "forgot-password", element: <ForgotPasswordPage /> },
          { path: "reset-password", element: <ResetPasswordPage /> },
        ],
      },
      {
        // Logged-out users are sent to log in.
        element: <RequireAuth />,
        children: [
          {
            element: <AppLayout />,
            children: [
              { path: "dashboard", element: <DashboardPage /> },
              { path: "settings/account", element: <AccountSettingsPage /> },
            ],
          },
        ],
      },
      ...devRoutes,
      { path: "*", element: <NotFoundPage /> },
    ],
  },
];
