package com.jmeterinsight.livedashboard.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterinsight.livedashboard.engine.LiveAggregator;
import org.apache.jmeter.samplers.SampleResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DashboardServerTest {

    @org.junit.jupiter.api.BeforeAll
    static void setTestMode() {
        System.setProperty("live.dashboard.test.mode", "true");
    }

    private static final String[] NO_HIDE = new String[0];
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private DashboardServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    // --- Helpers ---

    private int findFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private LiveAggregator createAggregator() {
        return new LiveAggregator(2000, 60000, 100, -1, -1, 500, NO_HIDE);
    }

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

    private LiveAggregator createPopulatedAggregator() {
        LiveAggregator agg = createAggregator();
        agg.addSample(mockSuccessSample("GET /api/users", 200));
        agg.addSample(mockSuccessSample("GET /api/users", 300));
        agg.addSample(mockSuccessSample("POST /api/data", 150));
        return agg;
    }

    private DashboardServer startServer(LiveAggregator agg, int port) throws IOException {
        DashboardServer srv = new DashboardServer(port, "127.0.0.1", agg, "Integration Test");
        srv.start();
        return srv;
    }

    private HttpURLConnection openConnection(int port, String path) throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        return (HttpURLConnection) url.openConnection();
    }

    private String readResponseBody(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getInputStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String readErrorBody(HttpURLConnection conn) throws IOException {
        try (InputStream is = conn.getErrorStream();
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) return "";
            byte[] buf = new byte[4096];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toString(StandardCharsets.UTF_8.name());
        }
    }

    /**
     * Read from an SSE InputStream until we get a complete event (ends with double newline)
     * or the timeout expires.
     */
    private String readSseEvent(InputStream is, long timeoutMs) throws Exception {
        StringBuilder sb = new StringBuilder();
        long deadline = System.currentTimeMillis() + timeoutMs;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            if (is.available() > 0) {
                int len = is.read(buf);
                if (len == -1) break;
                sb.append(new String(buf, 0, len, StandardCharsets.UTF_8));
                // SSE events end with double newline
                if (sb.toString().contains("\n\n")) {
                    break;
                }
            } else {
                Thread.sleep(50);
            }
        }
        return sb.toString();
    }

    // --- Tests ---

    @Test
    void testServerStartsAndStops() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        // Verify port is bound by successfully connecting
        HttpURLConnection conn = openConnection(port, "/");
        assertEquals(200, conn.getResponseCode());
        conn.disconnect();

        // Stop the server
        server.stop();

        // Verify port is released: connecting should fail
        try {
            HttpURLConnection conn2 = openConnection(port, "/");
            conn2.setConnectTimeout(1000);
            conn2.setReadTimeout(1000);
            conn2.getResponseCode();
            fail("Expected connection to be refused after server stop");
        } catch (IOException e) {
            // Expected: port is no longer listening
        }
        server = null; // prevent double-stop in @AfterEach
    }

    @Test
    void testServeDashboardHtml() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("text/html"));

        String body = readResponseBody(conn);
        // The page should mention the dashboard in some form
        assertTrue(body.contains("Live Insight Dashboard") || body.contains("Dashboard"),
                "HTML should contain dashboard title or heading");
        conn.disconnect();
    }

    @Test
    void testServeEchartsJs() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/echarts.min.js");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("application/javascript"),
                "Content-Type should be application/javascript, was: " + conn.getContentType());

        String body = readResponseBody(conn);
        assertFalse(body.isEmpty(), "echarts.min.js response body should not be empty");
        conn.disconnect();
    }

    @Test
    void testServe404ForUnknownPath() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/unknown");
        assertEquals(404, conn.getResponseCode());

        String body = readErrorBody(conn);
        assertTrue(body.contains("Not Found"), "404 body should contain 'Not Found'");
        conn.disconnect();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServeStatusJson() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/status");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("application/json"));

        String body = readResponseBody(conn);
        Map<String, Object> status = objectMapper.readValue(body, Map.class);

        assertEquals("Integration Test", status.get("testName"));
        assertTrue((Boolean) status.get("testRunning"));
        // We added 3 samples
        assertEquals(3, ((Number) status.get("totalSamples")).intValue());
        // From the jar's manifest; classes run from target/classes have none. Docker D1b
        // checks the real jar's value.
        assertEquals(DashboardServer.PLUGIN_VERSION, status.get("pluginVersion"));
        assertEquals("unknown", status.get("pluginVersion"));
        conn.disconnect();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServeConfigJson() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/config");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("application/json"));

        String body = readResponseBody(conn);
        Map<String, Object> config = objectMapper.readValue(body, Map.class);

        assertEquals(2000, ((Number) config.get("updateIntervalMs")).intValue());
        conn.disconnect();
    }

    @Test
    @SuppressWarnings("unchecked")
    void testServeHistoryJson() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        // Flush to create a history bucket
        agg.flushCurrentBucket();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/history");
        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("application/json"));

        String body = readResponseBody(conn);
        List<?> history = objectMapper.readValue(body, List.class);
        assertNotNull(history, "History should be a JSON array");
        // After flushing a populated aggregator there should be at least one bucket
        assertFalse(history.isEmpty(), "History should have at least one bucket after flush");
        conn.disconnect();
    }

    @Test
    void testSseConnection() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());
        assertTrue(conn.getContentType().contains("text/event-stream"),
                "Content-Type should be text/event-stream, was: " + conn.getContentType());

        // Read the catchup event
        String sseData = readSseEvent(conn.getInputStream(), 5000);
        assertTrue(sseData.contains("event: catchup"), "Should receive a catchup event");
        assertTrue(sseData.contains("data: "), "SSE event should have data field");
        conn.disconnect();
    }

    @Test
    void testSseCatchupContainsTestName() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);

        assertEquals(200, conn.getResponseCode());

        String sseData = readSseEvent(conn.getInputStream(), 5000);

        // Extract the data payload from the SSE event
        String dataLine = null;
        for (String line : sseData.split("\n")) {
            if (line.startsWith("data: ")) {
                dataLine = line.substring("data: ".length());
                break;
            }
        }
        assertNotNull(dataLine, "SSE catchup event should contain a data line");
        assertTrue(dataLine.contains("Integration Test"),
                "Catchup data should contain the testName 'Integration Test'");
        conn.disconnect();
    }

    @Test
    void testStopEndpoint() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/stop");
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Stop-Token", server.getStopToken());
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int responseCode = conn.getResponseCode();
        // Should return 200 (stopping) or 500 (if no JMeter engine is available) -- either is fine
        assertTrue(responseCode == 200 || responseCode == 500,
                "POST /api/stop should return 200 or 500, got: " + responseCode);

        // Read whichever stream is appropriate
        String body;
        if (responseCode == 200) {
            body = readResponseBody(conn);
        } else {
            body = readErrorBody(conn);
        }
        assertFalse(body.isEmpty(), "Response body should not be empty");
        conn.disconnect();
    }

    @Test
    void testStopNowEndpoint() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/stop-now");
        conn.setRequestMethod("POST");
        conn.setRequestProperty("X-Stop-Token", server.getStopToken());
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);

        int responseCode = conn.getResponseCode();
        // Should return 200 (stopped) or 500 (if no JMeter engine is available) -- either is fine
        assertTrue(responseCode == 200 || responseCode == 500,
                "POST /api/stop-now should return 200 or 500, got: " + responseCode);

        String body;
        if (responseCode == 200) {
            body = readResponseBody(conn);
        } else {
            body = readErrorBody(conn);
        }
        assertFalse(body.isEmpty(), "Response body should not be empty");
        conn.disconnect();
    }

    @Test
    void testStopEndpointsRejectMissingToken() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        for (String path : new String[]{"/api/stop", "/api/stop-now"}) {
            HttpURLConnection conn = openConnection(port, path);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            assertEquals(403, conn.getResponseCode(),
                    "POST " + path + " without a token should be rejected");
            conn.disconnect();
        }
    }

    @Test
    void testStopEndpointsRejectNonPost() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);

        // A GET must be refused before the token check even runs — a browser
        // can be tricked into a cross-site GET far more easily than into a
        // preflighted POST with a custom header.
        for (String path : new String[]{"/api/stop", "/api/stop-now"}) {
            HttpURLConnection conn = openConnection(port, path);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Stop-Token", server.getStopToken());
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            assertEquals(405, conn.getResponseCode(),
                    "GET " + path + " should return 405 Method Not Allowed");
            conn.disconnect();
        }
    }

    @Test
    void testCorsHeaders() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        // The main page embeds the stop token — it must NOT be readable
        // cross-origin, so no ACAO header on the HTML.
        HttpURLConnection conn1 = openConnection(port, "/");
        assertEquals(200, conn1.getResponseCode());
        assertNull(conn1.getHeaderField("Access-Control-Allow-Origin"),
                "Root page embeds the stop token and must not carry Access-Control-Allow-Origin");
        conn1.disconnect();

        // Check CORS on an API endpoint
        HttpURLConnection conn2 = openConnection(port, "/api/status");
        assertEquals(200, conn2.getResponseCode());
        assertEquals("*", conn2.getHeaderField("Access-Control-Allow-Origin"),
                "API endpoint should have Access-Control-Allow-Origin: *");
        conn2.disconnect();

        // Check CORS on a 404 response
        HttpURLConnection conn3 = openConnection(port, "/unknown");
        assertEquals(404, conn3.getResponseCode());
        assertEquals("*", conn3.getHeaderField("Access-Control-Allow-Origin"),
                "404 response should have Access-Control-Allow-Origin: *");
        conn3.disconnect();
    }

    @Test
    void testMultipleSseClients() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        // Connect first SSE client
        HttpURLConnection conn1 = openConnection(port, "/api/events");
        conn1.setRequestProperty("Accept", "text/event-stream");
        conn1.setConnectTimeout(3000);
        conn1.setReadTimeout(5000);
        assertEquals(200, conn1.getResponseCode());

        // Connect second SSE client
        HttpURLConnection conn2 = openConnection(port, "/api/events");
        conn2.setRequestProperty("Accept", "text/event-stream");
        conn2.setConnectTimeout(3000);
        conn2.setReadTimeout(5000);
        assertEquals(200, conn2.getResponseCode());

        // Both should receive catchup events
        String sse1 = readSseEvent(conn1.getInputStream(), 5000);
        String sse2 = readSseEvent(conn2.getInputStream(), 5000);

        assertTrue(sse1.contains("event: catchup"), "Client 1 should receive catchup");
        assertTrue(sse2.contains("event: catchup"), "Client 2 should receive catchup");

        // Both should contain test data
        assertTrue(sse1.contains("Integration Test"), "Client 1 catchup should have testName");
        assertTrue(sse2.contains("Integration Test"), "Client 2 catchup should have testName");

        conn1.disconnect();
        conn2.disconnect();
    }

    @Test
    void testBroadcastEvent() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        // Connect an SSE client
        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());

        InputStream is = conn.getInputStream();

        // Consume the initial catchup event
        readSseEvent(is, 5000);

        // Broadcast a custom event
        Map<String, Object> eventData = new LinkedHashMap<>();
        eventData.put("message", "test ended");
        eventData.put("status", "complete");
        server.broadcastEvent("testEnd", eventData);

        // Read the broadcasted event
        String broadcastedSse = readSseEvent(is, 5000);
        assertTrue(broadcastedSse.contains("event: event"),
                "Broadcasted SSE should have event type 'event'");
        assertTrue(broadcastedSse.contains("testEnd"),
                "Broadcasted SSE data should contain the event type 'testEnd'");
        assertTrue(broadcastedSse.contains("test ended"),
                "Broadcasted SSE data should contain event data");

        conn.disconnect();
    }

    @Test
    void testServerPortReuse() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createAggregator();

        // Start server first time
        server = startServer(agg, port);

        HttpURLConnection conn1 = openConnection(port, "/api/status");
        assertEquals(200, conn1.getResponseCode());
        conn1.disconnect();

        // Stop the server
        server.stop();
        server = null;

        // Wait briefly for the port to be fully released
        Thread.sleep(500);

        // Start a new server on the same port
        LiveAggregator agg2 = createPopulatedAggregator();
        server = startServer(agg2, port);

        HttpURLConnection conn2 = openConnection(port, "/api/status");
        assertEquals(200, conn2.getResponseCode());

        String body = readResponseBody(conn2);
        // The new server was created with populated aggregator, so totalSamples should be 3
        assertTrue(body.contains("\"totalSamples\":3"),
                "Restarted server should serve data from new aggregator");
        conn2.disconnect();
    }

    /**
     * When the configured port is taken the server auto-increments (up to
     * MAX_PORT_ATTEMPTS) rather than failing. Observed in a real run: two
     * overlapping JMeter processes, the second bound 7602 after 7601. Nothing
     * pinned that behaviour, so a regression would only surface as a dashboard
     * mysteriously absent from the port the operator was told to open.
     */
    @Test
    void testPortFallbackWhenConfiguredPortIsBusy() throws Exception {
        int busyPort = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();

        // Occupy the configured port for the lifetime of the assertion.
        try (ServerSocket squatter = new ServerSocket(busyPort, 0, InetAddress.getByName("127.0.0.1"))) {
            server = startServer(agg, busyPort);

            assertEquals(busyPort + 1, server.getPort(),
                    "Server should fall back to the next port when the configured one is busy");

            // And the fallback port must actually be serving, not merely recorded.
            HttpURLConnection conn = openConnection(server.getPort(), "/api/status");
            assertEquals(200, conn.getResponseCode());
            String body = readResponseBody(conn);
            assertTrue(body.contains("\"totalSamples\":3"),
                    "Server on the fallback port should serve the real aggregator");
            conn.disconnect();
        }
    }

    // --- Idle Timeout Tests ---
    //
    // These are @Tag("slow"): the behaviour under test is "how long until the
    // server gives up", so the waits are the assertion and cannot be mocked
    // away without testing something else. Together they are ~28s of the
    // class's ~48s. The smoke tier skips them with -DexcludedGroups=slow; the
    // regression tier runs them. Do not delete them to save time — this is the
    // path that decides whether a headless JMeter run ever exits.

    private DashboardServer startServerWithTimeout(LiveAggregator agg, int port, int timeoutSeconds) throws IOException {
        DashboardServer srv = new DashboardServer(port, "127.0.0.1", agg, "Timeout Test", timeoutSeconds);
        srv.start();
        return srv;
    }

    @Test
    @Tag("slow")
    void testIdleTimeoutStopsServer() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 2); // 2 second timeout

        // Verify server alive
        assertEquals(200, openConnection(port, "/api/status").getResponseCode());

        // Signal test ended, no clients connected -> timeout starts
        server.onTestEnded();
        Thread.sleep(3500); // Wait beyond 2s timeout

        // Server should have stopped
        assertTrue(server.isStopped(), "Server should be stopped after idle timeout");
    }

    @Test
    @Tag("slow")
    void testIdleTimeoutNotActiveWhileTestRunning() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 2);

        // Don't call onTestEnded — test is still running
        Thread.sleep(3500);

        // Server should still be alive
        assertFalse(server.isStopped(), "Server should not stop while test is running");
        assertEquals(200, openConnection(port, "/api/status").getResponseCode());
    }

    @Test
    @Tag("slow")
    void testIdleTimeoutDisabledWhenZero() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 0); // disabled

        server.onTestEnded();
        Thread.sleep(2000);

        // Server should still be alive
        assertFalse(server.isStopped(), "Server should not stop when idle timeout is 0 (disabled)");
        assertEquals(200, openConnection(port, "/api/status").getResponseCode());
    }

    @Test
    @Tag("slow")
    void testIdleTimeoutResetsOnClientConnect() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 3); // 3 second timeout

        server.onTestEnded();
        Thread.sleep(1500); // Wait 1.5s (halfway through timeout)

        // Connect SSE client — should reset the timer
        HttpURLConnection sseConn = openConnection(port, "/api/events");
        sseConn.setReadTimeout(1000);
        try { sseConn.getInputStream(); } catch (Exception ignored) {}

        Thread.sleep(2500); // 2.5s more (total 4s from test end, but timer was reset)

        // Server should still be alive because client connected
        assertFalse(server.isStopped(), "Server should not stop while SSE client is connected");
        sseConn.disconnect();
    }

    @Test
    @Tag("slow")
    void testIdleTimeoutStartsAfterLastClientDisconnects() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 2);

        // Connect SSE client before test ends
        HttpURLConnection sseConn = openConnection(port, "/api/events");
        sseConn.setReadTimeout(500);
        try { sseConn.getInputStream(); } catch (Exception ignored) {}

        server.onTestEnded();
        Thread.sleep(3000); // Wait beyond timeout, but client is connected

        assertFalse(server.isStopped(), "Server should not stop while client connected");

        // Disconnect client
        sseConn.disconnect();

        // After test ends, the clientChecker heartbeat (every 2s) detects dead clients.
        // Once detected, idle timeout (2s) starts. Allow enough time for both.
        Thread.sleep(8000);

        assertTrue(server.isStopped(), "Server should stop after client disconnects and timeout expires");
    }

    @Test
    @Tag("slow")
    void testNoBucketsAfterTestEnded() throws Exception {
        int port = findFreePort();
        // Use short interval (500ms) so scheduler ticks quickly
        LiveAggregator fastAgg = new LiveAggregator(500, 60000, 100, -1, -1, 500, NO_HIDE);
        fastAgg.addSample(mockSuccessSample("GET /api", 100));
        server = new DashboardServer(port, "127.0.0.1", fastAgg, "Post-Test Bucket Test", 30);
        server.start();

        // Connect SSE client
        HttpURLConnection sseConn = openConnection(port, "/api/events");
        sseConn.setRequestProperty("Accept", "text/event-stream");
        sseConn.setReadTimeout(8000);
        sseConn.connect();

        // Read the catchup event
        InputStream sseStream = sseConn.getInputStream();
        readSseEvent(sseStream, 3000);

        // End the test — this should stop the scheduler
        fastAgg.testEnded();
        server.onTestEnded();

        // Wait for a few would-be scheduler intervals to pass
        Thread.sleep(2000);

        // Read any events that arrived after testEnded
        StringBuilder postEndEvents = new StringBuilder();
        long deadline = System.currentTimeMillis() + 2000;
        byte[] buf = new byte[4096];
        while (System.currentTimeMillis() < deadline) {
            if (sseStream.available() > 0) {
                int len = sseStream.read(buf);
                if (len > 0) postEndEvents.append(new String(buf, 0, len, StandardCharsets.UTF_8));
            } else {
                Thread.sleep(100);
            }
        }

        // After testEnded the scheduler is stopped: only the one-shot final
        // flush may broadcast — the slots still open at the end: at most the
        // one in its grace period and the current one — each marked as not
        // running. Nothing periodic.
        String events = postEndEvents.toString();
        String[] sseEvents = events.split("\n\n");
        int bucketEvents = 0;
        for (String evt : sseEvents) {
            if (evt.trim().isEmpty()) continue;
            if (evt.contains("\"type\":\"bucket\"")) {
                bucketEvents++;
                assertTrue(evt.contains("\"testRunning\":false"),
                        "Final bucket must be flagged testRunning:false: "
                                + evt.substring(0, Math.min(200, evt.length())));
            }
        }
        assertTrue(bucketEvents <= 2,
                "Only the one-shot final flush may broadcast after test end, got " + bucketEvents);

        sseConn.disconnect();
    }

    @Test
    void testStopIsIdempotent() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 2);

        server.stop();
        assertTrue(server.isStopped());
        // Second stop should not throw
        server.stop();
        assertTrue(server.isStopped());
        server = null; // prevent double-stop in tearDown
    }

    @Test
    void testServerClosingEventSentBeforeStop() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        // Connect SSE client
        HttpURLConnection sseConn = openConnection(port, "/api/events");
        sseConn.setRequestProperty("Accept", "text/event-stream");
        sseConn.setReadTimeout(5000);
        sseConn.connect();
        InputStream sseStream = sseConn.getInputStream();

        // Read catchup
        readSseEvent(sseStream, 3000);

        // Broadcast serverClosing then stop (simulates what testStarted does)
        server.broadcastEvent("serverClosing", Map.of("reason", "newTestStarting"));

        // Read the serverClosing event
        String event = readSseEvent(sseStream, 3000);
        assertTrue(event.contains("serverClosing"),
                "Should receive serverClosing event, got: " + event.substring(0, Math.min(200, event.length())));

        server.stop();
        sseConn.disconnect();
    }

    // --- Start-up failure handling ---

    /**
     * A failure anywhere after the socket is bound must not leave the socket bound.
     *
     * <p>start() binds in HttpServer.create and only later reaches
     * the update-interval check, which rejects 0. The caller's rollback sets
     * {@code server = null}, so without cleanup inside start() the only reference to a
     * live, listening server is dropped and the port is squatted for the life of the
     * JVM — measurably: the port kept answering HTTP 200 and the next run fell back to
     * port+1.
     */
    @Test
    void aFailedStartReleasesTheBoundPort() throws Exception {
        int port = findFreePort();
        LiveAggregator broken = new LiveAggregator(0, 60000, 100, -1, -1, 500, NO_HIDE);
        DashboardServer failing = new DashboardServer(port, "127.0.0.1", broken, "Failing Start");

        // Note: this half passes even without the fix — start() already throws. It is the
        // reuse assertion below that discriminates.
        assertThrows(RuntimeException.class, failing::start,
                "an update interval of 0 must be rejected by the scheduler");

        LiveAggregator agg = createAggregator();
        server = startServer(agg, port);
        assertEquals(port, server.getPort(),
                "a failed start must release the port it bound — the next server was pushed "
                        + "to a fallback port, so the failed one is still listening");
    }

    /**
     * The catchup payload must carry the two pieces of state only the server knows: the
     * real idle timeout (the page otherwise hardcodes "10 minutes", which is wrong for
     * any configured value and is an outright false promise when the timeout is 0), and
     * an instance id so a reconnecting tab can tell it has been handed a DIFFERENT
     * server that took the port.
     *
     * <p>The instance id must never be the stop token. {@code /api/events} sends
     * {@code Access-Control-Allow-Origin: *} while {@code /} deliberately does not — see
     * {@link #testCorsHeaders} — so anything on this payload is readable cross-origin.
     * The token's whole purpose is that it is not.
     */
    @Test
    void testCatchupCarriesIdleTimeoutAndInstanceId() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServerWithTimeout(agg, port, 42);

        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());

        String dataLine = null;
        for (String line : readSseEvent(conn.getInputStream(), 5000).split("\n")) {
            if (line.startsWith("data: ")) {
                dataLine = line.substring("data: ".length());
                break;
            }
        }
        assertNotNull(dataLine, "SSE catchup event should contain a data line");

        Map<?, ?> payload = objectMapper.readValue(dataLine, Map.class);
        assertEquals(42, ((Number) payload.get("idleTimeoutSeconds")).intValue(),
                "catchup must carry the configured idle timeout so the page can state it");

        Object instanceId = payload.get("serverInstanceId");
        assertNotNull(instanceId, "catchup must carry a per-server instance id");
        assertNotEquals(server.getStopToken(), instanceId,
                "the instance id must NOT be the stop token — /api/events is CORS-readable "
                        + "and the token must not be");
        conn.disconnect();
    }

    /**
     * A bind address that exists on no interface is not a port conflict, and retrying
     * ten ports cannot fix it. Reporting it as port exhaustion sends the operator after
     * a conflict that does not exist.
     *
     * <p>192.0.2.0/24 is RFC 5737 TEST-NET-1, reserved for documentation and guaranteed
     * not to be routable or assigned to a real interface.
     */
    @Test
    void badBindMessageNamesTheAddress() throws Exception {
        LiveAggregator agg = createAggregator();
        // Assigned to the field so @AfterEach stops it: if this machine somehow DOES have
        // 192.0.2.1 (a lab alias, a container netns), the bind succeeds and a live server
        // would otherwise leak out of the test. findFreePort() rather than a fixed port,
        // like every other test here, so a real dashboard on 7699 cannot interfere.
        server = new DashboardServer(findFreePort(), "192.0.2.1", agg, "Bad Bind");

        IOException e = assertThrows(IOException.class, server::start,
                "192.0.2.1 is RFC 5737 TEST-NET-1 and must not be bindable");
        assertTrue(e.getMessage().contains("192.0.2.1"),
                "the failure must name the address that could not be bound, got: " + e.getMessage());
    }

    /**
     * The catch-up of a long run used to exist in memory four times over (Map tree,
     * String, StringBuilder, bytes) while it was written. Here a client reads only the
     * first bytes, so TCP backpressure parks the server mid-catch-up: whatever it holds
     * for that client is held right now.
     */
    @Test
    void testCatchupIsStreamedNotBuiltInMemory() throws Exception {
        // 600 samplers x 600 closed slots: ~29 MB of (columnar) catch-up JSON
        int samplers = 600, slots = 600;
        // The slots are made in the past, on a stepped clock, then the clock
        // catches up with real time so the server's own timer behaves normally
        long realNow = System.currentTimeMillis();
        java.util.concurrent.atomic.AtomicLong clockMs =
                new java.util.concurrent.atomic.AtomicLong(realNow - slots * 2000L - 10_000);
        LiveAggregator agg = new LiveAggregator(2000, 3_600_000, 100, -1, -1, 500, NO_HIDE,
                () -> clockMs.get() == Long.MIN_VALUE ? System.currentTimeMillis() : clockMs.get());
        SampleResult[] results = new SampleResult[samplers];
        for (int i = 0; i < samplers; i++) {
            SampleResult r = new SampleResult();   // real results: mocks record every call
            r.setSampleLabel("req-" + i);
            r.setSuccessful(true);
            r.setAllThreads(5);
            results[i] = r;
        }
        for (int s = 0; s < slots; s++) {
            for (int i = 0; i < samplers; i++) {
                results[i] = restamped(results[i], clockMs.get() - 20 - i, 10 + i);
                agg.addSample(results[i]);
            }
            agg.flushCurrentBucket();
            clockMs.addAndGet(2000);
        }
        clockMs.set(Long.MIN_VALUE); // from here on: the real clock

        int port = findFreePort();
        server = startServer(agg, port);

        long before = usedHeapAfterGc();
        try (Socket sock = new Socket()) {
            sock.setReceiveBufferSize(16 * 1024);
            sock.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            sock.setSoTimeout(30_000);
            sock.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1\r\n"
                    + "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            InputStream in = new BufferedInputStream(sock.getInputStream());
            byte[] head = new byte[4096];
            int got = 0;
            while (got < head.length) {
                int n = in.read(head, got, head.length - got);
                if (n < 0) break;
                got += n;
            }
            assertTrue(new String(head, 0, got, StandardCharsets.UTF_8).contains("event: catchup"));
            Thread.sleep(500); // let the server fill the socket buffers and block
            long held = usedHeapAfterGc() - before;

            assertTrue(held < 20L * 1024 * 1024,
                    "server held " + held / 1024 / 1024 + " MB while writing the catch-up");
        }

        // A full read of the streamed catch-up is still one valid SSE frame
        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setReadTimeout(30_000);
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
        assertEquals("event: catchup", reader.readLine());
        String data = reader.readLine();
        assertTrue(data.startsWith("data: "));
        assertEquals("", reader.readLine(), "frame ends with a blank line");
        String json = data.substring(6);
        assertTrue(json.length() > 25_000_000, "catch-up should be large: " + json.length());
        @SuppressWarnings("unchecked")
        Map<String, Object> catchup = objectMapper.readValue(json, Map.class);
        assertTrue(((List<?>) catchup.get("recentBuckets")).size() >= slots);
        assertNotNull(catchup.get("serverInstanceId"));
        conn.disconnect();
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

    @Test
    @SuppressWarnings("unchecked")
    void testSseIsGzippedWhenTheClientAcceptsIt() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        HttpURLConnection conn = openConnection(port, "/api/events");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setReadTimeout(5000);
        assertEquals(200, conn.getResponseCode());
        assertEquals("gzip", conn.getHeaderField("Content-Encoding"));
        // Bytes, not a Reader: InflaterInputStream.available() is 1 until the
        // end, so a Reader keeps reading into the next event and blocks there
        InputStream in = new java.util.zip.GZIPInputStream(conn.getInputStream());
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int prev = -1, c;
        while ((c = in.read()) >= 0 && !(c == '\n' && prev == '\n')) {
            frame.write(c);
            prev = c;
        }
        // Each event is sync-flushed: the catch-up arrives without waiting for more
        String[] lines = frame.toString("UTF-8").split("\n");
        assertEquals("event: catchup", lines[0]);
        Map<String, Object> catchup = objectMapper.readValue(lines[1].substring(6), Map.class);
        // Per-sampler maps travel columnar: field names once, a row per sampler
        Map<String, Object> stats = (Map<String, Object>) catchup.get("samplerStats");
        List<String> cols = (List<String>) stats.get("cols");
        Map<String, List<Object>> rows = (Map<String, List<Object>>) stats.get("rows");
        assertEquals(2, ((Number) rows.get("GET /api/users").get(cols.indexOf("samples"))).intValue());
        in.close();
        conn.disconnect();

        // Without Accept-Encoding: plain, as before
        HttpURLConnection plain = openConnection(port, "/api/events");
        plain.setReadTimeout(5000);
        assertEquals(200, plain.getResponseCode());
        assertNull(plain.getHeaderField("Content-Encoding"));
        plain.disconnect();
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
