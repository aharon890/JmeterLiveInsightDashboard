// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage } = require('./setup/dashboard');
const path = require('path');
const fs = require('fs');

/**
 * The snapshot is advertised as self-contained and as working offline. It was neither:
 * it pulled ECharts from a public CDN, so every chart was blank on an air-gapped machine,
 * behind a proxy allowlist, or once the pinned URL stops resolving — and nothing on the
 * page said so, because the header, cards and tables all render without ECharts.
 *
 * The existing snapshot-export.spec.js cannot catch this: it asserts chart CONTAINERS are
 * visible, and a container div is in the DOM whether or not ECharts ever loaded.
 */
test.describe('Snapshot is self-contained', () => {
  /** @type {string} */ let snapshotPath;
  /** @type {string} */ let html;

  test.beforeAll(async ({ browser }, testInfo) => {
    const ctx = await browser.newContext({ acceptDownloads: true });
    const page = await ctx.newPage();
    await openDashboard(page);

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    snapshotPath = path.join(__dirname, 'tmp', `offline-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(snapshotPath), { recursive: true });
    await download.saveAs(snapshotPath);
    html = fs.readFileSync(snapshotPath, 'utf8');
    await ctx.close();
  });

  test.afterAll(() => {
    if (snapshotPath && fs.existsSync(snapshotPath)) fs.unlinkSync(snapshotPath);
  });

  test('references no external resource @smoke', () => {
    const external = [...html.matchAll(/<(?:script|link|img)[^>]+(?:src|href)=["'](https?:\/\/[^"']+)/gi)]
      .map(m => m[1]);
    expect(external).toEqual([]);
  });

  test('carries the charting library inline, not by reference', () => {
    // A marker that only appears in the real ECharts bundle.
    expect(html).toContain('echarts');
    expect(html.length).toBeGreaterThan(600_000);
  });

  test('charts render with the network unavailable @smoke', async ({ browser }) => {
    const ctx = await browser.newContext();
    const page = await ctx.newPage();
    const pageErrors = [];
    page.on('pageerror', e => pageErrors.push(e.message));

    // Everything except the local file itself is unreachable — an air-gapped viewer.
    let blocked = 0;
    await page.route('**/*', route => {
      if (route.request().url().startsWith('file://')) return route.continue();
      blocked++;
      return route.abort();
    });

    await page.goto('file://' + snapshotPath);

    expect(pageErrors).toEqual([]);
    expect(await page.evaluate(() => typeof window.echarts)).toBe('object');

    await page.evaluate(() => swTab('timeline'));
    // The canvas ECharts creates is the only proof a chart exists; the container div is
    // present either way, which is why the older specs could not detect this.
    for (const id of ['#responseTimeChart', '#throughputChart', '#errorRateChart']) {
      await expect(page.locator(`${id} canvas`)).toHaveCount(1);
    }
    expect(blocked).toBe(0);

    await ctx.close();
  });
});

test.describe('Snapshot handles awkward sampler labels', () => {
  test('a label containing an apostrophe keeps its filter pill working', async ({ browser }, testInfo) => {
    const LABEL = "GET /user's profile";
    const ctx = await browser.newContext({ acceptDownloads: true });
    const page = await ctx.newPage();
    await openDashboard(page);

    const base = JSON.parse(fs.readFileSync(
      path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
    const proto = Object.values(base.samplerStats)[0];
    base.knownSamplers = [LABEL, 'GET /plain'];
    base.samplerStats = {
      [LABEL]: { ...proto, name: LABEL },
      'GET /plain': { ...proto, name: 'GET /plain' },
    };
    await sendToPage(page, 'catchup', base);

    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    const file = path.join(__dirname, 'tmp', `apos-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    await page.close();

    const view = await ctx.newPage();
    const pageErrors = [];
    view.on('pageerror', e => pageErrors.push(e.message));
    await view.goto('file://' + file);

    // The snapshot LOADS fine either way — inline handlers are compiled only when invoked,
    // so the breakage shows up on click and nothing warns beforehand.
    const pill = view.locator('#samplerFilters .sampler-pill', { hasText: "user's profile" });
    await expect(pill).toHaveCount(1);
    const before = await view.locator('#samplerFilters .sampler-pill.checked').count();
    await pill.click();

    await expect(view.locator('#samplerFilters .sampler-pill.checked')).toHaveCount(before - 1);
    expect(pageErrors).toEqual([]);

    await ctx.close();
    fs.unlinkSync(file);
  });
});

/**
 * Exported after the server has gone. Shutdown, a killed JMeter and Ctrl+C all look the
 * same to the page: every new request is refused. The library used to be
 * fetched at the first export, so a tab that had not exported before the server went
 * produced a snapshot whose charts needed the CDN: blank on an offline machine, and
 * nothing on the page said so (measured 2026-10-11 on a real run). Refusing every request
 * once the page has loaded reproduces that here.
 */
test.describe('Snapshot exported after the server has gone', () => {
  const CDN = /<script src="https:\/\/cdn\.jsdelivr\.net/;
  /** @param {import('@playwright/test').Request} r */
  const isLibraryFetch = r => r.url().endsWith('/echarts.min.js') && r.resourceType() === 'fetch';

  /** @param {import('@playwright/test').Page} page */
  async function exportSnapshot(page, testInfo, name) {
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    const file = path.join(__dirname, 'tmp', `${name}-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    const html = fs.readFileSync(file, 'utf8');
    fs.unlinkSync(file);
    return html;
  }

  test('still carries the charting library inline @smoke', async ({ page }, testInfo) => {
    // Lets the copy the page takes on load finish before the server goes. Without one,
    // the wait just times out and the export below shows what happens.
    const loaded = page.waitForResponse(r => isLibraryFetch(r.request()), { timeout: 5000 })
      .then(r => r.finished()).catch(() => null);
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    await loaded;

    await page.route(url => url.protocol.startsWith('http'), route => route.abort('connectionrefused'));
    const html = await exportSnapshot(page, testInfo, 'gone');

    expect(CDN.test(html), 'the library is linked to the CDN, not inlined').toBe(false);
    expect(html.length).toBeGreaterThan(600_000);
  });

  test('an export clicked while that copy is still loading waits for it', async ({ page }, testInfo) => {
    let fetches = 0;
    await page.route('**/echarts.min.js', async route => {
      if (route.request().resourceType() === 'fetch') {
        fetches++;
        await new Promise(r => setTimeout(r, 1500));
      }
      await route.continue();
    });
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    const html = await exportSnapshot(page, testInfo, 'inflight');

    expect(CDN.test(html), 'the library is linked to the CDN, not inlined').toBe(false);
    expect(fetches).toBe(1);   // the export reused the download in flight
  });

  test('an export tries again when the copy taken on load failed', async ({ page }, testInfo) => {
    let exporting = false;
    await page.route('**/echarts.min.js', route =>
      route.request().resourceType() === 'fetch' && !exporting
        ? route.abort('connectionrefused') : route.continue());
    const failed = page.waitForEvent('requestfailed', { predicate: isLibraryFetch, timeout: 5000 })
      .catch(() => null);
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    await failed;

    exporting = true;   // the server is reachable again
    const html = await exportSnapshot(page, testInfo, 'retry');

    expect(CDN.test(html), 'the library is linked to the CDN, not inlined').toBe(false);
    expect(html.length).toBeGreaterThan(600_000);
  });

  test('falls back to the CDN reference, and says why in the file, when no copy can be had', async ({ page }, testInfo) => {
    await page.route('**/echarts.min.js', route =>
      route.request().resourceType() === 'fetch' ? route.abort('connectionrefused') : route.continue());
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    const html = await exportSnapshot(page, testInfo, 'fallback');

    expect(CDN.test(html), 'the CDN reference is missing').toBe(true);
    expect(html).toContain('ECharts could not be inlined');
  });
});
