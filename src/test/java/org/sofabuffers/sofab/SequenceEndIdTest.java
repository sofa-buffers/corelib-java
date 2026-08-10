/*
 * SofaBuffers Java - a sequence-end header's id is discarded, but still bounded.
 *
 * CORELIB_PLAN §4.9 makes the marker asymmetric: an encoder MUST emit a sequence
 * end as exactly 0x07, while a decoder MUST discard the id - the marker closes the
 * innermost open sequence whatever the id says. Discarded is not unvalidated: §6.2
 * binds the id of *every* field header by ID_MAX, sequence end included, with
 * deliberately no exception for wire type 7. The bound is on the id's value, not on
 * its spelling, so a non-minimal encoding of an in-range id stays acceptable under
 * §4.1 and re-encodes as 0x07 like any other non-minimal varint.
 *
 * §7.2 asks for both directions, and specifically for the oversized id on a
 * sequence-end header rather than only on a value-bearing one: an implementation
 * that validates the id in the branches that *use* it passes the value-bearing case
 * and misses this one. Both decode surfaces are covered, because the check lives on
 * both: the contiguous fast path in IStream.feed and the resumable state machine
 * reached at a chunk boundary (§6.5 - "a guard added to one surface but not
 * another").
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sofabuffers.sofab.common.Decode.errorOf;
import static org.sofabuffers.sofab.common.Decode.errorOfChunked;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class SequenceEndIdTest {

    /** One past ID_MAX: rejected on an end marker exactly as anywhere else. */
    private static final long OVER_ID_MAX = 1L << 31;

    /** The header varint {@code (id << 3) | wireType}, minimally encoded. */
    private static byte[] header(long id, int wireType) {
        long v = (id << 3) | wireType;
        byte[] out = new byte[10];
        int n = 0;
        while ((v & ~0x7FL) != 0) {
            out[n++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out[n++] = (byte) v;
        return Arrays.copyOf(out, n);
    }

    private static List<String> decode(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream is = new IStream();
        is.feed(data, v);
        assertEquals(DecodeStatus.COMPLETE, is.status());
        return v.events;
    }

    /** Feed one byte at a time, so every header is reassembled by the state machine. */
    private static List<String> decodeByteByByte(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream is = new IStream();
        for (byte b : data) {
            is.feed(new byte[] {b}, v);
        }
        assertEquals(DecodeStatus.COMPLETE, is.status());
        return v.events;
    }

    // --- the ceiling binds a sequence-end header too (§6.2) -------------------

    @Test
    void headerHelperMatchesTheIsolateBytes() {
        // 76: id 14, wire type SequenceStart. 87 80 80 80 40: wire type 7, id 2^31.
        assertArrayEquals(bytes(0x76), header(14, WireFormat.T_SEQUENCE_START));
        assertArrayEquals(bytes(0x87, 0x80, 0x80, 0x80, 0x40), header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END));
    }

    @Test
    void overIdMaxIdOnSequenceEndRejected() {
        // An unknown id opened as a sequence, closed by an over-ceiling end marker.
        byte[] isolate = bytes(0x76, 0x87, 0x80, 0x80, 0x80, 0x40);
        assertEquals(SofabError.INVALID_MSG, errorOf(isolate));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(isolate));
    }

    @Test
    void largestPossibleIdOnSequenceEndRejected() {
        // The id sub-field of a 10-byte header varint tops out at 2^61 - 1. §4.1's
        // 64-bit bound lets that varint through; §6.2's ceiling is what rejects it.
        byte[] msg = concat(bytes(0x0E), header((1L << 61) - 1, WireFormat.T_SEQUENCE_END));
        assertEquals(SofabError.INVALID_MSG, errorOf(msg));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(msg));
    }

    @Test
    void overIdMaxIdOnNestedSequenceEndRejected() {
        // The inner closing marker of a nested run: rejected at the depth it sits at.
        byte[] end = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END);
        byte[] msg = concat(bytes(0x0E, 0x16), bytes(0x00, 0x2A), end, bytes(0x07));
        assertEquals(SofabError.INVALID_MSG, errorOf(msg));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(msg));
    }

    @Test
    void danglingSequenceEndRejectedWhateverItsId() {
        // §5.2's structural sequence-end condition: no open sequence.
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x07)));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bytes(0x07)));
        byte[] dangling = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END);
        assertEquals(SofabError.INVALID_MSG, errorOf(dangling));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(dangling));
    }

    @Test
    void overlongHeaderVarintRejectedOnSequenceEnd() {
        // §4.1 bounds the header encoding: 11 continuation bytes overflow the
        // 64-bit varint whatever wire type they would have decoded to.
        byte[] bad = bytes(0x0E, 0x87, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    // --- the same ceiling on a value-bearing header --------------------------

    @Test
    void overIdMaxIdOnUnsignedRejected() {
        byte[] bad = concat(header(OVER_ID_MAX, WireFormat.T_VARINT_UNSIGNED), bytes(0x00));
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    @Test
    void overIdMaxIdOnUnsignedInsideSkippedSubtreeRejected() {
        // Same header one level down, inside the unknown sequence the corelib
        // walks without any schema knowledge.
        byte[] bad = concat(bytes(0x76), header(OVER_ID_MAX, WireFormat.T_VARINT_UNSIGNED), bytes(0x00, 0x07));
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    @Test
    void overIdMaxIdOnSequenceStartRejected() {
        byte[] bad = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_START);
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    // --- tolerance: an in-range id, however spelled (§4.9, §4.1) -------------

    @Test
    void canonicalSequenceEndAccepted() throws SofabException {
        byte[] msg = bytes(0x0E, 0x07);
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void nonZeroIdWithinIdMaxOnSequenceEndAccepted() throws SofabException {
        // §7.2's tolerance case: the id is discarded, so an id of 3 closes the
        // sequence exactly as 0 does.
        byte[] msg = concat(bytes(0x0E), header(3, WireFormat.T_SEQUENCE_END));
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void idAtIdMaxOnSequenceEndAccepted() throws SofabException {
        // The ceiling itself is in range - the boundary the rejection tests sit
        // one past.
        byte[] msg = concat(bytes(0x0E), header(WireFormat.ID_MAX, WireFormat.T_SEQUENCE_END));
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void nonMinimalSpellingOfIdZeroAccepted() throws SofabException {
        // §4.9 names this one: 87 00 is id 0, wire type 7, written in two bytes
        // where one would do. The bound is on the value, not on the spelling.
        byte[] msg = bytes(0x0E, 0x87, 0x00);
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    // --- re-encode ----------------------------------------------------------

    /** Re-encodes what it decodes, so the output is this encoder's canonical form. */
    private static final class Reencoder implements Visitor {
        private final OStream os;

        Reencoder(OStream os) {
            this.os = os;
        }

        @Override
        public void unsigned(int id, long value) {
            try {
                os.writeUnsigned(id, value);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void sequenceBegin(int id) {
            try {
                os.writeSequenceBeginLazy(id);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void sequenceEnd() {
            try {
                os.writeSequenceEndKeep();
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }

    private static byte[] reencode(byte[] data) throws SofabException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        new IStream().feed(data, new Reencoder(os));
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    @Test
    void inRangeEndMarkerReencodesAsCanonicalSeven() throws SofabException {
        // §4.9: an accepted end marker comes back out as the single byte 0x07,
        // whatever id it carried and however that id was spelled.
        byte[] in = concat(bytes(0x76), bytes(0x00, 0x2A), header(3, WireFormat.T_SEQUENCE_END));
        assertArrayEquals(bytes(0x76, 0x00, 0x2A, 0x07), reencode(in));
        byte[] nonMinimal = bytes(0x76, 0x00, 0x2A, 0x87, 0x00);
        assertArrayEquals(bytes(0x76, 0x00, 0x2A, 0x07), reencode(nonMinimal));
    }
}
