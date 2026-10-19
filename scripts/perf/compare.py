"""Compare one perf-harness run's dashboard output against JMeter's own numbers.

    python compare.py <run-dir>

Reads what run-scenario.sh left in <run-dir> and writes report.md and
report.json there. Sources of truth, strongest first:

  results.jtl            every sample; recomputed here exactly (whole-ms
                         histograms, so percentiles are exact, not estimated)
  jmeter-aggregate.json  JMeter's own Aggregate Report calculator over the JTL
  html-report-*/         JMeter's HTML report (default settings, and with
                         percentiles over every sample)

and what the plugin showed: sse.ndjson (the live stream, as a client got it)
and browser.ndjson (what a real tab held and rendered).
"""
import struct
import bisect
import csv
import json
import math
import os
import re
import statistics
import sys
from collections import Counter, defaultdict

RUN = sys.argv[1]
CHILD = re.compile(r'^tx-step-')          # sub-result labels in perf-harness.jmx
TX_PARENT = re.compile(r'^tx-checkout$')  # their transaction parent
TOTAL = 'TOTAL'


def p(*parts):
    return os.path.join(RUN, *parts)


def load_ndjson(name):
    path = p(name)
    if not os.path.exists(path):
        return []
    with open(path, encoding='utf-8') as f:
        return [json.loads(line) for line in f if line.strip()]


def jfloat(x):
    """x as a Java float widened to double: the Aggregate Report's p90/95/99
    percents are Float.parseFloat(pct) / 100f, so 0.9 is 0.89999998, and
    that decides the rank whenever n x percent ends in .5."""
    return struct.unpack('f', struct.pack('f', x))[0]


def pct_exact(hist, n, q):
    """JMeter's percent point (StatCalculator.getPercentPoint): target rank
    Math.round(n*q), then the first value whose cumulative count reaches it."""
    if n == 0:
        return None
    target = math.floor(n * q + 0.5)
    acc = 0
    for v in sorted(hist):
        acc += hist[v]
        if acc >= target:
            return float(v)
    return float(max(hist))


class Acc:
    __slots__ = ('n', 'err', 'sum', 'min', 'max', 'hist', 'first', 'last', 'bytes', 'sent', 'lat', 'conn')

    def __init__(self):
        self.n = self.err = self.sum = self.bytes = self.sent = self.lat = self.conn = 0
        self.min, self.max = math.inf, -math.inf
        self.hist = Counter()
        self.first, self.last = math.inf, -math.inf

    def add(self, ts, el, ok, b, sb, lat, conn):
        self.n += 1
        self.err += 0 if ok else 1
        self.sum += el
        self.min = min(self.min, el)
        self.max = max(self.max, el)
        self.hist[el] += 1
        self.first = min(self.first, ts)
        self.last = max(self.last, ts + el)
        self.bytes += b
        self.sent += sb
        self.lat += lat
        self.conn += conn

    def merge(self, o):
        self.n += o.n; self.err += o.err; self.sum += o.sum
        self.min = min(self.min, o.min); self.max = max(self.max, o.max)
        self.hist.update(o.hist)
        self.first = min(self.first, o.first); self.last = max(self.last, o.last)
        self.bytes += o.bytes; self.sent += o.sent; self.lat += o.lat; self.conn += o.conn

    def stats(self):
        if not self.n:
            return {'samples': 0}
        span = (self.last - self.first) / 1000.0
        return {'samples': self.n, 'errors': self.err, 'errorPct': self.err * 100.0 / self.n,
                'mean': self.sum / self.n, 'min': float(self.min), 'max': float(self.max),
                'p50': pct_exact(self.hist, self.n, .50), 'p90': pct_exact(self.hist, self.n, jfloat(.90)),
                'p95': pct_exact(self.hist, self.n, jfloat(.95)), 'p99': pct_exact(self.hist, self.n, jfloat(.99)),
                'throughput': self.n / span if span > 0 else None,
                'receivedKBs': self.bytes / 1024.0 / span if span > 0 else None,
                'sentKBs': self.sent / 1024.0 / span if span > 0 else None}


# ---------------------------------------------------------------------------
# Plugin side: the live stream and the browser
# ---------------------------------------------------------------------------
scenario = json.load(open(p('scenario.json'), encoding='utf-8'))
sse = load_ndjson('sse.ndjson')
browser = load_ndjson('browser.ndjson')
live_buckets = [e for e in sse if e.get('ev') == 'bucket' and e.get('phase') == 'live']
final_bucket = next((e for e in reversed(live_buckets) if e.get('testRunning') is False), None)
# No testRunning=false bucket arrives when the last partial interval is empty
# (the server's final flush returns null); the tab then ends on the last
# running bucket, so that is what the user saw last.
final_delivered = final_bucket is not None
if final_bucket is None and live_buckets:
    final_bucket = live_buckets[-1]
checkpoints = [e for e in live_buckets if e.get('samplerStats') and e is not final_bucket]
catchups = [e for e in sse if e.get('ev') == 'catchup']
late_catchup = next((e for e in catchups if e.get('phase') == 'late'), None)

# When a bucket message was built. Before slots followed sample end times, a
# message was built at flush time, which is the next bucket's start. Slots
# aligned to the clock (every ts a multiple of the interval) close one slot of
# grace after their end, at the timer's tick 50 ms past the boundary.
bucket_ts = [e['ts'] for e in live_buckets]
interval_ms = next((e.get('interval') for e in live_buckets if e.get('interval')), 2000)
slot_aligned = bool(bucket_ts) and all(t % interval_ms == 0 for t in bucket_ts)
flush_time = {}
for i, e in enumerate(live_buckets):
    if slot_aligned:
        flush_time[id(e)] = e['ts'] + 2 * interval_ms + 50
    else:
        flush_time[id(e)] = bucket_ts[i + 1] if i + 1 < len(bucket_ts) else None

# ---------------------------------------------------------------------------
# Ground truth: one streaming pass over the JTL
# ---------------------------------------------------------------------------
cp_times = sorted(flush_time[id(e)] for e in checkpoints if flush_time[id(e)])
bounds = bucket_ts[:]                  # plugin bucket starts; bucket k = [bounds[k], bounds[k+1])
per_label = defaultdict(Acc)           # whole run, every row
cp_delta = defaultdict(lambda: defaultdict(Acc))   # checkpoint interval -> label -> Acc
near_cp = Counter()                    # top-level samples arriving within 1 s of a checkpoint
b_count = Counter(); b_err = Counter(); b_sum = Counter(); b_hist = defaultdict(Counter)
b_label = defaultdict(Counter)
n_rows = 0
ends = []                              # (arrival, label) for throughput steps; top-level only

pending = None   # a transaction parent waits for its children: it reaches listeners at their end


def place(arrival, label, ts, el, ok, b, sb, lat, conn):
    top = not CHILD.search(label)
    k = bisect.bisect_right(cp_times, arrival)
    cp_delta[k][label].add(ts, el, ok, b, sb, lat, conn)
    if top:
        for t in cp_times[max(0, k - 1):k + 1]:
            if abs(arrival - t) <= 1000:
                near_cp[t] += 1
    j = bisect.bisect_right(bounds, arrival) - 1
    if j >= 0:
        b_label[j][label] += 1
        if top:
            b_count[j] += 1; b_err[j] += 0 if ok else 1; b_sum[j] += el; b_hist[j][el] += 1
    if top:
        ends.append(arrival)


with open(p('results.jtl'), newline='', encoding='utf-8', errors='replace') as f:
    for r in csv.DictReader(f):
        try:
            ts = int(r['timeStamp']); el = int(r['elapsed']); label = r['label']
            ok = r['success'] == 'true'; b = int(r['bytes'] or 0); sb = int(r['sentBytes'] or 0)
            lat = int(r['Latency'] or 0); conn = int(r['Connect'] or 0)
        except (KeyError, ValueError):
            continue
        n_rows += 1
        per_label[label].add(ts, el, ok, b, sb, lat, conn)
        if TX_PARENT.search(label):
            if pending:
                place(*pending)
            pending = [ts + el, label, ts, el, ok, b, sb, lat, conn]
            continue
        if CHILD.search(label) and pending:
            pending[0] = max(pending[0], ts + el)
        elif pending:
            place(*pending); pending = None
        place(ts + el, label, ts, el, ok, b, sb, lat, conn)
if pending:
    place(*pending)

truth = {}
total_top = Acc()
for label, a in per_label.items():
    truth[label] = a.stats()
    if not CHILD.search(label):
        total_top.merge(a)
truth[TOTAL] = total_top.stats()

agg = {}
if os.path.exists(p('jmeter-aggregate.json')):
    try:
        agg = json.load(open(p('jmeter-aggregate.json'), encoding='utf-8'))
    except ValueError:
        agg = {}


def html_stats(variant):
    path = p(f'html-report-{variant}', 'statistics.json')
    if not os.path.exists(path):
        return {}
    d = json.load(open(path, encoding='utf-8'))
    out = {}
    for k, v in d.items():
        out[TOTAL if k == 'Total' else k] = {
            'samples': v.get('sampleCount'), 'errors': v.get('errorCount'), 'errorPct': v.get('errorPct'),
            'mean': v.get('meanResTime'), 'min': v.get('minResTime'), 'max': v.get('maxResTime'),
            'p50': v.get('medianResTime'), 'p90': v.get('pct1ResTime'), 'p95': v.get('pct2ResTime'),
            'p99': v.get('pct3ResTime'), 'throughput': v.get('throughput'),
            'receivedKBs': v.get('receivedKBytesPerSec'), 'sentKBs': v.get('sentKBytesPerSec')}
    return out


html_default, html_exact = html_stats('default'), html_stats('exact')


def plugin_final():
    """The plugin's last word: the final live bucket (what the open tab ends on)."""
    if not final_bucket:
        return {}
    c = final_bucket.get('cumulative') or {}
    stats_src = final_bucket.get('samplerStats')
    if stats_src is None:
        # The probe keeps per-sampler stats only at checkpoints; without a final
        # bucket, take them from what the open tab held when the run ended.
        end_snap = next((e for e in browser if e.get('kind') == 'snapshot' and e.get('tag') == 'end'), {})
        stats_src = end_snap.get('samplerStats') or {}
        c = end_snap.get('cumulative') or c
    out = {TOTAL: {'samples': c.get('totalSamples'), 'errors': c.get('totalErrors'),
                   'errorPct': c.get('overallErrorRate'), 'mean': c.get('avgResponseTime'),
                   'min': c.get('minResponseTime'), 'max': c.get('maxResponseTime'),
                   'p50': c.get('p50'), 'p90': c.get('p90'), 'p95': c.get('p95'), 'p99': c.get('p99'),
                   'throughput': c.get('overallThroughput'), 'receivedKBs': c.get('receivedKBs'),
                   'sentKBs': c.get('sentKBs')}}
    for label, s in stats_src.items():
        out[label] = {'samples': s.get('samples'), 'errors': s.get('errors'), 'errorPct': s.get('errorRate'),
                      'mean': s.get('avgResponseTime'), 'min': s.get('minResponseTime'),
                      'max': s.get('maxResponseTime'), 'p50': s.get('p50'), 'p90': s.get('p90'),
                      'p95': s.get('p95'), 'p99': s.get('p99'), 'throughput': s.get('throughput'),
                      'receivedKBs': s.get('receivedKBs'), 'sentKBs': s.get('sentKBs')}
    return out


plugin = plugin_final()

# ---------------------------------------------------------------------------
# Verdicts
# ---------------------------------------------------------------------------
EXACT = ('samples', 'errors', 'min', 'max')
TOL = {'errorPct': (1e-6, 0), 'mean': (1e-6, 0), 'p50': (0.01, 1), 'p90': (0.01, 1), 'p95': (0.01, 1),
       'p99': (0.02, 1), 'throughput': (0.005, 0), 'receivedKBs': (0.005, 0), 'sentKBs': (0.005, 0)}
METRICS = ['samples', 'errors', 'errorPct', 'mean', 'min', 'max', 'p50', 'p90', 'p95', 'p99',
           'throughput', 'receivedKBs', 'sentKBs']


def judge(metric, got, ref):
    if got is None or ref is None:
        return 'n/a'
    if metric in EXACT:
        return 'OK' if abs(got - ref) < 1e-9 else 'DIFF'
    rel, ab = TOL[metric]
    d = abs(got - ref)
    return 'OK' if d <= ab or (ref and d / abs(ref) <= rel) else 'DIFF'


def compare_table(ref, ref_name):
    rows, diffs = [], 0
    perr = defaultdict(list)    # metric -> [(rel err %, label, n, plugin, ref)] over samplers
    for label in sorted(set(plugin) | set(ref), key=lambda x: (x != TOTAL, x)):
        a, b = plugin.get(label, {}), ref.get(label, {})
        for m in METRICS:
            v = judge(m, a.get(m), b.get(m))
            if v == 'DIFF':
                diffs += 1
            if v == 'DIFF' or label == TOTAL:
                rows.append((label, m, a.get(m), b.get(m), v))
            if m in ('p50', 'p90', 'p95', 'p99') and label != TOTAL and a.get(m) is not None and b.get(m):
                perr[m].append((abs(a[m] - b[m]) / b[m] * 100, label, b.get('samples'), a[m], b[m]))
    summary = {}
    for m, xs in perr.items():
        xs.sort()
        worst = xs[-1]
        summary[m] = {'samplers': len(xs), 'medianErrPct': round(xs[len(xs) // 2][0], 2),
                      'maxErrPct': round(worst[0], 2), 'over1pct': sum(1 for x in xs if x[0] > 1),
                      'over5pct': sum(1 for x in xs if x[0] > 5),
                      'worst': {'sampler': worst[1], 'samples': worst[2], 'plugin': worst[3], 'ref': worst[4]}}
    return {'reference': ref_name, 'diffs': diffs, 'rows': rows, 'percentileErr': summary}


# JMeter live Aggregate Report view: transaction children never reach it.
agg_live = {}
if agg:
    agg_live = {k: v for k, v in agg['live']['samplers'].items()}
    agg_live[TOTAL] = agg['live']['total']
    for k, v in agg['file']['samplers'].items():   # children: JMeter only shows them from the file
        if CHILD.search(k):
            agg_live[k] = v
    for v in agg_live.values():
        v['p50'] = v.get('median')

final_vs = {
    'truth (exact, from JTL)': compare_table(truth, 'JTL exact'),
    'JMeter Aggregate Report': compare_table(agg_live, 'JMeter SamplingStatCalculator') if agg_live else None,
    'JMeter HTML report (exact window)': compare_table(html_exact, 'HTML report, statistic_window=-1') if html_exact else None,
    'JMeter HTML report (default)': compare_table(html_default, 'HTML report, defaults') if html_default else None,
}

# Live checkpoints: the plugin's running numbers vs the JTL up to that moment.
cp_rows = []
acc_cum = defaultdict(Acc)
k_done = 0
for e in checkpoints:
    t = flush_time[id(e)]
    if not t:
        continue
    k = bisect.bisect_right(cp_times, t)    # == index of interval after t
    while k_done < k:
        for label, a in cp_delta[k_done].items():
            acc_cum[label].merge(a)
        k_done += 1
    tot = Acc()
    for label, a in acc_cum.items():
        if not CHILD.search(label):
            tot.merge(a)
    st = tot.stats()
    c = e.get('cumulative') or {}
    got = c.get('totalSamples')
    worst_pct = 0.0
    for q in ('p90', 'p95', 'p99'):
        # A handful of early samples says nothing about percentile accuracy.
        if st.get(q) and c.get(q) is not None and st['samples'] >= 1000:
            worst_pct = max(worst_pct, abs(c[q] - st[q]) / st[q] * 100)
    cp_rows.append({'t': t, 'plugin': got, 'jtl': st.get('samples'), 'near': near_cp.get(t, 0),
                    'countOk': got is not None and abs(got - st['samples']) <= near_cp.get(t, 0),
                    'p95': c.get('p95'), 'p95jtl': st.get('p95'), 'worstPctErr': round(worst_pct, 2),
                    'meanErr': abs((c.get('avgResponseTime') or 0) - (st.get('mean') or 0))})

# Timeline: each plugin bucket vs the JTL samples that arrived inside it.
tl = {'buckets': len(live_buckets), 'exact': 0, 'absDiff': 0, 'maxDiff': 0, 'bucketSum': 0,
      'p95Err': [], 'meanErr': []}
for j, e in enumerate(live_buckets):
    got = (e.get('overall') or {}).get('samples') or 0
    want = b_count.get(j, 0)
    tl['bucketSum'] += got
    d = abs(got - want)
    tl['absDiff'] += d
    tl['maxDiff'] = max(tl['maxDiff'], d)
    tl['exact'] += d == 0
    ov = e.get('overall') or {}
    if want >= 20 and ov.get('p95') is not None:
        tp = pct_exact(b_hist[j], want, jfloat(.95))
        tl['p95Err'].append(abs(ov['p95'] - tp) / tp * 100 if tp else 0)
        tl['meanErr'].append(abs(ov['avgResponseTime'] - b_sum[j] / want))
tl['jtlTopLevel'] = truth[TOTAL]['samples']
tl['p95ErrMedian'] = statistics.median(tl['p95Err']) if tl['p95Err'] else None
tl['p95ErrMax'] = max(tl['p95Err']) if tl['p95Err'] else None
tl['meanErrMax'] = max(tl['meanErr']) if tl['meanErr'] else None
del tl['p95Err'], tl['meanErr']

# Delivery: how regularly updates arrive and how big they get.
recv = [e['t'] for e in live_buckets if e.get('testRunning') is not False]
gaps = [b - a for a, b in zip(recv, recv[1:])]
sgaps = [b - a for a, b in zip(bucket_ts, bucket_ts[1:])]
lag = [e['t'] - flush_time[id(e)] for e in live_buckets if flush_time[id(e)]]
off = min(lag) if lag else 0
sizes = [e['size'] for e in live_buckets]


def dist(xs):
    if not xs:
        return None
    s = sorted(xs)
    return {'n': len(s), 'min': s[0], 'p50': s[len(s) // 2], 'p99': s[min(len(s) - 1, int(len(s) * .99))],
            'max': s[-1], 'mean': round(sum(s) / len(s), 1)}


delivery = {'recvGapMs': dist(gaps), 'serverGapMs': dist(sgaps),
            'over2500ms': sum(1 for g in gaps if g > 2500), 'over5000ms': sum(1 for g in gaps if g > 5000),
            'relativeLagMs': dist([x - off for x in lag]), 'payloadBytes': dist(sizes),
            'payloadFirst': sizes[0] if sizes else None, 'payloadLast': sizes[-1] if sizes else None,
            'catchups': [{'phase': c['phase'], 'size': c['size'], 'buckets': c.get('buckets')} for c in catchups],
            # Compressed bytes on the socket, when the server gzips the stream
            'payloadWire': dist([e['wire'] for e in live_buckets if e.get('wire') is not None]),
            'catchupWire': [{'phase': c['phase'], 'wire': c.get('wire')} for c in catchups if c.get('wire') is not None],
            'slotAligned': slot_aligned,
            # Samples whose slot had already closed (end-time slots only); 0 in a normal run
            'lateSamples': ((final_bucket or {}).get('cumulative') or {}).get('lateSamples')}

# After the end: does anything still change?
after_end = {}
if final_bucket and late_catchup:
    a, b = final_bucket.get('samplerStats') or {}, late_catchup.get('samplerStats') or {}
    changed = []
    for label in sorted(set(a) | set(b)):
        for m in ('samples', 'throughput', 'receivedKBs', 'sentKBs', 'p95', 'avgResponseTime'):
            x, y = (a.get(label) or {}).get(m), (b.get(label) or {}).get(m)
            if x != y:
                changed.append((label, m, x, y))
    ca, cb = final_bucket.get('cumulative') or {}, late_catchup.get('cumulative') or {}
    for m in ('totalSamples', 'overallThroughput', 'receivedKBs', 'elapsedTime', 'p95'):
        if ca.get(m) != cb.get(m):
            changed.append((TOTAL, m, ca.get(m), cb.get(m)))
    after_end['reconnectAfterEnd'] = changed
snaps = {e['tag']: e for e in browser if e.get('kind') == 'snapshot'}
if 'end' in snaps and 'end-plus-linger' in snaps:
    r1, r2 = snaps['end']['rows'], snaps['end-plus-linger']['rows']
    after_end['tableRowsChanged'] = [(x, y) for x, y in zip(r1, r2) if x != y][:20]
    after_end['tableRowCount'] = (len(r1), len(r2))

# Resources
heap = []
gc_re = re.compile(r'\[(\d+)ms\].*?GC\(\d+\) (Pause [^(]+(?:\([^)]*\))*).*?(\d+)M->(\d+)M\((\d+)M\)')
if os.path.exists(p('gc.log')):
    for line in open(p('gc.log'), encoding='utf-8', errors='replace'):
        m = gc_re.search(line)
        if m:
            heap.append((int(m.group(1)), m.group(2).strip(), int(m.group(4)), int(m.group(5))))
full_gcs = sum(1 for h in heap if 'Full' in h[1])

# Wall-clock steps inside the container: the GC log stamps every line with
# both wall time and JVM uptime, so a change in (wall - uptime) is the wall
# clock jumping (Docker Desktop / VM time sync). Timeline mismatches next to a
# step are the clock, not the plugin's counting.
clock_steps = []
wall_re = re.compile(r'^\[(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3})[+-]\d{4}\]\[(\d+)ms\]')
if os.path.exists(p('gc.log')):
    import datetime
    prev = None
    for line in open(p('gc.log'), encoding='utf-8', errors='replace'):
        m = wall_re.match(line)
        if not m:
            continue
        wall = datetime.datetime.strptime(m.group(1), '%Y-%m-%dT%H:%M:%S.%f').replace(
            tzinfo=datetime.timezone.utc).timestamp() * 1000
        off = wall - int(m.group(2))
        if prev is not None and abs(off - prev[1]) > 200:
            clock_steps.append({'atWallMs': int(wall), 'stepMs': int(off - prev[1])})
        prev = (wall, off)
resources = {'gcEvents': len(heap), 'fullGcs': full_gcs, 'containerClockSteps': clock_steps}
if heap:
    after = [h[2] for h in heap]
    n = len(after)
    resources.update({'heapCapMB': heap[-1][3], 'afterGcMB_firstQuarter': max(after[: max(1, n // 4)]),
                      'afterGcMB_lastQuarter': max(after[-max(1, n // 4):]), 'afterGcMB_max': max(after)})
    # After a mixed GC old regions were collected too: the closest the log
    # gets to the live set (scenarios with a low marking threshold have many)
    mixed = [h for h in heap if 'Mixed' in h[1]]
    if mixed:
        end = heap[-1][0]
        resources['afterMixedMB_last'] = mixed[-1][2]
        resources['afterMixedMB_byQuarter'] = [max([h[2] for h in mixed if q * end / 4 <= h[0] < (q + 1) * end / 4] or [0]) for q in range(4)]
    half = [(h[0] / 60000.0, h[2]) for h in heap[n // 2:]]
    if len(half) > 2:
        xm = sum(x for x, _ in half) / len(half); ym = sum(y for _, y in half) / len(half)
        den = sum((x - xm) ** 2 for x, _ in half)
        resources['afterGcSlopeMBperMin_2ndHalf'] = round(sum((x - xm) * (y - ym) for x, y in half) / den, 2) if den else 0
cpu = []
if os.path.exists(p('stats.txt')):
    for line in open(p('stats.txt'), encoding='utf-8'):
        parts = line.strip().split('|')
        if len(parts) >= 3 and parts[1].endswith('%'):
            cpu.append(float(parts[1][:-1]))
resources['cpuPct'] = dist(cpu)

# Achieved rate per 30 s window (top-level arrivals): load actually generated.
ends.sort()
rate = []
if ends:
    t0 = ends[0]
    win = Counter((t - t0) // 30000 for t in ends)
    rate = [win[i] / 30.0 for i in range(max(win) + 1)]
achieved = {'perWindow30s': [round(x, 1) for x in rate], 'jtlRows': n_rows,
            'topLevel': truth[TOTAL]['samples'], 'overall': truth[TOTAL].get('throughput')}

bsamples = [e for e in browser if e.get('kind') == 'sample' and e.get('heapUsed')]
browser_sum = {}
if bsamples:
    hu = [e['heapUsed'] / 1e6 for e in bsamples]
    # The long-task counters live in the page, so a reload restarts them from
    # zero; reading only the last sample reported B180-medium's 4,400 long
    # tasks as the 113 after its reload. Sum each page's final count, a page
    # ending at each recorded reload.
    cuts = sorted(e['t'] for e in browser if e.get('kind') == 'reload') + [math.inf]
    long_n = long_ms = 0
    for lo, hi in zip([-math.inf] + cuts[:-1], cuts):
        page = [e for e in bsamples if lo <= e['t'] < hi]
        if page:
            long_n += page[-1].get('longCount') or 0
            long_ms += page[-1].get('longTotal') or 0
    browser_sum = {'heapMB_first': round(hu[0], 1), 'heapMB_last': round(hu[-1], 1), 'heapMB_max': round(max(hu), 1),
                   'domFirst': bsamples[0]['dom'], 'domLast': bsamples[-1]['dom'],
                   'longTasks': long_n, 'longTaskMsTotal': long_ms,
                   'longTaskMsMax': max((e.get('longMax') or 0) for e in bsamples),
                   'chartPointsLast': bsamples[-1].get('points'),
                   'reload': [e for e in browser if e.get('kind') == 'reload'],
                   'pageErrors': [e['message'] for e in browser if e.get('kind') in ('pageerror', 'console-error')][:10]}

# JMeter's own health: did the plugin hurt the test it reports on?
health = {'oomErrors': 0, 'firstOomAtS': None, 'threadsDiedEarly': 0, 'summariser': []}
jl = p('jmeter.log')
if os.path.exists(jl):
    import datetime
    ts_re = re.compile(r'^(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d),(\d{3})')
    sum_re = re.compile(r'summary \+\s+(\d+) in [\d:]+ =\s+([\d.]+)/s.*Active: (\d+) Started: (\d+) Finished: (\d+)')
    t0 = None
    t = None
    for line in open(jl, encoding='utf-8', errors='replace'):
        m = ts_re.match(line)
        if m:
            t = datetime.datetime.strptime(m.group(1), '%Y-%m-%d %H:%M:%S').timestamp() + int(m.group(2)) / 1000
        elif 'OutOfMemoryError' not in line:
            continue    # stack-trace lines carry no timestamp; OOM ones take the last one seen
        if t0 is None and 'Starting standalone test' in line or (t0 is None and 'Running the test' in line):
            t0 = t
        if 'OutOfMemoryError' in line:
            health['oomErrors'] += 1
            if health['firstOomAtS'] is None and t0 and t:
                health['firstOomAtS'] = round(t - t0)
        sm = sum_re.search(line) if m else None
        if sm and t0:
            at = round(t - t0)
            health['summariser'].append((at, float(sm.group(2)), int(sm.group(3)), int(sm.group(5))))
            if at < scenario['duration'] - 30:
                health['threadsDiedEarly'] = max(health['threadsDiedEarly'], int(sm.group(5)))

report = {'scenario': scenario, 'achieved': achieved, 'finalVs': final_vs, 'checkpoints': cp_rows,
          'timeline': tl, 'delivery': delivery, 'afterEnd': after_end, 'resources': resources,
          'browser': browser_sum, 'pluginPresent': bool(sse), 'finalBucketDelivered': final_delivered,
          'jmeterHealth': health}
with open(p('report.json'), 'w', encoding='utf-8') as f:
    json.dump(report, f, indent=1, default=str)


# ---------------------------------------------------------------------------
# report.md
# ---------------------------------------------------------------------------
def fmt(v):
    if v is None:
        return '-'
    if isinstance(v, float):
        return f'{v:,.3f}' if abs(v) < 1000 else f'{v:,.1f}'
    return f'{v:,}' if isinstance(v, int) else str(v)


L = [f"# {scenario['name']}: {scenario['what']}", '',
     f"jar `{scenario['jar']}` at `{scenario['gitSha']}`, started {scenario['started']}, "
     f"{scenario['duration']} s, plugin={'yes' if scenario['plugin'] else 'no'}", '',
     f"**Load generated:** {achieved['topLevel']:,} top-level samples ({n_rows:,} JTL rows), "
     f"{fmt(achieved['overall'])}/s overall.", '']
if sse:
    for name, t in final_vs.items():
        if not t:
            continue
        L.append(f"## Final numbers vs {name}: {t['diffs']} difference(s) beyond tolerance")
        L.append('')
        L.append('| sampler | metric | plugin | reference | verdict |')
        L.append('|---|---|---|---|---|')
        shown = [r for r in t['rows'] if r[0] == TOTAL or r[1] not in ('p50', 'p90', 'p95', 'p99')]
        for r in shown[:40]:
            L.append(f'| {r[0]} | {r[1]} | {fmt(r[2])} | {fmt(r[3])} | {r[4]} |')
        if len(shown) > 40:
            L.append(f"| ... | {len(shown) - 40} more rows in report.json | | | |")
        L.append('')
        if t['percentileErr']:
            L.append('Per-sampler percentile error (|plugin - reference| / reference):')
            L.append('')
            L.append('| metric | samplers | median err % | max err % | > 1 % | > 5 % | worst |')
            L.append('|---|---|---|---|---|---|---|')
            for m in ('p50', 'p90', 'p95', 'p99'):
                s = t['percentileErr'].get(m)
                if s:
                    w = s['worst']
                    L.append(f"| {m} | {s['samplers']} | {s['medianErrPct']} | {s['maxErrPct']} | {s['over1pct']} | "
                             f"{s['over5pct']} | {w['sampler']} (n={fmt(w['samples'])}): {fmt(w['plugin'])} vs {fmt(w['ref'])} |")
            L.append('')
    ok = sum(1 for c in cp_rows if c['countOk'])
    L += ['## Live accuracy during the run', '',
          f"{ok}/{len(cp_rows)} checkpoints had the exact sample count (allowing samples finishing within 1 s of the snapshot). "
          f"Worst percentile error at any checkpoint: {max((c['worstPctErr'] for c in cp_rows), default=0)}%.", '',
          '## Timeline (2 s buckets) vs the JTL', '',
          f"{tl['exact']}/{tl['buckets']} buckets exact; total |diff| {tl['absDiff']}, worst bucket {tl['maxDiff']}. "
          f"Bucket sum {tl['bucketSum']:,} vs JTL top-level {tl['jtlTopLevel']:,}. "
          f"Per-bucket p95 error median {fmt(tl['p95ErrMedian'])}%, max {fmt(tl['p95ErrMax'])}%; "
          f"mean error max {fmt(tl['meanErrMax'])} ms.", '',
          '## Delivery', '',
          f"Update gap as received: {delivery['recvGapMs']}", '',
          f"Server flush gap: {delivery['serverGapMs']}", '',
          f"Gaps > 2.5 s: {delivery['over2500ms']}, > 5 s: {delivery['over5000ms']}. "
          f"Relative lag: {delivery['relativeLagMs']}", '',
          f"Payload bytes: {delivery['payloadBytes']} (first {delivery['payloadFirst']}, last {delivery['payloadLast']}). "
          f"Catch-ups: {delivery['catchups']}", '',
          f"Slots aligned to the clock: {delivery['slotAligned']}; late samples: {delivery['lateSamples']}", '',
          f"On the wire (gzip): {delivery['payloadWire'] or 'not compressed'}; catch-ups {delivery['catchupWire']}", '',
          '## After the end', '',
          f"Final (testRunning=false) bucket delivered: {final_delivered}. {after_end}", '']
L += ['## JMeter health', '', f"OutOfMemoryError lines: {health['oomErrors']}, first at {health['firstOomAtS']} s; "
      f"threads that died before the planned end: {health['threadsDiedEarly']}. "
      f"Summariser (s, rate/s, active, finished): {health['summariser']}", '']
L += ['## Resources', '', f'{resources}', '']
if browser_sum:
    L += ['## Browser', '', f'{browser_sum}', '']
L += ['## Achieved rate per 30 s', '', ' '.join(fmt(x) for x in achieved['perWindow30s']), '']
with open(p('report.md'), 'w', encoding='utf-8') as f:
    f.write('\n'.join(L))
print('\n'.join(L[:12]))
print(f"[PERF] report: {p('report.md')}")

# The verdict, as the exit code, so run-all.sh's rc= means something: the dashboard's
# final numbers against the two exact references. The HTML report tables are not part
# of it: by default that report computes percentiles over a window, so they differ by
# design.
failed = [n for n in ('truth (exact, from JTL)', 'JMeter Aggregate Report') if final_vs.get(n) and final_vs[n]['diffs']]
if scenario['plugin'] and not sse:
    failed.append('the plugin sent nothing')
if failed:
    print(f"[PERF] FAIL: {'; '.join(failed)}")
    sys.exit(1)
