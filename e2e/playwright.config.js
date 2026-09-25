// @ts-check
const { defineConfig } = require("@playwright/test");
const path = require("path");

const root = path.resolve(__dirname, "..");
const CI = !!process.env.CI;

// Settings shared with the backend process. The admin password only takes effect when the
// admin_users table is empty, i.e. on a fresh database (see README "Authentication").
process.env.E2E_ADMIN_PASSWORD ||= "e2e-admin-password";
process.env.E2E_RATE_LIMIT_WINDOW_SECONDS ||= "10";

module.exports = defineConfig({
  testDir: "./tests",
  // Tests share one backend (and its login rate limiter), so run them one at a time.
  workers: 1,
  fullyParallel: false,
  forbidOnly: CI,
  retries: 0,
  timeout: 60_000,
  reporter: CI ? [["list"], ["html", { open: "never" }]] : "list",
  use: {
    baseURL: "http://localhost:5173",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  // Starts the whole stack unless it is already running (locally you can keep your own processes up).
  // Postgres must be running first: `docker compose up -d` from the repository root.
  webServer: [
    {
      command: "java -jar target/ai-microservice-0.0.1-SNAPSHOT.jar",
      cwd: path.join(root, "ai-microservice"),
      url: "http://localhost:8081/health",
      reuseExistingServer: !CI,
      timeout: 120_000,
    },
    {
      command: "java -jar target/backend-0.0.1-SNAPSHOT.jar",
      cwd: path.join(root, "backend"),
      url: "http://localhost:8080/tenants/list", // 401 counts as "up"
      reuseExistingServer: !CI,
      timeout: 120_000,
      env: {
        ADMIN_PASSWORD: process.env.E2E_ADMIN_PASSWORD,
        JWT_SECRET: "e2e-jwt-secret-that-is-at-least-32-bytes",
        LOGIN_RATE_LIMIT_WINDOW: `${process.env.E2E_RATE_LIMIT_WINDOW_SECONDS}s`,
      },
    },
    {
      command: "npm run preview -- --port 5173 --strictPort",
      cwd: path.join(root, "frontend-admin"),
      url: "http://localhost:5173",
      reuseExistingServer: !CI,
      timeout: 60_000,
    },
    {
      command: "python3 -m http.server 3000",
      cwd: path.join(root, "widget"),
      url: "http://localhost:3000/demo.html",
      reuseExistingServer: !CI,
      timeout: 30_000,
    },
  ],
});
