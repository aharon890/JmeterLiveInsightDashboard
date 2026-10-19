// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');

test.describe('Filter Controls', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('3 color palettes available', async ({ page }) => {
    const btns = page.locator('#paletteToggle .filter-toggle-btn');
    await expect(btns).toHaveCount(3);
  });

  test('palette toggle switches active', async ({ page }) => {
    const okabe = page.locator('#paletteToggle .filter-toggle-btn[data-palette="okabe-ito"]');
    await okabe.click();
    await expect(okabe).toHaveClass(/active/);
  });

  test('line thickness toggle works', async ({ page }) => {
    const thin = page.locator('#lineToggle .filter-toggle-btn[data-thickness="1"]');
    await thin.click();
    await expect(thin).toHaveClass(/active/);
  });

  test('metric toggle switches', async ({ page }) => {
    const p99 = page.locator('#metricToggle .filter-toggle-btn[data-metric="p99"]');
    await p99.click();
    await expect(p99).toHaveClass(/active/);
  });

  test('window size toggle works', async ({ page }) => {
    const full = page.locator('#windowToggle .filter-toggle-btn[data-window="0"]');
    await full.click();
    await expect(full).toHaveClass(/active/);
  });

  test('sampler filter pills rendered @smoke', async ({ page }) => {
    const pills = page.locator('.filter-sampler-list .sampler-pill');
    const count = await pills.count();
    expect(count).toBeGreaterThanOrEqual(3); // 3 samplers + sigma total
  });

  test('Select All checks all samplers', async ({ page }) => {
    await page.click('button:has-text("None")');
    await page.click('button:has-text("All")');
    const checked = page.locator('.filter-sampler-list .checked');
    await expect.poll(() => checked.count()).toBeGreaterThanOrEqual(3);
  });

  test('None unchecks all samplers', async ({ page }) => {
    await page.click('button:has-text("None")');
    const unchecked = page.locator('.filter-sampler-list [data-sampler-name]:not(.checked)');
    await expect.poll(() => unchecked.count()).toBeGreaterThanOrEqual(3);
  });

  test('time display toggle exists', async ({ page }) => {
    await expect(page.locator('#timeDisplayBtn')).toBeVisible();
  });

  test('preset controls visible', async ({ page }) => {
    await expect(page.locator('#presetSelect')).toBeVisible();
  });
});
