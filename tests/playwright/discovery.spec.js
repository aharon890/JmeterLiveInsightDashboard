// @ts-check
const { test, expect } = require('./fixtures');

// The tab the plugin opens itself connects at test start, before any sample exists, so
// its catch-up has no samplers, no hidden list and no hierarchy. Everything it learns
// afterwards arrives in updates. Auto-hide and the transaction hierarchy used to travel
// only in the catch-up: that tab charted Debug/JSR223 samplers and showed transaction
// children as plain rows for the whole run, and a reload "fixed" it.

const T0 = Math.floor(Date.now() / 2000) * 2000 - 60_000;

function stats(samples) {
  return { samples, errors: 0, errorRate: 0, avgResponseTime: 100, minResponseTime: 50, maxResponseTime: 200,
    p50: 100, p90: 150, p95: 180, p99: 200, throughput: 1, receivedKBs: 1, sentKBs: 1 };
}

function emptyCatchup(extra) {
  return {
    type: 'catchup', testName: 'Discovery', testStartTime: T0, testRunning: true, testEndTime: 0,
    interval: 2000, maxRetentionMs: 3_600_000, overviewInterval: 60_000,
    knownSamplers: [], hiddenSamplers: [], slaConfig: {}, slaStatus: {}, recentErrors: [],
    errorSummary: { byCode: {}, bySampler: {} }, samplerStats: {}, recentBuckets: [],
    cumulativeStats: { totalSamples: 0, totalErrors: 0, overallThroughput: 0, maxThreads: 5, activeThreads: 5 },
    ...extra,
  };
}

/** Slot `i`, carrying data and stats for `names`, plus whatever `extra` the server added. */
function bucket(i, names, extra) {
  const samplers = {}, samplerStats = {};
  names.forEach(n => { samplers[n] = { samples: 2, avgResponseTime: 100, p95: 180, maxResponseTime: 200,
    throughput: 1, errorRate: 0 }; samplerStats[n] = stats(10 + i); });
  return { type: 'bucket', timestamp: T0 + i * 2000, interval: 2000, testRunning: true,
    overall: { samples: 2 * names.length, avgResponseTime: 100, p95: 180, maxResponseTime: 200, throughput: names.length,
      errorRate: 0, activeThreads: 5 },
    samplers, samplerStats, ...extra };
}

async function send(page, path, body) {
  await page.evaluate(([p, b]) => fetch(p, {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(b),
  }), [path, body]);
}

/** Load the page and replace the fixture's catch-up with the empty one of a run just started. */
async function startRun(page, extra) {
  await page.goto('/');
  await page.waitForFunction(() => window.S && S.knownSamplers.length > 0);  // fixture catch-up applied
  await send(page, '/test/send-catchup', emptyCatchup(extra));
  await page.waitForFunction(() => S.knownSamplers.length === 0);
}

const pill = (page, name) => page.locator(`.sampler-pill[data-sampler-name="${name.toLowerCase()}"]`);
const nameCell = (page, name) => page.locator(`#samplerTableBody tr[data-sampler="${name}"] .sampler-name-cell`);

test.describe('Samplers discovered after the tab connected', () => {
  test('a sampler matching a hide pattern starts unchecked and is not charted', async ({ page }) => {
    await startRun(page);
    await page.click('.tab-btn[data-tab="timeline"]');
    const names = ['GET /a', 'Debug Sampler'];
    await send(page, '/test/send-bucket', bucket(1, names, { knownSamplers: names, hiddenSamplers: ['Debug Sampler'] }));
    await page.waitForFunction(() => S.knownSamplers.includes('Debug Sampler'));

    await expect(pill(page, 'Debug Sampler')).not.toHaveClass(/checked/);
    await expect(pill(page, 'GET /a')).toHaveClass(/checked/);
    await page.waitForFunction(() => (S.charts.throughputChart.getOption().series || []).some(s => s.name === 'GET /a'));
    const series = await page.evaluate(() => S.charts.throughputChart.getOption().series.map(s => s.name));
    expect(series).not.toContain('Debug Sampler');
  });

  test('a sampler checked back on stays checked when a later update changes the list', async ({ page }) => {
    await startRun(page);
    const first = ['GET /a', 'Debug Sampler'];
    await send(page, '/test/send-bucket', bucket(1, first, { knownSamplers: first, hiddenSamplers: ['Debug Sampler'] }));
    await page.waitForFunction(() => S.knownSamplers.includes('Debug Sampler'));
    await expect(pill(page, 'Debug Sampler')).not.toHaveClass(/checked/);
    await pill(page, 'Debug Sampler').click();
    await expect(pill(page, 'Debug Sampler')).toHaveClass(/checked/);

    const second = [...first, 'JSR223 Sampler'];
    await send(page, '/test/send-bucket', bucket(2, second,
      { knownSamplers: second, hiddenSamplers: ['Debug Sampler', 'JSR223 Sampler'] }));
    await page.waitForFunction(() => S.knownSamplers.includes('JSR223 Sampler'));

    await expect(pill(page, 'Debug Sampler')).toHaveClass(/checked/);
    await expect(pill(page, 'JSR223 Sampler')).not.toHaveClass(/checked/);
  });

  test('a transaction child is indented and follows Hide Children', async ({ page }) => {
    await startRun(page);
    await page.click('.tab-btn[data-tab="samplers"]');
    const names = ['Checkout', 'GET /child'];
    await send(page, '/test/send-bucket', bucket(1, names,
      { knownSamplers: names, hiddenSamplers: [], hierarchy: { 'GET /child': 'Checkout' } }));

    await expect(nameCell(page, 'GET /child')).toHaveAttribute('style', /padding-left:\s*24px/);
    await expect(nameCell(page, 'GET /child').locator('.sampler-name-text'))
      .toHaveAttribute('title', /\(child of Checkout\)/);
    await expect(nameCell(page, 'Checkout')).not.toHaveAttribute('style', /padding-left/);

    await page.click('#btnToggleChildren');
    await expect(page.locator('#samplerTableBody tr[data-sampler="GET /child"]')).toHaveCount(0);
    await expect(page.locator('#samplerTableBody tr[data-sampler="Checkout"]')).toHaveCount(1);
  });

  test('a catch-up received with the Samplers tab open indents children at once', async ({ page }) => {
    await startRun(page);
    await page.click('.tab-btn[data-tab="samplers"]');
    // A reconnect: the catch-up carries everything, the table is drawn from it directly
    await send(page, '/test/send-catchup', emptyCatchup({
      knownSamplers: ['Checkout', 'GET /child'], hierarchy: { 'GET /child': 'Checkout' },
      samplerStats: { 'Checkout': stats(10), 'GET /child': stats(10) },
    }));

    await expect(nameCell(page, 'GET /child')).toHaveAttribute('style', /padding-left:\s*24px/);
  });

  test('while paused, an update dropped from the buffer hands on its hidden list and hierarchy', async ({ page }) => {
    // A 20 s window: the pause buffer keeps 10 + 10 updates and drops the oldest
    await startRun(page, { maxRetentionMs: 20_000 });
    await page.click('#btnPause');
    const names = ['GET /a', 'Debug Sampler', 'Checkout', 'GET /child'];
    await send(page, '/test/send-bucket', bucket(1, names, { knownSamplers: names,
      hiddenSamplers: ['Debug Sampler'], hierarchy: { 'GET /child': 'Checkout' } }));
    for (let i = 2; i <= 22; i++) await send(page, '/test/send-bucket', bucket(i, names));
    await page.waitForFunction(() => S.pauseBuffer.length === 20 && S.pauseBuffer[0].timestamp > S.testStartTime + 2000);

    await page.click('#btnPause');  // resume
    await page.waitForFunction(() => S.knownSamplers.includes('Debug Sampler'));
    await expect(pill(page, 'Debug Sampler')).not.toHaveClass(/checked/);
    expect(await page.evaluate(() => S.hierarchy)).toEqual({ 'GET /child': 'Checkout' });
  });
});
