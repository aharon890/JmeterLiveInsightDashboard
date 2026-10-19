package com.jmeterinsight.livedashboard.engine;

import org.apache.jorphan.math.StatCalculatorLong;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Percentiles must be the Aggregate Report's, so they are checked against
 * JMeter's own calculator (StatCalculatorLong) on identical inputs — not
 * against a re-derivation of its rules.
 */
class ExactHistogramTest {

    private static final double[] PERCENTS = ExactHistogram.REPORTED;

    /** Feeds both calculators, then compares every reported percentile. */
    private static void assertSameAsJMeter(long[] values, String what) {
        ExactHistogram h = new ExactHistogram();
        StatCalculatorLong jm = new StatCalculatorLong();
        long max = Long.MIN_VALUE;
        for (long v : values) {
            h.add(v);
            jm.addValue(v);
            max = Math.max(max, v);
        }
        double[] ours = h.percentiles(PERCENTS, max);
        for (int k = 0; k < PERCENTS.length; k++) {
            long theirs = jm.getPercentPoint(PERCENTS[k]);
            assertEquals((double) theirs, ours[k], 0.0,
                    what + ": percent " + PERCENTS[k] + " (n=" + values.length + ")");
        }
        assertEquals(jm.getMedian().doubleValue(), ours[0], 0.0, what + ": median");
    }

    @Test
    void matchesJMeterAtTieCounts() {
        // Counts where count x percent lands on .5 for some percent: p99 ties
        // round UP (0.99f widens to 0.99000001), p90/p95 ties round DOWN, the
        // median (exactly 0.5) rounds up — the arithmetic, not a tie rule.
        for (int n : new int[]{1, 2, 3, 4, 5, 10, 20, 25, 50, 100, 150, 1000}) {
            long[] v = new long[n];
            for (int i = 0; i < n; i++) v[i] = (i * 37L) % 1000 + 1;   // distinct, unsorted
            assertSameAsJMeter(v, "distinct n=" + n);
        }
    }

    @Test
    void matchesJMeterOnRandomDistributions() {
        Random rnd = new Random(42);
        for (int trial = 0; trial < 200; trial++) {
            int n = 1 + rnd.nextInt(3000);
            long[] v = new long[n];
            int shape = trial % 4;
            for (int i = 0; i < n; i++) {
                switch (shape) {
                    case 0: v[i] = rnd.nextInt(50); break;                                   // many duplicates
                    case 1: v[i] = (long) Math.abs(rnd.nextGaussian() * 80 + 300); break;   // bell
                    case 2: v[i] = (long) (Math.exp(rnd.nextDouble() * 9)); break;          // long tail < 10 s
                    default: v[i] = rnd.nextInt(2) == 0 ? 5 : 9_000 + rnd.nextInt(999);     // bimodal
                }
            }
            assertSameAsJMeter(v, "trial " + trial + " shape " + shape);
        }
    }

    @Test
    void edgeCases() {
        assertSameAsJMeter(new long[]{0}, "single zero");
        assertSameAsJMeter(new long[]{7, 7, 7, 7}, "all equal");
        assertSameAsJMeter(new long[]{9_999, 0, 9_999}, "at the exact bound");
        ExactHistogram empty = new ExactHistogram();
        assertArrayEquals(new double[4], empty.percentiles(PERCENTS, 0), "empty gives zeros, never NaN");
    }

    @Test
    void aboveTheBoundIsWithinATenthOfAPercent() {
        Random rnd = new Random(7);
        for (int i = 0; i < 20_000; i++) {
            long v = 10_000 + (long) (rnd.nextDouble() * 3_600_000);
            long reported = ExactHistogram.valueOf(ExactHistogram.cellOf(v));
            assertTrue(Math.abs(reported - v) <= v * 0.001 + 1,
                    v + " reported as " + reported);
        }
        long huge = ExactHistogram.valueOf(ExactHistogram.cellOf(Long.MAX_VALUE));
        assertTrue(huge > 0 && huge <= (long) Integer.MAX_VALUE * 1.001, "capped, not overflowed: " + huge);
    }

    @Test
    void percentOfOneGivesTheExactMax() {
        ExactHistogram h = new ExactHistogram();
        h.add(5);
        h.add(123_456);
        assertEquals(123_456.0, h.percentile(1.0, 123_456), 0.0);
    }

    @Test
    void concurrentAddsAreNeverLost() throws InterruptedException {
        ExactHistogram h = new ExactHistogram();
        int threads = 16, perThread = 50_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            // Every thread touches fresh chunks at the same moments: the race
            // a copy-on-grow array would lose increments to
            pool.submit(() -> {
                for (int i = 0; i < perThread; i++) h.add((i * 7L + seed) % 20_000);
            });
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals((long) threads * perThread, h.count());
    }

    /**
     * An add that opens a higher chunk while a live read is in progress must not
     * zero the upper percentiles. The scan's bound was read before the count, so
     * the new value counted toward n but was never scanned, and every target
     * above the old count stayed 0: p90/p95/p99 read 0 for one update (1 in
     * ~530,000 reads under stress). The spy runs that add at the worst moment,
     * just before count().
     */
    @Test
    void aChunkOpenedDuringAReadIsScanned() {
        ExactHistogram h = spy(new ExactHistogram());
        h.add(50);
        AtomicBoolean once = new AtomicBoolean();
        doAnswer(inv -> {
            if (once.compareAndSet(false, true)) h.add(20_000);   // a far higher chunk
            return inv.callRealMethod();
        }).when(h).count();

        double[] p = h.percentiles(PERCENTS, 20_000);

        double high = ExactHistogram.valueOf(ExactHistogram.cellOf(20_000));
        assertArrayEquals(new double[]{50, high, high, high}, p, 0.0,
                "n = 2: the median is the first value, p90/p95/p99 the second");
    }
}
