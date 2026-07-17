/*
 * SofaBuffers Java - decoder rejects the remaining malformed-input branches.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class DecoderErrorsTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static SofabError errorOf(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> new IStream().feed(data, new Visitor() { }));
        return ex.error();
    }

    /** Feed {@code data} one byte at a time, exercising the incremental state machine. */
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

    @Test
    void idAboveMaxRejected() {
        // Header varint 0x400000000: (id = 2^31) << 3 | type 0; id exceeds ID_MAX.
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x80, 0x80, 0x80, 0x80, 0x40)));
    }

    @Test
    void reservedFixlenTypeRejected() {
        // fixlen field (id 0), fixlen header 0x04 -> reserved subtype 4.
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x02, 0x04)));
    }

    @Test
    void fp64WrongLengthRejected() {
        // fixlen field (id 0), header (4 << 3) | FP64(1) = 0x21 -> fp64 length 4 (must be 8).
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x02, 0x21)));
    }

    @Test
    void fp32WrongLengthRejected() {
        // fixlen field (id 0), header (5 << 3) | FP32(0) = 0x28 -> fp32 length 5 (must be 4).
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x02, 0x28)));
    }

    @Test
    void stringAsFixlenArrayElementRejected() {
        // fixlen-array (id 0), count 1, element header (1 << 3) | STRING(2) = 0x0A.
        // String/blob are not valid as fixlen-array elements.
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x05, 0x01, 0x0A)));
    }

    @Test
    void fixlenLengthAboveMaxRejected() {
        // fixlen string (id 0) with length 2^31 (> ARRAY_MAX): header (2^31 << 3) | STRING(2).
        assertEquals(SofabError.INVALID_MSG, errorOf(bytes(0x02, 0x82, 0x80, 0x80, 0x80, 0x40)));
    }

    @Test
    void nestingAtMaxDepthAccepted() throws SofabException {
        // MAX_DEPTH (255) nested sequence-start markers (id 0 -> 0x06), then their ends
        // (0x07), is the deepest legal nesting and must decode without error.
        byte[] data = new byte[Sofab.MAX_DEPTH * 2];
        Arrays.fill(data, 0, Sofab.MAX_DEPTH, (byte) 0x06);
        Arrays.fill(data, Sofab.MAX_DEPTH, data.length, (byte) 0x07);
        new IStream().feed(data, new Visitor() { });
    }

    @Test
    void nestingBeyondMaxDepthRejected() {
        // 256 nested sequence starts exceeds MAX_DEPTH = 255 and must be rejected.
        byte[] data = new byte[Sofab.MAX_DEPTH + 1];
        Arrays.fill(data, (byte) 0x06);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
    }

    // --- overlong (>64-bit) varint (issue #41 / MESSAGE_SPEC §4.1, §6.3) ---
    // Field header 0x30 = (id 6 << 3) | T_VARINT_UNSIGNED(0): a u64 value follows.
    // A varint holds 7 payload bits per byte; a 64-bit value takes 10 bytes and in
    // the 10th byte only bit 0 (value bit 63) may be set. Anything past bit 63 is
    // an overlong varint and must be rejected as INVALID, never silently truncated.

    @Test
    void overlongVarintTenthByteHighBitRejected() {
        // ff*9 then 0x02: the 10th byte sets bit 1 -> value bit 64 (the 65th bit).
        byte[] data = bytes(0x30, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x02);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));        // fast path
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data)); // incremental path
    }

    @Test
    void overlongVarintTenthByteAllHighBitsRejected() {
        // ff*9 then 0x7f: the 10th byte sets bits 1..6 -> value bits 64..69. A
        // distinct malformed input from ...0x02; both must be INVALID (not two
        // different silently-truncated values).
        byte[] data = bytes(0x30, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x7f);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void overlongVarintEleventhByteRejected() {
        // The 10th value byte (0x81) keeps only bit 0 set so it clears the high-bit
        // check, but its continuation flag forces an out-of-range 11th byte: the
        // value would need >10 bytes, so it is overlong and must be rejected.
        byte[] data = bytes(0x30, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x81, 0x00);
        assertEquals(SofabError.INVALID_MSG, errorOf(data));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(data));
    }

    @Test
    void maximumU64Accepted() throws SofabException {
        // Control: ff*9 then 0x01 is exactly 2^64-1, the valid maximum -> accepted.
        byte[] data = bytes(0x30, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0x01);

        RecordingVisitor fast = new RecordingVisitor();
        new IStream().feed(data, fast);
        assertEquals(Arrays.asList("u:6=18446744073709551615"), fast.events);

        RecordingVisitor chunked = new RecordingVisitor();
        IStream in = new IStream();
        for (byte b : data) {
            in.feed(new byte[] { b }, chunked);
        }
        assertEquals(Arrays.asList("u:6=18446744073709551615"), chunked.events);
    }
}
