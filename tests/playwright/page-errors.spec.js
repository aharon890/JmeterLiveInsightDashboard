// @ts-check
const { test, expect } = require('./fixtures');
const net = require('net');

/**
 * The page-error check in fixtures.js. A test fails when the dashboard throws, or reports a chart
 * redraw that failed, even if every assertion in it passed: before the check, a broken Throughput
 * chart passed every test that only looked for its element. Only those two count. The browser's
 * own "Failed to load resource" lines are what a server-gone test expects, so they never fail one.
 *
 * test.fail(): the first three must fail, and they fail only through the check.
 */
test.describe('Page errors', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    await page.waitForFunction(() => window.S && S.testRunning && Object.keys(S.samplerStats).length > 0);
  });

  test.fail('an uncaught exception in the page fails the test', async ({ page }) => {
    await Promise.all([
      page.waitForEvent('pageerror'),
      page.evaluate(() => { setTimeout(() => { throw new Error('thrown by the page'); }); }),
    ]);
  });

  test.fail('a chart redraw that fails fails the test, though the page carries on', async ({ page }) => {
    await Promise.all([
      page.waitForEvent('console', (m) => m.type() === 'error'),
      page.evaluate(() => {
        // @ts-ignore -- the page's own global: scheduleChartRefresh calls it by name
        window.refreshAfterBucket = () => { throw new Error('a chart failed to draw'); };
        scheduleChartRefresh();
      }),
    ]);
    await expect(page.locator('#liveLabel')).toBeVisible();
  });

  test.fail('a redraw that fails after the test has ended still fails it', async ({ page }) => {
    // None redraws the charts 150 ms after the click, by when this test is over
    await page.evaluate(() => {
      // @ts-ignore -- the page's own global: debouncedRefresh calls it by name
      window.refreshChartsForce = () => { throw new Error('the charts failed to redraw'); };
    });
    await page.click('button:has-text("None")');
  });

  test("the browser's own failed loads do not fail the test", async ({ page }) => {
    // a port just freed: refused, as when the dashboard's server is gone
    const port = await new Promise((done) => {
      const s = net.createServer().listen(0, '127.0.0.1', () => { const p = s.address().port; s.close(() => done(p)); });
    });
    const logged = page.waitForEvent('console', (m) => m.type() === 'error');
    await page.evaluate((p) => { new Image().src = `http://127.0.0.1:${p}/gone.png`; }, port);
    expect((await logged).text()).toBe('Failed to load resource: net::ERR_CONNECTION_REFUSED');
  });
});
