/*
 * SofaBuffers Java - byte-at-a-time state-machine coverage.
 *
 * The decoder has two paths: a contiguous fast path that advances a pointer over
 * a whole buffer, and a resumable byte-at-a-time state machine that takes over at
 * chunk boundaries. Most vectors use small field ids (single-byte headers) that
 * the fast path consumes directly, so this suite drives the *slow* path on
 * purpose: every field uses a field id >= 16 (a two-byte header) and arrays carry
 * 200 elements (a two-byte count), then the message is fed one byte at a time so
 * each header, count and value is reassembled by the state machine. Feeding
 * byte-by-byte and in odd chunks must reproduce the single-feed event stream.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class StateMachineCoverageTest {

    /** A message touching every wire type, all with two-byte (id >= 16) headers. */
    private static byte[] richMessage() throws IOException {
        long[] u64 = new long[200];
        int[] i32 = new int[200];
        for (int i = 0; i < 200; i++) {
            u64[i] = (long) i * 0x1_0000_0001L; // values that need multi-byte varints
            i32[i] = -i - 1;
        }
        byte[] buf = new byte[8192];
        OStream os = new OStream(buf);
        os.writeUnsigned(20, 0xDEAD_BEEF_CAFEL);
        os.writeSigned(21, -9_000_000_000L);
        os.writeBoolean(22, true);
        os.writeFp32(23, 3.14159f);
        os.writeFp64(24, 2.718281828459045);
        os.writeString(25, "a reasonably long string crossing chunk boundaries");
        os.writeBlob(26, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10});
        os.writeArrayUnsigned(27, u64);          // 200 elements -> two-byte count
        os.writeArraySigned(28, i32);
        os.writeArrayFp32(29, new float[] {1.5f, -2.5f, 3.5f});
        os.writeArrayFp64(30, new double[] {1.25, -2.5, 3.75});
        os.writeSequenceBegin(31);
        os.writeUnsigned(20, 7);
        os.writeSequenceEnd();
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /** Decode a buffer in fixed-size chunks through one decoder. */
    private static List<String> feedInChunks(byte[] data, int chunk) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream is = new IStream();
        for (int i = 0; i < data.length; i += chunk) {
            is.feed(data, i, Math.min(chunk, data.length - i), v);
        }
        return v.events;
    }

    @Test
    void slowPathMatchesFastPathForAllWireTypes() throws IOException {
        byte[] msg = richMessage();

        RecordingVisitor whole = new RecordingVisitor();
        new IStream().feed(msg, whole);

        // One byte at a time, and a few odd chunk sizes, must all agree with a
        // single feed -- exercising the state machine for every wire type, the
        // multi-byte header/count accumulation and every fixlen subtype.
        assertEquals(whole.events, feedInChunks(msg, 1));
        assertEquals(whole.events, feedInChunks(msg, 2));
        assertEquals(whole.events, feedInChunks(msg, 5));
        assertEquals(whole.events, feedInChunks(msg, 13));
    }

    // --- malformed input reaching the state machine (multi-byte, fed in pieces) --

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static SofabError slowError(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            IStream is = new IStream();
            for (byte b : data) {
                is.feed(new byte[] {b}, new Visitor() { });
            }
        });
        return ex.error();
    }

    @Test
    void idOverflowViaStateMachine() {
        // Five-byte header whose id exceeds ID_MAX; reassembled byte-by-byte.
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x80, 0x80, 0x80, 0x80, 0x40)));
    }

    @Test
    void varintOverflowViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG,
                slowError(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80)));
    }

    @Test
    void fixlenLengthAboveMaxViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x02, 0x82, 0x80, 0x80, 0x80, 0x40)));
    }

    @Test
    void fp64WrongLengthViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x02, 0x21)));
    }

    @Test
    void reservedFixlenTypeViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x02, 0x04)));
    }

    @Test
    void stringAsFixlenArrayElementViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x05, 0x01, 0x0A)));
    }

    @Test
    void zeroLengthArrayViaStateMachine() {
        assertEquals(SofabError.INVALID_MSG, slowError(bytes(0x03, 0x00)));
    }
}
