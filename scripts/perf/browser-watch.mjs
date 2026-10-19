// Keeps one real Chromium tab on the dashboard for the whole run and records
// what a user would experience: JS heap (after a forced GC), DOM size, main-
// thread long tasks, periodic screenshots, and — at the end — the numbers the
// page holds and shows, then again after a linger period, so anything that
// still moves after the test has ended is caught.
//
//   node browser-watch.mjs <url> <out-dir> [sample-s] [shot-s] [linger-s] [reload-at-s]
//
// reload-at-s > 0 reloads the tab once at that point of the run and records how
// long the catch-up takes to put data back on the page.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(path.join(here, '../../tests/playwright/package.json'));
const { chromium } = require('@playwright/test');

const [url, outDir, sampleArg, shotArg, lingerArg, reloadArg] = process.argv.slice(2);
const sampleMs = (Number(sampleArg) || 30) * 1000;
const shotMs = (Number(shotArg) || 300) * 1000;
const lingerMs = (Number(lingerArg) || 120) * 1000;
const reloadAtMs = (Number(reloadArg) || 0) * 1000;
const shots = path.join(outDir, 'screens');
fs.mkdirSync(shots, { recursive: true });
const out = fs.createWriteStream(path.join(outDir, 'browser.ndjson'), { flags: 'a' });
const log = (o) => out.write(JSON.stringify({ t: Date.now(), ...o }) + '\n');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const browser = await chromium.launch({
  args: ['--enable-precise-memory-info', '--js-flags=--expose-gc'],
});
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
page.on('pageerror', (e) => log({ kind: 'pageerror', message: String(e) }));
page.on('console', (m) => { if (m.type() === 'error') log({ kind: 'console-error', message: m.text() }); });

// Long-task observer, installed before any page script runs.
await page.addInitScript(() => {
  window.__perf = { longCount: 0, longTotal: 0, longMax: 0 };
  try {
    new PerformanceObserver((list) => {
      for (const e of list.getEntries()) {
        window.__perf.longCount++;
        window.__perf.longTotal += e.duration;
        window.__perf.longMax = Math.max(window.__perf.longMax, e.duration);
      }
    }).observe({ type: 'longtask', buffered: true });
  } catch (e) { /* not supported */ }
});

for (;;) {
  try { await page.goto(url, { waitUntil: 'load', timeout: 5000 }); break; } catch { await sleep(1000); }
}
const started = Date.now();
log({ kind: 'opened', url });

async function sample() {
  return page.evaluate(() => {
    if (window.gc) window.gc();
    const m = performance.memory || {};
    const p = window.__perf || {};
    return {
      heapUsed: m.usedJSHeapSize, heapTotal: m.totalJSHeapSize, dom: document.getElementsByTagName('*').length,
      longCount: p.longCount, longTotal: Math.round(p.longTotal || 0), longMax: Math.round(p.longMax || 0),
      points: (typeof S !== 'undefined' && S.timestamps) ? S.timestamps.length : null,
      running: (typeof S !== 'undefined') ? S.testRunning : null,
    };
  });
}

// What the page holds (S) and what the Samplers table and header actually render.
async function snapshot(tag) {
  await page.click('.tab-btn[data-tab="samplers"]').catch(() => {});
  await sleep(500);
  const state = await page.evaluate(() => ({
    cumulative: S.cumulative, samplerStats: S.samplerStats,
    rows: [...document.querySelectorAll('#samplerTableBody tr')].map((tr) =>
      [...tr.querySelectorAll('td')].map((td) => td.innerText.trim())),
    header: (document.querySelector('header') || document.body).innerText.slice(0, 2000),
  }));
  log({ kind: 'snapshot', tag, ...state });
  for (const tab of ['summary', 'timeline', 'samplers', 'errors']) {
    await page.click(`.tab-btn[data-tab="${tab}"]`).catch(() => {});
    await sleep(800);
    await page.screenshot({ path: path.join(shots, `${tag}-${tab}.png`) });
  }
  await page.click('.tab-btn[data-tab="summary"]').catch(() => {});
}

// The dashboard can vanish without the test ever "ending" for the page — e.g.
// JMeter dying of OutOfMemoryError and the runner killing the container. Then
// S.testRunning never turns false, so stop after the server has been gone for
// a while, keeping a snapshot of what the tab still shows.
const statusUrl = new URL('api/status', url).toString();
const serverUp = () => new Promise((resolve) => {
  const req = http.get(statusUrl, { timeout: 3000 }, (res) => { res.resume(); resolve(res.statusCode === 200); });
  req.on('timeout', () => { req.destroy(); resolve(false); });
  req.on('error', () => resolve(false));
});
let downSince = 0;

let lastShot = 0;
let reloaded = false;
let endedAt = 0;
let seenRunning = false;
for (;;) {
  if (await serverUp()) {
    downSince = 0;
  } else {
    downSince = downSince || Date.now();
    if (Date.now() - downSince >= 120000) {
      log({ kind: 'server-gone', sinceMs: Date.now() - downSince });
      await snapshot('server-gone').catch(() => {});
      break;
    }
  }
  let s;
  try { s = await sample(); } catch (e) { log({ kind: 'sample-error', message: String(e) }); break; }
  log({ kind: 'sample', ...s });
  const now = Date.now();
  if (now - lastShot >= shotMs) {
    await page.screenshot({ path: path.join(shots, `t${Math.round((now - started) / 1000)}s.png`) }).catch(() => {});
    lastShot = now;
  }
  if (reloadAtMs && !reloaded && now - started >= reloadAtMs && s.running !== false) {
    reloaded = true;
    const t0 = Date.now();
    await page.reload({ waitUntil: 'load' });
    await page.waitForFunction(() => typeof S !== 'undefined' && S.timestamps && S.timestamps.length > 0,
      null, { timeout: 60000 }).catch(() => {});
    log({ kind: 'reload', ms: Date.now() - t0, points: await page.evaluate(() => S.timestamps.length) });
  }
  // S.testRunning starts out false before the first catch-up, so "ended" only
  // counts once the page has seen the run in progress.
  if (s.running === true) seenRunning = true;
  if (seenRunning && s.running === false && !endedAt) {
    endedAt = now;
    await sleep(3000); // let the final bucket render
    await snapshot('end');
  }
  if (endedAt && now - endedAt >= lingerMs) {
    await snapshot('end-plus-linger');
    break;
  }
  await sleep(sampleMs);
}
await browser.close();
out.end();
