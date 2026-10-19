package com.jmeterinsight.livedashboard.engine;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ColumnarTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void encodesColumnsOnceAndARowPerSampler() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("GET /a", row("samples", 3L, "p95", 120.0));
        in.put("GET /b", row("samples", 1L, "p95", 80.0, "extra", "x"));
        Map<String, Object> out = (Map<String, Object>) Columnar.encode(in);
        assertEquals(Arrays.asList("samples", "p95", "extra"), out.get("cols"));
        Map<String, List<Object>> rows = (Map<String, List<Object>>) out.get("rows");
        assertEquals(Arrays.asList(3L, 120.0, null), rows.get("GET /a"), "a missing column is null");
        assertEquals(Arrays.asList(1L, 80.0, "x"), rows.get("GET /b"));
    }

    @Test
    void leavesAnythingElseAlone() {
        assertNull(Columnar.encode(null));
        assertEquals("text", Columnar.encode("text"));
        Map<String, Object> notRows = row("a", 1L);
        assertSame(notRows, Columnar.encode(notRows), "a map whose values are not maps is not per-sampler");
    }

    @Test
    void roundsComputedDoublesToSevenSignificantDigits() {
        assertEquals(76.39108, Columnar.significant7(76.39107723140076), 0.0);
        assertEquals(0.0001234568, Columnar.significant7(0.000123456789), 0.0);
        assertEquals(1234567.0, Columnar.significant7(1234567.4), 0.0);
        assertEquals(33.33333, Columnar.significant7(100.0 / 3), 0.0);
        assertEquals(-2.5, Columnar.significant7(-2.5), 0.0);
        // Whole numbers and non-finite values pass through
        assertEquals(98765432101.0, Columnar.significant7(98765432101.0), 0.0);
        assertTrue(Double.isNaN(Columnar.significant7(Double.NaN)));
        assertEquals(0.0, Columnar.significant7(0.0), 0.0);
        // Relative error stays under the harness's 1e-6 tolerance
        for (double v = 1e-3; v < 1e7; v *= 1.37) {
            double r = Columnar.significant7(v + 0.123456789e-3);
            assertTrue(Math.abs(r - (v + 0.123456789e-3)) / (v + 0.123456789e-3) < 1e-6, String.valueOf(v));
        }
    }

    /**
     * The table prints a mean as whole milliseconds, truncated as JMeter's Aggregate
     * Report does. Rounded to 7 digits on the wire, a mean of 1234.9996 ms arrived as
     * 1235 and printed one millisecond high.
     */
    @Test
    void neverRoundsAcrossAWholeNumber() {
        for (double v : new double[]{9.99999996, 99.9999996, 1234.9996, 12345.996, 123456.96, 1234567.6}) {
            assertEquals((long) v, (long) Columnar.significant7(v), String.valueOf(v));
            assertTrue(Math.abs(Columnar.significant7(v) - v) / v < 1e-6, "still within the harness tolerance: " + v);
        }
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("GET /slow", row("avgResponseTime", 12345.996));
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> rows = (Map<String, List<Object>>) ((Map<String, Object>) Columnar.encode(in)).get("rows");
        assertEquals(12345L, (long) (double) (Double) rows.get("GET /slow").get(0));
    }
}
