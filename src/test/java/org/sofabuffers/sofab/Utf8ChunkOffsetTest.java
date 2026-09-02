/*
 * SofaBuffers Java - strict UTF-8 validation of a string that sits PAST its own
 * length in the buffer.
 *
 * The shared invalid_utf8 vectors (assets/test_vectors.json, owned by
 * corelib-c-cpp#97) all put the string field at the very start of the message,
 * and the sink that drives them in Utf8StrictTest copies the payload into a fresh
 * array before validating it - so `chunkOffset` is 0 there and the validator is
 * always called as valid(payload, 0, length). At offset 0 a length passed where an
 * exclusive END index is required is indistinguishable from the correct call, and
 * that is a family-wide blind spot: a backend that validates the decoder's window
 * IN PLACE - the zero-copy shape, and what sofabgen's Java backend emits, since it
 * is what makes a string field cost no allocation - reads
 * [chunkOffset, chunkOffset + chunkLength). Get that argument wrong and every
 * malformed string is accepted the moment the field sits at a buffer offset at or
 * beyond its own length, with the whole conformance suite still green.
 *
 * These tests put the string field exactly there: each shared invalid_utf8 payload
 * is re-decoded behind enough padding that chunkOffset >= total, through a
 * zero-copy strict sink, and must still be rejected. The chunked half covers the
 * other direction of the same gap - an invalid sequence that arrives only in a
 * later chunk, at a payload offset beyond everything fed so far.
 *
 * CORELIB_PLAN §6.4 / MESSAGE_SPEC §8.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

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

class Utf8ChunkOffsetTest {

    /**
     * A zero-copy strict string sink, the shape generated code has: while the whole
     * declared payload is in hand it validates the decoder's window <em>in place</em>
     * over the caller's input array - no copy, no intermediate byte[] - and only a
     * payload split across feeds is accumulated first. Rejection is INVALID_MSG
     * wrapped in an {@link UncheckedIOException}, since {@link Visitor} declares no
     * checked exception.
     */
    private static final class ZeroCopyStrictStrings implements Visitor {
        /** Materialized strings, in arrival order. */
        final List<String> strings = new ArrayList<>();
        /** {@code {chunkOffset, total}} for every payload validated in place. */
        final List<int[]> windows = new ArrayList<>();

        private final ByteArrayOutputStream split = new ByteArrayOutputStream();

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            if (offset == 0 && chunkLength == total) {
                windows.add(new int[] {chunkOffset, total});
                validate(data, chunkOffset, chunkOffset + chunkLength);
                strings.add(new String(data, chunkOffset, chunkLength, StandardCharsets.UTF_8));
                return;
            }
            split.write(data, chunkOffset, chunkLength);
            if (offset + chunkLength < total) {
                return; // payload still arriving - not invalid, just not complete
            }
            byte[] payload = split.toByteArray();
            split.reset();
            validate(payload, 0, payload.length);
            strings.add(new String(payload, StandardCharsets.UTF_8));
        }

        private static void validate(byte[] b, int from, int end) {
            if (!Utf8.valid(b, from, end)) {
                throw new UncheckedIOException(
                        new SofabException(SofabError.INVALID_MSG, "string is not valid UTF-8"));
            }
        }

        /** Whether some payload was validated at an offset at or past its own length. */
        boolean sawFieldPastItsOwnLength() {
            for (int[] w : windows) {
                if (w[0] >= w[1]) {
                    return true;
                }
            }
            return false;
        }
    }

    // --- helpers ---------------------------------------------------------------

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    /**
     * A blob field (id 7) carrying {@code n} filler bytes: harmless, well-formed
     * padding whose only job is to push whatever follows it deep into the buffer.
     */
    private static byte[] padding(int n) throws IOException {
        byte[] buf = new byte[n + 16];
        OStream os = new OStream(buf);
        os.writeBlob(7, new byte[n]);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /** {@code field} preceded by padding, so its payload starts far from offset 0. */
    private static byte[] late(byte[] field) throws IOException {
        return concat(padding(32), field);
    }

    private static JsonArray loadInvalidUtf8() {
        try (InputStream in = Utf8ChunkOffsetTest.class.getResourceAsStream("/test_vectors.json")) {
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

    // --- the gap: chunkOffset >= total, validated in place ----------------------

    /**
     * Every shared invalid_utf8 vector, re-decoded with its string field pushed past
     * its own length in the buffer. The one-shot leg is the one the vectors never
     * reach: the payload is validated in place at a high {@code chunkOffset}, which
     * is asserted to have actually happened, so the case cannot quietly stop testing
     * what it is here for.
     */
    @TestFactory
    List<DynamicTest> invalidUtf8RejectedWithTheFieldPastItsOwnLength() {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonElement ve : loadInvalidUtf8()) {
            JsonObject v = ve.getAsJsonObject();
            String name = v.get("name").getAsString();
            byte[] field = hex(v.get("serialized_hex").getAsString());
            tests.add(DynamicTest.dynamicTest("late-field:" + name, () -> {
                byte[] wire = late(field);

                // One shot: the whole payload is in hand, so the sink validates the
                // decoder's window without copying it.
                IStream is = new IStream();
                ZeroCopyStrictStrings sink = new ZeroCopyStrictStrings();
                UncheckedIOException e = assertThrows(UncheckedIOException.class,
                        () -> is.feed(wire, sink),
                        name + ": an invalid string must be rejected wherever it sits");
                assertEquals(SofabError.INVALID_MSG, ((SofabException) e.getCause()).error());
                assertRejectsAgain(is, name + ": the rejection is terminal");
                assertTrue(sink.sawFieldPastItsOwnLength(),
                        name + ": this case only closes the gap if chunkOffset >= total");
                assertEquals(List.of(), sink.strings, name + ": nothing may be materialized");

                // One byte at a time: the payload straddles every boundary there is,
                // so the same bytes go through the accumulating leg instead.
                IStream chunked = new IStream();
                ZeroCopyStrictStrings chunkedSink = new ZeroCopyStrictStrings();
                assertThrows(UncheckedIOException.class, () -> {
                    for (byte b : wire) {
                        chunked.feed(new byte[] {b}, chunkedSink);
                    }
                }, name + ": chunking must not change the verdict");
                assertRejectsAgain(chunked, name + ": the rejection is terminal, chunked too");
            }));
        }
        assertTrue(tests.size() >= 11, "expected the shared invalid_utf8 vectors");
        return tests;
    }

    /**
     * The positive control for the case above: at the very same position a
     * well-formed multi-byte payload must be accepted and materialize exactly. Without
     * it, a sink that rejected everything would pass the negative half.
     */
    @Test
    void validUtf8AcceptedAtTheSameLateOffset() throws Exception {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeString(0, "äö😀");
        byte[] wire = late(Arrays.copyOf(buf, os.bytesUsed()));

        IStream is = new IStream();
        ZeroCopyStrictStrings sink = new ZeroCopyStrictStrings();

        assertEquals(DecodeStatus.COMPLETE, is.feed(wire, sink));
        assertEquals(List.of("äö😀"), sink.strings);
        assertTrue(sink.sawFieldPastItsOwnLength(),
                "the control must sit where the negative cases sit");
    }

    /**
     * Why the vectors alone cannot catch an offset-blind validator: {@link Utf8#valid}
     * takes an exclusive END index, and handed a LENGTH instead it inspects no bytes
     * at all once the field sits past its own length - reporting a malformed payload
     * as valid. Pinning both calls here states the contract the sink above depends on.
     */
    @Test
    void validTakesAnExclusiveEndIndexNotALength() throws Exception {
        byte[] wire = late(hex("0212c080"));           // string field, payload C0 80
        int payloadAt = wire.length - 2;
        assertTrue(payloadAt >= 2, "the payload must start past its own length");

        assertFalse(Utf8.valid(wire, payloadAt, payloadAt + 2),
                "C0 80 is the overlong NUL and must be rejected");
        assertTrue(Utf8.valid(wire, payloadAt, 2),
                "a length passed as the end index covers an empty range - the false negative "
                        + "that a green conformance run cannot see");
    }

    // --- the chunked half: the bad bytes arrive later than everything fed --------

    /**
     * A multi-byte sequence cut in half by a chunk boundary is still ARRIVING, not
     * invalid (§5.2): the validation happens once, on payload completion. Fed one
     * byte at a time, every sequence in this string is split.
     */
    @Test
    void aSequenceSplitAcrossEveryBoundaryIsNotRejected() throws Exception {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeString(1, "a€😀b");
        byte[] wire = Arrays.copyOf(buf, os.bytesUsed());

        IStream is = new IStream();
        ZeroCopyStrictStrings sink = new ZeroCopyStrictStrings();
        DecodeStatus after = null;
        for (int i = 0; i < wire.length; i++) {
            final int at = i;
            // Not malformed: a split byte returns an outcome instead of throwing,
            // which is the only way INVALID could be reported.
            after = assertDoesNotThrow(() -> is.feed(wire, at, 1, sink),
                    "a sequence split at byte " + i + " is incomplete, not malformed");
        }

        assertEquals(DecodeStatus.COMPLETE, after);
        assertEquals(List.of("a€😀b"), sink.strings);
    }

    /**
     * The invalid sequence starts at a payload offset beyond every byte fed so far:
     * the first feed carries only well-formed bytes and must be accepted as
     * INCOMPLETE, and the rejection lands on the feed that completes the payload.
     */
    @Test
    void anInvalidSequenceArrivingOnlyInALaterChunkIsRejectedAtCompletion() throws Exception {
        // string field id 1, payload "abcd" + C0 80 (6 bytes): 0x0A header,
        // fixlen_word (6 << 3) | STRING.
        byte[] wire = concat(
                bytes(0x0A, (6 << 3) | FixlenType.STRING.raw()),
                "abcd".getBytes(StandardCharsets.UTF_8),
                bytes(0xC0, 0x80));

        IStream is = new IStream();
        ZeroCopyStrictStrings sink = new ZeroCopyStrictStrings();

        // everything but the bad pair
        assertEquals(DecodeStatus.INCOMPLETE, is.feed(wire, 0, wire.length - 2, sink),
                "well-formed bytes so far - the verdict cannot be INVALID yet");
        assertEquals(List.of(), sink.strings);

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> is.feed(wire, wire.length - 2, 2, sink));
        assertEquals(SofabError.INVALID_MSG, ((SofabException) e.getCause()).error());
        assertRejectsAgain(is, "an invalid payload is terminal");
    }

    /**
     * A string nobody materializes is never validated (§6.4): the corelib streams the
     * payload and does no per-byte work, so a skipped field with malformed bytes
     * decodes to COMPLETE. That is what makes skipping a pure length jump - and it is
     * why the check above has to live in the sink.
     */
    @Test
    void aSkippedStringIsNotValidated() throws Exception {
        byte[] wire = late(hex("0212c080"));

        IStream is = new IStream();

        assertEquals(DecodeStatus.COMPLETE, is.feed(wire, new Visitor() { }));
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
