/**
 * A catch-up for a whole run, sent through the worker's mock server.
 *
 * Built in code rather than as a fixture file because the specs need its shape to
 * vary (ended or running, the update interval, connect/latency at zero) and because
 * the timestamps must be relative to now: an ended run's windows are measured from
 * its end, a running one's from the wall clock.
 *
 * Kept slim on purpose: a 3-minute run at 2 s slots is 90 slots, and every spec that
 * loads one pays for it.
 */
const { openDashboard } = require('./dashboard');

const NAMES = ['GET /search', 'POST /checkout', 'POST /token'];

function slot(ts, { zeroConnect = false } = {}) {
  const samplers = {};
  NAMES.forEach((n, i) => {
    samplers[n] = { samples: 10, avgResponseTime: 200 + 100 * i, p95: 350 + 100 * i, maxResponseTime: 450 + 100 * i,
      throughput: 5, errorRate: 0, avgConnectTime: zeroConnect ? 0 : 5, avgLatency: zeroConnect ? 0 : 50 };
  });
  return { timestamp: ts, overall: { samples: 30, avgResponseTime: 300, p95: 450, maxResponseTime: 650, throughput: 15,
    errorRate: 0, activeThreads: 10 }, samplers };
}

/**
 * A run of `minutes` (default 3). `endedAgoMs` places its last slot that far in the past; the end
 * time is 845 ms into that slot, as a real run's is (slots are labelled by their
 * start, the run ends partway through the last one). The sampler statistics are
 * session 1's real numbers, which is what exposed the table's rounding.
 */
function runCatchup({ endedAgoMs = 10 * 60_000, running = false, interval = 2000, zeroConnect = false, minutes = 3 } = {}) {
  const last = Math.floor((Date.now() - endedAgoMs) / interval) * interval;
  const start = last - minutes * 60_000;
  const recentBuckets = [];
  for (let t = start + interval; t <= last; t += interval) recentBuckets.push(slot(t, { zeroConnect }));
  return {
    type: 'catchup', testName: 'Run Data', testStartTime: start, testRunning: running, testEndTime: running ? 0 : last + 845,
    interval, maxRetentionMs: 3_600_000, overviewInterval: 60_000, knownSamplers: NAMES, hiddenSamplers: [],
    slaConfig: {}, slaStatus: {}, recentErrors: [], errorSummary: { byCode: {}, bySampler: {} },
    cumulativeStats: { totalSamples: 3276, totalErrors: 113, totalBytes: 166493, totalSentBytes: 622368, elapsedTime: 302975,
      activeThreads: running ? 10 : 0, maxThreads: 10, overallThroughput: 10.81277, overallErrorRate: 3.4493,
      receivedKBs: 0.53665, sentKBs: 2.00604, p50: 258, p90: 578, p95: 713, p99: 1030, avgResponseTime: 314.95,
      minResponseTime: 51, maxResponseTime: 1350, apdex: 0.895, apdexThreshold: 500, apdexToleratedThreshold: 1500 },
    samplerStats: {
      'GET /search': { samples: 633, errors: 18, errorRate: 2.8436, avgResponseTime: 411.0, minResponseTime: 150, maxResponseTime: 1048,
        p50: 398, p90: 594, p95: 740, p99: 954, throughput: 2.1232, receivedKBs: 0.08575532, sentKBs: 0.1990519 },
      'POST /checkout': { samples: 457, errors: 42, errorRate: 9.1904, avgResponseTime: 618.27, minResponseTime: 301, maxResponseTime: 1350,
        p50: 592, p90: 875, p95: 1077, p99: 1256, throughput: 1.53616, receivedKBs: 0.08212782, sentKBs: 0.7680801 },
      'POST /token': { samples: 460, errors: 11, errorRate: 2.3913, avgResponseTime: 352.08, minResponseTime: 203, maxResponseTime: 502,
        p50: 352, p90: 465, p95: 481, p99: 496, throughput: 1.53453, receivedKBs: 0.08009828, sentKBs: 0.3836326 },
    },
    recentBuckets,
  };
}

/** Load the page, replace the default catch-up with `catchup`, and wait until it is charted. */
async function loadRun(page, catchup) {
  await openDashboard(page);
  await page.evaluate(async (p) => {
    await fetch('/test/send-catchup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p) });
  }, catchup);
  await page.waitForFunction((n) => window.S && S.timestamps.length === n, catchup.recentBuckets.length);
}

module.exports = { NAMES, slot, runCatchup, loadRun };
