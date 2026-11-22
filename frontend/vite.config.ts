/// <reference types="vitest" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";

// Development: the team backend runs on :8080 and the data platform on :8081. The proxy keeps one origin.
// Production (Render static site): VITE_API_URL and VITE_PLATFORM_URL point at the two services.
// `npm run dev:mock` runs the whole UI on the built-in mock backend (no Java needed).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const api = env.DEV_API_URL || "http://localhost:8080";
  const platform = env.DEV_PLATFORM_URL || "http://localhost:8081";
  const toPlatform = ["/api/v1/ingest", "/api/v1/health", "/api/v1/admin/simulator", "/api/v1/admin/sensors", "/api/v1/sensors", "/api/v1/forecast"];
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        ...Object.fromEntries(toPlatform.map((p) => [p, { target: platform, changeOrigin: true }])),
        "/api": { target: api, changeOrigin: true },
        "/ws-platform": { target: platform.replace(/^http/, "ws"), ws: true, rewrite: (p: string) => p.replace(/^\/ws-platform/, "/ws") },
        "/ws": { target: api.replace(/^http/, "ws"), ws: true },
      },
    },
    build: { outDir: "dist", sourcemap: false },
    test: {
      environment: "jsdom",
      globals: true,
      // `npm run test:coverage`: totals in the terminal (and the CI log), HTML report in coverage/index.html.
      // Everything under src counts, the mock backend included; only tests, type declarations and the entry point don't.
      coverage: {
        provider: "v8",
        reporter: ["text-summary", "text", "html"],
        include: ["src/**/*.{ts,tsx,js}"],
        exclude: ["src/**/*.test.{ts,tsx}", "src/__tests__/**", "src/**/*.d.ts", "src/main.tsx"],
      },
    },
  };
});
