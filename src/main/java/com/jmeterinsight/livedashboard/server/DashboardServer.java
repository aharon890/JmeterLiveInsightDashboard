package com.jmeterinsight.livedashboard.server;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jmeterinsight.livedashboard.engine.CatchupSnapshot;
import com.jmeterinsight.livedashboard.engine.Columnar;
import com.jmeterinsight.livedashboard.engine.LiveAggregator;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.jmeter.engine.StandardJMeterEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPOutputStream;

/**
 * Embedded HTTP server for the live dashboard.
 * Uses JDK's built-in HttpServer for zero-dependency HTTP serving
 * and Server-Sent Events (SSE) for real-time data streaming.
 *
 * Endpoints:
 *   GET  /              - Dashboard HTML page
 *   GET  /api/events    - SSE stream (live data push)
 *   GET  /api/status    - Current test status (JSON)
 *   GET  /api/config    - Dashboard configuration (JSON)
 *   GET  /api/history   - Historical bucket data (JSON)
 *   POST /api/stop      - Graceful test stop
 *   POST /api/stop-now  - Immediate test stop
 */
public class DashboardServer {

    private static final Logger log = LoggerFactory.getLogger(DashboardServer.class);

    /**
     * The running build's version, from the jar's manifest (Implementation-Version,
     * written by the pom). "unknown" outside a jar, e.g. in unit tests. Logged at
     * start and served in /api/status, so a bug report can say which build it was.
     */
    public static final String PLUGIN_VERSION = Optional.ofNullable(
            DashboardServer.class.getPackage().getImplementationVersion()).orElse("unknown");

    private static final int MAX_PORT_ATTEMPTS = 10;
    private int port;
    private final String bind;
    private final LiveAggregator aggregator;
    private final String testName;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Per-server secret required on state-changing endpoints. It is embedded in
    // the served page, so the dashboard can call them but a cross-origin page
    // cannot (a custom header forces a CORS preflight, which is never granted).
    private final String stopToken = UUID.randomUUID().toString();

    // Identifies THIS server instance on the catchup payload, so a tab whose EventSource
    // reconnected can tell it has been handed a different server — a run that restarted,
    // or another JMeter process that took this port after the first one died. Deliberately
    // NOT the stop token: /api/events carries Access-Control-Allow-Origin: *, so anything
    // on that payload is readable cross-origin, and the token's whole purpose is that it
    // is not (see the header handling for "/" and testCorsHeaders).
    private final String serverInstanceId = UUID.randomUUID().toString();

    private HttpServer httpServer;
    private final List<SseClient> sseClients = new CopyOnWriteArrayList<>();

    // Held across flush+broadcast, and across a new client's catch-up snapshot
    // plus its registration. So every slot is either in a client's catch-up or
    // broadcast to it afterwards — never both, never neither — while the
    // catch-up itself is written outside the lock.
    private final Object broadcastLock = new Object();

    // A catch-up (or /api/history) of a long run is large to write even when
    // streamed; a few tabs reloading at once must not each hold a slice of
    // heap and a socket's worth of buffers. Extra requests wait their turn.
    private final Semaphore catchupPermits = new Semaphore(2);
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService idleTimer;
    private String cachedDashboardHtml;
    private String cachedEchartsJs;
    private final int idleTimeoutSeconds;
    private volatile boolean testEnded = false;
    private volatile boolean stopped = false;

    public DashboardServer(int port, String bind, LiveAggregator aggregator, String testName) {
        this(port, bind, aggregator, testName, 600);
    }

    public DashboardServer(int port, String bind, LiveAggregator aggregator, String testName, int idleTimeoutSeconds) {
        this.port = port;
        this.bind = bind;
        this.aggregator = aggregator;
        this.testName = testName;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    public void start() throws IOException {
        // Try configured port, auto-increment if already in use
        IOException lastEx = null;
        for (int attempt = 0; attempt < MAX_PORT_ATTEMPTS; attempt++) {
            try {
                httpServer = HttpServer.create(new InetSocketAddress(bind, port + attempt), 0);
                if (attempt > 0) {
                    log.info("Port {} was in use, using port {} instead", port, port + attempt);
                    port = port + attempt;
                }
                lastEx = null;
                break;
            } catch (java.net.BindException e) {
                lastEx = e;
                if (httpServer != null) { try { httpServer.stop(0); } catch (Exception ignored) {} httpServer = null; }
                log.debug("Port {} in use, trying {}", port + attempt, port + attempt + 1);
            }
        }
        if (lastEx != null) {
            // Name the bind address, not just the port range. A BindException covers both
            // "address already in use" and "cannot assign requested address", and the old
            // message reported the second as the first — sending an operator who typo'd
            // the bind address hunting for a port conflict that does not exist.
            throw new IOException("Could not start the dashboard on " + bind + ", ports "
                    + port + "-" + (port + MAX_PORT_ATTEMPTS - 1) + notOnThisMachineHint(), lastEx);
        }

        // From here the socket is ALREADY BOUND — HttpServer.create binds, start() only
        // begins dispatching — so every failure below must release it. Without this the
        // caller's `server = null` rollback drops the only reference to a live, listening
        // server: the port keeps answering requests and nothing in the process can ever
        // stop it. Reachable from a single bad config value (update.interval=0, which
        // which the interval check below rejects), and in the GUI it leaks one socket per run.
        boolean started = false;
        try {
            httpServer.createContext("/", this::handleDashboard);
            httpServer.createContext("/api/events", this::handleSseEvents);
            httpServer.createContext("/api/status", this::handleStatus);
            httpServer.createContext("/api/config", this::handleConfig);
            httpServer.createContext("/api/history", this::handleHistory);
            httpServer.createContext("/api/stop", this::handleStop);
            httpServer.createContext("/api/stop-now", this::handleStopNow);

            httpServer.setExecutor(Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "LiveDashboard-HTTP");
                t.setDaemon(true);
                return t;
            }));
            httpServer.start();

            // Start periodic data broadcast to SSE clients
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LiveDashboard-Broadcaster");
                t.setDaemon(true);
                return t;
            });

            int interval = aggregator.getUpdateIntervalMs();
            if (interval <= 0) {
                throw new IllegalArgumentException("update interval must be positive, was " + interval);
            }
            scheduleNextTick();
            started = true;
        } finally {
            if (!started) {
                // Swallow a cleanup failure: it must never replace the exception that
                // actually explains why the start failed.
                try {
                    stop();
                } catch (Exception cleanupFailed) {
                    log.debug("Releasing the socket after a failed start also failed: {}",
                            cleanupFailed.getMessage());
                }
            }
        }

        log.info("Dashboard HTTP server started on {}:{}", bind, port);
    }

    /**
     * Advisory suffix for a bind failure, never a pre-flight rejection.
     *
     * <p>Deliberately used only to explain a failure that already happened.
     * {@code NetworkInterface.getByInetAddress} returns null for interfaces that are
     * down, for IPv6 link-local addresses carrying a scope id, and for addresses added
     * after JMeter starts (VPN, Docker, WSL) — so refusing a bind on its say-so could
     * lose a dashboard that would have worked, silently. Getting the hint wrong only
     * costs a slightly-off message.
     */
    private String notOnThisMachineHint() {
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(bind);
            if (!addr.isAnyLocalAddress() && !addr.isLoopbackAddress()
                    && java.net.NetworkInterface.getByInetAddress(addr) == null) {
                return " — no network interface on this machine has address " + bind;
            }
        } catch (Exception ignored) {
            // Cannot tell: say nothing extra rather than guess.
        }
        return "";
    }

    public void stop() {
        if (stopped) return;
        stopped = true;
        if (idleTimer != null) {
            idleTimer.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        for (SseClient client : sseClients) {
            try { client.close(); } catch (Exception ignored) {}
        }
        sseClients.clear();
        if (httpServer != null) {
            httpServer.stop(1);
            httpServer = null;
        }
        log.info("Dashboard server stopped on port {}", port);
    }

    public boolean isStopped() {
        return stopped;
    }

    /**
     * Called when the test ends. Starts the idle timeout countdown
     * if no SSE clients are connected. Also starts a periodic check
     * for disconnected clients that haven't been detected yet.
     */
    public void onTestEnded() {
        testEnded = true;

        // Stop the periodic bucket broadcaster — no more data will arrive.
        // Do one final broadcast to flush any residual data, then shut down.
        if (scheduler != null && !scheduler.isShutdown()) {
            broadcastBucket();
            scheduler.shutdownNow();
        }

        // Start periodic dead-client detection (every 2s) since broadcastBucket
        // won't detect dead clients when there's no new data after test ends
        if (idleTimeoutSeconds > 0) {
            ScheduledExecutorService clientChecker = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "LiveDashboard-ClientChecker");
                t.setDaemon(true);
                return t;
            });
            clientChecker.scheduleAtFixedRate(() -> {
                if (stopped) { clientChecker.shutdownNow(); return; }
                sendToAll("heartbeat", "{\"type\":\"heartbeat\"}");
            }, 2, 2, TimeUnit.SECONDS);
        }
        scheduleIdleShutdownIfNeeded();
    }

    private void scheduleIdleShutdownIfNeeded() {
        if (!testEnded || idleTimeoutSeconds <= 0 || stopped) return;
        if (!sseClients.isEmpty()) return; // clients still connected, don't schedule

        cancelIdleTimer();
        idleTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LiveDashboard-IdleTimer");
            t.setDaemon(true);
            return t;
        });
        idleTimer.schedule(() -> {
            if (sseClients.isEmpty() && testEnded) {
                log.info("Idle timeout reached ({}s with no clients). Shutting down dashboard server.", idleTimeoutSeconds);
                stop();
            }
        }, idleTimeoutSeconds, TimeUnit.SECONDS);
        log.debug("Idle shutdown scheduled in {} seconds", idleTimeoutSeconds);
    }

    private void cancelIdleTimer() {
        if (idleTimer != null) {
            idleTimer.shutdownNow();
            idleTimer = null;
        }
    }

    private void onClientConnected() {
        cancelIdleTimer(); // Client connected, cancel any pending shutdown
    }

    private void onClientDisconnected() {
        scheduleIdleShutdownIfNeeded(); // Last client gone? Start countdown
    }

    // --- HTTP Handlers ---

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        String path = exchange.getRequestURI().getPath();
        if ("/echarts.min.js".equals(path)) {
            sendResponse(exchange, 200, "application/javascript; charset=utf-8", getEchartsJs());
            return;
        }
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            sendResponse(exchange, 404, "text/plain", "Not Found");
            return;
        }
        sendResponse(exchange, 200, "text/html; charset=utf-8", getDashboardHtml());
    }

    private void handleSseEvents(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        boolean gzip = acceptsGzip(exchange);
        if (gzip) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, 0);

        OutputStream os = gzip ? gzipStream(exchange.getResponseBody()) : exchange.getResponseBody();
        SseClient client = new SseClient(os, exchange);
        CatchupSnapshot snapshot;
        synchronized (broadcastLock) {
            snapshot = aggregator.snapshotCatchup(testName);
            sseClients.add(client);
        }
        onClientConnected();

        log.debug("SSE client connected. Total clients: {}", sseClients.size());

        // Stream the catch-up so the client gets current state; broadcasts
        // meanwhile queue on the client and follow it in order.
        boolean sent = false;
        try {
            // Two pieces of state the aggregator has no business knowing but the page
            // cannot work without: the real idle timeout (the page otherwise states a
            // hardcoded "10 minutes", which is wrong for any configured value and is a
            // false promise when the timeout is 0 and no timer is scheduled at all), and
            // this server's identity, so a reconnecting tab can notice it has been handed
            // a different one.
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("idleTimeoutSeconds", idleTimeoutSeconds);
            extra.put("serverInstanceId", serverInstanceId);
            catchupPermits.acquire();
            try {
                sent = client.writeCatchup(out -> {
                    try (JsonGenerator gen = newGenerator(out)) {
                        snapshot.writeJson(gen, extra);
                    }
                });
            } finally {
                catchupPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Failed to send catchup data: {}", e.getMessage());
        }
        if (!sent || !client.goLive()) {
            client.close();
            if (sseClients.remove(client) && sseClients.isEmpty()) onClientDisconnected();
        }

        // Keep the connection open - don't close the exchange.
        // The SSE client will be removed when the connection drops.
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        Map<String, Object> status = aggregator.getStatus(testName);
        status.put("connectedClients", sseClients.size());
        status.put("pluginVersion", PLUGIN_VERSION);
        sendJsonResponse(exchange, 200, status);
    }

    private void handleConfig(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        sendJsonResponse(exchange, 200, aggregator.getConfig());
    }

    private void handleHistory(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        // Streamed like the catch-up, for the same reason: an hour of slots at
        // many samplers is tens of MB as one String.
        CatchupSnapshot history = aggregator.snapshotHistory();
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        boolean gzip = acceptsGzip(exchange);
        if (gzip) exchange.getResponseHeaders().set("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, 0);
        OutputStream body = gzip ? gzipStream(exchange.getResponseBody()) : exchange.getResponseBody();
        try (OutputStream os = new BufferedOutputStream(body, 1 << 16)) {
            catchupPermits.acquire();
            try (JsonGenerator gen = newGenerator(os)) {
                history.writeBuckets(gen);
            } finally {
                catchupPermits.release();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean acceptsGzip(HttpExchange exchange) {
        String accept = exchange.getRequestHeaders().getFirst("Accept-Encoding");
        return accept != null && accept.toLowerCase(Locale.ROOT).contains("gzip");
    }

    /**
     * Compresses the stream when the client accepts it: the SSE stream is a
     * run of JSON with the same keys and names over and over, and at 1,000
     * samplers an update was ~800 KB uncompressed. Level 3: the work runs
     * inside the JMeter process, and measured on a 1,000-sampler update
     * (200 KB columnar) level 3 takes 2.8 ms for 64 KB where level 6 takes
     * 8.3 ms for 57 KB. Sync-flushed, so each event reaches the browser as
     * soon as it is written.
     */
    private static OutputStream gzipStream(OutputStream out) throws IOException {
        return new GZIPOutputStream(out, 8192, true) {
            {
                def.setLevel(GZIP_LEVEL);
            }
        };
    }

    private static final int GZIP_LEVEL = 3;

    /** A generator that leaves the stream open: the caller owns it. */
    private JsonGenerator newGenerator(OutputStream out) throws IOException {
        JsonGenerator gen = objectMapper.getFactory().createGenerator(out, JsonEncoding.UTF8);
        gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
        return gen;
    }

    private boolean isStopAuthorized(HttpExchange exchange) throws IOException {
        String token = exchange.getRequestHeaders().getFirst("X-Stop-Token");
        if (stopToken.equals(token)) {
            return true;
        }
        log.warn("Rejected unauthorized stop request from {}", exchange.getRemoteAddress());
        sendJsonResponse(exchange, 403, Map.of("error", "missing or invalid stop token"));
        return false;
    }

    private void handleStop(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!isStopAuthorized(exchange)) {
            return;
        }
        try {
            log.info("Stop test requested from dashboard");
            StandardJMeterEngine.stopEngine();
            sendJsonResponse(exchange, 200, Map.of("status", "stopping"));
        } catch (Exception e) {
            log.error("Failed to stop test: {}", e.getMessage());
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    private void handleStopNow(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            sendResponse(exchange, 405, "text/plain", "Method Not Allowed");
            return;
        }
        if (!isStopAuthorized(exchange)) {
            return;
        }
        try {
            log.info("Shutdown requested from dashboard");
            sendJsonResponse(exchange, 200, Map.of("status", "shutting down"));
            // Stop engine and kill JVM
            new Thread(() -> {
                try { StandardJMeterEngine.stopEngineNow(); } catch (Exception ignored) {}
                try { Thread.sleep(500); } catch (Exception ignored) {}
                if (!Boolean.getBoolean("live.dashboard.test.mode")) {
                    log.info("Shutting down JMeter process");
                    System.exit(0);
                }
            }, "LiveDashboard-Shutdown").start();
        } catch (Exception e) {
            log.error("Failed to shutdown: {}", e.getMessage());
            sendJsonResponse(exchange, 500, Map.of("error", e.getMessage()));
        }
    }

    // --- SSE Broadcasting ---

    /**
     * Send one SSE frame to every connected client, drop any that fail, and
     * start the idle-shutdown countdown if that emptied the client list.
     * The single choke point for all three broadcast paths so none of them
     * can forget the empty-client callback.
     */
    private void sendToAll(String event, String json) {
        if (sseClients.isEmpty()) return;
        List<SseClient> deadClients = new ArrayList<>();
        for (SseClient client : sseClients) {
            if (!client.send(event, json)) {
                deadClients.add(client);
            }
        }
        if (!deadClients.isEmpty()) {
            sseClients.removeAll(deadClients);
            log.debug("Removed {} disconnected SSE clients. Active: {}", deadClients.size(), sseClients.size());
            if (sseClients.isEmpty()) onClientDisconnected();
        }
    }

    // How long after a slot boundary the timer looks. Slots close by the
    // clock, not by the timer (see LiveAggregator.flushDueBuckets); the timer
    // only decides when to look, and re-aligns to the boundary every tick so
    // each tick finds exactly one slot due — a fixed rate drifts, and fires
    // missed runs back to back after a stall.
    private static final long TICK_AFTER_BOUNDARY_MS = 50;

    private void scheduleNextTick() {
        try {
            scheduler.schedule(this::tick, aggregator.millisUntilNextSlot() + TICK_AFTER_BOUNDARY_MS,
                    TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shutDown) {
            // Stopped or test ended: no more ticks
        }
    }

    private void tick() {
        try {
            broadcastBucket();
        } finally {
            if (!scheduler.isShutdown()) scheduleNextTick();
        }
    }

    private void broadcastBucket() {
        synchronized (broadcastLock) {
            try {
                for (Map<String, Object> bucket : aggregator.flushDueBuckets()) {
                    sendToAll("bucket", objectMapper.writeValueAsString(Columnar.wireMessage(bucket)));
                }
            } catch (Exception e) {
                log.debug("Error broadcasting bucket: {}", e.getMessage());
            }
        }
    }

    public void broadcastEvent(String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "event");
            event.put("event", eventType);
            event.put("timestamp", System.currentTimeMillis());
            event.put("data", data);

            sendToAll("event", objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.debug("Error broadcasting event: {}", e.getMessage());
        }
    }

    // --- Helpers ---

    private String getDashboardHtml() {
        if (cachedDashboardHtml != null) return cachedDashboardHtml;

        try (InputStream is = getClass().getResourceAsStream("/dashboard/index.html")) {
            if (is == null) {
                return "<html><body><h1>Dashboard resource not found</h1></body></html>";
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = is.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            cachedDashboardHtml = bos.toString(StandardCharsets.UTF_8.name())
                    .replace("__LD_STOP_TOKEN__", stopToken);
        } catch (IOException e) {
            log.error("Failed to load dashboard HTML", e);
            cachedDashboardHtml = "<html><body><h1>Error loading dashboard</h1></body></html>";
        }
        return cachedDashboardHtml;
    }

    private String getEchartsJs() {
        if (cachedEchartsJs != null) return cachedEchartsJs;
        try (InputStream is = getClass().getResourceAsStream("/dashboard/echarts.min.js")) {
            if (is == null) return "// echarts.min.js not found";
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int len;
            while ((len = is.read(buf)) != -1) bos.write(buf, 0, len);
            cachedEchartsJs = bos.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            log.error("Failed to load echarts.min.js", e);
            cachedEchartsJs = "// error loading echarts";
        }
        return cachedEchartsJs;
    }

    private void sendResponse(HttpExchange exchange, int code, String contentType, String body) throws IOException {
        // The dashboard page (and its JS bundle) embeds the per-server stop
        // token. Serving it with a wildcard ACAO would let any cross-origin
        // page fetch the HTML and read the secret, defeating the token check —
        // so only the read-only JSON API responses carry the CORS header.
        boolean isHtmlAsset = contentType.startsWith("text/html")
                || contentType.startsWith("application/javascript");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if (!isHtmlAsset) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        }
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendJsonResponse(HttpExchange exchange, int code, Object data) throws IOException {
        String json = objectMapper.writeValueAsString(data);
        sendResponse(exchange, code, "application/json; charset=utf-8", json);
    }

    public int getPort() { return port; }

    /** Secret required by the POST /api/stop and /api/stop-now endpoints. */
    public String getStopToken() { return stopToken; }

    // --- SSE Client ---

    private static class SseClient {
        // A client whose catch-up takes longer than this many broadcasts
        // (over half an hour at the 2 s default) is dropped, not buffered on.
        private static final int MAX_PENDING = 1000;

        private final OutputStream outputStream;
        private final HttpExchange exchange;
        private volatile boolean alive = true;

        // Broadcasts that arrived while the catch-up was still being written,
        // or null once the client is live. While non-null, only the handler
        // thread writing the catch-up touches the stream.
        private List<String[]> pending = new ArrayList<>();

        SseClient(OutputStream outputStream, HttpExchange exchange) {
            this.outputStream = outputStream;
            this.exchange = exchange;
        }

        interface Body {
            void writeTo(OutputStream out) throws IOException;
        }

        /** Writes the catch-up frame directly; called once, before {@link #goLive}. */
        boolean writeCatchup(Body body) {
            if (!alive) return false;
            try {
                OutputStream out = new BufferedOutputStream(new FilterOutputStream(outputStream) {
                    // Closing a wrapper must never close the SSE stream itself
                    @Override public void close() throws IOException { flush(); }
                }, 1 << 16);
                out.write("event: catchup\ndata: ".getBytes(StandardCharsets.UTF_8));
                body.writeTo(out);
                out.write("\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                return true;
            } catch (IOException e) {
                close();
                return false;
            }
        }

        /** Sends what queued during the catch-up, in order, then sends directly. */
        synchronized boolean goLive() {
            List<String[]> queued = pending;
            pending = null;
            if (queued != null) {
                for (String[] frame : queued) {
                    if (!write(frame[0], frame[1])) return false;
                }
            }
            return alive;
        }

        synchronized boolean send(String event, String data) {
            if (!alive) return false;
            if (pending != null) {
                if (pending.size() >= MAX_PENDING) {
                    close();
                    return false;
                }
                pending.add(new String[]{event, data});
                return true;
            }
            return write(event, data);
        }

        private boolean write(String event, String data) {
            if (!alive) return false;
            try {
                StringBuilder sb = new StringBuilder();
                sb.append("event: ").append(event).append('\n');
                // SSE data field: split multi-line JSON into multiple data: lines
                // For single-line JSON, one data: line suffices
                sb.append("data: ").append(data).append("\n\n");
                outputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
                return true;
            } catch (IOException e) {
                alive = false;
                close();
                return false;
            }
        }

        void close() {
            alive = false;
            try { outputStream.close(); } catch (IOException ignored) {}
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }
}
