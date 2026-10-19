// @ts-check
const { test, expect } = require('./fixtures');
const { sendToPage, settled } = require('./setup/dashboard');
const fs = require('fs');
const path = require('path');

/**
 * Two zoom groups, kept apart by decision (2026-10-12):
 *   - the Timeline's five charts zoom together, and a zoom stops the live view (EXPLORING)
 *     until Return to Live;
 *   - the Samplers tab's mini-charts zoom together and hold still while zoomed, on their own.
 * Zooming in one group never moves the other, or stops the other from following live data.
 */

const TIMELINE = ['responseTimeChart', 'throughputChart', 'errorRateChart', 'bytesChart', 'connectLatencyChart'];
const catchup = JSON.parse(fs.readFileSync(path.join(__dirname, 'fixtures', 'catchup-basic.json'), 'utf8'));
const NAME = Object.keys(catchup.samplerStats)[0];
const last = catchup.recentBuckets[catchup.recentBuckets.length - 1];
let ts = last.timestamp;

async function send(page) {
  await sendToPage(page, 'bucket', { type: 'bucket', timestamp: (ts += 2000), overall: last.overall, samplers: last.samplers,
    samplerStats: catchup.samplerStats, cumulative: catchup.cumulativeStats, testRunning: true });
}
/** The zoom (start, end %) and point count of each chart in a list of charts. */
const timeline = (page) => page.evaluate((ids) => ids.map((id) => {
  const o = S.charts[id].getOption();
  return { zoom: [Math.round(o.dataZoom[0].start), Math.round(o.dataZoom[0].end)], points: o.series[0]?.data.length ?? 0 };
}), TIMELINE);
const minis = (page) => page.evaluate((n) => [0, 1, 2].map((i) => {
  const o = [...(document.querySelector(`tr.detail-row[data-detail-for="${CSS.escape(n)}"]`)?.querySelectorAll('.detail-chart') || [])].map((el) => echarts.getInstanceByDom(el))[i].getOption();
  return { zoom: [Math.round(o.dataZoom[0].start), Math.round(o.dataZoom[0].end)], points: o.series[0].data.length };
}), NAME);
const zoomTimeline = (page, id, a, b) => page.evaluate(([c, s, e]) => S.charts[c].dispatchAction({ type: 'dataZoom', start: s, end: e }), [id, a, b]);
const zoomMini = (page, kind, a, b) => page.evaluate(([n, i, s, e]) => [...(document.querySelector(`tr.detail-row[data-detail-for="${CSS.escape(n)}"]`)?.querySelectorAll('.detail-chart') || [])].map((el) => echarts.getInstanceByDom(el))[i].dispatchAction({ type: 'dataZoom', start: s, end: e }), [NAME, { 'detail-rt-': 0, 'detail-tp-': 1, 'detail-er-': 2 }[kind], a, b]);

test.describe('Zoom groups', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('#liveLabel');
    await page.waitForFunction(() => window.S && S.testRunning && Object.keys(S.samplerStats).length > 0);
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page); // the Timeline's charts are drawn while on screen
  });

  test("the Timeline's charts zoom together and stop the live view until Return to Live", async ({ page }) => {
    const before = await timeline(page);
    await zoomTimeline(page, 'throughputChart', 30, 60);
    (await timeline(page)).forEach((c, i) => expect(c.zoom, `${TIMELINE[i]}: zooming one zooms all five`).toEqual([30, 60]));
    expect(await page.evaluate(() => S.liveMode)).toBe(false);
    await expect(page.locator('#returnLiveBtn')).toBeVisible();
    await expect(page.locator('#liveLabel')).toHaveText('EXPLORING');

    await send(page);
    (await timeline(page)).forEach((c, i) => {
      expect(c.points, `${TIMELINE[i]}: holds still while exploring`).toBe(before[i].points);
      expect(c.zoom).toEqual([30, 60]);
    });

    await page.click('#returnLiveBtn');
    expect(await page.evaluate(() => S.liveMode)).toBe(true);
    await expect(page.locator('#returnLiveBtn')).toBeHidden();
    (await timeline(page)).forEach((c, i) => {
      expect(c.zoom, `${TIMELINE[i]}: back to the whole range`).toEqual([0, 100]);
      expect(c.points, `${TIMELINE[i]}: live again, with the update it held back`).toBe(before[i].points + 1);
    });
  });

  test("a mini-chart zoom and a Timeline zoom never move each other's charts", async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    await page.click(`#samplerTableBody tr[data-sampler="${NAME}"] td`);
    await settled(page); // its mini-charts are built

    // A mini-chart zoom: the mini-charts zoom together; the Timeline stays whole and live.
    await zoomMini(page, 'detail-tp-', 30, 50);
    (await minis(page)).forEach((c, i) => expect(c.zoom, `mini-chart ${i}`).toEqual([30, 50]));
    (await timeline(page)).forEach((c, i) => expect(c.zoom, `${TIMELINE[i]} is not moved by a mini-chart`).toEqual([0, 100]));
    expect(await page.evaluate(() => S.liveMode), 'the Timeline stays live').toBe(true);
    // (a hidden tab's charts are drawn when it is shown, so watch the Timeline on its own tab)
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page); // the tab's own redraw is over: only the update can add a point
    const tl = await timeline(page);
    await send(page);
    (await timeline(page)).forEach((c, i) => expect(c.points, `${TIMELINE[i]} still follows live data`).toBe(tl[i].points + 1));

    // A Timeline zoom: the Timeline zooms and stops; the mini-charts stay whole and live.
    await zoomMini(page, 'detail-tp-', 0, 100);
    await zoomTimeline(page, 'responseTimeChart', 60, 90);
    (await timeline(page)).forEach((c, i) => expect(c.zoom, TIMELINE[i]).toEqual([60, 90]));
    expect(await page.evaluate(() => S.liveMode)).toBe(false);
    await page.click('.tab-btn[data-tab="samplers"]');
    const mini = await minis(page);
    mini.forEach((c, i) => expect(c.zoom, `mini-chart ${i} is not moved by the Timeline`).toEqual([0, 100]));
    await send(page);
    const slots = await page.evaluate(() => S.timestamps.length);
    (await minis(page)).forEach((c, i) => expect(c.points, `mini-chart ${i} still follows live data, caught up`).toBe(slots));
  });
});
