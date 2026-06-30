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
}
