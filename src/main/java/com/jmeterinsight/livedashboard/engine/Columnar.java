package com.jmeterinsight.livedashboard.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wire form of a per-sampler map: field names once, then a row of values
 * per sampler — {@code {"cols": [...], "rows": {"name": [...]}}}.
 *
 * As objects, every row repeated every field name: at 1,000 samplers an
 * update was ~800 KB, most of it the same twenty-odd keys a thousand times,
 * and the browser parsed all of it every 2 s. The client turns rows back into
 * objects at the door (index.html, decodeColumnar).
 */
public final class Columnar {

    private Columnar() {
    }

    /**
     * Encodes a map of per-sampler maps. Columns are the first row's keys plus
     * any key a later row adds; a row missing a column gets null there.
     * Anything that is not a map of maps is returned unchanged.
     */
    @SuppressWarnings("unchecked")
    public static Object encode(Object perSampler) {
        if (!(perSampler instanceof Map)) return perSampler;
        Map<String, Object> in = (Map<String, Object>) perSampler;
        List<String> cols = new ArrayList<>();
        for (Object row : in.values()) {
            if (!(row instanceof Map)) return perSampler;
            for (String k : ((Map<String, Object>) row).keySet()) {
                if (!cols.contains(k)) cols.add(k);
            }
        }
        Map<String, Object> rows = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Map<String, Object> row = (Map<String, Object>) e.getValue();
            List<Object> values = new ArrayList<>(cols.size());
            for (String c : cols) {
                Object v = row.get(c);
                values.add(v instanceof Double ? significant7((Double) v) : v);
            }
            rows.put(e.getKey(), values);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cols", cols);
        out.put("rows", rows);
        return out;
    }

    /**
     * A double to 7 significant digits. A computed average or rate prints 17
     * digits ("76.39107723140076"), which on the wire is noise: no display or
     * comparison uses more than 7 (the harness compares means with JMeter's at
     * 1e-6 relative). Whole numbers and non-finite values are left as they are.
     *
     * Never rounded across a whole number: the table prints a mean as whole
     * milliseconds, truncated as JMeter's Aggregate Report does, and 1234.9996
     * sent as 1235 printed one millisecond high. Such a value goes out unrounded.
     */
    static double significant7(double v) {
        if (v == 0 || Double.isNaN(v) || Double.isInfinite(v) || v == Math.rint(v)) return v;
        double scale = Math.pow(10, 6 - Math.floor(Math.log10(Math.abs(v))));
        double r = Math.round(v * scale) / scale;
        return Double.isFinite(r) && (long) r == (long) v ? r : v;
    }

    /**
     * A shallow copy of a bucket message with its per-sampler maps encoded:
     * {@code samplers}, {@code samplerStats} and each overview slot's
     * {@code samplers}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> wireMessage(Map<String, Object> msg) {
        Map<String, Object> out = new LinkedHashMap<>(msg);
        out.computeIfPresent("samplers", (k, v) -> encode(v));
        out.computeIfPresent("samplerStats", (k, v) -> encode(v));
        Object overview = out.get("overview");
        if (overview instanceof List) {
            List<Object> slots = new ArrayList<>();
            for (Object slot : (List<Object>) overview) {
                if (slot instanceof Map) {
                    Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) slot);
                    s.computeIfPresent("samplers", (k, v) -> encode(v));
                    slots.add(s);
                } else {
                    slots.add(slot);
                }
            }
            out.put("overview", slots);
        }
        return out;
    }
}
