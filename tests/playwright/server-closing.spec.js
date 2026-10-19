// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage } = require('./setup/dashboard');

test.describe('Server Closing — No Reconnect on New Test', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('serverClosing event shows CLOSED status', async ({ page }) => {
    // Verify connected first
    await expect(page.locator('#liveLabel')).toContainText(/LIVE|CONNECTED/);

    // Send serverClosing event
    await sendToPage(page, 'event', { type: 'event', event: 'serverClosing', data: { reason: 'newTestStarting' } });

    // Should show CLOSED
    await expect(page.locator('#liveLabel')).toHaveText('CLOSED');
    await expect(page.locator('#liveIndicator')).toHaveClass(/disconnected/);
  });

  test('browser does not reconnect after serverClosing', async ({ page }) => {
    // The page's own clock, so the 3 s reconnect timer can be run at once.
    await page.clock.install();
    await sendToPage(page, 'event', { type: 'event', event: 'serverClosing', data: { reason: 'newTestStarting' } });
    // The old server then stops and the connection drops. A page that kept its
    // connection open would see an error here and fall back to DISCONNECTED.
    await page.evaluate(() => fetch('/test/end-clients', { method: 'POST' }));
    await page.waitForFunction(() => eventSource.readyState === EventSource.CLOSED);

    // Past the 3 s reconnect: a page that reconnects would attach to the next run.
    await page.clock.runFor(4000);

    expect(await page.evaluate(() => eventSource.readyState), 'no new connection').toBe(2);
    await expect(page.locator('#liveLabel')).toHaveText('CLOSED');
  });

  test('data from first test is preserved after serverClosing', async ({ page }) => {
    // Verify we have data from catchup
    await expect(page.locator('#valSamples')).not.toHaveText('0');
    const samplesBeforeClose = await page.locator('#valSamples').textContent();

    // Send serverClosing event
    await sendToPage(page, 'event', { type: 'event', event: 'serverClosing', data: { reason: 'newTestStarting' } });

    // Data should still be there (not cleared)
    const samplesAfterClose = await page.locator('#valSamples').textContent();
    expect(samplesAfterClose).toBe(samplesBeforeClose);
  });
});
