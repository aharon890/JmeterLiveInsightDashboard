package com.jmeterinsight.livedashboard.engine;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TimeBucketTest {

    private TimeBucket bucket;

    @BeforeEach
    void setUp() {
        bucket = new TimeBucket();
    }

    @Test
    void testNewBucketIsEmpty() {
        assertEquals(0, bucket.getSampleCount());
        assertEquals(0, bucket.getErrorCount());
        assertEquals(0, bucket.getTotalBytes());
        assertEquals(0, bucket.getTotalSentBytes());
        assertEquals(0.0, bucket.getActiveThreads());
        assertEquals(0, bucket.getMinResponseTime());
        assertEquals(0, bucket.getMaxResponseTime());
        assertEquals(0.0, bucket.getAvgResponseTime());
        assertTrue(bucket.getSamplerBuckets().isEmpty());
    }

    @Test
    void testRecordOverall() {
        bucket.addSample("HTTP Request", 250, true, 1024, 512, 5, 30, 50);

        assertEquals(1, bucket.getSampleCount());
        assertEquals(0, bucket.getErrorCount());
        assertEquals(250.0, bucket.getAvgResponseTime());
        assertEquals(1024, bucket.getTotalBytes());
        assertEquals(512, bucket.getTotalSentBytes());
    }

    @Test
    void testRecordPerSampler() {
        bucket.addSample("Login", 100, true, 500, 200, 3, 10, 20);
        bucket.addSample("Search", 200, false, 800, 300, 5, 15, 25);

        Map<String, TimeBucket.SamplerBucket> samplers = bucket.getSamplerBuckets();
        assertEquals(2, samplers.size());
        assertTrue(samplers.containsKey("Login"));
        assertTrue(samplers.containsKey("Search"));

        assertEquals(1, samplers.get("Login").getSampleCount());
        assertEquals(1, samplers.get("Search").getSampleCount());
    }

    @Test
    void testPercentiles() {
        // Add samples with known response times: 100, 200, 300, 400, 500
        for (int i = 1; i <= 5; i++) {
            bucket.addSample("Request", i * 100L, true, 100, 50, 1, 5, 10);
        }

        double p50 = bucket.getPercentile(50);
        double p90 = bucket.getPercentile(90);
        double p99 = bucket.getPercentile(99);

        // p50 should be near 300, p90 near 500, p99 near 500
        assertTrue(p50 >= 200 && p50 <= 400, "p50 should be around 300, was " + p50);
        assertTrue(p90 >= 400 && p90 <= 500, "p90 should be around 500, was " + p90);
        assertTrue(p99 >= 400 && p99 <= 500, "p99 should be around 500, was " + p99);

        // Percentile on empty bucket returns 0
        TimeBucket emptyBucket = new TimeBucket();
        assertEquals(0.0, emptyBucket.getPercentile(50));
    }

    @Test
    void testThroughputComputation() {
        bucket.addSample("A", 100, true, 100, 50, 1, 5, 10);
        bucket.addSample("A", 200, true, 100, 50, 2, 5, 10);
        bucket.addSample("B", 150, true, 100, 50, 3, 5, 10);

        assertEquals(3, bucket.getSampleCount());
        assertEquals(2, bucket.getSamplerBuckets().get("A").getSampleCount());
        assertEquals(1, bucket.getSamplerBuckets().get("B").getSampleCount());
    }

    @Test
    void testErrorRate() {
        bucket.addSample("Req", 100, true, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 200, false, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 150, false, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 120, true, 100, 50, 1, 5, 10);

        assertEquals(4, bucket.getSampleCount());
        assertEquals(2, bucket.getErrorCount());

        TimeBucket.SamplerBucket sb = bucket.getSamplerBuckets().get("Req");
        assertEquals(50.0, sb.getErrorRate(), 0.001);
    }

    @Test
    void testBytesAccumulation() {
        bucket.addSample("Req", 100, true, 1000, 200, 1, 5, 10);
        bucket.addSample("Req", 200, true, 2000, 300, 1, 5, 10);
        bucket.addSample("Req", 150, true, 3000, 500, 1, 5, 10);

        assertEquals(6000, bucket.getTotalBytes());
        assertEquals(1000, bucket.getTotalSentBytes());

        TimeBucket.SamplerBucket sb = bucket.getSamplerBuckets().get("Req");
        assertEquals(6000, sb.getTotalBytes());
        assertEquals(1000, sb.getTotalSentBytes());
    }

    @Test
    void testMultipleSamplers() {
        bucket.addSample("Login", 100, true, 500, 100, 2, 10, 20);
        bucket.addSample("Login", 200, false, 600, 120, 3, 12, 22);
        bucket.addSample("Search", 300, true, 1000, 200, 4, 15, 30);

        Map<String, TimeBucket.SamplerBucket> samplers = bucket.getSamplerBuckets();
        TimeBucket.SamplerBucket login = samplers.get("Login");
        TimeBucket.SamplerBucket search = samplers.get("Search");

        // Login: 2 samples, 1 error
        assertEquals(2, login.getSampleCount());
        assertEquals(1, login.getErrorCount());
        assertEquals(150.0, login.getAvgResponseTime(), 0.001);
        assertEquals(1100, login.getTotalBytes());

        // Search: 1 sample, 0 errors
        assertEquals(1, search.getSampleCount());
        assertEquals(0, search.getErrorCount());
        assertEquals(300.0, search.getAvgResponseTime(), 0.001);
        assertEquals(1000, search.getTotalBytes());

        // Overall totals
        assertEquals(3, bucket.getSampleCount());
        assertEquals(1, bucket.getErrorCount());
    }

    @Test
    void testConnectTimeLatency() {
        bucket.addSample("Req", 200, true, 100, 50, 1, 30, 60);
        bucket.addSample("Req", 300, true, 100, 50, 1, 50, 80);

        assertEquals(40.0, bucket.getAvgConnectTime(), 0.001);
        assertEquals(70.0, bucket.getAvgLatency(), 0.001);

        TimeBucket.SamplerBucket sb = bucket.getSamplerBuckets().get("Req");
        assertEquals(40.0, sb.getAvgConnectTime(), 0.001);
        assertEquals(70.0, sb.getAvgLatency(), 0.001);
    }

    @Test
    void testMinMaxResponseTime() {
        bucket.addSample("Req", 300, true, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 100, true, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 500, true, 100, 50, 1, 5, 10);
        bucket.addSample("Req", 200, true, 100, 50, 1, 5, 10);

        assertEquals(100, bucket.getMinResponseTime());
        assertEquals(500, bucket.getMaxResponseTime());

        TimeBucket.SamplerBucket sb = bucket.getSamplerBuckets().get("Req");
        assertEquals(100, sb.getMinResponseTime());
        assertEquals(500, sb.getMaxResponseTime());
    }

    @Test
    void testActiveThreadsIsTheMeanOverTheSlot() {
        // JMeter's HTML report and the JMeter-Plugins graph show the mean
        // active threads per interval, not the peak
        bucket.addSample("Req", 100, true, 100, 50, 3, 5, 10);
        assertEquals(3.0, bucket.getActiveThreads(), 1e-9);

        bucket.addSample("Req", 200, true, 100, 50, 7, 5, 10);
        bucket.addSample("Req", 150, true, 100, 50, 2, 5, 10);
        assertEquals(4.0, bucket.getActiveThreads(), 1e-9);

        // A result that doesn't know its concurrency doesn't drag the mean to 0
        bucket.addSample("Req", 150, true, 100, 50, 0, 5, 10);
        assertEquals(4.0, bucket.getActiveThreads(), 1e-9);
    }

    @Test
    void testSamplerBucketErrorRate() {
        TimeBucket.SamplerBucket sb = new TimeBucket.SamplerBucket();

        // Empty bucket: 0% error rate
        assertEquals(0.0, sb.getErrorRate(), 0.001);

        // All successes: 0%
        sb.addSample(100, true, 500, 100, 10, 20);
        sb.addSample(200, true, 500, 100, 10, 20);
        assertEquals(0.0, sb.getErrorRate(), 0.001);

        // 1 error out of 3: 33.33%
        sb.addSample(150, false, 500, 100, 10, 20);
        assertEquals(100.0 / 3.0, sb.getErrorRate(), 0.001);

        // 2 errors out of 4: 50%
        sb.addSample(180, false, 500, 100, 10, 20);
        assertEquals(50.0, sb.getErrorRate(), 0.001);
    }
}
