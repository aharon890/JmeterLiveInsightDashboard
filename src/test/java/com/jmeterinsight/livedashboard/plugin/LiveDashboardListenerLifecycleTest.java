package com.jmeterinsight.livedashboard.plugin;

import com.jmeterinsight.livedashboard.engine.LiveAggregator;
import com.jmeterinsight.livedashboard.server.DashboardServer;
import org.apache.jmeter.samplers.SampleEvent;
import org.apache.jmeter.samplers.SampleResult;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lifecycle behaviour of the listener that needs the real static start/stop path.
 *
 * <p>Deliberately its own class: {@link #startupFailureIsVisibleOnStderr} swaps the
 * global {@code System.err}, so it must not interleave with another test's output.
 */
class LiveDashboardListenerLifecycleTest {

    private static void loadProperties() throws Exception {
        File props = File.createTempFile("jmeter-lifecycle-test", ".properties");
        props.deleteOnExit();
        JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        // Never let a unit test launch a browser.
        JMeterUtils.setProperty(LiveDashboardListener.OPEN_BROWSER, "false");
    }

    @SuppressWarnings("unchecked")
    private static void clearLifecycleState() throws Exception {
        Field hosts = LiveDashboardListener.class.getDeclaredField("startedHosts");
        hosts.setAccessible(true);
        ((Set<String>) hosts.get(null)).clear();
        Field feeders = LiveDashboardListener.class.getDeclaredField("feeders");
        feeders.setAccessible(true);
        feeders.set(null, Collections.emptyMap());
        Field server = LiveDashboardListener.class.getDeclaredField("server");
        server.setAccessible(true);
        DashboardServer running = (DashboardServer) server.get(null);
        if (running != null) {
            running.stop();
        }
        server.set(null, null);
    }

    private static LiveAggregator currentAggregator() throws Exception {
        Field agg = LiveDashboardListener.class.getDeclaredField("aggregator");
        agg.setAccessible(true);
        return (LiveAggregator) agg.get(null);
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static SampleEvent sampleWithThreads(int allThreads) {
        SampleResult r = new SampleResult();
        r.setSampleLabel("GET /api/test");
        r.setSuccessful(true);
        r.setAllThreads(allThreads);
        return new SampleEvent(r, "Thread Group");
    }

    /**
     * Starts a real dashboard (on a free port, never a browser) and leaves the
     * lifecycle clean afterwards, whatever the test did.
     */
    private static void withDashboard(ThrowingRunnable body) throws Exception {
        loadProperties();
        JMeterUtils.setProperty(LiveDashboardListener.PORT, String.valueOf(freePort()));
        JMeterUtils.setProperty(LiveDashboardListener.BIND_ADDRESS, "localhost");
        try {
            clearLifecycleState();
            body.run();
        } finally {
            clearLifecycleState();
            JMeterUtils.getJMeterProperties().remove(LiveDashboardListener.PORT);
            JMeterUtils.getJMeterProperties().remove(LiveDashboardListener.BIND_ADDRESS);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /**
     * A distributed run gives each remote engine its own copy of the listener on
     * the controller, and each copy receives only its own engine's samples. Every
     * engine's copy must feed the dashboard. Measured before the fix, two engines:
     * the first copy owned the dashboard, the second dropped every sample (306 of
     * 613 counted) and the thread gauge showed one engine's 10 instead of 20.
     *
     * <p>Two fresh listeners stand in for the per-engine clones faithfully: they
     * carry identical properties, so — like real clones — they are equals() to
     * each other. That is what the thread assertion pins: a map keyed by equality
     * would merge the two copies and attribute both engines to one.
     */
    @Test
    void distributedRunCountsEveryEnginesSamples() throws Exception {
        withDashboard(() -> {
            LiveDashboardListener engine1Copy = new LiveDashboardListener();
            LiveDashboardListener engine2Copy = new LiveDashboardListener();
            assertEquals(engine1Copy, engine2Copy,
                    "precondition: per-engine copies are equals() like real JMeter clones");

            engine1Copy.testStarted("engine-1");
            engine2Copy.testStarted("engine-2");
            engine1Copy.sampleOccurred(sampleWithThreads(10));
            engine2Copy.sampleOccurred(sampleWithThreads(10));

            Map<String, Object> status = currentAggregator().getStatus("Test");
            assertEquals(2L, status.get("totalSamples"), "one sample from each engine");
            assertEquals(20, status.get("activeThreads"), "10 threads on each of two engines");

            // engine-1 finishes first: the dashboard stays up for engine-2, and
            // engine-1's threads stop counting.
            engine1Copy.testEnded("engine-1");
            assertNotNull(currentAggregator(), "the dashboard must outlive the first engine");
            engine2Copy.sampleOccurred(sampleWithThreads(10));
            status = currentAggregator().getStatus("Test");
            assertEquals(3L, status.get("totalSamples"));
            assertEquals(10, status.get("activeThreads"), "only engine-2 is still running");
            engine2Copy.testEnded("engine-2");
        });
    }

    /**
     * The guard the single owner existed for must survive the move to per-engine
     * feeders. Also pins the identity keying from the other side: the second
     * element is equals() to the first, so a map keyed by equality finds it as a
     * feeder and counts every sample twice (verified by mutation).
     */
    @Test
    void secondListenerElementOnTheSameEngineDoesNotDoubleCount() throws Exception {
        withDashboard(() -> {
            LiveDashboardListener first = new LiveDashboardListener();
            LiveDashboardListener second = new LiveDashboardListener();
            first.testStarted();
            second.testStarted();

            SampleEvent sample = sampleWithThreads(5);
            first.sampleOccurred(sample);
            second.sampleOccurred(sample);

            assertEquals(1L, currentAggregator().getStatus("Test").get("totalSamples"),
                    "a sample both elements see is counted once");
            first.testEnded();
            second.testEnded();
        });
    }

    /**
     * A hide pattern only decides which pills start unchecked; it must never cost the
     * run. '[' and '${' — a typo, or JMeter's own variable syntax — used to throw
     * PatternSyntaxException out of testStarted into JMeter's engine thread, which then
     * died before starting a single thread group: no samples, no dashboard, exit 0.
     */
    @Test
    void hidePatternWithRegexMetacharactersStillStartsTheRun() throws Exception {
        withDashboard(() -> {
            JMeterUtils.setProperty(LiveDashboardListener.HIDE_PATTERNS, "GET /x[,${user}*");
            try {
                LiveDashboardListener listener = new LiveDashboardListener();
                assertDoesNotThrow(() -> listener.testStarted("local"));

                LiveAggregator agg = currentAggregator();
                assertNotNull(agg, "the dashboard must start");
                assertTrue(agg.shouldHide("GET /x["));
                assertTrue(agg.shouldHide("${user} login"));
                listener.testEnded("local");
            } finally {
                JMeterUtils.getJMeterProperties().remove(LiveDashboardListener.HIDE_PATTERNS);
            }
        });
    }

    /**
     * A run that gets no dashboard must say so on a channel the operator can see.
     *
     * <p>JMeter's shipped log4j2.xml has no Console appender, so the existing
     * {@code log.error} reaches {@code jmeter.log} and nothing else — a non-GUI run
     * prints not one word about the dashboard. Measured: stdout and stderr were both
     * empty for a run whose dashboard failed to start.
     */
    @Test
    void startupFailureIsVisibleOnStderr() throws Exception {
        loadProperties();
        // 192.0.2.0/24 is RFC 5737 TEST-NET-1 — reserved for documentation, so it is
        // never a real interface address and the bind is guaranteed to fail.
        JMeterUtils.setProperty(LiveDashboardListener.BIND_ADDRESS, "192.0.2.1");

        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try {
            clearLifecycleState();
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8.name()));

            new LiveDashboardListener().testStarted("local");

            System.setErr(originalErr);
            String err = captured.toString(StandardCharsets.UTF_8.name());
            assertFalse(err.trim().isEmpty(),
                    "a dashboard that failed to start must report it on stderr; nothing was printed");
            assertTrue(err.contains("Live Dashboard") || err.contains("LIVE-DASHBOARD"),
                    "the stderr line must identify the plugin, got: " + err);
        } finally {
            System.setErr(originalErr);
            JMeterUtils.getJMeterProperties().remove(LiveDashboardListener.BIND_ADDRESS);
            clearLifecycleState();
        }
    }

    /**
     * Upgrading by dropping the new jar in beside the old leaves both in lib/ext, and
     * JMeter may then run the old one. The start warns about the others, by name.
     */
    @Test
    void otherCopiesOfThePluginBesideItsJarAreFound(@TempDir Path libExt) throws Exception {
        for (String name : new String[] {"jmeter-live-dashboard-1.5.1.jar", "jmeter-live-dashboard-1.4.0.jar",
                "jmeter-live-dashboard-1.5.0-SNAPSHOT.jar", "ApacheJMeter_core.jar", "jmeter-live-dashboard-notes.txt"}) {
            Files.createFile(libExt.resolve(name));
        }
        Files.createDirectory(libExt.resolve("jmeter-live-dashboard-old.jar"));

        assertEquals(List.of("jmeter-live-dashboard-1.4.0.jar", "jmeter-live-dashboard-1.5.0-SNAPSHOT.jar"),
                LiveDashboardListener.otherPluginJars(libExt.resolve("jmeter-live-dashboard-1.5.1.jar").toFile()));
    }

    @Test
    void aSingleCopyOrClassesOutsideAJarFindNothing(@TempDir Path libExt) throws Exception {
        Path own = Files.createFile(libExt.resolve("jmeter-live-dashboard-1.5.1.jar"));
        assertEquals(List.of(), LiveDashboardListener.otherPluginJars(own.toFile()));
        // Run from target/classes (an IDE, the unit tests): there is no lib/ext to look in.
        Files.createFile(libExt.resolve("jmeter-live-dashboard-1.4.0.jar"));
        assertEquals(List.of(), LiveDashboardListener.otherPluginJars(libExt.toFile()));
    }
}
