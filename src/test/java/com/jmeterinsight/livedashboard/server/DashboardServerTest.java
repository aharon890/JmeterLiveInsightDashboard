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

    /**
     * No response lets another website read it. Every JSON response, the SSE stream and the
     * error responses used to carry {@code Access-Control-Allow-Origin: *}, so any page open
     * in the operator's browser could read the test name, the sampler names (often internal
     * URLs) and the error messages. The dashboard is same-origin and never needed the header;
     * curl and scripts are unaffected, because only a browser enforces it.
     */
    @Test
    void testNoResponseIsReadableCrossOrigin() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        for (String path : new String[]{"/", "/echarts.min.js", "/api/status", "/api/config",
                "/api/history", "/api/events", "/unknown"}) {
            String head = rawRequest(port, "GET", path, "127.0.0.1:" + port, "Origin: https://other.example");
            assertFalse(head.toLowerCase(Locale.ROOT).contains("access-control-allow-origin"),
                    "GET " + path + " must not carry Access-Control-Allow-Origin, got:\n" + head);
        }
        // The refusals too: a 405 and the stop endpoints' 403 are answers like any other.
        for (String[] call : new String[][]{{"POST", "/api/status"}, {"POST", "/api/stop"}, {"POST", "/api/stop-now"}}) {
            String head = rawRequest(port, call[0], call[1], "127.0.0.1:" + port, "Origin: https://other.example");
            assertFalse(head.toLowerCase(Locale.ROOT).contains("access-control-allow-origin"),
                    call[0] + " " + call[1] + " must not carry Access-Control-Allow-Origin, got:\n" + head);
        }
    }

    // --- A reader that stops reading ---

    /**
     * Asks for the SSE stream on a raw socket and never reads a byte of it: a tab frozen in a
     * debugger, a paused VM, a hung proxy. The connection stays open, so no write to it fails.
     */
    private Socket openStalledSseReader(int port) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(4096); // before connect: the window is agreed there
        socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        socket.getOutputStream().write(("GET /api/events HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private int connectedClients(int port) throws IOException {
        HttpURLConnection conn = openConnection(port, "/api/status");
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(3000);
        Map<?, ?> status = objectMapper.readValue(readResponseBody(conn), Map.class);
        conn.disconnect();
        return ((Number) status.get("connectedClients")).intValue();
    }

    private void awaitConnectedClients(int port, int expected, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (connectedClients(port) != expected) {
            if (System.currentTimeMillis() > deadline) {
                fail("connectedClients never became " + expected + ", it is " + connectedClients(port));
            }
            Thread.sleep(50);
        }
    }

    /** Broadcasts 1 MB frames, far larger than a socket buffer: a reader that reads nothing is full after the first. */
    private Thread flood(DashboardServer target, int frames) {
        char[] megabyte = new char[1 << 20];
        Arrays.fill(megabyte, 'x');
        Map<String, Object> payload = Map.of("filler", new String(megabyte));
        Thread thread = new Thread(() -> {
            for (int i = 0; i < frames; i++) target.broadcastEvent("filler", payload);
        }, "test-flood");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Sending never waits for a reader. Every frame used to be written to each client in turn
     * by the thread that sent it, so the first write to a reader whose buffers were full never
     * returned.
     */
    @Test
    void testABroadcastNeverWaitsForAReader() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        try (Socket stalled = openStalledSseReader(port)) {
            awaitConnectedClients(port, 1, 5000);
            Thread flood = flood(server, 3);
            flood.join(5000);
            assertFalse(flood.isAlive(), "a broadcast is still waiting on a reader that does not read");
        }
    }

    /**
     * One reader that stops reading must not stop the others. The broadcaster used to block on
     * the stalled reader's socket while holding the lock a new tab's catch-up takes: no tab got
     * another update and a new tab got its headers and nothing after them.
     */
    @Test
    void testANewTabGetsItsCatchupWhileAnotherReaderIsStalled() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        try (Socket stalled = openStalledSseReader(port)) {
            awaitConnectedClients(port, 1, 5000);
            flood(server, 3);
            Thread.sleep(2500); // past one 2 s tick, so the broadcaster has met the stalled reader

            HttpURLConnection fresh = openConnection(port, "/api/events");
            fresh.setConnectTimeout(3000);
            fresh.setReadTimeout(8000);
            assertEquals(200, fresh.getResponseCode());
            String first = readSseEvent(fresh.getInputStream(), 6000);
            assertTrue(first.contains("event: catchup"),
                    "a new tab got no catch-up while another reader was stalled; it got: "
                            + (first.length() > 80 ? first.substring(0, 80) : first));
            fresh.disconnect();
        }
    }

    /**
     * A reader too far behind is dropped, not buffered on without end: what waits for it is
     * held in JMeter's heap. Its tab reconnects by itself and gets a fresh catch-up.
     */
    @Test
    void testAReaderTooFarBehindIsDropped() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        try (Socket stalled = openStalledSseReader(port)) {
            awaitConnectedClients(port, 1, 5000);
            Thread flood = flood(server, 40); // 40 MB of frames against a 32 MB allowance
            flood.join(20000);
            assertFalse(flood.isAlive(), "the broadcasts did not finish");
            awaitConnectedClients(port, 0, 5000);
        }
    }

    // --- Host header (DNS rebinding) ---

    /**
     * One request over a raw socket, so the test chooses the Host header: HttpURLConnection
     * always sends the one from its URL. Returns the status line and the response headers.
     */
    private String rawRequest(int port, String method, String path, String host, String... extraHeaders)
            throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000);
            StringBuilder request = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
            if (host != null) request.append("Host: ").append(host).append("\r\n");
            for (String header : extraHeaders) request.append(header).append("\r\n");
            if ("POST".equals(method)) request.append("Content-Length: 0\r\n");
            request.append("Connection: close\r\n\r\n");
            socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
            StringBuilder head = new StringBuilder();
            for (String line = reader.readLine(); line != null && !line.isEmpty(); line = reader.readLine()) {
                head.append(line).append('\n');
            }
            return head.toString();
        }
    }

    private static int statusOf(String head) {
        return Integer.parseInt(head.split(" ")[1]);
    }

    /**
     * DNS rebinding: a page on attacker.example re-points its own name at 127.0.0.1, and the
     * browser then treats the dashboard as that page's own site. It can read the page, stop
     * token included, and call the stop endpoints with it. The request still names the
     * attacker's host, so a dashboard bound to loopback answers only to a loopback name.
     */
    @Test
    void testLoopbackBindRefusesAForeignHostName() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);
        String foreign = "attacker.example:" + port;

        for (String path : new String[]{"/", "/index.html", "/echarts.min.js", "/api/status",
                "/api/config", "/api/history", "/api/events"}) {
            assertEquals(403, statusOf(rawRequest(port, "GET", path, foreign)),
                    "GET " + path + " with Host " + foreign + " must be refused");
        }
        // With the VALID token: a rebound page has read it, so the token cannot be the defence.
        for (String path : new String[]{"/api/stop", "/api/stop-now"}) {
            assertEquals(403, statusOf(rawRequest(port, "POST", path, foreign,
                            "X-Stop-Token: " + server.getStopToken())),
                    "POST " + path + " with Host " + foreign + " and the token must be refused");
        }
    }

    @Test
    void testLoopbackBindAnswersToEveryLoopbackName() throws Exception {
        int port = findFreePort();
        LiveAggregator agg = createPopulatedAggregator();
        server = startServer(agg, port);

        // Any port: an SSH tunnel or a port-forward reaches the dashboard as localhost:<another port>.
        for (String host : new String[]{"localhost:" + port, "localhost", "LocalHost:" + port, "localhost:1",
                "127.0.0.1:" + port, "127.0.0.1", "127.8.9.10:" + port,
                "[::1]:" + port, "[::1]", "[0:0:0:0:0:0:0:1]:" + port}) {
            assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", host)),
                    "Host " + host + " names this machine and must be served");
        }
        // No Host header at all is not a browser (every browser sends one), so it is no rebinding and
        // the Host check lets it through. Newer JDKs (seen on 21.0.13) answer 400 to such an HTTP/1.1
        // request themselves, before any handler runs; older ones (17.0.5) serve it. Either way it is
        // not the 403 of the Host check.
        int noHost = statusOf(rawRequest(port, "GET", "/api/status", null));
        assertTrue(noHost == 200 || noHost == 400,
                "a request with no Host header is served, or refused with 400 by the JDK's own server: " + noHost);
    }

    @Test
    void testOnlyLoopbackNamesPassOnALoopbackBind() {
        for (String host : new String[]{"localhost", "LOCALHOST", "localhost:7566", "localhost.", "localhost.:80",
                "127.0.0.1", "127.0.0.1:7566", "127.255.255.254:1", "[::1]", "[::1]:7566",
                "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]:7566", null}) {
            assertTrue(DashboardServer.isHostAllowed(true, host), "must be allowed: " + host);
        }
        for (String host : new String[]{"attacker.example", "attacker.example:7566", "localhost.attacker.example",
                "127.0.0.1.attacker.example:7566", "notlocalhost", "128.0.0.1", "127.0.0.1.5", "1270.0.0.1",
                "127.0.0.256", "10.0.0.5:7566", "[::2]:7566", "[2001:db8::1]", "::1", "", " ", "localhost:",
                "localhost:abc", "localhost:7566:1", "localhost@attacker.example", "[::1", "[::1]x"}) {
            assertFalse(DashboardServer.isHostAllowed(true, host), "must be refused: " + host);
        }
    }

    /**
     * Bound to 0.0.0.0 or to a named interface, the dashboard is reached by whatever name the
     * network gives the machine (http://loadgen01:7566, a Docker or Kubernetes service name, a
     * proxy's upstream name), so there is no check. Docker check D3b pins this against real JMeter.
     */
    @Test
    void testABindBeyondLoopbackHasNoHostCheck() {
        for (String host : new String[]{"loadgen01:7566", "jmeter-test-12:7566", "10.0.0.5:7566",
                "attacker.example", "localhost:7566", null}) {
            assertTrue(DashboardServer.isHostAllowed(false, host), "no check beyond loopback: " + host);
        }
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
     * <p>The instance id must never be the stop token. The catch-up is data the page shows,
     * and it reaches every tab, proxy and script that reads the stream; the token is the one
     * value that authorises stopping the run, and travels only inside the page itself.
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

    // --- Access link (live.dashboard.access.token) ---

    private static final String ACCESS_TOKEN_IN_TESTS = "the-link-of-this-test-run-0123456789";

    /**
     * A dashboard started with an access token. Bound to 127.0.0.1 like every other test here: a token
     * that is set is asked for on every bind, so no test has to listen on the network to exercise it.
     * The real 0.0.0.0 bind, with real JMeter, is Docker check D16.
     */
    private DashboardServer startServerWithAccessToken(LiveAggregator agg, int port, String accessToken) throws IOException {
        DashboardServer srv = new DashboardServer(port, "127.0.0.1", agg, "Integration Test", 600, accessToken);
        srv.start();
        return srv;
    }

    private static String headerOf(String head, String name) {
        for (String line : head.split("\n")) {
            if (line.regionMatches(true, 0, name + ":", 0, name.length() + 1)) return line.substring(name.length() + 1).trim();
        }
        return null;
    }

    /**
     * Anyone who can reach the port could read the run (sampler names, URLs, error messages) and stop
     * JMeter. With a token set, a request that does not carry it is refused on every path, the stop
     * endpoints included even with their own valid stop token.
     */
    @Test
    void testWithoutItsAccessLinkADashboardRefusesEveryPath() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        String host = "localhost:" + port;

        assertTrue(server.isAccessLinkRequired());
        for (String path : new String[]{"/", "/index.html", "/echarts.min.js", "/api/status",
                "/api/config", "/api/history", "/api/events"}) {
            String head = rawRequest(port, "GET", path, host);
            assertEquals(401, statusOf(head), "GET " + path + " without the access link must be refused");
        }
        for (String path : new String[]{"/api/stop", "/api/stop-now"}) {
            assertEquals(401, statusOf(rawRequest(port, "POST", path, host, "X-Stop-Token: " + server.getStopToken())),
                    "POST " + path + " without the access link must be refused, stop token or not");
        }
        // A wrong token, in each of the three places a token can travel.
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status?token=not-the-token", host)));
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host, "Authorization: token not-the-token")));
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host,
                "Cookie: " + server.accessCookieName() + "=not-the-token")));
    }

    /**
     * The way Jupyter does it: the link carries the token once. Opening it stores the token in a cookie
     * and shows the page at its plain address, so the token is not left in the address bar, the history
     * or a link someone copies from there.
     */
    @Test
    void testTheAccessLinkSetsACookieAndShowsThePageAtItsPlainAddress() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        String host = "localhost:" + port;

        String link = rawRequest(port, "GET", "/?token=" + ACCESS_TOKEN_IN_TESTS, host);
        assertEquals(302, statusOf(link), "the link redirects to the plain address");
        assertEquals("/", headerOf(link, "Location"));
        String cookie = headerOf(link, "Set-Cookie");
        assertNotNull(cookie, "the link must set the cookie");
        assertTrue(cookie.startsWith(server.accessCookieName() + "=" + ACCESS_TOKEN_IN_TESTS + ";"), cookie);
        assertTrue(cookie.contains("HttpOnly"), "a script in the page must not be able to read it: " + cookie);
        assertTrue(cookie.contains("SameSite=Lax"), "another site's page must not send it with its requests: " + cookie);
        assertTrue(cookie.contains("Path=/"), cookie);

        // Any other parameter of the link survives the redirect; only the token is taken out.
        String withMore = rawRequest(port, "GET", "/index.html?tab=errors&token=" + ACCESS_TOKEN_IN_TESTS + "&x=1", host);
        assertEquals("/index.html?tab=errors&x=1", headerOf(withMore, "Location"));

        // From now on the plain address works in that browser: the page, its script, the data, the live stream.
        String carried = "Cookie: other=1; " + server.accessCookieName() + "=" + ACCESS_TOKEN_IN_TESTS;
        for (String path : new String[]{"/", "/echarts.min.js", "/api/status", "/api/config", "/api/history", "/api/events"}) {
            assertEquals(200, statusOf(rawRequest(port, "GET", path, host, carried)),
                    "GET " + path + " with the link's cookie must be served");
        }
    }

    /** The Stop buttons keep their own secret, and work for a browser that came in through the link. */
    @Test
    void testStopWorksWithTheLinksCookieAndStillNeedsItsOwnToken() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        String host = "localhost:" + port;
        String carried = "Cookie: " + server.accessCookieName() + "=" + ACCESS_TOKEN_IN_TESTS;

        assertEquals(403, statusOf(rawRequest(port, "POST", "/api/stop", host, carried)),
                "the access link alone does not stop the run: the page's stop token is still asked for");
        assertEquals(200, statusOf(rawRequest(port, "POST", "/api/stop", host, carried,
                "X-Stop-Token: " + server.getStopToken())));
    }

    /** A script has no cookie jar to begin with: it sends the token in a header, or in the address. */
    @Test
    void testAScriptSendsTheTokenInAHeaderOrInTheAddress() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        String host = "localhost:" + port;

        assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", host, "Authorization: token " + ACCESS_TOKEN_IN_TESTS)));
        assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", host, "Authorization: Token " + ACCESS_TOKEN_IN_TESTS)));
        // In the address an API call is answered where it is, not redirected: curl follows no redirect by default.
        String inAddress = rawRequest(port, "GET", "/api/status?token=" + ACCESS_TOKEN_IN_TESTS, host);
        assertEquals(200, statusOf(inAddress));
        assertNotNull(headerOf(inAddress, "Set-Cookie"), "a browser that opened an API address is remembered too");
        // One header form only.
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host, "Authorization: Bearer " + ACCESS_TOKEN_IN_TESTS)));
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host, "X-Access-Token: " + ACCESS_TOKEN_IN_TESTS)));
    }

    /**
     * A browser keeps cookies per host, not per port. Two dashboards on one machine (ports 7566 and 7567)
     * would overwrite each other's cookie under one name, so the name carries the port, and another
     * dashboard's cookie opens nothing here.
     */
    @Test
    void testAnotherDashboardsCookieOpensNothing() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        String host = "localhost:" + port;

        assertEquals("ld_access_" + port, server.accessCookieName());
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host,
                "Cookie: ld_access_" + (port + 1) + "=" + ACCESS_TOKEN_IN_TESTS)));
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", host, "Cookie: ld_access=" + ACCESS_TOKEN_IN_TESTS)));
    }

    /**
     * The port in the cookie's name is the one in the browser's address, not the one this server listens
     * on. Two JMeter containers both listen on 7566 and are published as 7566 and 7567, and two tunnels
     * end at two local ports. Named after the server's own port, the second link would overwrite the
     * first one's cookie, and the first tab would get 401 on its next request.
     */
    @Test
    void testTheCookieIsNamedAfterThePortInTheBrowsersAddress() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);
        // The port the browser sees, as "docker run -p <published>:<port>" or a tunnel gives it.
        int publishedPort = port == 65535 ? port - 1 : port + 1;
        String publishedHost = "localhost:" + publishedPort;

        String link = rawRequest(port, "GET", "/?token=" + ACCESS_TOKEN_IN_TESTS, publishedHost);
        assertEquals(302, statusOf(link));
        String cookie = headerOf(link, "Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.startsWith("ld_access_" + publishedPort + "=" + ACCESS_TOKEN_IN_TESTS + ";"),
                "named after the port in the address the browser opened: " + cookie);

        // The browser sends that cookie back to the same address, and it opens the dashboard there.
        assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", publishedHost,
                "Cookie: ld_access_" + publishedPort + "=" + ACCESS_TOKEN_IN_TESTS)));
        // The cookie of the dashboard published on the other port opens nothing here.
        assertEquals(401, statusOf(rawRequest(port, "GET", "/api/status", publishedHost,
                "Cookie: ld_access_" + port + "=" + ACCESS_TOKEN_IN_TESTS)));
        // An address with no port in it (a proxy on port 80): the server's own port names the cookie.
        String noPort = headerOf(rawRequest(port, "GET", "/?token=" + ACCESS_TOKEN_IN_TESTS, "localhost"), "Set-Cookie");
        assertNotNull(noPort);
        assertTrue(noPort.startsWith("ld_access_" + port + "="), noPort);
    }

    /**
     * A dashboard on localhost is still reached from other machines when something forwards to it: a
     * reverse proxy on the same machine that sends "Host: localhost", an SSH tunnel, a port-forward.
     * Such a client chooses its own Host header, so the Host check does not keep it out. A token that
     * is set is therefore asked for on a localhost bind too, as Jupyter asks on localhost.
     */
    @Test
    void testATokenThatIsSetIsAskedForOnALocalhostBindToo() throws Exception {
        int port = findFreePort();
        server = startServerWithAccessToken(createPopulatedAggregator(), port, ACCESS_TOKEN_IN_TESTS);

        assertTrue(server.isLoopbackBind());
        assertTrue(server.isAccessLinkRequired(), "a token that is set is in force on every bind");
        // What a forwarder sends on: a loopback Host, with and without a port.
        for (String forwardedHost : new String[]{"localhost", "localhost:" + port, "127.0.0.1:" + port}) {
            for (String path : new String[]{"/", "/api/status", "/api/events"}) {
                assertEquals(401, statusOf(rawRequest(port, "GET", path, forwardedHost)),
                        "GET " + path + " for Host " + forwardedHost + " without the access link must be refused");
            }
            assertEquals(401, statusOf(rawRequest(port, "POST", "/api/stop", forwardedHost,
                    "X-Stop-Token: " + server.getStopToken())), "stopping the run needs the access link too");
        }
        assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", "localhost",
                "Authorization: token " + ACCESS_TOKEN_IN_TESTS)));
    }

    /**
     * With no token set nothing is asked for, as before the property existed. An empty token is no
     * token: JMeter itself removes a property given as "-Jname=". Beyond localhost the listener warns
     * at start (Docker check D16a).
     */
    @Test
    void testWithNoTokenSetNothingIsAskedFor() throws Exception {
        for (String noToken : new String[]{null, ""}) {
            int port = findFreePort();
            server = startServerWithAccessToken(createPopulatedAggregator(), port, noToken);

            assertFalse(server.isAccessLinkRequired());
            assertEquals(200, statusOf(rawRequest(port, "GET", "/api/status", "localhost:" + port)));
            assertEquals(200, statusOf(rawRequest(port, "GET", "/", "localhost:" + port)));
            server.stop();
            server = null;
        }
    }

    @Test
    void testTheTokenIsReadFromTheAddressTheHeaderAndTheCookie() throws Exception {
        assertEquals("abc", DashboardServer.queryParameter("token=abc", "token"));
        assertEquals("abc", DashboardServer.queryParameter("tab=errors&token=abc&x=1", "token"));
        assertEquals("a b~c", DashboardServer.queryParameter("token=a%20b~c", "token"));
        assertEquals("", DashboardServer.queryParameter("token", "token"));
        assertNull(DashboardServer.queryParameter("xtoken=abc&tokens=abc", "token"));
        assertNull(DashboardServer.queryParameter(null, "token"));
        assertNull(DashboardServer.queryParameter("token=%zz", "token"), "a broken escape is no token");

        assertEquals("abc", DashboardServer.authorizationToken("token abc"));
        assertEquals("abc", DashboardServer.authorizationToken("  TOKEN   abc  "));
        assertNull(DashboardServer.authorizationToken("Bearer abc"));
        assertNull(DashboardServer.authorizationToken("tokenabc"));
        assertNull(DashboardServer.authorizationToken(null));

        assertEquals("abc", DashboardServer.cookieValue(List.of("a=1; ld_access_7566=abc; b=2"), "ld_access_7566"));
        assertEquals("abc", DashboardServer.cookieValue(List.of("a=1", "ld_access_7566=abc"), "ld_access_7566"));
        assertNull(DashboardServer.cookieValue(List.of("xld_access_7566=abc; ld_access_75660=abc"), "ld_access_7566"));
        assertNull(DashboardServer.cookieValue(null, "ld_access_7566"));

        assertEquals("7567", DashboardServer.portOfHost("loadgen01:7567"));
        assertEquals("7567", DashboardServer.portOfHost("[::1]:7567"));
        assertNull(DashboardServer.portOfHost("loadgen01"), "no port in the address");
        assertNull(DashboardServer.portOfHost("[::1]"));
        assertNull(DashboardServer.portOfHost("loadgen01:"));
        assertNull(DashboardServer.portOfHost("loadgen01:http"), "digits only: the value goes into a cookie's name");
        assertNull(DashboardServer.portOfHost("loadgen01:756600"));
        assertNull(DashboardServer.portOfHost(null));

        assertEquals("/", DashboardServer.withoutParameter(new URI("/?token=abc"), "token"));
        assertEquals("/index.html?tab=errors&x=1", DashboardServer.withoutParameter(new URI("/index.html?tab=errors&token=abc&x=1"), "token"));
        assertEquals("/?a=1", DashboardServer.withoutParameter(new URI("/?a=1"), "token"));
    }
}
