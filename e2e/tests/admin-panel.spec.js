// @ts-check
const { test, expect } = require("@playwright/test");
const { ADMIN_PASSWORD, unique, uiLogin, sidebar } = require("./helpers");

test.describe("login and sessions", () => {
  test("unauthenticated visitors are sent to the login page", async ({ page }) => {
    await page.goto("/dashboard");
    await expect(page).toHaveURL(/\/login$/);
  });

  test("wrong password is rejected, correct one issues a JWT", async ({ page }) => {
    await page.goto("/login");
    await page.getByPlaceholder("Username").fill("admin");
    await page.getByPlaceholder("Enter admin password").fill("definitely-wrong");
    await page.getByRole("button", { name: "Login" }).click();
    await expect(page.getByText("Invalid username or password")).toBeVisible();

    await uiLogin(page, "admin", ADMIN_PASSWORD);
    const token = await page.evaluate(() => localStorage.getItem("admin_token"));
    expect(token?.split(".")).toHaveLength(3);
    await expect(sidebar(page).getByText("Super admin")).toBeVisible();
  });

  test("reload keeps the session; expired and rejected tokens return to login", async ({ page }) => {
    await uiLogin(page, "admin", ADMIN_PASSWORD);
    await page.goto("/tenants");
    await expect(page).toHaveURL(/\/tenants$/);

    await page.evaluate(() => localStorage.setItem("admin_token_expires", "2000-01-01T00:00:00Z"));
    await page.reload();
    await expect(page).toHaveURL(/\/login$/);

    await page.evaluate(() => {
      localStorage.setItem("admin_token", "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhZG1pbiJ9.bogus");
      localStorage.setItem("admin_token_expires", "2999-01-01T00:00:00Z");
    });
    await page.goto("/tenants");
    await expect(page).toHaveURL(/\/login$/);
  });
});

test("super admin creates a tenant and FAQ, and the chat answers from it", async ({ page, browser }) => {
  const tenant = unique("Globex");
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await uiLogin(page, "admin", ADMIN_PASSWORD);

  await sidebar(page).getByRole("link", { name: "Tenants" }).click();
  await page.getByPlaceholder("New tenant name").fill(tenant);
  await page.getByRole("button", { name: "Add" }).click();
  await expect(page.getByText(tenant)).toBeVisible();

  await sidebar(page).getByRole("link", { name: "FAQs" }).click();
  await page.locator("select").selectOption({ label: tenant });
  await page.getByPlaceholder("Question").fill("Where is your office located?");
  await page.getByPlaceholder("Answer").fill("Helsinki, Finland.");
  await page.locator("form").getByRole("button", { name: "Add" }).click();
  await expect(page.getByText("Where is your office located?")).toBeVisible();
  await page.getByRole("button", { name: "Retrain AI" }).click();
  await expect(page.getByText(/Trained model for tenant/)).toBeVisible();

  // A second chat session for the same tenant must not receive the first session's replies.
  const other = await page.context().newPage();
  await other.goto("/chat");
  await other.locator("select").selectOption({ label: tenant });

  await sidebar(page).getByRole("link", { name: "Chat (WebSocket)" }).click();
  await page.locator("select").selectOption({ label: tenant });
  const input = page.getByPlaceholder("Type a message...");
  // The STOMP connection opens asynchronously; resend until a reply arrives.
  await expect(async () => {
    await input.fill("where is the office?");
    await input.press("Enter");
    await expect(page.locator(".bg-gray-200", { hasText: "Helsinki" }).first()).toBeVisible({ timeout: 3000 });
  }).toPass({ timeout: 20_000 });

  await other.waitForTimeout(1000);
  await expect(other.locator(".bg-gray-200")).toHaveCount(0);
  expect(errors).toEqual([]);
});
