// @ts-check
const { test, expect } = require('./fixtures');
const fs = require('fs');
const path = require('path');
const { runCatchup, loadRun } = require('./setup/run-data');

/**
 * The dashboard is an English page, and it prints a number the same way on every machine, as
 * JMeter's HTML report does (toFixed, whatever the machine's language). It followed the
 * browser's language: on a German machine POST /checkout's 1,350 ms max read "1.350", one and
 * a third milliseconds to an English reader, and 0.08 KB/s read "0,08".
 */
const table = (p) => p.evaluate(() => {
  const heads = [...document.querySelectorAll('#samplerTable thead th')].map(t => t.textContent.trim());
  const rows = {};
  document.querySelectorAll('#samplerTableBody > tr').forEach(r => {
    if (r.children.length === heads.length) rows[r.dataset.sampler || r.children[0].textContent.trim()] = Object.fromEntries(heads.map((h, i) => [h, r.children[i].textContent.trim()]));
  });
  return rows;
});

test.describe('Numbers on a German-language machine', () => {
  test.use({ locale: 'de-DE' });

  test('the live page prints them as on an English one', async ({ page }) => {
    await loadRun(page, runCatchup());
    await expect(page.locator('#valSamples')).toHaveText('3,276');
    await page.click('.tab-btn[data-tab="samplers"]');
    await expect.poll(async () => (await table(page))['POST /checkout']).toMatchObject({ 'Max (ms)': '1,350', 'P95 (ms)': '1,077', 'Recv KB/s': '0.08' });
  });

  test('so does the exported snapshot', async ({ page }, testInfo) => {
    await loadRun(page, runCatchup());
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    const file = path.join(__dirname, 'tmp', `german-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    const view = await page.context().newPage();
    await view.goto('file://' + file);
    await view.click('.tab-btn[data-tab="samplers"]');
    expect(Object.values(await table(view)).find((r) => r.Sampler && r.Sampler.includes('POST /checkout')))
      .toMatchObject({ 'Max (ms)': '1,350', 'P95 (ms)': '1,077', 'Recv KB/s': '0.08' });
  });
});
