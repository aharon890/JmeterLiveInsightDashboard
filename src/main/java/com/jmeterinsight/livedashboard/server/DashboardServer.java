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
    // cannot: it cannot read the page (no response carries a CORS header), and a
    // custom header forces a CORS preflight, which is never granted. A page that
    // re-points its own host name at this machine is same-origin to the browser
    // and reads the token like the dashboard does; the Host check stops that one.
    private final String stopToken = UUID.randomUUID().toString();

    // Identifies THIS server instance on the catchup payload, so a tab whose EventSource
    // reconnected can tell it has been handed a different server — a run that restarted,
    // or another JMeter process that took this port after the first one died. Deliberately
    // NOT the stop token: the catch-up is data the page shows, and reaches every tab, proxy
    // and script that reads the stream. The token authorises stopping the run and travels
    // only inside the page itself.
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

    // True when the socket is bound to a loopback address: then only a loopback Host name is
    // answered (see hostChecked). Set once the socket is bound, before any request is served.
    private volatile boolean loopbackBind = false;
    private final java.util.concurrent.atomic.AtomicBoolean foreignHostLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    // The token of the access link; null when none is asked for (see accessChecked).
    private final String accessToken;
    private final java.util.concurrent.atomic.AtomicBoolean noAccessLinkLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    public DashboardServer(int port, String bind, LiveAggregator aggregator, String testName) {
        this(port, bind, aggregator, testName, 600);
    }

    public DashboardServer(int port, String bind, LiveAggregator aggregator, String testName, int idleTimeoutSeconds) {
        this(port, bind, aggregator, testName, idleTimeoutSeconds, null);
    }

    /**
     * @param accessToken the token of the dashboard's access link (see accessChecked), or null for none.
     *                    A token that is set is asked for on every bind, localhost included.
     */
    public DashboardServer(int port, String bind, LiveAggregator aggregator, String testName, int idleTimeoutSeconds,
                           String accessToken) {
        this.port = port;
        this.bind = bind;
        this.aggregator = aggregator;
        this.testName = testName;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
        this.accessToken = accessToken == null || accessToken.isEmpty() ? null : accessToken;
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
            loopbackBind = httpServer.getAddress().getAddress().isLoopbackAddress();
            httpServer.createContext("/", hostChecked(accessChecked(this::handleDashboard)));
            httpServer.createContext("/api/events", hostChecked(accessChecked(this::handleSseEvents)));
            httpServer.createContext("/api/status", hostChecked(accessChecked(this::handleStatus)));
            httpServer.createContext("/api/config", hostChecked(accessChecked(this::handleConfig)));
            httpServer.createContext("/api/history", hostChecked(accessChecked(this::handleHistory)));
            httpServer.createContext("/api/stop", hostChecked(accessChecked(this::handleStop)));
            httpServer.createContext("/api/stop-now", hostChecked(accessChecked(this::handleStopNow)));

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
            client.drop();
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

    // --- Access link (live.dashboard.access.token) ---

    static final String ACCESS_LINK_NEEDED = "This dashboard needs its access link.\n\n"
            + "It was started with an access token, so it answers only to someone who has the link from the "
            + "\"Live Dashboard ... started:\" line of jmeter.log (the link ends in ?token=...). "
            + "Open that link once and this browser remembers it. "
            + "A script sends the header \"Authorization: token <the token>\".\n";

    /**
     * Wraps a handler so that a dashboard started with a token (live.dashboard.access.token)
     * answers only to someone who holds its access link.
     *
     * <p>Bound to 0.0.0.0 or to a named interface, anyone who could reach the port could read the
     * run (sampler names, often internal URLs, and error messages) and stop JMeter, which then
     * exits with code 0. This is the way Jupyter guards its server: the start line in jmeter.log
     * prints a link that ends in ?token=...; opening it once stores the token in a cookie, and from
     * then on the plain address works in that browser. A script sends "Authorization: token
     * &lt;token&gt;" instead. The live stream is an EventSource, which cannot send a header, so for
     * a browser the cookie is the only carrier.
     *
     * <p>A token that is set is asked for on every bind, localhost included, as Jupyter asks on
     * localhost. A dashboard on localhost is still reached from other machines when something
     * forwards to it (a reverse proxy on the same machine, an SSH tunnel, a port-forward), and
     * such a client writes its own Host header, so the Host check does not keep it out. Asking
     * only beyond localhost left exactly that setup open while the operator believed a token was
     * in force (found in review, 2026-11-22). With no token set nothing is asked for, on any
     * bind. The token is not encryption: it keeps out whoever can reach the port, not whoever
     * can read the network.
     */
    private com.sun.net.httpserver.HttpHandler accessChecked(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            if (!isAccessLinkRequired()) {
                handler.handle(exchange);
                return;
            }
            java.net.URI uri = exchange.getRequestURI();
            String cookieName = accessCookieName(exchange.getRequestHeaders().getFirst("Host"));
            if (tokenMatches(queryParameter(uri.getRawQuery(), "token"))) {
                exchange.getResponseHeaders().add("Set-Cookie",
                        cookieName + "=" + accessToken + "; Path=/; HttpOnly; SameSite=Lax");
                String path = uri.getPath();
                if ("GET".equals(exchange.getRequestMethod()) && ("/".equals(path) || "/index.html".equals(path))) {
                    // The link itself: show the page at its plain address, so the token is not left
                    // in the address bar, the history, or a link someone copies from there. Only the
                    // page redirects (to one of two fixed paths, never to a path taken from the
                    // request): an API call is answered where it is, because curl follows no redirect.
                    exchange.getResponseHeaders().set("Location", withoutParameter(uri, "token"));
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                handler.handle(exchange);
                return;
            }
            if (tokenMatches(authorizationToken(exchange.getRequestHeaders().getFirst("Authorization")))
                    || tokenMatches(cookieValue(exchange.getRequestHeaders().get("Cookie"), cookieName))) {
                handler.handle(exchange);
                return;
            }
            // Once at WARN: a scanner on the network can repeat the request as fast as it likes.
            if (noAccessLinkLogged.compareAndSet(false, true)) {
                log.warn("Refused a request from {} that did not carry the dashboard's access link. The link is on "
                        + "the \"Live Dashboard ... started:\" line of jmeter.log. Further refusals are logged at DEBUG.",
                        exchange.getRemoteAddress());
            } else {
                log.debug("Refused a request from {} without the access link", exchange.getRemoteAddress());
            }
            exchange.getResponseHeaders().set("WWW-Authenticate", "Token");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            sendResponse(exchange, 401, "text/plain; charset=utf-8", ACCESS_LINK_NEEDED);
        };
    }

    /** Compared in constant time, so the answer's timing does not tell how much of a guess was right. */
    private boolean tokenMatches(String candidate) {
        return candidate != null && java.security.MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8), accessToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The cookie the access link sets for a request to the given Host. Its name carries a port: a
     * browser keeps cookies per host, not per port, so two dashboards on one machine would
     * overwrite each other's under one name.
     *
     * <p>It is the port in the browser's address, not the port this server listens on. Two JMeter
     * containers both listen on 7566 and are published as 7566 and 7567, and two tunnels end at
     * two local ports: named after the server's own port, the second link would replace the first
     * one's cookie and the first tab would get 401 on its next request. An address with no port
     * in it (a proxy on port 80) gets the server's own port.
     */
    String accessCookieName(String hostHeader) {
        String requested = portOfHost(hostHeader);
        return "ld_access_" + (requested != null ? requested : String.valueOf(port));
    }

    /** The cookie's name for an address that names this server's own port. */
    String accessCookieName() {
        return accessCookieName(null);
    }

    /**
     * The port a Host header names, as written: 1 to 5 digits after the host. Null when it names
     * none. Digits only, because the value goes into a cookie's name.
     */
    static String portOfHost(String hostHeader) {
        if (hostHeader == null) return null;
        int colon = hostHeader.lastIndexOf(':');
        if (colon < 0 || colon < hostHeader.lastIndexOf(']')) return null;
        String candidate = hostHeader.substring(colon + 1);
        return !candidate.isEmpty() && isPort(candidate) ? candidate : null;
    }

    /** One parameter's decoded value from a raw query string; null when it is not there or is not readable. */
    static String queryParameter(String rawQuery, String name) {
        if (rawQuery == null) return null;
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (!name.equals(eq < 0 ? pair : pair.substring(0, eq))) continue;
            try {
                return java.net.URLDecoder.decode(eq < 0 ? "" : pair.substring(eq + 1), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException brokenEscape) {
                return null;
            }
        }
        return null;
    }

    /** The request's path and query with one parameter taken out: where the access link redirects to. */
    static String withoutParameter(java.net.URI uri, String name) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        StringBuilder rest = new StringBuilder();
        if (uri.getRawQuery() != null) {
            for (String pair : uri.getRawQuery().split("&")) {
                int eq = pair.indexOf('=');
                if (pair.isEmpty() || name.equals(eq < 0 ? pair : pair.substring(0, eq))) continue;
                rest.append(rest.length() == 0 ? '?' : '&').append(pair);
            }
        }
        return path + rest;
    }

    /** "token abc" gives "abc", the word in any case, as Jupyter reads the header. Anything else gives null. */
    static String authorizationToken(String header) {
        if (header == null) return null;
        String value = header.trim();
        return value.regionMatches(true, 0, "token ", 0, 6) ? value.substring(6).trim() : null;
    }

    /** A cookie's value from the request's Cookie headers; null when it is not there. */
    static String cookieValue(java.util.List<String> cookieHeaders, String name) {
        if (cookieHeaders == null) return null;
        for (String header : cookieHeaders) {
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq > 0 && name.equals(pair.substring(0, eq).trim())) return pair.substring(eq + 1).trim();
            }
        }
        return null;
    }

    // --- Host check (DNS rebinding) ---

    /**
     * Wraps a handler so that a dashboard bound to loopback answers only to a loopback name.
     *
     * <p>The attack it stops is DNS rebinding: a page on attacker.example re-points its own
     * name at 127.0.0.1, and the browser then treats this server as that page's own site. The
     * page can read the dashboard, stop token included, and stop the run with it; the token
     * cannot help, because a same-origin page reads it the way the dashboard does. Measured
     * 2026-10-11: {@code POST /api/stop-now} with such a Host and the token answered 200 and
     * JMeter exited a second later with code 0. The request still names the attacker's host,
     * which is the one thing the page cannot change. webpack-dev-server and Vite defend the
     * same way.
     */
    private com.sun.net.httpserver.HttpHandler hostChecked(com.sun.net.httpserver.HttpHandler handler) {
        return exchange -> {
            String host = exchange.getRequestHeaders().getFirst("Host");
            if (!isHostAllowed(loopbackBind, host)) {
                // Once at WARN: a hostile page can repeat the request as fast as it likes.
                if (foreignHostLogged.compareAndSet(false, true)) {
                    log.warn("Refused a request for host '{}' from {}: the dashboard is bound to {} and answers "
                            + "only to localhost, 127.x.x.x or [::1]. To reach it by another name, bind it to "
                            + "that interface or to 0.0.0.0 (live.dashboard.bind). Further refusals are logged at DEBUG.",
                            host, exchange.getRemoteAddress(), bind);
                } else {
                    log.debug("Refused a request for host '{}' from {}", host, exchange.getRemoteAddress());
                }
                sendResponse(exchange, 403, "text/plain",
                        "Forbidden: this dashboard is bound to localhost and answers only to localhost, "
                        + "127.0.0.1 or [::1]. To reach it by another name, start JMeter with "
                        + "-Jlive.dashboard.bind=0.0.0.0");
                return;
            }
            handler.handle(exchange);
        };
    }

    /**
     * Whether a request's Host header names a host this server answers to.
     *
     * <p>Only a loopback bind is checked. There, {@code localhost}, any {@code 127.x.x.x} and
     * {@code [::1]} pass on any port, so an SSH tunnel or a port-forward to another local
     * port keeps working. Bound to {@code 0.0.0.0} or to a named interface the dashboard is
     * reached by whatever name the network gives the machine ({@code http://loadgen01:7566},
     * a Docker or Kubernetes service name, a proxy's upstream), so nothing is refused: anyone
     * who can reach that port can read the run and stop it, which the docs say.
     *
     * <p>A request with no Host header passes: every browser sends one, so it is no rebinding.
     * (Newer JDKs, seen on 21.0.13, answer 400 to an HTTP/1.1 request with no Host header
     * themselves, before any handler runs.) The name is never resolved; resolving it is the very
     * thing the attacker controls.
     */
    static boolean isHostAllowed(boolean loopbackBind, String hostHeader) {
        if (!loopbackBind || hostHeader == null) return true;
        String name;
        String port;
        if (hostHeader.startsWith("[")) {
            int close = hostHeader.indexOf(']');
            if (close < 0) return false;
            name = hostHeader.substring(1, close);
            String rest = hostHeader.substring(close + 1);
            if (!rest.isEmpty() && rest.charAt(0) != ':') return false;
            port = rest.isEmpty() ? null : rest.substring(1);
            return isPort(port) && isLoopbackIpv6Literal(name);
        }
        int colon = hostHeader.indexOf(':');
        name = colon < 0 ? hostHeader : hostHeader.substring(0, colon);
        port = colon < 0 ? null : hostHeader.substring(colon + 1);
        if (!isPort(port)) return false;
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1); // the rooted form, localhost.
        return "localhost".equalsIgnoreCase(name) || isLoopbackIpv4Literal(name);
    }

    /** No port, or 1 to 5 digits. */
    private static boolean isPort(String port) {
        if (port == null) return true;
        if (port.isEmpty() || port.length() > 5) return false;
        for (int i = 0; i < port.length(); i++) {
            if (port.charAt(i) < '0' || port.charAt(i) > '9') return false;
        }
        return true;
    }

    /** Four decimal parts of 0 to 255, the first 127. */
    private static boolean isLoopbackIpv4Literal(String name) {
        String[] parts = name.split("[.]", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) return false;
            for (int i = 0; i < part.length(); i++) {
                if (part.charAt(i) < '0' || part.charAt(i) > '9') return false;
            }
            if (Integer.parseInt(part) > 255) return false;
        }
        return Integer.parseInt(parts[0]) == 127;
    }

    /** An IPv6 literal, as written between the brackets, that is the loopback address. */
    private static boolean isLoopbackIpv6Literal(String name) {
        if (name.indexOf(':') < 0) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean literalChar = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
                    || c == ':' || c == '.';
            if (!literalChar) return false; // a name, or a zone id: never handed to a resolver
        }
        try {
            return java.net.InetAddress.getByName(name).isLoopbackAddress();
        } catch (java.net.UnknownHostException | RuntimeException notAnAddress) {
            return false;
        }
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
        // From here this thread is the client's writer: it writes what queued behind the
        // catch-up, then every frame as it arrives, and comes back when the reader is gone.
        try {
            if (sent) client.writeQueuedFrames();
        } finally {
            client.finish();
            if (sseClients.remove(client) && sseClients.isEmpty()) onClientDisconnected();
            log.debug("SSE client gone. Total clients: {}", sseClients.size());
        }
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
            // removeAll is false when each client's own writer already took it off the list,
            // and then that writer has made the empty-list call too.
            boolean removed = sseClients.removeAll(deadClients);
            log.debug("Removed {} disconnected SSE clients. Active: {}", deadClients.size(), sseClients.size());
            if (removed && sseClients.isEmpty()) onClientDisconnected();
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
        // No response carries Access-Control-Allow-Origin. The dashboard is same-origin and
        // never needed it, and with the wildcard the JSON responses once had, any page open in
        // the operator's browser could read the test name, the sampler names (often internal
        // URLs) and the error messages. curl and scripts are unaffected: only a browser
        // enforces CORS. Pinned by testNoResponseIsReadableCrossOrigin.
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
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

    /** True when the socket is bound to a loopback address. Meaningful once start() has returned. */
    public boolean isLoopbackBind() { return loopbackBind; }

    /** True when every request must carry the access link's token: one is set, whatever the bind. */
    public boolean isAccessLinkRequired() { return accessToken != null; }

    // --- SSE Client ---

    private static class SseClient {
        // A reader this far behind is dropped, not buffered on. By frames: over half an hour
        // at the 2 s default, which the catch-up of a long run on a slow link may need. By
        // size: at 1,000 samplers one frame is ~200 KB of JSON, and a thousand of those is
        // the heap.
        private static final int MAX_QUEUED_FRAMES = 1000;
        private static final long MAX_QUEUED_CHARS = 32L * 1024 * 1024;

        private final OutputStream outputStream;
        private final HttpExchange exchange;
        private volatile boolean alive = true;

        // Frames waiting for this client's writer, oldest first. A sender only ever adds here.
        // The stream is written by one thread, the client's own handler thread: the catch-up
        // first, then these. So a reader that stops reading (a tab frozen in a debugger, a
        // paused VM, a hung proxy) blocks that thread and no other. Every frame used to be
        // written by whoever sent it, which is the broadcaster, for each client in turn: once
        // a stalled reader's socket buffers were full that write never returned, no tab got
        // another update, a new tab waited for the lock the broadcaster held, and the slots
        // it should have closed stayed open in the aggregator.
        private final java.util.ArrayDeque<String[]> queue = new java.util.ArrayDeque<>();
        private long queuedChars = 0;

        // The handler thread while it serves this client, so drop() can interrupt it.
        // Guarded by queue. The client is built on that thread.
        private Thread writer = Thread.currentThread();

        SseClient(OutputStream outputStream, HttpExchange exchange) {
            this.outputStream = outputStream;
            this.exchange = exchange;
        }

        interface Body {
            void writeTo(OutputStream out) throws IOException;
        }

        /** Writes the catch-up frame directly; called once, before {@link #writeQueuedFrames}. */
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
                alive = false;
                return false;
            }
        }

        /** Queues a frame for the writer. Never touches the socket, so it never waits for a reader. */
        boolean send(String event, String data) {
            synchronized (queue) {
                if (!alive) return false;
                // One frame alone always fits: the size limit is on what is waiting behind others.
                if (queue.size() >= MAX_QUEUED_FRAMES
                        || (!queue.isEmpty() && queuedChars + data.length() > MAX_QUEUED_CHARS)) {
                    dropLocked();
                    return false;
                }
                queue.add(new String[]{event, data});
                queuedChars += data.length();
                queue.notifyAll();
                return true;
            }
        }

        /**
         * Writes every queued frame, in order, as it arrives. Runs on the client's handler
         * thread and returns when the reader is gone or was dropped.
         */
        void writeQueuedFrames() {
            try {
                while (true) {
                    String[] frame;
                    synchronized (queue) {
                        while (alive && queue.isEmpty()) queue.wait();
                        if (!alive) return;
                        frame = queue.poll();
                        queuedChars -= frame[1].length();
                    }
                    write(frame[0], frame[1]);
                }
            } catch (IOException | InterruptedException readerGone) {
                // The reader left, or drop() interrupted this thread.
            }
        }

        private void write(String event, String data) throws IOException {
            StringBuilder sb = new StringBuilder(data.length() + event.length() + 16);
            sb.append("event: ").append(event).append('\n');
            // SSE data field: split multi-line JSON into multiple data: lines
            // For single-line JSON, one data: line suffices
            sb.append("data: ").append(data).append("\n\n");
            outputStream.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            outputStream.flush();
        }

        /**
         * Ends the client, from any thread. It does not touch the stream: closing a stream
         * waits for the write in progress, and a stalled reader's never ends. Interrupting
         * the writer closes the socket under that write instead.
         */
        void drop() {
            synchronized (queue) {
                dropLocked();
            }
        }

        private void dropLocked() {
            alive = false;
            queue.clear();
            queuedChars = 0;
            queue.notifyAll();
            if (writer != null) writer.interrupt();
        }

        /** The writer's last act, on its own thread: give the thread back clean and close the exchange. */
        void finish() {
            synchronized (queue) {
                alive = false;
                queue.clear();
                queuedChars = 0;
                writer = null; // after this line no drop() can interrupt this thread
            }
            Thread.interrupted(); // and one that already did must not follow it back to the pool
            try { outputStream.close(); } catch (IOException ignored) {}
            try { exchange.close(); } catch (Exception ignored) {}
        }
    }
}
