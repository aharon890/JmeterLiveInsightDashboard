package com.jmeterinsight.livedashboard.engine;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A closed time slot, frozen to plain numbers.
 *
 * A closed slot used to stay a live {@link TimeBucket} for the whole retention
 * window, holding a T-Digest per sampler (~17 KB each) that nothing would ever
 * add to again. Measured with the perf harness: ~12 MB/min at 30 samplers, and
 * OutOfMemoryError after ~5 min at 500 samplers, independent of the sample rate.
 * So a slot's numbers are computed once when it closes and its working data dropped.
 *
 * Per-sampler numbers are columns of primitives (~56 B per sampler per slot),
 * not maps of boxed values. Sampler names are ids into a table the aggregator
 * owns: every label String a slot kept would otherwise be its own copy.
 * Percentiles and averages are floats: percentiles are whole milliseconds
 * (exact to 16,777,216 ms in a float) and 7 significant digits is plenty for
 * an average, at half the footprint.
 *
 * The live broadcast and the history are both built from this one object, so a
 * reload still shows exactly the numbers the live stream showed.
 */
final class FrozenBucket {

    final long startTime;
    final long durationMs;

    // Overall (top-level samples): one set per slot, so plain doubles
    private final long samples;
    private final long errors;
    private final double activeThreads;
    private final long minRt;
    private final long maxRt;
    private final double avgRt, p50, p90, p95, p99, avgConnect, avgLatency;
    private final long bytes;
    private final long sentBytes;

    // Per sampler: entry i describes sampler ids[i]. Only samplers that had a
    // sample in this slot appear, in the slot's own iteration order.
    private final int[] ids;
    private final int[] count, errorCount, min, max;
    private final float[] mean, q50, q90, q95, q99, recvBps, sentBps, connect, latency;

    /** Resolves a label to its id in the aggregator's name table. */
    interface SamplerIds {
        int idOf(String label);
    }

    /**
     * @param widthMs the slot's NOMINAL width: rates divide by it, as JMeter's
     *                TimeRateAggregator does, so a short or delayed flush cannot
     *                produce a spike and a partial last slot reads low exactly
     *                as JMeter's does
     */
    FrozenBucket(TimeBucket b, long widthMs, SamplerIds names) {
        startTime = b.getStartTime();
        durationMs = widthMs;
        double sec = durationMs / 1000.0;

        samples = b.getSampleCount();
        errors = b.getErrorCount();
        activeThreads = b.getActiveThreads();
        minRt = b.getMinResponseTime();
        maxRt = b.getMaxResponseTime();
        avgRt = b.getAvgResponseTime();
        double[] p = b.getReportedPercentiles();
        p50 = p[0];
        p90 = p[1];
        p95 = p[2];
        p99 = p[3];
        avgConnect = b.getAvgConnectTime();
        avgLatency = b.getAvgLatency();
        bytes = b.getTotalBytes();
        sentBytes = b.getTotalSentBytes();

        Map<String, TimeBucket.SamplerBucket> sbs = b.getSamplerBuckets();
        int n = sbs.size();
        ids = new int[n];
        count = new int[n];
        errorCount = new int[n];
        min = new int[n];
        max = new int[n];
        mean = new float[n];
        q50 = new float[n];
        q90 = new float[n];
        q95 = new float[n];
        q99 = new float[n];
        recvBps = new float[n];
        sentBps = new float[n];
        connect = new float[n];
        latency = new float[n];
        int i = 0;
        for (Map.Entry<String, TimeBucket.SamplerBucket> e : sbs.entrySet()) {
            // The slot is closed and rotated out under the write lock, so its
            // map can no longer grow; the guard is belt and braces.
            if (i == n) break;
            TimeBucket.SamplerBucket sb = e.getValue();
            ids[i] = names.idOf(e.getKey());
            count[i] = (int) sb.getSampleCount();
            errorCount[i] = (int) sb.getErrorCount();
            min[i] = (int) Math.min(Integer.MAX_VALUE, sb.getMinResponseTime());
            max[i] = (int) Math.min(Integer.MAX_VALUE, sb.getMaxResponseTime());
            mean[i] = (float) sb.getAvgResponseTime();
            double[] sp = sb.getReportedPercentiles();
            q50[i] = (float) sp[0];
            q90[i] = (float) sp[1];
            q95[i] = (float) sp[2];
            q99[i] = (float) sp[3];
            recvBps[i] = (float) (sb.getTotalBytes() / sec);
            sentBps[i] = (float) (sb.getTotalSentBytes() / sec);
            connect[i] = (float) sb.getAvgConnectTime();
            latency[i] = (float) sb.getAvgLatency();
            i++;
        }
    }

    long getSampleCount() { return samples; }

    Map<String, Object> overallMap() {
        Map<String, Object> stats = new LinkedHashMap<>();
        boolean has = samples > 0;
        stats.put("samples", samples);
        stats.put("errors", errors);
        // Value-metrics of an empty interval are UNKNOWN, not zero: JSON null
        // makes the client chart a gap instead of a fabricated 0ms dip. Rates
        // (throughput, bytes/s, error rate) genuinely are zero and stay real.
        stats.put("avgResponseTime", has ? avgRt : null);
        stats.put("minResponseTime", has ? minRt : null);
        stats.put("maxResponseTime", has ? maxRt : null);
        stats.put("p50", has ? p50 : null);
        stats.put("p90", has ? p90 : null);
        stats.put("p95", has ? p95 : null);
        stats.put("p99", has ? p99 : null);
        double sec = durationMs / 1000.0;
        stats.put("throughput", samples / sec);
        stats.put("receivedBytesPerSec", bytes / sec);
        stats.put("sentBytesPerSec", sentBytes / sec);
        stats.put("activeThreads", has ? activeThreads : null);
        stats.put("errorRate", has ? (errors * 100.0 / samples) : 0);
        stats.put("avgConnectTime", has ? avgConnect : null);
        stats.put("avgLatency", has ? avgLatency : null);
        return stats;
    }

    /** Per-sampler data keyed by name; {@code names} is the aggregator's id table. */
    Map<String, Object> samplersMap(List<String> names) {
        Map<String, Object> samplers = new LinkedHashMap<>();
        double sec = durationMs / 1000.0;
        for (int i = 0; i < ids.length; i++) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("samples", (long) count[i]);
            d.put("errors", (long) errorCount[i]);
            d.put("avgResponseTime", mean[i]);
            d.put("minResponseTime", (long) min[i]);
            d.put("maxResponseTime", (long) max[i]);
            d.put("p50", q50[i]);
            d.put("p90", q90[i]);
            d.put("p95", q95[i]);
            d.put("p99", q99[i]);
            d.put("throughput", count[i] / sec);
            d.put("receivedBytesPerSec", recvBps[i]);
            d.put("sentBytesPerSec", sentBps[i]);
            d.put("errorRate", count[i] > 0 ? errorCount[i] * 100.0 / count[i] : 0.0);
            d.put("avgConnectTime", connect[i]);
            d.put("avgLatency", latency[i]);
            samplers.put(names.get(ids[i]), d);
        }
        return samplers;
    }

    static final String[] SAMPLER_COLS = {"samples", "errors", "avgResponseTime", "minResponseTime",
            "maxResponseTime", "p50", "p90", "p95", "p99", "throughput", "receivedBytesPerSec",
            "sentBytesPerSec", "errorRate", "avgConnectTime", "avgLatency"};

    /**
     * Streams {@code Columnar.encode(samplersMap(names))} without building
     * either: the columns once, then a row of values per sampler.
     */
    void writeSamplersColumnar(JsonGenerator gen, List<String> names) throws IOException {
        double sec = durationMs / 1000.0;
        gen.writeStartObject();
        gen.writeArrayFieldStart("cols");
        for (String c : SAMPLER_COLS) gen.writeString(c);
        gen.writeEndArray();
        gen.writeObjectFieldStart("rows");
        for (int i = 0; i < ids.length; i++) {
            gen.writeArrayFieldStart(names.get(ids[i]));
            gen.writeNumber((long) count[i]);
            gen.writeNumber((long) errorCount[i]);
            gen.writeNumber(mean[i]);
            gen.writeNumber((long) min[i]);
            gen.writeNumber((long) max[i]);
            gen.writeNumber(q50[i]);
            gen.writeNumber(q90[i]);
            gen.writeNumber(q95[i]);
            gen.writeNumber(q99[i]);
            gen.writeNumber(count[i] / sec);
            gen.writeNumber(recvBps[i]);
            gen.writeNumber(sentBps[i]);
            gen.writeNumber(count[i] > 0 ? errorCount[i] * 100.0 / count[i] : 0.0);
            gen.writeNumber(connect[i]);
            gen.writeNumber(latency[i]);
            gen.writeEndArray();
        }
        gen.writeEndObject();
        gen.writeEndObject();
    }

    /**
     * Streams the same object {@link #samplersMap} builds, field for field,
     * without allocating it: a catch-up at 1,000 samplers x 1,800 slots would
     * otherwise build 1.8 million maps before writing a byte.
     */
    void writeSamplers(JsonGenerator gen, List<String> names) throws IOException {
        double sec = durationMs / 1000.0;
        gen.writeStartObject();
        for (int i = 0; i < ids.length; i++) {
            gen.writeObjectFieldStart(names.get(ids[i]));
            gen.writeNumberField("samples", (long) count[i]);
            gen.writeNumberField("errors", (long) errorCount[i]);
            gen.writeNumberField("avgResponseTime", mean[i]);
            gen.writeNumberField("minResponseTime", (long) min[i]);
            gen.writeNumberField("maxResponseTime", (long) max[i]);
            gen.writeNumberField("p50", q50[i]);
            gen.writeNumberField("p90", q90[i]);
            gen.writeNumberField("p95", q95[i]);
            gen.writeNumberField("p99", q99[i]);
            gen.writeNumberField("throughput", count[i] / sec);
            gen.writeNumberField("receivedBytesPerSec", recvBps[i]);
            gen.writeNumberField("sentBytesPerSec", sentBps[i]);
            gen.writeNumberField("errorRate", count[i] > 0 ? errorCount[i] * 100.0 / count[i] : 0.0);
            gen.writeNumberField("avgConnectTime", connect[i]);
            gen.writeNumberField("avgLatency", latency[i]);
            gen.writeEndObject();
        }
        gen.writeEndObject();
    }
}
