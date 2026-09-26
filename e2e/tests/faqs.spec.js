// @ts-check
const { test, expect } = require("@playwright/test");
const fs = require("fs");
const { ADMIN_PASSWORD, adminToken, apiCall, createTenant, uiLogin, sidebar } = require("./helpers");

test("FAQ list is paged and searchable, errors are shown, and export includes every FAQ", async ({ page, request }) => {
  const token = await adminToken(request);
  const tenant = await createTenant(request, token);
  const rows = Array.from({ length: 60 }, (_, i) => ({
    question: `Question number ${i + 1}?`,
    answer: i === 41 ? "The special answer." : `Answer ${i + 1}.`,
  }));
  await apiCall(request, token, "POST", `/faq/import/${tenant.id}`, rows);

  await uiLogin(page, "admin", ADMIN_PASSWORD);
  await sidebar(page).getByRole("link", { name: "FAQs" }).click();
  await page.locator("select").selectOption({ label: tenant.name });
  const pager = page.getByLabel("Pagination");
  await expect(pager).toContainText("Page 1 of 2 · 60 FAQs");
  await expect(page.getByText("Question number 60?")).toBeVisible();   // newest first
  await expect(page.getByText("Question number 10?", { exact: true })).toHaveCount(0);

  await pager.getByRole("button", { name: "Next" }).click();
  await expect(pager).toContainText("Page 2 of 2");
  await expect(page.getByText("Question number 10?", { exact: true })).toBeVisible();
  await expect(pager.getByRole("button", { name: "Next" })).toBeDisabled();

  await page.getByLabel("Search questions and answers").fill("SPECIAL");
  await expect(pager).toContainText("Page 1 of 1 · 1 FAQs");
  await expect(page.getByText("Question number 42?")).toBeVisible();
  await page.getByLabel("Search questions and answers").fill("no such text");
  await expect(page.getByText("No FAQs match your search.")).toBeVisible();

  // Backend validation errors are shown instead of failing silently.
  await page.getByPlaceholder("Question", { exact: true }).fill("Too long?");
  await page.getByPlaceholder("Answer", { exact: true }).fill("x".repeat(3001));
  await page.locator("form").getByRole("button", { name: "Add" }).click();
  await expect(page.getByRole("alert")).toHaveText("Answer is too long (at most 3000 characters)");

  // Export uses the full list, not the current page.
  await sidebar(page).getByRole("link", { name: "Import / Export" }).click();
  await page.locator("select").selectOption({ label: tenant.name });
  await expect(page.getByText("Currently 60 FAQs.")).toBeVisible();
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export CSV" }).click();
  const csv = fs.readFileSync(await (await download).path(), "utf8");
  expect(csv.trim().split("\n")).toHaveLength(61);   // header + 60 rows
  expect(csv).toContain("The special answer.");
});
