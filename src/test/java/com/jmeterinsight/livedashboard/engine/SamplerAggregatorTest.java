package com.jmeterinsight.livedashboard.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class SamplerAggregatorTest {

    private static final double APDEX_THRESHOLD = 500.0;

    @Test
    void testInitialState() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        assertEquals("TestSampler", agg.getName());
        assertEquals(0, agg.getSampleCount());
        assertEquals(0, agg.getErrorCount());
        assertEquals(0.0, agg.getAvgResponseTime());
        assertEquals(0.0, agg.getErrorRate());
        assertEquals(0.0, agg.getPercentile(50));
        assertEquals(1.0, agg.getApdex());
    }

    @Test
    void testRecordSample() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        agg.addSample(200, true, 1024, 512, 10, 50, APDEX_THRESHOLD);

        assertEquals(1, agg.getSampleCount());
        assertEquals(200.0, agg.getAvgResponseTime());
        assertEquals(0, agg.getErrorCount());

        agg.addSample(400, true, 2048, 256, 20, 80, APDEX_THRESHOLD);

        assertEquals(2, agg.getSampleCount());
        assertEquals(300.0, agg.getAvgResponseTime());
    }

    @Test
    void testPercentiles() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // Add 1000 samples with values 1..1000 to get stable percentile estimates
        for (int i = 1; i <= 1000; i++) {
            agg.addSample(i, true, 100, 50, 5, 10, 0);
        }

        double tolerance = 20.0; // T-Digest is approximate
        assertEquals(500, agg.getPercentile(50), tolerance, "p50 should be near 500");
        assertEquals(900, agg.getPercentile(90), tolerance, "p90 should be near 900");
        assertEquals(950, agg.getPercentile(95), tolerance, "p95 should be near 950");
        assertEquals(990, agg.getPercentile(99), tolerance, "p99 should be near 990");
    }

    @Test
    void testErrorCount() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        agg.addSample(100, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(200, false, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(300, false, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(400, true, 100, 50, 5, 10, APDEX_THRESHOLD);

        assertEquals(4, agg.getSampleCount());
        assertEquals(2, agg.getErrorCount());
        assertEquals(50.0, agg.getErrorRate(), 0.001);
    }

    @Test
    void testMinMaxTracking() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        agg.addSample(300, true, 100, 50, 5, 10, 0);
        agg.addSample(100, true, 100, 50, 5, 10, 0);
        agg.addSample(500, true, 100, 50, 5, 10, 0);
        agg.addSample(200, true, 100, 50, 5, 10, 0);

        Map<String, Object> map = agg.toMap();
        assertEquals(100L, map.get("minResponseTime"));
        assertEquals(500L, map.get("maxResponseTime"));
    }

    @Test
    void testMeanCalculation() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        agg.addSample(100, true, 100, 50, 5, 10, 0);
        agg.addSample(200, true, 100, 50, 5, 10, 0);
        agg.addSample(300, true, 100, 50, 5, 10, 0);

        // mean = (100 + 200 + 300) / 3 = 200.0
        assertEquals(200.0, agg.getAvgResponseTime(), 0.001);
    }

    @Test
    void testApdexSatisfied() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // All samples below threshold (500ms) -> all satisfied
        agg.addSample(100, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(200, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(300, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(500, true, 100, 50, 5, 10, APDEX_THRESHOLD);

        // apdex = (4 + 0/2) / 4 = 1.0
        assertEquals(1.0, agg.getApdex(), 0.001);
    }

    @Test
    void testApdexTolerating() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // All samples between T (500) and JMeter's tolerated threshold (1500,
        // inclusive, as in JMeter's HTML report) -> all tolerating
        agg.addSample(600, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(1000, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(1200, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(1500, true, 100, 50, 5, 10, APDEX_THRESHOLD);

        // apdex = (0 + 4/2) / 4 = 0.5
        assertEquals(0.5, agg.getApdex(), 0.001);
    }

    @Test
    void testApdexBetweenJMetersToleratedThresholdAndFourTIsFrustrated() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // 4T would call these tolerating; JMeter's report calls them frustrated
        agg.addSample(1501, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(2000, true, 100, 50, 5, 10, APDEX_THRESHOLD);

        assertEquals(0.0, agg.getApdex(), 0.001);
    }

    @Test
    void testApdexFrustrated() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // All samples above the tolerated threshold -> all frustrated
        agg.addSample(2001, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(3000, true, 100, 50, 5, 10, APDEX_THRESHOLD);
        agg.addSample(5000, true, 100, 50, 5, 10, APDEX_THRESHOLD);

        // apdex = (0 + 0/2) / 3 = 0.0
        assertEquals(0.0, agg.getApdex(), 0.001);
    }

    @Test
    void testApdexMixed() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // 2 satisfied, 2 tolerating, 1 frustrated
        agg.addSample(100, true, 100, 50, 5, 10, APDEX_THRESHOLD);  // satisfied
        agg.addSample(400, true, 100, 50, 5, 10, APDEX_THRESHOLD);  // satisfied
        agg.addSample(800, true, 100, 50, 5, 10, APDEX_THRESHOLD);  // tolerating
        agg.addSample(1500, true, 100, 50, 5, 10, APDEX_THRESHOLD); // tolerating
        agg.addSample(3000, true, 100, 50, 5, 10, APDEX_THRESHOLD); // frustrated

        // apdex = (2 + 2/2) / 5 = 3/5 = 0.6
        assertEquals(0.6, agg.getApdex(), 0.001);
    }

    @Test
    void testApdexErrors() {
        SamplerAggregator agg = new SamplerAggregator("TestSampler");

        // 1 satisfied, 1 error (counts as frustrated regardless of elapsed)
        agg.addSample(100, true, 100, 50, 5, 10, APDEX_THRESHOLD);  // satisfied
        agg.addSample(100, false, 100, 50, 5, 10, APDEX_THRESHOLD); // frustrated (error)

        // apdex = (1 + 0/2) / 2 = 0.5
        assertEquals(0.5, agg.getApdex(), 0.001);
    }

    @Test
    void testToMap() {
        SamplerAggregator agg = new SamplerAggregator("LoginRequest");

        agg.addSample(150, true, 2048, 512, 10, 30, APDEX_THRESHOLD);
        agg.addSample(350, false, 1024, 256, 20, 60, APDEX_THRESHOLD);

        Map<String, Object> map = agg.toMap();

        assertEquals("LoginRequest", map.get("name"));
        assertEquals(2L, map.get("samples"));
        assertEquals(1L, map.get("errors"));
        assertEquals(50.0, (double) map.get("errorRate"), 0.001);
        assertEquals(250.0, (double) map.get("avgResponseTime"), 0.001);
        assertEquals(150L, map.get("minResponseTime"));
        assertEquals(350L, map.get("maxResponseTime"));
        assertEquals(3072L, map.get("totalBytes"));
        assertEquals(768L, map.get("totalSentBytes"));
        assertEquals(15.0, (double) map.get("avgConnectTime"), 0.001);
        assertEquals(45.0, (double) map.get("avgLatency"), 0.001);

        // Verify all expected keys are present
        String[] expectedKeys = {
            "name", "samples", "errors", "errorRate",
            "avgResponseTime", "minResponseTime", "maxResponseTime",
            "p50", "p90", "p95", "p99",
            "totalBytes", "totalSentBytes",
            "avgConnectTime", "avgLatency",
            "throughput", "apdex"
        };
        for (String key : expectedKeys) {
            assertTrue(map.containsKey(key), "Map should contain key: " + key);
        }
    }

    @Test
    void testPercentilesNeverNaN() {
        SamplerAggregator agg = new SamplerAggregator("Fresh");
        // Empty digest must yield 0, never NaN (NaN serializes to invalid JSON)
        assertEquals(0.0, agg.getPercentile(95), 0.0);
        assertFalse(Double.isNaN(agg.getPercentile(95)));
        Map<String, Object> map = agg.toMap();
        for (String k : new String[]{"p50", "p90", "p95", "p99"}) {
            assertFalse(Double.isNaN((Double) map.get(k)), k + " must not be NaN");
        }
    }

    @Test
    void testActiveWindowFromOwnTimestamps() {
        SamplerAggregator agg = new SamplerAggregator("Login");

        // Untracked (timestamp 0) -> window unknown
        assertEquals(-1.0, agg.getActiveWindowMs(), 0.001);

        // Sampler active from t=1000 to t=1200+100 across a 60s notional test
        agg.addSample(100, true, 100, 50, 5, 10, 0, 1000L);
        agg.addSample(100, true, 100, 50, 5, 10, 0, 1200L);

        // Window = last end (1300) - first start (1000) = 300ms
        assertEquals(300.0, agg.getActiveWindowMs(), 0.001);
    }

    @Test
    void testMinNeverLeaksSentinelWhenCountRacesAhead() throws Exception {
        SamplerAggregator agg = new SamplerAggregator("Racy");
        // Reproduce the torn state addSample can expose without synchronization:
        // sampleCount already incremented, minResponseTime not yet updated.
        java.lang.reflect.Field f = SamplerAggregator.class.getDeclaredField("sampleCount");
        f.setAccessible(true);
        ((java.util.concurrent.atomic.AtomicLong) f.get(agg)).set(1);

        Map<String, Object> map = agg.toMap();
        assertEquals(0L, map.get("minResponseTime"),
                "A racing reader must see 0, never the Long.MAX_VALUE sentinel");
    }

    @Test
    void testConcurrentRecording() throws InterruptedException {
        SamplerAggregator agg = new SamplerAggregator("ConcurrentSampler");

        int threadCount = 8;
        int samplesPerThread = 1000;
        int totalExpected = threadCount * samplesPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < samplesPerThread; i++) {
                        agg.addSample(100 + (i % 50), true, 512, 128, 5, 20, APDEX_THRESHOLD);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Threads should complete within timeout");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        assertEquals(totalExpected, agg.getSampleCount());
        assertEquals(0, agg.getErrorCount());
        assertTrue(agg.getAvgResponseTime() > 0, "Average should be positive");
        assertTrue(agg.getPercentile(50) > 0, "p50 should be positive after concurrent writes");
    }
    @Test
    void testPercentilesEqualJMeterAggregateReport() {
        // B10: ~500 samples per sampler with a long tail, where the T-Digest
        // showed p99 438 against the exact 393 (11.5% off). The Aggregate
        // Report is exact, and so must the table be: compared with JMeter's own
        // calculator, at the float-widened percents its columns use.
        java.util.Random rnd = new java.util.Random(393);
        for (int trial = 0; trial < 20; trial++) {
            SamplerAggregator agg = new SamplerAggregator("req");
            org.apache.jorphan.math.StatCalculatorLong jm = new org.apache.jorphan.math.StatCalculatorLong();
            int n = 400 + rnd.nextInt(200);
            for (int i = 0; i < n; i++) {
                long v = 50 + (long) (Math.exp(rnd.nextGaussian() * 0.6) * 120);
                agg.addSample(v, true, 100, 50, 5, 10, 0);
                jm.addValue(v);
            }
            Map<String, Object> map = agg.toMap();
            assertEquals(jm.getMedian().doubleValue(), (Double) map.get("p50"), 0.0, "p50, trial " + trial);
            assertEquals((double) jm.getPercentPoint((double) (90f / 100f)), (Double) map.get("p90"), 0.0, "p90, trial " + trial);
            assertEquals((double) jm.getPercentPoint((double) (95f / 100f)), (Double) map.get("p95"), 0.0, "p95, trial " + trial);
            assertEquals((double) jm.getPercentPoint((double) (99f / 100f)), (Double) map.get("p99"), 0.0, "p99, trial " + trial);
        }
    }
}
