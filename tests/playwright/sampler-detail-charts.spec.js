// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage, settled } = require('./setup/dashboard');
const fs = require('fs');
const path = require('path');

/**
 * An expanded sampler's mini-charts must live as long as the row is expanded.
 *
 * Every update rebuilds the sampler table from scratch (tbody.innerHTML). The expanded detail
 * rows used to be rebuilt with it and three new charts drawn into them, while the old three
 * stayed registered in ECharts: measured 2026-10-12, 60 charts created and none disposed over
 * 20 updates with one row expanded, and a zoom on a mini-chart reset within one update,
 * although FEATURES.md and the Samplers tutorial promise it is kept.
 */

const catchup = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
const [NAME, NAME2, NAME3] = Object.keys(catchup.samplerStats);
const last = catchup.recentBuckets[catchup.recentBuckets.length - 1];
let ts = last.timestamp;
/** A live update as the server's newest message is: a slot, plus the sampler table. */
const update = () => ({ type: 'bucket', timestamp: (ts += 2000), overall: last.overall, samplers: last.samplers,
  samplerStats: catchup.samplerStats, cumulative: catchup.cumulativeStats, testRunning: true });

async function send(page, n = 1) {
  for (let i = 0; i < n; i++) {
    await sendToPage(page, 'bucket', update());
  }
}

/** Count every chart created and disposed from now on. */
async function countCharts(page) {
  await page.evaluate(() => {
    const proto = Object.getPrototypeOf(echarts.init(document.createElement('div')));
    if (!window.__counting) {
      const init = echarts.init, dispose = proto.dispose;
      echarts.init = function () { window.__init++; return init.apply(this, arguments); };
      proto.dispose = function () { window.__dispose++; return dispose.apply(this, arguments); };
      window.__counting = true;
    }
    window.__init = 0; window.__dispose = 0;
  });
}
const counts = (page) => page.evaluate(() => ({ created: window.__init, disposed: window.__dispose }));
/** The expanded sampler's three charts, found through its own detail row (response time, throughput,
 * error rate): their ids, whether they are on the page, and their data lengths. */
const miniCharts = (page, name) => page.evaluate((n) => [0, 1, 2].map((i) => {
  const c = [...(document.querySelector(`tr.detail-row[data-detail-for="${CSS.escape(n)}"]`)?.querySelectorAll('.detail-chart') || [])].map((el) => echarts.getInstanceByDom(el))[i];
  return c ? { id: c.id, attached: document.body.contains(c.getDom()), points: c.getOption().series[0].data.length,
    zoom: [c.getOption().dataZoom[0].start, c.getOption().dataZoom[0].end] } : null;
}), name);

/** Zoom one mini-chart, the way the mouse wheel does. */
const zoom = (page, name, kind, start, end) => page.evaluate(([n, i, a, b]) => {
  [...(document.querySelector(`tr.detail-row[data-detail-for="${CSS.escape(n)}"]`)?.querySelectorAll('.detail-chart') || [])].map((el) => echarts.getInstanceByDom(el))[i].dispatchAction({ type: 'dataZoom', start: a, end: b });
}, [name, { 'detail-rt-': 0, 'detail-tp-': 1, 'detail-er-': 2 }[kind], start, end]);

async function expand(page, name) {
  await page.click('.tab-btn[data-tab="samplers"]');
  await page.click(`#samplerTableBody tr[data-sampler="${name}"] td`);
  await settled(page); // its three charts are built
}

test.describe('Expanded sampler charts', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    await page.waitForFunction(() => window.S && Object.keys(S.samplerStats).length > 0);
  });

  test('are kept across updates, not drawn again @smoke', async ({ page }) => {
    await expand(page, NAME);
    const before = await miniCharts(page, NAME);
    expect(before.every(Boolean), 'three mini-charts').toBe(true);
    await countCharts(page);
    await send(page, 20);
    const after = await miniCharts(page, NAME);
    expect(await counts(page), 'no chart created or disposed by updates').toEqual({ created: 0, disposed: 0 });
    expect(after.map((c) => c.id), 'the same three charts').toEqual(before.map((c) => c.id));
    expect(after.every((c) => c.attached)).toBe(true);
    after.forEach((c, i) => expect(c.points, 'and they still take the new data').toBe(before[i].points + 20));
  });

  test('zoom together and freeze together, like the Timeline charts, and follow the data again once zoomed out', async ({ page }) => {
    // The mini-charts are there to compare one period across KPIs and samplers: zooming one
    // zooms all of them, and they hold still together while zoomed, as the Timeline's charts do.
    await expand(page, NAME);
    await expand(page, NAME2);
    await send(page, 4);
    const all = async () => [...await miniCharts(page, NAME), ...await miniCharts(page, NAME2)];
    await zoom(page, NAME, 'detail-rt-', 40, 60);
    const zoomed = await all();
    zoomed.forEach((c, i) => expect(c.zoom, `chart ${i}: zooming one zooms all`).toEqual([40, 60]));
    await send(page, 3);
    const held = await all();
    held.forEach((c, i) => {
      expect(c.zoom, `chart ${i}: the zoom survives updates`).toEqual([40, 60]);
      expect(c.points, `chart ${i}: held still while zoomed`).toBe(zoomed[i].points);
      expect(c.id).toBe(zoomed[i].id);
    });

    await zoom(page, NAME2, 'detail-tp-', 0, 100); // zoomed back out, from another chart
    await send(page, 2);
    const slots = await page.evaluate(() => S.timestamps.length);
    (await all()).forEach((c, i) => {
      expect(c.zoom, `chart ${i}: zoomed out with the others`).toEqual([0, 100]);
      expect(c.points, `chart ${i}: live again, caught up`).toBe(slots);
    });
  });

  test('a sampler expanded while the others are zoomed opens at the same zoom', async ({ page }) => {
    await expand(page, NAME);
    await send(page, 3);
    await zoom(page, NAME, 'detail-er-', 25, 75);
    await expand(page, NAME3);
    const joined = await miniCharts(page, NAME3);
    joined.forEach((c, i) => expect(c.zoom, `new chart ${i}`).toEqual([25, 75]));
    const before = joined.map((c) => c.points);
    await send(page, 2);
    (await miniCharts(page, NAME3)).forEach((c, i) => expect(c.points, `new chart ${i}: frozen with the rest`).toBe(before[i]));
  });

  test('survive a sort and a search, and are disposed on collapse', async ({ page }) => {
    await expand(page, NAME);
    const before = await miniCharts(page, NAME);
    await countCharts(page);
    const detailFollowsItsRow = () => page.evaluate((n) => {
      const row = document.querySelector(`#samplerTableBody tr[data-sampler="${CSS.escape(n)}"]`);
      return !!row && row.nextElementSibling?.dataset.detailFor === n;
    }, NAME);

    await page.click('#samplerTable thead th:nth-child(2)'); // sort
    expect(await detailFollowsItsRow(), 'after a sort').toBe(true);

    await page.fill('#samplerSearch', 'no-such-sampler');      // hides the row...
    await page.fill('#samplerSearch', '');                     // ...and brings it back
    await settled(page);                                       // any rebuild of its charts would be done
    expect(await detailFollowsItsRow(), 'after a search').toBe(true);
    const after = await miniCharts(page, NAME);
    expect(after.map((c) => c.id), 'the same charts').toEqual(before.map((c) => c.id));
    expect(after.every((c) => c.attached)).toBe(true);
    expect(await counts(page)).toEqual({ created: 0, disposed: 0 });

    await page.click(`#samplerTableBody tr[data-sampler="${NAME}"] td`); // collapse
    expect(await counts(page), 'collapsing disposes the three').toEqual({ created: 0, disposed: 3 });
    expect(await page.evaluate(() => Object.keys(S.detailCharts))).toEqual([]);
    expect(await page.locator('.detail-row').count()).toBe(0);
  });
});

/**
 * Samplers whose names look alike. The mini-charts' element ids and registry keys were built
 * from the name itself (hashName: anything but a-z, A-Z, 0-9 turned into _, cut at 40), so two
 * samplers could share them: names apart only in punctuation, long paths alike for their first
 * 40 characters, any two non-Latin names of one length. Expanding both left the second without
 * charts, and after one update the first drew the second's numbers (measured 2026-10-12).
 */
const PAIRS = [
  ['GET /a', 'GET-/a'],
  ['GET /api/v2/marketplace/sellers/12345/orders/list', 'GET /api/v2/marketplace/sellers/12345/orders/create'],
  ['בדיקה אחת', 'בדיקה שתי'], // two Hebrew names of one length
];
const ALIKE = PAIRS.flat();
const OWN_RT = Object.fromEntries(ALIKE.map((n, i) => [n, 100 * (i + 1)]));

function alikeCatchup() {
  const start = Math.floor((Date.now() - 10 * 60_000) / 2000) * 2000;
  const row = (n) => ({ samples: 10, errors: 0, avgResponseTime: OWN_RT[n], p50: OWN_RT[n], p90: OWN_RT[n], p95: OWN_RT[n], p99: OWN_RT[n],
    maxResponseTime: OWN_RT[n], throughput: 5, errorRate: 0, receivedBytesPerSec: 1000, avgConnectTime: 1, avgLatency: 1 });
  const slots = [];
  for (let i = 0; i < 30; i++) slots.push({ timestamp: start + i * 2000, overall: { samples: 60, avgResponseTime: 350, p95: 350, maxResponseTime: 600,
    throughput: 30, errorRate: 0, activeThreads: 5 }, samplers: Object.fromEntries(ALIKE.map((n) => [n, row(n)])) });
  return { type: 'catchup', testName: 'Alike', testStartTime: start, testRunning: true, testEndTime: 0, interval: 2000, maxRetentionMs: 3_600_000,
    overviewInterval: 60_000, knownSamplers: ALIKE, hiddenSamplers: [], slaConfig: {}, slaStatus: {}, recentErrors: [], errorSummary: { byCode: {}, bySampler: {} },
    cumulativeStats: { totalSamples: 1800 }, samplerStats: Object.fromEntries(ALIKE.map((n) => [n, { ...row(n), samples: 300 }])), recentBuckets: slots };
}

test.describe('Expanded sampler charts, names that look alike', () => {
  let dialogs;
  test.beforeEach(async ({ page }) => {
    dialogs = [];
    page.on('dialog', (d) => { dialogs.push(d.message()); d.dismiss(); });
    await openDashboard(page);
    const c = alikeCatchup();
    await page.evaluate(async (p) => {
      await fetch('/test/send-catchup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p) });
    }, c);
    await page.waitForFunction((n) => S.knownSamplers.length === n, ALIKE.length);
    await page.click('.tab-btn[data-tab="samplers"]');
  });

  for (const [a, b] of PAIRS) {
    test(`"${a}" and "${b}" each get their own charts and numbers`, async ({ page }) => {
      await expand(page, a);
      await expand(page, b);
      const [ca, cb] = [await miniCharts(page, a), await miniCharts(page, b)];
      expect(ca.every(Boolean) && cb.every(Boolean), 'both rows have their three charts').toBe(true);
      expect(new Set([...ca, ...cb].map((c) => c.id)).size, 'six distinct charts').toBe(6);

      const lastRt = (n) => page.evaluate((name) => {
        const el = document.querySelector(`tr.detail-row[data-detail-for="${CSS.escape(name)}"] .detail-chart`);
        const d = echarts.getInstanceByDom(el).getOption().series[0].data;
        return d[d.length - 1][1];
      }, n);
      await sendToPage(page, 'bucket', { type: 'bucket', timestamp: Date.now(), overall: { samples: 60 }, samplers: Object.fromEntries(ALIKE.map((n) => [n, { samples: 10, avgResponseTime: OWN_RT[n], p95: OWN_RT[n], throughput: 5, errorRate: 0 }])), testRunning: true });
      expect(await lastRt(a), `"${a}" draws its own response time`).toBe(OWN_RT[a]);
      expect(await lastRt(b), `"${b}" draws its own response time`).toBe(OWN_RT[b]);

      await page.click(`#samplerTableBody tr[data-sampler="${a}"] td`); // collapse the first
      expect((await miniCharts(page, b)).every((c) => c && c.attached), `"${b}" keeps its charts`).toBe(true);
      expect(await lastRt(b)).toBe(OWN_RT[b]);
    });
  }

  test('any number of samplers can be expanded at once, with no popup', async ({ page }) => {
    for (const n of ALIKE) await expand(page, n);
    expect(dialogs, 'no popup').toEqual([]);
    expect(await page.locator('#samplerTableBody tr.detail-row').count(), 'a detail row each').toBe(ALIKE.length);
    for (const n of ALIKE) expect((await miniCharts(page, n)).every((c) => c && c.attached), `"${n}" has its three charts`).toBe(true);
  });
});
