/*
 * SofaBuffers Java - support layer: reassembly of a chunked payload.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * A chunk boundary is where this decoder's defects live: the split is decided by
 * the network, not by the message, so any rule applied to a payload has to hold
 * for every split of it. {@link PayloadAcc} is the piece generated code reassembles
 * through, and the method here is exhaustive rather than illustrative — every
 * payload below is offered at <b>every</b> split point {@code 0..n}, and every one
 * must produce the identical result.
 *
 * <p>That is also where the known coverage gap was: the shared {@code invalid_utf8}
 * vectors never drive a payload past its own length, so the rejection was only ever
 * exercised on a whole payload (corelib-java#96 / generator#345).
 */
class PayloadAccTest {

    /** "aä€𝄞z" — one, two, three and four-byte characters in one payload. */
    private static final String MIXED = "aä€𝄞z";

    /** Feed {@code payload} to a fresh accumulator, split at {@code at}. */
    private static String stringSplitAt(byte[] payload, int at) {
        PayloadAcc acc = new PayloadAcc();
        String first = acc.string(payload.length, 0, payload, 0, at, Bound.SCHEMA_BOUNDED);
        if (at >= payload.length) {
            return first;
        }
        assertNull(first, "a payload short of its total is not complete");
        return acc.string(payload.length, at, payload, at, payload.length - at, Bound.SCHEMA_BOUNDED);
    }

    /** As {@link #stringSplitAt}, for a blob. */
    private static byte[] blobSplitAt(byte[] payload, int at) {
        PayloadAcc acc = new PayloadAcc();
        byte[] first = acc.blob(payload.length, 0, payload, 0, at, Bound.SCHEMA_BOUNDED);
        if (at >= payload.length) {
            return first;
        }
        assertNull(first, "a payload short of its total is not complete");
        return acc.blob(payload.length, at, payload, at, payload.length - at, Bound.SCHEMA_BOUNDED);
    }

    // --- the split must not be observable ------------------------------------

    @Test
    void everySplitOfAStringYieldsTheSameValue() {
        byte[] payload = MIXED.getBytes(StandardCharsets.UTF_8);
        for (int at = 0; at <= payload.length; at++) {
            assertEquals(MIXED, stringSplitAt(payload, at), "split at " + at);
        }
    }

    @Test
    void everySplitOfABlobYieldsTheSameBytes() {
        byte[] payload = bytes(0x00, 0xFF, 0x41, 0x00, 0x7F);
        for (int at = 0; at <= payload.length; at++) {
            assertArrayEquals(payload, blobSplitAt(payload, at), "split at " + at);
        }
    }

    /**
     * The rejection too. A multi-byte character split across a boundary is
     * <em>not</em> the failure — validating per chunk would reject it — so the
     * verdict is taken once, on the reassembled payload.
     */
    @Test
    void everySplitOfAnInvalidStringIsRejectedAlike() {
        // A lone surrogate (ED A0 80), preceded and followed by ASCII so the split
        // lands inside it, before it and after it in turn.
        byte[] payload = bytes('h', 'i', 0xED, 0xA0, 0x80, 'z');
        for (int at = 0; at <= payload.length; at++) {
            int split = at;
            UncheckedIOException e = assertThrows(UncheckedIOException.class,
                    () -> stringSplitAt(payload, split), "split at " + split);
            assertEquals(SofabError.INVALID_MSG,
                    ((SofabException) e.getCause()).error(), "split at " + split);
        }
    }

    /** A valid character split across a boundary is valid, at every one of its bytes. */
    @Test
    void aCharacterSplitAcrossChunksIsNotAFailure() {
        byte[] payload = "𝄞".getBytes(StandardCharsets.UTF_8); // F0 9D 84 9E
        assertEquals(4, payload.length);
        for (int at = 0; at <= payload.length; at++) {
            assertEquals("𝄞", stringSplitAt(payload, at), "split at " + at);
        }
    }

    /** One byte at a time, the smallest split there is. */
    @Test
    void aPayloadArrivingOneByteAtATimeReassembles() {
        byte[] payload = MIXED.getBytes(StandardCharsets.UTF_8);
        PayloadAcc acc = new PayloadAcc();
        String out = null;
        for (int i = 0; i < payload.length; i++) {
            assertNull(out, "completed early at byte " + i);
            out = acc.string(payload.length, i, payload, i, 1, Bound.SCHEMA_BOUNDED);
        }
        assertEquals(MIXED, out);
    }

    /** Growth by doubling holds for a payload far past any initial buffer. */
    @Test
    void aLongPayloadReassemblesOneByteAtATime() {
        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ('a' + (i % 26));
        }
        PayloadAcc acc = new PayloadAcc();
        byte[] out = null;
        for (int i = 0; i < payload.length; i++) {
            out = acc.blob(payload.length, i, payload, i, 1, Bound.SCHEMA_BOUNDED);
        }
        assertArrayEquals(payload, out);
    }

    // --- what the accumulator refuses to do ----------------------------------

    /**
     * The adversarial case: {@code total} is the wire's claim, and a peer that
     * announces two gigabytes and sends three bytes must buy three bytes.
     */
    @Test
    void anAnnouncedTotalNearTwoToThe31AllocatesNothing() {
        PayloadAcc acc = new PayloadAcc();
        assertNull(acc.blob(Integer.MAX_VALUE, 0, bytes('a', 'b', 'c'), 0, 3, Bound.SCHEMA_BOUNDED));
        assertNull(acc.blob(Integer.MAX_VALUE, 3, bytes('d'), 0, 1, Bound.SCHEMA_BOUNDED));
    }

    /** A payload that never completed is dropped when the next one starts. */
    @Test
    void anAbandonedPayloadIsNotPrefixedOntoTheNext() {
        PayloadAcc acc = new PayloadAcc();
        assertNull(acc.string(9, 0, bytes('s', 't', 'a', 'l', 'e'), 0, 5, Bound.SCHEMA_BOUNDED));

        byte[] next = bytes('o', 'k');
        assertNull(acc.string(2, 0, next, 0, 1, Bound.SCHEMA_BOUNDED));
        assertEquals("ok", acc.string(2, 1, next, 1, 1, Bound.SCHEMA_BOUNDED));
    }

    /** The bytes handed back are the caller's; the input buffer is only borrowed. */
    @Test
    void theBlobHandedBackIsACopy() {
        byte[] input = bytes(1, 2, 3, 4);
        PayloadAcc acc = new PayloadAcc();
        assertNull(acc.blob(4, 0, input, 0, 2, Bound.SCHEMA_BOUNDED));
        byte[] out = acc.blob(4, 2, input, 2, 2, Bound.SCHEMA_BOUNDED);
        assertNotSame(input, out);
        Arrays.fill(input, (byte) 0);
        assertArrayEquals(bytes(1, 2, 3, 4), out, "the copy is not a view");

        byte[] whole = acc.blob(4, 0, bytes(9, 8, 7, 6), 0, 4, Bound.SCHEMA_BOUNDED);
        assertArrayEquals(bytes(9, 8, 7, 6), whole, "and so is the one-chunk answer");
    }

    /** An empty payload arrives as one chunk of nothing, and is a value. */
    @Test
    void anEmptyPayloadIsAValue() {
        PayloadAcc acc = new PayloadAcc();
        assertEquals("", acc.string(0, 0, Seq.EMPTY_BYTES, 0, 0, Bound.SCHEMA_BOUNDED));
        assertArrayEquals(Seq.EMPTY_BYTES, acc.blob(0, 0, Seq.EMPTY_BYTES, 0, 0, Bound.SCHEMA_BOUNDED));
    }

    /** The chunk lives at {@code chunkOffset} in the decoder's buffer, not at 0. */
    @Test
    void theChunkIsReadAtItsOffsetInTheInputBuffer() {
        byte[] input = bytes('x', 'x', 'h', 'i', 'x');
        PayloadAcc acc = new PayloadAcc();
        assertEquals("hi", acc.string(2, 0, input, 2, 2, Bound.SCHEMA_BOUNDED));

        assertNull(acc.blob(2, 0, input, 2, 1, Bound.SCHEMA_BOUNDED));
        assertArrayEquals(bytes('h', 'i'), acc.blob(2, 1, input, 3, 1, Bound.SCHEMA_BOUNDED));
    }

    /** One accumulator serves payload after payload, in either flavour. */
    @Test
    void oneAccumulatorServesPayloadAfterPayload() {
        PayloadAcc acc = new PayloadAcc();
        byte[] first = bytes('o', 'n', 'e');
        assertNull(acc.string(3, 0, first, 0, 2, Bound.SCHEMA_BOUNDED));
        assertEquals("one", acc.string(3, 2, first, 2, 1, Bound.SCHEMA_BOUNDED));

        byte[] second = bytes('t', 'w', 'o');
        assertNull(acc.blob(3, 0, second, 0, 1, Bound.SCHEMA_BOUNDED));
        assertArrayEquals(second, acc.blob(3, 1, second, 1, 2, Bound.SCHEMA_BOUNDED));

        byte[] third = bytes('s', 'i', 'x');
        assertNull(acc.string(3, 0, third, 0, 1, Bound.SCHEMA_BOUNDED));
        assertEquals("six", acc.string(3, 1, third, 1, 2, Bound.SCHEMA_BOUNDED));
    }

    // --- driven by the decoder, at every chunk size ---------------------------

    /**
     * The same guarantee end to end: a real message through {@link IStream}, fed
     * whole and then in one- and three-byte slices, with a visitor shaped the way
     * generated code is. The value must not depend on the feed size.
     */
    @Test
    void theSameMessageDecodesAlikeAtEveryFeedSize() throws SofabException {
        byte[] payload = MIXED.getBytes(StandardCharsets.UTF_8);
        byte[] blob = bytes(0xDE, 0xAD, 0xBE, 0xEF);
        byte[] message = concat(
                bytes(0x0A, (payload.length << 3) | 0x02), payload,   // id 1, string
                bytes(0x12, (blob.length << 3) | 0x03), blob);        // id 2, blob

        for (int chunk : List.of(0, 1, 3, 7)) {
            Collector v = new Collector();
            IStream in = new IStream();
            DecodeStatus after = null;
            if (chunk == 0) {
                after = in.feed(message, v);
            } else {
                for (int i = 0; i < message.length; i += chunk) {
                    after = in.feed(message, i, Math.min(chunk, message.length - i), v);
                }
            }
            assertEquals(DecodeStatus.COMPLETE, after, "chunk " + chunk);
            assertEquals(List.of(MIXED), v.strings, "chunk " + chunk);
            assertEquals(1, v.blobs.size(), "chunk " + chunk);
            assertArrayEquals(blob, v.blobs.get(0), "chunk " + chunk);
        }
    }

    /** A visitor holding one {@link PayloadAcc}, the shape generated code has. */
    private static final class Collector implements Visitor {
        final List<String> strings = new ArrayList<>();
        final List<byte[]> blobs = new ArrayList<>();
        private final PayloadAcc acc = new PayloadAcc();

        @Override
        public void string(int id, int total, int offset, byte[] data, int co, int cl) {
            String s = acc.string(total, offset, data, co, cl, Bound.SCHEMA_BOUNDED);
            if (s != null) {
                strings.add(s);
            }
        }

        @Override
        public void blob(int id, int total, int offset, byte[] data, int co, int cl) {
            byte[] b = acc.blob(total, offset, data, co, cl, Bound.SCHEMA_BOUNDED);
            if (b != null) {
                blobs.add(b);
            }
        }
    }
}
