package com.jmeterinsight.livedashboard.engine;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe per-sampler cumulative statistics aggregator. Percentiles are
 * exact, computed as JMeter's Aggregate Report does (see ExactHistogram).
 */
public class SamplerAggregator {

    private final String name;
    private final AtomicLong sampleCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong totalSentBytes = new AtomicLong(0);
    private final AtomicLong totalConnectTime = new AtomicLong(0);
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong(0);

    // First sample start / last sample end (start + elapsed) for this sampler,
    // so throughput divides by the sampler's own active window (a login that ran
    // only in the first minute of an hour-long test must not be divided by the
    // full hour). The caller passes the start time, not the raw timestamp.
    private final AtomicLong firstTimestamp = new AtomicLong(0);
    private final AtomicLong lastTimestamp = new AtomicLong(0);

    // Apdex counters
    private final AtomicLong apdexSatisfied = new AtomicLong(0);
    private final AtomicLong apdexTolerating = new AtomicLong(0);
    private final AtomicLong apdexFrustrated = new AtomicLong(0);

    private final ExactHistogram histogram = new ExactHistogram();

    public SamplerAggregator(String name) {
        this.name = name;
    }

    public void addSample(long elapsed, boolean success, long bytes, long sentBytes,
                          long connectTime, long latency, double apdexThreshold) {
        addSample(elapsed, success, bytes, sentBytes, connectTime, latency, apdexThreshold, 0);
    }

    /** With JMeter's default tolerated threshold (see {@link LiveAggregator#DEFAULT_APDEX_TOLERATED_MS}). */
    public void addSample(long elapsed, boolean success, long bytes, long sentBytes,
                          long connectTime, long latency, double apdexThreshold,
                          long timestamp) {
        addSample(elapsed, success, bytes, sentBytes, connectTime, latency, apdexThreshold,
                LiveAggregator.DEFAULT_APDEX_TOLERATED_MS, timestamp);
    }

    // No lock at all: counters, apdex, min/max and the histogram are atomic.
    public void addSample(long elapsed, boolean success, long bytes, long sentBytes,
                          long connectTime, long latency, double apdexThreshold,
                          double apdexToleratedThreshold, long timestamp) {
        if (timestamp > 0) {
            firstTimestamp.accumulateAndGet(timestamp, (cur, val) -> cur == 0 ? val : Math.min(cur, val));
            lastTimestamp.accumulateAndGet(timestamp + elapsed, Math::max);
        }
        sampleCount.incrementAndGet();
        if (!success) {
            errorCount.incrementAndGet();
            apdexFrustrated.incrementAndGet();
        } else if (apdexThreshold > 0) {
            if (elapsed <= apdexThreshold) apdexSatisfied.incrementAndGet();
            else if (elapsed <= apdexToleratedThreshold) apdexTolerating.incrementAndGet();
            else apdexFrustrated.incrementAndGet();
        }
        totalResponseTime.addAndGet(elapsed);
        totalBytes.addAndGet(bytes);
        totalSentBytes.addAndGet(sentBytes);
        totalConnectTime.addAndGet(connectTime);
        totalLatency.addAndGet(latency);

        minResponseTime.accumulateAndGet(elapsed, Math::min);
        maxResponseTime.accumulateAndGet(elapsed, Math::max);
        histogram.add(elapsed);
    }

    /** Percentile for a percent such as 95, as the Aggregate Report's column shows it. */
    public double getPercentile(double percent) {
        return histogram.percentile(ExactHistogram.jmeterPercent(percent), maxResponseTime.get());
    }

    public double getAvgResponseTime() {
        long count = sampleCount.get();
        return count > 0 ? (double) totalResponseTime.get() / count : 0;
    }

    public double getErrorRate() {
        long count = sampleCount.get();
        return count > 0 ? (errorCount.get() * 100.0 / count) : 0;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        long count = sampleCount.get();
        map.put("name", name);
        map.put("samples", count);
        map.put("errors", errorCount.get());
        map.put("errorRate", getErrorRate());
        map.put("avgResponseTime", getAvgResponseTime());
        // Guard on the sentinel, not the count: addSample increments the count
        // before it updates the min, so a concurrent read of a brand-new
        // sampler can otherwise observe count>0 with min still at MAX_VALUE.
        long minRt = minResponseTime.get();
        map.put("minResponseTime", minRt == Long.MAX_VALUE ? 0 : minRt);
        map.put("maxResponseTime", maxResponseTime.get());
        double[] p = histogram.percentiles(ExactHistogram.REPORTED, maxResponseTime.get());
        map.put("p50", p[0]);
        map.put("p90", p[1]);
        map.put("p95", p[2]);
        map.put("p99", p[3]);
        map.put("totalBytes", totalBytes.get());
        map.put("totalSentBytes", totalSentBytes.get());
        map.put("avgConnectTime", count > 0 ? (double) totalConnectTime.get() / count : 0);
        map.put("avgLatency", count > 0 ? (double) totalLatency.get() / count : 0);
        map.put("throughput", 0.0); // computed at higher level by the aggregator
        map.put("apdex", getApdex());
        return map;
    }

    public double getApdex() {
        long total = sampleCount.get();
        if (total == 0) return 1.0;
        return (apdexSatisfied.get() + apdexTolerating.get() / 2.0) / total;
    }

    public String getName() { return name; }
    public long getSampleCount() { return sampleCount.get(); }
    public long getErrorCount() { return errorCount.get(); }

    /**
     * Length of this sampler's own active window (first sample start to last
     * sample end), in milliseconds, or -1 if timestamps weren't tracked or
     * there is too little data. Callers fall back to the global test window.
     */
    public double getActiveWindowMs() {
        long first = firstTimestamp.get();
        long last = lastTimestamp.get();
        if (first <= 0 || last <= first) return -1;
        return last - first;
    }
}
