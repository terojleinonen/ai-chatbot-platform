// @ts-check
const { expect } = require("@playwright/test");

const API = "http://localhost:8080";
const ADMIN_PASSWORD = process.env.E2E_ADMIN_PASSWORD || "e2e-admin-password";

/** Short unique suffix so tests never collide with each other or with earlier runs. */
function unique(prefix) {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;
}

async function apiLogin(request, username, password) {
  const r = await request.post(`${API}/auth/login`, { data: { username, password } });
  expect(r.status(), `login as ${username}`).toBe(200);
  return (await r.json()).token;
}

async function apiCall(request, token, method, path, data) {
  const r = await request.fetch(`${API}${path}`, {
    method,
    data,
    headers: { Authorization: `Bearer ${token}` },
  });
  expect(r.ok(), `${method} ${path} → ${r.status()} ${await r.text()}`).toBeTruthy();
  return r.status() === 204 ? null : r.json();
}

/** Logs in as the bootstrap super admin through the API. */
const adminToken = (request) => apiLogin(request, "admin", ADMIN_PASSWORD);

async function createTenant(request, token, name = unique("Tenant")) {
  return apiCall(request, token, "POST", "/tenants/create", { name });
}

async function createFaq(request, token, tenantId, question, answer) {
  return apiCall(request, token, "POST", "/faq/create", { tenantId, question, answer });
}

async function createUser(request, token, { role = "TENANT_ADMIN", tenantIds = [], password = "e2e-user-password" } = {}) {
  const username = unique("user").toLowerCase();
  const user = await apiCall(request, token, "POST", "/users", { username, password, role, tenantIds });
  return { ...user, password };
}

/** Logs in through the admin panel's login page and waits for the dashboard. */
async function uiLogin(page, username, password) {
  await page.goto("/login");
  await page.getByPlaceholder("Username").fill(username);
  await page.getByPlaceholder("Enter admin password").fill(password);
  await page.getByRole("button", { name: "Login" }).click();
  await expect(page).toHaveURL(/\/dashboard$/);
}

const sidebar = (page) => page.locator("aside");

module.exports = {
  API, ADMIN_PASSWORD, unique, apiLogin, apiCall, adminToken, createTenant, createFaq, createUser, uiLogin, sidebar,
};
