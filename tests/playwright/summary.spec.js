// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');

test.describe('Summary Tab', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('metric cards display values @smoke', async ({ page }) => {
    await expect(page.locator('#valSamples')).not.toHaveText('0');
    await expect(page.locator('#valErrorRate')).toContainText('%');
    await expect(page.locator('#valThroughput')).toContainText('/s');
  });

  test('error rate card has error styling when errors exist', async ({ page }) => {
    const card = page.locator('#cardErrorRate');
    await expect(card).toHaveClass(/card-error/);
  });

  test('duration card shows formatted time', async ({ page }) => {
    const text = await page.locator('#valDuration').textContent();
    expect(text).toMatch(/\d{2}:\d{2}:\d{2}/);
  });

  test('response time chart renders @smoke', async ({ page }) => {
    const chart = page.locator('#summaryRTChart');
    await expect(chart).toBeVisible();
    // ECharts renders canvas
    const canvas = chart.locator('canvas');
    await expect(canvas.first()).toBeVisible();
  });

  test('avg response time card shows ms or s', async ({ page }) => {
    const text = await page.locator('#valAvgRT').textContent();
    expect(text).toMatch(/\d+\s*(ms|s|min)/);
  });

  test('total errors card displays', async ({ page }) => {
    await expect(page.locator('#valTotalErrors')).not.toHaveText('0');
  });

  // The card is the exact overall P95, the Aggregate Report's TOTAL row; Copy Summary
  // called it "Avg P95", which it is not.
  test('Copy Summary labels P95 as the card does', async ({ page, context }) => {
    await context.grantPermissions(['clipboard-read', 'clipboard-write']);
    await page.click('button:has-text("Copy Summary")');
    // set once this click's copy has landed: the clipboard can still hold an earlier test's
    await expect(page.locator('button:has-text("Copied!")')).toBeVisible();
    const text = await page.evaluate(() => navigator.clipboard.readText());
    expect(text).toMatch(/^P95: /m);
    expect(text).not.toContain('Avg P95');
  });
});
