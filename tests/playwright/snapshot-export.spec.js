// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');
const path = require('path');
const fs = require('fs');

test.describe('Snapshot Export', () => {
  // These tests deliberately share one exported snapshot built once in
  // beforeAll — exporting per test would be far slower. That shared page only
  // exists within a single worker, so opt this file out of the config-wide
  // fullyParallel split. 'default' (not 'serial') keeps them sequential in one
  // worker without letting one failure skip the rest.
  test.describe.configure({ mode: 'default' });

  /** @type {import('@playwright/test').Page} */
  let snapshotPage;
  let snapshotPath;

  test.beforeAll(async ({ browser }, testInfo) => {
    // Open live dashboard, wait for data, then export snapshot
    const context = await browser.newContext({ acceptDownloads: true });
    const page = await context.newPage();
    await openDashboard(page);

    // Trigger snapshot export and capture download
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")')
    ]);

    // Worker-scoped filename: a fixed path let concurrent workers overwrite
    // each other's export and unlink a file another was still reading.
    snapshotPath = path.join(__dirname, 'tmp', `test-snapshot-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(snapshotPath), { recursive: true });
    await download.saveAs(snapshotPath);
    await page.close();

    // Open the snapshot in a new page
    snapshotPage = await context.newPage();
    await snapshotPage.goto('file://' + snapshotPath);
  });

  test.afterAll(async () => {
    if (snapshotPage) await snapshotPage.close();
    if (snapshotPath && fs.existsSync(snapshotPath)) fs.unlinkSync(snapshotPath);
  });

  // --- Header ---
  test('has test name in header', async () => {
    await expect(snapshotPage.locator('h1')).toContainText('Smoke Test');
  });

  test('has SNAPSHOT badge', async () => {
    await expect(snapshotPage.locator('.status-badge:has-text("SNAPSHOT")')).toBeVisible();
  });

  test('has dark mode toggle button', async () => {
    await expect(snapshotPage.locator('button[title="Toggle dark mode"]')).toBeVisible();
  });

  test('has colorblind mode toggle button', async () => {
    await expect(snapshotPage.locator('button[title="Toggle colorblind mode"]')).toBeVisible();
  });

  // --- Filter Bar ---
  test('has palette switcher', async () => {
    await expect(snapshotPage.locator('#paletteToggle')).toBeVisible();
    await expect(snapshotPage.locator('#paletteToggle button')).toHaveCount(3);
  });

  test('has line thickness toggle', async () => {
    await expect(snapshotPage.locator('#lineToggle')).toBeVisible();
  });

  test('has metric toggle', async () => {
    await expect(snapshotPage.locator('#metricToggle')).toBeVisible();
    await expect(snapshotPage.locator('#metricToggle button')).toHaveCount(6);
  });

  test('has sampler filters', async () => {
    await expect(snapshotPage.locator('#samplerFilters')).toBeVisible();
    const pills = snapshotPage.locator('#samplerFilters .sampler-pill');
    await expect(pills).toHaveCount(3); // 3 samplers in fixture
  });

  test('has clock/elapsed toggle', async () => {
    await expect(snapshotPage.locator('#timeBtn')).toBeVisible();
  });

  // --- Summary Tab ---
  test('has 8 summary cards @smoke', async () => {
    const cards = snapshotPage.locator('.summary-card');
    await expect(cards).toHaveCount(8);
  });

  // The card is labelled "P95", not "AVG P95": it carries cumulative.p95, the
  // true overall percentile from the server's digest, not a mean of per-bucket
  // percentiles. The old label/assertion outlived the avgP95 -> p95 contract.
  test('summary cards include Max Threads and P95', async () => {
    await expect(snapshotPage.locator('.card-label:has-text("Max Threads")')).toBeVisible();
    await expect(snapshotPage.locator('.card-label:has-text("P95")')).toBeVisible();
  });

  test('summary chart has fullscreen button', async () => {
    await expect(snapshotPage.locator('.chart-section:has(#summaryRTChart) button:has-text("Fullscreen")')).toBeVisible();
  });

  // --- Timeline Tab ---
  test('timeline tab has 5 charts', async () => {
    await snapshotPage.click('.tab-btn[data-tab="timeline"]');
    await expect(snapshotPage.locator('#responseTimeChart')).toBeVisible();
    await expect(snapshotPage.locator('#throughputChart')).toBeVisible();
    await expect(snapshotPage.locator('#errorRateChart')).toBeVisible();
    await expect(snapshotPage.locator('#bytesChart')).toBeVisible();
    await expect(snapshotPage.locator('#connectLatencyChart')).toBeVisible();
  });

  /**
   * The visibility assertions above pass whether or not a chart was ever drawn — the
   * container div is in the DOM either way. That is exactly how a snapshot whose charting
   * library failed to load went undetected: header, cards and tables all render fine, so
   * the file looks finished. The canvas ECharts creates is the only proof.
   */
  test('timeline charts actually rendered @smoke', async () => {
    await snapshotPage.click('.tab-btn[data-tab="timeline"]');
    for (const id of ['#responseTimeChart', '#throughputChart', '#errorRateChart',
                      '#bytesChart', '#connectLatencyChart']) {
      await expect(snapshotPage.locator(`${id} canvas`)).toHaveCount(1);
    }
  });

  // --- Samplers Tab ---
  test('samplers tab has search input', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');
    await expect(snapshotPage.locator('#samplerSearch')).toBeVisible();
  });

  test('samplers tab has CSV export button', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');
    await expect(snapshotPage.locator('#tab-samplers button:has-text("Export CSV")')).toBeVisible();
  });

  test('samplers tab has Copy Summary button', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');
    await expect(snapshotPage.locator('button:has-text("Copy Summary")')).toBeVisible();
  });

  // The exported report's table follows the live one: whole ms under "(ms)", KB/s to two
  // decimals. It printed the fixture's 1,200 ms max as "1.2 s" and 42.1 KB/s as "42.1 KB/s".
  test('statistics table prints whole ms and two-decimal KB/s', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');
    const cart = await snapshotPage.evaluate(() => {
      const heads = [...document.querySelectorAll('#samplerTable thead th')].map(t => t.textContent.trim());
      const row = [...document.querySelectorAll('#samplerTableBody tr')].find(r => r.children[0].textContent.includes('POST /api/cart'));
      return Object.fromEntries(heads.map((h, i) => [h, row.children[i].textContent.trim()]));
    });
    expect(cart).toMatchObject({ 'P99 (ms)': '1,100', 'Max (ms)': '1,200', 'Recv KB/s': '42.10', 'Sent KB/s': '15.70' });
  });

  test('sampler table has data rows', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');
    const rows = snapshotPage.locator('#samplerTableBody tr');
    // 1 total row + 3 sampler rows
    await expect(rows).toHaveCount(4);
  });

  // --- Errors Tab ---
  test('errors tab has pie and bar charts', async () => {
    await snapshotPage.click('.tab-btn[data-tab="errors"]');
    await expect(snapshotPage.locator('#errorPieChart')).toBeVisible();
    await expect(snapshotPage.locator('#errorBarChart')).toBeVisible();
  });

  test('errors tab has error table', async () => {
    await snapshotPage.click('.tab-btn[data-tab="errors"]');
    await expect(snapshotPage.locator('#errorTableBody')).toBeVisible();
  });

  // --- Dark Mode Toggle ---
  test('dark mode toggle works', async () => {
    // Go back to summary
    await snapshotPage.click('.tab-btn[data-tab="summary"]');

    const body = snapshotPage.locator('body');
    const wasDark = await body.evaluate(el => el.classList.contains('dark-mode'));

    await snapshotPage.click('button[title="Toggle dark mode"]');
    await expect.poll(() => body.evaluate(el => el.classList.contains('dark-mode'))).toBe(!wasDark);

    // Toggle back
    await snapshotPage.click('button[title="Toggle dark mode"]');
  });

  // --- Metric Toggle ---
  test('metric toggle re-renders charts', async () => {
    await snapshotPage.click('.tab-btn[data-tab="summary"]');

    // Click P90 metric
    await snapshotPage.click('#metricToggle button:has-text("P90")');

    // Verify the button is now active
    const p90Btn = snapshotPage.locator('#metricToggle button:has-text("P90")');
    await expect(p90Btn).toHaveClass(/active/);
  });

  // --- Sampler Visibility ---
  test('sampler visibility toggle works', async () => {
    await snapshotPage.click('.tab-btn[data-tab="summary"]');

    // Toggle sampler via clicking the pill
    const changed = await snapshotPage.evaluate(() => {
      const pill = document.querySelector('#samplerFilters .sampler-pill');
      if (!pill) return false;
      pill.click();
      return true;
    });
    expect(changed).toBe(true);

    // Re-enable
    await snapshotPage.evaluate(() => {
      const pill = document.querySelector('#samplerFilters .sampler-pill');
      if (pill) pill.click();
    });
  });

  // --- Fullscreen Toggle ---
  test('fullscreen toggle works on chart', async () => {
    await snapshotPage.click('.tab-btn[data-tab="summary"]');

    const fsBtn = snapshotPage.locator('.chart-section:has(#summaryRTChart) button:has-text("Fullscreen")');
    await fsBtn.click();

    const section = snapshotPage.locator('.chart-section:has(#summaryRTChart)');
    await expect(section).toHaveClass(/chart-fullscreen/);

    // Exit fullscreen
    await snapshotPage.locator('.chart-section:has(#summaryRTChart) button:has-text("Exit Fullscreen")').click();
    await expect(section).not.toHaveClass(/chart-fullscreen/);
  });

  // --- Table Sorting ---
  test('clicking table header sorts rows', async () => {
    await snapshotPage.click('.tab-btn[data-tab="samplers"]');

    // Click "Samples" header to sort
    await snapshotPage.click('th:has-text("Samples")');

    // Click again to reverse sort
    await snapshotPage.click('th:has-text("Samples")');

    // Just verify rows still exist (sorting didn't break)
    const rows = snapshotPage.locator('#samplerTableBody tr');
    await expect(rows).toHaveCount(4);
  });

  // --- Clock/Elapsed Toggle ---
  test('time display toggle changes button text', async () => {
    const btn = snapshotPage.locator('#timeBtn');
    const initialText = await btn.textContent();

    await btn.click();
    await expect.poll(() => btn.textContent()).not.toBe(initialText);

    // Toggle back
    await btn.click();
  });
});
