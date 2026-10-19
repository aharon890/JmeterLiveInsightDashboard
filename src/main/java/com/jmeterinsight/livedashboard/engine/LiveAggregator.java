package com.jmeterinsight.livedashboard.engine;

import org.apache.jmeter.samplers.SampleResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Core statistics engine for live dashboard.
 * Receives SampleResult objects, aggregates into time buckets,
 * and provides data for streaming to browser clients.
 */
public class LiveAggregator {

    private static final Logger log = LoggerFactory.getLogger(LiveAggregator.class);

    private final int updateIntervalMs;
    // Slot arithmetic divides by this; a non-positive configured interval is
    // rejected by the server at start, not by a division here.
    private final long slotMs;
    private final LongSupplier clock;
    private final long maxRetentionMs;
    private final int maxErrorSamples;
    private final double slaP95Threshold;
    private final double slaErrorRateThreshold;
    private final double apdexThreshold;
    private final double apdexToleratedThreshold;
    private final List<String> hideGlobs;
    private final Pattern[] hidePatterns;

    // Cumulative stats (top-level samples only; sub-results are tracked per sampler)
    private final AtomicLong totalSamples = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong totalSentBytes = new AtomicLong(0);
    private final AtomicLong totalResponseTimeSum = new AtomicLong(0);
    private final AtomicLong overallMinResponseTime = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong overallMaxResponseTime = new AtomicLong(0);
    // Apdex counts every result, sub-results included (see addSample), so it has
    // its own total rather than totalSamples
    private final AtomicLong apdexTotal = new AtomicLong(0);
    private final AtomicLong apdexSatisfied = new AtomicLong(0);
    private final AtomicLong apdexTolerating = new AtomicLong(0);
    private volatile long testStartTime = 0;
    private volatile long testEndTime = 0;
    // First/last sample times, updated from all sampler threads. AtomicLong
    // (not a volatile check-then-act) so a stale write can't shrink the
    // measured test window and inflate every throughput/KB-per-s figure.
    private final AtomicLong firstSampleTime = new AtomicLong(0);
    private final AtomicLong lastSampleTime = new AtomicLong(0);
    private volatile boolean testRunning = true;
    private final AtomicInteger activeThreads = new AtomicInteger(0);
    private final AtomicInteger maxThreads = new AtomicInteger(0);

    // Latest thread count reported by each engine. A result's allThreads covers
    // only the engine that produced it, so a distributed run's concurrency is
    // the SUM over engines — taking any one result's value showed a single
    // engine's share (measured: 10 of 20 threads with two engines). A local
    // run has one entry and behaves exactly as before.
    private final ConcurrentHashMap<String, Integer> threadsByEngine = new ConcurrentHashMap<>();

    // Cumulative response times across all top-level samples (true overall
    // percentiles, the Aggregate Report's TOTAL row)
    private final ExactHistogram overallHistogram = new ExactHistogram();

    // Per-sampler cumulative stats
    private final ConcurrentHashMap<String, SamplerAggregator> samplerStats = new ConcurrentHashMap<>();

    // Name table for frozen slots: a slot stores sampler ids, not label
    // Strings. Appended only under the aggregator monitor (Tier.close); read
    // freely, since ids only ever grow.
    private final List<String> slotNames = new CopyOnWriteArrayList<>();
    private final Map<String, Integer> slotIds = new HashMap<>();

    // Slot timing follows JMeter's own model (TimeStampKeysSelector, the
    // JMeter-Plugins graphs): a sample belongs to the slot containing its END
    // time, slots are aligned to the clock (index = t / interval), and rates
    // divide by the nominal width. Slots used to be "whatever arrived between
    // two timer ticks", divided by the measured tick gap: when Docker's clock
    // stepped back 1.92 s a slot appeared to last 77 ms and showed 12,792
    // samples/s instead of ~500.
    //
    // Two resolutions, both fed as samples arrive: the live window at the
    // update interval, and the whole run at one minute (the HTML report's
    // default resolution). The minute tier cannot be derived from the live
    // one — closed slots are frozen to scalars and percentiles do not merge —
    // so it keeps its own open slots. The live tier is pruned to the
    // retention window, the minute tier never (~3 MB per hour at 1,000
    // samplers).
    static final long OVERVIEW_MS = 60_000;
    private final Tier live;
    private final Tier overview;

    // Samples whose live slot had already closed (added to the oldest open
    // slot instead). Should stay at zero in a normal local run.
    private final AtomicLong lateSamples = new AtomicLong(0);

    // Final-slot messages built by testEnded(), delivered once by the next
    // flush (the server's one-shot end-of-test broadcast). Guarded by the
    // aggregator monitor.
    private List<Map<String, Object>> pendingFinalMsgs;

    // Guards slot closing against concurrent sample adds: many sampler
    // threads add under the shared read lock, closing takes the exclusive
    // write lock. This prevents a sample from landing in a slot that has
    // already been closed and broadcast (a torn read that dropped the sample
    // from every payload).
    private final ReentrantReadWriteLock bucketLock = new ReentrantReadWriteLock();

    // Recent errors (newest first). A separate counter avoids the O(n)
    // ConcurrentLinkedDeque.size() scan on every error during an outage.
    private final ConcurrentLinkedDeque<Map<String, Object>> recentErrors = new ConcurrentLinkedDeque<>();
    private final AtomicInteger recentErrorsCount = new AtomicInteger(0);

    // Error grouping for breakdown charts
    private final ConcurrentHashMap<String, AtomicLong> errorsByCode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> errorsBySampler = new ConcurrentHashMap<>();

    // Known samplers (in discovery order)
    private final CopyOnWriteArrayList<String> knownSamplers = new CopyOnWriteArrayList<>();

    // knownSamplers' size when it was last broadcast (monitor-guarded)
    private int knownSamplersSent = -1;

    // Transaction hierarchy: child → parent
    private final ConcurrentHashMap<String, String> parentMap = new ConcurrentHashMap<>();

    // parentMap's size when it was last broadcast (monitor-guarded)
    private int parentMapSent = 0;

    /**
     * JMeter's default {@code jmeter.reportgenerator.apdex_tolerated_threshold}: where
     * its HTML report stops counting a successful sample as tolerating. Apdex follows
     * the report, not the textbook 4T, so the two read the same number.
     */
    public static final double DEFAULT_APDEX_TOLERATED_MS = 1500;

    public LiveAggregator(int updateIntervalMs, long maxRetentionMs, int maxErrorSamples,
                          double slaP95Threshold, double slaErrorRateThreshold,
                          double apdexThreshold, String[] hidePatternsStr) {
        this(updateIntervalMs, maxRetentionMs, maxErrorSamples, slaP95Threshold,
                slaErrorRateThreshold, apdexThreshold, hidePatternsStr, System::currentTimeMillis);
    }

    /** With an explicit wall clock, so tests can step time (and step it back). */
    public LiveAggregator(int updateIntervalMs, long maxRetentionMs, int maxErrorSamples,
                          double slaP95Threshold, double slaErrorRateThreshold,
                          double apdexThreshold, String[] hidePatternsStr, LongSupplier clock) {
        this(updateIntervalMs, maxRetentionMs, maxErrorSamples, slaP95Threshold, slaErrorRateThreshold,
                apdexThreshold, DEFAULT_APDEX_TOLERATED_MS, hidePatternsStr, clock);
    }

    /**
     * With both Apdex thresholds: satisfied up to {@code apdexThreshold} (T),
     * tolerating up to {@code apdexToleratedThreshold} (F), both inclusive, exactly
     * as JMeter's HTML report classifies them.
     */
    public LiveAggregator(int updateIntervalMs, long maxRetentionMs, int maxErrorSamples,
                          double slaP95Threshold, double slaErrorRateThreshold,
                          double apdexThreshold, double apdexToleratedThreshold,
                          String[] hidePatternsStr, LongSupplier clock) {
        this.clock = clock;
        this.updateIntervalMs = updateIntervalMs;
        this.slotMs = Math.max(1, updateIntervalMs);
        this.maxRetentionMs = maxRetentionMs;
        this.maxErrorSamples = maxErrorSamples;
        this.slaP95Threshold = slaP95Threshold;
        this.slaErrorRateThreshold = slaErrorRateThreshold;
        this.apdexThreshold = apdexThreshold;
        this.apdexToleratedThreshold = apdexToleratedThreshold;
        this.testStartTime = clock.getAsLong();
        this.live = new Tier(slotMs, maxRetentionMs, testStartTime);
        this.overview = new Tier(OVERVIEW_MS, Long.MAX_VALUE, testStartTime);

        this.hideGlobs = hidePatternsStr == null ? List.of() : Arrays.stream(hidePatternsStr)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
        this.hidePatterns = hideGlobs.stream().map(LiveAggregator::globToPattern).toArray(Pattern[]::new);
    }

    /**
     * A hide pattern is a glob — {@code *} any run of characters, {@code ?} exactly one —
     * and everything else is literal. Sampler names are full of regex metacharacters
     * ({@code [Setup] Login}, {@code GET /users/{id}}, {@code ${var}}), and a pattern
     * that reached Pattern.compile raw either threw, from this constructor and out of the
     * listener's testStarted, killing the run before a thread group started, or silently
     * hid the wrong samplers. Quoted, a pattern can never fail to compile.
     */
    private static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (char c : glob.toCharArray()) {
            if (c != '*' && c != '?') {
                literal.append(c);
                continue;
            }
            if (literal.length() > 0) {
                regex.append(Pattern.quote(literal.toString()));
                literal.setLength(0);
            }
            regex.append(c == '*' ? ".*" : ".");
        }
        if (literal.length() > 0) regex.append(Pattern.quote(literal.toString()));
        // DOTALL: "any characters" includes a line break inside a label.
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * Ingest a single SampleResult. Called from JMeter's sample listener thread.
     */
    public void addSample(SampleResult result) {
        addSample(result, "local");
    }

    /**
     * Ingest a result produced by the named engine ({@code "local"} for a
     * non-distributed run). The engine only matters for thread counts.
     */
    public void addSample(SampleResult result, String engine) {
        addSample(result, engine, true, 0);
    }

    /**
     * An engine of a distributed run finished while others still run: its
     * threads no longer count toward the live concurrency.
     */
    public void engineFinished(String engine) {
        threadsByEngine.remove(engine);
        activeThreads.set(sumThreadsByEngine());
    }

    private int sumThreadsByEngine() {
        int sum = 0;
        for (int t : threadsByEngine.values()) {
            sum += t;
        }
        return sum;
    }

    /**
     * Top-level results feed the overall totals (matching JMeter's own reports,
     * where a delivered result counts once). Sub-results (transaction children,
     * redirects, embedded resources) are recorded against their own sampler only —
     * the parent's duration and byte counts already include them. The overall
     * Apdex is the exception: it counts them too, as JMeter's HTML report does.
     */
    private void addSample(SampleResult result, String engine, boolean topLevel, long parentEnd) {
        long now = clock.getAsLong();
        String label = result.getSampleLabel();
        long elapsed = result.getTime();
        boolean success = result.isSuccessful();
        long bytes = result.getBytesAsLong();
        long sentBytes = result.getSentBytes();

        // Total concurrency across all thread groups; fall back to the
        // group count for results that don't carry the global number
        int allThreads = result.getAllThreads();
        int threads = allThreads > 0 ? allThreads : result.getGroupThreads();
        int concurrency = 0;
        // Sub-results go into their parent's slots, so a transaction and its
        // children are always charted in the same interval
        long end = topLevel ? endTimeOf(result, elapsed, now) : parentEnd;

        // The rate windows run from the first sample's start to the last one's start
        // + elapsed: its end as the JTL records it. That is the window of the HTML
        // report and of an Aggregate Report loaded from the JTL. A live Aggregate
        // Report ends at getEndTime(), which also holds a transaction's excluded
        // timer time; it can't be had from the JTL, so the post-run references
        // agree with this one, not with that.
        long sampleStart = startTimeOf(result);

        if (topLevel) {
            // A result that never started has no time to give the window: its 0
            // became the window's start, and the next sample re-seeded it.
            if (sampleStart > 0) {
                firstSampleTime.accumulateAndGet(sampleStart, (cur, val) -> cur == 0 ? val : Math.min(cur, val));
                lastSampleTime.accumulateAndGet(sampleStart + elapsed, Math::max);
            }

            // Update cumulative totals
            totalSamples.incrementAndGet();
            totalBytes.addAndGet(bytes);
            totalSentBytes.addAndGet(sentBytes);
            totalResponseTimeSum.addAndGet(elapsed);
            overallMinResponseTime.accumulateAndGet(elapsed, Math::min);
            overallMaxResponseTime.accumulateAndGet(elapsed, Math::max);
            if (!success) {
                totalErrors.incrementAndGet();
            }
            overallHistogram.add(elapsed);

            // Update thread counts (skip 0 so odd results can't blank the gauge)
            if (threads > 0) {
                threadsByEngine.put(engine, threads);
                concurrency = sumThreadsByEngine();
                activeThreads.set(concurrency);
                maxThreads.accumulateAndGet(concurrency, Math::max);
            }
        }

        // Overall Apdex over every result, sub-results included, as JMeter's HTML
        // report computes its Apdex Total: the JTL holds a transaction's steps and a
        // request's redirect hops as rows of their own, and the report counts every
        // row. So unlike the Σ row (the Aggregate Report's top-level count), a
        // generate-parent transaction scores its steps too. An empty transaction
        // (its body sampled nothing) is skipped, as the report skips it; counted, it
        // scored as satisfied. Classified as the report does: successes up to T
        // satisfied, up to F tolerating (both inclusive). F is JMeter's tolerated
        // threshold, not the textbook 4T: with 4T, samples of 1501-2000 ms scored
        // tolerating here and frustrated in the report. Frustrated is the remainder
        // (failures and > F): (s + t/2) / total.
        if (apdexThreshold > 0 && !isEmptyTransaction(result)) {
            apdexTotal.incrementAndGet();
            if (success) {
                if (elapsed <= apdexThreshold) apdexSatisfied.incrementAndGet();
                else if (elapsed <= apdexToleratedThreshold) apdexTolerating.incrementAndGet();
            }
        }

        // Discover new sampler
        if (!samplerStats.containsKey(label)) {
            samplerStats.putIfAbsent(label, new SamplerAggregator(label));
            if (!knownSamplers.contains(label)) {
                knownSamplers.addIfAbsent(label);
            }
        }

        // Update per-sampler cumulative (pass the sample's start so the
        // sampler can measure throughput over its own active window)
        SamplerAggregator sampler = samplerStats.get(label);
        sampler.addSample(elapsed, success, bytes, sentBytes,
                result.getConnectTime(), result.getLatency(), apdexThreshold,
                apdexToleratedThreshold, sampleStart);

        // Add to its slot under the shared read lock so the sample can't land
        // in a slot that is concurrently being closed (closing takes the
        // exclusive write lock).
        bucketLock.readLock().lock();
        try {
            TimeBucket slot = live.slotFor(end, topLevel);
            TimeBucket minute = overview.slotFor(end, false);
            if (topLevel) {
                slot.addSample(label, elapsed, success, bytes, sentBytes,
                        concurrency, result.getConnectTime(), result.getLatency());
                minute.addSample(label, elapsed, success, bytes, sentBytes,
                        concurrency, result.getConnectTime(), result.getLatency());
            } else {
                slot.addSamplerSample(label, elapsed, success, bytes, sentBytes,
                        result.getConnectTime(), result.getLatency());
                minute.addSamplerSample(label, elapsed, success, bytes, sentBytes,
                        result.getConnectTime(), result.getLatency());
            }
        } finally {
            bucketLock.readLock().unlock();
        }

        // Track error DETAILS for every failed result, including sub-results:
        // a transaction child's failure carries the real response code, URL and
        // sampler name, which the parent record lacks. Only totalErrors stays
        // top-level-only (above), so the headline count matches JMeter reports.
        if (!success) {
            Map<String, Object> errorRecord = new LinkedHashMap<>();
            errorRecord.put("timestamp", now);
            errorRecord.put("sampler", label);
            errorRecord.put("responseCode", result.getResponseCode());
            errorRecord.put("responseMessage", result.getResponseMessage());
            String url = result.getUrlAsString();
            errorRecord.put("requestUrl", url != null ? url : "");
            recentErrors.addFirst(errorRecord);
            recentErrorsCount.incrementAndGet();
            while (recentErrorsCount.get() > maxErrorSamples) {
                if (recentErrors.pollLast() != null) {
                    recentErrorsCount.decrementAndGet();
                } else {
                    break;
                }
            }
            // Error grouping
            String code = result.getResponseCode();
            if (code != null && !code.isEmpty()) {
                errorsByCode.computeIfAbsent(code, k -> new AtomicLong(0)).incrementAndGet();
            }
            errorsBySampler.computeIfAbsent(label, k -> new AtomicLong(0)).incrementAndGet();
        }

        // Process sub-results (transaction controllers, etc.)
        SampleResult[] subResults = result.getSubResults();
        if (subResults != null && subResults.length > 0) {
            for (SampleResult sub : subResults) {
                String childLabel = sub.getSampleLabel();
                if (childLabel != null && !childLabel.equals(label)) {
                    parentMap.putIfAbsent(childLabel, label);
                }
                addSample(sub, engine, false, end);
            }
        }
    }

    /**
     * A sample's end time, which decides its slots. Deliberately not clamped
     * to "now": when the wall clock steps back while sample times keep
     * advancing, clamping piled two slots' samples into one — the very spike
     * this timing model removes. A slot ahead of the clock just closes when
     * the clock gets there.
     */
    private static long endTimeOf(SampleResult result, long elapsed, long now) {
        long end = result.getEndTime();
        if (end <= 0) {
            long ts = result.getTimeStamp();
            end = ts > 0 ? ts + elapsed : now;
        }
        return end;
    }

    /**
     * A sample's start, as every JMeter output takes it (getStartTime). The
     * timestamp is the start only under sampleresult.timestamp.start=true, which
     * the shipped jmeter.properties sets but JMeter's code default does not: with
     * the property false or absent it is the END, and a window starting there ran
     * short at one end and long at the other. Falls back to the timestamp for a
     * result whose start was never set.
     */
    private static long startTimeOf(SampleResult result) {
        long start = result.getStartTime();
        return start > 0 ? start : result.getTimeStamp();
    }

    /**
     * JMeter's own test for a transaction whose body sampled nothing in this
     * iteration (report.core.Sample.isEmptyController): TransactionController's
     * message prefix with a count of 0.
     */
    private static boolean isEmptyTransaction(SampleResult result) {
        String msg = result.getResponseMessage();
        return msg != null && msg.startsWith(EMPTY_TRANSACTION_PREFIX);
    }

    private static final String EMPTY_TRANSACTION_PREFIX = "Number of samples in transaction : 0";

    /** Milliseconds until the clock enters the next slot: when the timer should next look. */
    public long millisUntilNextSlot() {
        long now = clock.getAsLong();
        return (Math.floorDiv(now, slotMs) + 1) * slotMs - now;
    }

    /**
     * Close every slot whose end is at least one slot in the past, store each
     * in history and build its message for SSE broadcast, oldest first. This
     * (with testEnded) is the only place slots close, so the slot a live
     * client sees is the same object a reloading client gets from history —
     * the numbers cannot disagree. A late timer simply finds more slots due;
     * each still has its full nominal width.
     */
    public synchronized List<Map<String, Object>> flushDueBuckets() {
        // The final messages built by testEnded() are delivered exactly once —
        // normally by the server's one-shot end-of-test broadcast.
        if (pendingFinalMsgs != null) {
            List<Map<String, Object>> msgs = pendingFinalMsgs;
            pendingFinalMsgs = null;
            return msgs;
        }
        long now = clock.getAsLong();
        return closeDue(Math.floorDiv(now, slotMs) - 2, now, false);
    }

    /**
     * Close every open slot up to and including the current one, without
     * waiting out the grace period, and return the newest slot's message (or
     * null if nothing closed). For tests, which step the clock themselves.
     */
    public synchronized Map<String, Object> flushCurrentBucket() {
        List<Map<String, Object>> msgs;
        if (pendingFinalMsgs != null) {
            msgs = pendingFinalMsgs;
            pendingFinalMsgs = null;
        } else {
            long now = clock.getAsLong();
            msgs = closeDue(Math.floorDiv(now, slotMs), now, false);
        }
        return msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);
    }

    /**
     * Close the live slots through {@code liveThrough} and every minute slot
     * now due, and build one message per closed live slot, oldest first.
     */
    private List<Map<String, Object>> closeDue(long liveThrough, long now, boolean testEnd) {
        List<FrozenBucket> slots = live.close(liveThrough, now, testEnd);
        // A minute closes one live slot of grace after it ends, like a slot
        long minuteThrough = testEnd
                ? Math.max(Math.floorDiv(now, OVERVIEW_MS), overview.lastOpenIndex())
                : Math.floorDiv(now - slotMs, OVERVIEW_MS) - 1;
        List<FrozenBucket> minutes = overview.close(minuteThrough, now, testEnd);

        List<Map<String, Object>> msgs = new ArrayList<>();
        for (FrozenBucket bucket : slots) {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "bucket");
            msg.put("timestamp", bucket.startTime);
            msg.put("interval", updateIntervalMs);
            msg.put("testRunning", testRunning);
            // Overall and per-sampler slot stats, from the same frozen object
            // history keeps
            msg.put("overall", bucket.overallMap());
            msg.put("samplers", bucket.samplersMap(slotNames));
            msgs.add(msg);
        }
        if (msgs.isEmpty()) {
            // A test whose last seconds had no samples ends with no slot left to
            // close (slots after the last sample are not part of the run), but
            // its last minute does close. It must still reach the open tabs:
            // their 1-minute chart points take percentiles from these minutes,
            // and without it the last one stayed an average of 2 s values until
            // a reload. Mid-run every slot closes, empty or not, so in practice
            // this is the end of a test: one message with no slot, carrying the
            // minute and the final summaries (the page adds no chart point).
            // At the end it is sent even with no minute left to close: an idle tail
            // that outlasted the last minute left nothing open, and the tab that
            // watched the run then never got 0 threads or the run's final duration.
            if (minutes.isEmpty() && !testEnd) return msgs;
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("type", "bucket");
            msg.put("interval", updateIntervalMs);
            msg.put("testRunning", testRunning);
            msgs.add(msg);
        }

        // The summaries describe "now", not a slot: only the newest message
        // carries them (the client keeps the last it saw), so several slots
        // closing at once don't each repeat the full sampler table. Minutes
        // that just closed ride along the same way.
        Map<String, Object> newest = msgs.get(msgs.size() - 1);
        if (!minutes.isEmpty()) {
            newest.put("overviewInterval", OVERVIEW_MS);
            newest.put("overview", CatchupSnapshot.historyOnly(minutes, slotNames).bucketList());
        }
        // Send all recent errors (client replaces its list)
        newest.put("recentErrors", new ArrayList<>(recentErrors));
        newest.put("errorSummary", buildErrorSummary());
        newest.put("slaStatus", buildSlaStatus());
        newest.put("cumulative", buildCumulativeStats(now));
        newest.put("samplerStats", buildSamplerCumulative(now));
        // Only when they changed: the catch-up always carries them in full, and
        // the client keeps what it has (index.html, handleBucket). The hidden list
        // and the hierarchy must ride along too, not live in the catch-up alone: the
        // tab the plugin opens connects before the first sample, so its catch-up has
        // neither, and for the whole run it charted Debug/JSR223 samplers and showed
        // transaction children as plain rows. Both maps only grow, so size is change.
        if (knownSamplers.size() != knownSamplersSent) {
            knownSamplersSent = knownSamplers.size();
            newest.put("knownSamplers", new ArrayList<>(knownSamplers));
            newest.put("hiddenSamplers", hiddenSamplers());
        }
        if (parentMap.size() != parentMapSent) {
            parentMapSent = parentMap.size();
            newest.put("hierarchy", new LinkedHashMap<>(parentMap));
        }
        return msgs;
    }

    private List<String> hiddenSamplers() {
        List<String> hidden = new ArrayList<>();
        for (String sampler : knownSamplers) {
            if (shouldHide(sampler)) hidden.add(sampler);
        }
        return hidden;
    }

    /**
     * One resolution of the timeline: open slots by index, and the closed
     * slots frozen to plain numbers (see FrozenBucket), keyed by start time.
     *
     * A slot is closed (frozen, stored, broadcast) once the clock is past its
     * end plus one live slot of grace, so samples still in flight — or batched
     * by a remote engine — land in their own slot. Keyed by index, a backward
     * clock step cannot overwrite a slot.
     */
    private final class Tier {
        final long widthMs;
        final long retentionMs;   // Long.MAX_VALUE: the whole run
        final ConcurrentSkipListMap<Long, TimeBucket> open = new ConcurrentSkipListMap<>();
        final ConcurrentNavigableMap<Long, FrozenBucket> closed = new ConcurrentSkipListMap<>();
        // Highest closed slot index. Only ever grows. A sample whose slot is
        // already closed goes into the oldest open slot instead.
        volatile long closedThrough;
        // False until the first data-bearing slot is stored: idle slots before
        // the first sample are not part of the run. Guarded by the monitor.
        boolean anyStored;

        Tier(long widthMs, long retentionMs, long now) {
            this.widthMs = widthMs;
            this.retentionMs = retentionMs;
            this.closedThrough = Math.floorDiv(now, widthMs) - 1;
        }

        /** The open slot for a sample ending at {@code end}. Call under the read lock. */
        TimeBucket slotFor(long end, boolean countLate) {
            long idx = Math.floorDiv(end, widthMs);
            if (idx <= closedThrough) {
                idx = closedThrough + 1;
                if (countLate) lateSamples.incrementAndGet();
            }
            return open.computeIfAbsent(idx, k -> new TimeBucket(k * widthMs));
        }

        long lastOpenIndex() {
            return open.isEmpty() ? Long.MIN_VALUE : open.lastKey();
        }

        /** Close and store every slot through {@code through}; the stored ones, oldest first. */
        List<FrozenBucket> close(long through, long now, boolean testEnd) {
            long from = closedThrough + 1;
            if (through < from) return Collections.emptyList();

            // Take the due slots out under the exclusive write lock: this waits
            // for every in-flight sample add to finish, so each closed slot is a
            // stable snapshot no add can still be mutating.
            NavigableMap<Long, TimeBucket> data = new TreeMap<>();
            bucketLock.writeLock().lock();
            try {
                NavigableMap<Long, TimeBucket> due = open.headMap(through, true);
                data.putAll(due);
                due.clear();
                closedThrough = through;
            } finally {
                bucketLock.writeLock().unlock();
            }

            // Pre-traffic idle: nothing to store or broadcast yet
            if (totalSamples.get() == 0) return Collections.emptyList();
            long first = from;
            if (!anyStored) {
                if (data.isEmpty()) return Collections.emptyList();
                first = data.firstKey();
            }
            long last = through;
            if (testEnd) {
                // Slots after the last sample are not part of the run. This
                // keeps the once-only final delivery: when they are all that is
                // left, no final message is built at all.
                if (data.isEmpty()) return Collections.emptyList();
                last = data.lastKey();
            }
            // After a forward clock jump, empty slots older than the retention
            // window would be pruned at once: don't build them
            first = Math.max(first, Math.min(last - retentionMs / widthMs, data.isEmpty() ? last : data.firstKey()));

            List<FrozenBucket> stored = new ArrayList<>();
            for (long k = first; k <= last; k++) {
                TimeBucket slot = data.get(k);
                if (slot == null) slot = new TimeBucket(k * widthMs);
                FrozenBucket bucket = new FrozenBucket(slot, widthMs, LiveAggregator.this::slotIdOf);
                // Store in history — including empty slots, so a reload shows
                // the same zero-traffic intervals the live stream showed
                closed.put(bucket.startTime, bucket);
                anyStored = true;
                stored.add(bucket);
            }
            if (retentionMs != Long.MAX_VALUE) closed.headMap(now - retentionMs).clear();
            return stored;
        }
    }

    /**
     * Capture the catch-up for a newly connected client. Cheap: the history is
     * held by reference (frozen slots never change) and written later, slot by
     * slot, by {@link CatchupSnapshot#writeJson}.
     */
    public CatchupSnapshot snapshotCatchup(String testName) {
        Map<String, Object> head = new LinkedHashMap<>();
        long now = clock.getAsLong();

        head.put("type", "catchup");
        head.put("testName", testName);
        head.put("testStartTime", testStartTime);
        head.put("testRunning", testRunning);
        head.put("testEndTime", testEndTime);
        // Let the client bound its in-memory series to the same retention the
        // server prunes to, instead of growing without limit on long soaks.
        head.put("interval", updateIntervalMs);
        head.put("maxRetentionMs", maxRetentionMs);
        head.put("overviewInterval", OVERVIEW_MS);
        head.put("cumulativeStats", buildCumulativeStats(now));

        // Per-sampler cumulative
        head.put("samplerStats", buildSamplerCumulative(now));

        // Known samplers and config (after the recent buckets)
        Map<String, Object> tail = new LinkedHashMap<>();
        tail.put("knownSamplers", new ArrayList<>(knownSamplers));
        tail.put("recentErrors", new ArrayList<>(recentErrors));
        tail.put("errorSummary", buildErrorSummary());
        tail.put("slaStatus", buildSlaStatus());
        tail.put("slaConfig", getSlaConfig());

        tail.put("hiddenSamplers", hiddenSamplers());

        // Transaction hierarchy
        if (!parentMap.isEmpty()) {
            tail.put("hierarchy", new LinkedHashMap<>(parentMap));
        }

        return new CatchupSnapshot(head, new ArrayList<>(live.closed.values()),
                new ArrayList<>(overview.closed.values()), slotNames, tail);
    }

    /** The catch-up as one map. Tests only: the server streams the snapshot. */
    public Map<String, Object> buildCatchupPayload(String testName) {
        return snapshotCatchup(testName).toMap();
    }

    /** The stored history, captured by reference for streaming. */
    public CatchupSnapshot snapshotHistory() {
        return CatchupSnapshot.historyOnly(new ArrayList<>(live.closed.values()), slotNames);
    }

    public List<Map<String, Object>> getRecentBuckets() {
        return snapshotHistory().bucketList();
    }

    public Map<String, Object> getStatus(String testName) {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("testName", testName);
        status.put("testRunning", testRunning);
        status.put("testStartTime", testStartTime);
        // Frozen at the end like the page's own timer: it kept counting the wall
        // clock, 3,076,510 ms for a 303 s run read 50 minutes later. testEndTime
        // is checked too, so a read racing testEnded() can't go negative.
        long end = testEndTime;
        status.put("elapsedTime", (!testRunning && end > 0 ? end : clock.getAsLong()) - testStartTime);
        status.put("totalSamples", totalSamples.get());
        status.put("totalErrors", totalErrors.get());
        status.put("activeThreads", activeThreads.get());
        status.put("maxThreads", maxThreads.get());
        status.put("knownSamplers", knownSamplers.size());
        return status;
    }

    public Map<String, Object> getConfig() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("updateIntervalMs", updateIntervalMs);
        config.put("maxRetentionMs", maxRetentionMs);
        config.put("sla", getSlaConfig());
        config.put("hidePatterns", hideGlobs);
        return config;
    }

    public synchronized void testEnded() {
        testRunning = false;
        long now = clock.getAsLong();
        testEndTime = now;
        // An ended test has no active threads. The gauge holds the latest
        // sample's allThreads, which is at least 1 (the thread that produced
        // it): after a graceful stop the header read "Threads: 3/10" while
        // JMeter's summariser printed "Active: 0". Cleared before the final
        // messages are built so the last update already carries 0; the peak,
        // maxThreads, is history and stays.
        threadsByEngine.clear();
        activeThreads.set(0);
        // Close and store every open slot immediately — no grace, every sample
        // is in — so history is complete even when no server ever started
        // (port-bind failure) or it was stopped early. The built messages are
        // cached for the server's one-shot broadcast to deliver, so the live
        // view still ends on the true final totals.
        long through = Math.max(Math.floorDiv(now, slotMs), live.lastOpenIndex());
        List<Map<String, Object>> msgs = closeDue(through, now, true);
        pendingFinalMsgs = msgs.isEmpty() ? null : msgs;
    }

    // --- Private helpers ---

    /** Id of a sampler in the frozen-slot name table; called under the monitor. */
    private int slotIdOf(String label) {
        Integer id = slotIds.get(label);
        if (id == null) {
            id = slotNames.size();
            slotNames.add(label);
            slotIds.put(label, id);
        }
        return id;
    }

    /**
     * Build the per-sampler cumulative table. Throughput and byte rates use
     * each sampler's OWN active window (first sample to last sample) so a
     * sampler that ran only briefly isn't divided by the whole test duration;
     * samplers without tracked timestamps fall back to the global window.
     */
    private Map<String, Object> buildSamplerCumulative(long now) {
        Map<String, Object> samplerCumulative = new LinkedHashMap<>();
        double globalSec = sampleWindowSec(now);
        for (Map.Entry<String, SamplerAggregator> entry : samplerStats.entrySet()) {
            SamplerAggregator agg = entry.getValue();
            Map<String, Object> sMap = agg.toMap();
            double windowMs = agg.getActiveWindowMs();
            double sSec = windowMs > 0 ? windowMs / 1000.0 : globalSec;
            long totalB = (Long) sMap.get("totalBytes");
            long totalSB = (Long) sMap.get("totalSentBytes");
            sMap.put("throughput", sSec > 0 ? agg.getSampleCount() / sSec : 0);
            sMap.put("receivedKBs", sSec > 0 ? totalB / 1024.0 / sSec : 0);
            sMap.put("sentKBs", sSec > 0 ? totalSB / 1024.0 / sSec : 0);
            // Sent every update for every sampler: fields the page never reads
            // are left out (the key is the name; byte totals became KB/s above)
            sMap.remove("name");
            sMap.remove("totalBytes");
            sMap.remove("totalSentBytes");
            sMap.remove("apdex");
            samplerCumulative.put(entry.getKey(), sMap);
        }
        return samplerCumulative;
    }

    private Map<String, Object> buildCumulativeStats(long now) {
        Map<String, Object> cumulative = new LinkedHashMap<>();
        cumulative.put("totalSamples", totalSamples.get());
        cumulative.put("totalErrors", totalErrors.get());
        cumulative.put("totalBytes", totalBytes.get());
        cumulative.put("totalSentBytes", totalSentBytes.get());
        // Elapsed time for display: runs on with the wall clock while the test
        // runs. It is deliberately NOT the rate denominator below.
        long effectiveEnd = testRunning ? now : (lastSampleTime.get() > 0 ? lastSampleTime.get() : now);
        long effectiveStart = firstSampleTime.get() > 0 ? firstSampleTime.get() : testStartTime;
        cumulative.put("elapsedTime", effectiveEnd - effectiveStart);
        cumulative.put("activeThreads", activeThreads.get());
        cumulative.put("maxThreads", maxThreads.get());
        cumulative.put("lateSamples", lateSamples.get());
        double elapsedSec = sampleWindowSec(now);
        cumulative.put("overallThroughput", elapsedSec > 0 ? totalSamples.get() / elapsedSec : 0);
        cumulative.put("overallErrorRate", totalSamples.get() > 0
                ? (totalErrors.get() * 100.0 / totalSamples.get()) : 0);
        cumulative.put("receivedKBs", elapsedSec > 0 ? totalBytes.get() / 1024.0 / elapsedSec : 0);
        cumulative.put("sentKBs", elapsedSec > 0 ? totalSentBytes.get() / 1024.0 / elapsedSec : 0);

        // Overall percentiles over all samples (a true percentile, not an
        // average of per-sampler percentiles)
        long totalCount = totalSamples.get();
        double[] p = overallHistogram.percentiles(ExactHistogram.REPORTED, overallMaxResponseTime.get());
        cumulative.put("p50", p[0]);
        cumulative.put("p90", p[1]);
        cumulative.put("p95", p[2]);
        cumulative.put("p99", p[3]);
        cumulative.put("avgResponseTime", totalCount > 0
                ? (double) totalResponseTimeSum.get() / totalCount : 0);
        long minRt = overallMinResponseTime.get();
        cumulative.put("minResponseTime", minRt == Long.MAX_VALUE ? 0 : minRt);
        cumulative.put("maxResponseTime", overallMaxResponseTime.get());

        // Overall Apdex, absent until a result is scored: JMeter's report has no
        // Apdex for an empty run, and the page shows the badge only once one
        // arrives (it read a green 1.00 before the first sample)
        long apdexCount = apdexTotal.get();
        if (apdexThreshold > 0 && apdexCount > 0) {
            cumulative.put("apdex", (apdexSatisfied.get() + apdexTolerating.get() / 2.0) / apdexCount);
            cumulative.put("apdexThreshold", apdexThreshold);
            cumulative.put("apdexToleratedThreshold", apdexToleratedThreshold);
        }

        return cumulative;
    }

    /**
     * Denominator of Total throughput and KB/s: first sample start to last
     * sample end, the Aggregate Report's definition — while running too.
     * Dividing by "now" while running made the value depend on when it was
     * read: a test ending in an idle phase kept its last live value (the empty
     * final slot is never broadcast), and a reload then showed a different,
     * finished one (measured -1.3%). With this window the last update sent
     * while running already holds the final value.
     */
    private double sampleWindowSec(long now) {
        long first = firstSampleTime.get();
        long last = lastSampleTime.get();
        if (first > 0 && last > first) return (last - first) / 1000.0;
        // No timestamped samples yet, or a single zero-length one
        long start = first > 0 ? first : testStartTime;
        return (now - start) / 1000.0;
    }

    private Map<String, Map<String, String>> buildSlaStatus() {
        Map<String, Map<String, String>> slaStatus = new LinkedHashMap<>();
        if (slaP95Threshold <= 0 && slaErrorRateThreshold <= 0) return slaStatus;

        for (Map.Entry<String, SamplerAggregator> entry : samplerStats.entrySet()) {
            SamplerAggregator agg = entry.getValue();
            Map<String, String> status = new LinkedHashMap<>();

            if (slaP95Threshold > 0) {
                double p95 = agg.getPercentile(95);
                if (p95 > slaP95Threshold) status.put("p95", "FAIL");
                else if (p95 > slaP95Threshold * 0.8) status.put("p95", "WARN");
                else status.put("p95", "PASS");
            }

            if (slaErrorRateThreshold > 0) {
                double errorRate = agg.getErrorRate();
                if (errorRate > slaErrorRateThreshold) status.put("errorRate", "FAIL");
                else if (errorRate > slaErrorRateThreshold * 0.8) status.put("errorRate", "WARN");
                else status.put("errorRate", "PASS");
            }

            if (!status.isEmpty()) {
                slaStatus.put(entry.getKey(), status);
            }
        }
        return slaStatus;
    }

    private Map<String, Object> getSlaConfig() {
        Map<String, Object> sla = new LinkedHashMap<>();
        if (slaP95Threshold > 0) sla.put("p95", slaP95Threshold);
        if (slaErrorRateThreshold > 0) sla.put("errorRate", slaErrorRateThreshold);
        return sla;
    }

    private Map<String, Object> buildErrorSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Long> byCode = new LinkedHashMap<>();
        errorsByCode.forEach((k, v) -> byCode.put(k, v.get()));
        summary.put("byCode", byCode);
        Map<String, Long> bySampler = new LinkedHashMap<>();
        errorsBySampler.forEach((k, v) -> bySampler.put(k, v.get()));
        summary.put("bySampler", bySampler);
        return summary;
    }

    public boolean shouldHide(String samplerName) {
        for (Pattern p : hidePatterns) {
            if (p.matcher(samplerName).matches()) return true;
        }
        return false;
    }

    public int getUpdateIntervalMs() { return updateIntervalMs; }
    public boolean isTestRunning() { return testRunning; }
}
