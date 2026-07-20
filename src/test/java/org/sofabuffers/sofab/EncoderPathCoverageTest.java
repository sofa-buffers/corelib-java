/*
 * SofaBuffers Java - encoder paths the byte-exact vector tests do not reach:
 * argument validation, the buffer-spanning UTF-8 writer, and the check-free bulk
 * array encoder.
 *
 * The wire output must not depend on which path produced it, so the bulk tests
 * assert that a large buffer (bulk path) and a small buffer plus flush sink
 * (per-element streaming path) emit identical bytes.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class EncoderPathCoverageTest {

    @FunctionalInterface
    private interface EncodeBody {
        void run(OStream os) throws IOException;
    }

    /** Encode into a buffer large enough that every fast/bulk path is taken. */
    private static byte[] encode(EncodeBody body) throws IOException {
        byte[] buf = new byte[1024];
        OStream os = new OStream(buf);
        body.run(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /** Encode through a {@code bufSize}-byte buffer, streaming to a sink. */
    private static byte[] encodeStreamed(int bufSize, EncodeBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OStream os = new OStream(new byte[bufSize], 0, out::write);
        body.run(os);
        os.flush();
        return out.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    // --- constructor / bufferSet argument validation -------------------------

    @Test
    void constructorRejectsNullBuffer() {
        assertThrows(IllegalArgumentException.class, () -> new OStream(null));
    }

    @Test
    void constructorRejectsEmptyBuffer() {
        assertThrows(IllegalArgumentException.class, () -> new OStream(new byte[0]));
    }

    @Test
    void constructorRejectsNegativeOffset() {
        assertThrows(IllegalArgumentException.class, () -> new OStream(new byte[8], -1));
    }

    @Test
    void constructorRejectsOffsetPastEnd() {
        assertThrows(IllegalArgumentException.class, () -> new OStream(new byte[8], 9));
    }

    @Test
    void constructorAcceptsOffsetAtEnd() {
        // offset == buffer.length reserves the whole buffer; legal, and the first
        // write then flushes (or fails with BUFFER_FULL when there is no sink).
        assertEquals(8, new OStream(new byte[8], 8).bytesUsed());
    }

    @Test
    void bufferSetRejectsEmptyBuffer() {
        OStream os = new OStream(new byte[8]);
        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(new byte[0], 0));
        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(null, 0));
    }

    @Test
    void bufferSetRejectsOffsetOutOfRange() {
        OStream os = new OStream(new byte[8]);
        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(new byte[8], -1));
        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(new byte[8], 9));
    }

    @Test
    void bufferSetSwitchesBuffers() throws IOException {
        byte[] first = new byte[4];
        OStream os = new OStream(first);
        os.writeUnsigned(0, 1);
        byte[] second = new byte[16];
        os.bufferSet(second, 2);
        os.writeUnsigned(0, 1);
        // Writing resumed at offset 2 of the new buffer.
        assertEquals(4, os.bytesUsed());
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x01), Arrays.copyOf(second, 4));
    }

    @Test
    void negativeIdRejected() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[16]).writeUnsigned(-1, 0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    // --- flush ---------------------------------------------------------------

    @Test
    void flushWithoutSinkReportsPendingAndKeepsBytes() throws IOException {
        byte[] buf = new byte[16];
        OStream os = new OStream(buf);
        os.writeUnsigned(0, 1);
        assertEquals(2, os.flush());
        // With no sink the buffer is left intact, so the bytes are still there.
        assertEquals(2, os.bytesUsed());
        assertArrayEquals(bytes(0x00, 0x01), Arrays.copyOf(buf, 2));
    }

    @Test
    void flushWithNothingPendingIsNoOp() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OStream os = new OStream(new byte[16], 0, out::write);
        assertEquals(0, os.flush());
        assertEquals(0, out.size());
    }

    // --- UTF-8 -------------------------------------------------------------

    @Test
    void threeByteCharEncodedInline() throws IOException {
        // U+20AC EURO SIGN -> E2 82 AC; header (3 << 3) | STRING(2) = 0x1A.
        assertArrayEquals(
                bytes(0x02, 0x1A, 0xE2, 0x82, 0xAC),
                encode(os -> os.writeString(0, "€")));
    }

    @Test
    void mixedWidthStringSameBytesAcrossBufferSizes() throws IOException {
        // One char of every UTF-8 width (1, 2, 3, 4 bytes). A three-byte buffer
        // cannot hold any of the multi-byte forms outright, so the encoder takes
        // the buffer-spanning writer that flushes mid-character.
        String text = "aä€😀z";
        byte[] inline = encode(os -> os.writeString(7, text));
        assertArrayEquals(inline, encodeStreamed(3, os -> os.writeString(7, text)));

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(inline, v);
        assertEquals(Arrays.asList("str:7=" + text), v.events);
    }

    // --- bulk array encoding -------------------------------------------------

    /** Unsigned values covering every varint width from one to ten bytes. */
    private static final long[] WIDE_UNSIGNED = {
        0L, 1L, 0x7FL, 0x80L,
        0x3FFFL, 0x4000L, 0x1F_FFFFL, 0x20_0000L,
        0xFFF_FFFFL, 0x1000_0000L, 0x7_FFFF_FFFFL, 0x8_0000_0000L,
        0x3FF_FFFF_FFFFL, 0x400_0000_0000L, 0x1_FFFF_FFFF_FFFFL, 0x2_0000_0000_0000L,
        0xFF_FFFF_FFFF_FFFFL, 0x100_0000_0000_0000L, Long.MAX_VALUE, -1L,
    };

    /** Signed values whose ZigZag forms cover the same range of widths. */
    private static final long[] WIDE_SIGNED = {
        0L, -1L, 63L, -64L,
        0x1FFFL, -0x2000L, 0xF_FFFFL, -0x10_0000L,
        0x7FF_FFFFL, -0x800_0000L, 0x3_FFFF_FFFFL, -0x4_0000_0000L,
        0x1FF_FFFF_FFFFL, -0x200_0000_0000L, 0xFFFF_FFFF_FFFFL, -0x1_0000_0000_0000L,
        0x7F_FFFF_FFFF_FFFFL, -0x80_0000_0000_0000L, Long.MAX_VALUE, Long.MIN_VALUE,
    };

    private static int[] toInts(long[] values) {
        int[] out = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (int) values[i];
        }
        return out;
    }

    /**
     * The bulk encoder runs only when the array's worst case (ten bytes per
     * element) fits the buffer outright; below that the per-element streaming
     * loop takes over. Both must produce the same bytes.
     */
    private static void assertBulkMatchesStreamed(EncodeBody body) throws IOException {
        byte[] bulk = encode(body);
        assertArrayEquals(bulk, encodeStreamed(24, body));
    }

    @Test
    void bulkUnsignedLongArrayMatchesStreamed() throws IOException {
        assertBulkMatchesStreamed(os -> os.writeArrayUnsigned(1, WIDE_UNSIGNED));
    }

    @Test
    void bulkSignedLongArrayMatchesStreamed() throws IOException {
        assertBulkMatchesStreamed(os -> os.writeArraySigned(2, WIDE_SIGNED));
    }

    @Test
    void bulkUnsignedIntArrayMatchesStreamed() throws IOException {
        assertBulkMatchesStreamed(os -> os.writeArrayUnsigned(3, toInts(WIDE_UNSIGNED)));
    }

    @Test
    void bulkSignedIntArrayMatchesStreamed() throws IOException {
        assertBulkMatchesStreamed(os -> os.writeArraySigned(4, toInts(WIDE_SIGNED)));
    }

    @Test
    void shortArrayStaysOnStreamingPath() throws IOException {
        // Below BULK_MIN elements the bulk branch is skipped even with room to
        // spare; the output is still identical.
        long[] few = { 1L, -1L, 0x4000L };
        assertBulkMatchesStreamed(os -> os.writeArrayUnsigned(5, few));
    }

    @Test
    void bulkEncodedArrayRoundTrips() throws IOException {
        byte[] wire = encode(os -> os.writeArrayUnsigned(9, WIDE_UNSIGNED));

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(wire, v);

        assertEquals("arr:9:UNSIGNED:" + WIDE_UNSIGNED.length, v.events.get(0));
        for (int i = 0; i < WIDE_UNSIGNED.length; i++) {
            assertEquals("u:9=" + Long.toUnsignedString(WIDE_UNSIGNED[i]), v.events.get(i + 1));
        }
    }

    @Test
    void bulkEncodedSignedArrayRoundTrips() throws IOException {
        byte[] wire = encode(os -> os.writeArraySigned(9, WIDE_SIGNED));

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(wire, v);

        assertEquals("arr:9:SIGNED:" + WIDE_SIGNED.length, v.events.get(0));
        for (int i = 0; i < WIDE_SIGNED.length; i++) {
            assertEquals("s:9=" + WIDE_SIGNED[i], v.events.get(i + 1));
        }
    }
}
