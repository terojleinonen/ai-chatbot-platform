// @ts-check
const { test, expect } = require("@playwright/test");
const { API, ADMIN_PASSWORD, adminToken, createTenant, createFaq, createUser, uiLogin, sidebar } = require("./helpers");

test("tenant admins only see and manage their assigned tenants", async ({ page, browser, request }) => {
  const token = await adminToken(request);
  const t1 = await createTenant(request, token);
  const t2 = await createTenant(request, token);
  await createFaq(request, token, t1.id, "What is the tenant one secret?", "Tenant one answer.");
  await createFaq(request, token, t2.id, "What is the tenant two secret?", "Tenant two answer.");
  const bob = await createUser(request, token, { role: "TENANT_ADMIN", tenantIds: [t1.id] });

  await uiLogin(page, bob.username, bob.password);
  await expect(sidebar(page).getByText("Tenant admin")).toBeVisible();
  await expect(sidebar(page).getByRole("link", { name: "Users" })).toHaveCount(0);
  await page.goto("/users");
  await expect(page).toHaveURL(/\/dashboard$/);

  await sidebar(page).getByRole("link", { name: "Tenants" }).click();
  await expect(page.getByText(t1.name)).toBeVisible();
  await expect(page.getByText(t2.name)).toHaveCount(0);
  await expect(page.getByPlaceholder("New tenant name")).toHaveCount(0);

  await sidebar(page).getByRole("link", { name: "FAQs" }).click();
  await expect(page.locator("select option", { hasText: t1.name })).toHaveCount(1);
  await expect(page.locator("select option", { hasText: t2.name })).toHaveCount(0);
  await page.locator("select").selectOption({ label: t1.name });
  await expect(page.getByText("What is the tenant one secret?")).toBeVisible();

  // Direct API access to the other tenant is refused
  const status = await page.evaluate(async ({ api, id }) => {
    const r = await fetch(`${api}/faq/list/${id}`, {
      headers: { Authorization: "Bearer " + localStorage.getItem("admin_token") },
    });
    return r.status;
  }, { api: API, id: t2.id });
  expect(status).toBe(403);
});

test("access changes by a super admin apply without signing in again", async ({ page, browser, request }) => {
  const token = await adminToken(request);
  const t1 = await createTenant(request, token);
  const t2 = await createTenant(request, token);
  const bob = await createUser(request, token, { role: "TENANT_ADMIN", tenantIds: [t1.id] });

  const admin = await (await browser.newContext()).newPage();
  await uiLogin(admin, "admin", ADMIN_PASSWORD);
  await sidebar(admin).getByRole("link", { name: "Users" }).click();
  const bobRow = admin.locator("tr", { hasText: bob.username });
  await expect(bobRow).toContainText(t1.name);

  await uiLogin(page, bob.username, bob.password);
  await sidebar(page).getByRole("link", { name: "Tenants" }).click();
  await expect(page.getByText(t1.name)).toBeVisible();

  // Move Bob from tenant 1 to tenant 2
  await bobRow.getByRole("button", { name: "Edit access" }).click();
  const dialog = admin.locator(".fixed");
  await dialog.locator("label", { hasText: t1.name }).getByRole("checkbox").uncheck();
  await dialog.locator("label", { hasText: t2.name }).getByRole("checkbox").check();
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(bobRow).toContainText(t2.name);
  await page.reload();
  await expect(page.getByText(t2.name)).toBeVisible();
  await expect(page.getByText(t1.name)).toHaveCount(0);

  // Promote to super admin: the Users link appears after a reload
  await bobRow.getByRole("button", { name: "Edit access" }).click();
  await dialog.getByRole("combobox", { name: "Role" }).selectOption("SUPER_ADMIN");
  await dialog.getByRole("button", { name: "Save" }).click();
  await expect(bobRow).toContainText("All tenants");
  await page.reload();
  await expect(sidebar(page).getByRole("link", { name: "Users" })).toBeVisible();
});
