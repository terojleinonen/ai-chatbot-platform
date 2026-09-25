// @ts-check
const { test, expect } = require("@playwright/test");
const { adminToken, createUser } = require("./helpers");

const WINDOW_SECONDS = Number(process.env.E2E_RATE_LIMIT_WINDOW_SECONDS || 10);

test("repeated failed logins are locked out until the window passes", async ({ page, request }) => {
  const user = await createUser(request, await adminToken(request));
  await page.goto("/login");

  const attempt = async (password) => {
    await page.getByPlaceholder("Username").fill(user.username);
    await page.getByPlaceholder("Enter admin password").fill(password);
    const response = page.waitForResponse((r) => r.url().endsWith("/auth/login"));
    await page.getByRole("button", { name: "Login" }).click();
    return response;
  };

  for (let i = 0; i < 5; i++) expect((await attempt("wrong-password")).status()).toBe(401);

  // Even the correct password is refused while locked out.
  const blocked = await attempt(user.password);
  expect(blocked.status()).toBe(429);
  expect(Number(blocked.headers()["retry-after"])).toBeGreaterThan(0);
  await expect(page.getByText(/Too many failed attempts\. Try again in \d+ minute/)).toBeVisible();

  await page.waitForTimeout((WINDOW_SECONDS + 1) * 1000);
  expect((await attempt(user.password)).status()).toBe(200);
  await expect(page).toHaveURL(/\/dashboard$/);
});
