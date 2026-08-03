/*
 * SofaBuffers Java - a sequence-end header's id is discarded, never validated.
 *
 * CORELIB_PLAN §4.9 makes the marker asymmetric: an encoder MUST emit a sequence
 * end as exactly 0x07, while a decoder MUST accept one carrying *any* id, discard
 * it, and re-encode the marker canonically. §6.2 says the same from the other
 * side: ID_MAX bounds the id of a value-bearing header - unsigned, signed,
 * fixlen, the array types and sequence *start* - and explicitly not the end
 * marker, which has no value space to bound. §7.2 files this under test class 5b,
 * tolerance tests: input that is non-canonical but well-formed must decode to
 * what it denotes rather than INVALID.
 *
 * Both decode surfaces are covered here, because the ceiling used to be applied
 * on both: the contiguous fast path in IStream.feed and the resumable state
 * machine reached at a chunk boundary (§6.5 - "a guard added to one surface but
 * not another").
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class SequenceEndIdToleranceTest {

    /** One past ID_MAX: the id the ceiling used to reject on an end marker. */
    private static final long OVER_ID_MAX = 1L << 31;

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

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

    private static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int n = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, n, p.length);
            n += p.length;
        }
        return out;
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

    private static SofabError errorOf(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> new IStream().feed(data, new Visitor() { }));
        return ex.error();
    }

    private static SofabError errorOfChunked(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            IStream is = new IStream();
            Visitor v = new Visitor() { };
            for (byte b : data) {
                is.feed(new byte[] {b}, v);
            }
        });
        return ex.error();
    }

    // --- the reproducer -----------------------------------------------------

    @Test
    void headerHelperMatchesTheIsolateBytes() {
        // 76: id 14, wire type SequenceStart. 87 80 80 80 40: wire type 7, id 2^31.
        assertArrayEquals(bytes(0x76), header(14, WireFormat.T_SEQUENCE_START));
        assertArrayEquals(bytes(0x87, 0x80, 0x80, 0x80, 0x40), header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END));
    }

    @Test
    void overIdMaxIdOnSequenceEndAccepted() throws SofabException {
        // An unknown id opened as a sequence, closed by an oversized end marker.
        byte[] isolate = bytes(0x76, 0x87, 0x80, 0x80, 0x80, 0x40);
        assertEquals(List.of("seq{:14", "seq}"), decode(isolate));
        assertEquals(List.of("seq{:14", "seq}"), decodeByteByByte(isolate));
    }

    @Test
    void largestPossibleIdOnSequenceEndAccepted() throws SofabException {
        // The id sub-field of a 10-byte header varint tops out at 2^61 - 1; even
        // that is discarded. Only §4.1's 64-bit bound on the varint itself is left.
        byte[] msg = concat(bytes(0x0E), header((1L << 61) - 1, WireFormat.T_SEQUENCE_END));
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void overIdMaxIdOnSequenceEndAcceptedWhenNested() throws SofabException {
        // Every closing marker of a nested run carries an over-ceiling id.
        byte[] end = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END);
        byte[] msg = concat(bytes(0x0E, 0x16), bytes(0x00, 0x2A), end, end);
        assertEquals(List.of("seq{:1", "seq{:2", "u:0=42", "seq}", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq{:2", "u:0=42", "seq}", "seq}"), decodeByteByByte(msg));
    }

    // --- controls: these passed before the fix and must keep passing ---------

    @Test
    void canonicalSequenceEndStillAccepted() throws SofabException {
        byte[] msg = bytes(0x0E, 0x07);
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void smallNonZeroIdOnSequenceEndStillAccepted() throws SofabException {
        byte[] msg = concat(bytes(0x0E), header(3, WireFormat.T_SEQUENCE_END));
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void idAtIdMaxOnSequenceEndStillAccepted() throws SofabException {
        byte[] msg = concat(bytes(0x0E), header(WireFormat.ID_MAX, WireFormat.T_SEQUENCE_END));
        assertEquals(List.of("seq{:1", "seq}"), decode(msg));
        assertEquals(List.of("seq{:1", "seq}"), decodeByteByByte(msg));
    }

    @Test
    void danglingSequenceEndStillRejectedWhateverItsId() {
        // §5.2's only sequence-end INVALID condition: no open sequence. The id
        // being unbounded does not make the marker structurally free.
        byte[] dangling = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END);
        assertEquals(SofabError.INVALID_MSG, errorOf(dangling));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(dangling));
    }

    @Test
    void overlongHeaderVarintStillRejectedOnSequenceEnd() {
        // §4.1 still bounds the header encoding: 11 continuation bytes overflow
        // the 64-bit varint whatever wire type they would have decoded to.
        byte[] bad = bytes(0x0E, 0x87, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    // --- the ceiling still binds a value-bearing header ----------------------

    @Test
    void overIdMaxIdOnUnsignedStillRejected() {
        byte[] bad = header(OVER_ID_MAX, WireFormat.T_VARINT_UNSIGNED);
        assertEquals(SofabError.INVALID_MSG, errorOf(concat(bad, bytes(0x00))));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(concat(bad, bytes(0x00))));
    }

    @Test
    void overIdMaxIdOnUnsignedInsideSkippedSubtreeStillRejected() {
        // Same header one level down, inside the unknown sequence the corelib
        // walks without any schema knowledge.
        byte[] bad = concat(bytes(0x76), header(OVER_ID_MAX, WireFormat.T_VARINT_UNSIGNED), bytes(0x00, 0x07));
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
    }

    @Test
    void overIdMaxIdOnSequenceStartStillRejected() {
        // Sequence *start* is value-bearing - its id names a field - so §6.2's
        // ceiling does govern it.
        byte[] bad = header(OVER_ID_MAX, WireFormat.T_SEQUENCE_START);
        assertEquals(SofabError.INVALID_MSG, errorOf(bad));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bad));
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
    void oversizedEndMarkerReencodesAsCanonicalSeven() throws SofabException {
        // §4.9: the discarded id is normalized away, exactly as a non-minimal
        // varint is - the marker comes back out as the single byte 0x07.
        byte[] in = concat(bytes(0x76), bytes(0x00, 0x2A), header(OVER_ID_MAX, WireFormat.T_SEQUENCE_END));
        assertArrayEquals(bytes(0x76, 0x00, 0x2A, 0x07), reencode(in));
    }

    @Test
    void oversizedEndMarkerOnEmptySequenceReencodesToTheEmptyMessage() throws SofabException {
        // The isolate itself: an unknown, contentless sequence. A lazy begin
        // dropped by an end that receives no content leaves nothing on the wire.
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        Visitor v = new Visitor() {
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
                    os.writeSequenceEnd();
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        };
        new IStream().feed(bytes(0x76, 0x87, 0x80, 0x80, 0x80, 0x40), v);
        assertEquals(0, os.bytesUsed());
    }
}
