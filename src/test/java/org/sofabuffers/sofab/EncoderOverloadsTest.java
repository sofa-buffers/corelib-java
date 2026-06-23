/*
 * SofaBuffers Java - exercises every OStream writer overload and its argument
 * validation, decoding the result back to confirm the values survive.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class EncoderOverloadsTest {

    private static List<Long> decodeUnsigned(byte[] buf, int len) throws SofabException {
        List<Long> out = new ArrayList<>();
        new IStream().feed(buf, 0, len, new Visitor() {
            @Override public void unsigned(int id, long v) { out.add(v); }
        });
        return out;
    }

    private static List<Long> decodeSigned(byte[] buf, int len) throws SofabException {
        List<Long> out = new ArrayList<>();
        new IStream().feed(buf, 0, len, new Visitor() {
            @Override public void signed(int id, long v) { out.add(v); }
        });
        return out;
    }

    @Test
    void unsignedArrayOverloads() throws IOException {
        byte[] buf = new byte[64];

        OStream os = new OStream(buf);
        os.writeArrayUnsigned(1, new byte[] {0, 1, (byte) 0xFF});      // 8-bit -> 0,1,255
        assertEquals(Arrays.asList(0L, 1L, 255L), decodeUnsigned(buf, os.bytesUsed()));

        os = new OStream(buf);
        os.writeArrayUnsigned(1, new long[] {0L, -1L, 0x1234_5678_9ABC_DEF0L}); // 64-bit
        assertEquals(Arrays.asList(0L, -1L, 0x1234_5678_9ABC_DEF0L), decodeUnsigned(buf, os.bytesUsed()));
    }

    @Test
    void signedArrayOverloads() throws IOException {
        byte[] buf = new byte[64];

        OStream os = new OStream(buf);
        os.writeArraySigned(1, new byte[] {-1, 0, 1, Byte.MIN_VALUE});
        assertEquals(Arrays.asList(-1L, 0L, 1L, (long) Byte.MIN_VALUE), decodeSigned(buf, os.bytesUsed()));

        os = new OStream(buf);
        os.writeArraySigned(1, new short[] {-1, 0, 1, Short.MIN_VALUE});
        assertEquals(Arrays.asList(-1L, 0L, 1L, (long) Short.MIN_VALUE), decodeSigned(buf, os.bytesUsed()));

        os = new OStream(buf);
        os.writeArraySigned(1, new long[] {Long.MIN_VALUE, -1L, Long.MAX_VALUE});
        assertEquals(Arrays.asList(Long.MIN_VALUE, -1L, Long.MAX_VALUE), decodeSigned(buf, os.bytesUsed()));
    }

    @Test
    void fp32ArrayRoundtrips() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeArrayFp32(1, new float[] {1.5f, -2.5f, 3.25f});
        List<Float> out = new ArrayList<>();
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void fp32(int id, float v) { out.add(v); }
        });
        assertEquals(Arrays.asList(1.5f, -2.5f, 3.25f), out);
    }

    @Test
    void booleanFalse() throws IOException {
        byte[] buf = new byte[8];
        OStream os = new OStream(buf);
        os.writeBoolean(9, false);
        assertEquals(List.of(0L), decodeUnsigned(buf, os.bytesUsed()));
    }

    @Test
    void blobSlice() throws IOException {
        byte[] buf = new byte[32];
        byte[] src = {9, 9, 1, 2, 3, 9};
        OStream os = new OStream(buf);
        os.writeBlob(1, src, 2, 3); // only {1,2,3}
        byte[][] got = new byte[1][];
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
                got[0] = Arrays.copyOfRange(d, o, o + l);
            }
        });
        assertArrayEquals(new byte[] {1, 2, 3}, got[0]);
    }

    @Test
    void writeFixlenRejectsNegativeLength() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[16]).writeFixlen(0, new byte[4], 0, -1, FixlenType.BLOB));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void emptyArraysRejected() {
        OStream os = new OStream(new byte[16]);
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> os.writeArrayUnsigned(1, new int[0])).error());
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> os.writeArraySigned(1, new long[0])).error());
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> os.writeArrayFp32(1, new float[0])).error());
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> os.writeArrayFp64(1, new double[0])).error());
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(IllegalArgumentException.class, () -> new OStream(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> new OStream(new byte[8], 9));
        assertThrows(IllegalArgumentException.class, () -> new OStream(null));
    }

    @Test
    void bufferSetSwapsBufferAndValidates() throws IOException {
        OStream os = new OStream(new byte[4]);
        byte[] fresh = new byte[16];
        os.bufferSet(fresh, 2); // swap in a new buffer, reserving 2 bytes up front
        os.writeUnsigned(7, 42);
        assertEquals(2 + 2, os.bytesUsed()); // 2 reserved + header(1) + value(1)

        // The field lands in the new buffer, after the reserved prefix.
        List<Long> got = new ArrayList<>();
        new IStream().feed(fresh, 2, os.bytesUsed() - 2, new Visitor() {
            @Override public void unsigned(int id, long v) {
                assertEquals(7, id);
                got.add(v);
            }
        });
        assertEquals(List.of(42L), got);

        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(new byte[0], 0));
        assertThrows(IllegalArgumentException.class, () -> os.bufferSet(new byte[4], 99));
    }
}
