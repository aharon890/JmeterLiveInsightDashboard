package com.jmeterinsight.livedashboard.plugin;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for public static constants and default values defined in
 * LiveDashboardListener. These are pure value checks that require
 * no JMeter runtime.
 */
class LiveDashboardListenerConfigTest {

    @Test
    void testDefaultPort() {
        assertEquals(7566, LiveDashboardListener.DEFAULT_PORT);
    }

    @Test
    void testDefaultBind() {
        assertEquals("localhost", LiveDashboardListener.DEFAULT_BIND);
    }

    @Test
    void testDefaultUpdateInterval() {
        assertEquals(2000, LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_MS);
    }

    @Test
    void testDefaultMaxRetention() {
        assertEquals(3600, LiveDashboardListener.DEFAULT_MAX_RETENTION_SECONDS);
    }

    @Test
    void testDefaultHidePatterns() {
        String patterns = LiveDashboardListener.DEFAULT_HIDE_PATTERNS_STR;
        assertNotNull(patterns);
        assertTrue(patterns.contains("JSR223*"),
                "Default hide patterns should contain 'JSR223*', was: " + patterns);
    }

    @Test
    void testDefaultMaxErrorSamples() {
        assertEquals(100, LiveDashboardListener.DEFAULT_MAX_ERROR_SAMPLES);
    }

    @Test
    void testDefaultApdexThreshold() {
        assertEquals(500.0, LiveDashboardListener.DEFAULT_APDEX_THRESHOLD, 0.001);
    }

    @Test
    void testPropertyKeys() {
        assertEquals("live.dashboard.port", LiveDashboardListener.PORT);
        assertEquals("live.dashboard.bind", LiveDashboardListener.BIND_ADDRESS);
        assertEquals("live.dashboard.update.interval", LiveDashboardListener.UPDATE_INTERVAL);
        assertEquals("live.dashboard.window.max", LiveDashboardListener.MAX_RETENTION);
        assertEquals("live.dashboard.hide.patterns", LiveDashboardListener.HIDE_PATTERNS);
        assertEquals("live.dashboard.sla.default.p95", LiveDashboardListener.SLA_P95);
        assertEquals("live.dashboard.sla.default.errorRate", LiveDashboardListener.SLA_ERROR_RATE);
        assertEquals("live.dashboard.error.samples.max", LiveDashboardListener.MAX_ERROR_SAMPLES);
        assertEquals("live.dashboard.open.browser", LiveDashboardListener.OPEN_BROWSER);
        assertEquals("live.dashboard.apdex.threshold", LiveDashboardListener.APDEX_THRESHOLD);
        assertEquals("live.dashboard.enabled", LiveDashboardListener.ENABLED);

        // All property keys must start with "live.dashboard."
        String[] allKeys = {
                LiveDashboardListener.PORT,
                LiveDashboardListener.BIND_ADDRESS,
                LiveDashboardListener.UPDATE_INTERVAL,
                LiveDashboardListener.MAX_RETENTION,
                LiveDashboardListener.HIDE_PATTERNS,
                LiveDashboardListener.SLA_P95,
                LiveDashboardListener.SLA_ERROR_RATE,
                LiveDashboardListener.MAX_ERROR_SAMPLES,
                LiveDashboardListener.OPEN_BROWSER,
                LiveDashboardListener.APDEX_THRESHOLD,
                LiveDashboardListener.ENABLED
        };
        for (String key : allKeys) {
            assertTrue(key.startsWith("live.dashboard."),
                    "Property key should start with 'live.dashboard.' but was: " + key);
        }
    }

    @Test
    void testAllPropertyKeysUnique() throws IllegalAccessException {
        // Collect all public static String fields that look like property keys
        Set<String> values = new HashSet<>();
        for (Field field : LiveDashboardListener.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (java.lang.reflect.Modifier.isPublic(mod)
                    && java.lang.reflect.Modifier.isStatic(mod)
                    && java.lang.reflect.Modifier.isFinal(mod)
                    && field.getType() == String.class) {
                String value = (String) field.get(null);
                // Only check property key fields (start with "live.dashboard.")
                if (value.startsWith("live.dashboard.")) {
                    boolean added = values.add(value);
                    assertTrue(added,
                            "Duplicate property key value found: " + value
                                    + " (field: " + field.getName() + ")");
                }
            }
        }
        // Sanity: we should have found at least the 12 known property keys
        assertTrue(values.size() >= 12,
                "Expected at least 12 property keys, found " + values.size());
    }

    @Test
    void testOpenBrowserJmeterPropertyOverridesSavedElementValue() throws Exception {
        // Auto-open is an environment concern: the GUI writes the checkbox into
        // every saved plan, so -Jlive.dashboard.open.browser=false (headless CI,
        // Docker) must beat the element value — unlike the other getters.
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            listener.setProperty(LiveDashboardListener.OPEN_BROWSER, "true"); // GUI-saved plan

            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("shouldOpenBrowser");
            m.setAccessible(true);

            assertTrue((Boolean) m.invoke(listener), "element value governs when no -J override");

            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.OPEN_BROWSER, "false");
            assertFalse((Boolean) m.invoke(listener), "-J property must override the saved element value");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.OPEN_BROWSER);
        }
    }

    /**
     * Bind address is an ENVIRONMENT concern, so — like {@code open.browser} — the JMeter
     * property beats the saved element value.
     *
     * <p>It has to be. The GUI's Bind Address control is a fixed, non-editable dropdown
     * with no blank option, and {@code modifyTestElement} writes the selection
     * unconditionally, so <em>every</em> plan saved from the GUI pins this property. Under
     * the normal element-wins rule that made {@code -Jlive.dashboard.bind=0.0.0.0}
     * silently ineffective for exactly the plans people actually have — the dashboard
     * stayed on localhost and teammates could not reach it, with nothing explaining why.
     * The Port field is a text box that can be cleared, so it does not need this and keeps
     * the normal rule.
     */
    @Test
    void bindJmeterPropertyOverridesSavedElementValue() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getBindConfig");
            m.setAccessible(true);

            // What every GUI-saved plan contains.
            listener.setProperty(LiveDashboardListener.BIND_ADDRESS, "localhost");
            assertEquals("localhost", m.invoke(listener),
                    "the element value governs when there is no -J override");

            org.apache.jmeter.util.JMeterUtils.setProperty(
                    LiveDashboardListener.BIND_ADDRESS, "0.0.0.0");
            assertEquals("0.0.0.0", m.invoke(listener),
                    "-J must override the saved element value, or a GUI-saved plan can never "
                            + "be made remotely reachable from the command line");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.BIND_ADDRESS);
        }
    }

    /**
     * An update interval of 0 or less reaches the server's start, which throws
     * — after the HTTP socket has already been bound, so the server is left listening
     * with nothing able to stop it. Reject it here and keep the default instead.
     *
     * <p>Both branches matter: a {@code -J} property bypasses the element value
     * entirely, so validating only the element value would leave the CLI path open.
     */
    @Test
    void invalidUpdateIntervalFallsBackToTheDefault() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getUpdateIntervalConfig");
            m.setAccessible(true);

            // Element value — what the GUI writes into a saved plan.
            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "0");
            assertEquals(LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_MS, m.invoke(listener),
                    "an element value of 0 must fall back to the default");

            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "-1000");
            assertEquals(LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_MS, m.invoke(listener),
                    "a negative element value must fall back to the default");

            // -J property — reached only when the element value is absent.
            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "");
            org.apache.jmeter.util.JMeterUtils.setProperty(
                    LiveDashboardListener.UPDATE_INTERVAL, "0");
            assertEquals(LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_MS, m.invoke(listener),
                    "a -J value of 0 must fall back to the default");

            // A valid value must still win, or the guard is too aggressive.
            org.apache.jmeter.util.JMeterUtils.setProperty(
                    LiveDashboardListener.UPDATE_INTERVAL, "1");
            assertEquals(1000, m.invoke(listener), "a valid -J value must still be honoured");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.UPDATE_INTERVAL);
        }
    }

    /**
     * The interval is in seconds, like the GUI field. It was milliseconds while the GUI
     * showed seconds, so {@code -Jlive.dashboard.update.interval=1}, meant as one second,
     * made 1 ms slots: about 1,000 chart messages a second to every tab and 1,000 retained
     * slots a second in the load generator's heap. (The getter returns milliseconds, the
     * unit everything downstream works in.)
     */
    @Test
    void updateIntervalIsInSeconds() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getUpdateIntervalConfig");
            m.setAccessible(true);

            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "1");
            assertEquals(1000, m.invoke(listener), "element value 1 s");
            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "5");
            assertEquals(5000, m.invoke(listener), "element value 5 s");
            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "999");
            assertEquals(999_000, m.invoke(listener), "999 is the largest value read as seconds");

            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "1");
            assertEquals(1000, m.invoke(listener), "-J value 1 s");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "3");
            assertEquals(3000, m.invoke(listener), "-J value 3 s");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.UPDATE_INTERVAL);
        }
    }

    /**
     * Every plan an older GUI saved says 2000, and older docs showed {@code =1000}: a
     * value of 1000 or more is still read as milliseconds. No live dashboard refreshes
     * once every 17 minutes, so the two units cannot be confused.
     */
    @Test
    void intervalOfAThousandOrMoreIsReadAsTheOldMilliseconds() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getUpdateIntervalConfig");
            m.setAccessible(true);

            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "2000");
            assertEquals(2000, m.invoke(listener), "a plan saved by an older GUI");
            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "1500");
            assertEquals(1500, m.invoke(listener), "a hand-edited millisecond value");

            listener.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "1000");
            assertEquals(1000, m.invoke(listener), "-J in the old unit");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.UPDATE_INTERVAL);
        }
    }

    /**
     * Closed live slots older than the retention are pruned, so 0 or below pruned every
     * slot the moment it closed: a reload, a new tab or a look after the run showed an
     * empty timeline, and nothing said why. Rejected the way an invalid interval is: an
     * element value falls through to {@code -J}, and an invalid {@code -J} to the default.
     */
    @Test
    void nonPositiveRetentionFallsBackToTheDefault() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        long defaultMs = LiveDashboardListener.DEFAULT_MAX_RETENTION_SECONDS * 1000L;
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getMaxRetentionConfig");
            m.setAccessible(true);

            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "0");
            assertEquals(defaultMs, m.invoke(listener), "element value 0");
            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "-300");
            assertEquals(defaultMs, m.invoke(listener), "negative element value");

            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.MAX_RETENTION, "7200");
            assertEquals(7_200_000L, m.invoke(listener), "an invalid element value falls through to -J");

            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.MAX_RETENTION, "0");
            assertEquals(defaultMs, m.invoke(listener), "-J value 0");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.MAX_RETENTION, "-5");
            assertEquals(defaultMs, m.invoke(listener), "negative -J value");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.MAX_RETENTION);
        }
    }

    /** The GUI's floor is one minute; a few seconds of 2-second slots is barely a chart. */
    @Test
    void retentionBelowOneMinuteIsRaisedToOneMinute() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m =
                    LiveDashboardListener.class.getDeclaredMethod("getMaxRetentionConfig");
            m.setAccessible(true);

            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "30");
            assertEquals(60_000L, m.invoke(listener), "element value 30 s");
            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "1800");
            assertEquals(1_800_000L, m.invoke(listener), "a value above the floor is kept as is");

            listener.setProperty(LiveDashboardListener.MAX_RETENTION, "");
            org.apache.jmeter.util.JMeterUtils.setProperty(LiveDashboardListener.MAX_RETENTION, "1");
            assertEquals(60_000L, m.invoke(listener), "-J value 1 s");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties()
                    .remove(LiveDashboardListener.MAX_RETENTION);
        }
    }

    /**
     * The header, page title and notifications show the plan's own name. The name used
     * to come from a JMeter property, testPlanName, that JMeter never sets, so every run
     * was "JMeter Load Test". Non-GUI JMeter knows the plan only as the script file it
     * loaded, so the name is read from that file's TestPlan element.
     */
    @Test
    void testNameComesFromThePlanBeingRun() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        java.io.File dir = java.nio.file.Files.createTempDirectory("ld-plan").toFile();
        java.io.File jmx = new java.io.File(dir, "checkout-flow.jmx");
        org.apache.jmeter.services.FileServer fs = org.apache.jmeter.services.FileServer.getFileServer();
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("resolveTestName");
            m.setAccessible(true);

            writePlan(jmx, "Checkout — Ünïcode &amp; &lt;Peak&gt;");
            fs.setBaseForScript(jmx);
            assertEquals("Checkout — Ünïcode & <Peak>", m.invoke(listener),
                    "the TestPlan element's name, entities decoded");

            writePlan(jmx, "");
            assertEquals("checkout-flow", m.invoke(listener),
                    "a plan saved without a name falls back to its file name");

            org.apache.jmeter.util.JMeterUtils.setProperty("testPlanName", "Release 42 soak");
            assertEquals("Release 42 soak", m.invoke(listener), "-JtestPlanName stays an explicit override");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove("testPlanName");
            fs.setScriptName(null);
            fs.resetBase();
        }
    }

    /**
     * In GUI mode the name comes from the Test Plan node in JMeter's tree, not the saved
     * file, so a plan renamed and run without saving shows its new name. Neither
     * Playwright nor the headless Docker run can reach this branch; installing a
     * GuiPackage here is the only place it is pinned. The saved file carries a different
     * name, so the test cannot pass by falling through to it. Unit tests count as GUI mode
     * already: JMeter.isNonGUI() reads a system property nothing here sets.
     */
    @Test
    void testNameComesFromTheGuiTreeInGuiMode() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        java.io.File dir = java.nio.file.Files.createTempDirectory("ld-plan").toFile();
        java.io.File jmx = new java.io.File(dir, "saved-plan.jmx");
        org.apache.jmeter.services.FileServer fs = org.apache.jmeter.services.FileServer.getFileServer();
        // GuiPackage is a singleton with no reset; left installed, it would rename every
        // later test's plan.
        Field guiPack = org.apache.jmeter.gui.GuiPackage.class.getDeclaredField("guiPack");
        guiPack.setAccessible(true);
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("resolveTestName");
            m.setAccessible(true);

            writePlan(jmx, "Name On Disk");
            fs.setBaseForScript(jmx);
            org.apache.jmeter.testelement.TestPlan plan = new org.apache.jmeter.testelement.TestPlan("Name In The Tree");
            org.apache.jmeter.gui.tree.JMeterTreeModel model = new org.apache.jmeter.gui.tree.JMeterTreeModel(plan);
            org.apache.jmeter.gui.GuiPackage.initInstance(
                    new org.apache.jmeter.gui.tree.JMeterTreeListener(model), model);

            assertEquals("Name In The Tree", m.invoke(listener), "the GUI's tree wins over the saved file");

            plan.setName("  ");
            assertEquals("Name On Disk", m.invoke(listener), "a blank tree name falls through to the saved file");

            plan.setName("Name In The Tree");
            org.apache.jmeter.util.JMeterUtils.setProperty("testPlanName", "Release 42 soak");
            assertEquals("Release 42 soak", m.invoke(listener), "-JtestPlanName beats the GUI's tree too");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove("testPlanName");
            guiPack.set(null, null);
            fs.setScriptName(null);
            fs.resetBase();
        }
    }

    /**
     * With no plan to read — no GUI tree, no script — the name is the fixed default. A
     * blank -JtestPlanName is not an override: automation that passes an unset variable
     * (-JtestPlanName=$NAME) must get the plan's name, not an empty header.
     */
    @Test
    void testNameDefaultsWhenThereIsNoPlan() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        org.apache.jmeter.services.FileServer fs = org.apache.jmeter.services.FileServer.getFileServer();
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("resolveTestName");
            m.setAccessible(true);
            fs.setScriptName(null);

            assertEquals("JMeter Load Test", m.invoke(listener), "no plan anywhere: the default");

            org.apache.jmeter.util.JMeterUtils.setProperty("testPlanName", "   ");
            assertEquals("JMeter Load Test", m.invoke(listener), "a blank -JtestPlanName is no override");
        } finally {
            org.apache.jmeter.util.JMeterUtils.getJMeterProperties().remove("testPlanName");
            fs.resetBase();
        }
    }

    /**
     * A script whose TestPlan name can't be read still has a file name, and that is the
     * name. Reading the plan happens while the dashboard starts, so a bad file must
     * degrade to the file name, never throw.
     */
    @Test
    void testNameFallsBackToTheFileNameWhenThePlanCannotBeRead() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        java.io.File dir = java.nio.file.Files.createTempDirectory("ld-plan").toFile();
        java.io.File jmx = new java.io.File(dir, "checkout-flow.jmx");
        org.apache.jmeter.services.FileServer fs = org.apache.jmeter.services.FileServer.getFileServer();
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("resolveTestName");
            m.setAccessible(true);
            fs.setBaseForScript(jmx);

            assertEquals("checkout-flow", m.invoke(listener), "the script file is missing");

            java.nio.file.Files.write(jmx.toPath(),
                    "this is not a test plan".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("checkout-flow", m.invoke(listener), "the script is not XML");

            java.nio.file.Files.write(jmx.toPath(), ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<jmeterTestPlan version=\"1.2\"><hashTree>"
                    + "<TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\"/>"
                    + "</hashTree></jmeterTestPlan>\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals("checkout-flow", m.invoke(listener), "the TestPlan has no testname attribute");
        } finally {
            fs.setScriptName(null);
            fs.resetBase();
        }
    }

    private static void writePlan(java.io.File jmx, String escapedName) throws java.io.IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<jmeterTestPlan version=\"1.2\" properties=\"5.0\" jmeter=\"5.6.3\">\n"
                + "  <hashTree>\n"
                + "    <TestPlan guiclass=\"TestPlanGui\" testclass=\"TestPlan\" testname=\"" + escapedName + "\">\n"
                + "      <boolProp name=\"TestPlan.functional_mode\">false</boolProp>\n"
                + "    </TestPlan>\n"
                + "    <hashTree/>\n"
                + "  </hashTree>\n"
                + "</jmeterTestPlan>\n";
        java.nio.file.Files.write(jmx.toPath(), xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Apdex thresholds follow JMeter's HTML report: T and F default to
     * jmeter.reportgenerator.apdex_satisfied_threshold / apdex_tolerated_threshold
     * (500 / 1500), so the dashboard and the report agree. live.dashboard.apdex.threshold
     * still overrides T.
     */
    @Test
    void apdexThresholdsFollowJMetersReportProperties() throws Exception {
        java.io.File props = java.io.File.createTempFile("jmeter-test", ".properties");
        props.deleteOnExit();
        org.apache.jmeter.util.JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
        java.util.Properties jp = org.apache.jmeter.util.JMeterUtils.getJMeterProperties();
        try {
            LiveDashboardListener listener = new LiveDashboardListener();
            java.lang.reflect.Method m = LiveDashboardListener.class.getDeclaredMethod("getApdexConfig");
            m.setAccessible(true);

            assertArrayEquals(new double[]{500, 1500}, (double[]) m.invoke(listener), 0.0,
                    "JMeter's report defaults when nothing is set");

            jp.setProperty(LiveDashboardListener.JMETER_APDEX_SATISFIED, "300");
            jp.setProperty(LiveDashboardListener.JMETER_APDEX_TOLERATED, "1200");
            assertArrayEquals(new double[]{300, 1200}, (double[]) m.invoke(listener), 0.0,
                    "a user who tuned the report's thresholds gets the same ones here");

            jp.setProperty(LiveDashboardListener.APDEX_THRESHOLD, "250");
            assertArrayEquals(new double[]{250, 1200}, (double[]) m.invoke(listener), 0.0,
                    "live.dashboard.apdex.threshold overrides T only");
        } finally {
            jp.remove(LiveDashboardListener.JMETER_APDEX_SATISFIED);
            jp.remove(LiveDashboardListener.JMETER_APDEX_TOLERATED);
            jp.remove(LiveDashboardListener.APDEX_THRESHOLD);
        }
    }
}
