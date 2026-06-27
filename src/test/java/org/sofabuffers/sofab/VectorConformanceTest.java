/*
 * SofaBuffers Java - conformance tests against the shared, language-agnostic
 * test vector suite (assets/test_vectors.json from the documentation repo).
 *
 * Per the SofaBuffers architecture guide (§7), every corelib must replay these
 * exact vectors for both encode and decode. One dynamic test is generated per
 * vector per direction, so a failure points at the specific vector.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

class VectorConformanceTest {

    private static JsonArray loadVectors() {
        try (InputStream in = VectorConformanceTest.class.getResourceAsStream("/test_vectors.json")) {
            if (in == null) {
                throw new IllegalStateException("test_vectors.json not found on the test classpath");
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("sofabuffers-test-vectors", root.get("format").getAsString());
            return root.getAsJsonArray("vectors");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @TestFactory
    List<DynamicTest> encodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("encode:" + v.get("name").getAsString(),
                    () -> assertEncode(v)));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> decodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("decode:" + v.get("name").getAsString(),
                    () -> assertDecode(v)));
        }
        return tests;
    }

    /**
     * Chunked decode (test_vectors_README "Chunked processing"): feed every vector
     * one byte at a time and in 3- / 7-byte chunks through a single decoder and
     * assert the event stream is byte-for-byte identical to a single feed. This is
     * the strongest guard on the streaming state machine — the decoder must
     * suspend and resume at any byte boundary without losing state.
     */
    @TestFactory
    List<DynamicTest> chunkedDecodeVectors() {
        int[] chunkSizes = {1, 3, 7};
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            for (int chunk : chunkSizes) {
                tests.add(DynamicTest.dynamicTest("decode/" + chunk + "B:" + name,
                        () -> assertChunkedDecode(v, chunk)));
            }
        }
        return tests;
    }

    /**
     * Skip-IDs decode (test_vectors_README "Skip-IDs decoding"): for vectors that
     * carry a {@code skip_ids} list, a receiver that ignores those field ids (and,
     * for a sequence id, the entire sub-tree under it) must still recover every
     * other field. This proves the decoder resyncs on the field following any
     * skipped field or sub-sequence, at any nesting depth.
     */
    @TestFactory
    List<DynamicTest> skipIdsDecodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            if (!v.has("skip_ids")) {
                continue;
            }
            tests.add(DynamicTest.dynamicTest("skip:" + v.get("name").getAsString(),
                    () -> assertSkipDecode(v)));
        }
        return tests;
    }

    /**
     * Chunked encode (ARCHITECTURE §7 test kind #4): replay every vector into an
     * encoder backed by a buffer far smaller than the message plus a flush sink,
     * forcing repeated mid-field flushes, and assert the streamed-out bytes are
     * identical to the one-shot encoding. Validates the encoder's
     * capacity/flush bookkeeping under the optimized contiguous-write path.
     */
    @TestFactory
    List<DynamicTest> chunkedEncodeVectors() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("encode/chunked:" + v.get("name").getAsString(),
                    () -> assertChunkedEncode(v)));
        }
        return tests;
    }

    /**
     * {@code requires} awareness: this Java corelib compiles in every wire feature
     * (it has no {@code SOFAB_DISABLE_*} switches), so no vector is skipped. Guard
     * that the suite never references an unknown capability tag — a new tag would
     * otherwise be silently treated as "supported".
     */
    @TestFactory
    List<DynamicTest> requiresTagsAreKnown() {
        java.util.Set<String> known = java.util.Set.of("fixlen", "array", "sequence", "fp64", "int64");
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("requires:" + v.get("name").getAsString(), () -> {
                for (String cap : requiresOf(v)) {
                    org.junit.jupiter.api.Assertions.assertTrue(
                            known.contains(cap), "unknown capability tag: " + cap);
                }
            }));
        }
        return tests;
    }

    // --- encode: replay fields[] and compare produced bytes to serialized.hex --

    private static void assertEncode(JsonObject v) throws IOException {
        int offset = v.get("offset").getAsInt();
        byte[] buf = new byte[4096];
        OStream os = new OStream(buf, offset);
        for (JsonElement fe : v.getAsJsonArray("fields")) {
            replay(os, fe.getAsJsonObject());
        }
        String got = hex(buf, offset, os.bytesUsed());
        assertEquals(v.getAsJsonObject("serialized").get("hex").getAsString(), got);
    }

    private static void replay(OStream os, JsonObject f) throws IOException {
        String op = f.get("op").getAsString();
        int id = f.has("id") ? f.get("id").getAsInt() : 0;
        switch (op) {
            case "unsigned": os.writeUnsigned(id, f.get("value").getAsBigInteger().longValue()); break;
            case "signed":   os.writeSigned(id, f.get("value").getAsLong()); break;
            case "boolean":  os.writeBoolean(id, f.get("value").getAsBoolean()); break;
            case "fp32":     os.writeFp32(id, toFloat(f.get("value"))); break;
            case "fp64":     os.writeFp64(id, toDouble(f.get("value"))); break;
            case "string":   os.writeString(id, f.get("value").getAsString()); break;
            case "blob":     os.writeBlob(id, unhex(f.get("value_hex").getAsString())); break;
            case "array":    writeArray(os, id, f.get("element_type").getAsString(), f.getAsJsonArray("values")); break;
            case "sequence_begin": os.writeSequenceBegin(id); break;
            case "sequence_end":   os.writeSequenceEnd(); break;
            default: throw new IllegalArgumentException("unknown op " + op);
        }
    }

    private static void writeArray(OStream os, int id, String elemType, JsonArray values) throws IOException {
        int n = values.size();
        switch (elemType) {
            case "u8": case "i8": {
                byte[] a = new byte[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (byte) bigOf(values.get(i)).longValue();
                }
                if (elemType.charAt(0) == 'u') os.writeArrayUnsigned(id, a); else os.writeArraySigned(id, a);
                break;
            }
            case "u16": case "i16": {
                short[] a = new short[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (short) bigOf(values.get(i)).longValue();
                }
                if (elemType.charAt(0) == 'u') os.writeArrayUnsigned(id, a); else os.writeArraySigned(id, a);
                break;
            }
            case "u32": case "i32": {
                int[] a = new int[n];
                for (int i = 0; i < n; i++) {
                    a[i] = (int) bigOf(values.get(i)).longValue();
                }
                if (elemType.charAt(0) == 'u') os.writeArrayUnsigned(id, a); else os.writeArraySigned(id, a);
                break;
            }
            case "u64": case "i64": {
                long[] a = new long[n];
                for (int i = 0; i < n; i++) {
                    a[i] = bigOf(values.get(i)).longValue();
                }
                if (elemType.charAt(0) == 'u') os.writeArrayUnsigned(id, a); else os.writeArraySigned(id, a);
                break;
            }
            case "fp32": {
                float[] a = new float[n];
                for (int i = 0; i < n; i++) {
                    a[i] = toFloat(values.get(i));
                }
                os.writeArrayFp32(id, a);
                break;
            }
            case "fp64": {
                double[] a = new double[n];
                for (int i = 0; i < n; i++) {
                    a[i] = toDouble(values.get(i));
                }
                os.writeArrayFp64(id, a);
                break;
            }
            default: throw new IllegalArgumentException("unknown element_type " + elemType);
        }
    }

    // --- decode: feed serialized.hex and compare the event stream to fields[] ---

    private static void assertDecode(JsonObject v) throws SofabException {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        EventVisitor visitor = new EventVisitor();
        new IStream().feed(wire, visitor);
        assertEquals(expectedEvents(v.getAsJsonArray("fields")), visitor.events);
    }

    /** Feed {@code wire} through one decoder in fixed-size chunks; events must match a single feed. */
    private static void assertChunkedDecode(JsonObject v, int chunk) throws SofabException {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        EventVisitor visitor = new EventVisitor();
        IStream is = new IStream();
        for (int i = 0; i < wire.length; i += chunk) {
            is.feed(wire, i, Math.min(chunk, wire.length - i), visitor);
        }
        assertEquals(expectedEvents(v.getAsJsonArray("fields")), visitor.events);
    }

    /** Decode while ignoring the vector's skip_ids; the remaining fields must survive intact. */
    private static void assertSkipDecode(JsonObject v) throws SofabException {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        java.util.Set<Integer> skip = skipIdsOf(v);
        SkippingVisitor visitor = new SkippingVisitor(skip);
        new IStream().feed(wire, visitor);
        assertEquals(expectedEventsWithSkip(v.getAsJsonArray("fields"), skip), visitor.out.events);
    }

    /** Encode through a tiny buffer + flush sink; the streamed bytes must equal the one-shot wire. */
    private static void assertChunkedEncode(JsonObject v) throws IOException {
        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        FlushSink sink = collected::write;
        // 4-byte buffer forces a flush mid-field for nearly every vector.
        OStream os = new OStream(new byte[4], v.get("offset").getAsInt(), sink);
        for (JsonElement fe : v.getAsJsonArray("fields")) {
            replay(os, fe.getAsJsonObject());
        }
        os.flush();
        byte[] out = collected.toByteArray();
        assertEquals(v.getAsJsonObject("serialized").get("hex").getAsString(), hex(out, 0, out.length));
    }

    /**
     * Visitor that drops fields whose id is in {@code skip}, and — for a sequence
     * id in {@code skip} — the whole sub-tree beneath it, delegating everything
     * that survives to an {@link EventVisitor}. Mirrors how a real receiver ignores
     * optional fields using only the field header.
     */
    private static final class SkippingVisitor implements Visitor {
        final EventVisitor out = new EventVisitor();
        private final java.util.Set<Integer> skip;
        private int depth;
        private int skipUntil = -1; // depth of the skipped sub-tree's parent, or -1 if not skipping

        SkippingVisitor(java.util.Set<Integer> skip) {
            this.skip = skip;
        }

        private boolean keep(int id) {
            return skipUntil < 0 && !skip.contains(id);
        }

        @Override public void unsigned(int id, long v) { if (keep(id)) out.unsigned(id, v); }
        @Override public void signed(int id, long v) { if (keep(id)) out.signed(id, v); }
        @Override public void fp32(int id, float v) { if (keep(id)) out.fp32(id, v); }
        @Override public void fp64(int id, double v) { if (keep(id)) out.fp64(id, v); }
        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            if (keep(id)) out.string(id, total, offset, d, o, l);
        }
        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
            if (keep(id)) out.blob(id, total, offset, d, o, l);
        }
        @Override public void arrayBegin(int id, ArrayKind kind, int count) {
            if (keep(id)) out.arrayBegin(id, kind, count);
        }
        @Override public void sequenceBegin(int id) {
            if (skipUntil < 0 && skip.contains(id)) {
                skipUntil = depth; // begin skipping this whole sub-tree
            } else if (skipUntil < 0) {
                out.sequenceBegin(id);
            }
            depth++;
        }
        @Override public void sequenceEnd() {
            depth--;
            if (skipUntil >= 0) {
                if (depth == skipUntil) {
                    skipUntil = -1; // closed the skipped sub-tree
                }
                return;
            }
            out.sequenceEnd();
        }
    }

    /** Records every decoder callback as a normalized string event. */
    private static final class EventVisitor implements Visitor {
        final List<String> events = new ArrayList<>();
        // string/blob chunk reassembly
        private String pendKind;
        private int pendId;
        private int pendTotal;
        private byte[] pendBuf;

        @Override public void unsigned(int id, long value) { events.add("u:" + id + ":" + Long.toUnsignedString(value)); }
        @Override public void signed(int id, long value) { events.add("s:" + id + ":" + value); }
        @Override public void fp32(int id, float value) { events.add("f32:" + id + ":" + Float.floatToRawIntBits(value)); }
        @Override public void fp64(int id, double value) { events.add("f64:" + id + ":" + Double.doubleToRawLongBits(value)); }

        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            chunk("str", id, total, offset, d, o, l);
        }
        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
            chunk("blob", id, total, offset, d, o, l);
        }

        private void chunk(String kind, int id, int total, int offset, byte[] d, int o, int l) {
            if (pendKind == null) {
                pendKind = kind;
                pendId = id;
                pendTotal = total;
                pendBuf = new byte[total];
            }
            System.arraycopy(d, o, pendBuf, offset, l);
            if (offset + l >= pendTotal) {
                if (pendKind.equals("str")) {
                    events.add("str:" + pendId + ":" + new String(pendBuf, StandardCharsets.UTF_8));
                } else {
                    events.add("blob:" + pendId + ":" + hex(pendBuf, 0, pendBuf.length));
                }
                pendKind = null;
            }
        }

        @Override public void arrayBegin(int id, ArrayKind kind, int count) { events.add("arr:" + id + ":" + kind + ":" + count); }
        @Override public void sequenceBegin(int id) { events.add("seq{:" + id); }
        @Override public void sequenceEnd() { events.add("seq}"); }
    }

    /** Build the same normalized event list directly from the vector fields[]. */
    private static List<String> expectedEvents(JsonArray fields) {
        List<String> ev = new ArrayList<>();
        for (JsonElement fe : fields) {
            appendExpected(ev, fe.getAsJsonObject());
        }
        return ev;
    }

    /** Append the normalized event(s) one field would produce, matching {@link EventVisitor}. */
    private static void appendExpected(List<String> ev, JsonObject f) {
        String op = f.get("op").getAsString();
        int id = f.has("id") ? f.get("id").getAsInt() : 0;
        switch (op) {
            case "unsigned": ev.add("u:" + id + ":" + Long.toUnsignedString(f.get("value").getAsBigInteger().longValue())); break;
            case "signed":   ev.add("s:" + id + ":" + f.get("value").getAsLong()); break;
            case "boolean":  ev.add("u:" + id + ":" + (f.get("value").getAsBoolean() ? 1 : 0)); break;
            case "fp32":     ev.add("f32:" + id + ":" + Float.floatToRawIntBits(toFloat(f.get("value")))); break;
            case "fp64":     ev.add("f64:" + id + ":" + Double.doubleToRawLongBits(toDouble(f.get("value")))); break;
            case "string":   ev.add("str:" + id + ":" + f.get("value").getAsString()); break;
            case "blob":     ev.add("blob:" + id + ":" + f.get("value_hex").getAsString().toLowerCase()); break;
            case "array":    expectedArray(ev, id, f.get("element_type").getAsString(), f.getAsJsonArray("values")); break;
            case "sequence_begin": ev.add("seq{:" + id); break;
            case "sequence_end":   ev.add("seq}"); break;
            default: throw new IllegalArgumentException("unknown op " + op);
        }
    }

    /**
     * The expected event list once {@code skip} ids are removed: a skipped scalar
     * drops its single field, a skipped sequence id drops its entire sub-tree.
     * Mirrors {@link SkippingVisitor} exactly so the two can be compared.
     */
    private static List<String> expectedEventsWithSkip(JsonArray fields, java.util.Set<Integer> skip) {
        List<String> ev = new ArrayList<>();
        int depth = 0;
        int skipUntil = -1;
        for (JsonElement fe : fields) {
            JsonObject f = fe.getAsJsonObject();
            String op = f.get("op").getAsString();
            int id = f.has("id") ? f.get("id").getAsInt() : 0;
            switch (op) {
                case "sequence_begin":
                    if (skipUntil < 0 && skip.contains(id)) {
                        skipUntil = depth;
                    } else if (skipUntil < 0) {
                        ev.add("seq{:" + id);
                    }
                    depth++;
                    break;
                case "sequence_end":
                    depth--;
                    if (skipUntil >= 0) {
                        if (depth == skipUntil) {
                            skipUntil = -1;
                        }
                    } else {
                        ev.add("seq}");
                    }
                    break;
                default:
                    if (skipUntil < 0 && !skip.contains(id)) {
                        appendExpected(ev, f);
                    }
                    break;
            }
        }
        return ev;
    }

    private static java.util.Set<Integer> skipIdsOf(JsonObject v) {
        java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (v.has("skip_ids")) {
            for (JsonElement e : v.getAsJsonArray("skip_ids")) {
                ids.add(e.getAsInt());
            }
        }
        return ids;
    }

    private static List<String> requiresOf(JsonObject v) {
        List<String> caps = new ArrayList<>();
        if (v.has("requires")) {
            for (JsonElement e : v.getAsJsonArray("requires")) {
                caps.add(e.getAsString());
            }
        }
        return caps;
    }

    private static void expectedArray(List<String> ev, int id, String elemType, JsonArray values) {
        int n = values.size();
        // arrayBegin announces the wire category, not the declared width.
        ArrayKind kind = elemType.startsWith("fp") ? ArrayKind.FIXLEN
                : elemType.charAt(0) == 'u' ? ArrayKind.UNSIGNED : ArrayKind.SIGNED;
        ev.add("arr:" + id + ":" + kind + ":" + n);
        for (JsonElement el : values) {
            switch (elemType) {
                case "u8":  ev.add("u:" + id + ":" + Long.toUnsignedString(((byte) bigOf(el).longValue()) & 0xFFL)); break;
                case "u16": ev.add("u:" + id + ":" + Long.toUnsignedString(((short) bigOf(el).longValue()) & 0xFFFFL)); break;
                case "u32": ev.add("u:" + id + ":" + Long.toUnsignedString(((int) bigOf(el).longValue()) & 0xFFFFFFFFL)); break;
                case "u64": ev.add("u:" + id + ":" + Long.toUnsignedString(bigOf(el).longValue())); break;
                case "i8":  ev.add("s:" + id + ":" + (long) (byte) bigOf(el).longValue()); break;
                case "i16": ev.add("s:" + id + ":" + (long) (short) bigOf(el).longValue()); break;
                case "i32": ev.add("s:" + id + ":" + (long) (int) bigOf(el).longValue()); break;
                case "i64": ev.add("s:" + id + ":" + bigOf(el).longValue()); break;
                case "fp32": ev.add("f32:" + id + ":" + Float.floatToRawIntBits(toFloat(el))); break;
                case "fp64": ev.add("f64:" + id + ":" + Double.doubleToRawLongBits(toDouble(el))); break;
                default: throw new IllegalArgumentException("unknown element_type " + elemType);
            }
        }
    }

    // --- helpers ------------------------------------------------------------

    private static BigInteger bigOf(JsonElement e) {
        return e.getAsBigInteger();
    }

    /** A float value: a JSON number, or the literals "inf" / "-inf". */
    private static float toFloat(JsonElement e) {
        if (e.isJsonPrimitive() && ((JsonPrimitive) e).isString()) {
            return infValue(e.getAsString());
        }
        return (float) e.getAsDouble();
    }

    private static double toDouble(JsonElement e) {
        if (e.isJsonPrimitive() && ((JsonPrimitive) e).isString()) {
            return infValue(e.getAsString());
        }
        return e.getAsDouble();
    }

    private static float infValue(String s) {
        switch (s) {
            case "inf":  return Float.POSITIVE_INFINITY;
            case "-inf": return Float.NEGATIVE_INFINITY;
            default: throw new IllegalArgumentException("unexpected float literal " + s);
        }
    }

    private static String hex(byte[] b, int from, int to) {
        StringBuilder sb = new StringBuilder((to - from) * 2);
        for (int i = from; i < to; i++) {
            sb.append(Character.forDigit((b[i] >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b[i] & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] unhex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
