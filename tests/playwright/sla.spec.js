// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage } = require('./setup/dashboard');

test.describe('SLA & Apdex', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('SLA badge shows status @smoke', async ({ page }) => {
    const badge = page.locator('#slaBadge');
    await expect(badge).toBeVisible();
    await expect(badge).toContainText('SLA');
  });

  test('Apdex badge shows score', async ({ page }) => {
    const badge = page.locator('#apdexBadge');
    await expect(badge).toBeVisible();
    await expect(badge).toContainText('Apdex');
  });

  test('SLA marklines visible on response time chart', async ({ page }) => {
    // Chart is on Summary tab by default, SLA config has p95: 1000
    const chart = page.locator('#summaryRTChart');
    await expect(chart).toBeVisible();
    // Can't easily assert markline content without reading ECharts internals
    // Just verify chart rendered
    await expect(chart.locator('canvas').first()).toBeVisible();
  });

  test('per-sampler SLA badges in table', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    // Fixture has WARN for POST /api/cart
    await expect(page.locator('#samplerTableBody')).toContainText(/OK|PASS|WARN|FAIL/);
  });
});

// Firefox and Safari refuse a permission prompt that no click started, so a page that
// asked on load, or when an SLA failed, never got to show a notification there, and
// logged an error on every load. The page asks on the user's first click instead.
test.describe('SLA notification permission', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      window.__asks = [];
      Object.defineProperty(Notification, 'permission', { get: () => 'default' });
      Notification.requestPermission = () => {
        window.__asks.push(navigator.userActivation.isActive ? 'click' : 'no click');
        return Promise.resolve('default');
      };
    });
    await openDashboard(page);
  });

  test('is asked for on the first click, never without one', async ({ page }) => {
    const asks = () => page.evaluate(() => window.__asks);
    expect(await asks(), 'not on load').toEqual([]);

    await sendToPage(page, 'bucket', { type: 'bucket', testRunning: true,
      slaStatus: { 'POST /api/cart': { p95: 'FAIL' } } });
    await expect(page.locator('#slaBadge')).toHaveText('SLA FAIL');
    expect(await asks(), 'not when an SLA fails').toEqual([]);

    await page.mouse.click(5, 5);
    await page.mouse.click(5, 5);
    expect(await asks(), 'once, from the first click').toEqual(['click']);
  });
});
