package com.jmeterinsight.livedashboard.engine;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A time bucket that accumulates sample statistics for a fixed time interval.
 * Contains overall aggregate and per-sampler breakdowns.
 * Thread-safe for concurrent sample ingestion.
 */
public class TimeBucket {

    private final long startTime;
    private volatile long endTime = 0;

    public TimeBucket() {
        this(System.currentTimeMillis());
    }

    public TimeBucket(long startTime) {
        this.startTime = startTime;
    }

    /** Marks the end of this bucket's collection window. */
    public void close(long endTime) {
        this.endTime = endTime;
    }

    public long getStartTime() { return startTime; }

    /** Actual length of the collection window, in milliseconds (min 1). */
    public long getDurationMs() {
        long end = endTime > 0 ? endTime : System.currentTimeMillis();
        return Math.max(1, end - startTime);
    }

    private final AtomicLong sampleCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong totalResponseTime = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong totalSentBytes = new AtomicLong(0);
    private final AtomicLong totalConnectTime = new AtomicLong(0);
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxResponseTime = new AtomicLong(0);
    // Mean concurrency over the slot's samples, as JMeter's HTML report and
    // the JMeter-Plugins graph compute active threads per interval (they
    // break it down per thread group; this is the total). It was the peak.
    private final AtomicLong threadSum = new AtomicLong(0);
    private final AtomicLong threadSamples = new AtomicLong(0);

    private final ExactHistogram histogram = new ExactHistogram();

    private final ConcurrentHashMap<String, SamplerBucket> samplerBuckets = new ConcurrentHashMap<>();

    // No lock at all: counters, min/max and the histogram are atomic, so
    // samplers never serialize on a monitor and perturb the measured latency.
    public void addSample(String label, long elapsed, boolean success,
                          long bytes, long sentBytes, int threads,
                          long connectTime, long latency) {
        sampleCount.incrementAndGet();
        if (!success) {
            errorCount.incrementAndGet();
        }
        totalResponseTime.addAndGet(elapsed);
        totalBytes.addAndGet(bytes);
        totalSentBytes.addAndGet(sentBytes);
        totalConnectTime.addAndGet(connectTime);
        totalLatency.addAndGet(latency);
        // A result that doesn't know its concurrency (0) says nothing about it
        if (threads > 0) {
            threadSum.addAndGet(threads);
            threadSamples.incrementAndGet();
        }

        minResponseTime.accumulateAndGet(elapsed, Math::min);
        maxResponseTime.accumulateAndGet(elapsed, Math::max);
        histogram.add(elapsed);

        addSamplerSample(label, elapsed, success, bytes, sentBytes, connectTime, latency);
    }

    /**
     * Record a sample against its sampler only, without touching the overall
     * bucket stats. Used for sub-results, whose parent already accounts for
     * them in the overall totals.
     */
    public void addSamplerSample(String label, long elapsed, boolean success,
                                  long bytes, long sentBytes,
                                  long connectTime, long latency) {
        samplerBuckets.computeIfAbsent(label, k -> new SamplerBucket())
                .addSample(elapsed, success, bytes, sentBytes, connectTime, latency);
    }

    public long getSampleCount() { return sampleCount.get(); }
    public long getErrorCount() { return errorCount.get(); }
    public long getTotalBytes() { return totalBytes.get(); }
    public long getTotalSentBytes() { return totalSentBytes.get(); }
    public double getActiveThreads() {
        long n = threadSamples.get();
        return n > 0 ? (double) threadSum.get() / n : 0;
    }
    public long getMinResponseTime() { long min = minResponseTime.get(); return min == Long.MAX_VALUE ? 0 : min; }
    public long getMaxResponseTime() { return maxResponseTime.get(); }

    public double getAvgResponseTime() {
        long count = sampleCount.get();
        return count > 0 ? (double) totalResponseTime.get() / count : 0;
    }

    public double getAvgConnectTime() {
        long count = sampleCount.get();
        return count > 0 ? (double) totalConnectTime.get() / count : 0;
    }

    public double getAvgLatency() {
        long count = sampleCount.get();
        return count > 0 ? (double) totalLatency.get() / count : 0;
    }

    /** Percentile for a percent such as 95, as the Aggregate Report computes it. */
    public double getPercentile(double percent) {
        return histogram.percentile(ExactHistogram.jmeterPercent(percent), maxResponseTime.get());
    }

    /** p50, p90, p95, p99 in one pass. */
    double[] getReportedPercentiles() {
        return histogram.percentiles(ExactHistogram.REPORTED, maxResponseTime.get());
    }

    public Map<String, SamplerBucket> getSamplerBuckets() { return samplerBuckets; }

    /**
     * Per-sampler data within a time bucket.
     */
    public static class SamplerBucket {
        private final AtomicLong sampleCount = new AtomicLong(0);
        private final AtomicLong errorCount = new AtomicLong(0);
        private final AtomicLong totalResponseTime = new AtomicLong(0);
        private final AtomicLong totalBytes = new AtomicLong(0);
        private final AtomicLong totalSentBytes = new AtomicLong(0);
        private final AtomicLong totalConnectTime = new AtomicLong(0);
        private final AtomicLong totalLatency = new AtomicLong(0);
        private final AtomicLong minResponseTime = new AtomicLong(Long.MAX_VALUE);
        private final AtomicLong maxResponseTime = new AtomicLong(0);
        private final ExactHistogram histogram = new ExactHistogram();

        public void addSample(long elapsed, boolean success, long bytes, long sentBytes,
                              long connectTime, long latency) {
            sampleCount.incrementAndGet();
            if (!success) {
                errorCount.incrementAndGet();
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

        public long getSampleCount() { return sampleCount.get(); }
        public long getErrorCount() { return errorCount.get(); }
        public long getTotalBytes() { return totalBytes.get(); }
        public long getTotalSentBytes() { return totalSentBytes.get(); }
        public long getMinResponseTime() { long min = minResponseTime.get(); return min == Long.MAX_VALUE ? 0 : min; }
        public long getMaxResponseTime() { return maxResponseTime.get(); }

        public double getAvgResponseTime() {
            long count = sampleCount.get();
            return count > 0 ? (double) totalResponseTime.get() / count : 0;
        }

        public double getPercentile(double percent) {
            return histogram.percentile(ExactHistogram.jmeterPercent(percent), maxResponseTime.get());
        }

        /** p50, p90, p95, p99 in one pass. */
        double[] getReportedPercentiles() {
            return histogram.percentiles(ExactHistogram.REPORTED, maxResponseTime.get());
        }

        public double getErrorRate() {
            long count = sampleCount.get();
            return count > 0 ? (errorCount.get() * 100.0 / count) : 0;
        }

        public double getAvgConnectTime() {
            long count = sampleCount.get();
            return count > 0 ? (double) totalConnectTime.get() / count : 0;
        }

        public double getAvgLatency() {
            long count = sampleCount.get();
            return count > 0 ? (double) totalLatency.get() / count : 0;
        }
    }
}
