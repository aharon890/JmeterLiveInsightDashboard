package com.jmeterinsight.livedashboard.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** What each value of live.dashboard.access.token asks for. */
class AccessLinkTest {

    @Test
    void testUnsetAsksForNoLinkAndDoesNotSayItIsIntended() {
        for (String unset : new String[]{null, "", "   "}) {
            AccessLink link = AccessLink.from(unset);
            assertNull(link.token, "no token for: " + unset);
            assertFalse(link.declaredNotRequired, "unset is not a decision, so the open-dashboard warning still shows");
            assertNull(link.note);
        }
    }

    @Test
    void testNotRequiredSaysAnOpenDashboardIsIntended() {
        for (String value : new String[]{"not_required", "NOT_REQUIRED", " not_required "}) {
            AccessLink link = AccessLink.from(value);
            assertNull(link.token);
            assertTrue(link.declaredNotRequired, "declared for: " + value);
            assertNull(link.note);
        }
    }

    /**
     * One generated token for the life of this JMeter process. In the JMeter window a second Start begins
     * a new dashboard; with a new token each time, a teammate's open tab would get 401 at every re-run.
     */
    @Test
    void testGenerateMakesOneTokenForThisJMeterProcess() {
        AccessLink first = AccessLink.from("generate");
        AccessLink second = AccessLink.from("GENERATE");
        assertTrue(first.token.matches("[0-9a-f]{48}"), "48 hex characters, 192 random bits: " + first.token.length());
        assertEquals(first.token, second.token, "the next run in the same JMeter keeps the link");
        assertFalse(first.declaredNotRequired);
        assertNull(first.note);
    }

    /** The starter of several plans gives them one token, so one browser tab keeps following them. */
    @Test
    void testATokenOfYourOwnIsUsedAsWritten() {
        for (String own : new String[]{"run-2026-11-22_a1b2c3", "ABCDEFGHIJKLMNOP", "a.b~c-d_e.f~g-h_i"}) {
            AccessLink link = AccessLink.from(" " + own + " ");
            assertEquals(own, link.token);
            assertNull(link.note);
        }
    }

    /**
     * A value that cannot travel in a URL and a cookie as it is, or is short enough to guess. Starting an
     * open dashboard would be the unsafe reading of a typo, so a generated token takes its place and the
     * log says so.
     */
    @Test
    void testAnUnusableTokenBecomesAGeneratedOneAndTheLogSaysSo() {
        for (String unusable : new String[]{"short", "fifteen-chars-x", "has a space in the middle", "semi;colon;in;the;token",
                "quote\"in-the-token-value", "slash/in/the/token/value", "true", "yes", "x".repeat(129)}) {
            AccessLink link = AccessLink.from(unusable);
            assertNotNull(link.token, "a token is still required for: " + unusable);
            assertTrue(link.token.matches("[0-9a-f]{48}"), "generated, not the value as written: " + unusable);
            assertEquals(AccessLink.from("generate").token, link.token, "the token 'generate' gives in this JMeter");
            assertNotNull(link.note, "the log must say why for: " + unusable);
            assertTrue(link.note.contains("live.dashboard.access.token"), link.note);
        }
    }
}
