/*
 * SofaBuffers Java - MIN_OUTPUT_BUFFER: the declared streaming minimum, the
 * guard on every sink-installed buffer, and the one-shot path it must not bind
 * (CORELIB_PLAN §5.1, §7.2 item 4).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MinOutputBufferTest {

    /** Everything a flush divides: a 10-byte varint, a long string, an fp64, an array. */
    private static void message(OStream os) throws IOException {
        os.writeUnsigned(1, -1L);                        // 10-byte varint
        os.writeString(2, "the quick brown fox jumps over the lazy dog");
        os.writeFp64(3, Math.PI);
        os.writeArrayUnsigned(4, new long[] {1, 300, 70000, Long.MIN_VALUE});
    }

    private static byte[] oneShot() throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        message(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /** The constant exists and stays inside the normative ceiling (§5.1). */
    @Test
    void constantIsDeclaredAndWithinTheCeiling() {
        assertTrue(Sofab.MIN_OUTPUT_BUFFER >= 1, "a declaration below 1 is meaningless");
        assertTrue(Sofab.MIN_OUTPUT_BUFFER <= 20, "§5.1 caps the declaration at 20");
    }

    /**
     * §7.2 item 4: encode into a buffer of exactly {@code MIN_OUTPUT_BUFFER} bytes,
     * driving the sink repeatedly, and get output byte-identical to the one-shot
     * path. The message carries a string longer than the buffer, so the divisible
     * run is what the flush splits.
     */
    @Test
    void encodingAtExactlyTheMinimumMatchesTheOneShotOutput() throws IOException {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        OStream os = new OStream(new byte[Sofab.MIN_OUTPUT_BUFFER], 0, acc::write);
        message(os);
        os.flush();
        assertArrayEquals(oneShot(), acc.toByteArray());
    }

    /** Any size at or above the minimum produces the same bytes. */
    @Test
    void everySizeAtOrAboveTheMinimumMatchesTheOneShotOutput() throws IOException {
        byte[] reference = oneShot();
        for (int size : new int[] {1, 2, 3, 7, 20, 64}) {
            if (size < Sofab.MIN_OUTPUT_BUFFER) {
                continue;
            }
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            OStream os = new OStream(new byte[size], 0, acc::write);
            message(os);
            os.flush();
            assertArrayEquals(reference, acc.toByteArray(), "buffer size " + size);
        }
    }

    /**
     * A sink-installed buffer one byte short of the minimum is rejected <b>where it
     * is handed over</b> — at construction, by the same mechanism as an out-of-range
     * offset — not partway through a message.
     */
    @Test
    void constructorRejectsAnUndersizedSinkBuffer() {
        int room = Sofab.MIN_OUTPUT_BUFFER - 1;
        byte[] buf = new byte[8];
        FlushSink sink = (data, off, len) -> { };
        assertThrows(IllegalArgumentException.class,
                () -> new OStream(buf, buf.length - room, sink));
    }

    /** The same rejection at every mid-stream buffer-set. */
    @Test
    void bufferSetRejectsAnUndersizedSinkBuffer() {
        int room = Sofab.MIN_OUTPUT_BUFFER - 1;
        OStream os = new OStream(new byte[8], 0, (data, off, len) -> { });
        byte[] fresh = new byte[8];
        assertThrows(IllegalArgumentException.class,
                () -> os.bufferSet(fresh, fresh.length - room));
    }

    /**
     * The converse, and the reason the guard is confined to the sink path: the same
     * undersized buffer <b>without</b> a sink is accepted, and a message that fits
     * encodes into it. §5.1 puts no minimum on a buffer that cannot flush, and a
     * caller sizing from the generated {@code MAX_SIZE} depends on that being exact.
     */
    @Test
    void aSinklessBufferIsSubjectToNoMinimum() throws IOException {
        int room = Sofab.MIN_OUTPUT_BUFFER - 1;
        byte[] buf = new byte[8];
        OStream reserved = new OStream(buf, buf.length - room);
        assertEquals(buf.length - room, reserved.bytesUsed());

        // Exact sizing stays exact: a two-byte message into a two-byte buffer.
        byte[] tight = new byte[2];
        OStream os = new OStream(tight);
        os.writeUnsigned(1, 1);
        assertEquals(2, os.bytesUsed());
        assertArrayEquals(new byte[] {0x08, 0x01}, tight);
    }

    /**
     * The repro from the report: a zero-usable-byte buffer with a sink used to be
     * accepted, and the first flush emitted the untouched reservation as if it were
     * message content.
     */
    @Test
    void aZeroRoomSinkBufferNeverReachesTheSink() {
        ByteArrayOutputStream acc = new ByteArrayOutputStream();
        byte[] buf = new byte[4];
        Arrays.fill(buf, (byte) 0x5A);
        assertThrows(IllegalArgumentException.class,
                () -> new OStream(buf, 4, acc::write));
        assertEquals(0, acc.size());
    }
}
