// @ts-check
const { test, expect } = require("@playwright/test");
const fs = require("fs");
const path = require("path");
const { ADMIN_PASSWORD, adminToken, apiCall, createTenant, createFaq, uiLogin, sidebar } = require("./helpers");

// The widget loads SockJS and STOMP from jsDelivr. Serve the same pinned versions from
// node_modules so the test does not depend on the CDN being reachable.
const LIBS = {
  "sockjs.min.js": path.join(__dirname, "../node_modules/sockjs-client/dist/sockjs.min.js"),
  "stomp.min.js": path.join(__dirname, "../node_modules/stompjs/lib/stomp.min.js"),
};
const WIDGET_ORIGIN = "http://localhost:3000";

/** Opens the demo page (served from http://localhost:3000) with the given widget key. */
async function openWidget(page, widgetKey) {
  await page.route("https://cdn.jsdelivr.net/**", (route) => {
    const file = Object.keys(LIBS).find((f) => route.request().url().endsWith(f));
    if (!file) return route.abort();
    return route.fulfill({ contentType: "application/javascript", body: fs.readFileSync(LIBS[file]) });
  });
  await page.goto(`${WIDGET_ORIGIN}/demo.html?widgetKey=${encodeURIComponent(widgetKey)}`);
  await page.locator("#cw-bubble").click();
}

/** Sends a message and returns the bot's complete reply (resending until the SockJS connection is up). */
async function ask(page, text) {
  const replies = page.locator(".cw-bot");
  const before = await replies.count();
  const input = page.locator("#cw-input");
  await expect(async () => {
    await input.fill(text);
    await input.press("Enter");
    await expect(replies).toHaveCount(before + 1, { timeout: 3000 });
  }).toPass({ timeout: 20_000 });
  await expect(replies.nth(before)).not.toHaveClass(/cw-streaming/);
  return replies.nth(before).textContent();
}

test("embedded widget answers from the tenant's FAQs over SockJS", async ({ page, request }) => {
  const token = await adminToken(request);
  const tenant = await createTenant(request, token);
  await createFaq(request, token, tenant.id, "What is your return policy?", "30 days, no questions asked.");
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));

  await openWidget(page, tenant.widgetKey);
  await expect(page.locator("#cw-container")).toHaveCSS("position", "fixed"); // stylesheet next to the script
  await expect(page.locator("#cw-header")).toHaveText("Support");
  expect(await ask(page, "can I return an item?")).toBe("30 days, no questions asked.");
  expect(errors).toEqual([]);
});

test("an unknown widget key gets no answer from the AI", async ({ page }) => {
  await openWidget(page, "not-a-real-widget-key");
  expect(await ask(page, "hello?")).toBe("This chat is not configured correctly.");
});

test("allowed websites restrict where a tenant's widget works", async ({ page, request }) => {
  const token = await adminToken(request);
  const tenant = await createTenant(request, token);
  await createFaq(request, token, tenant.id, "Do you ship abroad?", "Yes, worldwide.");

  await apiCall(request, token, "PUT", `/tenants/${tenant.id}/settings`, { allowedOrigins: ["https://shop.example.com"] });
  await openWidget(page, tenant.widgetKey);
  expect(await ask(page, "do you ship abroad?")).toBe("This chat is not enabled on this website.");

  await apiCall(request, token, "PUT", `/tenants/${tenant.id}/settings`,
    { allowedOrigins: ["https://shop.example.com", WIDGET_ORIGIN] });
  expect(await ask(page, "do you ship abroad?")).toBe("Yes, worldwide.");
});

test("Tenants page shows the embed code, saves allowed websites and rotates the key", async ({ page, browser, request }) => {
  const token = await adminToken(request);
  const tenant = await createTenant(request, token);
  await createFaq(request, token, tenant.id, "What are your opening hours?", "9 to 5.");
  page.on("dialog", (d) => d.accept());

  await uiLogin(page, "admin", ADMIN_PASSWORD);
  await sidebar(page).getByRole("link", { name: "Tenants" }).click();
  const card = page.locator(`[data-tenant-id="${tenant.id}"]`);
  await expect(card.getByTestId("widget-key")).toHaveText(tenant.widgetKey);
  await expect(card.locator("pre")).toContainText(`widgetKey: "${tenant.widgetKey}"`);

  await card.getByLabel("Allowed websites").fill("HTTPS://Shop.Example.com/\nnot a website");
  await card.getByRole("button", { name: "Save websites" }).click();
  await expect(card.getByRole("alert")).toContainText("Invalid website 'not a website'");
  await card.getByLabel("Allowed websites").fill(`HTTPS://Shop.Example.com/\n${WIDGET_ORIGIN}`);
  await card.getByRole("button", { name: "Save websites" }).click();
  await expect(card.getByRole("status")).toHaveText("Allowed websites saved.");
  await expect(card.getByLabel("Allowed websites")).toHaveValue(`https://shop.example.com\n${WIDGET_ORIGIN}`);

  // A widget embedded with the current key works...
  const widget = await (await browser.newContext()).newPage();
  await openWidget(widget, tenant.widgetKey);
  expect(await ask(widget, "opening hours?")).toBe("9 to 5.");

  // ...and stops working once the key is rotated.
  await card.getByRole("button", { name: "Rotate key" }).click();
  await expect(card.getByRole("status")).toContainText("New widget key created");
  await expect(card.getByTestId("widget-key")).not.toHaveText(tenant.widgetKey);
  expect(await ask(widget, "opening hours?")).toBe("This chat is not configured correctly.");
});
