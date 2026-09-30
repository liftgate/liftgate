import { defineConfig } from "@playwright/test";

const origin = "http://localhost:3100";

export default defineConfig({
  testDir: "e2e",
  forbidOnly: !!process.env.CI,
  reporter: process.env.CI ? [["list"], ["github"]] : "list",
  use: { baseURL: origin, trace: "retain-on-failure" },
  projects: [
    { name: "1440", use: { viewport: { width: 1440, height: 900 } } },
    { name: "768", use: { viewport: { width: 768, height: 1024 } }, testMatch: "routes.spec.ts" },
    { name: "375", use: { viewport: { width: 375, height: 812 } }, testMatch: "routes.spec.ts" },
  ],
  webServer: {
    command: "npm run start -- --port 3100",
    url: `${origin}/login`,
    reuseExistingServer: !process.env.CI,
    env: { LIFTGATE_LANDING: "true", LIFTGATE_DASHBOARD_URL: origin, LIFTGATE_API_URL: "http://localhost:3102" },
  },
});
