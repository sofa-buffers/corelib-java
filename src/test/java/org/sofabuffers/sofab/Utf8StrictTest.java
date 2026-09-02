/*
 * SofaBuffers Java - strict UTF-8 encode tests (issue #85).
 *
 * MESSAGE_SPEC §8 / CORELIB_PLAN §6.4: a `string` field is always strict,
 * well-formed UTF-8. Java's `String` is a Unicode string type, so on the DECODE
 * side materialization (and therefore the strict-UTF-8 check) lives in generated
 * code, which validates the assembled payload with this corelib's own Utf8.valid
 * and raises INVALID_MSG on bad bytes. This corelib owns the ENCODE side:
 * OStream.writeString must refuse a String it cannot represent as well-formed
 * UTF-8 (an unpaired UTF-16 surrogate) with SofabError.ARGUMENT, and must never
 * lossily substitute a replacement byte.
 *
 * The shared negative vectors (assets/test_vectors.json "invalid_utf8", tracking
 * corelib-c-cpp#97) are exercised three ways here: (1) their raw payload bytes are
 * put through the same validator the generated code calls and must be
 * rejected (the decode-reject direction), (2) the two lone-surrogate vectors are
 * mapped to a one-char String and must be refused by writeString, and (3) every
 * payload — including the byte-level malformations no Java String can ever hold —
 * is offered to the byte-container entry point OStream.writeFixlen(..., STRING),
 * which must refuse it too (issue #73): both are the encode-reject direction the
 * vectors' `encode_outcome: "invalid_argument"` asks for.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class Utf8StrictTest {

    @FunctionalInterface
    private interface EncodeBody {
        void run(OStream os) throws IOException;
    }

    /** Encode via {@code body} into a fresh buffer and return exactly the used bytes. */
    private static byte[] encode(EncodeBody body) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        body.run(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    // ---- encode-reject: unpaired surrogates -----------------------------------

    private static void assertEncodeRejects(String text) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            byte[] buf = new byte[64];
            OStream os = new OStream(buf);
            os.writeString(0, text);
            // Must never reach here: an unpaired surrogate is not encodable.
            assertEquals(0, os.bytesUsed(), "no bytes may be emitted for an invalid string");
        });
        assertEquals(SofabError.ARGUMENT, ex.error(),
                "unpaired surrogate must raise the invalid-argument category");
    }

    @Test
    void loneHighSurrogateRejected() {
        assertEncodeRejects("\uD800");
    }

    @Test
    void loneLowSurrogateRejected() {
        assertEncodeRejects("\uDFFF");
    }

    @Test
    void highSurrogateNotFollowedByLowRejected() {
        assertEncodeRejects("a\uD800b");
    }

    @Test
    void highSurrogateAtEndRejected() {
        assertEncodeRejects("tail\uD83D");
    }

    @Test
    void lowSurrogateAtStartRejected() {
        assertEncodeRejects("\uDC00head");
    }

    @Test
    void twoHighSurrogatesRejected() {
        assertEncodeRejects("\uD800\uD800");
    }

    /** A rejected write must leave the stream untouched (no partial field). */
    @Test
    void rejectedWriteEmitsNoBytes() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        assertThrows(SofabException.class, () -> os.writeString(3, "x\uD800"));
        assertEquals(0, os.bytesUsed());
    }

    // ---- valid strings still encode byte-identically --------------------------

    @Test
    void asciiUnchanged() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x62, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x43, 0x6F, 0x75, 0x63, 0x68, 0x21),
                encode(os -> os.writeString(0, "Hello Couch!")));
    }

    @Test
    void multiByteUnchanged() throws IOException {
        // "äöüÄÖÜß" -> 14 UTF-8 bytes; header (14<<3)|STRING = 0x72. Matches the
        // shared positive vector (id 3 there; id 0 here).
        assertArrayEquals(
                bytes(0x02, 0x72, 0xC3, 0xA4, 0xC3, 0xB6, 0xC3, 0xBC,
                        0xC3, 0x84, 0xC3, 0x96, 0xC3, 0x9C, 0xC3, 0x9F),
                encode(os -> os.writeString(0, "äöüÄÖÜß")));
    }

    @Test
    void astralPairEncodesAsFourBytes() throws IOException {
        // U+1F600 GRINNING FACE = surrogate pair D83D DE00 -> F0 9F 98 80.
        assertArrayEquals(
                bytes(0x02, 0x22, 0xF0, 0x9F, 0x98, 0x80),
                encode(os -> os.writeString(0, "😀")));
    }

    // ---- embedded U+0000 is a valid code point and must round-trip ------------

    @Test
    void embeddedNulEncodesAsSingleZeroByte() throws IOException {
        // "a\u0000b" -> 61 00 62 (3 bytes); header (3<<3)|STRING = 0x1A. This is
        // the correct single-byte NUL, never the "modified UTF-8" overlong C0 80.
        assertArrayEquals(
                bytes(0x02, 0x1A, 0x61, 0x00, 0x62),
                encode(os -> os.writeString(0, "a\u0000b")));
    }

    @Test
    void embeddedNulRoundTrips() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeString(0, "a\u0000b");

        List<String> texts = new ArrayList<>();
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
                // The corelib delivers raw bytes; generated code materializes the
                // String. Reconstruct the same way to confirm value preservation.
                texts.add(new String(d, o, l, StandardCharsets.UTF_8));
            }
        });
        assertEquals(List.of("a\u0000b"), texts);
    }

    // ---- shared negative vectors (assets/test_vectors.json "invalid_utf8") -----

    private static JsonArray loadInvalidUtf8() {
        try (InputStream in = Utf8StrictTest.class.getResourceAsStream("/test_vectors.json")) {
            if (in == null) {
                throw new IllegalStateException("test_vectors.json not found on the test classpath");
            }
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            return root.getAsJsonArray("invalid_utf8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Every invalid_utf8 payload must be rejected by the validator generated code calls. */
    @TestFactory
    List<DynamicTest> invalidUtf8DecodeRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadInvalidUtf8()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            tests.add(DynamicTest.dynamicTest("decode-reject:" + name, () -> {
                assertEquals("invalid", v.get("decode_outcome").getAsString());
                assertEquals("invalid_argument", v.get("encode_outcome").getAsString());
                byte[] payload = hex(v.get("string_hex").getAsString());
                assertFalse(Utf8.valid(payload, 0, payload.length),
                        name + ": the strict validator must reject invalid UTF-8");
            }));
        }
        assertTrue(tests.size() >= 11, "expected the shared invalid_utf8 vectors");
        return tests;
    }

    /**
     * A strict string sink, exactly as generated code builds one: it accumulates
     * the chunks of the string field and validates the assembled bytes with
     * {@link Utf8#valid} once the declared payload is complete — a multi-byte
     * sequence merely split at a chunk boundary is INCOMPLETE, not INVALID —
     * raising INVALID_MSG wrapped in an {@link UncheckedIOException}, since
     * {@link Visitor} declares no checked exception.
     */
    private static final class StrictStringSink implements Visitor {
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            buf.write(data, chunkOffset, chunkLength);
            if (offset + chunkLength < total) {
                return; // payload still arriving
            }
            byte[] payload = buf.toByteArray();
            buf.reset();
            if (!Utf8.valid(payload, 0, payload.length)) {
                throw new UncheckedIOException(
                        new SofabException(SofabError.INVALID_MSG, "string is not valid UTF-8"));
            }
        }
    }

    /**
     * The vectors' {@code decode_outcome: "invalid"} is a statement about the whole
     * message, so assert it where the outcome actually lives: feed
     * {@code serialized_hex} to an {@link IStream} driving the strict sink above
     * and read the decoder's verdict. That verdict is {@link DecodeStatus#INVALID},
     * which never comes back as a return value — the decode throws
     * {@link SofabError#INVALID_MSG} — and it is terminal: a further feed of a
     * well-formed field decodes nothing and cannot restore {@code COMPLETE}
     * (corelib-java#71). Checked whole and one byte at a time: the verdict must not
     * depend on how the payload was chunked.
     */
    @TestFactory
    List<DynamicTest> invalidUtf8DecodeOutcomeIsInvalidAndTerminal() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadInvalidUtf8()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            byte[] wire = hex(v.get("serialized_hex").getAsString());
            tests.add(DynamicTest.dynamicTest("decode-outcome:" + name, () -> {
                assertEquals("invalid", v.get("decode_outcome").getAsString());
                for (int chunk : new int[] {wire.length, 1}) {
                    IStream is = new IStream();
                    StrictStringSink sink = new StrictStringSink();
                    assertThrows(UncheckedIOException.class, () -> {
                        for (int i = 0; i < wire.length; i += chunk) {
                            is.feed(wire, i, Math.min(chunk, wire.length - i), sink);
                        }
                    }, name + ": strict decode must reject the message");
                    assertRejectsAgain(is,
                            name + ": the decode outcome is INVALID (chunk " + chunk + ")");

                    // Terminal: well-formed bytes fed afterwards change nothing.
                    List<String> events = new ArrayList<>();
                    Visitor record = new Visitor() {
                        @Override
                        public void unsigned(int id, long value) {
                            events.add(id + "=" + value);
                        }
                    };
                    assertThrows(SofabException.class,
                            () -> is.feed(new byte[] {0x08, 0x01}, record));
                    assertEquals(List.of(), events);
                }
            }));
        }
        assertTrue(tests.size() >= 11, "expected the shared invalid_utf8 vectors");
        return tests;
    }

    /**
     * The two lone-surrogate vectors map to a single-char Java String; writeString
     * must refuse them with ARGUMENT (the encode-reject direction of the vectors).
     */
    @TestFactory
    List<DynamicTest> invalidUtf8SurrogateEncodeRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadInvalidUtf8()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            if (!name.startsWith("utf8_surrogate_")) {
                continue; // only lone surrogates are representable as a Java String
            }
            char surrogate = (char) Integer.parseInt(name.substring("utf8_surrogate_".length()), 16);
            tests.add(DynamicTest.dynamicTest("encode-reject:" + name,
                    () -> assertEncodeRejects(String.valueOf(surrogate))));
        }
        assertEquals(2, tests.size(), "expected the two lone-surrogate vectors");
        return tests;
    }

    /**
     * The byte-container encode direction: {@code writeFixlen(..., STRING)} takes
     * raw bytes, so every invalid_utf8 payload — not just the two representable as
     * a Java String — must be refused there with ARGUMENT, matching the vectors'
     * {@code encode_outcome: "invalid_argument"}, with no bytes emitted. Offering
     * the same payload as a BLOB must still succeed: the check is on the string
     * sub-type only (issue #73).
     */
    @TestFactory
    List<DynamicTest> invalidUtf8FixlenStringEncodeRejected() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadInvalidUtf8()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            byte[] payload = hex(v.get("string_hex").getAsString());
            tests.add(DynamicTest.dynamicTest("encode-reject-fixlen:" + name, () -> {
                assertEquals("invalid_argument", v.get("encode_outcome").getAsString());
                assertTrue(!Utf8.valid(payload, 0, payload.length), name + ": vector must be invalid UTF-8");

                byte[] buf = new byte[64];
                OStream os = new OStream(buf);
                SofabException ex = assertThrows(SofabException.class,
                        () -> os.writeFixlen(1, payload, 0, payload.length, FixlenType.STRING),
                        name + ": writeFixlen must refuse an invalid UTF-8 string payload");
                assertEquals(SofabError.ARGUMENT, ex.error());
                assertEquals(0, os.bytesUsed(), name + ": a refused string must emit no bytes");

                // Same bytes as an opaque blob: accepted, and byte-identical to the
                // payload after the two header bytes.
                os.writeFixlen(1, payload, 0, payload.length, FixlenType.BLOB);
                assertArrayEquals(payload,
                        Arrays.copyOfRange(buf, 2, os.bytesUsed()),
                        name + ": blob payload must pass through verbatim");
            }));
        }
        assertTrue(tests.size() >= 11, "expected the shared invalid_utf8 vectors");
        return tests;
    }

    /** Sanity: a valid String the strict validator accepts also encodes cleanly. */
    @Test
    void strictValidatorAcceptsValidRoundTrip() throws Exception {
        String value = "a\u0000b äöü 😀";
        byte[] out = encode(os -> os.writeString(0, value));
        // Strip the 2-byte header (id+type, then the fixlen varint); the payload must
        // pass the validator generated code runs, and materialize back to the input.
        byte[] payload = Arrays.copyOfRange(out, 2, out.length);
        assertTrue(Utf8.valid(payload, 0, payload.length),
                "a String this corelib encoded must be well-formed UTF-8");
        assertEquals(value, new String(payload, StandardCharsets.UTF_8));
    }

    /**
     * The verdict after a rejection, read where it now lives. INVALID travels on the
     * error channel, and it is terminal (§5.2): the decoder decodes nothing further
     * and throws the same code again, whatever it is fed. The probe message is a
     * clean {@code unsigned id 0 = 42}, so the throw can only come from the latch.
     */
    private static void assertRejectsAgain(IStream is, String message) {
        List<String> seen = new ArrayList<>();
        Visitor probe = new Visitor() {
            @Override
            public void unsigned(int id, long value) {
                seen.add(id + "=" + value);
            }
        };
        SofabException e = assertThrows(SofabException.class,
                () -> is.feed(bytes(0x00, 0x2A), probe), message);
        assertEquals(SofabError.INVALID_MSG, e.error(), message);
        assertEquals(List.of(), seen, message);
    }
}
