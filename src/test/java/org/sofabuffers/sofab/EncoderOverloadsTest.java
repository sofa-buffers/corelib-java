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

    /** Records arrayBegin(count) plus any element callbacks, to prove a zero-count array fires once and carries no elements. */
    private static final class ArrayRecorder implements Visitor {
        int beginCount = -1;
        int beginCalls;
        int elements;
        @Override public void arrayBegin(int id, ArrayKind kind, int count) { beginCount = count; beginCalls++; }
        @Override public void unsigned(int id, long v) { elements++; }
        @Override public void signed(int id, long v) { elements++; }
        @Override public void fp32(int id, float v) { elements++; }
        @Override public void fp64(int id, double v) { elements++; }
    }

    @Test
    void emptyUnsignedArrayRoundTrips() throws IOException {
        byte[] buf = new byte[16];
        OStream os = new OStream(buf);
        os.writeArrayUnsigned(7, new int[0]);
        // Wire form is exactly [ header = (7<<3)|3 ][ count = 0 ].
        assertEquals(2, os.bytesUsed());
        assertArrayEquals(new byte[] {(byte) ((7 << 3) | 3), 0}, Arrays.copyOf(buf, 2));

        ArrayRecorder rec = new ArrayRecorder();
        new IStream().feed(buf, 0, os.bytesUsed(), rec);
        assertEquals(1, rec.beginCalls);
        assertEquals(0, rec.beginCount);
        assertEquals(0, rec.elements);
    }

    @Test
    void emptySignedArrayRoundTrips() throws IOException {
        byte[] buf = new byte[16];
        OStream os = new OStream(buf);
        os.writeArraySigned(7, new long[0]);
        assertEquals(2, os.bytesUsed());
        assertArrayEquals(new byte[] {(byte) ((7 << 3) | 4), 0}, Arrays.copyOf(buf, 2));

        ArrayRecorder rec = new ArrayRecorder();
        new IStream().feed(buf, 0, os.bytesUsed(), rec);
        assertEquals(1, rec.beginCalls);
        assertEquals(0, rec.beginCount);
        assertEquals(0, rec.elements);
    }

    @Test
    void emptyFixlenArraysCarryTheFixlenWord() throws IOException {
        // §4.8: a zero-count fp32/fp64 array is [ header ][ count = 0 ][ fixlen_word ]
        // — the fixlen_word is always present so an empty fp32 array is
        // distinguishable on the wire from an empty fp64 array.
        byte[] buf = new byte[16];

        OStream os = new OStream(buf);
        os.writeArrayFp32(7, new float[0]);
        assertEquals(3, os.bytesUsed());
        // fixlen_word 0x20 = (4 << 3) | fp32-subtype(0).
        assertArrayEquals(new byte[] {(byte) ((7 << 3) | 5), 0, 0x20}, Arrays.copyOf(buf, 3));
        ArrayRecorder rec = new ArrayRecorder();
        new IStream().feed(buf, 0, os.bytesUsed(), rec);
        assertEquals(1, rec.beginCalls);
        assertEquals(0, rec.beginCount);
        assertEquals(0, rec.elements);

        os = new OStream(buf);
        os.writeArrayFp64(7, new double[0]);
        assertEquals(3, os.bytesUsed());
        // fixlen_word 0x41 = (8 << 3) | fp64-subtype(1).
        assertArrayEquals(new byte[] {(byte) ((7 << 3) | 5), 0, 0x41}, Arrays.copyOf(buf, 3));
        rec = new ArrayRecorder();
        new IStream().feed(buf, 0, os.bytesUsed(), rec);
        assertEquals(1, rec.beginCalls);
        assertEquals(0, rec.beginCount);
        assertEquals(0, rec.elements);
    }

    @Test
    void emptyArrayFollowedByFieldDecodesBoth() throws IOException {
        // An empty fixlen array must not swallow the next field's bytes (no phantom fixlen_word).
        byte[] buf = new byte[32];
        OStream os = new OStream(buf);
        os.writeArrayFp32(1, new float[0]);
        os.writeUnsigned(2, 42);
        int len = os.bytesUsed();

        // Whole-buffer and byte-at-a-time feeds must agree.
        for (int chunk : new int[] {len, 1}) {
            List<Long> unsigned = new ArrayList<>();
            int[] beginCount = {-1};
            Visitor v = new Visitor() {
                @Override public void arrayBegin(int id, ArrayKind kind, int count) { beginCount[0] = count; }
                @Override public void unsigned(int id, long val) { unsigned.add(val); }
            };
            IStream is = new IStream();
            for (int o = 0; o < len; o += chunk) {
                is.feed(buf, o, Math.min(chunk, len - o), v);
            }
            assertEquals(0, beginCount[0]);
            assertEquals(List.of(42L), unsigned);
        }
    }

    @Test
    void writeSequenceEndWithoutOpenSequenceRejected() {
        // Empty arrays are now accepted (see tests above); the encoder's remaining
        // structural guard is an unbalanced sequence end.
        assertEquals(SofabError.USAGE,
                assertThrows(SofabException.class, () -> new OStream(new byte[16]).writeSequenceEnd()).error());
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
