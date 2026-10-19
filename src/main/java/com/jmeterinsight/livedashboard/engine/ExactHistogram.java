package com.jmeterinsight.livedashboard.engine;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Response-time counts from which percentiles are computed the way JMeter's
 * Aggregate Report computes them (StatCalculator.getPercentPoint).
 *
 * This replaced a T-Digest. The digest was ~0.3% off at 10,000+ samples per
 * sampler, but p99 was off by up to 11.5% at ~500 samples (438 shown vs 393
 * exact, perf run B10) — and the Aggregate Report is exact, so the dashboard
 * disagreed with JMeter on exactly the column people check first. It also
 * needed a lock on every add.
 *
 * Each millisecond value up to {@link #EXACT_BOUND_MS} has its own counter, so
 * any percentile below the bound is exact. Above it, counters are 0.1% wide
 * (a 30 s value is reported within 30 ms), which bounds memory for
 * distributions spreading over minutes; JMeter's exact calculator has no such
 * bound, one reason it is GUI-only. Values are capped at Integer.MAX_VALUE ms.
 *
 * Counters live in small chunks allocated on first use, so a sampler whose
 * responses all fall under 200 ms holds ~4 chunks, not 10,000 counters. Adds
 * are lock-free: chunks are published with a CAS and never copied (a grown
 * array would lose increments racing with the copy), and an increment is one
 * CAS on one cell — contended only by threads recording the very same
 * millisecond at the very same moment.
 */
final class ExactHistogram {

    static final int EXACT_BOUND_MS = 10_000;
    private static final double LOG_STEP = Math.log1p(0.001);
    private static final int LOG_CELLS =
            (int) Math.ceil(Math.log((double) Integer.MAX_VALUE / EXACT_BOUND_MS) / LOG_STEP) + 1;
    static final int CELLS = EXACT_BOUND_MS + LOG_CELLS;

    private static final int CHUNK_SHIFT = 6;
    private static final int CHUNK = 1 << CHUNK_SHIFT;
    private static final int CHUNK_MASK = CHUNK - 1;

    /**
     * The Aggregate Report's p90/p95/p99 columns parse their percent as a
     * float ({@code Float.parseFloat("90") / 100f}) and widen it to double, so
     * 0.9 is really 0.89999998 — and that decides which rank a tie rounds to.
     * Mirror the arithmetic, not a rule about ties. The median is exactly 0.5.
     */
    static final double P50 = 0.5;
    static final double P90 = jmeterPercent(90);
    static final double P95 = jmeterPercent(95);
    static final double P99 = jmeterPercent(99);
    static final double[] REPORTED = {P50, P90, P95, P99};

    static double jmeterPercent(double pct) {
        return (double) ((float) pct / 100f);
    }

    private final AtomicReferenceArray<AtomicLongArray> chunks =
            new AtomicReferenceArray<>((CELLS + CHUNK - 1) >>> CHUNK_SHIFT);
    // Highest chunk ever allocated: queries scan no further
    private final AtomicInteger highestChunk = new AtomicInteger(-1);

    void add(long ms) {
        int cell = cellOf(ms);
        int c = cell >>> CHUNK_SHIFT;
        AtomicLongArray chunk = chunks.get(c);
        if (chunk == null) {
            AtomicLongArray fresh = new AtomicLongArray(CHUNK);
            if (chunks.compareAndSet(c, null, fresh)) {
                chunk = fresh;
                highestChunk.accumulateAndGet(c, Math::max);
            } else {
                chunk = chunks.get(c);
            }
        }
        chunk.incrementAndGet(cell & CHUNK_MASK);
    }

    static int cellOf(long ms) {
        if (ms < EXACT_BOUND_MS) return (int) Math.max(0, ms);
        long v = Math.min(ms, Integer.MAX_VALUE);
        int i = (int) Math.floor(Math.log((double) v / EXACT_BOUND_MS) / LOG_STEP);
        return Math.min(EXACT_BOUND_MS + i, CELLS - 1);
    }

    /** The value a cell reports: exact below the bound, its lower edge above. */
    static long valueOf(int cell) {
        if (cell < EXACT_BOUND_MS) return cell;
        return (long) Math.ceil(EXACT_BOUND_MS * Math.exp((cell - EXACT_BOUND_MS) * LOG_STEP));
    }

    /**
     * Percentile points exactly as StatCalculator.getPercentPoint computes
     * them: target = Math.round(count x percent); the first value whose
     * cumulative count reaches the target (a target of 0 gives the minimum);
     * a percent of 1 or more gives the maximum. {@code percents} ascending.
     * An empty histogram gives zeros. {@code max} is the exact maximum, which
     * a capped or 0.1%-wide cell could not report.
     */
    double[] percentiles(double[] percents, long max) {
        double[] out = new double[percents.length];
        // Counts may grow while we read. Cells only ever increase, so the scan
        // reaches every target the count set — provided it covers every chunk
        // the count did. So the scan's bound is read AFTER the count: read
        // before, a chunk opened in between counted toward n but was never
        // scanned, and p90/p95/p99 came out 0 for that read.
        long n = count();
        if (n == 0) return out;
        int top = highestChunk.get();
        long[] targets = new long[percents.length];
        int j = 0;
        for (int k = 0; k < percents.length; k++) {
            if (percents[k] >= 1.0) {
                out[k] = max;
                targets[k] = Long.MIN_VALUE; // answered
            } else {
                targets[k] = Math.round(n * percents[k]);
            }
        }
        long cum = 0;
        scan:
        for (int c = 0; c <= top; c++) {
            AtomicLongArray chunk = chunks.get(c);
            if (chunk == null) continue;
            for (int i = 0; i < CHUNK; i++) {
                long cnt = chunk.get(i);
                if (cnt == 0) continue;
                cum += cnt;
                long value = valueOf((c << CHUNK_SHIFT) + i);
                while (j < targets.length && (targets[j] == Long.MIN_VALUE || cum >= targets[j])) {
                    if (targets[j] != Long.MIN_VALUE) out[j] = value;
                    j++;
                }
                if (j == targets.length) break scan;
            }
        }
        return out;
    }

    /** Total recorded values. */
    long count() {
        long n = 0;
        int top = highestChunk.get();
        for (int c = 0; c <= top; c++) {
            AtomicLongArray chunk = chunks.get(c);
            if (chunk == null) continue;
            for (int i = 0; i < CHUNK; i++) n += chunk.get(i);
        }
        return n;
    }

    double percentile(double percent, long max) {
        return percentiles(new double[]{percent}, max)[0];
    }
}
