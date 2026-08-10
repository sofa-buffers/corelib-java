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
import static org.sofabuffers.sofab.common.Wire.bytes;

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
    void shortArrayTakesTheSamePathsAsALongOne() throws IOException {
        // A three-element array is bulk-encoded like any other once the room test
        // passes — there is no element-count floor — and identical either way.
        long[] few = { 1L, -1L, 0x4000L };
        assertBulkMatchesStreamed(os -> os.writeArrayUnsigned(5, few));
    }

    // --- the per-type bulk room bound ---------------------------------------

    /**
     * Each integer array writer enters the check-free bulk loop on its <em>own</em>
     * element width — two bytes for a {@code byte}, three for a {@code short}, five
     * for an {@code int}, ten for a {@code long} — plus a full varint's room for the
     * last element, because {@link OStream} assembles a varint with an eight-byte
     * store that must stay inside the buffer.
     *
     * <p>Sweeping the buffer size across that threshold is what tests it: below it
     * the per-element streaming loop runs, at and above it the bulk loop does, and a
     * width bound that under-estimated its type would run the eight-byte store past
     * the end of an exactly-sized buffer rather than quietly producing other bytes.
     * Every element here is its type's widest encoding.
     */
    private static void assertSameBytesAcrossTheBulkThreshold(EncodeBody body)
            throws IOException {
        byte[] want = encode(body);
        for (int size = want.length; size <= want.length + 24; size++) {
            byte[] buf = new byte[size];
            OStream os = new OStream(buf);
            body.run(os);
            assertArrayEquals(want, Arrays.copyOf(buf, os.bytesUsed()),
                    "buffer of " + size + " bytes produced different output");
        }
        assertArrayEquals(want, encodeStreamed(16, body));
    }

    private static byte[] widestBytes() {
        byte[] a = new byte[24];
        Arrays.fill(a, (byte) 0xFF);
        return a;
    }

    private static short[] widestShorts() {
        short[] a = new short[24];
        Arrays.fill(a, (short) 0xFFFF);
        return a;
    }

    private static int[] widestInts() {
        int[] a = new int[24];
        Arrays.fill(a, -1);
        return a;
    }

    private static long[] widestLongs() {
        long[] a = new long[24];
        Arrays.fill(a, -1L);
        return a;
    }

    @Test
    void unsignedByteArrayBulkBoundIsExact() throws IOException {
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArrayUnsigned(1, widestBytes()));
    }

    @Test
    void unsignedShortArrayBulkBoundIsExact() throws IOException {
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArrayUnsigned(2, widestShorts()));
    }

    @Test
    void unsignedIntArrayBulkBoundIsExact() throws IOException {
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArrayUnsigned(3, widestInts()));
    }

    @Test
    void unsignedLongArrayBulkBoundIsExact() throws IOException {
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArrayUnsigned(4, widestLongs()));
    }

    @Test
    void signedByteArrayBulkBoundIsExact() throws IOException {
        byte[] a = new byte[24];
        Arrays.fill(a, Byte.MIN_VALUE);
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArraySigned(5, a));
    }

    @Test
    void signedShortArrayBulkBoundIsExact() throws IOException {
        short[] a = new short[24];
        Arrays.fill(a, Short.MIN_VALUE);
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArraySigned(6, a));
    }

    @Test
    void signedIntArrayBulkBoundIsExact() throws IOException {
        int[] a = new int[24];
        Arrays.fill(a, Integer.MIN_VALUE);
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArraySigned(7, a));
    }

    @Test
    void signedLongArrayBulkBoundIsExact() throws IOException {
        long[] a = new long[24];
        Arrays.fill(a, Long.MIN_VALUE);
        assertSameBytesAcrossTheBulkThreshold(os -> os.writeArraySigned(8, a));
    }

    @Test
    void emptyIntegerArraysNeverEnterTheBulkLoop() throws IOException {
        // count == 0: no element is written, so no room beyond the header is
        // needed and the encoder must not demand a varint's worth of it.
        assertArrayEquals(bytes(0x0B, 0x00), encode(os -> os.writeArrayUnsigned(1, new byte[0])));
        assertArrayEquals(bytes(0x0B, 0x00), encode(os -> os.writeArrayUnsigned(1, new short[0])));
        assertArrayEquals(bytes(0x0B, 0x00), encode(os -> os.writeArrayUnsigned(1, new int[0])));
        assertArrayEquals(bytes(0x0B, 0x00), encode(os -> os.writeArrayUnsigned(1, new long[0])));
        assertArrayEquals(bytes(0x0C, 0x00), encode(os -> os.writeArraySigned(1, new byte[0])));
        assertArrayEquals(bytes(0x0C, 0x00), encode(os -> os.writeArraySigned(1, new short[0])));
        assertArrayEquals(bytes(0x0C, 0x00), encode(os -> os.writeArraySigned(1, new int[0])));
        assertArrayEquals(bytes(0x0C, 0x00), encode(os -> os.writeArraySigned(1, new long[0])));
    }

    @Test
    void narrowIntegerArraysRoundTrip() throws IOException {
        byte[] wire = encode(os -> {
            os.writeArrayUnsigned(1, widestBytes());
            os.writeArrayUnsigned(2, widestShorts());
        });

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(wire, v);

        assertEquals("arr:1:UNSIGNED:24", v.events.get(0));
        assertEquals("u:1=255", v.events.get(1));
        assertEquals("arr:2:UNSIGNED:24", v.events.get(25));
        assertEquals("u:2=65535", v.events.get(26));
    }

    // --- the packed field / float stores -------------------------------------

    /**
     * A one-byte header with a one-byte value goes out as a single two-byte store,
     * and an fp32 field as a single eight-byte one; both fall back to the general
     * varint path once the id no longer fits one byte. The two arms must agree with
     * each other and with the buffer-spanning writer, which has neither.
     */
    @Test
    void packedFieldStoresAgreeWithTheGeneralPath() throws IOException {
        for (int id : new int[] { 0, 1, 15, 16, 31, 1000, 0x10_0000 }) {
            final int fieldId = id;
            for (long value : new long[] { 0L, 1L, 0x7FL, 0x80L, -1L }) {
                final long v = value;
                assertBulkMatchesStreamed(os -> os.writeUnsigned(fieldId, v));
                assertBulkMatchesStreamed(os -> os.writeSigned(fieldId, v));
            }
            assertBulkMatchesStreamed(os -> os.writeFp32(fieldId, 3.5f));
            assertBulkMatchesStreamed(os -> os.writeFp64(fieldId, -2.25));
            assertBulkMatchesStreamed(os -> os.writeFp32(fieldId, Float.NEGATIVE_INFINITY));
            assertBulkMatchesStreamed(os -> os.writeFp64(fieldId, Double.NaN));
        }
    }

    @Test
    void packedFloatFieldsRoundTrip() throws IOException {
        byte[] wire = encode(os -> {
            os.writeFp32(3, 3.5f);
            os.writeFp64(4, -2.25);
            os.writeFp32(4096, 1.5f);
            os.writeFp64(4096, 0.5);
        });

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(wire, v);

        assertEquals(Arrays.asList(
                "f32:3=3.5", "f64:4=-2.25", "f32:4096=1.5", "f64:4096=0.5"), v.events);
    }

    // --- float arrays: the per-element little-endian store ------------------

    /** fp32 specials plus a signaling NaN, whose payload bits must survive verbatim. */
    private static final float[] FP32_SPECIALS = {
        0.0f, -0.0f, 1.5f, -2.5f, Float.MIN_VALUE, Float.MAX_VALUE,
        Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
        Float.NaN, Float.intBitsToFloat(0x7F80_0001),
    };

    /** The fp64 twins of {@link #FP32_SPECIALS}. */
    private static final double[] FP64_SPECIALS = {
        0.0, -0.0, 1.5, -2.5, Double.MIN_VALUE, Double.MAX_VALUE,
        Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
        Double.NaN, Double.longBitsToDouble(0x7FF0_0000_0000_0001L),
    };

    /**
     * A float array takes the check-free bulk loop only when the <em>whole</em>
     * payload fits the buffer — fixed-width elements leave no varint slack to bound
     * per element — and below that each element goes out on its own: a single
     * little-endian store while the element fits the room left, and a flush-aware
     * byte-at-a-time fallback for the one element that straddles the buffer end.
     * Sweeping the streaming buffer from {@link Sofab#MIN_OUTPUT_BUFFER} up past the
     * whole message drives all three, and every size must reproduce the one-shot
     * bytes: which path emitted an element is not allowed to be visible on the wire.
     */
    private static void assertFloatArrayStreamsIdentically(EncodeBody body) throws IOException {
        byte[] want = encode(body);
        for (int size = Sofab.MIN_OUTPUT_BUFFER; size <= want.length + 4; size++) {
            assertArrayEquals(want, encodeStreamed(size, body),
                    "streaming through a " + size + "-byte buffer produced different output");
        }
    }

    @Test
    void fp32ArrayStreamsIdentically() throws IOException {
        assertFloatArrayStreamsIdentically(os -> os.writeArrayFp32(1, FP32_SPECIALS));
    }

    @Test
    void fp64ArrayStreamsIdentically() throws IOException {
        assertFloatArrayStreamsIdentically(os -> os.writeArrayFp64(2, FP64_SPECIALS));
    }

    /**
     * The payload of a NaN is data, not a flag: §6.5 requires the wire bits to come
     * back exactly, so a signaling NaN must not arrive quieted. Asserted on raw bits
     * (a NaN never compares equal to itself) and on the element the streamed encoder
     * had to split, not just the ones a bulk store wrote.
     */
    @Test
    void floatArrayElementsRoundTripBitExact() throws IOException {
        byte[] wire = encodeStreamed(9, os -> {
            os.writeArrayFp32(1, FP32_SPECIALS);
            os.writeArrayFp64(2, FP64_SPECIALS);
        });

        int[] got32 = new int[FP32_SPECIALS.length];
        long[] got64 = new long[FP64_SPECIALS.length];
        int[] n = {0, 0};
        new IStream().feed(wire, new Visitor() {
            @Override public void fp32(int id, float value) {
                got32[n[0]++] = Float.floatToRawIntBits(value);
            }

            @Override public void fp64(int id, double value) {
                got64[n[1]++] = Double.doubleToRawLongBits(value);
            }
        });

        assertEquals(FP32_SPECIALS.length, n[0]);
        assertEquals(FP64_SPECIALS.length, n[1]);
        for (int i = 0; i < FP32_SPECIALS.length; i++) {
            assertEquals(Float.floatToRawIntBits(FP32_SPECIALS[i]), got32[i],
                    "fp32 element " + i + " lost bits");
            assertEquals(Double.doubleToRawLongBits(FP64_SPECIALS[i]), got64[i],
                    "fp64 element " + i + " lost bits");
        }
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
