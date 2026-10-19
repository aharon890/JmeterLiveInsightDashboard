package com.jmeterinsight.livedashboard.plugin;

import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.util.JMeterUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.text.JTextComponent;
import java.io.File;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the GUI panel's element<->field mapping.
 *
 * The GUI is where a saved plan's properties actually come from, and it does
 * not always store what it displays: the retention field is in MINUTES but the
 * property is SECONDS. Get that conversion wrong and a saved plan silently keeps
 * 60x the intended history, with nothing to show for it in the UI. The interval
 * field and property are both in seconds, but plans saved by older versions hold
 * milliseconds.
 *
 * Swing components are constructed here but never shown, which is headless-safe.
 */
class LiveDashboardListenerGuiTest {

    @BeforeAll
    static void initJMeterProperties() throws Exception {
        // AbstractListenerGui's makeBorder()/makeTitlePanel() read JMeter
        // resources; without loaded properties they blow up on construction.
        File props = File.createTempFile("jmeter-gui-test", ".properties");
        props.deleteOnExit();
        JMeterUtils.loadJMeterProperties(props.getAbsolutePath());
    }

    @Test
    void testCreateTestElementReturnsConfiguredListener() {
        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        TestElement element = gui.createTestElement();

        assertNotNull(element);
        assertTrue(element instanceof LiveDashboardListener,
                "GUI must create a LiveDashboardListener, got: " + element.getClass().getName());
    }

    /**
     * Pins the ABSOLUTE scale, not just symmetry: the GUI's own defaults must
     * land on the documented constants. Two directions that are both wrong by
     * the same factor would still round-trip cleanly, so symmetry alone cannot
     * catch a bad conversion — this can.
     */
    @Test
    void testGuiDefaultsMatchListenerConstants() {
        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.clearGui();

        TestElement element = gui.createTestElement();

        assertEquals(String.valueOf(LiveDashboardListener.DEFAULT_UPDATE_INTERVAL_SECONDS),
                element.getPropertyAsString(LiveDashboardListener.UPDATE_INTERVAL),
                "GUI default interval (2s) must serialize to DEFAULT_UPDATE_INTERVAL_SECONDS");
        assertEquals(String.valueOf(LiveDashboardListener.DEFAULT_MAX_RETENTION_SECONDS),
                element.getPropertyAsString(LiveDashboardListener.MAX_RETENTION),
                "GUI default retention (60min) must serialize to DEFAULT_MAX_RETENTION_SECONDS");
        assertEquals(String.valueOf(LiveDashboardListener.DEFAULT_PORT),
                element.getPropertyAsString(LiveDashboardListener.PORT));
    }

    /**
     * configure() -> modifyTestElement() must be lossless. This is the path a
     * user's saved plan takes every time they open the element in the GUI and
     * click away: any drift here silently rewrites their plan.
     */
    @Test
    void testConfigureThenModifyRoundTripsWithoutDrift() {
        LiveDashboardListener source = new LiveDashboardListener();
        source.setProperty(LiveDashboardListener.PORT, "7777");
        source.setProperty(LiveDashboardListener.BIND_ADDRESS, "0.0.0.0");
        source.setProperty(LiveDashboardListener.OPEN_BROWSER, "false");
        source.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "5");      // s, as in the GUI
        source.setProperty(LiveDashboardListener.MAX_RETENTION, "1800");     // sec -> 30min in the GUI
        source.setProperty(LiveDashboardListener.HIDE_PATTERNS, "Foo*,Bar*");

        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.configure(source);

        LiveDashboardListener target = new LiveDashboardListener();
        gui.modifyTestElement(target);

        assertEquals("7777", target.getPropertyAsString(LiveDashboardListener.PORT));
        assertEquals("0.0.0.0", target.getPropertyAsString(LiveDashboardListener.BIND_ADDRESS));
        assertEquals("false", target.getPropertyAsString(LiveDashboardListener.OPEN_BROWSER));
        assertEquals("5", target.getPropertyAsString(LiveDashboardListener.UPDATE_INTERVAL),
                "Interval must survive the trip unchanged");
        assertEquals("1800", target.getPropertyAsString(LiveDashboardListener.MAX_RETENTION),
                "Retention must survive the s->min->s trip unchanged");
        assertEquals("Foo*,Bar*", target.getPropertyAsString(LiveDashboardListener.HIDE_PATTERNS));
    }

    /**
     * The server binds any address, so a plan can carry a specific interface. The bind
     * control used to be a fixed dropdown of localhost/0.0.0.0: it could not show any
     * other value, and since JMeter calls modifyTestElement merely on leaving the tree
     * node, clicking the element rewrote the plan's address to localhost.
     */
    @Test
    void handEditedBindAddressSurvivesTheGui() {
        LiveDashboardListener source = new LiveDashboardListener();
        source.setProperty(LiveDashboardListener.BIND_ADDRESS, "10.0.0.5");

        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.configure(source);
        gui.modifyTestElement(source);

        assertEquals("10.0.0.5", source.getPropertyAsString(LiveDashboardListener.BIND_ADDRESS));
    }

    /**
     * JMeter keeps one GUI instance per class. With the fixed dropdown, an element the
     * dropdown could not represent showed — and was then saved with — whatever the
     * previously viewed element had selected.
     */
    @Test
    void sharedGuiInstanceDoesNotCarryOneElementsBindIntoAnother() {
        LiveDashboardListener shared = new LiveDashboardListener();
        shared.setProperty(LiveDashboardListener.BIND_ADDRESS, "0.0.0.0");
        LiveDashboardListener pinned = new LiveDashboardListener();
        pinned.setProperty(LiveDashboardListener.BIND_ADDRESS, "10.0.0.5");

        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.configure(shared);
        gui.configure(pinned);
        gui.modifyTestElement(pinned);

        assertEquals("10.0.0.5", pinned.getPropertyAsString(LiveDashboardListener.BIND_ADDRESS));
    }

    /**
     * An editable combo commits typed text on Enter or focus loss, and JMeter may call
     * modifyTestElement before either reaches it (the tree takes the click first). What
     * is in the box is what gets saved.
     */
    @Test
    void typedBindAddressIsSavedEvenBeforeTheComboCommitsIt() throws Exception {
        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.clearGui();

        Field comboField = LiveDashboardListenerGui.class.getDeclaredField("bindAddressCombo");
        comboField.setAccessible(true);
        JComboBox<?> combo = (JComboBox<?>) comboField.get(gui);
        ((JTextComponent) combo.getEditor().getEditorComponent()).setText(" 192.168.1.20 ");

        LiveDashboardListener target = new LiveDashboardListener();
        gui.modifyTestElement(target);

        assertEquals("192.168.1.20", target.getPropertyAsString(LiveDashboardListener.BIND_ADDRESS));
    }

    /**
     * The open-browser checkbox is the one setting a headless run overrides via
     * -J, so the GUI must still persist it faithfully in both states — see
     * LiveDashboardListenerConfigTest for the precedence half of this contract.
     */
    @Test
    void testOpenBrowserCheckboxRoundTripsBothStates() {
        for (String expected : new String[]{"true", "false"}) {
            LiveDashboardListener source = new LiveDashboardListener();
            source.setProperty(LiveDashboardListener.OPEN_BROWSER, expected);

            LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
            gui.configure(source);

            LiveDashboardListener target = new LiveDashboardListener();
            gui.modifyTestElement(target);

            assertEquals(expected, target.getPropertyAsString(LiveDashboardListener.OPEN_BROWSER),
                    "open.browser=" + expected + " must round-trip through the GUI");
        }
    }

    /**
     * The interval field had no lower bound, so a field of 0 wrote exactly 0 — which
     * reaches the server's start and throws AFTER the HTTP socket is bound, leaving a
     * server nothing can stop.
     *
     * <p>The clamped value must be WRITTEN, not skipped: skipping the write leaves the
     * previous value in the element, which is a silent no-op of exactly the kind this
     * codebase keeps warning about.
     */
    @Test
    void testSubSecondIntervalIsClampedNotWrittenAsZero() {
        LiveDashboardListener source = new LiveDashboardListener();
        source.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "0");

        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.configure(source);   // field shows 0 seconds

        LiveDashboardListener target = new LiveDashboardListener();
        target.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "9999"); // must be overwritten
        gui.modifyTestElement(target);

        String written = target.getPropertyAsString(LiveDashboardListener.UPDATE_INTERVAL);
        assertNotEquals("9999", written,
                "the clamped value must be written — skipping the write leaves the old value behind");
        assertEquals("1", written, "a 0-second field must clamp to 1s");
    }

    /**
     * Plans saved before the interval became seconds hold milliseconds (2000). The GUI
     * must show them in seconds and save them back in seconds.
     */
    @Test
    void oldMillisecondIntervalOpensAndSavesAsSeconds() {
        LiveDashboardListener source = new LiveDashboardListener();
        source.setProperty(LiveDashboardListener.UPDATE_INTERVAL, "5000");

        LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
        gui.configure(source);
        gui.modifyTestElement(source);

        assertEquals("5", source.getPropertyAsString(LiveDashboardListener.UPDATE_INTERVAL));
    }

    /**
     * The retention field is in minutes and was multiplied by 60 with no lower bound, so 0
     * (easily meant as "unlimited") or a negative wrote a retention that prunes every
     * chart slot as soon as it closes. Clamped to one minute and written, like the interval.
     */
    @Test
    void nonPositiveRetentionIsClampedToOneMinuteAndWritten() {
        for (String saved : new String[]{"0", "-300"}) {
            LiveDashboardListener source = new LiveDashboardListener();
            source.setProperty(LiveDashboardListener.MAX_RETENTION, saved);

            LiveDashboardListenerGui gui = new LiveDashboardListenerGui();
            gui.configure(source);

            LiveDashboardListener target = new LiveDashboardListener();
            target.setProperty(LiveDashboardListener.MAX_RETENTION, "9999"); // must be overwritten
            gui.modifyTestElement(target);

            assertEquals("60", target.getPropertyAsString(LiveDashboardListener.MAX_RETENTION),
                    "saved retention " + saved + " s");
        }
    }
}
