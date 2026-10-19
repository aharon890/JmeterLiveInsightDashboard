// Records the dashboard's SSE stream exactly as a client receives it.
//
//   node sse-probe.mjs <base-url> <out-dir> [linger-seconds-after-end] [checkpoint-seconds]
//
// One NDJSON line per event in <out-dir>/sse.ndjson: receive time (host clock),
// event name, payload size, and the numbers the comparison needs. Full
// per-sampler cumulative stats are large, so they are kept only at
// checkpoints, on catch-up, and on the final (testRunning=false) bucket.
//
// After the test ends the probe stays connected for the linger period — the
// server only idles out once no client is connected, and staying lets the
// comparison see whether anything still changes after the end. Then it
// reconnects once, to record the catch-up a late-opening tab would get.
import http from 'node:http';
import zlib from 'node:zlib';
import fs from 'node:fs';
import path from 'node:path';

const [base, outDir, lingerArg, checkpointArg] = process.argv.slice(2);
if (!base || !outDir) {
  console.error('usage: node sse-probe.mjs <base-url> <out-dir> [linger-s] [checkpoint-s]');
  process.exit(2);
}
const lingerMs = (Number(lingerArg) || 120) * 1000;
const checkpointMs = (Number(checkpointArg) || 60) * 1000;
fs.mkdirSync(outDir, { recursive: true });
const out = fs.createWriteStream(path.join(outDir, 'sse.ndjson'), { flags: 'a' });
const log = (o) => out.write(JSON.stringify(o) + '\n');

let lastCheckpoint = 0;
let endedAt = 0;

// Per-sampler maps may arrive columnar ({cols, rows}); the comparison wants objects.
function objects(m) {
  if (!m || !m.cols) return m;
  const out = {};
  for (const [k, r] of Object.entries(m.rows)) out[k] = Object.fromEntries(m.cols.map((c, i) => [c, r[i]]));
  return out;
}

function summarise(ev, raw, t, phase, wire) {
  const rec = { t, phase, ev, size: Buffer.byteLength(raw) };
  if (wire != null) rec.wire = wire;   // compressed bytes on the socket for this event
  let d;
  try { d = JSON.parse(raw); } catch (e) { rec.parseError = String(e); return rec; }
  if (d.samplers) d.samplers = objects(d.samplers);
  if (d.samplerStats) d.samplerStats = objects(d.samplerStats);
  for (const b of d.recentBuckets || []) b.samplers = objects(b.samplers);
  if (ev === 'bucket') {
    rec.ts = d.timestamp;
    rec.interval = d.interval;
    rec.testRunning = d.testRunning;
    rec.overall = d.overall;
    rec.samplers = Object.fromEntries(Object.entries(d.samplers || {})
      .map(([k, v]) => [k, { samples: v.samples, errors: v.errors }]));
    rec.cumulative = d.cumulative;
    rec.knownSamplers = (d.knownSamplers || []).length;
    rec.recentErrors = (d.recentErrors || []).length;
    const checkpoint = t - lastCheckpoint >= checkpointMs || d.testRunning === false;
    if (checkpoint) {
      rec.samplerStats = d.samplerStats;
      rec.errorSummary = d.errorSummary;
      lastCheckpoint = t;
    }
    if (d.testRunning === false && !endedAt) endedAt = t;
  } else if (ev === 'catchup') {
    rec.testRunning = d.testRunning;
    rec.buckets = (d.recentBuckets || []).length;
    rec.firstBucketTs = d.recentBuckets?.[0]?.timestamp;
    rec.lastBucketTs = d.recentBuckets?.at(-1)?.timestamp;
    rec.bucketSampleSum = (d.recentBuckets || []).reduce((s, b) => s + (b.overall?.samples || 0), 0);
    rec.cumulative = d.cumulativeStats;
    rec.samplerStats = d.samplerStats;
    rec.errorSummary = d.errorSummary;
    rec.maxRetentionMs = d.maxRetentionMs;
  } else if (ev === 'event') {
    rec.event = d.event;
    rec.data = d.data;
    // When the last partial interval is empty the server sends no final
    // bucket at all (doFlush returns null), only this event.
    if (d.event === 'testEnded' && !endedAt) endedAt = t;
  }
  return rec;
}

function connect(phase) {
  return new Promise((resolve) => {
    const req = http.get(base.replace(/\/$/, '') + '/api/events',
      { headers: { Accept: 'text/event-stream', 'Accept-Encoding': 'gzip' } }, (res) => {
        const gz = res.headers['content-encoding'] === 'gzip';
        log({ t: Date.now(), phase, ev: '_connected', status: res.statusCode, gzip: gz });
        // A catch-up is one data line of tens of MB. Appending each chunk to
        // one string and rescanning it from the start was quadratic: 11 s to
        // parse a 22 MB catch-up the server wrote in 0.2 s, which made the
        // probe report slow (or missing) catch-ups that were not slow. Only
        // the new chunk is scanned; the partial line waits in pieces.
        let pending = [];
        let ev = 'message';
        let data = [];
        let wire = 0;
        res.on('data', (c) => { wire += c.length; });
        const body = gz ? res.pipe(zlib.createGunzip({ flush: zlib.constants.Z_SYNC_FLUSH })) : res;
        body.setEncoding('utf8');
        body.on('data', (chunk) => {
          let start = 0;
          let nl;
          while ((nl = chunk.indexOf('\n', start)) >= 0) {
            pending.push(chunk.slice(start, nl));
            const line = pending.join('').replace(/\r$/, '');
            pending = [];
            start = nl + 1;
            if (line === '') {
              if (data.length) log(summarise(ev, data.join('\n'), Date.now(), phase, gz ? wire : null));
              if (phase === 'late' && ev === 'catchup') connect.gotLateCatchup = Date.now();
              wire = 0;
              ev = 'message';
              data = [];
            } else if (line.startsWith('event:')) {
              ev = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              data.push(line.slice(5).replace(/^ /, ''));
            }
          }
          if (start < chunk.length) pending.push(chunk.slice(start));
        });
        // 'close' fires both when the server ends the stream and when the
        // probe destroys the request; 'end' only on the former.
        res.on('close', () => { log({ t: Date.now(), phase, ev: '_closed' }); resolve(req); });
        res.on('error', () => {});
      });
    req.on('error', (e) => { log({ t: Date.now(), phase, ev: '_error', error: String(e) }); resolve(req); });
    connect.current = req;
  });
}

async function waitForServer() {
  for (;;) {
    const ok = await new Promise((r) => {
      http.get(base.replace(/\/$/, '') + '/api/status', (res) => { res.resume(); r(res.statusCode === 200); })
        .on('error', () => r(false));
    });
    if (ok) return;
    await new Promise((r) => setTimeout(r, 500));
  }
}

await waitForServer();
const live = connect('live');
// Linger after the end, then drop the live connection and take one fresh catch-up.
const timer = setInterval(() => {
  if (endedAt && Date.now() - endedAt >= lingerMs) {
    clearInterval(timer);
    connect.current.destroy();
  }
}, 1000);
await live;
clearInterval(timer);
// The late catch-up carries the whole 1 h window plus the minute tier; after a
// 3 h run it did not arrive within the 5 s this once waited (B60's took 3.9 s),
// so wait for it (up to 120 s) and record how long it took.
if (endedAt) {
  const t0 = Date.now();
  const late = connect('late');
  const done = setInterval(() => {
    if (connect.gotLateCatchup || Date.now() - t0 >= 120000) {
      log({ t: Date.now(), phase: 'late', ev: '_lateWait', ms: connect.gotLateCatchup ? connect.gotLateCatchup - t0 : null });
      clearInterval(done);
      connect.current.destroy();
    }
  }, 200);
  await late;
}
out.end();
