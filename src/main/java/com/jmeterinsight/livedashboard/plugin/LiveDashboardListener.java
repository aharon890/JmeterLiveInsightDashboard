package com.jmeterinsight.livedashboard.plugin;

import com.jmeterinsight.livedashboard.engine.LiveAggregator;
import com.jmeterinsight.livedashboard.server.DashboardServer;
import org.apache.jmeter.JMeter;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.JMeterContextService;
import org.apache.jmeter.util.JMeterUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.awt.Desktop;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * JMeter listener plugin that provides real-time test monitoring
 * through a browser-based live dashboard.
 *
 * Extends ResultCollector (JMeter's standard listener base) to properly
 * integrate with JMeter's sample distribution pipeline.
 *
 * Usage (non-GUI): jmeter -n -t test.jmx  (add this listener to the test plan)
 * Usage (GUI): Add → Listener → Live Insight Dashboard
 *
 * The dashboard is served at http://localhost:7566 by default.
 */
public class LiveDashboardListener extends ResultCollector {

    private static final Logger log = LoggerFactory.getLogger(LiveDashboardListener.class);
    private static final long serialVersionUID = 1L;

    // Property keys (used in test element properties and JMeter properties)
    public static final String PORT = "live.dashboard.port";
    public static final String BIND_ADDRESS = "live.dashboard.bind";
    public static final String UPDATE_INTERVAL = "live.dashboard.update.interval";
    public static final String MAX_RETENTION = "live.dashboard.window.max";
    public static final String HIDE_PATTERNS = "live.dashboard.hide.patterns";
    public static final String SLA_P95 = "live.dashboard.sla.default.p95";
    public static final String SLA_ERROR_RATE = "live.dashboard.sla.default.errorRate";
    public static final String MAX_ERROR_SAMPLES = "live.dashboard.error.samples.max";
    public static final String OPEN_BROWSER = "live.dashboard.open.browser";
    public static final String APDEX_THRESHOLD = "live.dashboard.apdex.threshold";
    public static final String IDLE_TIMEOUT = "live.dashboard.idle.timeout";
    public static final String ENABLED = "live.dashboard.enabled";

    // JMeter's own HTML-report Apdex thresholds, set in bin/jmeter.properties and
    // overridable like any JMeter property. The dashboard's Apdex follows them, so
    // the header badge and JMeter's report read the same number.
    public static final String JMETER_APDEX_SATISFIED = "jmeter.reportgenerator.apdex_satisfied_threshold";
    public static final String JMETER_APDEX_TOLERATED = "jmeter.reportgenerator.apdex_tolerated_threshold";

    // Defaults
    public static final int DEFAULT_PORT = 7566;
    public static final String DEFAULT_BIND = "localhost";
    public static final int DEFAULT_UPDATE_INTERVAL_SECONDS = 2;
    public static final int DEFAULT_UPDATE_INTERVAL_MS = DEFAULT_UPDATE_INTERVAL_SECONDS * 1000;
    public static final int DEFAULT_MAX_RETENTION_SECONDS = 3600;
    // The GUI's own floor (1 min); see getMaxRetentionConfig.
    public static final int MIN_MAX_RETENTION_SECONDS = 60;
    // An update interval this large is the old millisecond unit; see getUpdateIntervalConfig.
    static final int LEGACY_MS_INTERVAL_FROM = 1000;
    public static final String DEFAULT_HIDE_PATTERNS_STR = "JSR223*,Debug*,BeanShell*";
    public static final int DEFAULT_MAX_ERROR_SAMPLES = 100;
    public static final double DEFAULT_APDEX_THRESHOLD = 500;
    public static final double DEFAULT_APDEX_TOLERATED_THRESHOLD = LiveAggregator.DEFAULT_APDEX_TOLERATED_MS;
    public static final int DEFAULT_IDLE_TIMEOUT_SECONDS = 600;

    // Shared lifecycle state, guarded by LIFECYCLE_LOCK. server and aggregator
    // are static so a run with more than one listener element (or a distributed
    // run delivering testStarted/testEnded per host) coordinates through one
    // dashboard instead of each element clobbering the others' server.
    private static final Object LIFECYCLE_LOCK = new Object();
    private static volatile DashboardServer server;
    private static volatile LiveAggregator aggregator;

    // Hosts whose testStarted has not yet been matched by testEnded. A Set
    // (not a counter) so a host that restarts mid-run can't double-count, and
    // so leftover state from a run that never finished cleanly is inspectable.
    private static final Set<String> startedHosts = new HashSet<>();
    // Run identity (JMeterContextService.getTestStartTime()) captured when the
    // current run's dashboard started. A later testStarted carrying a DIFFERENT run
    // identity while startedHosts is non-empty means the previous run died
    // without delivering all its testEnded callbacks — reset instead of
    // wedging every subsequent run into the dead "joined" branch.
    private static long ownedTestStart;

    // The listener copies that feed the dashboard, each mapped to the engine
    // whose samples it delivers. The first copy to start for a host feeds that
    // host; a second listener element in the plan starts for the same host and
    // only joins, so it cannot double-count.
    //
    // A map and not a single owner because a distributed run gives every
    // remote engine its OWN copy of this element on the controller
    // (ClientJMeterEngine.configure clones the whole tree per engine), each
    // receiving only that engine's testStarted and samples. A single owner fed
    // one engine and dropped the rest — measured with two engines: the
    // dashboard counted 306 of 613 samples while looking perfectly healthy.
    //
    // Keyed by identity: AbstractTestElement.equals/hashCode compare property
    // maps, so the per-engine copies of one element are "equal" and an
    // ordinary map would collapse them back into one. Replaced wholesale under
    // LIFECYCLE_LOCK, read lock-free on the sample path.
    private static volatile Map<LiveDashboardListener, String> feeders = Collections.emptyMap();

    // Once-per-instance flag for the non-owner warning below (transient: the
    // element is serialized into the plan; the flag is runtime-only).
    private transient boolean droppedSamplesWarned;

    @Override
    public void sampleOccurred(SampleEvent event) {
        String host = feeders.get(this);
        if (host == null) {
            // A second listener element scoped inside another thread group
            // only ever sees its own subtree's samples — dropping them here
            // silently under-reports the test. Make the loss visible.
            if (!feeders.isEmpty() && !droppedSamplesWarned) {
                droppedSamplesWarned = true;
                log.warn("Live Insight Dashboard: another listener element owns the dashboard; "
                        + "samples seen only by this element are NOT counted. "
                        + "Use a single Live Insight Dashboard listener at test-plan level.");
            }
            return;
        }
        LiveAggregator agg = aggregator;
        if (agg == null) return;
        agg.addSample(event.getResult(), host);
    }

    @Override
    public void sampleStarted(SampleEvent event) {
        // Not used
    }

    @Override
    public void sampleStopped(SampleEvent event) {
        // Not used
    }

    @Override
    public void testStarted() {
        testStarted("local");
    }

    @Override
    public void testStarted(String host) {
        if (!isPluginEnabled()) {
            log.info("Live Dashboard is disabled");
            return;
        }

        synchronized (LIFECYCLE_LOCK) {
            // Stale-run recovery: hosts left over from a run whose testEnded
            // never arrived (crashed remote engine, engine abort) would wedge
            // every later run into the "joined" branch with no live dashboard. When
            // the run identity has moved on, reset and start fresh. When it is
            // unavailable (0) or unchanged, stay conservative: the Set still
            // self-heals one run later via idempotent add/remove.
            //
            // Unchanged is the distributed GUI case. The controller does set the
            // identity (ClientJMeterEngine.runTest -> JMeterContextService.startTest),
            // but only MainFrame.testEnded resets it, once every host has ended —
            // the very callbacks a crashed engine never sends. The next run then
            // joins the stale dashboard. JMeter's GUI is equally stuck at that
            // point (it still shows the test running); non-GUI runs are a fresh
            // JVM each time and cannot get here.
            long runStart = JMeterContextService.getTestStartTime();
            if (!startedHosts.isEmpty() && runStart > 0 && runStart != ownedTestStart) {
                log.warn("Live Dashboard: previous run never finished cleanly (engines still marked "
                        + "started: {}); resetting lifecycle state for the new run", startedHosts);
                startedHosts.clear();
                feeders = Collections.emptyMap();
            }

            // The first start of the run starts the dashboard; every start feeds
            // it if it is the first for its host (see feeders). A copy that is
            // already feeding keeps its original host rather than being
            // re-pointed, so its samples are never attributed to two engines.
            boolean first = startedHosts.isEmpty();
            if (startedHosts.add(host) && !feeders.containsKey(this)) {
                Map<LiveDashboardListener, String> next = new IdentityHashMap<>(feeders);
                next.put(this, host);
                feeders = Collections.unmodifiableMap(next);
            }
            if (!first) {
                log.info("Live Dashboard: engine '{}' joined ({} engines running); reusing dashboard", host, startedHosts.size());
                return;
            }
            ownedTestStart = runStart;
            startDashboard();
        }
    }

    /**
     * Warns when lib/ext holds another copy of this plugin, the usual result of
     * upgrading by dropping the new jar in beside the old one. JMeter puts every jar
     * there on one classpath, so only one copy's classes are used, and with the jars
     * sorted by name that can be the older one: the new version looks installed and
     * the old code runs. Both channels, as for a failed start: jmeter.log has it, and
     * stderr is what a terminal sees.
     */
    private static void warnAboutOtherPluginJars() {
        try {
            File own = new File(LiveDashboardListener.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            List<String> others = otherPluginJars(own);
            if (others.isEmpty()) {
                return;
            }
            // Names the jar that is running, not the one to keep: with an old jar left
            // behind, the one running is usually the old one (it sorts first).
            String msg = "Live Dashboard: more than one copy of the plugin is in " + own.getParent()
                    + ". This run uses " + own.getName() + " (version " + DashboardServer.PLUGIN_VERSION
                    + "); also there: " + String.join(", ", others)
                    + ". Remove the copies you don't want and restart JMeter.";
            log.warn(msg);
            System.err.println(msg);
        } catch (Exception | LinkageError e) {
            log.debug("Live Dashboard: could not check lib/ext for other copies", e);
        }
    }

    /** The other jmeter-live-dashboard-*.jar files beside {@code ownJar}, sorted; none when it isn't a jar. */
    static List<String> otherPluginJars(File ownJar) {
        List<String> others = new ArrayList<>();
        File[] siblings = ownJar.isFile() ? ownJar.getParentFile().listFiles() : null;
        if (siblings == null) {
            return others;
        }
        for (File f : siblings) {
            String name = f.getName();
            if (f.isFile() && name.startsWith("jmeter-live-dashboard-") && name.endsWith(".jar")
                    && !name.equals(ownJar.getName())) {
                others.add(name);
            }
        }
        Collections.sort(others);
        return others;
    }

    private void startDashboard() {
        // Stop any previous server (GUI mode: multiple runs without JMeter restart)
        if (server != null) {
            server.broadcastEvent("serverClosing", Map.of("reason", "newTestStarting"));
            server.stop();
            server = null;
        }

        int port = getPortConfig();
        String bind = getBindConfig();
        int updateInterval = getUpdateIntervalConfig();
        long maxRetention = getMaxRetentionConfig();
        int maxErrors = getMaxErrorSamplesConfig();
        double slaP95 = getDoubleConfig(SLA_P95, -1);
        double slaErrorRate = getDoubleConfig(SLA_ERROR_RATE, -1);
        double[] apdex = getApdexConfig();
        String[] hidePatterns = getHidePatternsConfig();
        int idleTimeout = getIntConfig(IDLE_TIMEOUT, DEFAULT_IDLE_TIMEOUT_SECONDS);

        String testName = resolveTestName();
        warnAboutOtherPluginJars();

        try {
            // Inside the try: anything thrown here escapes testStarted into JMeter's
            // engine thread, which dies before starting a thread group — the whole run
            // lost, silently. A bad hide pattern did exactly that once.
            aggregator = new LiveAggregator(updateInterval, maxRetention, maxErrors,
                    slaP95, slaErrorRate, apdex[0], apdex[1], hidePatterns, System::currentTimeMillis);
            server = new DashboardServer(port, bind, aggregator, testName, idleTimeout);
            server.start();

            String url = "http://" + ("0.0.0.0".equals(bind) ? "localhost" : bind) + ":" + server.getPort();

            log.info("========================================================");
            log.info("  Live Dashboard {} started: {}", DashboardServer.PLUGIN_VERSION, url);
            log.info("  Open this URL in your browser to monitor the test.");
            log.info("========================================================");

            if (shouldOpenBrowser()) {
                openBrowser(url);
            }

            // Broadcast test started event
            server.broadcastEvent("testStarted", Map.of(
                    "testName", testName,
                    "startTime", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Failed to start Live Dashboard on port {}: {}", port, e.getMessage(), e);
            // Also to stderr: JMeter's shipped log4j2.xml has NO Console appender, so the
            // line above reaches jmeter.log and the GUI panel and nothing else. Without
            // this, a run that produced no dashboard says not one word on any channel a
            // terminal or CI job is watching — and since every artifact this plugin
            // produces is downloaded from that dashboard, no dashboard means no output
            // at all, silently.
            // ASCII only: System.err encodes with the platform default charset, and a Windows
            // console at cp437/cp850 turns a dash or any other non-ASCII character into
            // mojibake — on the one channel that exists purely to be read in a console.
            System.err.println("Live Dashboard: NOT started (" + e.getMessage()
                    + ") - this run has no dashboard.");
            // Roll back all lifecycle state: without this, the run would keep
            // aggregating samples nobody can view, and testEnded would
            // "finalize" a server that never bound (logging a bogus review URL
            // and spinning up its idle/heartbeat executors for nothing).
            server = null;
            aggregator = null;
            feeders = Collections.emptyMap();
            startedHosts.clear();
        }
    }

    @Override
    public void testEnded() {
        testEnded("local");
    }

    @Override
    public void testEnded(String host) {
        synchronized (LIFECYCLE_LOCK) {
            if (startedHosts.isEmpty()) {
                return; // never started (disabled or failed startup) — nothing to finalize
            }
            // Finalize only when the last engine has finished; earlier host
            // completions must not tear down state that still-running hosts need.
            startedHosts.remove(host);
            if (!startedHosts.isEmpty()) {
                log.info("Live Dashboard: engine '{}' finished ({} engines still running)", host, startedHosts.size());
                // Its threads are gone while the others still run; left in, the
                // gauge would keep counting them until the whole run ended. The
                // LAST engine needs no call here: finalizeDashboard ends the test,
                // and LiveAggregator.testEnded() zeroes the gauge for every run,
                // local or distributed.
                if (aggregator != null) {
                    aggregator.engineFinished(host);
                }
                return;
            }
            finalizeDashboard();
        }
    }

    private void finalizeDashboard() {
        if (aggregator != null) {
            aggregator.testEnded();
        }
        if (server != null) {
            server.broadcastEvent("testEnded", Map.of(
                    "endTime", System.currentTimeMillis()
            ));
            server.onTestEnded();
            log.info("Test ended. Dashboard remains at http://localhost:{} for review.", server.getPort());
        }
        feeders = Collections.emptyMap(); // release so the next run re-assigns cleanly
    }

    // --- Configuration Helpers ---

    /**
     * The name shown in the header, the page title, Copy Summary and notifications.
     *
     * <p>It used to come only from the JMeter property {@code testPlanName}, which JMeter
     * never sets, and from a {@code TestPlan.name} property this element never has, so
     * every run read "JMeter Load Test". The plan's name lives in the GUI's tree, and
     * non-GUI JMeter knows the plan only as the script file it loaded, so: an explicit
     * {@code -JtestPlanName} first, then the GUI's Test Plan node, then the TestPlan
     * element of the script file, then that file's name.
     */
    private String resolveTestName() {
        String override = JMeterUtils.getProperty("testPlanName");
        if (override != null && !override.trim().isEmpty()) return override.trim();

        if (!JMeter.isNonGUI()) {
            String name = GuiPlanName.get();
            if (name != null) return name;
        }

        String name = scriptPlanName();
        return name != null ? name : "JMeter Load Test";
    }

    /** Separate class so non-GUI runs never load GuiPackage. */
    private static final class GuiPlanName {
        static String get() {
            try {
                GuiPackage gui = GuiPackage.getInstance();
                if (gui == null) return null;
                for (JMeterTreeNode node : gui.getTreeModel().getNodesOfType(TestPlan.class)) {
                    String name = node.getName();
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            } catch (RuntimeException | LinkageError e) {
                log.debug("Live Dashboard: could not read the Test Plan name from the GUI: {}", e.toString());
            }
            return null;
        }
    }

    /** The loaded script's TestPlan name, else the script file's name without .jmx; null if no script. */
    private static String scriptPlanName() {
        FileServer fs = FileServer.getFileServer();
        String script = fs.getScriptName();
        if (script == null || script.trim().isEmpty()) return null;
        File file = new File(script);
        if (!file.isAbsolute()) file = new File(fs.getBaseDir(), script);
        String name = readTestPlanName(file);
        if (name != null) return name;
        String base = file.getName();
        return base.toLowerCase(Locale.ROOT).endsWith(".jmx") ? base.substring(0, base.length() - 4) : base;
    }

    /**
     * The testname attribute of a .jmx file's TestPlan element. Streamed, stopping at
     * that element: it is the third element of every plan, so even a huge plan costs a
     * few hundred bytes. DTDs and external entities are off; the file is the user's own,
     * but nothing here needs either.
     */
    private static String readTestPlanName(File jmx) {
        if (!jmx.isFile()) return null;
        try (InputStream in = new BufferedInputStream(new FileInputStream(jmx))) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            try {
                for (int elements = 0; reader.hasNext() && elements < 20; ) {
                    if (reader.next() != XMLStreamConstants.START_ELEMENT) continue;
                    elements++;
                    if ("TestPlan".equals(reader.getLocalName())) {
                        String name = reader.getAttributeValue(null, "testname");
                        return name == null || name.trim().isEmpty() ? null : name.trim();
                    }
                }
            } finally {
                reader.close();
            }
        } catch (IOException | XMLStreamException | RuntimeException e) {
            log.debug("Live Dashboard: could not read the Test Plan name from {}: {}", jmx, e.toString());
        }
        return null;
    }

    private int getPortConfig() {
        String prop = getPropertyAsString(PORT, "");
        if (!prop.isEmpty()) {
            try { return Integer.parseInt(prop); } catch (NumberFormatException ignored) {}
        }
        return JMeterUtils.getPropDefault(PORT, DEFAULT_PORT);
    }

    /**
     * Bind address. Like {@link #shouldOpenBrowser}, and unlike every other getter, the
     * JMeter property wins over the saved element value.
     *
     * <p>Where the plan is the right owner of a setting, the element wins. Bind address is
     * not that: it describes the machine, not the test. And the GUI pins it — its Bind
     * Address box comes pre-filled with localhost and modifyTestElement writes it
     * unconditionally, so EVERY plan saved from the GUI carries this property (until the
     * box became editable it was a fixed dropdown that could not even be cleared). Under
     * the element-wins rule {@code -Jlive.dashboard.bind=0.0.0.0} was therefore silently
     * ineffective for exactly the plans people actually have: the dashboard stayed on
     * localhost, teammates could not reach it, and nothing said why. The Port field was
     * always a text box that could be cleared, so it keeps the normal rule.
     */
    private String getBindConfig() {
        String jmeterProp = JMeterUtils.getProperty(BIND_ADDRESS);
        if (jmeterProp != null && !jmeterProp.isEmpty()) {
            return jmeterProp;
        }
        String prop = getPropertyAsString(BIND_ADDRESS, "");
        if (!prop.isEmpty()) return prop;
        return JMeterUtils.getPropDefault(BIND_ADDRESS, DEFAULT_BIND);
    }

    /**
     * Update interval, set in seconds and returned in ms, the unit everything downstream
     * works in. Rejects anything <= 0 rather than passing it on.
     *
     * <p>An interval of 0 reaches DashboardServer.start(), which rejects it with
     * IllegalArgumentException — but only AFTER DashboardServer has bound
     * its socket, so the failure leaves a live, listening server that nothing can stop
     * and that squats the port for the life of the JVM. Both branches are guarded: a
     * {@code -J} property is read when the element value is absent, so validating only
     * the element value would leave the CLI path open.
     *
     * <p>The setting used to be in milliseconds while the GUI field showed seconds, so
     * {@code -Jlive.dashboard.update.interval=1}, meant as one second, made 1 ms slots:
     * about 1,000 chart messages a second to every tab and 1,000 retained slots a second
     * in the load generator's own heap. Now it is seconds everywhere. A value of
     * {@value #LEGACY_MS_INTERVAL_FROM} or more is still read as milliseconds: every plan
     * an older GUI saved says 2000, and no live dashboard refreshes once every 17 minutes.
     */
    private int getUpdateIntervalConfig() {
        String prop = getPropertyAsString(UPDATE_INTERVAL, "");
        if (!prop.isEmpty()) {
            try {
                int value = Integer.parseInt(prop);
                if (value > 0) return intervalToMs(value, "the test element");
                // Do NOT name the default here: this falls through to the -J value, which
                // may be perfectly valid, and claiming "using 2s" while then returning
                // 5s would be a lie in the one message an operator has to go on.
                log.warn("Ignoring invalid {} '{}' from the test element (must be > 0)",
                        UPDATE_INTERVAL, prop);
            } catch (NumberFormatException ignored) {}
        }
        int fromProperty = JMeterUtils.getPropDefault(UPDATE_INTERVAL, DEFAULT_UPDATE_INTERVAL_SECONDS);
        if (fromProperty <= 0) {
            log.warn("Ignoring invalid -J{}={} (must be > 0) — using {}s",
                    UPDATE_INTERVAL, fromProperty, DEFAULT_UPDATE_INTERVAL_SECONDS);
            return DEFAULT_UPDATE_INTERVAL_MS;
        }
        return intervalToMs(fromProperty, "-J");
    }

    private static int intervalToMs(int value, String source) {
        if (value < LEGACY_MS_INTERVAL_FROM) return value * 1000;
        log.warn("{}={} from {} is read as milliseconds, its old unit. The setting is now in "
                        + "seconds: use {} (or open and save the listener in the GUI).",
                UPDATE_INTERVAL, value, source, Math.max(1, Math.round(value / 1000f)));
        return value;
    }

    /**
     * Retention of the live slots in ms. Rejects anything <= 0 the way the interval does,
     * and raises anything below a minute to a minute, the GUI's floor.
     *
     * <p>Closed live slots older than the retention are pruned, so 0 or below pruned each
     * slot the moment it closed: a reload, a new tab or the review after the run showed an
     * empty timeline, and nothing said why. 0 does not mean "unlimited" either — keeping
     * every live slot of a long run is what this window exists to prevent, and the minute
     * tier already covers the whole run.
     */
    private long getMaxRetentionConfig() {
        // Compute in long: seconds * 1000 overflows int for retention >~24 days,
        // which would push the prune cutoff into the future and wipe all history.
        String prop = getPropertyAsString(MAX_RETENTION, "");
        if (!prop.isEmpty()) {
            try {
                int value = Integer.parseInt(prop);
                if (value > 0) return retentionAtLeastOneMinute(value, "the test element") * 1000L;
                // Falls through to the -J value, so the default is not named here.
                log.warn("Ignoring invalid {} '{}' from the test element (must be > 0)",
                        MAX_RETENTION, prop);
            } catch (NumberFormatException ignored) {}
        }
        int fromProperty = JMeterUtils.getPropDefault(MAX_RETENTION, DEFAULT_MAX_RETENTION_SECONDS);
        if (fromProperty <= 0) {
            log.warn("Ignoring invalid -J{}={} (must be > 0) — using {}s",
                    MAX_RETENTION, fromProperty, DEFAULT_MAX_RETENTION_SECONDS);
            return DEFAULT_MAX_RETENTION_SECONDS * 1000L;
        }
        return retentionAtLeastOneMinute(fromProperty, "-J") * 1000L;
    }

    private static int retentionAtLeastOneMinute(int seconds, String source) {
        if (seconds >= MIN_MAX_RETENTION_SECONDS) return seconds;
        log.warn("{}={} from {} is below the minimum (the value is in seconds) — using {}s",
                MAX_RETENTION, seconds, source, MIN_MAX_RETENTION_SECONDS);
        return MIN_MAX_RETENTION_SECONDS;
    }

    private int getMaxErrorSamplesConfig() {
        return JMeterUtils.getPropDefault(MAX_ERROR_SAMPLES, DEFAULT_MAX_ERROR_SAMPLES);
    }

    private double getDoubleConfig(String key, double defaultVal) {
        String prop = getPropertyAsString(key, "");
        if (!prop.isEmpty()) {
            try { return Double.parseDouble(prop); } catch (NumberFormatException ignored) {}
        }
        String jmProp = JMeterUtils.getProperty(key);
        if (jmProp != null && !jmProp.isEmpty()) {
            try { return Double.parseDouble(jmProp); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    /**
     * Apdex thresholds {T, F}. Both default to JMeter's HTML-report thresholds, so the
     * dashboard and the report agree out of the box and a user who tuned the report's
     * thresholds gets the same ones here. {@code live.dashboard.apdex.threshold} still
     * overrides T for the dashboard.
     */
    private double[] getApdexConfig() {
        double satisfied = getJMeterDouble(JMETER_APDEX_SATISFIED, DEFAULT_APDEX_THRESHOLD);
        return new double[]{
                getDoubleConfig(APDEX_THRESHOLD, satisfied),
                getJMeterDouble(JMETER_APDEX_TOLERATED, DEFAULT_APDEX_TOLERATED_THRESHOLD)
        };
    }

    private static double getJMeterDouble(String key, double defaultVal) {
        String value = JMeterUtils.getProperty(key);
        if (value != null && !value.trim().isEmpty()) {
            try { return Double.parseDouble(value.trim()); } catch (NumberFormatException ignored) {}
        }
        return defaultVal;
    }

    private int getIntConfig(String key, int defaultVal) {
        String prop = getPropertyAsString(key, "");
        if (!prop.isEmpty()) {
            try { return Integer.parseInt(prop); } catch (NumberFormatException ignored) {}
        }
        return JMeterUtils.getPropDefault(key, defaultVal);
    }

    private String[] getHidePatternsConfig() {
        String prop = getPropertyAsString(HIDE_PATTERNS, "");
        if (prop.isEmpty()) {
            prop = JMeterUtils.getPropDefault(HIDE_PATTERNS, DEFAULT_HIDE_PATTERNS_STR);
        }
        return prop.split(",");
    }

    private boolean shouldOpenBrowser() {
        // Auto-open is an ENVIRONMENT concern, so unlike the other getters the
        // JMeter property (-J flag / jmeter.properties) wins over the saved
        // element value: the GUI writes the checkbox into every saved plan, and
        // a headless CI box must be able to override it with
        // -Jlive.dashboard.open.browser=false (STP-07, Tutorial 10).
        String jmeterProp = JMeterUtils.getProperty(OPEN_BROWSER);
        if (jmeterProp != null && !jmeterProp.isEmpty()) {
            return Boolean.parseBoolean(jmeterProp);
        }
        String prop = getPropertyAsString(OPEN_BROWSER, "");
        if (!prop.isEmpty()) {
            return Boolean.parseBoolean(prop);
        }
        return true;
    }

    private boolean isPluginEnabled() {
        return JMeterUtils.getPropDefault(ENABLED, true);
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                String os = System.getProperty("os.name", "").toLowerCase();
                ProcessBuilder pb;
                if (os.contains("win")) {
                    pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
                } else if (os.contains("mac")) {
                    pb = new ProcessBuilder("open", url);
                } else {
                    pb = new ProcessBuilder("xdg-open", url);
                }
                pb.start();
            }
        } catch (Exception e) {
            log.debug("Could not auto-open browser: {}", e.getMessage());
        }
    }
}
