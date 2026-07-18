/*
 * SofaBuffers Java - strict UTF-8 encode tests (issue #85).
 *
 * MESSAGE_SPEC §8 / CORELIB_PLAN §6.4: a `string` field is always strict,
 * well-formed UTF-8. Java's `String` is a Unicode string type, so on the DECODE
 * side materialization (and therefore the strict-UTF-8 check) lives in generated
 * code, which decodes with a REPORTing CharsetDecoder that raises INVALID_MSG on
 * bad bytes. This corelib owns the ENCODE side: OStream.writeString must refuse a
 * String it cannot represent as well-formed UTF-8 (an unpaired UTF-16 surrogate)
 * with SofabError.ARGUMENT, and must never lossily substitute a replacement byte.
 *
 * The shared negative vectors (assets/test_vectors.json "invalid_utf8", tracking
 * corelib-c-cpp#97) are exercised two ways here: (1) their raw payload bytes are
 * fed to the same REPORTing UTF-8 decoder the generated code uses and must be
 * rejected (the decode-reject direction), and (2) the two lone-surrogate vectors
 * are mapped to a one-char String and must be refused by writeString (the
 * encode-reject direction). All other invalid_utf8 payloads are byte-level
 * malformations no Java String can ever hold, so they are only decode-reject.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
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

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /** The REPORTing UTF-8 decoder the generated Java decode path uses. */
    private static CharsetDecoder strictDecoder() {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
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

    /** Every invalid_utf8 payload must be rejected by the REPORTing UTF-8 decoder. */
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
                assertThrows(CharacterCodingException.class,
                        () -> strictDecoder().decode(ByteBuffer.wrap(payload)),
                        name + ": strict decoder must reject invalid UTF-8");
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

    /** Sanity: a valid String the strict decoder accepts also encodes cleanly. */
    @Test
    void strictDecoderAcceptsValidRoundTrip() throws Exception {
        String value = "a\u0000b äöü 😀";
        byte[] out = encode(os -> os.writeString(0, value));
        // Strip the 2-byte header (id+type, then the fixlen varint) and decode the
        // payload with the strict decoder to confirm it is well-formed UTF-8.
        byte[] payload = Arrays.copyOfRange(out, 2, out.length);
        CharBuffer decoded = strictDecoder().decode(ByteBuffer.wrap(payload));
        assertEquals(value, decoded.toString());
    }
}
