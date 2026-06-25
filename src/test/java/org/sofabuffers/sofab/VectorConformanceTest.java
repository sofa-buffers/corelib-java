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
            JsonObject f = fe.getAsJsonObject();
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
        return ev;
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
