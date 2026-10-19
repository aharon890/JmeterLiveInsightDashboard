// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage, settled } = require('./setup/dashboard');

// The server keeps the live window (2 s slots) for its retention period only,
// and the whole run at one minute. Where the live window no longer reaches,
// the charts must show the minute slots instead of starting mid-run.

const T0 = 1711900000000 - (1711900000000 % 60000);
const SAMPLER = 'GET /api/products';

function slot(ts, samples, p95) {
  const overall = {
    samples, errors: 0, avgResponseTime: p95 / 2, p50: p95 / 2, p90: p95 * 0.9, p95, p99: p95 * 1.2,
    throughput: samples / 2, errorRate: 0, receivedBytesPerSec: 1000, sentBytesPerSec: 100,
    activeThreads: 5, minResponseTime: 1, maxResponseTime: p95 * 2, avgConnectTime: 1, avgLatency: 1,
  };
  return { timestamp: ts, overall, samplers: { [SAMPLER]: { ...overall } } };
}

function minute(ts, p95) {
  const b = slot(ts, 600, p95);
  b.overall.throughput = 10;
  b.samplers[SAMPLER].throughput = 10;
  return b;
}

function catchup(extra) {
  return {
    type: 'catchup', testName: 'Overview', testStartTime: T0, testRunning: true, testEndTime: 0,
    interval: 2000, knownSamplers: [SAMPLER], hiddenSamplers: [], slaConfig: {}, slaStatus: {},
    cumulativeStats: { totalSamples: 1, totalErrors: 0, overallThroughput: 1, maxThreads: 5, activeThreads: 5 },
    samplerStats: {}, recentErrors: [], errorSummary: { byCode: {}, bySampler: {} }, ...extra,
  };
}

async function send(page, path, body) {
  await page.evaluate(([p, b]) => fetch(p, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b),
  }), [path, body]);
}

test.describe('Whole-run overview', () => {
  test('a reload past the live window charts the whole run', async ({ page }) => {
    await openDashboard(page);
    // Live window: the last 2 minutes (from minute 10). Overview: minutes 0-11.
    const live = [];
    for (let i = 0; i < 60; i++) live.push(slot(T0 + 600000 + i * 2000, 20, 300));
    const minutes = [];
    for (let m = 0; m < 12; m++) minutes.push(minute(T0 + m * 60000, 100 + m));
    await sendToPage(page, 'catchup', catchup({
      maxRetentionMs: 120000, recentBuckets: live, overviewBuckets: minutes, overviewInterval: 60000,
    }));

    const state = await page.evaluate(() => ({
      head: S.ovHead, first: S.timestamps[0], n: S.timestamps.length,
      p95: S.overall.p95.slice(0, 11), sampler: S.samplers['GET /api/products'].p95.slice(0, 11),
      sorted: S.timestamps.every((t, i, a) => i === 0 || a[i - 1] < t),
    }));
    // Minutes 0-9 end at or before the first live point; 10 and 11 overlap it
    expect(state.head).toBe(10);
    expect(state.first).toBe(T0);
    expect(state.n).toBe(70);
    expect(state.sorted).toBe(true);
    expect(state.p95.slice(0, 10)).toEqual([100, 101, 102, 103, 104, 105, 106, 107, 108, 109]);
    expect(state.p95[10]).toBe(300);
    expect(state.sampler.slice(0, 10)).toEqual([100, 101, 102, 103, 104, 105, 106, 107, 108, 109]);

    // The throughput chart (Full window) starts at the run's first minute
    await page.click('.tab-btn[data-tab="timeline"]');
    await settled(page);
    const firstX = await page.evaluate(() => {
      const opt = S.charts.throughputChart.getOption();
      const total = opt.series.find(s => s.data && s.data.length);
      return total.data[0][0];
    });
    expect(firstX).toBe(T0);
  });

  test('minutes trimmed out of the live window are replaced by overview points', async ({ page }) => {
    await openDashboard(page);
    // A 20 s window: the cap keeps 10 + 10 live points
    await sendToPage(page, 'catchup', catchup({ maxRetentionMs: 20000, recentBuckets: [] }));
    // 3 minutes of 2 s slots. Each minute's overview arrives with the slot
    // message that closes one slot after the minute ends.
    for (let i = 0; i < 90; i++) {
      const b = slot(T0 + i * 2000, 20, 300);
      b.testRunning = true;
      if (i > 30 && (i - 1) % 30 === 0) {
        const m = Math.floor((i - 1) / 30) - 1;
        b.overview = [minute(T0 + m * 60000, 100 + m)];
        b.overviewInterval = 60000;
      }
      await send(page, '/test/send-bucket', b);
    }
    await page.waitForFunction((last) => S.timestamps[S.timestamps.length - 1] === last, T0 + 89 * 2000);
    const state = await page.evaluate(() => ({
      head: S.ovHead, n: S.timestamps.length, first: S.timestamps[0], overview: S.overview.length,
      firstLive: S.timestamps[S.ovHead], p95: S.overall.p95.slice(0, S.ovHead),
      sorted: S.timestamps.every((t, i, a) => i === 0 || a[i - 1] < t),
    }));
    expect(state.overview).toBe(2);           // minutes 0 and 1 have closed
    expect(state.firstLive).toBe(T0 + 70 * 2000);
    expect(state.head).toBe(2);               // both end before the first live point
    expect(state.n).toBe(2 + 20);
    expect(state.first).toBe(T0);
    expect(state.p95).toEqual([100, 101]);
    expect(state.sorted).toBe(true);
  });
});
