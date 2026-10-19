package com.jmeterinsight.livedashboard.engine;

import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a newly connected client needs, captured at one instant and
 * written later, straight to the socket.
 *
 * The catch-up used to be built as one Map tree, serialized to a String,
 * copied into a StringBuilder and then into a byte array: four full copies
 * held at once. Measured JSON alone was 9.9 MB after 10 min at 100 samplers,
 * heading for ~60 MB per hour, and the object tree is several times that —
 * one page reload could exhaust a heap the test itself needs. This holds only
 * references to frozen slots (immutable) and the small summary maps; the
 * history is generated slot by slot as it is written.
 */
public final class CatchupSnapshot {

    private final Map<String, Object> head;
    private final List<FrozenBucket> buckets;
    private final List<FrozenBucket> overview;
    private final List<String> names;
    private final Map<String, Object> tail;

    CatchupSnapshot(Map<String, Object> head, List<FrozenBucket> buckets, List<FrozenBucket> overview,
                    List<String> names, Map<String, Object> tail) {
        this.head = head;
        this.buckets = buckets;
        this.overview = overview;
        this.names = names;
        this.tail = tail;
    }

    /** Only the history part: what {@code /api/history} serves. */
    static CatchupSnapshot historyOnly(List<FrozenBucket> buckets, List<String> names) {
        return new CatchupSnapshot(new LinkedHashMap<>(), buckets, null, names, new LinkedHashMap<>());
    }

    /**
     * Writes the catch-up object, then {@code extra}'s fields (state the
     * server owns). The generator needs an ObjectCodec for the summary maps —
     * create it from an ObjectMapper.
     */
    public void writeJson(JsonGenerator gen, Map<String, Object> extra) throws IOException {
        // Per-sampler maps go out columnar (see Columnar): this is the
        // largest payload the dashboard sends
        gen.writeStartObject();
        for (Map.Entry<String, Object> e : head.entrySet()) {
            gen.writeFieldName(e.getKey());
            gen.writeObject("samplerStats".equals(e.getKey()) ? Columnar.encode(e.getValue()) : e.getValue());
        }
        gen.writeFieldName("recentBuckets");
        writeBuckets(gen, buckets, true);
        if (overview != null) {
            // The whole run at one minute: the client charts it where the
            // live window no longer reaches
            gen.writeFieldName("overviewBuckets");
            writeBuckets(gen, overview, true);
        }
        writeFields(gen, tail);
        writeFields(gen, extra);
        gen.writeEndObject();
    }

    /** Writes the history as a JSON array of slots, per-sampler maps as objects (/api/history). */
    public void writeBuckets(JsonGenerator gen) throws IOException {
        writeBuckets(gen, buckets, false);
    }

    private void writeBuckets(JsonGenerator gen, List<FrozenBucket> slots, boolean columnar) throws IOException {
        gen.writeStartArray();
        for (FrozenBucket fb : slots) {
            gen.writeStartObject();
            gen.writeNumberField("timestamp", fb.startTime);
            gen.writeFieldName("overall");
            gen.writeObject(fb.overallMap());
            gen.writeFieldName("samplers");
            if (columnar) fb.writeSamplersColumnar(gen, names);
            else fb.writeSamplers(gen, names);
            gen.writeEndObject();
        }
        gen.writeEndArray();
    }

    private static void writeFields(JsonGenerator gen, Map<String, Object> fields) throws IOException {
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            gen.writeFieldName(e.getKey());
            gen.writeObject(e.getValue());
        }
    }

    /** The history as maps — the same content {@link #writeBuckets} streams. */
    public List<Map<String, Object>> bucketList() {
        return bucketList(buckets);
    }

    private List<Map<String, Object>> bucketList(List<FrozenBucket> slots) {
        List<Map<String, Object>> list = new ArrayList<>(slots.size());
        for (FrozenBucket fb : slots) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("timestamp", fb.startTime);
            b.put("overall", fb.overallMap());
            b.put("samplers", fb.samplersMap(names));
            list.add(b);
        }
        return list;
    }

    /**
     * The whole catch-up as one map, per-sampler maps as objects. For tests;
     * {@link #writeJson} writes it with those maps columnar.
     */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>(head);
        m.put("recentBuckets", bucketList(buckets));
        if (overview != null) m.put("overviewBuckets", bucketList(overview));
        m.putAll(tail);
        return m;
    }
}
