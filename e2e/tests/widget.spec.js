// @ts-check
const { test, expect } = require("@playwright/test");
const fs = require("fs");
const path = require("path");
const { adminToken, createTenant, createFaq } = require("./helpers");

// The widget loads SockJS and STOMP from jsDelivr. Serve the same pinned versions from
// node_modules so the test does not depend on the CDN being reachable.
const LIBS = {
  "sockjs.min.js": path.join(__dirname, "../node_modules/sockjs-client/dist/sockjs.min.js"),
  "stomp.min.js": path.join(__dirname, "../node_modules/stompjs/lib/stomp.min.js"),
};

test("embedded widget answers from the tenant's FAQs over SockJS", async ({ page, request }) => {
  const token = await adminToken(request);
  const tenant = await createTenant(request, token);
  await createFaq(request, token, tenant.id, "What is your return policy?", "30 days, no questions asked.");

  await page.route("https://cdn.jsdelivr.net/**", (route) => {
    const file = Object.keys(LIBS).find((f) => route.request().url().endsWith(f));
    if (!file) return route.abort();
    return route.fulfill({ contentType: "application/javascript", body: fs.readFileSync(LIBS[file]) });
  });
  const errors = [];
  page.on("pageerror", (e) => errors.push(e.message));

  await page.goto(`http://localhost:3000/demo.html?tenantId=${tenant.id}`);
  const container = page.locator("#cw-container");
  await expect(container).toHaveCSS("position", "fixed"); // stylesheet resolved next to the script
  await expect(page.locator("#cw-header")).toHaveText("Support");

  await page.locator("#cw-bubble").click();
  const input = page.locator("#cw-input");
  await expect(async () => {
    await input.fill("can I return an item?");
    await input.press("Enter");
    await expect(page.locator(".cw-bot").first()).toHaveText("30 days, no questions asked.", { timeout: 3000 });
  }).toPass({ timeout: 20_000 });
  expect(errors).toEqual([]);
});
