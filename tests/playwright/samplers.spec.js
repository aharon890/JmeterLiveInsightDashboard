// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard } = require('./setup/dashboard');
const fs = require('fs');
const path = require('path');
const { runCatchup, loadRun } = require('./setup/run-data');

test.describe('Samplers Tab', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
    await page.click('.tab-btn[data-tab="samplers"]');
  });

  test('table renders sampler rows @smoke', async ({ page }) => {
    const rows = page.locator('#samplerTableBody tr');
    // Total row + 3 samplers
    await expect(rows).toHaveCount(4);
  });

  test('sigma total row present @smoke', async ({ page }) => {
    await expect(page.locator('.total-sampler-row')).toBeVisible();
    await expect(page.locator('.total-sampler-row')).toContainText('\u03A3 Total');
  });

  test('search filters rows', async ({ page }) => {
    await page.fill('#samplerSearch', 'cart');
    // Total + filtered rows
    const visible = page.locator('#samplerTableBody tr:visible');
    const text = await page.locator('#samplerTableBody').textContent();
    expect(text).toContain('cart');
  });

  test('a header click sorts the rows, and the sorted column shows its direction', async ({ page }) => {
    const sampler = page.locator('#samplerTable thead th', { hasText: 'Sampler' });
    const samples = page.locator('#samplerTable thead th', { hasText: /^Samples$/ });
    const counts = async () => (await page.locator('#samplerTableBody tr.expandable td:nth-child(2)').allTextContents())
      .map((t) => Number(t.replace(/,/g, '')));
    await expect(sampler, 'by name, ascending, on load').toHaveClass(/sorted-asc/);

    await samples.click();
    await expect(samples).toHaveClass(/sorted-asc/);
    await expect(sampler).not.toHaveClass(/sorted-/);
    const asc = await counts();
    expect(asc).toEqual([...asc].sort((a, b) => a - b));

    await samples.click();
    await expect(samples).toHaveClass(/sorted-desc/);
    expect(await counts()).toEqual([...asc].reverse());
  });

  test('SLA badges display per row', async ({ page }) => {
    const badges = page.locator('.sla-badge-mini');
    const count = await badges.count();
    expect(count).toBeGreaterThanOrEqual(3);
  });

  test('color dots visible per row', async ({ page }) => {
    const dots = page.locator('#samplerTableBody .sampler-name-cell span[style*="border-radius"]');
    const count = await dots.count();
    expect(count).toBeGreaterThanOrEqual(3);
  });

  test('row click expands detail charts', async ({ page }) => {
    const firstRow = page.locator('#samplerTableBody tr.expandable').first();
    await firstRow.click();
    const detailRow = page.locator('.detail-row');
    await expect(detailRow).toBeVisible();
    const charts = detailRow.locator('.detail-chart canvas');
    await expect.poll(() => charts.count()).toBeGreaterThanOrEqual(3);
  });

  test('CSV export button present', async ({ page }) => {
    await expect(page.locator('#tab-samplers button:has-text("Export CSV")')).toBeVisible();
  });

  test('column visibility gear button present', async ({ page }) => {
    await expect(page.locator('button:has-text("Columns")')).toBeVisible();
  });

  test('hide children button present', async ({ page }) => {
    await expect(page.locator('#btnToggleChildren')).toBeVisible();
  });
});

// Cells follow JMeter's Aggregate Report: whole milliseconds under the "(ms)" headers and
// KB/s to two decimals. The short card format printed 1077 ms as "1.1 s" in a column headed
// (ms), and one decimal turned 0.08 and 0.09 KB/s into the same "0.1".
test.describe('Samplers table precision', () => {
  test('"(ms)" columns print whole milliseconds and KB/s keeps two decimals', async ({ page }) => {
    await loadRun(page, runCatchup());
    await page.click('.tab-btn[data-tab="samplers"]');
    const table = await page.evaluate(() => {
      const heads = [...document.querySelectorAll('#samplerTable thead th')].map(t => t.textContent.trim());
      const rows = {};
      document.querySelectorAll('#samplerTableBody > tr').forEach(r => {
        rows[r.dataset.sampler || 'TOTAL'] = Object.fromEntries(heads.map((h, i) => [h, r.children[i].textContent.trim()]));
      });
      return rows;
    });
    expect(table['POST /checkout']).toMatchObject({ 'Mean (ms)': '618', 'P95 (ms)': '1,077', 'P99 (ms)': '1,256',
      'Max (ms)': '1,350', 'Recv KB/s': '0.08', 'Sent KB/s': '0.77' });
    expect(table['GET /search']).toMatchObject({ 'Max (ms)': '1,048', 'Recv KB/s': '0.09' });
    expect(table['POST /token']).toMatchObject({ 'Sent KB/s': '0.38' });
    expect(table['TOTAL']).toMatchObject({ 'P99 (ms)': '1,030', 'Max (ms)': '1,350', 'Recv KB/s': '0.54', 'Sent KB/s': '2.01' });
  });

  test('the CSV export keeps two decimals of KB/s too', async ({ page }) => {
    await loadRun(page, runCatchup());
    await page.click('.tab-btn[data-tab="samplers"]');
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('#tab-samplers button:has-text("Export CSV")'),
    ]);
    const csv = fs.readFileSync(await download.path(), 'utf8').split('\n');
    const checkout = csv.find(l => l.startsWith('"POST /checkout"'));
    expect(checkout).toContain('"0.08","0.77"');
  });
});

// A mean prints as JMeter's Aggregate Report prints it, truncated to whole milliseconds
// ((long) mean; the Summariser's "Avg:" too). Rounded, about half of all rows read 1 ms
// above JMeter's: 618.9 showed "619" and the run's 314.95 showed "315".
test.describe('Mean truncates as the Aggregate Report does', () => {
  // Mean cells by row; the snapshot's rows carry no data-sampler, so match the name cell
  const means = (p) => p.evaluate(() => {
    const heads = [...document.querySelectorAll('#samplerTable thead th')].map(t => t.textContent.trim());
    const col = heads.indexOf('Mean (ms)');
    const rows = {};
    document.querySelectorAll('#samplerTableBody > tr').forEach(r => {
      if (r.children.length !== heads.length) return;
      const name = r.classList.contains('total-sampler-row') ? 'TOTAL' : r.children[0].textContent;
      if (name === 'TOTAL' || name.includes('POST /checkout')) rows[name === 'TOTAL' ? name : 'POST /checkout'] = r.children[col].textContent.trim();
    });
    return rows;
  });
  const avgCard = (p) => p.locator('.summary-card:has(.card-label:text-is("Avg Response Time")) .card-value');
  const csvLine = async (p, sampler) => {
    const [download] = await Promise.all([
      p.waitForEvent('download'),
      p.click('#tab-samplers button:has-text("Export CSV")'),
    ]);
    return fs.readFileSync(await download.path(), 'utf8').split('\n').find(l => l.startsWith(`"${sampler}"`));
  };
  const run = () => {
    const r = runCatchup();
    r.samplerStats['POST /checkout'].avgResponseTime = 618.9;   // the run's mean is 314.95
    return r;
  };

  test('in the live table, the card and the CSV', async ({ page }) => {
    await loadRun(page, run());
    await expect(avgCard(page)).toHaveText('314 ms');
    await page.click('.tab-btn[data-tab="samplers"]');
    expect(await means(page)).toEqual({ 'POST /checkout': '618', TOTAL: '314' });
    expect(await csvLine(page, 'POST /checkout')).toContain('"9.19","618","875"');
  });

  test('and in the exported snapshot', async ({ page }, testInfo) => {
    await loadRun(page, run());
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    const file = path.join(__dirname, 'tmp', `mean-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    const view = await page.context().newPage();
    await view.goto('file://' + file);
    await expect(avgCard(view)).toHaveText('314 ms');
    await view.click('.tab-btn[data-tab="samplers"]');
    expect(await means(view)).toEqual({ 'POST /checkout': '618', TOTAL: '314' });
    expect(await csvLine(view, 'POST /checkout')).toContain('"9.19","618","875"');
  });
});
