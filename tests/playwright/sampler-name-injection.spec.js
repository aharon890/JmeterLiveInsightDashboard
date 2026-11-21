// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage, settled } = require('./setup/dashboard');
const path = require('path');
const fs = require('fs');

/**
 * A sampler name is text from the test plan, and often from data the plan reads (a CSV of
 * URLs, a value taken from a response). The page must show it and never run it.
 *
 * Two places ran it (found by the 2026-11-21 security scan, both reproduced first):
 *  - the Samplers row wrote the name into its own onclick as a JS string and escaped the
 *    quote but not the backslash, so `x\');...` closed the string and ran the rest;
 *  - the chart legend's tooltip shows the full name where the legend cuts it short, and
 *    ECharts 5.5.0 writes that text into the tooltip as HTML.
 */
test.describe('Sampler names are shown, never run', () => {
  /** The basic catch-up with one sampler only, renamed: one row, one legend entry. */
  const catchupWithOnly = (name) => {
    const base = JSON.parse(fs.readFileSync(
      path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
    const [original, ...others] = base.knownSamplers;
    const walk = (v) => {
      if (typeof v === 'string') return v === original ? name : v;
      if (Array.isArray(v)) return v.filter((x) => !others.includes(x)).map(walk);
      if (v && typeof v === 'object') {
        return Object.fromEntries(Object.entries(v)
          .filter(([k]) => !others.includes(k))
          .map(([k, x]) => [k === original ? name : k, walk(x)]));
      }
      return v;
    };
    return walk(base);
  };

  const pwned = (page) => page.evaluate(() => window.__PWNED === 1);

  /**
   * Moves the mouse along a chart's legend, outward from its centre, until the legend's
   * tooltip shows text or the payload has run. Returns the tooltip's text ('' if none).
   */
  async function hoverLegend(page, chartId) {
    // Centred, so the legend at the chart's bottom edge is clear of the page's fixed footer bar
    await page.evaluate((id) => document.getElementById(id).scrollIntoView({ block: 'center' }), chartId);
    const box = await page.locator('#' + chartId + ' canvas').first().boundingBox();
    if (!box) throw new Error(`#${chartId} has no chart drawn`);
    // legend:{bottom:5}, and the legend is 25px tall (measured): its entries' centre line is
    // 17px above the canvas's bottom edge
    const y = box.y + box.height - 17;
    const centre = box.x + box.width / 2;
    const tooltipText = () => page.evaluate((id) => Array.from(document.querySelectorAll('#' + id + ' > div'))
      .map((d) => (getComputedStyle(d).display === 'none' ? '' : d.textContent || '')).find((t) => t.length > 0) || '', chartId);
    for (let off = 0; off < box.width / 2 - 10; off += 6) {
      for (const x of off === 0 ? [centre] : [centre - off, centre + off]) {
        await page.mouse.move(x, y);
        await page.waitForTimeout(40); // the tooltip is drawn on the chart's next frame
        if (await pwned(page)) return '';
        const text = await tooltipText();
        if (text) return text;
      }
    }
    return '';
  }

  const BREAKOUT = "x\\');window.__PWNED=1;//"; // x\');window.__PWNED=1;//

  test('a backslash in a sampler name cannot break out of its Samplers row @smoke', async ({ page }) => {
    await openDashboard(page);
    await sendToPage(page, 'catchup', catchupWithOnly(BREAKOUT));
    await page.click('.tab-btn[data-tab="samplers"]');
    await expect(page.locator('#samplerTableBody tr.expandable')).toHaveCount(1);

    await page.click('#samplerTableBody tr.expandable td');
    await settled(page);

    expect(await pwned(page), 'the sampler name ran as script').toBe(false);
    // and the click still did its job, for exactly this name
    expect(await page.evaluate((n) => S.expandedSamplers.has(n), BREAKOUT)).toBe(true);
    await expect(page.locator('#samplerTableBody tr.detail-row')).toHaveCount(1);
  });

  test('a sampler named like a Windows path, with quotes, still opens its row', async ({ page }) => {
    // \t and \n are escapes inside a JS string, and a name ending in a backslash swallowed
    // the closing quote: such a row never opened.
    const name = 'GET C:\\temp\\new "report" it\'s <b>&amp;</b> \\';
    await openDashboard(page);
    await sendToPage(page, 'catchup', catchupWithOnly(name));
    await page.click('.tab-btn[data-tab="samplers"]');

    await page.click('#samplerTableBody tr.expandable td');
    await settled(page);

    expect(await page.evaluate((n) => S.expandedSamplers.has(n), name)).toBe(true);
    await expect(page.locator('#samplerTableBody tr.detail-row')).toHaveCount(1);
    await expect(page.locator('#samplerTableBody tr.expandable .sampler-name-cell')).toContainText(name);
  });

  const MARKUP = '<img src=x onerror="window.__PWNED=1">';

  test('markup in a sampler name is text in the chart legend tooltip @smoke', async ({ page }) => {
    await openDashboard(page);
    await sendToPage(page, 'catchup', catchupWithOnly(MARKUP));
    await settled(page);

    const shown = await hoverLegend(page, 'summaryRTChart');
    await page.waitForTimeout(200); // a broken image reports its error a moment after it is added

    expect(await pwned(page), 'the sampler name ran as script').toBe(false);
    expect(shown, 'the tooltip shows the full name, as text').toBe(MARKUP);
  });

  test('and in the exported snapshot, the file people pass on', async ({ browser }, testInfo) => {
    const ctx = await browser.newContext({ acceptDownloads: true });
    const page = await ctx.newPage();
    await openDashboard(page);
    await sendToPage(page, 'catchup', catchupWithOnly(MARKUP));
    const [download] = await Promise.all([
      page.waitForEvent('download'),
      page.click('button:has-text("Export Snapshot")'),
    ]);
    // Worker-scoped path: a fixed one lets parallel workers unlink a file another is reading.
    const file = path.join(__dirname, 'tmp', `name-inj-w${testInfo.workerIndex}.html`);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    await download.saveAs(file);
    await page.close();

    const view = await ctx.newPage();
    const pageErrors = [];
    view.on('pageerror', (e) => pageErrors.push(e.message));
    await view.goto('file://' + file);
    await view.waitForFunction(() => typeof window.D === 'object' && !!window.echarts
      && !!echarts.getInstanceByDom(document.getElementById('summaryRTChart')));

    const shown = await hoverLegend(view, 'summaryRTChart');
    await view.waitForTimeout(200);

    expect(await pwned(view), 'the sampler name ran as script').toBe(false);
    expect(shown, 'the tooltip shows the full name, as text').toBe(MARKUP);
    expect(pageErrors).toEqual([]);

    await ctx.close();
    fs.unlinkSync(file);
  });
});
