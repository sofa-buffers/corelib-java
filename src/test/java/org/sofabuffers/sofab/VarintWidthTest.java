/*
 * SofaBuffers Java - every varint width, on every reading surface.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sofabuffers.sofab.common.Decode.errorOf;
import static org.sofabuffers.sofab.common.Decode.errorOfChunked;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.ByteArrayOutputStream;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.Decode;
import org.sofabuffers.sofab.common.RecordingVisitor;

/**
 * A varint is read by four different pieces of code, chosen by how much room is
 * left rather than by what the varint means: the eight-at-a-time window when ten
 * or more bytes remain, its ninth/tenth-byte tail, the single-byte short-circuit
 * for the buffer's last nine bytes, and the bounded reader behind it. The
 * resumable state machine is a fifth. They must agree on every width and on the
 * one rule that can reject a varint — the 64-bit bound (§4.1/§6.3).
 *
 * <p>Which of them runs is decided by <em>position</em>, so each vector here is
 * decoded twice: once with ten or more bytes of slack after it, and once as the
 * last field in the buffer, where the tail readers take over. Both are then also
 * driven byte-at-a-time.
 */
class VarintWidthTest {

    /** Base-128 varint bytes for {@code v}, treated as unsigned. */
    private static byte[] varint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long x = v;
        do {
            int b = (int) (x & 0x7F);
            x >>>= 7;
            out.write(x != 0 ? (b | 0x80) : b);
        } while (x != 0);
        return out.toByteArray();
    }

    /** The narrowest value that needs exactly {@code width} varint bytes, and the widest. */
    private static long[] valuesOfWidth(int width) {
        long low = width == 1 ? 0L : 1L << (7 * (width - 1));
        long high = width == 10 ? -1L : (1L << (7 * width)) - 1;
        return new long[] { low, high };
    }

    /**
     * Two nine-byte {@code u64 = 0} fields appended after the vector under test, so
     * it is read with well over the ten bytes of slack the eight-at-a-time window
     * needs. Without them every vector sits at the end of the buffer and only the
     * tail readers would ever run.
     */
    private static final byte[] SLACK = concat(
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00),
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x00));

    private static void assertUnsignedDecodesTo(byte[] field, String want) {
        for (byte[] vector : new byte[][] { field, concat(field, SLACK) }) {
            for (int chunk : Decode.CHUNKS) {
                RecordingVisitor v = new RecordingVisitor();
                assertEquals("A", Decode.verdict(vector, v, chunk),
                        "chunk " + chunk + " rejected a well-formed varint");
                assertEquals(want, v.events.get(0),
                        "chunk " + chunk + " decoded the varint differently");
            }
        }
    }

    /**
     * Every width from one to ten bytes decodes to the same value as a scalar
     * field, at the head of the buffer and at its very end.
     */
    @Test
    void everyWidthDecodesTheSameAsAScalar() {
        for (int width = 1; width <= 10; width++) {
            for (long value : valuesOfWidth(width)) {
                assertUnsignedDecodesTo(concat(bytes(0x08), varint(value)),
                        "u:1=" + Long.toUnsignedString(value));
            }
        }
    }

    /**
     * The same widths as array elements. The element loop has its own copy of the
     * eight-at-a-time window and its own tail, so a width it got wrong would decode
     * correctly as a scalar and wrongly here.
     */
    @Test
    void everyWidthDecodesTheSameAsAnArrayElement() {
        for (int width = 1; width <= 10; width++) {
            for (long value : valuesOfWidth(width)) {
                // [ id 1, varint array ][ count 2 ][ element 0 = 1 ][ element 1 ]
                byte[] field = concat(bytes(0x0B, 0x02, 0x01), varint(value));
                for (byte[] vector : new byte[][] { field, concat(field, SLACK) }) {
                    for (int chunk : Decode.CHUNKS) {
                        RecordingVisitor v = new RecordingVisitor();
                        assertEquals("A", Decode.verdict(vector, v, chunk));
                        assertEquals("u:1=" + Long.toUnsignedString(value), v.events.get(2),
                                "width " + width + " element differs at chunk " + chunk);
                    }
                }
            }
        }
    }

    /**
     * The 64-bit bound is the one rule a varint can break, and it breaks the same
     * way wherever it is read: a tenth byte carrying more than bit 63, and an
     * eleventh byte implied by a tenth that still continues.
     */
    @Test
    void pastTheSixtyFourBitBoundIsRejectedOnEverySurface() {
        byte[] eightContinuations = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        for (int tenth : new int[] { 0x02, 0x7F, 0x81, 0xFF }) {
            byte[] tail = concat(eightContinuations, bytes(tenth));
            for (byte[] prefix : new byte[][] {
                    bytes(0x08),                    // scalar value
                    bytes(0x0B, 0x01),              // varint-array element
                    bytes(0x02),                    // fixlen_word
                    bytes(0x0B) }) {                // array count
                byte[] vector = concat(prefix, tail);
                assertEquals(SofabError.INVALID_MSG, errorOf(vector),
                        "tenth byte " + Integer.toHexString(tenth) + " accepted");
                assertEquals(SofabError.INVALID_MSG, errorOfChunked(vector),
                        "tenth byte " + Integer.toHexString(tenth) + " accepted (chunked)");
                assertEquals(SofabError.INVALID_MSG, errorOf(concat(vector, SLACK)),
                        "tenth byte " + Integer.toHexString(tenth) + " accepted with slack");
            }
        }
    }

    /**
     * A tenth byte of {@code 0x01} is the largest legal varint — bit 63 and nothing
     * above it — so the bound rejects only what is past it.
     */
    @Test
    void theLargestLegalVarintIsAccepted() {
        assertUnsignedDecodesTo(
                concat(bytes(0x08), varint(-1L)), "u:1=" + Long.toUnsignedString(-1L));
    }
}
