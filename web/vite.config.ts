import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vitest/config";

// The backend (Spring Boot) runs on :8080. In development the browser talks only to Vite on :5173
// and Vite forwards /api to the backend, so the session and XSRF-TOKEN cookies are first-party and
// CSRF works exactly as it will in production, where Spring Boot serves the built app itself.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://localhost:8080",
        // Keep the browser's Host header (localhost:5173) so the backend sees the same origin the
        // browser does.
        changeOrigin: false,
      },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
  test: {
    environment: "jsdom",
    environmentOptions: { jsdom: { url: "http://localhost:5173/" } },
    setupFiles: ["./src/test/setup.ts"],
    // Several findBy* waits (up to 5 s each, see setup.ts) can happen in one test.
    testTimeout: 20_000,
    css: false,
    restoreMocks: true,
    unstubGlobals: true,
  },
});
