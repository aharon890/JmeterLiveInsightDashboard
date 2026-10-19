// @ts-check
const { test, expect } = require('./fixtures');
const { sendToPage } = require('./setup/dashboard');

/**
 * Timeline points at a coarser granularity (the 6s/10s/30s/1m buttons, and Auto beyond
 * 5 minutes) must be what JMeter's own over-time graphs plot. JMeter's HTML report and the
 * jp@gc graphs put a sample in the fixed clock period [kG, (k+1)G) that holds its end time
 * (TimeStampKeysSelector: time - time % granularity), and compute the point from that period
 * alone. The server already slots samples that way; a coarser point is therefore the 2 s slots
 * that START in one clock period, and once the period is over it never changes.
 *
 * The page used to group the slots that happened to be in view, counting from the first one,
 * so every group moved by one slot on each update while the window slid: a spike's height and
 * position changed with no new data about it (365 to 505 ms over 16 updates, measured 2026-10-11).
 *
 * `expected()` below is the definition, written independently of the page:
 *   - a point is plotted at the start of its period, as JMeter's report does;
 *   - count-based values are exact: mean = sum of (mean x samples) / samples, error % = errors /
 *     samples, max = the maximum, rate = samples / the period's length, as JMeter's report divides
 *     (TimeRateAggregator: count x 1000 / granularity), a slot in which the sampler had no sample
 *     counting as 0 samples. Only a period still filling while the run is live divides by the
 *     seconds it has covered so far;
 *   - a percentile cannot be rebuilt from slot percentiles. At 1m it is the minute's exact value
 *     from the server's minute tier; at 6/10/30 s it stays a sample-weighted average of the slot
 *     percentiles, an approximation the page says it is;
 *   - minutes older than the live 2 s slots arrive as 1-minute points and are drawn as they are,
 *     and so is the minute the live slots begin in once the server has pruned part of it.
 */

const IV = 2000;
const MIN = 60_000;

/** One sampler's numbers in one slot. */
function row(n, { mean = 200, max = mean * 2, p95 = mean * 1.5, errors = 0 } = {}) {
  return { samples: n, errors, avgResponseTime: mean, p50: mean, p90: p95 * 0.9, p95, p99: p95 * 1.2, maxResponseTime: max,
    throughput: n / (IV / 1000), errorRate: n ? (errors / n) * 100 : 0, receivedBytesPerSec: n * 1000, sentBytesPerSec: n * 100,
    avgConnectTime: 5, avgLatency: mean / 2 };
}

/** A slot whose overall numbers are consistent with its samplers', as the server's are. */
function slotOf(ts, samplers) {
  const rows = Object.values(samplers);
  const n = rows.reduce((a, r) => a + r.samples, 0);
  const w = (m) => (n ? rows.reduce((a, r) => a + r[m] * r.samples, 0) / n : 0);
  const overall = { samples: n, errors: rows.reduce((a, r) => a + r.errors, 0), avgResponseTime: w('avgResponseTime'),
    p50: w('p50'), p90: w('p90'), p95: w('p95'), p99: w('p99'),
    maxResponseTime: rows.reduce((a, r) => Math.max(a, r.maxResponseTime), 0), throughput: n / (IV / 1000),
    errorRate: n ? (rows.reduce((a, r) => a + r.errors, 0) / n) * 100 : 0, activeThreads: 10,
    receivedBytesPerSec: n * 1000, sentBytesPerSec: n * 100, avgConnectTime: 5, avgLatency: w('avgLatency') };
  return { timestamp: ts, overall, samplers };
}

/** A slot with no samples, as the server sends it: value metrics unknown (a gap), rates 0. */
function emptySlot(ts) {
  return { timestamp: ts, samplers: {}, overall: { samples: 0, errors: 0, avgResponseTime: null, minResponseTime: null,
    maxResponseTime: null, p50: null, p90: null, p95: null, p99: null, throughput: 0, receivedBytesPerSec: 0,
    sentBytesPerSec: 0, activeThreads: null, errorRate: 0, avgConnectTime: null, avgLatency: null } };
}

function catchupOf({ slots, minutes = [], names, running = false, endTime = 0, interval = IV }) {
  const last = slots[slots.length - 1].overall;
  return { type: 'catchup', testName: 'Grouping', testStartTime: (minutes[0] || slots[0]).timestamp, testRunning: running,
    testEndTime: running ? 0 : endTime || slots[slots.length - 1].timestamp + 845, interval, maxRetentionMs: 3_600_000,
    overviewInterval: MIN, knownSamplers: names, hiddenSamplers: [], slaConfig: {}, slaStatus: {}, recentErrors: [],
    errorSummary: { byCode: {}, bySampler: {} }, cumulativeStats: { totalSamples: 1, totalErrors: 0, p95: last.p95 },
    samplerStats: {}, recentBuckets: slots, overviewBuckets: minutes };
}

/**
 * What a point must be, by the definitions above, for an ended run (every period closed).
 * `name` null = the Σ Total line; `minutes`, the server's closed minutes, give a 1m percentile.
 */
function expected(slots, G, name, metric, minutes = []) {
  const groups = new Map();
  for (const s of slots) {
    const k = Math.floor(s.timestamp / G) * G;
    (groups.get(k) || groups.set(k, []).get(k)).push(s);
  }
  return [...groups].sort((a, b) => a[0] - b[0]).map(([k, ss]) => {
    const rows = ss.map((s) => (name ? s.samplers[name] : s.overall)).filter(Boolean);
    const n = rows.reduce((a, r) => a + r.samples, 0);
    let v = null;
    if (rows.length) {
      const maxes = rows.map((r) => r.maxResponseTime).filter((x) => x != null);
      if (metric === 'throughput') v = n / (G / 1000);
      else if (metric === 'maxResponseTime') v = maxes.length ? Math.max(...maxes) : null;
      else if (metric === 'errorRate') v = n ? (rows.reduce((a, r) => a + r.errors, 0) / n) * 100 : null;
      else v = n ? rows.reduce((a, r) => a + (r.samples ? r[metric] * r.samples : 0), 0) / n : null;
    }
    const minute = G === MIN && /^p\d\d$/.test(metric) && minutes.find((m) => m.timestamp === k);
    if (minute) v = (name ? minute.samplers[name] : minute.overall)?.[metric] ?? null;
    return [k, v];
  });
}

async function load(page, catchup) {
  await page.goto('/');
  await page.waitForSelector('#liveLabel');
  // the mock server sends the catch-up to connected tabs only
  await page.waitForFunction(() => window.S && S.connected);
  await page.evaluate(async (p) => {
    await fetch('/test/send-catchup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p) });
  }, catchup);
  await page.waitForFunction((n) => window.S && S.timestamps.length >= n, catchup.recentBuckets.length);
  await page.click('.tab-btn[data-tab="timeline"]');
  // The Σ Total line starts hidden; these tests check it too.
  await page.evaluate(() => { S.samplerVisibility[TOTAL_NAME] = true; buildFilterBar(); });
}

// Clicks the real buttons, then draws at once: the page redraws 150 ms after a click (debouncedRefresh),
// and a fixed 350 ms sleep per switch was 61% of the slowest test, 27 s of a 30 s limit.
async function choose(page, { gran, metric, window: win }) {
  if (win !== undefined) await page.click(`#windowToggle [data-window="${win}"]`);
  if (metric) await page.click(`[data-metric="${metric}"]`);
  if (gran !== undefined) await page.click(`#granularityToggle [data-gran="${gran}"]`);
  await page.evaluate(() => { clearTimeout(refreshTimer); refreshChartsForce(); });
}

const METRICS = ['avgResponseTime', 'maxResponseTime', 'p95', 'throughput', 'errorRate'];
const CHART = { avgResponseTime: 'responseTimeChart', maxResponseTime: 'responseTimeChart', p95: 'responseTimeChart',
  throughput: 'throughputChart', errorRate: 'errorRateChart' };

/** The points a chart line plots: [[time, value], ...], or null when there is no such line. */
async function plotted(page, metric, line) {
  return page.evaluate(([chart, name]) => {
    const s = S.charts[chart].getOption().series.find((x) => x.name === name);
    return s ? s.data.map((p) => [p[0], p[1] == null ? null : p[1]]) : null;
  }, [CHART[metric], line]);
}

function same(got, want, label) {
  expect(got, `${label}: no such line`).not.toBeNull();
  expect(got.map((p) => p[0]), `${label}: point times`).toEqual(want.map((p) => p[0]));
  got.forEach((p, i) => {
    const w = want[i][1];
    if (w == null) expect(p[1], `${label} @${i}`).toBeNull();
    else expect(p[1], `${label} @${i} (${new Date(p[0]).toISOString().slice(11, 19)})`).toBeCloseTo(w, 6);
  });
}

/** A seeded generator, so a failing random run can be replayed. */
function rng(seed) {
  return () => { seed = (seed + 0x6d2b79f5) | 0; let t = seed; t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

// A 4-minute ended run starting 14 s into a minute, so the first period of every size is partial.
// A runs in every slot; B, sparse, has one sample every 10 s; an error burst and a slow stretch.
function fixedRun() {
  const start = Math.floor((Date.now() - 30 * MIN) / MIN) * MIN + 14_000;
  const slots = [];
  for (let i = 0; i < 120; i++) {
    const ts = start + i * IV;
    const slow = i >= 40 && i < 43;
    const samplers = { A: row(slow ? 4 : 20, { mean: slow ? 3000 : 180 + (i % 7) * 10, max: slow ? 5000 : 400, errors: i >= 60 && i < 62 ? 10 : 0 }) };
    if (i % 5 === 0) samplers.B = row(1, { mean: 900, max: 900 });
    slots.push(slotOf(ts, samplers));
  }
  return slots;
}

test.describe('Timeline grouping follows the clock, as JMeter does', () => {
  test('each point is one clock period, computed from the slots in it @smoke', async ({ page }) => {
    const slots = fixedRun();
    await load(page, catchupOf({ slots, names: ['A', 'B'] }));
    for (const g of [3, 5, 15, 30]) {
      for (const metric of METRICS) {
        await choose(page, { gran: String(g), metric: CHART[metric] === 'responseTimeChart' ? metric : undefined, window: '0' });
        for (const [line, name] of [['A', 'A'], ['B', 'B'], ['Σ Total', null]]) {
          const got = await plotted(page, metric, line);
          if (got === null && metric === 'errorRate' && name === null) continue; // no Σ line on that chart
          same(got, expected(slots, g * IV, name, metric), `${metric} ${line} at ${g * 2}s`);
        }
      }
    }
  });

  test("a sparse sampler's throughput is its samples per second", async ({ page }) => {
    const slots = fixedRun();
    await load(page, catchupOf({ slots, names: ['A', 'B'] }));
    await choose(page, { gran: '15', window: '0' });
    const got = await plotted(page, 'throughput', 'B');
    // One sample every 10 s is 0.1/s. Averaging only the slots it appeared in read 0.5/s.
    const interior = got.slice(1, -1).map((p) => p[1]);
    expect(interior.length).toBeGreaterThan(3);
    interior.forEach((v) => expect(v).toBeCloseTo(0.1, 9));
  });

  test("at 1m a percentile is the minute's exact value from the server", async ({ page }) => {
    const slots = fixedRun();
    // The server's minute tier: exact percentiles per minute, deliberately unlike any average
    // of the slots' values, so the test can tell which one was drawn.
    const minutes = [];
    for (let m = Math.ceil(slots[0].timestamp / MIN) * MIN; m + MIN <= slots[slots.length - 1].timestamp; m += MIN) {
      const p95 = 1234 + m / MIN % 97;
      minutes.push({ timestamp: m, overall: { samples: 1, p95 }, samplers: { A: { samples: 1, p95: p95 + 1 } } });
    }
    await load(page, catchupOf({ slots, minutes, names: ['A', 'B'] }));
    await choose(page, { gran: '30', metric: 'p95', window: '0' });
    for (const [line, key] of [['Σ Total', 'overall'], ['A', 'A']]) {
      const got = new Map(await plotted(page, 'p95', line));
      for (const m of minutes) {
        const want = key === 'overall' ? m.overall.p95 : m.samplers.A.p95;
        expect(got.get(m.timestamp), `${line} at ${new Date(m.timestamp).toISOString().slice(11, 16)}`).toBe(want);
      }
    }
  });

  // At the default 2 s every period divides a minute, so a clock period never holds more than
  // one older minute. At 5 s the 30s and 1m buttons stand for 75 s and 150 s: grouping those
  // minutes would merge them and move them off their minute; they must stay as they are.
  for (const interval of [2000, 5000]) {
    test(`minutes older than the live window are drawn one point per minute (update interval ${interval} ms)`, async ({ page }) => {
      // 10 older minutes, then 4 minutes of slots, as a run longer than the retention arrives.
      // The live part starts 2 minutes past a 5-minute mark, so a 75 s or 150 s period that
      // holds its first slot starts before it (on a 5-minute mark it would not, by chance).
      const firstLive = Math.floor((Date.now() - 20 * MIN) / (5 * MIN)) * 5 * MIN + 2 * MIN;
      const minutes = [];
      for (let i = 10; i >= 1; i--) {
        const r = row(600 + i, { mean: 100 + i });
        minutes.push({ timestamp: firstLive - i * MIN, overall: { ...slotOf(0, { A: r }).overall, throughput: (600 + i) / 60 },
          samplers: { A: { ...r, throughput: (600 + i) / 60 } } });
      }
      const slots = [];
      for (let ts = firstLive; ts < firstLive + 4 * MIN; ts += interval) slots.push(slotOf(ts, { A: row(20) }));
      await load(page, catchupOf({ slots, minutes, names: ['A'], interval }));
      for (const g of ['0', '15', '30']) {
        await choose(page, { gran: g, metric: 'avgResponseTime', window: '0' });
        for (const metric of ['avgResponseTime', 'throughput']) {
          const line = await plotted(page, metric, 'A');
          const head = line.slice(0, minutes.length);
          expect(head.map((p) => p[0]), `${metric}, granularity ${g}`).toEqual(minutes.map((m) => m.timestamp));
          expect(head.map((p) => p[1])).toEqual(minutes.map((m) => m.samplers.A[metric]));
          // The live part starts after the last minute, never inside it, and the line only moves forward.
          expect(line[minutes.length][0], `${metric}, granularity ${g}: first live point`).toBeGreaterThanOrEqual(firstLive);
          line.forEach((p, i) => { if (i) expect(p[0], `${metric}, granularity ${g}: point ${i} after point ${i - 1}`).toBeGreaterThan(line[i - 1][0]); });
        }
      }
    });
  }

  test('points already drawn do not change as the window slides', async ({ page }) => {
    let now = Math.floor(Date.now() / IV) * IV + 900;
    await page.clock.setFixedTime(now);
    const slots = [];
    for (let ts = now - 900 - 10 * MIN; ts < now - 900; ts += IV) {
      const i = slots.length;
      slots.push(slotOf(ts, { A: row(i % 9 === 0 ? 4 : 20, { mean: i % 9 === 0 ? 2500 : 200 + (i % 13) }) }));
    }
    await load(page, catchupOf({ slots, names: ['A'], running: true }));
    await choose(page, { gran: '15', metric: 'avgResponseTime', window: '300' });
    let before = await plotted(page, 'avgResponseTime', 'A');
    for (let u = 0; u < 16; u++) {
      const ts = slots[slots.length - 1].timestamp + IV;
      const b = slotOf(ts, { A: row(20, { mean: 210 + u }) });
      slots.push(b);
      now += IV;
      await page.clock.setFixedTime(now);
      await sendToPage(page, 'bucket', { ...b, type: 'bucket', testRunning: true });
      const after = await plotted(page, 'avgResponseTime', 'A');
      after.forEach((p) => expect(p[0] % (15 * IV), 'a point starts on a 30 s boundary').toBe(0));
      // Every period that was already over before this update must read exactly as before.
      const closedBefore = Math.floor(ts / (15 * IV)) * (15 * IV);
      const was = new Map(before.filter((p) => p[0] < closedBefore));
      for (const [t, v] of after) if (was.has(t)) expect(v, `update ${u + 1}, point ${new Date(t).toISOString().slice(11, 19)}`).toBe(was.get(t));
      before = after;
    }
  });

  test('random runs: every point matches the definitions', async ({ page }) => {
    for (const seed of [11, 23, 37]) {
      const r = rng(seed);
      const start = Math.floor((Date.now() - 40 * MIN) / IV) * IV + Math.floor(r() * 30) * IV;
      const slots = [];
      for (let i = 0; i < 150 + Math.floor(r() * 150); i++) {
        const samplers = {};
        for (const name of ['A', 'B', 'C']) {
          if (r() < (name === 'C' ? 0.15 : 0.85)) {
            const n = 1 + Math.floor(r() * 40);
            samplers[name] = row(n, { mean: 50 + r() * 2000, max: 2500 + r() * 5000, errors: r() < 0.1 ? Math.floor(r() * n) : 0 });
          }
        }
        if (!Object.keys(samplers).length) samplers.A = row(1);
        slots.push(slotOf(start + i * IV, samplers));
      }
      // The server's closed minutes, with percentiles no average of the slots would give: drawn
      // at 1m, and never at 6/10/30 s.
      const minutes = [];
      for (let m = Math.floor(start / MIN) * MIN; m <= slots[slots.length - 1].timestamp; m += MIN) {
        const pick = () => ({ samples: 1, p50: 7000 + r(), p90: 8000 + r(), p95: 9000 + r(), p99: 9900 + r() });
        minutes.push({ timestamp: m, overall: pick(), samplers: { A: pick(), B: pick(), C: pick() } });
      }
      await load(page, catchupOf({ slots, minutes, names: ['A', 'B', 'C'] }));
      for (const g of [[3, 5][Math.floor(r() * 2)], 15, 30]) {
        for (const metric of METRICS) {
          await choose(page, { gran: String(g), metric: CHART[metric] === 'responseTimeChart' ? metric : undefined, window: '0' });
          for (const [line, name] of [['A', 'A'], ['B', 'B'], ['C', 'C'], ['Σ Total', null]]) {
            const got = await plotted(page, metric, line);
            if (got === null && metric === 'errorRate' && name === null) continue;
            same(got, expected(slots, g * IV, name, metric, minutes), `seed ${seed}: ${metric} ${line} at ${g * 2}s`);
          }
        }
      }
    }
  });

  test('the minute the live slots begin in is drawn whole, and stays put as they are pruned', async ({ page }) => {
    // A run longer than the retention: the server keeps 2 s slots for the last minute or so
    // and the whole run in minutes. Its live tier has been pruned 40 s into minute m, whose
    // first 40 s were slow. Grouping the 2 s slots that are left drew part of that minute as
    // the minute, from fast slots only, and redrew it on every update as pruning moved on.
    const m = Math.floor((Date.now() - 30 * MIN) / (5 * MIN)) * 5 * MIN + 2 * MIN;
    const minuteRow = (n, o) => ({ ...row(n, o), throughput: n / 60 });
    const minuteOf = (ts, r) => ({ timestamp: ts, overall: { ...slotOf(0, { A: r }).overall, throughput: r.throughput }, samplers: { A: r } });
    const minutes = [];
    for (let i = 10; i >= 1; i--) minutes.push(minuteOf(m - i * MIN, minuteRow(600, { mean: 150 + i, max: 600, p95: 400 })));
    minutes.push(minuteOf(m, minuteRow(2220, { mean: 2100, max: 5000, p95: 4800, errors: 66 })));
    const slots = [];
    for (let ts = m + 40_000; ts <= m + 118_000; ts += IV) {
      const late = ts >= m + MIN;
      slots.push(slotOf(ts, { A: row(20, { mean: late ? 210 + (ts / IV) % 5 : 200, max: late ? 450 : 400, p95: late ? 320 : 300 }) }));
    }
    // Minute m+1, built from its own slots as the server would, closes when its last slot has.
    const next = slots.filter((x) => x.timestamp >= m + MIN).map((x) => x.samplers.A);
    const n1 = next.reduce((a, x) => a + x.samples, 0);
    const minute1 = minuteOf(m + MIN, { ...minuteRow(n1, { mean: next.reduce((a, x) => a + x.avgResponseTime * x.samples, 0) / n1,
      max: Math.max(...next.map((x) => x.maxResponseTime)), p95: 333 }) });

    let now = m + 120_900;
    await page.clock.setFixedTime(now);
    const catchup = catchupOf({ slots, minutes, names: ['A'], running: true });
    catchup.maxRetentionMs = 60_000; // the page then keeps 40 live slots, as the server keeps its last minute or so
    await load(page, catchup);

    for (const g of ['15', '30']) {
      await choose(page, { gran: g, metric: 'avgResponseTime', window: '0' });
      const byMetric = {};
      for (const metric of ['avgResponseTime', 'maxResponseTime', 'p95', 'throughput']) {
        if (CHART[metric] === 'responseTimeChart') await choose(page, { metric });
        for (const line of ['A', 'Σ Total']) {
          const got = await plotted(page, metric, line);
          const at = new Map(got);
          const last = minutes[minutes.length - 1], want = line === 'A' ? last.samplers.A : last.overall;
          expect(at.get(m), `${line} ${metric} at granularity ${g}: the minute the live slots begin in`)
            .toBeCloseTo(metric === 'throughput' ? want.throughput : want[metric], 9);
          expect(got.filter((p) => p[0] > m && p[0] < m + MIN), `${line} ${metric}: nothing else inside that minute`).toEqual([]);
          if (line === 'A') byMetric[metric] = at;
        }
      }
      for (const [t, p95] of byMetric.p95) {
        expect(p95, `granularity ${g}, ${new Date(t).toISOString().slice(11, 19)}: P95 within Max`).toBeLessThanOrEqual(byMetric.maxResponseTime.get(t));
      }
    }

    // Pruning moves the live slots' start 2 s per update, past the end of minute m after 10 updates.
    // At 1m, every period that is over must read the same throughout, the seam included.
    await choose(page, { gran: '30', metric: 'avgResponseTime' });
    const read = async () => ({ mean: await plotted(page, 'avgResponseTime', 'A'), rate: await plotted(page, 'throughput', 'A') });
    let before = await read();
    for (let u = 1; u <= 14; u++) {
      const ts = slots[slots.length - 1].timestamp + IV;
      const b = slotOf(ts, { A: row(20, { mean: 230, max: 460, p95: 330 }) });
      slots.push(b);
      now += IV;
      await page.clock.setFixedTime(now);
      const msg = { ...b, type: 'bucket', testRunning: true };
      if (ts === m + 2 * MIN) Object.assign(msg, { overview: [minute1], overviewInterval: MIN });
      await sendToPage(page, 'bucket', msg);
      const after = await read();
      const closedBefore = Math.floor(ts / MIN) * MIN;
      for (const key of ['mean', 'rate']) {
        const was = new Map(before[key].filter((p) => p[0] < closedBefore));
        expect([...was.keys()].every((t) => after[key].some((p) => p[0] === t)), `update ${u}: a ${key} point disappeared`).toBe(true);
        for (const [t, v] of after[key]) {
          if (was.has(t)) expect(v, `update ${u}, ${key} at ${new Date(t).toISOString().slice(11, 19)}`).toBeCloseTo(was.get(t), 9);
        }
      }
      before = after;
    }
  });
  // The seam minute is a point only where the window reaches into it. On a window that starts
  // after it, 1.6.0 appended it after the newest point: every line ran back to it and the axis
  // stretched to include it, on a run longer than the live retention.
  test('a window that starts after the seam minute does not draw it', async ({ page }) => {
    const m = Math.floor((Date.now() - 30 * MIN) / (5 * MIN)) * 5 * MIN + 2 * MIN;
    const minuteRow = (n, o) => ({ ...row(n, o), throughput: n / 60 });
    const minuteOf = (ts, r) => ({ timestamp: ts, overall: { ...slotOf(0, { A: r }).overall, throughput: r.throughput }, samplers: { A: r } });
    const minutes = [];
    for (let i = 10; i >= 0; i--) minutes.push(minuteOf(m - i * MIN, minuteRow(600, { mean: 150 + i, max: 600, p95: 400 })));
    const slots = [];
    for (let ts = m + 40_000; ts <= m + 178_000; ts += IV) slots.push(slotOf(ts, { A: row(20, { mean: 200, max: 400, p95: 300 }) }));
    await page.clock.setFixedTime(m + 180_900);
    const catchup = catchupOf({ slots, minutes, names: ['A'], running: true });
    catchup.maxRetentionMs = 180_000;
    await load(page, catchup);

    for (const [win, gran] of [['60', '15'], ['60', '30'], ['300', '15'], ['0', '15']]) {
      await choose(page, { window: win, gran });
      for (const chart of ['responseTimeChart', 'throughputChart', 'errorRateChart', 'summaryRTChart']) {
        const lines = await page.evaluate((c) => S.charts[c].getOption().series
          .filter((x) => Array.isArray(x.data) && x.data.length).map((x) => [x.name, x.data.map((d) => d[0])]), chart);
        for (const [name, times] of lines) {
          const back = times.findIndex((t, i) => i > 0 && t <= times[i - 1]);
          expect(back, `window ${win}, granularity ${gran}, ${chart} "${name}": point ${back} goes back in time`).toBe(-1);
        }
      }
    }
  });

  test('a pause mid-run draws a gap for response times and 0 for throughput', async ({ page }) => {
    // 2 minutes of traffic, 64 s with no sample at all (every slot still closes, empty), then
    // traffic again. A period wholly in the pause has no response time: a gap, not a 0 ms dip.
    // Its Σ throughput is a real 0, and a partly idle period's rate counts the idle seconds.
    const start = Math.floor((Date.now() - 30 * MIN) / MIN) * MIN + 10_000;
    const slots = [];
    for (let i = 0; i < 120; i++) {
      const ts = start + i * IV;
      const idle = i >= 50 && i < 82;
      slots.push(idle ? emptySlot(ts) : slotOf(ts, { A: row(15, { mean: 250 + (i % 11) * 5, max: 700 }), B: row(5, { mean: 400, max: 900 }) }));
    }
    await load(page, catchupOf({ slots, names: ['A', 'B'] }));
    const pauseFrom = start + 50 * IV, pauseTo = start + 82 * IV;
    for (const g of [1, 3, 5, 15, 30]) {
      for (const metric of METRICS) {
        await choose(page, { gran: String(g), metric: CHART[metric] === 'responseTimeChart' ? metric : undefined, window: '0' });
        for (const [line, name] of [['A', 'A'], ['B', 'B'], ['Σ Total', null]]) {
          const got = await plotted(page, metric, line);
          if (got === null && metric === 'errorRate' && name === null) continue;
          same(got, expected(slots, g * IV, name, metric), `${metric} ${line} at ${g * 2}s`);
          for (const [t, v] of got) {
            if (t < pauseFrom || t + g * IV > pauseTo) continue; // only periods wholly in the pause
            if (metric === 'throughput' && name === null) expect(v, `Σ throughput in the pause at ${g * 2}s`).toBe(0);
            else expect(v, `${line} ${metric} in the pause at ${g * 2}s`).toBeNull();
          }
        }
      }
    }
  });

  test('a last minute that arrives with no slot makes the last 1m point exact, adding no point', async ({ page }) => {
    // A run ending in think time: its last minute closes after its last slot was sent, so the
    // server sends that minute alone (LiveAggregator.closeDue). The tab watching the run must
    // take it, so the 1m P95 is that minute's own, not an average of 2 s values.
    const m = Math.floor((Date.now() - 30 * MIN) / MIN) * MIN;
    const slots = [];
    for (let ts = m; ts < m + 2 * MIN + 30_000; ts += IV) slots.push(slotOf(ts, { A: row(20, { p95: 300 }) }));
    const minutes = [0, 1].map((i) => ({ timestamp: m + i * MIN, overall: { samples: 600, p95: 900 + i }, samplers: { A: { samples: 600, p95: 900 + i } } }));
    await load(page, catchupOf({ slots, minutes, names: ['A'], running: true }));
    await choose(page, { gran: '30', metric: 'p95', window: '0' });
    const lastMinute = m + 2 * MIN;
    expect(new Map(await plotted(page, 'p95', 'A')).get(lastMinute), 'before: the open minute is an average').toBeCloseTo(300, 9);
    const n = await page.evaluate(() => S.timestamps.length);
    await page.evaluate(async (x) => {
      await fetch('/test/send-bucket', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(x) });
    }, { type: 'bucket', interval: IV, testRunning: false, overviewInterval: MIN,
      overview: [{ timestamp: lastMinute, overall: { samples: 300, p95: 1777 }, samplers: { A: { samples: 300, p95: 1777 } } }] });
    await page.waitForFunction((k) => S.overview.some((b) => b.timestamp === k), lastMinute);
    await page.evaluate(() => refreshChartsForce());
    expect(await page.evaluate(() => S.timestamps.length), 'no point added').toBe(n);
    for (const line of ['A', 'Σ Total']) {
      expect(new Map(await plotted(page, 'p95', line)).get(lastMinute), `${line}: the last minute's exact P95`).toBe(1777);
    }
  });
});
