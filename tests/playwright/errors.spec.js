// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');

test.describe('Errors Tab', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
    await page.click('.tab-btn[data-tab="errors"]');
  });

  test('error breakdown pie chart container visible @smoke', async ({ page }) => {
    const chart = page.locator('#errorPieChart');
    await expect(chart).toBeVisible();
  });

  test('error breakdown bar chart container visible', async ({ page }) => {
    const chart = page.locator('#errorBarChart');
    await expect(chart).toBeVisible();
  });

  test('error table shows records @smoke', async ({ page }) => {
    const rows = page.locator('#errorTableBody tr');
    const count = await rows.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('error code badges visible', async ({ page }) => {
    const badges = page.locator('.error-code-badge');
    const count = await badges.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });

  test('error count displayed in tab label', async ({ page }) => {
    const tabCount = page.locator('#errorTabCount');
    await expect(tabCount).toContainText('(');
  });

  test('CSV export button on error table', async ({ page }) => {
    const buttons = page.locator('button:has-text("Export CSV")');
    const count = await buttons.count();
    expect(count).toBeGreaterThanOrEqual(1);
  });
});
