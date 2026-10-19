// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');

test.describe('Header & Global UI', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('test name displays from catchup', async ({ page }) => {
    await expect(page.locator('#testName')).toHaveText('Smoke Test');
  });

  test('status badge shows LIVE when connected @smoke', async ({ page }) => {
    const label = page.locator('#liveLabel');
    await expect(label).toHaveText(/LIVE|CONNECTED/);
  });

  test('dark mode toggle works', async ({ page }) => {
    const body = page.locator('body');
    await expect(body).not.toHaveClass(/dark-mode/);
    await page.click('#darkModeIcon');
    await expect(body).toHaveClass(/dark-mode/);
    await page.click('#darkModeIcon');
    await expect(body).not.toHaveClass(/dark-mode/);
  });

  test('colorblind mode toggle works', async ({ page }) => {
    const body = page.locator('body');
    await expect(body).not.toHaveClass(/cb-mode/);
    await page.click('#cbModeIcon');
    await expect(body).toHaveClass(/cb-mode/);
    await page.click('#cbModeIcon');
    await expect(body).not.toHaveClass(/cb-mode/);
  });

  test('dark + colorblind combined mode', async ({ page }) => {
    await page.click('#darkModeIcon');
    await page.click('#cbModeIcon');
    const body = page.locator('body');
    await expect(body).toHaveClass(/dark-mode/);
    await expect(body).toHaveClass(/cb-mode/);
  });

  test('thread info displays', async ({ page }) => {
    await expect(page.locator('#threadInfo')).toContainText('/');
  });

  test('SLA badge visible when config present', async ({ page }) => {
    await expect(page.locator('#slaBadge')).toBeVisible();
  });

  test('Apdex badge visible', async ({ page }) => {
    await expect(page.locator('#apdexBadge')).toBeVisible();
    await expect(page.locator('#apdexBadge')).toContainText('Apdex');
  });

  test('tab navigation switches panels @smoke', async ({ page }) => {
    await page.click('.tab-btn[data-tab="timeline"]');
    await expect(page.locator('#tab-timeline')).toHaveClass(/active/);
    await expect(page.locator('#tab-summary')).not.toHaveClass(/active/);

    await page.click('.tab-btn[data-tab="samplers"]');
    await expect(page.locator('#tab-samplers')).toHaveClass(/active/);

    await page.click('.tab-btn[data-tab="errors"]');
    await expect(page.locator('#tab-errors')).toHaveClass(/active/);
  });

  test('elapsed timer is running', async ({ page }) => {
    const elapsed = page.locator('#elapsed');
    await expect(elapsed).not.toHaveText('--:--:--');
  });

  const send = (page, path, body) =>
    page.evaluate(async ([p, b]) => {
      await fetch(p, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b) });
    }, [path, body]);

  // The elapsed timer only ticks while the test runs. A tab opened or reloaded
  // after testEnded gets a catch-up with testRunning:false and no live update
  // follows, so the header stayed "--:--:--" for the whole post-test review.
  test('elapsed shows the run length when the page is opened after the test ended', async ({ page }) => {
    const start = Date.now() - 300000;
    await send(page, '/test/send-catchup',
      { type: 'catchup', testName: 'Smoke Test', testRunning: false, testStartTime: start, testEndTime: start + 295000 });
    await expect(page.locator('#elapsed')).toHaveText('00:04:55');
  });

  test('elapsed stops at the run end time when the test ends with the tab open', async ({ page }) => {
    const start = Date.now() - 120000;
    await send(page, '/test/send-catchup', { type: 'catchup', testName: 'Smoke Test', testRunning: true, testStartTime: start });
    await expect(page.locator('#elapsed')).toHaveText(/^00:02:0\d$/);
    await send(page, '/test/send-event', { type: 'event', event: 'testEnded', data: { endTime: start + 100000 } });
    await expect(page.locator('#elapsed')).toHaveText('00:01:40');
  });

  test('elapsed counts from the new start when a run follows an ended one', async ({ page }) => {
    const start = Date.now() - 300000;
    await send(page, '/test/send-catchup',
      { type: 'catchup', testName: 'Smoke Test', testRunning: false, testStartTime: start, testEndTime: start + 295000 });
    await send(page, '/test/send-event', { type: 'event', event: 'testStarted', data: { startTime: Date.now() - 10000 } });
    await expect(page.locator('#elapsed')).toHaveText(/^00:00:1\d$/);
  });
});
