/*
 * SofaBuffers Java - conformance tests against the shared, language-agnostic
 * test vector suite (assets/test_vectors.json from the documentation repo).
 *
 * Per CORELIB_PLAN §7.1 the file is copied verbatim from corelib-c-cpp and never
 * edited here; §7.2 requires every corelib to replay these exact vectors for
 * encode and decode, chunked, and — for the vectors carrying `skip_ids` — with
 * those field ids left unread (item 7), one byte at a time as well. One dynamic
 * test is generated per vector per scenario, so a failure points at the specific
 * vector, and the run prints how many vectors and checks executed.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Decode.verdict;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class VectorConformanceTest {

    /**
     * Load the shared vectors.
     *
     * <p><b>Which column this repo asserts.</b> Every test here drives the
     * <em>primitive</em> encoder/decoder against {@code serialized} — the dense
     * image, the ground truth for the raw wire layer this library implements. The
     * sibling {@code serialized_sparse} column is deliberately <b>not</b> read, and
     * that is not a coverage gap: it is the MESSAGE_SPEC §2 sparse-canonical image,
     * produced by a <em>message</em> layer deciding per field whether a value
     * equals its declared default. This repo has no message layer and no schema
     * defaults, so it cannot produce that form and has nothing to compare against.
     * {@code serialized_sparse} is exercised by the <b>generator's</b> conformance
     * driver ({@code sofabgen}'s {@code tests/conformance/java/}), which generates
     * the message classes that own the defaults. The primitive-level half of §2 —
     * a contentless sequence costing zero bytes — is covered here by
     * {@code OStreamTest}'s lazy-framing cases instead.
     */
    private static JsonArray cachedVectors;

    private static synchronized JsonArray loadVectors() {
        if (cachedVectors != null) {
            return cachedVectors;
        }
        try (InputStream in = VectorConformanceTest.class.getResourceAsStream("/test_vectors.json")) {
            if (in == null) {
                throw new IllegalStateException("test_vectors.json not found on the test classpath");
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            assertEquals("sofabuffers-test-vectors", root.get("format").getAsString());
            // Only the `vectors` array is read here. The file's other top-level
            // blocks are driven by their own tests: `invalid_utf8` by
            // Utf8StrictTest / Utf8ChunkOffsetTest, and `sequence_growth`
            // (CORELIB_PLAN §7.2 item 8) by SequenceGrowthTest, whose cases are
            // keyed by a delivery sequence of element ids rather than by bytes and
            // so share no shape with a vector. A block this file does not read is
            // ignored rather than rejected, so re-copying a regenerated file never
            // fails on one.
            cachedVectors = root.getAsJsonArray("vectors");
            return cachedVectors;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * The wire capabilities this build provides — the {@code requires} tags a
     * vector may name and still run here. This corelib compiles every feature in
     * (it has no {@code SOFAB_DISABLE_*} switches), so the set is the whole
     * vocabulary and nothing is gated out. A port with feature-reduced builds
     * narrows this set per configuration, and {@link #perVector} then runs the
     * part of the matrix that configuration can represent instead of dropping the
     * file whole.
     */
    private static final Set<String> SUPPORTED_CAPS =
            Set.of("fixlen", "array", "sequence", "fp64", "int64");

    /** Individual comparisons executed; reported by {@link #reportWhatRan()}. */
    private static final AtomicInteger CHECKS = new AtomicInteger();

    /** Count one executed check — called before the assertion, so a failing one counts too. */
    private static void check() {
        CHECKS.incrementAndGet();
    }

    /**
     * State what actually ran. The shared suite is only comparable across ports if
     * each says how much of it it executed — the C runner prints its vector and
     * check counts, and this is the same line (surefire forwards it to the console,
     * so a CI log carries it).
     */
    @AfterAll
    static void reportWhatRan() {
        JsonArray vs = loadVectors();
        int gated = 0;
        int withSkipIds = 0;
        for (JsonElement ve : vs) {
            JsonObject v = ve.getAsJsonObject();
            if (!missingCaps(v).isEmpty()) {
                gated++;
            }
            if (v.has("skip_ids")) {
                withSkipIds++;
            }
        }
        System.out.println("[test_vectors] " + vs.size() + " vectors, " + gated
                + " gated out by requires, " + withSkipIds + " carrying skip_ids; "
                + CHECKS.get() + " checks executed");
    }

    /** The vector's {@code requires} tags this build cannot provide; empty when it runs here. */
    private static List<String> missingCaps(JsonObject v) {
        List<String> missing = new ArrayList<>();
        for (String cap : requiresOf(v)) {
            if (!SUPPORTED_CAPS.contains(cap)) {
                missing.add(cap);
            }
        }
        return missing;
    }

    /** One scenario applied to one vector. */
    @FunctionalInterface
    private interface VectorCase {
        void run(JsonObject vector) throws Exception;
    }

    /**
     * One dynamic test per vector that {@code applies} accepts, named
     * {@code scenario:vector} so a failure names both. A vector requiring a
     * capability this build lacks becomes an aborted test (reported as skipped)
     * carrying the missing tag — never a silently dropped one.
     */
    private static List<DynamicTest> perVector(String scenario,
                                               Predicate<JsonObject> applies,
                                               VectorCase body) {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            if (!applies.test(v)) {
                continue;
            }
            String name = scenario + ":" + v.get("name").getAsString();
            List<String> missing = missingCaps(v);
            if (!missing.isEmpty()) {
                tests.add(DynamicTest.dynamicTest(name + " [gated]",
                        () -> Assumptions.abort("vector requires " + missing
                                + ", which this build does not provide")));
                continue;
            }
            tests.add(DynamicTest.dynamicTest(name, () -> {
                check();
                body.run(v);
            }));
        }
        return tests;
    }

    @TestFactory
    List<DynamicTest> encodeVectors() {
        return perVector("encode", v -> true, VectorConformanceTest::assertEncode);
    }

    @TestFactory
    List<DynamicTest> decodeVectors() {
        return perVector("decode", v -> true, VectorConformanceTest::assertDecode);
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
        List<DynamicTest> tests = new ArrayList<>();
        for (int size : new int[] {1, 3, 7}) {
            final int chunk = size;
            tests.addAll(perVector("decode/" + chunk + "B", v -> true,
                    v -> assertChunkedDecode(v, chunk)));
        }
        return tests;
    }

    /**
     * Skip-IDs decode (test_vectors_README "Skip-IDs decoding"): for vectors that
     * carry a {@code skip_ids} list, a receiver that ignores those field ids — at
     * every nesting level, and for a sequence id the entire sub-tree under it —
     * must still recover every other field with its exact value, and the message
     * must be fully consumed. This is the §7.2 item 7 scenario, and since the
     * regenerated suite it runs the whole skip matrix: every (read type, skipped
     * type) pair, with an unsigned anchor after each skipped field that only
     * decodes correctly if the skip consumed exactly the right number of bytes.
     * Fields are only ever skipped when {@code skip_ids} is present.
     */
    @TestFactory
    List<DynamicTest> skipIdsDecodeVectors() {
        return perVector("skip", v -> v.has("skip_ids"), v -> assertSkipDecode(v, 0));
    }

    /**
     * The same skip scenario fed <b>one byte at a time</b>, so every skipped field
     * straddles chunk boundaries and the resync after it happens in the resumable
     * state machine rather than the contiguous fast path — the surface where a
     * skip that mis-counts its length hides from a single-buffer feed.
     */
    @TestFactory
    List<DynamicTest> skipIdsChunkedDecodeVectors() {
        return perVector("skip/1B", v -> v.has("skip_ids"), v -> assertSkipDecode(v, 1));
    }

    /**
     * Chunked encode (CORELIB_PLAN §7.2 item 4, encode side): replay every vector
     * into an encoder backed by a buffer far smaller than the message plus a flush
     * sink, forcing repeated mid-field flushes, and assert the streamed-out bytes
     * are identical to the one-shot encoding. Validates the encoder's
     * capacity/flush bookkeeping under the optimized contiguous-write path.
     */
    @TestFactory
    List<DynamicTest> chunkedEncodeVectors() {
        return perVector("encode/chunked", v -> true, VectorConformanceTest::assertChunkedEncode);
    }

    /**
     * {@code requires} awareness: a vector naming a capability this build lacks is
     * gated out by {@link #perVector} rather than failed. That only works while
     * every tag in the file is one this port has actually implemented, so guard
     * that the suite never introduces an unknown tag — an unrecognized capability
     * would otherwise be silently treated as "not supported" and quietly remove
     * vectors from the run. Runs for every vector, gated ones included.
     */
    @TestFactory
    List<DynamicTest> requiresTagsAreKnown() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            tests.add(DynamicTest.dynamicTest("requires:" + v.get("name").getAsString(), () -> {
                check();
                for (String cap : requiresOf(v)) {
                    assertTrue(SUPPORTED_CAPS.contains(cap), "unknown capability tag: " + cap);
                }
            }));
        }
        return tests;
    }

    /**
     * Nothing in this loader is fixed-size, and this pins that.
     *
     * <p>The C harness carried a fixed {@code MAXSKIP} that <em>truncated</em> an
     * over-long {@code skip_ids} list: the surplus ids were then read instead of
     * skipped, so the vector still passed while testing less than it claimed
     * (fixed upstream in corelib-c-cpp#160 — it refuses now). Java's loader hands
     * the parsed JSON model straight to each scenario, so there is no cap to
     * truncate against; the sizes the regenerated suite actually needs are read
     * back out of the loaded model here, so a bound introduced later — or a stale
     * vector file — fails loudly, by name, instead of quietly testing less.
     */
    @Test
    void theLoaderCarriesEveryVectorAtFullSize() {
        int maxSkipIds = 0;
        int maxFieldId = 0;
        int maxArrayCount = 0;
        int maxPayloadBytes = 0;
        int fp64Arrays = 0;
        int skipMatrixVectors = 0;
        for (JsonElement ve : loadVectors()) {
            JsonObject v = ve.getAsJsonObject();
            maxSkipIds = Math.max(maxSkipIds, skipIdsOf(v).size());
            if (v.has("group") && "skip/matrix".equals(v.get("group").getAsString())) {
                skipMatrixVectors++;
            }
            for (JsonElement fe : v.getAsJsonArray("fields")) {
                JsonObject f = fe.getAsJsonObject();
                if (f.has("id")) {
                    maxFieldId = Math.max(maxFieldId, f.get("id").getAsInt());
                }
                if (f.has("values")) {
                    maxArrayCount = Math.max(maxArrayCount, f.getAsJsonArray("values").size());
                    if ("fp64".equals(f.get("element_type").getAsString())) {
                        fp64Arrays++;
                    }
                }
                if (f.has("value_hex")) {
                    maxPayloadBytes = Math.max(maxPayloadBytes,
                            f.get("value_hex").getAsString().length() / 2);
                }
                if ("string".equals(f.get("op").getAsString())) {
                    maxPayloadBytes = Math.max(maxPayloadBytes,
                            f.get("value").getAsString().getBytes(StandardCharsets.UTF_8).length);
                }
            }
        }
        // Each bound below is a size the regenerated suite carries; a loader that
        // clipped any of them would run a smaller matrix without saying so.
        assertTrue(maxSkipIds >= 9,
                "skip_ids truncated: longest list loaded is " + maxSkipIds + ", the suite carries 9");
        assertTrue(maxFieldId >= 2147483647,
                "field id clipped: largest loaded is " + maxFieldId + ", the suite carries 2147483647");
        assertTrue(maxArrayCount >= 200,
                "array truncated: longest loaded is " + maxArrayCount + " elements, the suite carries 200");
        assertTrue(maxPayloadBytes >= 130,
                "payload truncated: longest loaded is " + maxPayloadBytes + " bytes, the suite carries 130");
        assertTrue(fp64Arrays > 0, "no fp64 array vector loaded (8-byte element length untested on the skip path)");
        assertTrue(skipMatrixVectors >= 36,
                "the skip matrix is missing: " + skipMatrixVectors + " vectors in group skip/matrix, expected 36");
    }

    // --- encode: replay fields[] and compare produced bytes to serialized.hex --

    private static void assertEncode(JsonObject v) throws IOException {
        int offset = v.get("offset").getAsInt();
        String expected = v.getAsJsonObject("serialized").get("hex").getAsString();
        // Sized from this vector, exactly: no fixed cap can silently cut a long
        // payload short, and an encoder writing one byte too many runs off the end
        // and fails loudly instead of landing in slack space.
        byte[] buf = new byte[Math.max(1, offset + expected.length() / 2)];
        OStream os = new OStream(buf, offset);
        for (JsonElement fe : v.getAsJsonArray("fields")) {
            replay(os, fe.getAsJsonObject());
        }
        assertEquals(expected, hex(buf, offset, os.bytesUsed()));
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
            // The vectors' `serialized` hex is the DENSE image, which always carries
            // the frame — including for the three empty-sequence vectors. Replaying
            // through the raw encoder therefore closes with the frame-keeping form:
            // writeSequenceEnd() would drop a contentless sequence and those vectors
            // would encode to nothing (MESSAGE_SPEC §2).
            case "sequence_begin": os.writeSequenceBeginLazy(id); break;
            case "sequence_end":   os.writeSequenceEndKeep(); break;
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

    private static void assertDecode(JsonObject v) {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        EventVisitor visitor = new EventVisitor();
        // "A" is the shared harness's accept verdict: no rejection, and the fed
        // bytes ended at a field boundary with every sequence closed — i.e. the
        // message was fully consumed, not merely parsed up to somewhere.
        assertEquals("A", verdict(wire, visitor, 0), "one-shot decode did not accept the message");
        assertEquals(expectedEvents(v.getAsJsonArray("fields")), visitor.events);
    }

    /** Feed {@code wire} through one decoder in fixed-size chunks; events must match a single feed. */
    private static void assertChunkedDecode(JsonObject v, int chunk) {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        EventVisitor visitor = new EventVisitor();
        assertEquals("A", verdict(wire, visitor, chunk),
                "chunked decode did not accept the message");
        assertEquals(expectedEvents(v.getAsJsonArray("fields")), visitor.events);
    }

    /**
     * Decode while ignoring the vector's {@code skip_ids} — at every nesting
     * level, and the whole sub-tree when the id names a sequence — and demand that
     * every remaining field still arrives with its exact value and the message is
     * fully consumed.
     *
     * <p><b>What "skip" means in this port.</b> The Java decoder is push-based:
     * there is no per-field skip call for a receiver to get wrong, because the
     * state machine walks every byte and a {@link Visitor} simply does not act on
     * the fields it does not want (see {@link Visitor} and {@code SkipTest}). So
     * the vectors' skip scenario lands on this decoder as: leave those ids unread
     * and check that the length computation for the skipped construct — varint,
     * fixlen word, element count, {@code count × element_length}, sequence end —
     * still put the following anchor field at the right offset. A skip that
     * consumed a byte too few or too many shows up exactly as it does in a
     * pull-style decoder: the anchor's value comparison fails.
     *
     * @param chunk 0 to feed the whole message at once, 1 to feed it a byte at a
     *              time so every skipped field straddles chunk boundaries
     */
    private static void assertSkipDecode(JsonObject v, int chunk) {
        byte[] wire = unhex(v.getAsJsonObject("serialized").get("hex").getAsString());
        Set<Integer> skip = skipIdsOf(v);
        SkippingVisitor visitor = new SkippingVisitor(skip);
        assertEquals("A", verdict(wire, visitor, chunk),
                "decode did not accept the message while skipping " + skip);
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
        private final Set<Integer> skip;
        private int depth;
        private int skipUntil = -1; // depth of the skipped sub-tree's parent, or -1 if not skipping

        SkippingVisitor(Set<Integer> skip) {
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
    private static List<String> expectedEventsWithSkip(JsonArray fields, Set<Integer> skip) {
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

    private static Set<Integer> skipIdsOf(JsonObject v) {
        Set<Integer> ids = new HashSet<>();
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
        // arrayBegin announces the wire element kind; a fixlen array names its
        // concrete subtype (§4.8), which for a well-formed vector is the declared one.
        ArrayKind kind = elemType.equals("fp32") ? ArrayKind.FP32
                : elemType.equals("fp64") ? ArrayKind.FP64
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
