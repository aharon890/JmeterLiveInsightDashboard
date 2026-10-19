// @ts-check
const { test, expect } = require('./fixtures');
const { openDashboard, sendToPage, settled } = require('./setup/dashboard');

function makeBucket(overrides = {}) {
  const ts = Date.now();
  const base = {
    type: 'bucket', timestamp: ts, interval: 2000, testRunning: true,
    overall: { samples: 25, errors: 1, avgResponseTime: 250, p50: 200, p90: 400, p95: 500, p99: 800,
      throughput: 12.5, errorRate: 4.0, receivedBytesPerSec: 25600, sentBytesPerSec: 6400,
      activeThreads: 5, minResponseTime: 50, maxResponseTime: 900, avgConnectTime: 12, avgLatency: 55 },
    samplers: {
      'GET /api/products': { samples: 10, errors: 0, avgResponseTime: 180, p50: 150, p90: 350, p95: 450, p99: 700, throughput: 5, errorRate: 0, receivedBytesPerSec: 10240, avgConnectTime: 10, avgLatency: 45 },
      'POST /api/cart': { samples: 8, errors: 1, avgResponseTime: 340, p50: 300, p90: 620, p95: 880, p99: 1100, throughput: 4, errorRate: 12.5, receivedBytesPerSec: 8192, avgConnectTime: 15, avgLatency: 70 },
      'GET /api/checkout': { samples: 7, errors: 0, avgResponseTime: 200, p50: 160, p90: 380, p95: 500, p99: 820, throughput: 3.5, errorRate: 0, receivedBytesPerSec: 7168, avgConnectTime: 11, avgLatency: 50 }
    },
    recentErrors: [{ timestamp: ts, sampler: 'POST /api/cart', responseCode: '500', responseMessage: 'Server Error', requestUrl: 'http://example.com/api/cart' }],
    errorSummary: { byCode: { '500': 4, '503': 2 }, bySampler: { 'POST /api/cart': 4, 'GET /api/products': 2 } },
    slaStatus: { 'GET /api/products': { p95: 'PASS' }, 'POST /api/cart': { p95: 'PASS' }, 'GET /api/checkout': { p95: 'PASS' } },
    cumulative: { totalSamples: 600, totalErrors: 8, totalBytes: 614400, totalSentBytes: 153600, elapsedTime: 35000, activeThreads: 5, maxThreads: 5, overallThroughput: 17.1, overallErrorRate: 1.33, receivedKBs: 17.5, sentKBs: 4.4, p50: 200, p90: 390, p95: 470, p99: 810, avgResponseTime: 240, minResponseTime: 42, maxResponseTime: 1250, apdex: 0.90, apdexThreshold: 500 },
    samplerStats: {
      'GET /api/products': { name: 'GET /api/products', samples: 240, errors: 3, errorRate: 1.25, avgResponseTime: 185, minResponseTime: 42, maxResponseTime: 910, p50: 155, p90: 355, p95: 455, p99: 790, throughput: 6.9, receivedKBs: 88, sentKBs: 22, apdex: 0.94 },
      'POST /api/cart': { name: 'POST /api/cart', samples: 180, errors: 3, errorRate: 1.67, avgResponseTime: 330, minResponseTime: 95, maxResponseTime: 1250, p50: 285, p90: 610, p95: 860, p99: 1120, throughput: 5.1, receivedKBs: 44, sentKBs: 16, apdex: 0.83 },
      'GET /api/checkout': { name: 'GET /api/checkout', samples: 180, errors: 2, errorRate: 1.11, avgResponseTime: 215, minResponseTime: 55, maxResponseTime: 970, p50: 175, p90: 410, p95: 530, p99: 860, throughput: 5.1, receivedKBs: 57, sentKBs: 19, apdex: 0.91 }
    },
    knownSamplers: ['GET /api/products', 'POST /api/cart', 'GET /api/checkout']
  };
  return { ...base, ...overrides };
}

async function sendBucket(page, bucket) {
  await sendToPage(page, 'bucket', bucket);
}

async function sendEvent(page, evt) {
  await sendToPage(page, 'event', evt);
}

test.describe('Live Data Updates', () => {
  test.beforeEach(async ({ page }) => {
    await openDashboard(page);
  });

  test('new bucket updates metric cards @smoke', async ({ page }) => {
    await sendBucket(page, makeBucket());
    await expect(page.locator('#valSamples')).toContainText('600');
    // Pins the server contract: the card reads cumulative.p95 (the true overall
    // percentile), not the old avgP95 — a server-side rename must fail here.
    await expect(page.locator('#valP95')).toContainText('470');
  });

  test('new bucket updates sampler table @smoke', async ({ page }) => {
    await page.click('.tab-btn[data-tab="samplers"]');
    await sendBucket(page, makeBucket());
    const text = await page.locator('#samplerTableBody').textContent();
    expect(text).toContain('240');
  });

  test('error shows in error table @smoke', async ({ page }) => {
    await page.click('.tab-btn[data-tab="errors"]');
    await sendBucket(page, makeBucket());
    const text = await page.locator('#errorTableBody').textContent();
    expect(text).toContain('500');
  });

  test('error count badge updates', async ({ page }) => {
    await sendBucket(page, makeBucket());
    await expect(page.locator('#errorTabCount')).toContainText('(');
  });

  test('pause stops card updates', async ({ page }) => {
    await page.click('#btnPause');
    const before = await page.locator('#valSamples').textContent();
    const b = makeBucket();
    b.cumulative.totalSamples = 9999;
    await sendBucket(page, b);
    const after = await page.locator('#valSamples').textContent();
    expect(after).toBe(before);
  });

  test('resume applies pending data', async ({ page }) => {
    await page.click('#btnPause');
    const b = makeBucket();
    b.cumulative.totalSamples = 7777;
    await sendBucket(page, b);
    await page.click('#btnPause'); // resume
    await sendBucket(page, b);
    await expect(page.locator('#valSamples')).toContainText('7');
  });

  test('test ended shows ENDED badge @smoke', async ({ page }) => {
    await sendEvent(page, { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
  });

  test('test ended disables stop button', async ({ page }) => {
    await sendEvent(page, { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#btnStop')).toBeDisabled();
  });

  test('new sampler adds filter pill', async ({ page }) => {
    const b = makeBucket();
    b.knownSamplers.push('NEW /api/special');
    b.samplers['NEW /api/special'] = { samples: 5, errors: 0, avgResponseTime: 100, p50: 80, p90: 150, p95: 200, p99: 300, throughput: 2.5, errorRate: 0, receivedBytesPerSec: 5120, avgConnectTime: 5, avgLatency: 30 };
    await sendBucket(page, b);
    const pills = await page.locator('.filter-sampler-list .sampler-name').allTextContents();
    expect(pills.some(p => p.includes('NEW /api/special'))).toBe(true);
  });

  test('SLA FAIL updates header badge', async ({ page }) => {
    const b = makeBucket();
    b.slaStatus = { 'POST /api/cart': { p95: 'FAIL' } };
    await sendBucket(page, b);
    await expect(page.locator('#slaBadge')).toContainText('FAIL');
  });

  test('apdex value updates from cumulative', async ({ page }) => {
    const b = makeBucket();
    b.cumulative.apdex = 0.75;
    await sendBucket(page, b);
    await expect(page.locator('#apdexBadge')).toContainText('0.75');
  });

  test('error charts visible on errors tab', async ({ page }) => {
    await sendBucket(page, makeBucket());
    await page.click('.tab-btn[data-tab="errors"]');
    await expect(page.locator('#errorPieChart')).toBeVisible();
    await expect(page.locator('#errorBarChart')).toBeVisible();
  });

  // "Painted" means the chart's own option data grew with the series arrays.
  // With the default window (all) and granularity (1:1 at few points), a
  // painted summary chart has exactly one data point per timestamp, so a
  // mismatch means a bucket reached S.timestamps but never the canvas.
  // Charts are drawn in their own task after an update, so wait for the draw;
  // a bucket that never reaches the chart still fails this.
  async function chartsCaughtUp(page) {
    await expect.poll(async () => { const s = await chartVsState(page); return s.appended - s.painted; }).toBe(0);
    return chartVsState(page);
  }

  async function chartVsState(page) {
    return page.evaluate(() => {
      const opt = window.S.charts['summaryRTChart'].getOption();
      return {
        painted: opt.series.length ? opt.series[0].data.length : 0,
        appended: window.S.timestamps.length,
      };
    });
  }

  test('final bucket after testEnded is painted on charts', async ({ page }) => {
    await sendBucket(page, makeBucket());
    // Server order at end of test: finalizeDashboard broadcasts the testEnded
    // event FIRST, then onTestEnded flushes the final partial bucket. That
    // last bucket used to reach the series arrays and cards but never the
    // charts, because refreshCharts refuses to paint once the test ended.
    await sendEvent(page, { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
    await sendBucket(page, makeBucket({ testRunning: false }));
    const { painted, appended } = await chartsCaughtUp(page);
    expect(appended).toBeGreaterThanOrEqual(2);
    expect(painted).toBe(appended);
  });

  test('resume after test end paints the buffered data and stays Ended', async ({ page }) => {
    await sendBucket(page, makeBucket());
    await page.click('#btnPause');
    const buffered = makeBucket();
    buffered.cumulative.totalSamples = 8888;
    await sendBucket(page, buffered); // buffered while paused, still testRunning:true
    // Real end-of-test order while paused: the testEnded event bypasses the
    // pause gate, then the server's final flush bucket lands in the buffer.
    await sendEvent(page, { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
    const finalBucket = makeBucket({ testRunning: false });
    finalBucket.cumulative.totalSamples = 9100;
    await sendBucket(page, finalBucket); // buffered too
    await page.click('#btnPause'); // resume — replays both buffered buckets
    await expect(page.locator('#valSamples')).toContainText('9,100');
    // The final flush bucket must reach the charts, not just the arrays/cards.
    const { painted, appended } = await chartsCaughtUp(page);
    expect(painted).toBe(appended);
    // And replaying the stale testRunning:true bucket must not resurrect the
    // Running state (re-enabled stop button, live-looking charts) after end.
    await expect(page.locator('#testStatus')).toHaveText('Ended');
    await expect(page.locator('#btnStop')).toBeDisabled();
  });

  test('resume before the final bucket does not resurrect Running state', async ({ page }) => {
    // The narrow window: the user resumes after the testEnded event but
    // before the server's final flush bucket. The replay then ENDS on a
    // stale testRunning:true bucket, and nothing later corrects the state —
    // without the testEndedEvent latch the status flips back to Running and
    // the stop button re-enables on a test that no longer exists.
    await sendBucket(page, makeBucket());
    await page.click('#btnPause');
    const buffered = makeBucket();
    buffered.cumulative.totalSamples = 8888;
    await sendBucket(page, buffered); // buffered, still testRunning:true
    await sendEvent(page, { type: 'event', event: 'testEnded', data: { endTime: Date.now() } });
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
    await page.click('#btnPause'); // resume — replay ends on the stale bucket
    await expect(page.locator('#valSamples')).toContainText('8,888');
    await expect(page.locator('#testStatus')).toHaveText('Ended');
    await expect(page.locator('#btnStop')).toBeDisabled();
    await expect(page.locator('#liveLabel')).toHaveText('ENDED');
  });

  // At scale the browser redrew every tab on every update. Hidden tabs are now
  // brought up to date when shown — this pins that they still are.
  test('a hidden Samplers table is rebuilt when its tab is shown', async ({ page }) => {
    const b = makeBucket();
    b.samplerStats['GET /api/new'] = { name: 'GET /api/new', samples: 12345, errors: 0, errorRate: 0, avgResponseTime: 10,
      minResponseTime: 1, maxResponseTime: 20, p50: 9, p90: 15, p95: 17, p99: 19, throughput: 1, receivedKBs: 1, sentKBs: 1 };
    b.knownSamplers = [...b.knownSamplers, 'GET /api/new'];
    await sendBucket(page, b);   // Summary tab is showing: the table is not rebuilt
    await expect(page.locator('#samplerTableBody')).not.toContainText('GET /api/new');
    await page.click('.tab-btn[data-tab="samplers"]');
    await expect(page.locator('#samplerTableBody')).toContainText('GET /api/new');
    await expect(page.locator('#samplerTableBody')).toContainText('12,345');
  });

  test('hidden charts are drawn when their tab is shown', async ({ page }) => {
    await page.click('.tab-btn[data-tab="timeline"]');   // initialises the Timeline charts
    await settled(page);   // and draws them, while they are on screen
    await page.click('.tab-btn[data-tab="summary"]');
    await settled(page);   // the Summary's own redraw is over: only the bucket can add a point
    const points = () => page.evaluate(() => {
      const total = c => c.getOption().series.find(s => s.name === 'TOTAL' || s.name === 'Σ Total') || c.getOption().series[0];
      return { summary: total(S.charts.summaryRTChart).data.length, timeline: total(S.charts.throughputChart).data.length, appended: S.timestamps.length };
    });
    const before = await points();
    await sendBucket(page, makeBucket());
    await expect.poll(async () => (await points()).summary).toBe(before.appended + 1);   // on screen: drawn
    const hidden = await points();
    expect(hidden.appended).toBe(before.appended + 1);
    expect(hidden.timeline).toBe(before.timeline);      // hidden: not drawn
    await page.click('.tab-btn[data-tab="timeline"]');
    await expect.poll(async () => (await points()).timeline).toBe(hidden.appended);
  });
  // The server sends per-sampler maps columnar ({cols, rows}); the page must
  // end up with exactly what the object form gave it.
  test('columnar per-sampler maps decode to the same state as objects', async ({ page }) => {
    const toColumnar = (m) => {
      const cols = [...new Set(Object.values(m).flatMap(Object.keys))];
      return { cols, rows: Object.fromEntries(Object.entries(m).map(([k, v]) => [k, cols.map(c => (c in v ? v[c] : null))])) };
    };
    const asObjects = makeBucket();
    await sendBucket(page, asObjects);
    const viaObjects = await page.evaluate(() => ({
      p95: S.samplers['POST /api/cart'].p95.slice(-1)[0], stats: S.samplerStats['POST /api/cart'] }));

    const columnar = makeBucket();
    columnar.samplers = toColumnar(columnar.samplers);
    columnar.samplerStats = toColumnar(columnar.samplerStats);
    await sendBucket(page, columnar);
    const viaColumnar = await page.evaluate(() => ({
      p95: S.samplers['POST /api/cart'].p95.slice(-1)[0], stats: S.samplerStats['POST /api/cart'] }));
    expect(viaColumnar).toEqual(viaObjects);
    await page.click('.tab-btn[data-tab="samplers"]');
    await expect(page.locator('#samplerTableBody')).toContainText('POST /api/cart');
  });
});
