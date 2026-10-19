const { chromium } = require('playwright');
const fs = require('fs');
const os = require('os');
const path = require('path');

const IMG_DIR = path.resolve(__dirname, '../../docs/images');
// Point at a real run for the docs: BASE_URL=http://localhost:7566 while a
// plan is running. The default is the mock server (`node setup/test-server.js`),
// whose two-bucket fixture draws flat lines and a nonsense elapsed time.
const BASE = process.env.BASE_URL || 'http://localhost:7577';
// Which set to take, because one run cannot show every state:
//   live          (default) a run still going: every tab, control and the LIVE/EXPLORING badges
//   ended         started while the run is going: the ENDED badge when it finishes, then
//                 DISCONNECTED once a non-GUI JMeter exits and takes the server with it
//   transactions  a run of a plan with Transaction Controllers (test-plans/docker-smoke-test.jmx)
const SET = process.env.SHOTS || 'live';

const img = (file) => path.join(IMG_DIR, file);

// Element shots with some room around them: a crop flush to the border reads as cut off.
// Several locators are joined into one box, for a control and the popup it opened.
// The fixed footer is unpinned first, or a crop reaching past the viewport has it
// painted across the middle; the boxes are measured after that, for the footer's own.
async function crop(page, locators, file, pad = 8) {
  const unpin = await page.addStyleTag({ content: '.dashboard-footer{position:static!important}' });
  const boxes = [];
  for (const l of [].concat(locators)) boxes.push(await l.boundingBox());
  const x = Math.max(0, Math.min(...boxes.map(b => b.x)) - pad);
  const y = Math.max(0, Math.min(...boxes.map(b => b.y)) - pad);
  const right = Math.max(...boxes.map(b => b.x + b.width)) + pad;
  const bottom = Math.max(...boxes.map(b => b.y + b.height)) + pad;
  await page.screenshot({ path: img(file), clip: { x, y, width: right - x, height: bottom - y }, fullPage: true });
  await unpin.evaluate(el => el.remove());
}

async function openDashboard(page) {
  await page.goto(BASE);
  // The header's elapsed time stays "--:--:--" until the first live update
  // after the catch-up (up to one update interval), so wait for it rather than
  // for a fixed delay.
  await page.waitForFunction(() => !document.getElementById('elapsed').textContent.includes('--'),
    null, { timeout: 15000 }).catch(() => {});
  await page.waitForTimeout(500);
}

async function tab(page, name) {
  await page.click(`.tab-btn[data-tab="${name}"]`);
  await page.waitForTimeout(500);
}

async function liveSet(page) {
  // The footer is position:fixed, so a fullPage capture paints it where the
  // 800px viewport ended — across the middle of the charts. Unpin it for those.
  const fullPageShot = async (file) => {
    const unpin = await page.addStyleTag({ content: '.dashboard-footer{position:static!important}' });
    await page.screenshot({ path: img(file), fullPage: true });
    await unpin.evaluate(el => el.remove());
  };
  const darkMode = async () => {
    await page.click('button[title="Toggle dark mode"]');
    await page.waitForTimeout(500);
  };

  // Summary tab, light and dark, and the header on its own
  await page.screenshot({ path: img('summary-light.png') });
  await crop(page, page.locator('.report-header'), 'header.png', 0);
  await crop(page, page.locator('#liveIndicator'), 'state-live.png', 4);
  await darkMode();
  await page.screenshot({ path: img('summary-dark.png') });
  await darkMode();

  // Colorblind mode: the success/error colors turn blue/orange
  await page.click('button[title="Toggle colorblind mode"]');
  await page.waitForTimeout(500);
  await page.screenshot({ path: img('colorblind-mode.png') });
  await page.click('button[title="Toggle colorblind mode"]');
  await page.waitForTimeout(500);

  // Filter bar. At any width each of its two rows wraps onto more than one line,
  // so outline the rows and name them: the guide describes them as Row 1 and Row 2.
  const outline = await page.addStyleTag({ content: `
    .filter-bar .filter-row{outline:2px dashed #f59e0b;outline-offset:3px;position:relative}
    .filter-bar .filter-row::after{position:absolute;top:-9px;right:6px;padding:1px 7px;border-radius:4px;
      background:#f59e0b;color:#fff;font:700 10px/14px sans-serif;letter-spacing:.04em}
    .filter-bar .filter-row:nth-of-type(1)::after{content:'ROW 1'}
    .filter-bar .filter-row:nth-of-type(2)::after{content:'ROW 2'}` });
  await crop(page, page.locator('.filter-bar'), 'filter-bar.png', 6);
  await outline.evaluate(el => el.remove());
  await crop(page, page.locator('#samplerFilterList'), 'sampler-pills.png');
  // The color picker opens from a sampler's color dot (the Σ Total dot has none)
  const pill = page.locator('#samplerFilterList .sampler-pill').nth(1);
  await pill.locator('.sampler-color-dot').click();
  const popup = page.locator('.color-picker-popup.open');
  await popup.waitFor();
  await crop(page, [pill, popup], 'color-picker.png');
  await page.mouse.click(5, 5);

  // Timeline tab: the five charts, one chart card, then a zoom (EXPLORING)
  await tab(page, 'timeline');
  await fullPageShot('timeline-light.png');
  await darkMode();
  await fullPageShot('timeline-dark.png');
  await darkMode();
  await crop(page, page.locator('.chart-section[data-chart-id="responseTimeChart"]'), 'chart-card.png');
  await page.evaluate(() => S.charts.responseTimeChart.dispatchAction({ type: 'dataZoom', start: 40, end: 70 }));
  await page.waitForFunction(() => document.getElementById('liveLabel').textContent === 'EXPLORING');
  await page.waitForTimeout(500);
  await page.screenshot({ path: img('explore-mode.png') });
  await crop(page, page.locator('#liveIndicator'), 'state-exploring.png', 4);
  await page.click('#returnLiveBtn');
  await page.waitForTimeout(500);

  // Samplers tab: table, toolbar, column menu, one expanded row
  await tab(page, 'samplers');
  await page.screenshot({ path: img('samplers-light.png') });
  await crop(page, page.locator('#samplerTable'), 'samplers-table.png');
  await crop(page, page.locator('.table-toolbar'), 'table-toolbar.png');
  await page.click('button:has-text("Columns")');
  await crop(page, [page.locator('.table-toolbar'), page.locator('#colMenu')], 'column-menu.png');
  await page.click('button:has-text("Columns")');
  const firstRow = page.locator('#samplerTableBody tr.expandable').first();
  await firstRow.click();
  await page.waitForTimeout(500);
  await fullPageShot('samplers-detail.png');
  await crop(page, [firstRow, page.locator('#samplerTableBody tr.detail-row').first()], 'sampler-detail-row.png');

  // Errors tab: one screen of it, then each chart
  await tab(page, 'errors');
  await page.screenshot({ path: img('errors-light.png') });
  await crop(page, page.locator('#tab-errors .chart-section').nth(0), 'error-pie.png', 0);
  await crop(page, page.locator('#tab-errors .chart-section').nth(1), 'error-bar.png', 0);

  // Footer
  await crop(page, page.locator('footer.dashboard-footer'), 'footer.png', 0);

  // Copy Summary: what lands on the clipboard, shown as a page would render it
  await page.evaluate(() => {
    navigator.clipboard.write = async (items) => {
      window.__copied = await (await items[0].getType('text/html')).text();
    };
  });
  await page.click('button:has-text("Copy Summary")');
  const copied = await page.waitForFunction(() => window.__copied).then(h => h.jsonValue());
  const paste = await page.context().newPage();
  await paste.setContent(`<body style="margin:0;padding:16px;background:#fff">${copied}</body>`);
  await crop(paste, paste.locator('table'), 'copy-summary.png', 12);
  await paste.close();

  // Export Snapshot: the downloaded file, opened from disk as a recipient would
  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('button:has-text("Export Snapshot")'),
  ]);
  const file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'snapshot-')), 'snapshot.html');
  await download.saveAs(file);
  const snap = await page.context().newPage();
  await snap.goto('file://' + file);
  await snap.waitForTimeout(1500);
  await snap.screenshot({ path: img('snapshot.png') });
  await snap.close();
}

(async () => {
  const browser = await chromium.launch();
  const context = await browser.newContext({ viewport: { width: 1280, height: 800 }, acceptDownloads: true });
  const page = await context.newPage();
  await openDashboard(page);

  if (SET === 'live') {
    await liveSet(page);
  } else if (SET === 'ended') {
    // Both states are short-lived: ENDED lasts until JMeter exits, so poll fast.
    const label = (text, timeout) => page.waitForFunction(
      t => document.getElementById('liveLabel').textContent === t, text, { timeout, polling: 50 });
    await label('ENDED', 0);
    await crop(page, page.locator('#liveIndicator'), 'state-ended.png', 4);
    await label('DISCONNECTED', 120000);
    await crop(page, page.locator('#liveIndicator'), 'state-disconnected.png', 4);
  } else if (SET === 'transactions') {
    await tab(page, 'samplers');
    await crop(page, page.locator('#samplerTable'), 'samplers-transactions.png');
  } else {
    throw new Error(`SHOTS=${SET}: expected live, ended or transactions`);
  }

  await browser.close();
  console.log(`Screenshots (${SET}) saved to docs/images/`);
})();
