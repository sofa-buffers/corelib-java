/*
 * SofaBuffers Java - an array that straddles a feed boundary must cost the
 * byte-at-a-time machine ONE element, not its whole remainder (corelib-java#74).
 *
 * The two decode paths are byte-for-byte equivalent, so no visitor can tell them
 * apart: a decoder that reads a 200 000-element array one byte at a time emits
 * exactly the events a one-shot feed emits, only ~55x slower. Timing is not a
 * unit test, so these tests read the one piece of state that does distinguish
 * them - IStream.machineBytes, the count of bytes handed to the resumable
 * machine - and hold it to a bound proportional to the number of chunk
 * boundaries rather than to the size of the array.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class StreamingArrayFastPathTest {

    /**
     * Bytes the resumable machine may consume per chunk boundary: at most one
     * field header, one count/fixlen word and one straddling element, each of
     * which is at most ten bytes. Generous on purpose - the defect this guards
     * hands it every byte of the array after the first boundary, which is orders
     * of magnitude more, so a loose bound still fails loudly on a regression
     * while never tripping on a legitimately straddling construct.
     */
    private static final int SLOW_BYTES_PER_BOUNDARY = 30;

    // --- the guard ----------------------------------------------------------

    @Test
    void unsignedArrayReturnsToTheFastPathAfterAStraddlingElement() throws IOException {
        long[] src = new long[5000];
        for (int i = 0; i < src.length; i++) {
            // Mixed widths (1..9 wire bytes) so elements straddle at odd offsets.
            src[i] = (i % 9 == 0) ? -1L >>> 1 : (1L << (i % 60)) + i;
        }
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));
        assertChunkedDecodeStaysOnTheFastPath(msg);
    }

    @Test
    void signedArrayReturnsToTheFastPathAfterAStraddlingElement() throws IOException {
        long[] src = new long[5000];
        for (int i = 0; i < src.length; i++) {
            src[i] = ((i & 1) == 0 ? 1 : -1) * ((1L << (i % 55)) + i);
        }
        byte[] msg = encode(os -> os.writeArraySigned(2, src));
        assertChunkedDecodeStaysOnTheFastPath(msg);
    }

    @Test
    void fp64ArrayReturnsToTheFastPathAfterAStraddlingElement() throws IOException {
        double[] src = new double[4000];
        for (int i = 0; i < src.length; i++) {
            src[i] = i * 1.5;
        }
        byte[] msg = encode(os -> os.writeArrayFp64(3, src));
        assertChunkedDecodeStaysOnTheFastPath(msg);
    }

    @Test
    void fp32ArrayReturnsToTheFastPathAfterAStraddlingElement() throws IOException {
        float[] src = new float[4000];
        for (int i = 0; i < src.length; i++) {
            src[i] = i * 0.25f;
        }
        byte[] msg = encode(os -> os.writeArrayFp32(4, src));
        assertChunkedDecodeStaysOnTheFastPath(msg);
    }

    /**
     * A boundary that lands exactly <em>between</em> two elements degrades the
     * remainder just as one inside an element does: the element loop stops feeding
     * the fast path when fewer than one element's bytes remain and arms the machine
     * at the end of the chunk. Here the first chunk ends on the fixlen_word and
     * every later one on an element boundary, so the machine should see one
     * eight-byte element per boundary and nothing else.
     */
    @Test
    void aBoundaryBetweenElementsCostsOneElement() throws IOException {
        double[] src = new double[2000];
        for (int i = 0; i < src.length; i++) {
            src[i] = -i;
        }
        byte[] msg = encode(os -> os.writeArrayFp64(3, src));
        // header (1) + count varint (2, for 2000) + fixlen_word (1) = 4 bytes.
        int head = 4;
        assertEquals(head + 8 * src.length, msg.length, "wire layout assumed by this test");

        Sum expected = new Sum();
        new IStream().feed(msg, expected);

        Sum actual = new Sum();
        IStream is = new IStream();
        DecodeStatus after = is.feed(msg, 0, head, actual);
        int boundaries = 1; // the one just fed
        for (int p = head; p < msg.length; p += 800) { // 100 whole elements per chunk
            after = is.feed(msg, p, Math.min(800, msg.length - p), actual);
            boundaries++;
        }

        assertEquals(DecodeStatus.COMPLETE, after);
        assertEquals(expected, actual);
        assertTrue(is.machineBytes <= 8L * boundaries,
                "a chunk boundary between elements must cost the byte-at-a-time machine at most "
                        + "the one element that follows it, but " + is.machineBytes + " bytes went "
                        + "through it over " + boundaries + " boundaries - the decoder never "
                        + "returned to the bulk element loop (corelib-java#74)");
    }

    /**
     * The control the issue reports as healthy: after a scalar the machine returns
     * to idle by itself, so a boundary costs one field however many follow it. It
     * is here so a regression in the shared {@code advanceAfterElement} path is
     * attributed to arrays rather than to streaming in general.
     */
    @Test
    void scalarFieldsAlreadyReturnToTheFastPath() throws IOException {
        byte[] msg = encode(os -> {
            for (int i = 0; i < 5000; i++) {
                os.writeUnsigned(1, (1L << (i % 60)) + i);
            }
        });
        assertChunkedDecodeStaysOnTheFastPath(msg);
    }

    // --- harness ------------------------------------------------------------

    /**
     * Feed {@code msg} whole and then in fixed-size chunks, asserting that every
     * chunking decodes to the same events and that the machine only ever sees the
     * straddling constructs. Chunk sizes are large enough that the bound is a real
     * constraint (a chunk of a few bytes is all boundary, and proves nothing).
     */
    private static void assertChunkedDecodeStaysOnTheFastPath(byte[] msg) throws SofabException {
        Sum expected = new Sum();
        new IStream().feed(msg, expected);

        for (int chunk : new int[] {997, 4096, 65536}) {
            Sum actual = new Sum();
            IStream is = new IStream();
            DecodeStatus after = null;
            for (int p = 0; p < msg.length; p += chunk) {
                after = is.feed(msg, p, Math.min(chunk, msg.length - p), actual);
            }
            int boundaries = (msg.length + chunk - 1) / chunk;
            long budget = (long) SLOW_BYTES_PER_BOUNDARY * boundaries;

            assertEquals(DecodeStatus.COMPLETE, after, "chunk " + chunk);
            assertEquals(expected, actual, "chunk " + chunk + " decoded differently");
            assertTrue(is.machineBytes <= budget,
                    "chunk " + chunk + ": " + is.machineBytes + " of " + msg.length + " bytes went "
                            + "through the byte-at-a-time machine, budget " + budget + " for "
                            + boundaries + " chunk boundaries. The decoder stayed in the resumable "
                            + "machine for the rest of the field instead of handing the chunk back "
                            + "to the bulk loop (corelib-java#74)");
        }
    }

    /** Encode a message into a buffer big enough for it and return the exact bytes. */
    private static byte[] encode(Emit body) throws IOException {
        byte[] buf = new byte[1 << 20];
        OStream os = new OStream(buf);
        body.write(os);
        byte[] out = new byte[os.bytesUsed()];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    @FunctionalInterface
    private interface Emit {
        void write(OStream os) throws IOException;
    }

    /**
     * Order-sensitive digest of the visitor callbacks. A list of a few thousand
     * event strings would say the same thing; this keeps the differential cheap
     * enough to run over arrays large enough for the bound to bite.
     */
    private static final class Sum implements Visitor {
        private long hash = 1469598103934665603L;
        private int events;

        private void mix(long v) {
            hash = (hash ^ v) * 1099511628211L;
            events++;
        }

        @Override public void unsigned(int id, long value) {
            mix(id * 31L + value);
        }

        @Override public void signed(int id, long value) {
            mix(id * 37L - value);
        }

        @Override public void fp32(int id, float value) {
            mix(id * 41L + Float.floatToRawIntBits(value));
        }

        @Override public void fp64(int id, double value) {
            mix(id * 43L + Double.doubleToRawLongBits(value));
        }

        @Override public void arrayBegin(int id, ArrayKind kind, int count) {
            mix(id * 47L + kind.ordinal() * 1000L + count);
        }

        @Override public boolean equals(Object o) {
            return o instanceof Sum other && other.hash == hash && other.events == events;
        }

        @Override public int hashCode() {
            return Long.hashCode(hash) * 31 + events;
        }

        @Override public String toString() {
            return events + " events, digest " + Long.toHexString(hash);
        }
    }
}
