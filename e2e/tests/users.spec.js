// @ts-check
const { test, expect } = require("@playwright/test");
const { adminToken, createUser, uiLogin, sidebar, unique } = require("./helpers");

// Acts as a dedicated super admin so the bootstrap admin's password never changes.
test("super admin manages users; resets and deletes revoke sessions immediately", async ({ page, browser, request }) => {
  const actor = await createUser(request, await adminToken(request), { role: "SUPER_ADMIN" });
  page.on("dialog", (d) => d.accept());
  await uiLogin(page, actor.username, actor.password);
  await sidebar(page).getByRole("link", { name: "Users" }).click();
  await expect(page.locator("tr", { hasText: "(you)" }).getByRole("button")).toHaveCount(0);

  // Create with validation
  const alice = unique("alice").toLowerCase();
  await page.getByPlaceholder("Username").fill(alice);
  await page.getByPlaceholder("Initial password").fill("short");
  await page.getByRole("button", { name: "Add user" }).click();
  await expect(page.getByRole("alert")).toHaveText("Password must be at least 12 characters");
  await page.getByPlaceholder("Initial password").fill("alice-initial-pw");
  await page.getByRole("button", { name: "Add user" }).click();
  const aliceRow = page.locator("tr", { hasText: alice });
  await expect(aliceRow).toBeVisible();
  await page.getByPlaceholder("Username").fill(alice);
  await page.getByPlaceholder("Initial password").fill("alice-initial-pw");
  await page.getByRole("button", { name: "Add user" }).click();
  await expect(page.getByRole("alert")).toHaveText("Username already exists");

  // Alice signs in elsewhere; resetting her password ends that session
  const alicePage = await (await browser.newContext()).newPage();
  await uiLogin(alicePage, alice, "alice-initial-pw");
  await aliceRow.getByRole("button", { name: "Reset password" }).click();
  await page.locator(".fixed input[type=password]").fill("alice-second-pw");
  await page.locator(".fixed").getByRole("button", { name: "Save" }).click();
  await expect(page.getByRole("status")).toContainText("signed out");
  await sidebar(alicePage).getByRole("link", { name: "Tenants" }).click();
  await expect(alicePage).toHaveURL(/\/login$/);
  await uiLogin(alicePage, alice, "alice-second-pw");

  // Deleting her ends the new session too
  await aliceRow.getByRole("button", { name: "Delete" }).click();
  await expect(page.locator("tr", { hasText: alice })).toHaveCount(0);
  await sidebar(alicePage).getByRole("link", { name: "FAQs" }).click();
  await expect(alicePage).toHaveURL(/\/login$/);
});

test("changing your own password keeps this session and signs out the others", async ({ page, browser, request }) => {
  const me = await createUser(request, await adminToken(request), { role: "TENANT_ADMIN" });
  const otherSession = await (await browser.newContext()).newPage();
  await uiLogin(otherSession, me.username, me.password);
  await uiLogin(page, me.username, me.password);

  await sidebar(page).getByRole("link", { name: "My account" }).click();
  const fill = async (current, next, confirm) => {
    await page.getByPlaceholder("Current password").fill(current);
    await page.getByPlaceholder("New password", { exact: true }).fill(next);
    await page.getByPlaceholder("Confirm new password").fill(confirm);
    await page.getByRole("button", { name: "Change password" }).click();
  };
  await fill("not-my-password", "brand-new-password", "brand-new-password");
  await expect(page.getByRole("alert")).toHaveText("Current password is incorrect");
  await fill(me.password, "brand-new-password", "brand-new-passwordX");
  await expect(page.getByRole("alert")).toHaveText("New passwords do not match.");
  await fill(me.password, "brand-new-password", "brand-new-password");
  await expect(page.getByRole("status")).toContainText("password was changed");

  await sidebar(page).getByRole("link", { name: "Tenants" }).click();
  await expect(page).toHaveURL(/\/tenants$/);
  await sidebar(otherSession).getByRole("link", { name: "Tenants" }).click();
  await expect(otherSession).toHaveURL(/\/login$/);
});
