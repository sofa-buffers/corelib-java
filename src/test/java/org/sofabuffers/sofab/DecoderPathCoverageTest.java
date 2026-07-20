/*
 * SofaBuffers Java - decoder branches that only one of the two decode paths can
 * reach.
 *
 * The decoder has a contiguous-buffer fast path and a resumable byte-at-a-time
 * state machine, and each has its own copy of the header / length / count varint
 * readers. These tests drive the malformed-input branches of the readers that
 * DecoderErrorsTest (which exercises the value readers) leaves untouched, and
 * force the state machine to run by splitting multi-byte headers across feeds.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class DecoderPathCoverageTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** Feed {@code data} in one call: the contiguous-buffer fast path. */
    private static SofabError errorOf(byte[] data) {
        SofabException ex = assertThrows(SofabException.class,
                () -> new IStream().feed(data, new Visitor() { }));
        return ex.error();
    }

    /** Feed {@code data} one byte at a time: the resumable state machine. */
    private static SofabError errorOfChunked(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            IStream in = new IStream();
            Visitor v = new Visitor() { };
            for (byte b : data) {
                in.feed(new byte[] { b }, v);
            }
        });
        return ex.error();
    }

    /** Nine 0xFF continuation bytes then {@code last}: a ten-byte varint. */
    private static byte[] tenByteVarint(int prefix, int last) {
        byte[] out = new byte[11];
        out[0] = (byte) prefix;
        Arrays.fill(out, 1, 10, (byte) 0xFF);
        out[10] = (byte) last;
        return out;
    }

    /** Nine 0x80 bytes then {@code last}: a ten-byte varint carrying no high bits. */
    private static byte[] tenByteEmptyVarint(int prefix, int last) {
        byte[] out = new byte[11];
        out[0] = (byte) prefix;
        Arrays.fill(out, 1, 10, (byte) 0x80);
        out[10] = (byte) last;
        return out;
    }

    // --- overlong (>64-bit) varints outside the value readers ----------------

    @Test
    void overlongFieldHeaderRejected() {
        // The field header is itself a varint: ff*9 then 0x02 sets value bit 64.
        byte[] data = new byte[10];
        Arrays.fill(data, 0, 9, (byte) 0xFF);
        data[9] = 0x02;
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void overlongFixlenLengthHeaderRejected() {
        // fixlen field (id 0) whose length header runs past 64 bits.
        assertEquals(SofabError.INVALID_MSG, errorOf(tenByteVarint(0x02, 0x02)));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(tenByteVarint(0x02, 0x02)));
    }

    @Test
    void elevenByteFixlenLengthHeaderRejected() {
        // The tenth byte keeps only bit 0 set (so the high-bit check passes) but
        // still flags a continuation, demanding an out-of-range eleventh byte.
        assertEquals(SofabError.INVALID_MSG, errorOf(tenByteEmptyVarint(0x02, 0x81)));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(tenByteEmptyVarint(0x02, 0x81)));
    }

    @Test
    void overlongArrayCountRejected() {
        // Varint array (id 0, type 3) whose element count runs past 64 bits.
        assertEquals(SofabError.INVALID_MSG, errorOf(tenByteVarint(0x03, 0x02)));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(tenByteVarint(0x03, 0x02)));
    }

    @Test
    void elevenByteArrayCountRejected() {
        assertEquals(SofabError.INVALID_MSG, errorOf(tenByteEmptyVarint(0x03, 0x81)));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(tenByteEmptyVarint(0x03, 0x81)));
    }

    @Test
    void arrayCountAboveMaxRejected() {
        // Count 2^31 exceeds ARRAY_MAX (INT32_MAX).
        byte[] data = bytes(0x03, 0x80, 0x80, 0x80, 0x80, 0x08);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    // --- array elements read by the unrolled fast-path varint reader --------
    //
    // An element is read by the unrolled reader only when a full ten bytes of
    // room remain, so these messages end exactly at the tenth element byte.

    @Test
    void overlongArrayElementRejected() {
        // Varint array (id 0), count 1, element ff*9 then 0x02: value bit 64 set.
        byte[] data = new byte[12];
        data[0] = 0x03;
        data[1] = 0x01;
        Arrays.fill(data, 2, 11, (byte) 0xFF);
        data[11] = 0x02;
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void elevenByteArrayElementRejected() {
        // The tenth element byte keeps only bit 0 (value bit 63) set so the
        // high-bit check passes, but its continuation flag demands an eleventh.
        byte[] data = new byte[12];
        data[0] = 0x03;
        data[1] = 0x01;
        Arrays.fill(data, 2, 11, (byte) 0xFF);
        data[11] = (byte) 0x81;
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void maximumU64ArrayElementAccepted() throws SofabException {
        // Control: ff*9 then 0x01 is exactly 2^64-1, the valid maximum.
        byte[] data = new byte[12];
        data[0] = 0x03;
        data[1] = 0x01;
        Arrays.fill(data, 2, 11, (byte) 0xFF);
        data[11] = 0x01;

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(data, v);
        assertEquals(Arrays.asList("arr:0:UNSIGNED:1", "u:0=18446744073709551615"), v.events);
    }

    // --- fixlen-array element header (the shared fixlen_word, §4.8) ----------

    @Test
    void overlongFixlenArrayElementHeaderRejected() {
        // fixlen array (id 0, type 5), count 1, then an overlong element header.
        byte[] data = new byte[12];
        data[0] = 0x05;
        data[1] = 0x01;
        Arrays.fill(data, 2, 11, (byte) 0xFF);
        data[11] = 0x02;
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void elevenByteFixlenArrayElementHeaderRejected() {
        byte[] data = new byte[12];
        data[0] = 0x05;
        data[1] = 0x01;
        Arrays.fill(data, 2, 11, (byte) 0x80);
        data[11] = (byte) 0x81;
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void fixlenArrayElementLengthAboveMaxRejected() {
        // Element header (2^31 << 3) | FP32(0): the element length exceeds ARRAY_MAX.
        byte[] data = bytes(0x05, 0x01, 0x80, 0x80, 0x80, 0x80, 0x40);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void fixlenArrayFp32WrongElementLengthRejected() {
        // Element header (5 << 3) | FP32(0) = 0x28 -> fp32 element of 5 bytes.
        byte[] data = bytes(0x05, 0x01, 0x28);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void fixlenArrayFp64WrongElementLengthRejected() {
        // Element header (4 << 3) | FP64(1) = 0x21 -> fp64 element of 4 bytes.
        byte[] data = bytes(0x05, 0x01, 0x21);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void blobAsFixlenArrayElementRejected() {
        // Element header (1 << 3) | BLOB(3) = 0x0B; only fp32/fp64 may be elements.
        byte[] data = bytes(0x05, 0x01, 0x0B);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    // --- state machine: headers split across feeds ---------------------------
    //
    // A one-byte header is still decoded by the fast path even when it arrives
    // alone, so the state machine only takes over once a header varint itself
    // straddles a feed boundary. Field id 16 gives a two-byte header:
    // (16 << 3) | type, e.g. 0x86 0x01 for a sequence start.

    /** Feed each byte in its own call, recording what the visitor sees. */
    private static List<String> decodeByteAtATime(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        for (byte b : data) {
            in.feed(new byte[] { b }, v);
        }
        return v.events;
    }

    @Test
    void sequenceStartAndEndDecodedByStateMachine() throws SofabException {
        // seq start (id 16), unsigned 1 = 7, seq end (id 16 -> the end marker
        // carries id 16 too here, which the decoder ignores for T_SEQUENCE_END).
        byte[] data = bytes(0x86, 0x01, 0x08, 0x07, 0x87, 0x01);
        assertEquals(Arrays.asList("seq{:16", "u:1=7", "seq}"), decodeByteAtATime(data));
    }

    @Test
    void danglingSequenceEndRejectedByStateMachine() {
        // (16 << 3) | T_SEQUENCE_END(7) = 0x87 0x01 with no open sequence.
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bytes(0x87, 0x01)));
    }

    @Test
    void nestingBeyondMaxDepthRejectedByStateMachine() {
        // MAX_DEPTH + 1 two-byte sequence starts, fed one byte at a time.
        byte[] data = new byte[(Sofab.MAX_DEPTH + 1) * 2];
        for (int i = 0; i < data.length; i += 2) {
            data[i] = (byte) 0x86;
            data[i + 1] = 0x01;
        }
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void nestingAtMaxDepthAcceptedByStateMachine() throws SofabException {
        byte[] data = new byte[Sofab.MAX_DEPTH * 4];
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            data[i * 2] = (byte) 0x86;
            data[i * 2 + 1] = 0x01;
            data[Sofab.MAX_DEPTH * 2 + i * 2] = (byte) 0x87;
            data[Sofab.MAX_DEPTH * 2 + i * 2 + 1] = 0x01;
        }
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            expected.add("seq{:16");
        }
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            expected.add("seq}");
        }

        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        for (byte b : data) {
            in.feed(new byte[] { b }, v);
        }
        assertEquals(expected, v.events);
        // Every sequence closed, no partial header: a clean field boundary.
        assertEquals(DecodeStatus.COMPLETE, in.status());
    }

    @Test
    void fp32WrongLengthRejectedByStateMachine() {
        // fixlen field (id 0) with a two-byte length header: (16 << 3) | FP32(0)
        // = 0x80 0x01, i.e. an fp32 declared 16 bytes long.
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bytes(0x02, 0x80, 0x01)));
    }

    @Test
    void fixlenLengthAboveMaxRejectedByStateMachine() {
        // String length 2^31 (> ARRAY_MAX) in a header split across feeds.
        assertEquals(SofabError.INVALID_MSG,
                errorOfChunked(bytes(0x02, 0x82, 0x80, 0x80, 0x80, 0x40)));
    }

    @Test
    void reservedFixlenTypeRejectedByStateMachine() {
        // Reserved subtype 4 in a two-byte length header: (16 << 3) | 4 = 0x84 0x01.
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(bytes(0x02, 0x84, 0x01)));
    }
}
