// D15 of the Docker gate: every point the Timeline plots at 6s/10s/30s/1m against JMeter's
// numbers for the same clock period.
//
//   node chart-vs-jmeter.mjs <dashboard-final.json> <jmeter-buckets.json> <child-label-regex>
//
// The real index.html is loaded through the Playwright suite's mock server and given the
// run's final catch-up, exactly as a tab opened after the run would get it; the points are
// read back from the charts. jmeter-buckets.json (jmeter-buckets.groovy) holds JMeter's own
// calculator's numbers per period [kG, (k+1)G) of sample end times, which is how JMeter's
// over-time graphs define a point. Compared, per line (each visible sampler, and Σ Total):
//   Mean, Max, error %  exactly (within the 7 significant digits the wire carries);
//   throughput          as JMeter's report computes it (TimeRateAggregator): the period's
//                       count x 1000 / its length, the run's partial first and last periods
//                       included (the run has ended, so every period is closed);
//   P95 at 1 minute     exactly: the page draws the server's exact minute values there.
// A period in which JMeter has no sample for a line has no point there, as in JMeter's
// report: any value the chart draws for it is a mismatch (a throughput of 0 is allowed).
// At 6/10/30 s a percentile is an average of 2 s values by design and is not compared.
// Transaction steps (the child regex) are not compared: the dashboard charts a sub-result in
// its parent's slot, by decision, so near a period boundary it can fall one period over.
// Exit 0 when every point matches, 1 on any mismatch, 2 when an input is unusable.
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const require = createRequire(path.join(ROOT, 'tests', 'playwright', 'package.json'));
const die = (m) => { console.error('D15: ' + m); process.exit(2); };

const [dashFile, refFile, childArg] = process.argv.slice(2);
if (!dashFile || !refFile) die('usage: chart-vs-jmeter.mjs <dashboard-final.json> <jmeter-buckets.json> <child-regex>');
let catchup, ref;
try { catchup = JSON.parse(fs.readFileSync(dashFile, 'utf8')); } catch (e) { die('unreadable dashboard catch-up: ' + e.message); }
try { ref = JSON.parse(fs.readFileSync(refFile, 'utf8')); } catch (e) { die('unreadable JMeter buckets: ' + e.message); }
if (catchup.testRunning !== false) die('the catch-up was taken while the test was still running');
const child = childArg ? new RegExp(childArg) : null;

const { chromium } = require('playwright');
const { startServer } = require(path.join(ROOT, 'tests', 'playwright', 'setup', 'test-server.js'));
const server = await startServer(0);
const browser = await chromium.launch();
let compared = 0, points = 0;
const mismatches = [];
try {
  const page = await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.port}/`);
  await page.waitForSelector('#liveLabel');
  await page.waitForTimeout(300);
  await page.evaluate(async (p) => {
    await fetch('/test/send-catchup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(p) });
  }, catchup);
  await page.waitForFunction(() => window.S && S.timestamps.length > 0 && S.testRunning === false);
  await page.click('.tab-btn[data-tab="timeline"]');
  await page.evaluate(() => { S.samplerVisibility[TOTAL_NAME] = true; buildFilterBar(); });
  const { interval, lines, total } = await page.evaluate(() => ({
    interval: S.updateIntervalMs, total: TOTAL_NAME,
    lines: S.knownSamplers.filter((n) => S.samplerVisibility[n]),
  }));
  const compareLines = lines.filter((n) => !(child && child.test(n))).concat([total]);
  const choose = async (sel) => { await page.click(sel); await page.waitForTimeout(350); };
  const read = (chart, line) => page.evaluate(([c, l]) => {
    const s = S.charts[c].getOption().series.find((x) => x.name === l);
    return s ? s.data.map((p) => [p[0], p[1] == null ? null : p[1]]) : null;
  }, [chart, line]);
  const near = (a, b) => Math.abs(a - b) <= Math.max(1e-6 * Math.abs(b), 1e-9);

  await choose('#windowToggle [data-window="0"]');
  for (const g of [3, 5, 15, 30]) {
    const G = g * interval, byRow = ref[String(G)];
    if (!byRow) die(`no JMeter buckets for a ${G} ms period`);
    await choose(`#granularityToggle [data-gran="${g}"]`);
    const checks = [['avgResponseTime', 'responseTimeChart', 'mean'], ['maxResponseTime', 'responseTimeChart', 'max'],
      ['throughput', 'throughputChart', null], ['errorRate', 'errorRateChart', 'errorPct']];
    if (G === catchup.overviewInterval) checks.push(['p95', 'responseTimeChart', 'p95']);
    for (const [metric, chart, field] of checks) {
      if (chart === 'responseTimeChart') await choose(`[data-metric="${metric}"]`);
      for (const line of compareLines) {
        const got = await read(chart, line);
        if (got === null) {
          if (metric === 'errorRate' && line === total) continue; // that chart has no Σ line
          mismatches.push(`${line}: no ${metric} line at ${G / 1000}s`); continue;
        }
        const rows = byRow[line === total ? 'TOTAL' : line] || {};
        const seen = new Set();
        for (const [k, v] of got) {
          points++;
          seen.add(String(k));
          const r = rows[String(k)];
          let want;
          if (field === null) {
            if (!r && v == null) continue; // no samples: a gap, as in JMeter's report
            want = (r ? r.samples : 0) / (G / 1000);
          } else if (!r) {
            if (v != null) mismatches.push(`${line} ${metric} at ${G / 1000}s, period ${new Date(k).toISOString().slice(11, 19)}: chart=${v} but JMeter has no samples there`);
            continue;
          } else {
            want = r[field];
          }
          compared++;
          if (v == null || !near(v, want)) {
            mismatches.push(`${line} ${metric} at ${G / 1000}s, period ${new Date(k).toISOString().slice(11, 19)}: chart=${v} jmeter=${want}`);
          }
        }
        for (const k of Object.keys(rows)) if (!seen.has(k)) mismatches.push(`${line} ${metric} at ${G / 1000}s: no point for period ${new Date(+k).toISOString().slice(11, 19)}, JMeter has ${rows[k].samples} samples`);
      }
    }
  }
} finally {
  await browser.close();
  await server.close();
}
console.log(`D15: compared ${compared} values in ${points} chart points (6s/10s/30s/1m)`);
if (!mismatches.length) { console.log("D15: every point equals JMeter's for its clock period"); process.exit(0); }
console.log(`D15: ${mismatches.length} value(s) differ from JMeter's:`);
mismatches.slice(0, 25).forEach((m) => console.log('  ' + m));
process.exit(1);
