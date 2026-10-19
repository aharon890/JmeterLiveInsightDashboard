package com.jmeterinsight.livedashboard.engine;

import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LiveAggregatorTest {

    private static final String[] NO_HIDE = new String[0];

    // Slots are aligned to the clock, so a flush only closes a new slot once
    // time has moved on: tests step this clock instead of sleeping.
    private final java.util.concurrent.atomic.AtomicLong clockMs =
            new java.util.concurrent.atomic.AtomicLong(1_700_000_000_000L);

    private SampleResult mockSample(String label, long elapsed, boolean success,
                                     long bytes, long sentBytes, int threads,
                                     long connectTime, long latency, String responseCode) {
        SampleResult result = mock(SampleResult.class);
        when(result.getSampleLabel()).thenReturn(label);
        when(result.getTime()).thenReturn(elapsed);
        when(result.isSuccessful()).thenReturn(success);
        when(result.getBytesAsLong()).thenReturn(bytes);
        when(result.getSentBytes()).thenReturn(sentBytes);
        when(result.getGroupThreads()).thenReturn(threads);
        when(result.getConnectTime()).thenReturn(connectTime);
        when(result.getLatency()).thenReturn(latency);
        when(result.getResponseCode()).thenReturn(responseCode);
        when(result.getResponseMessage()).thenReturn(success ? "OK" : "Error");
        when(result.getUrlAsString()).thenReturn("http://example.com/" + label);
        when(result.getSubResults()).thenReturn(null);
        return result;
    }

    private SampleResult mockSuccessSample(String label, long elapsed) {
        return mockSample(label, elapsed, true, 1024L, 256L, 5, 10L, 50L, "200");
    }

    private SampleResult mockErrorSample(String label, long elapsed, String responseCode) {
        return mockSample(label, elapsed, false, 512L, 128L, 5, 10L, 50L, responseCode);
    }

    @Test
    void testAddSampleUpdatesCumulativeCounters() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/test", 200));
        agg.addSample(mockSuccessSample("GET /api/test", 300));
        agg.addSample(mockSuccessSample("POST /api/data", 150));

        Map<String, Object> status = agg.getStatus("Test");
        assertEquals(3L, status.get("totalSamples"));
    }

    @Test
    void testAddSampleUpdatesPerSamplerStats() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/users", 100));
        agg.addSample(mockSuccessSample("GET /api/users", 200));
        agg.addSample(mockSuccessSample("POST /api/users", 300));

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        Map<String, Object> samplerStats = (Map<String, Object>) payload.get("samplerStats");
        @SuppressWarnings("unchecked")
        Map<String, Object> usersSampler = (Map<String, Object>) samplerStats.get("GET /api/users");

        assertEquals(2L, usersSampler.get("samples"));
    }

    @Test
    void testErrorRecording() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockErrorSample("GET /api/fail", 500, "500"));

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("recentErrors");

        assertEquals(1, errors.size());
        assertEquals("GET /api/fail", errors.get(0).get("sampler"));
        assertEquals("500", errors.get(0).get("responseCode"));
    }

    @Test
    void testErrorBufferEviction() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 5, -1, -1, 500, NO_HIDE, clockMs::get);

        for (int i = 0; i < 8; i++) {
            agg.addSample(mockErrorSample("GET /api/err" + i, 100, "500"));
        }

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> errors = (List<Map<String, Object>>) payload.get("recentErrors");

        assertEquals(5, errors.size());
    }

    @Test
    void testSamplerDiscovery() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/alpha", 100));
        agg.addSample(mockSuccessSample("GET /api/beta", 200));
        agg.addSample(mockSuccessSample("GET /api/alpha", 150));

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        List<String> known = (List<String>) payload.get("knownSamplers");

        assertEquals(2, known.size());
        assertTrue(known.contains("GET /api/alpha"));
        assertTrue(known.contains("GET /api/beta"));
    }

    @Test
    void testHidePatterns() {
        String[] hidePatterns = new String[]{"JSR223*"};
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, hidePatterns, clockMs::get);

        assertTrue(agg.shouldHide("JSR223 Sampler"));
        assertTrue(agg.shouldHide("JSR223"));
        assertFalse(agg.shouldHide("GET /api/test"));
        assertFalse(agg.shouldHide("My JSR223 Script"));
    }

    /**
     * Hide patterns are globs, as the GUI help and the docs say, and sampler names are full
     * of regex metacharacters. They used to go to Pattern.compile with only '.' and '*'
     * translated: '{', or an unbalanced '(' or '[', threw PatternSyntaxException from this
     * constructor, which the listener called outside its try — the exception escaped
     * testStarted and JMeter never started a single thread group. The balanced ones
     * compiled and silently hid the wrong samplers ('[Setup]*' hid every name made of
     * S/e/t/u/p, never "[Setup] Login").
     */
    @Test
    void hidePatternsMatchRegexMetacharactersLiterally() {
        String[] patterns = {"GET /x[", "${user}*", "Login {step}*", "*(", "[Setup]*",
                "C++*", "GET /api/$x", "Login|Logout", "^start*", "C:\\*"};
        LiveAggregator agg = assertDoesNotThrow(
                () -> new LiveAggregator(2000, 60000, 100, -1, -1, 500, patterns, clockMs::get),
                "no hide pattern may stop the aggregator from being built");

        assertTrue(agg.shouldHide("GET /x["));
        assertTrue(agg.shouldHide("${user} login"));
        assertTrue(agg.shouldHide("Login {step} 1"));
        assertTrue(agg.shouldHide("Open dialog ("));
        assertTrue(agg.shouldHide("[Setup] Login"));
        assertFalse(agg.shouldHide("Setup"), "brackets are literal, not a character class");
        assertTrue(agg.shouldHide("C++ build"));
        assertFalse(agg.shouldHide("C"), "'+' is literal, not a quantifier");
        assertTrue(agg.shouldHide("GET /api/$x"));
        assertTrue(agg.shouldHide("Login|Logout"));
        assertFalse(agg.shouldHide("Login"), "'|' is literal, not alternation");
        assertTrue(agg.shouldHide("^start page"));
        assertTrue(agg.shouldHide("C:\\temp"));
    }

    /** The GUI help promises "? (one char)"; '?' used to be a regex quantifier instead. */
    @Test
    void questionMarkMatchesExactlyOneCharacter() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500,
                new String[]{"Debug?", "TC??_*", "?Init"}, clockMs::get);

        assertTrue(agg.shouldHide("Debug1"));
        assertTrue(agg.shouldHide("Debugs"));
        assertFalse(agg.shouldHide("Debug"));
        assertFalse(agg.shouldHide("Debu"));
        assertFalse(agg.shouldHide("Debug12"));
        assertTrue(agg.shouldHide("TC01_Login"));
        assertFalse(agg.shouldHide("TC_Login"));
        assertTrue(agg.shouldHide("xInit"));
        assertFalse(agg.shouldHide("Init"));
    }

    /** '*' is "any characters", and a label can carry a line break. */
    @Test
    void starMatchesAcrossLineBreaks() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500,
                new String[]{"Setup*"}, clockMs::get);

        assertTrue(agg.shouldHide("Setup\nstep 2"));
    }

    @Test
    void testSlaEvaluationPass() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, 1000.0, 5.0, 500, NO_HIDE, clockMs::get);

        for (int i = 0; i < 100; i++) {
            agg.addSample(mockSuccessSample("GET /api/fast", 100));
        }

        Map<String, Object> msg = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> slaStatus =
                (Map<String, Map<String, String>>) msg.get("slaStatus");

        assertEquals("PASS", slaStatus.get("GET /api/fast").get("p95"));
        assertEquals("PASS", slaStatus.get("GET /api/fast").get("errorRate"));
    }

    @Test
    void testSlaEvaluationFail() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, 200.0, 5.0, 500, NO_HIDE, clockMs::get);

        for (int i = 0; i < 100; i++) {
            agg.addSample(mockSuccessSample("GET /api/slow", 500));
        }

        Map<String, Object> msg = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> slaStatus =
                (Map<String, Map<String, String>>) msg.get("slaStatus");

        assertEquals("FAIL", slaStatus.get("GET /api/slow").get("p95"));
    }

    @Test
    void testSlaDisabled() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/test", 5000));

        Map<String, Object> msg = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> slaStatus =
                (Map<String, Map<String, String>>) msg.get("slaStatus");

        assertTrue(slaStatus.isEmpty());
    }

    @Test
    void testBuildCatchupPayload() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, 1000.0, 5.0, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/test", 200));

        Map<String, Object> payload = agg.buildCatchupPayload("My Test");

        assertEquals("catchup", payload.get("type"));
        assertEquals("My Test", payload.get("testName"));
        assertTrue((Boolean) payload.get("testRunning"));
        assertNotNull(payload.get("testStartTime"));
        assertNotNull(payload.get("cumulativeStats"));
        assertNotNull(payload.get("samplerStats"));
        assertNotNull(payload.get("recentBuckets"));
        assertNotNull(payload.get("knownSamplers"));
        assertNotNull(payload.get("recentErrors"));
        assertNotNull(payload.get("errorSummary"));
        assertNotNull(payload.get("slaStatus"));
        assertNotNull(payload.get("slaConfig"));
        assertNotNull(payload.get("hiddenSamplers"));
    }

    @Test
    void testFlushCurrentBucket() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/test", 200));

        Map<String, Object> msg = agg.flushCurrentBucket();

        assertNotNull(msg);
        assertEquals("bucket", msg.get("type"));
        assertNotNull(msg.get("timestamp"));
        assertEquals(2000, msg.get("interval"));

        // One interval later, a flush with no new samples produces a bucket with zero count
        clockMs.addAndGet(2000);
        Map<String, Object> msg2 = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Object> overall2 = (Map<String, Object>) msg2.get("overall");
        assertEquals(0L, overall2.get("samples"));
    }

    @Test
    void testGetStatus() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockSuccessSample("GET /api/test", 200));

        Map<String, Object> status = agg.getStatus("Load Test");

        assertEquals("Load Test", status.get("testName"));
        assertTrue((Boolean) status.get("testRunning"));
        assertNotNull(status.get("testStartTime"));
        assertNotNull(status.get("elapsedTime"));
        assertEquals(1L, status.get("totalSamples"));
        assertEquals(0L, status.get("totalErrors"));
        assertEquals(5, status.get("activeThreads"));
        assertEquals(1, status.get("knownSamplers"));
    }

    @Test
    void testGetConfig() {
        String[] hidePatterns = new String[]{"JSR223*", "Debug*"};
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, 1000.0, 5.0, 500, hidePatterns, clockMs::get);

        Map<String, Object> config = agg.getConfig();

        assertEquals(2000, config.get("updateIntervalMs"));
        assertEquals(60000L, config.get("maxRetentionMs"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sla = (Map<String, Object>) config.get("sla");
        assertEquals(1000.0, sla.get("p95"));
        assertEquals(5.0, sla.get("errorRate"));

        @SuppressWarnings("unchecked")
        List<String> patterns = (List<String>) config.get("hidePatterns");
        assertEquals(List.of("JSR223*", "Debug*"), patterns,
                "/api/config echoes the patterns as typed, not the regex they compile to");
    }

    @Test
    void testTestEnded() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        assertTrue(agg.isTestRunning());

        agg.testEnded();

        assertFalse(agg.isTestRunning());
    }

    @Test
    void testApdexInCumulative() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        // All samples satisfied (below 500ms threshold)
        for (int i = 0; i < 10; i++) {
            agg.addSample(mockSuccessSample("GET /api/test", 100));
        }

        Map<String, Object> msg = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Object> cumulative = (Map<String, Object>) msg.get("cumulative");

        assertTrue(cumulative.containsKey("apdex"));
        double apdex = (Double) cumulative.get("apdex");
        assertEquals(1.0, apdex, 0.001);
        assertEquals(500.0, cumulative.get("apdexThreshold"));
    }

    /**
     * Apdex must read what JMeter's HTML report reads: tolerating ends at JMeter's
     * apdex_tolerated_threshold (1500 ms by default), not the textbook 4T. With 4T,
     * 5 x 300 ms + 5 x 1800 ms scored 0.75 here and 0.50 in JMeter's report for the
     * same JTL. The boundaries are JMeter's, measured with its report generator:
     * 1500 ms is tolerated, 1501 ms is frustrated.
     */
    @Test
    void testApdexUsesJMetersToleratedThresholdNotFourT() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        for (int i = 0; i < 5; i++) {
            agg.addSample(mockSuccessSample("GET /x", 300));
            agg.addSample(mockSuccessSample("GET /x", 1800));
        }
        assertEquals(0.5, overallApdex(agg), 1e-9, "JMeter's report gives 0.50 for these samples");

        LiveAggregator atF = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        atF.addSample(mockSuccessSample("GET /x", 100));
        atF.addSample(mockSuccessSample("GET /x", 1500));
        assertEquals(0.75, overallApdex(atF), 1e-9, "1500 ms is tolerated");

        LiveAggregator pastF = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        pastF.addSample(mockSuccessSample("GET /x", 100));
        pastF.addSample(mockSuccessSample("GET /x", 1501));
        assertEquals(0.5, overallApdex(pastF), 1e-9, "1501 ms is frustrated");
    }

    @SuppressWarnings("unchecked")
    private static double overallApdex(LiveAggregator agg) {
        return (Double) ((Map<String, Object>) agg.buildCatchupPayload("t").get("cumulativeStats")).get("apdex");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> catchupCumulative(LiveAggregator agg) {
        return (Map<String, Object>) agg.buildCatchupPayload("t").get("cumulativeStats");
    }

    private static final String EMPTY_TRANSACTION = "Number of samples in transaction : 0, number of failing samples : 0";

    private SampleResult transaction(String label, long elapsed, String message, SampleResult... steps) {
        SampleResult r = mockSuccessSample(label, elapsed);
        when(r.getResponseMessage()).thenReturn(message);
        when(r.getSubResults()).thenReturn(steps.length == 0 ? null : steps);
        return r;
    }

    /**
     * The badge reads what JMeter's HTML report reads as its Apdex Total, which counts
     * every row of the JTL: a transaction's steps, and a request's redirect hops, are
     * rows of their own beside the parent. Counting the delivered parent alone, a
     * transaction of three 300 ms steps scored 0.50 (one 900 ms sample, tolerating)
     * where the report scores (3 + 0.5) / 4 = 0.875.
     */
    @Test
    void testApdexCountsEveryRowAsJMetersReportDoes() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(transaction("Checkout", 900, "Number of samples in transaction : 3, number of failing samples : 0",
                mockSuccessSample("Cart", 300), mockSuccessSample("Pay", 300), mockSuccessSample("Confirm", 300)));

        assertEquals(0.875, overallApdex(agg), 1e-9);
        assertEquals(1L, catchupCumulative(agg).get("totalSamples"),
                "the Σ row stays the Aggregate Report's: the delivered transaction once");
    }

    /**
     * A Transaction Controller whose body sampled nothing this iteration (an If
     * Controller that was false, a Throughput Controller that skipped) still delivers
     * a successful 0 ms parent. JMeter's report leaves it out of the Apdex; counted,
     * it scored as satisfied and pulled the badge toward 1.00. The Σ row keeps it,
     * as the Aggregate Report does.
     */
    @Test
    void testApdexSkipsAnEmptyTransactionAsJMetersReportDoes() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("Search", 2000));   // frustrated
        agg.addSample(transaction("Checkout", 0, EMPTY_TRANSACTION));

        assertEquals(0.0, overallApdex(agg), 1e-9);
        assertEquals(2L, catchupCumulative(agg).get("totalSamples"));
    }

    /**
     * No sample, no score: JMeter's report has no Apdex for an empty run, and the page
     * shows the badge only once a score arrives. It read a green 1.00 from the moment
     * the auto-opened tab connected, before the first sample.
     */
    @Test
    void testNoApdexBeforeTheFirstScoredSample() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        assertFalse(catchupCumulative(agg).containsKey("apdex"), "no samples yet");

        agg.addSample(transaction("Checkout", 0, EMPTY_TRANSACTION));
        assertFalse(catchupCumulative(agg).containsKey("apdex"), "an empty transaction is not scored");

        agg.addSample(mockSuccessSample("Search", 100));
        assertEquals(1.0, overallApdex(agg), 1e-9);
    }

    /**
     * An ended test has no active threads. The gauge holds the last sample's
     * allThreads, which is at least 1 (the thread that produced it): after a graceful
     * stop the header read "Threads: 3/10" while JMeter's summariser printed "Active: 0".
     */
    @Test
    void testActiveThreadsIsZeroOnceTheTestHasEnded() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        SampleResult five = mockSuccessSample("GET /api/test", 100);
        when(five.getAllThreads()).thenReturn(5);
        agg.addSample(five);
        SampleResult last = mockSuccessSample("GET /api/test", 100);
        when(last.getAllThreads()).thenReturn(1);
        agg.addSample(last);

        agg.testEnded();

        assertEquals(0, agg.getStatus("Test").get("activeThreads"));
        assertEquals(5, agg.getStatus("Test").get("maxThreads"), "the peak is history and stays");
        @SuppressWarnings("unchecked")
        Map<String, Object> cumulative = (Map<String, Object>) agg.buildCatchupPayload("t").get("cumulativeStats");
        assertEquals(0, cumulative.get("activeThreads"), "a tab opened after the end reads it from the catch-up");
    }

    /**
     * /api/status elapsedTime stops at the end of the test. It kept counting the wall
     * clock: 3,076,510 ms for a 303 s run, read 50 minutes after it ended.
     */
    @Test
    void testStatusElapsedTimeStopsAtTheEndOfTheTest() {
        long start = clockMs.get();
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api/test", 100));
        clockMs.addAndGet(10_000);
        assertEquals(10_000L, agg.getStatus("Test").get("elapsedTime"), "runs with the clock while the test runs");

        agg.testEnded();
        clockMs.addAndGet(8 * 60_000L);

        assertEquals(10_000L, agg.getStatus("Test").get("elapsedTime"));
        assertEquals(start, agg.getStatus("Test").get("testStartTime"));
    }

    @Test
    void testErrorSummary() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        agg.addSample(mockErrorSample("GET /api/a", 100, "500"));
        agg.addSample(mockErrorSample("GET /api/a", 200, "500"));
        agg.addSample(mockErrorSample("GET /api/b", 300, "404"));

        Map<String, Object> msg = agg.flushCurrentBucket();
        @SuppressWarnings("unchecked")
        Map<String, Object> errorSummary = (Map<String, Object>) msg.get("errorSummary");
        @SuppressWarnings("unchecked")
        Map<String, Long> byCode = (Map<String, Long>) errorSummary.get("byCode");
        @SuppressWarnings("unchecked")
        Map<String, Long> bySampler = (Map<String, Long>) errorSummary.get("bySampler");

        assertEquals(2L, byCode.get("500"));
        assertEquals(1L, byCode.get("404"));
        assertEquals(2L, bySampler.get("GET /api/a"));
        assertEquals(1L, bySampler.get("GET /api/b"));
    }

    @Test
    void testHierarchyDetection() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        SampleResult child1 = mockSuccessSample("GET /api/step1", 100);
        SampleResult child2 = mockSuccessSample("GET /api/step2", 150);
        SampleResult parent = mockSuccessSample("Transaction Controller", 300);
        when(parent.getSubResults()).thenReturn(new SampleResult[]{child1, child2});

        agg.addSample(parent);

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        Map<String, String> hierarchy = (Map<String, String>) payload.get("hierarchy");

        assertNotNull(hierarchy);
        assertEquals("Transaction Controller", hierarchy.get("GET /api/step1"));
        assertEquals("Transaction Controller", hierarchy.get("GET /api/step2"));
    }

    @Test
    void testThreadCountUsesAllThreads() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        // Two thread groups of 50: getAllThreads carries total concurrency
        SampleResult r = mockSuccessSample("GET /api/test", 100);
        when(r.getAllThreads()).thenReturn(100);
        when(r.getGroupThreads()).thenReturn(50);
        agg.addSample(r);

        Map<String, Object> status = agg.getStatus("Test");
        assertEquals(100, status.get("activeThreads"));
        assertEquals(100, status.get("maxThreads"));
    }

    /**
     * allThreads covers only the engine that produced the result, so in a
     * distributed run the run's concurrency is the sum over engines. Before,
     * whichever engine's result arrived last set the gauge — a real two-engine
     * run of 10 threads each showed 10.
     */
    @Test
    void testThreadCountSumsAcrossEngines() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        SampleResult ten = mockSuccessSample("GET /api/test", 100);
        when(ten.getAllThreads()).thenReturn(10);
        agg.addSample(ten, "engine-1");
        agg.addSample(ten, "engine-2");

        Map<String, Object> status = agg.getStatus("Test");
        assertEquals(20, status.get("activeThreads"), "both engines' threads are running");
        assertEquals(20, status.get("maxThreads"));
        // The timeline's thread line is the slot's mean concurrency; once both
        // engines have reported, every sample's concurrency is the sum
        agg.flushCurrentBucket();
        clockMs.addAndGet(2000);
        agg.addSample(ten, "engine-1");
        agg.addSample(ten, "engine-2");
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) agg.flushCurrentBucket().get("overall");
        assertEquals(20.0, overall.get("activeThreads"), "the timeline's thread line must show the sum too");

        // Each engine's LATEST report counts, not its peak: engine-1 ramps down.
        SampleResult four = mockSuccessSample("GET /api/test", 100);
        when(four.getAllThreads()).thenReturn(4);
        agg.addSample(four, "engine-1");
        status = agg.getStatus("Test");
        assertEquals(14, status.get("activeThreads"));
        assertEquals(20, status.get("maxThreads"), "the peak survives a ramp-down");
    }

    @Test
    void testFinishedEngineNoLongerCountsTowardActiveThreads() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        SampleResult ten = mockSuccessSample("GET /api/test", 100);
        when(ten.getAllThreads()).thenReturn(10);
        agg.addSample(ten, "engine-1");
        agg.addSample(ten, "engine-2");

        agg.engineFinished("engine-1");

        Map<String, Object> status = agg.getStatus("Test");
        assertEquals(10, status.get("activeThreads"), "only engine-2 is still running");
        assertEquals(20, status.get("maxThreads"), "the run's peak is history, not live state");
    }

    @Test
    void testSubResultsExcludedFromOverallTotals() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        SampleResult child1 = mockSuccessSample("GET /api/step1", 100);
        SampleResult child2 = mockErrorSample("GET /api/step2", 150, "500");
        SampleResult parent = mockSample("Transaction Controller", 300, false,
                2048L, 512L, 5, 10L, 50L, "500");
        when(parent.getSubResults()).thenReturn(new SampleResult[]{child1, child2});

        agg.addSample(parent);

        // Overall totals count only the delivered top-level result (matches JMeter reports)
        Map<String, Object> status = agg.getStatus("Test");
        assertEquals(1L, status.get("totalSamples"));
        assertEquals(1L, status.get("totalErrors"));

        // Per-sampler stats still track parent and children individually
        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        Map<String, Object> samplerStats = (Map<String, Object>) payload.get("samplerStats");
        assertEquals(3, samplerStats.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> childStats = (Map<String, Object>) samplerStats.get("GET /api/step2");
        assertEquals(1L, childStats.get("samples"));
        assertEquals(1L, childStats.get("errors"));
    }

    @Test
    void testConcurrentSampleAddition() throws InterruptedException {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        int threadCount = 10;
        int samplesPerThread = 100;
        int totalExpected = threadCount * samplesPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < samplesPerThread; i++) {
                        agg.addSample(mockSuccessSample(
                                "GET /api/thread" + threadId, 100 + (i % 50)));
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS), "All threads should complete within timeout");
        executor.shutdown();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));

        Map<String, Object> status = agg.getStatus("Concurrent Test");
        assertEquals((long) totalExpected, status.get("totalSamples"));
    }

    // --- Post-test bucket suppression tests ---

    @Test
    void testFlushReturnsNullAfterTestEndedWithEmptyBucket() {
        LiveAggregator agg = new LiveAggregator(1000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));

        // Flush the bucket that has data — should succeed
        Map<String, Object> bucket = agg.flushCurrentBucket();
        assertNotNull(bucket, "Bucket with data should not be null");

        // End the test
        agg.testEnded();

        // Now flush again: the slot is empty and the test is over, so no slot is
        // sent; only the minute the test ended in, which closed at the end
        Map<String, Object> postTestBucket = agg.flushCurrentBucket();
        assertNotNull(postTestBucket, "the closing minute is still delivered");
        assertFalse(postTestBucket.containsKey("timestamp"), "no empty slot after the test ended");
        assertNotNull(postTestBucket.get("overview"));
        assertNull(agg.flushCurrentBucket(), "and only once");
    }

    @Test
    void testFlushReturnsDataDuringMidTestPause() {
        LiveAggregator agg = new LiveAggregator(1000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));

        // Flush the bucket that has data
        agg.flushCurrentBucket();

        // Test is still running — the next interval is empty (a pause)
        clockMs.addAndGet(1000);
        Map<String, Object> pauseBucket = agg.flushCurrentBucket();
        assertNotNull(pauseBucket, "Empty bucket during running test should still be broadcast (legitimate pause)");
    }

    @Test
    void testFlushReturnsDataForFinalBucketOnTestEnd() {
        LiveAggregator agg = new LiveAggregator(1000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));
        agg.addSample(mockSuccessSample("GET /api", 200));

        // End the test with data still in the current bucket. The one-shot
        // broadcast that follows must deliver that final partial interval.
        agg.testEnded();

        Map<String, Object> finalBucket = agg.flushCurrentBucket();
        assertNotNull(finalBucket, "Final partial bucket must be broadcast at test end");
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) finalBucket.get("overall");
        assertEquals(2L, overall.get("samples"));

        // Afterwards there is nothing left to broadcast
        assertNull(agg.flushCurrentBucket(), "No further broadcasts after the final bucket");
    }

    // --- Total throughput window (Aggregate Report: first start -> last end) ---

    private SampleResult mockTimedSample(String label, long timeStamp, long elapsed) {
        SampleResult r = mockSuccessSample(label, elapsed);
        when(r.getTimeStamp()).thenReturn(timeStamp);
        return r;
    }

    /** Ten samples starting at {@code base}, the last ending exactly 5 s after the first began. */
    private void addTenSamplesOverFiveSeconds(LiveAggregator agg, long base) {
        for (int i = 0; i < 10; i++) {
            agg.addSample(mockTimedSample("GET /api", base + i * 400L, 100));
        }
        agg.addSample(mockTimedSample("GET /api", base + 4000, 1000)); // ends at base + 5000
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cumulative(Map<String, Object> msg, String key) {
        return (Map<String, Object>) msg.get(key);
    }

    /** As with sampleresult.timestamp.start=false: the timestamp is the sample's END. */
    private SampleResult endStamped(String label, long start, long elapsed) {
        SampleResult r = mockSuccessSample(label, elapsed);
        when(r.getStartTime()).thenReturn(start);
        when(r.getEndTime()).thenReturn(start + elapsed);
        when(r.getTimeStamp()).thenReturn(start + elapsed);
        return r;
    }

    /**
     * The window starts at each sample's start time, which every JMeter output takes
     * from getStartTime(). With sampleresult.timestamp.start=false the timestamp is
     * the END, and a window built on it ran from the first sample's end to the last
     * one's end plus its elapsed: 4.1 s here instead of 5.
     */
    @Test
    @SuppressWarnings("unchecked")
    void testRateWindowStartsAtTheStartTimeWhenTheTimestampIsTheEnd() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        long base = clockMs.get() - 60_000;
        agg.addSample(endStamped("GET /api", base, 1000));
        agg.addSample(endStamped("GET /api", base + 4900, 100));   // ends at base + 5000

        assertEquals(2 / 5.0, (Double) catchupCumulative(agg).get("overallThroughput"), 1e-9);
        Map<String, Object> rows = (Map<String, Object>) agg.buildCatchupPayload("t").get("samplerStats");
        assertEquals(2 / 5.0, (Double) ((Map<String, Object>) rows.get("GET /api")).get("throughput"), 1e-9);
        agg.testEnded();
        assertEquals(5000L, catchupCumulative(agg).get("elapsedTime"), "the Duration card");
    }

    /**
     * A result from a sampler that never called sampleStart() carries no time at all.
     * It counts, but must not move the window: its 0 became the window's start, the
     * next sample re-seeded it, and Total throughput read 4.0/s instead of 2.4/s.
     */
    @Test
    void testAResultWithoutTimesLeavesTheTotalWindowAlone() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        long base = clockMs.get() - 60_000;
        for (int i = 0; i < 10; i++) {
            if (i == 5) agg.addSample(mockSuccessSample("Custom", 100));   // timestamp and start time 0
            agg.addSample(mockTimedSample("GET /api", base + i * 400L, 100));
        }
        agg.addSample(mockTimedSample("GET /api", base + 4000, 1000)); // ends at base + 5000

        assertEquals(12 / 5.0, (Double) catchupCumulative(agg).get("overallThroughput"), 1e-9);
    }

    @Test
    void testTotalThroughputDividesBySampleWindowWhileRunning() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        // Samples finished a minute ago: dividing by "now" would read ~0.18/s
        addTenSamplesOverFiveSeconds(agg, clockMs.get() - 60_000);

        Map<String, Object> cum = cumulative(agg.flushCurrentBucket(), "cumulative");
        assertEquals(11 / 5.0, (Double) cum.get("overallThroughput"), 1e-9);
        assertEquals(11 * 1024 / 1024.0 / 5.0, (Double) cum.get("receivedKBs"), 1e-9);
        assertEquals(11 * 256 / 1024.0 / 5.0, (Double) cum.get("sentKBs"), 1e-9);
    }

    @Test
    void testTotalThroughputDoesNotChangeWhenTestEndsAfterIdlePhase() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        addTenSamplesOverFiveSeconds(agg, clockMs.get() - 60_000);

        // Last broadcast while running, then the test ends in an idle phase: the
        // empty final slot is (deliberately) never broadcast; the closing minute
        // is, with the final summaries, which must not move the live numbers.
        Map<String, Object> live = cumulative(agg.flushCurrentBucket(), "cumulative");
        agg.testEnded();
        Map<String, Object> last = agg.flushCurrentBucket();
        assertNotNull(last, "the closing minute is delivered");
        assertFalse(last.containsKey("timestamp"), "empty final slot stays unbroadcast");
        for (String key : new String[]{"overallThroughput", "receivedKBs", "sentKBs"}) {
            assertEquals((Double) live.get(key), (Double) cumulative(last, "cumulative").get(key), 1e-9, key);
        }

        // A reload after the end must show the same numbers
        Map<String, Object> reloaded = cumulative(agg.buildCatchupPayload("Test"), "cumulativeStats");
        for (String key : new String[]{"overallThroughput", "receivedKBs", "sentKBs"}) {
            assertEquals((Double) live.get(key), (Double) reloaded.get(key), 1e-9, key);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFinalMinuteReachesTheLiveViewWhenTheTestEndsIdle() {
        // The page's 1-minute chart points take percentiles from the server's closed
        // minutes. A test that ended a few idle seconds after its last sample closed
        // its last minute but sent it nowhere: no slot was left to carry it, so a tab
        // that watched the run kept an average of 2 s values for that minute until a
        // reload.
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        long minute = clockMs.get() - clockMs.get() % 60_000; // the clock starts 20 s into it
        agg.addSample(mockSuccessSample("GET /api", 100));
        agg.addSample(mockSuccessSample("GET /api", 300));
        clockMs.addAndGet(6_000);
        assertFalse(agg.flushDueBuckets().isEmpty(), "the data slot closes and is sent");

        // Idle: mid-run, empty slots keep closing and being sent; the minute is still open
        clockMs.addAndGet(10_000);
        List<Map<String, Object>> idle = agg.flushDueBuckets();
        assertFalse(idle.isEmpty(), "empty slots close mid-run");
        assertTrue(idle.stream().noneMatch(m -> m.containsKey("overview")), "the minute has not closed yet");

        // The test ends 36 s into the minute, 16 s after its last sample
        agg.testEnded();
        List<Map<String, Object>> fin = agg.flushDueBuckets();
        assertEquals(1, fin.size(), "one final message");
        Map<String, Object> msg = fin.get(0);
        assertFalse(msg.containsKey("timestamp"), "no slot: slots after the last sample are not part of the run");
        assertEquals(Boolean.FALSE, msg.get("testRunning"));
        assertNotNull(msg.get("cumulative"), "the final summaries ride along");
        List<Map<String, Object>> sent = (List<Map<String, Object>>) msg.get("overview");
        assertNotNull(sent, "the minute the run ended in is sent");
        assertEquals(1, sent.size());
        assertEquals(minute, sent.get(0).get("timestamp"));

        // exactly the minute a reload gets
        List<Map<String, Object>> reloaded = (List<Map<String, Object>>) agg.buildCatchupPayload("Test").get("overviewBuckets");
        assertEquals(reloaded.get(reloaded.size() - 1).get("overall"), sent.get(0).get("overall"));
        assertEquals(2L, ((Number) ((Map<String, Object>) sent.get(0).get("overall")).get("samples")).longValue());
        assertTrue(agg.flushDueBuckets().isEmpty(), "delivered once");
    }

    @Test
    void testAnEndAfterTheLastMinuteClosedStillSendsTheFinalSummaries() {
        // An idle tail long enough for the last sample's slot and its minute both to
        // close left nothing open at the end, and 1.6.0 then sent no final message:
        // a tab that watched the run kept "Threads: N/M" and a Duration still counting,
        // while a reload showed 0 threads and the run's real duration.
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));
        for (int i = 0; i < 40; i++) { // 80 s idle, flushed as the server's timer does
            clockMs.addAndGet(2_000);
            agg.flushDueBuckets();
        }

        agg.testEnded();
        List<Map<String, Object>> fin = agg.flushDueBuckets();
        assertEquals(1, fin.size(), "one final message");
        Map<String, Object> msg = fin.get(0);
        assertEquals(Boolean.FALSE, msg.get("testRunning"));
        assertFalse(msg.containsKey("timestamp"), "no slot: slots after the last sample are not part of the run");
        assertFalse(msg.containsKey("overview"), "no minute was left to close");
        Map<String, Object> sent = cumulative(msg, "cumulative");
        Map<String, Object> reloaded = cumulative(agg.buildCatchupPayload("Test"), "cumulativeStats");
        assertEquals(0, ((Number) sent.get("activeThreads")).intValue(), "an ended test has no active threads");
        assertEquals(reloaded.get("elapsedTime"), sent.get("elapsedTime"), "the same duration a reload shows");
        assertTrue(agg.flushDueBuckets().isEmpty(), "delivered once");
    }

    @Test
    void testPreTrafficIdleIsNotPartOfTheRun() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        // Idle minutes before any traffic: nothing to broadcast, and the first
        // data-bearing slot must be the slot the sample is in — not one
        // spanning or charted back at aggregator creation.
        clockMs.addAndGet(60_000);
        assertTrue(agg.flushDueBuckets().isEmpty(), "Nothing to broadcast before first sample");
        clockMs.addAndGet(10_000);
        agg.addSample(mockSuccessSample("GET /api", 100));
        clockMs.addAndGet(4_000); // past the slot's end plus its grace

        List<Map<String, Object>> msgs = agg.flushDueBuckets();
        assertEquals(1, msgs.size(), "only the data-bearing slot, no idle slots before it");
        long slotStart = (Long) msgs.get(0).get("timestamp");
        assertEquals(1_700_000_070_000L - 1_700_000_070_000L % 2000, slotStart);
        assertEquals(1, agg.getRecentBuckets().size());
    }

    @Test
    void testEmptyBucketEmitsNullValueMetrics() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));
        assertNotNull(agg.flushCurrentBucket(), "Data bucket broadcasts");

        // Next interval has no samples: value-metrics are UNKNOWN (null → the
        // client charts a gap), while rates are genuinely zero.
        clockMs.addAndGet(2000);
        Map<String, Object> msg = agg.flushCurrentBucket();
        assertNotNull(msg, "Empty in-test bucket still broadcasts (keeps live and reload in sync)");
        @SuppressWarnings("unchecked")
        Map<String, Object> overall = (Map<String, Object>) msg.get("overall");
        assertEquals(0L, overall.get("samples"));
        assertNull(overall.get("avgResponseTime"), "empty interval must not fabricate a 0ms average");
        assertNull(overall.get("p95"), "empty interval must not fabricate a 0ms p95");
        assertNull(overall.get("activeThreads"), "unknown concurrency is not zero concurrency");
        assertEquals(0.0, (Double) overall.get("throughput"), 0.001, "zero throughput is real");
    }

    @Test
    void testSubResultErrorDetailsRecorded() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);

        SampleResult child = mockErrorSample("GET /login", 150, "500");
        SampleResult parent = mockSample("Transaction Controller", 300, false,
                2048L, 512L, 5, 10L, 50L, "500");
        when(parent.getSubResults()).thenReturn(new SampleResult[]{child});

        agg.addSample(parent);

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        Map<String, Object> errorSummary = (Map<String, Object>) payload.get("errorSummary");
        @SuppressWarnings("unchecked")
        Map<String, Long> bySampler = (Map<String, Long>) errorSummary.get("bySampler");
        assertEquals(1L, bySampler.get("GET /login"),
                "The failing child sampler must appear in the error breakdown");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> recentErrors =
                (java.util.List<Map<String, Object>>) payload.get("recentErrors");
        assertTrue(recentErrors.stream().anyMatch(e -> "GET /login".equals(e.get("sampler"))),
                "The child's error record (real URL/code) must be in recentErrors");

        // Headline count still matches JMeter reports: one delivered result.
        assertEquals(1L, agg.getStatus("Test").get("totalErrors"));
    }

    @Test
    void testFinalBucketStoredEvenWithoutBroadcast() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /api", 100));

        // testEnded alone (server never started / stopped early) must land the
        // final partial bucket in history for /api/history and catchup.
        agg.testEnded();

        Map<String, Object> payload = agg.buildCatchupPayload("Test");
        @SuppressWarnings("unchecked")
        java.util.List<Map<String, Object>> buckets =
                (java.util.List<Map<String, Object>>) payload.get("recentBuckets");
        assertFalse(buckets.isEmpty(), "Final partial bucket must be in history without a broadcast");
    }

    // --- Closed slots are frozen (perf runs A2-*: OOM at 500 samplers) ---

    @Test
    @SuppressWarnings("unchecked")
    void testHistoryShowsExactlyWhatTheLiveStreamShowed() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /a", 123));
        agg.addSample(mockSuccessSample("GET /a", 457));
        agg.addSample(mockErrorSample("GET /b", 89, "500"));
        Map<String, Object> live = agg.flushCurrentBucket();

        Map<String, Object> stored = agg.getRecentBuckets().get(0);
        assertEquals(live.get("timestamp"), stored.get("timestamp"));
        assertEquals(live.get("overall"), stored.get("overall"));
        assertEquals(live.get("samplers"), stored.get("samplers"));
        Map<String, Object> a = (Map<String, Object>) ((Map<String, Object>) stored.get("samplers")).get("GET /a");
        assertEquals(2L, a.get("samples"));
        assertEquals(290.0, ((Number) a.get("avgResponseTime")).doubleValue(), 1e-3);
        assertEquals(123L, a.get("minResponseTime"));
        assertEquals(457L, a.get("maxResponseTime"));
    }

    @Test
    void testClosedSlotsRetainLittleMemoryPerSampler() {
        // 200 samplers x 150 closed slots. Each slot used to keep a T-Digest per
        // sampler (~17 KB): ~500 MB here. Frozen, it is ~56 B per sampler-slot.
        int samplers = 200, slots = 150;
        LiveAggregator agg = new LiveAggregator(2000, 3_600_000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        SampleResult[] results = new SampleResult[samplers];
        // Real results, not mocks: a mock records every getter call, and that
        // history alone would dwarf what is being measured
        for (int i = 0; i < samplers; i++) {
            SampleResult r = new SampleResult();
            r.setSampleLabel("req-" + i);
            r.setSuccessful(true);
            r.setBytes(1024L);
            r.setAllThreads(5);
            results[i] = r;
        }

        long before = usedHeapAfterGc();
        for (int s = 0; s < slots; s++) {
            for (int i = 0; i < samplers; i++) {
                results[i] = restamped(results[i], clockMs.get() - 20 - i, 10 + i);
                for (int k = 0; k < 5; k++) agg.addSample(results[i]);
            }
            agg.flushCurrentBucket();
            clockMs.addAndGet(2000);
        }
        long grown = usedHeapAfterGc() - before;

        assertTrue(grown < 40L * 1024 * 1024,
                "closed slots retained " + grown / 1024 / 1024 + " MB for " + samplers * slots + " sampler-slots");
        assertEquals(slots, agg.getRecentBuckets().size(), "every slot kept");
    }

    @Test
    void testStreamedCatchupEqualsTheMapForm() throws Exception {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, 300, 5, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /a", 123));
        agg.addSample(mockErrorSample("GET /b", 89, "500"));
        agg.flushCurrentBucket();
        clockMs.addAndGet(2000);
        agg.addSample(mockSuccessSample("GET /a", 77));
        agg.flushCurrentBucket();

        com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
        CatchupSnapshot snap = agg.snapshotCatchup("Test");
        java.io.StringWriter out = new java.io.StringWriter();
        try (com.fasterxml.jackson.core.JsonGenerator gen = om.getFactory().createGenerator(out)) {
            snap.writeJson(gen, java.util.Collections.singletonMap("extra", 1));
        }
        Map<String, Object> expected = new java.util.LinkedHashMap<>(snap.toMap());
        expected.put("extra", 1);
        // On the wire, per-sampler maps are columnar
        expected.put("samplerStats", Columnar.encode(expected.get("samplerStats")));
        for (String tier : new String[]{"recentBuckets", "overviewBuckets"}) {
            for (Object b : (List<?>) expected.get(tier)) {
                @SuppressWarnings("unchecked")
                Map<String, Object> slot = (Map<String, Object>) b;
                slot.put("samplers", Columnar.encode(slot.get("samplers")));
            }
        }
        // Same text, not just equal trees: the streamed writer must not drift
        // from what the live broadcast serializes
        assertEquals(om.writeValueAsString(expected), out.toString());
    }

    // --- Slot timing (JMeter's model: end-time slots, nominal width) ---

    @Test
    void testSlotRatesDivideByTheNominalWidth() {
        // Real clock, no stepping: two flushes milliseconds apart. A slot used
        // to be divided by the measured gap between flushes, so the second
        // read 100 samples / a few ms = tens of thousands per second.
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE);
        List<Map<String, Object>> msgs = new java.util.ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < 100; i++) agg.addSample(mockSuccessSample("GET /api", 10));
            Map<String, Object> m = agg.flushCurrentBucket();
            if (m != null) msgs.add(m);
        }
        assertFalse(msgs.isEmpty());
        for (Map<String, Object> m : msgs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> overall = (Map<String, Object>) m.get("overall");
            double tput = (Double) overall.get("throughput");
            assertTrue(tput <= 200 / 2.0 + 1e-9, "slot throughput " + tput + "/s from at most 200 samples in a 2 s slot");
        }
    }

    private SampleResult endingAt(String label, long end, long elapsed) {
        SampleResult r = mockSuccessSample(label, elapsed);
        when(r.getEndTime()).thenReturn(end);
        when(r.getTimeStamp()).thenReturn(end - elapsed);
        return r;
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSampleIsChartedInTheSlotOfItsEndTime() {
        clockMs.set(1_700_000_000_000L + 100);   // 100 ms into slot k
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        long slotK = 1_700_000_000_000L;
        // Delivered in slot k+1, but it ended in slot k: it belongs to slot k
        clockMs.set(slotK + 2000 + 300);
        agg.addSample(endingAt("GET /a", slotK + 1900, 50));
        agg.addSample(endingAt("GET /a", slotK + 2000 + 250, 50));

        clockMs.set(slotK + 6000);  // both slots past their end plus grace
        List<Map<String, Object>> msgs = agg.flushDueBuckets();
        assertEquals(2, msgs.size());
        assertEquals(slotK, msgs.get(0).get("timestamp"));
        assertEquals(1L, ((Map<String, Object>) msgs.get(0).get("overall")).get("samples"));
        assertEquals(slotK + 2000, msgs.get(1).get("timestamp"));
        assertEquals(1L, ((Map<String, Object>) msgs.get(1).get("overall")).get("samples"));
        // Only the newest message carries the "now" summaries
        assertNull(msgs.get(0).get("cumulative"));
        assertEquals(0L, ((Map<String, Object>) msgs.get(1).get("cumulative")).get("lateSamples"));
    }

    @Test
    void testWallClockStepBackDoesNotSpike() {
        // The B10 case: Docker's clock stepped back 1.92 s while sample times
        // kept advancing. A slot then "lasted" 77 ms and read 12,792/s, not ~500.
        assertEquals(0L, runAcrossBackwardClockStep(1920, false));
    }

    @Test
    void testClockStepBackWithinGraceIsAbsorbed() {
        // Sample times step back too: those seconds really are recorded twice
        // (JMeter's own reports bucket them the same way), but every sample is
        // charted once, in an open slot, and nothing is overwritten
        assertEquals(0L, runAcrossBackwardClockStep(1920, true));
    }

    @Test
    void testClockStepBackBeyondGraceCountsLateSamples() {
        assertTrue(runAcrossBackwardClockStep(6000, true) > 0,
                "samples ending in an already-closed slot are counted late");
    }

    /**
     * ~250 samples/s for 10 s with the wall clock stepping back once (sample
     * times too, if {@code samplesStepToo}), flushing every 2 s as the server's
     * timer would. Always: no slot sent twice or overwritten in history, every
     * sample charted exactly once. With monotonic sample times, additionally no
     * slot reads more than 1.5x the true rate. Returns the late-sample count.
     */
    @SuppressWarnings("unchecked")
    private long runAcrossBackwardClockStep(long stepBackMs, boolean samplesStepToo) {
        long t0 = 1_700_000_000_000L;
        clockMs.set(t0);
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        List<Map<String, Object>> sent = new java.util.ArrayList<>();
        long wall = t0, sampleTime = t0;
        for (int step = 0; step < 50; step++) {
            for (int i = 0; i < 50; i++) agg.addSample(endingAt("GET /a", sampleTime, 5));
            wall += 200;
            sampleTime += 200;
            if (step == 25) {
                wall -= stepBackMs;
                if (samplesStepToo) sampleTime -= stepBackMs;
            }
            clockMs.set(wall);
            if (step % 10 == 9) sent.addAll(agg.flushDueBuckets());
        }
        clockMs.addAndGet(20_000);
        sent.addAll(agg.flushDueBuckets());

        long total = 0;
        java.util.Set<Object> stamps = new java.util.HashSet<>();
        for (Map<String, Object> m : sent) {
            Map<String, Object> overall = (Map<String, Object>) m.get("overall");
            total += (Long) overall.get("samples");
            if (!samplesStepToo) {
                assertTrue((Double) overall.get("throughput") <= 1.5 * 250,
                        "slot at " + m.get("timestamp") + " reads " + overall.get("throughput") + "/s");
            }
            assertTrue(stamps.add(m.get("timestamp")), "slot sent twice: " + m.get("timestamp"));
        }
        assertEquals(50 * 50, total, "every sample charted exactly once");
        long stored = 0;
        for (Map<String, Object> b : agg.getRecentBuckets()) {
            stored += (Long) ((Map<String, Object>) b.get("overall")).get("samples");
        }
        assertEquals(50 * 50, stored, "history kept every slot; none overwritten");
        Map<String, Object> cum = (Map<String, Object>) agg.buildCatchupPayload("t").get("cumulativeStats");
        return (Long) cum.get("lateSamples");
    }

    // --- Whole-run timeline: the one-minute tier ---

    @Test
    @SuppressWarnings("unchecked")
    void testMinuteTierKeepsTheWholeRunPastTheLiveWindow() {
        // 70 minutes, one sample per second, a 1 h live window: the 2 s tier
        // drops the first ten minutes, the minute tier keeps all seventy
        long t0 = 1_700_000_000_000L - 1_700_000_000_000L % 60_000;
        clockMs.set(t0);
        LiveAggregator agg = new LiveAggregator(2000, 3_600_000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        int overviewMsgs = 0;
        for (int sec = 0; sec < 70 * 60; sec++) {
            SampleResult r = new SampleResult();
            r.setSampleLabel("req");
            r.setStampAndTime(t0 + sec * 1000L + 100, 7);
            r.setSuccessful(true);
            agg.addSample(r);
            clockMs.set(t0 + (sec + 1) * 1000L);
            for (Map<String, Object> m : agg.flushDueBuckets()) {
                List<Map<String, Object>> ov = (List<Map<String, Object>>) m.get("overview");
                if (ov != null) {
                    overviewMsgs += ov.size();
                    assertEquals(60_000L, m.get("overviewInterval"));
                }
            }
        }
        agg.testEnded();

        Map<String, Object> catchup = agg.buildCatchupPayload("t");
        List<Map<String, Object>> live = (List<Map<String, Object>>) catchup.get("recentBuckets");
        List<Map<String, Object>> minutes = (List<Map<String, Object>>) catchup.get("overviewBuckets");
        assertTrue(live.size() <= 1801, "live tier pruned to the window: " + live.size());
        assertEquals(70, minutes.size(), "every minute of the run");
        assertEquals(t0, minutes.get(0).get("timestamp"));
        long sum = 0;
        for (Map<String, Object> m : minutes) {
            Map<String, Object> o = (Map<String, Object>) m.get("overall");
            sum += (Long) o.get("samples");
            assertEquals(60L, o.get("samples"));
            assertEquals(1.0, (Double) o.get("throughput"), 1e-9, "60 samples over a nominal minute");
        }
        assertEquals(70 * 60, sum);
        assertEquals(69, overviewMsgs, "every minute but the last was broadcast while running");
    }

    @Test
    void testKnownSamplersSentOnlyWhenTheListChanges() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE, clockMs::get);
        agg.addSample(mockSuccessSample("GET /a", 10));
        assertNotNull(agg.flushCurrentBucket().get("knownSamplers"), "first update carries the list");
        clockMs.addAndGet(2000);
        agg.addSample(mockSuccessSample("GET /a", 10));
        assertNull(agg.flushCurrentBucket().get("knownSamplers"), "unchanged: not resent every 2 s");
        clockMs.addAndGet(2000);
        agg.addSample(mockSuccessSample("GET /b", 10));
        assertEquals(java.util.Arrays.asList("GET /a", "GET /b"), agg.flushCurrentBucket().get("knownSamplers"));
        assertNotNull(agg.buildCatchupPayload("t").get("knownSamplers"), "the catch-up always carries it");
    }

    /**
     * The tab the plugin opens itself connects at test start, before any sample, so its
     * catch-up holds no hidden list and no hierarchy. Both used to travel only in the
     * catch-up: for the whole run that tab charted JSR223/Debug samplers and showed
     * transaction children un-indented, until a reload. They ride along with the update
     * that changes them now, like knownSamplers.
     */
    @Test
    void hiddenSamplersAndHierarchyGoOutWhenTheyChange() {
        LiveAggregator agg = new LiveAggregator(2000, 60000, 100, -1, -1, 500,
                new String[]{"Debug*"}, clockMs::get);
        agg.addSample(mockSuccessSample("GET /a", 10));
        Map<String, Object> first = agg.flushCurrentBucket();
        assertEquals(List.of(), first.get("hiddenSamplers"), "sent with the sampler list");
        assertNull(first.get("hierarchy"), "no transaction yet");

        clockMs.addAndGet(2000);
        SampleResult child = mockSuccessSample("GET /child", 10);
        SampleResult parent = mockSuccessSample("Checkout", 20);
        when(parent.getSubResults()).thenReturn(new SampleResult[]{child});
        agg.addSample(parent);
        agg.addSample(mockSuccessSample("Debug Sampler", 1));
        Map<String, Object> second = agg.flushCurrentBucket();
        assertEquals(List.of("Debug Sampler"), second.get("hiddenSamplers"));
        assertEquals(Map.of("GET /child", "Checkout"), second.get("hierarchy"));

        clockMs.addAndGet(2000);
        agg.addSample(parent);
        Map<String, Object> third = agg.flushCurrentBucket();
        assertNull(third.get("hiddenSamplers"), "unchanged: not resent every 2 s");
        assertNull(third.get("hierarchy"), "unchanged: not resent every 2 s");
    }

    /** A fresh copy of {@code r} at a new time: a SampleResult cannot be re-stamped. */
    private static SampleResult restamped(SampleResult r, long stamp, long elapsed) {
        SampleResult c = new SampleResult();
        c.setSampleLabel(r.getSampleLabel());
        c.setStampAndTime(stamp, elapsed);
        c.setSuccessful(r.isSuccessful());
        c.setBytes(r.getBytesAsLong());
        c.setAllThreads(r.getAllThreads());
        return c;
    }

    private static long usedHeapAfterGc() {
        Runtime rt = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        return rt.totalMemory() - rt.freeMemory();
    }
}
