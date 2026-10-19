// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage, settled } = require('./setup/dashboard');
const { runCatchup, loadRun } = require('./setup/run-data');

test.describe('Controls & Live/Explore Mode', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('pause button toggles text @smoke', async ({ page }) => {
    const btn = page.locator('#btnPause');
    await expect(btn).toHaveText('Pause Updates');
    await btn.click();
    await expect(btn).toHaveText('Resume Updates');
    await btn.click();
    await expect(btn).toHaveText('Pause Updates');
  });

  test('stop button is enabled', async ({ page }) => {
    const btn = page.locator('#btnStop');
    await expect(btn).toBeEnabled();
  });

  // The stop buttons sit behind confirm() dialogs, and Playwright auto-dismisses
  // dialogs — a test that clicks one without its own dialog handler silently
  // exercises the cancel branch and asserts nothing. Each test below installs
  // the handler it means to test with.

  test('stop button posts /api/stop and stays disabled through the wind-down', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.evaluate(async () => { await fetch('/test/reset-stop-requests', { method: 'POST' }); });
    await page.click('#btnStop');
    // The mock serves the raw index.html, so the token header carries the
    // unsubstituted placeholder — asserting it pins that the header is sent
    // and comes from LD_TOKEN.
    await expect.poll(async () =>
      page.evaluate(async () => (await fetch('/test/stop-requests')).json())
    ).toEqual([{ path: '/api/stop', token: '__LD_STOP_TOKEN__' }]);
    await expect(page.locator('#btnStop')).toBeDisabled();
    await expect(page.locator('#testStatus')).toHaveText('Stopping');
    // A graceful stop winds down over several buckets that still carry
    // testRunning:true — they must NOT re-enable the button or erase the
    // "Stopping" status (they used to, via updateTestStatusUI).
    await sendToPage(page, 'bucket', { type: 'bucket', timestamp: Date.now(), testRunning: true,
          overall: { samples: 5, avgResponseTime: 200, p95: 400, throughput: 2.5, errorRate: 0 },
          cumulative: { totalSamples: 605, totalErrors: 8, elapsedTime: 37000, activeThreads: 5, maxThreads: 5,
            overallThroughput: 16.9, overallErrorRate: 1.32, receivedKBs: 17.5, sentKBs: 4.4,
            p50: 200, p90: 390, p95: 470, p99: 810, avgResponseTime: 240, minResponseTime: 42,
            maxResponseTime: 1250, apdex: 0.90, apdexThreshold: 500 } });
    await expect(page.locator('#btnStop')).toBeDisabled();
    await expect(page.locator('#testStatus')).toHaveText('Stopping');
  });

  test('cancelling the stop confirm sends no request', async ({ page }) => {
    page.on('dialog', d => d.dismiss());
    await page.evaluate(async () => { await fetch('/test/reset-stop-requests', { method: 'POST' }); });
    // Record the page's requests. stopTest sends in the click handler itself,
    // right after the confirm, so the record is complete once the click returns.
    await page.evaluate(() => {
      const fetch = window.fetch;
      window.__fetched = [];
      window.fetch = (url, ...rest) => { window.__fetched.push(String(url)); return fetch(url, ...rest); };
    });
    await page.click('#btnStop');
    expect(await page.evaluate(() => window.__fetched)).toEqual([]);
    const requests = await page.evaluate(async () => (await fetch('/test/stop-requests')).json());
    expect(requests).toEqual([]);
    await expect(page.locator('#btnStop')).toBeEnabled();
  });

  test('shutdown button posts /api/stop-now and disables itself', async ({ page }) => {
    page.on('dialog', d => d.accept());
    await page.evaluate(async () => { await fetch('/test/reset-stop-requests', { method: 'POST' }); });
    await page.click('#btnStopNow');
    await expect.poll(async () =>
      page.evaluate(async () => (await fetch('/test/stop-requests')).json())
    ).toEqual([{ path: '/api/stop-now', token: '__LD_STOP_TOKEN__' }]);
    await expect(page.locator('#btnStopNow')).toBeDisabled();
  });

  test('export snapshot button present @smoke', async ({ page }) => {
    await expect(page.locator('button:has-text("Export Snapshot")')).toBeVisible();
  });

  test('copy summary button present', async ({ page }) => {
    await expect(page.locator('button:has-text("Copy Summary")')).toBeVisible();
  });

  test('return to live button hidden by default', async ({ page }) => {
    const btn = page.locator('#returnLiveBtn');
    await expect(btn).not.toHaveClass(/visible/);
  });

  test('dark mode toggle preserves timeline charts', async ({ page }) => {
    await page.click('.tab-btn[data-tab="timeline"]');
    // Verify charts render before toggle
    for (const id of ['responseTimeChart', 'throughputChart', 'errorRateChart', 'bytesChart', 'connectLatencyChart']) {
      await expect(page.locator('#' + id).locator('canvas').first()).toBeVisible();
    }
    // Toggle dark mode
    await page.click('button[title="Toggle dark mode"]');
    // Verify charts still render after toggle
    for (const id of ['responseTimeChart', 'throughputChart', 'errorRateChart', 'bytesChart', 'connectLatencyChart']) {
      await expect(page.locator('#' + id).locator('canvas').first()).toBeVisible();
    }
  });

  test('dark mode toggle preserves timeline charts after test ends', async ({ page }) => {
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page); // the tab's own redraw is over, so it can't repaint what dark mode should
    // End the test
    await sendToPage(page, 'event', { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    // Toggle dark mode after test ended
    await page.click('button[title="Toggle dark mode"]');
    // Charts must still be visible
    for (const id of ['responseTimeChart', 'throughputChart', 'errorRateChart', 'bytesChart', 'connectLatencyChart']) {
      await expect(page.locator('#' + id).locator('canvas').first()).toBeVisible();
    }
  });

  test('dark mode toggle preserves sampler table', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    // Verify table rows before toggle
    const rows = page.locator('#samplerTableBody tr');
    await expect(rows).toHaveCount(4); // Total + 3 samplers
    // Toggle dark mode
    await page.click('button[title="Toggle dark mode"]');
    // Table rows must still be present
    await expect(rows).toHaveCount(4);
    await expect(page.locator('.total-sampler-row')).toBeVisible();
  });

  test('dark mode toggle preserves sampler table after test ends', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    // End the test
    await sendToPage(page, 'event', { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    // Toggle dark mode after test ended
    await page.click('button[title="Toggle dark mode"]');
    // Table must still render
    await expect(page.locator('#samplerTableBody tr')).toHaveCount(4);
    await expect(page.locator('.total-sampler-row')).toBeVisible();
  });

  test('dark mode toggle preserves expanded detail charts', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    // Expand first sampler row to show detail mini-charts
    const firstRow = page.locator('#samplerTableBody tr.expandable').first();
    await firstRow.click();
    const detailRow = page.locator('.detail-row');
    await expect(detailRow).toBeVisible();
    await expect(detailRow.locator('.detail-chart canvas').first()).toBeVisible();
    const chartCount = await detailRow.locator('.detail-chart canvas').count();
    // Toggle dark mode
    await page.click('button[title="Toggle dark mode"]');
    // Detail charts must still be visible with same count
    await expect(page.locator('.detail-row')).toBeVisible();
    await expect(page.locator('.detail-row .detail-chart canvas').first()).toBeVisible();
    const afterCount = await page.locator('.detail-row .detail-chart canvas').count();
    expect(afterCount).toBe(chartCount);
  });

  test('test ended event updates UI', async ({ page }) => {
    // Send testEnded event via test control endpoint
    await sendToPage(page, 'event', { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
  });
});

test.describe('Footer buttons', () => {
  // .footer-btn.danger:hover outranked .footer-btn:disabled, so a disabled Stop Test under
  // the pointer looked enabled - and right after clicking it, the pointer is still there.
  test('a disabled Stop Test stays pale under the pointer', async ({ page }) => {
    await loadRun(page, runCatchup());
    const stop = page.locator('#btnStop');
    await expect(stop).toBeDisabled();
    expect(await stop.evaluate(b => getComputedStyle(b).opacity), 'not hovered').toBe('0.4');
    await stop.hover({ force: true });
    await expect.poll(() => stop.evaluate(b => b.matches(':hover'))).toBe(true);
    expect(await stop.evaluate(b => getComputedStyle(b).opacity), 'hovered').toBe('0.4');
  });
});
