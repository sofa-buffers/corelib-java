/*
 * SofaBuffers Java - encoder tests (byte-exact vs. the C reference vectors).
 *
 * The expected byte arrays are copied verbatim from the C corelib reference
 * suite (test/c/test_ostream.c) to guarantee byte-for-byte interoperability.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class OStreamTest {

    /** Encode via {@code body} into a fresh buffer and return exactly the used bytes. */
    private static byte[] encode(EncodeBody body) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        body.run(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    @FunctionalInterface
    private interface EncodeBody {
        void run(OStream os) throws IOException;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void unsignedIdMin() throws IOException {
        assertArrayEquals(bytes(0x00, 0x00), encode(os -> os.writeUnsigned(0, 0)));
    }

    @Test
    void unsignedIdMax() throws IOException {
        assertArrayEquals(
                bytes(0xF8, 0xFF, 0xFF, 0xFF, 0x3F, 0x00),
                encode(os -> os.writeUnsigned(Integer.MAX_VALUE, 0)));
    }

    @Test
    void unsignedMax() throws IOException {
        // UINT64_MAX -> ten 0xFF payload bytes then 0x01.
        assertArrayEquals(
                bytes(0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeUnsigned(0, -1L)));
    }

    @Test
    void signedMin() throws IOException {
        assertArrayEquals(
                bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeSigned(0, Long.MIN_VALUE)));
    }

    @Test
    void signedMax() throws IOException {
        assertArrayEquals(
                bytes(0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeSigned(0, Long.MAX_VALUE)));
    }

    @Test
    void booleanTrue() throws IOException {
        assertArrayEquals(bytes(0x00, 0x01), encode(os -> os.writeBoolean(0, true)));
    }

    @Test
    void fp32() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x20, 0x56, 0x0E, 0x49, 0x40),
                encode(os -> os.writeFp32(0, 3.1415f)));
    }

    @Test
    void fp64() throws IOException {
        // The C reference widens a float literal: (double) 3.14159265f.
        assertArrayEquals(
                bytes(0x02, 0x41, 0x00, 0x00, 0x00, 0x60, 0xFB, 0x21, 0x09, 0x40),
                encode(os -> os.writeFp64(0, (double) 3.14159265f)));
    }

    @Test
    void string() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x62, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x43, 0x6F, 0x75, 0x63, 0x68, 0x21),
                encode(os -> os.writeString(0, "Hello Couch!")));
    }

    @Test
    void stringEmpty() throws IOException {
        assertArrayEquals(bytes(0x02, 0x02), encode(os -> os.writeString(0, "")));
    }

    @Test
    void blob() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x2B, 0x01, 0x02, 0x03, 0x04, 0x05),
                encode(os -> os.writeBlob(0, bytes(0x01, 0x02, 0x03, 0x04, 0x05))));
    }

    @Test
    void blobEmpty() throws IOException {
        assertArrayEquals(bytes(0x02, 0x03), encode(os -> os.writeBlob(0, new byte[0])));
    }

    @Test
    void arrayUnsigned32() throws IOException {
        int[] a = {1, 2, 3, 0x80000000, 0xFFFFFFFF};
        assertArrayEquals(
                bytes(0x03, 0x05, 0x01, 0x02, 0x03, 0x80, 0x80, 0x80, 0x80, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
                encode(os -> os.writeArrayUnsigned(0, a)));
    }

    @Test
    void arrayUnsigned16() throws IOException {
        short[] a = {1, 2, 3, 0, (short) 0xFFFF};
        assertArrayEquals(
                bytes(0x03, 0x05, 0x01, 0x02, 0x03, 0x00, 0xFF, 0xFF, 0x03),
                encode(os -> os.writeArrayUnsigned(0, a)));
    }

    @Test
    void arraySigned32() throws IOException {
        int[] a = {-1, -2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE};
        assertArrayEquals(
                bytes(0x04, 0x05, 0x01, 0x03, 0x05, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0xFE, 0xFF, 0xFF, 0xFF, 0x0F),
                encode(os -> os.writeArraySigned(0, a)));
    }

    @Test
    void arrayFp32() throws IOException {
        float[] a = {1.0f, 2.0f, 3.0f, -Float.MAX_VALUE, Float.MAX_VALUE};
        assertArrayEquals(
                bytes(0x05, 0x05, 0x20, 0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x40, 0x00,
                        0x00, 0x40, 0x40, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0x7F, 0x7F),
                encode(os -> os.writeArrayFp32(0, a)));
    }

    @Test
    void arrayFp64() throws IOException {
        double[] a = {1.0, 2.0, 3.0, -Double.MAX_VALUE, Double.MAX_VALUE};
        assertArrayEquals(
                bytes(0x05, 0x05, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x08, 0x40, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0xFF, 0xFF,
                        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0x7F),
                encode(os -> os.writeArrayFp64(0, a)));
    }

    @Test
    void nestedSequence() throws IOException {
        assertArrayEquals(
                bytes(0x00, 0x2A, 0x0E, 0x00, 0x2A, 0x11, 0x53, 0x07, 0x11, 0x53),
                encode(os -> {
                    os.writeUnsigned(0, 42);
                    os.writeSequenceBegin(1);
                    os.writeUnsigned(0, 42);
                    os.writeSigned(2, -42);
                    os.writeSequenceEnd();
                    os.writeSigned(2, -42);
                }));
    }

    @Test
    void nestedSequenceWithArray() throws IOException {
        assertArrayEquals(
                bytes(0x00, 0x2A, 0x1E, 0x00, 0x2A, 0x1C, 0x03, 0x53, 0x55, 0x57, 0x07, 0x11, 0x53),
                encode(os -> {
                    os.writeUnsigned(0, 42);
                    os.writeSequenceBegin(3);
                    os.writeUnsigned(0, 42);
                    os.writeArraySigned(3, new int[] {-42, -43, -44});
                    os.writeSequenceEnd();
                    os.writeSigned(2, -42);
                }));
    }

    // --- error / argument handling -----------------------------------------

    @Test
    void idOverflowRejected() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[16]).writeUnsigned(-1, 0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void bufferFullWithoutSink() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[2]).writeUnsigned(0, -1L));
        assertEquals(SofabError.BUFFER_FULL, ex.error());
    }

    @Test
    void maxDepthNestingAccepted() throws IOException {
        // Opening (and closing) MAX_DEPTH = 255 nested sequences is the deepest legal nesting.
        byte[] buf = new byte[2 * Sofab.MAX_DEPTH];
        OStream os = new OStream(buf);
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBegin(0);
        }
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceEnd();
        }
        assertEquals(2 * Sofab.MAX_DEPTH, os.bytesUsed());
    }

    @Test
    void nestingBeyondMaxDepthRejected() throws IOException {
        OStream os = new OStream(new byte[2 * Sofab.MAX_DEPTH + 8]);
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBegin(0);
        }
        SofabException ex = assertThrows(SofabException.class, () -> os.writeSequenceBegin(0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void unbalancedSequenceEndRejected() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[16]).writeSequenceEnd());
        assertEquals(SofabError.USAGE, ex.error());
    }
}
