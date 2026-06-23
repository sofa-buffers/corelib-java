/*
 * SofaBuffers Java - encode -> decode value-preservation tests.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class RoundTripTest {

    /** Visitor that captures every decoded value into typed lists. */
    private static final class Capture implements Visitor {
        final List<Long> unsigned = new ArrayList<>();
        final List<Long> signed = new ArrayList<>();
        final List<Float> f32 = new ArrayList<>();
        final List<Double> f64 = new ArrayList<>();
        final List<String> strings = new ArrayList<>();
        final List<String> seqEvents = new ArrayList<>();

        @Override public void unsigned(int id, long v) { unsigned.add(v); }
        @Override public void signed(int id, long v) { signed.add(v); }
        @Override public void fp32(int id, float v) { f32.add(v); }
        @Override public void fp64(int id, double v) { f64.add(v); }
        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            strings.add(new String(d, o, l, StandardCharsets.UTF_8));
        }
        @Override public void arrayBegin(int id, ArrayKind kind, int count) { seqEvents.add("arr:" + kind + ":" + count); }
        @Override public void sequenceBegin(int id) { seqEvents.add("seq{" + id); }
        @Override public void sequenceEnd() { seqEvents.add("seq}"); }
    }

    @FunctionalInterface
    private interface EncodeBody {
        void run(OStream os) throws IOException;
    }

    private static Capture roundtrip(EncodeBody body) throws IOException {
        byte[] buf = new byte[4096];
        OStream os = new OStream(buf);
        body.run(os);
        Capture c = new Capture();
        new IStream().feed(buf, 0, os.bytesUsed(), c);
        return c;
    }

    @Test
    void scalars() throws IOException {
        Capture c = roundtrip(os -> {
            os.writeUnsigned(1, 0xDEAD_BEEFL);
            os.writeUnsigned(2, -1L);                 // UINT64_MAX bit pattern
            os.writeSigned(3, -5_000_000_000_000L);
            os.writeSigned(4, Long.MIN_VALUE);
            os.writeBoolean(5, true);
            os.writeFp32(6, 3.14159f);
            os.writeFp64(7, 2.718281828459045);
        });
        assertEquals(List.of(0xDEAD_BEEFL, -1L, 1L), c.unsigned);
        assertEquals(List.of(-5_000_000_000_000L, Long.MIN_VALUE), c.signed);
        assertEquals(List.of(3.14159f), c.f32);
        assertEquals(List.of(2.718281828459045), c.f64);
    }

    @Test
    void stringsAndBlobs() throws IOException {
        byte[] blob = new byte[300];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 7);
        }
        byte[] buf = new byte[4096];
        OStream os = new OStream(buf);
        os.writeString(1, "grüße"); // multi-byte UTF-8
        os.writeBlob(2, blob);

        byte[][] captured = new byte[1][];
        List<String> texts = new ArrayList<>();
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
                texts.add(new String(d, o, l, StandardCharsets.UTF_8));
            }
            @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
                if (captured[0] == null) {
                    captured[0] = new byte[total];
                }
                System.arraycopy(d, o, captured[0], offset, l);
            }
        });
        assertEquals(List.of("grüße"), texts);
        org.junit.jupiter.api.Assertions.assertArrayEquals(blob, captured[0]);
    }

    @Test
    void arraysPreserveValues() throws IOException {
        long[] u = {0, 1, 0xFFFF_FFFF_FFFF_FFFFL, 0x1234_5678_9ABC_DEF0L};
        int[] s = {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE};
        double[] d = {1.5, -2.5, 1e300};

        Capture c = roundtrip(os -> {
            os.writeArrayUnsigned(1, u);
            os.writeArraySigned(2, s);
            os.writeArrayFp64(3, d);
        });

        assertEquals(Arrays.asList(0L, 1L, -1L, 0x1234_5678_9ABC_DEF0L), c.unsigned);
        assertEquals(Arrays.asList(0L, -1L, (long) Integer.MIN_VALUE, (long) Integer.MAX_VALUE), c.signed);
        assertEquals(Arrays.asList(1.5, -2.5, 1e300), c.f64);
    }

    @Test
    void nestedSequencesBalance() throws IOException {
        Capture c = roundtrip(os -> {
            os.writeUnsigned(1, 1);
            os.writeSequenceBegin(2);
            os.writeUnsigned(1, 2);
            os.writeSequenceBegin(3);
            os.writeUnsigned(1, 3);
            os.writeSequenceEnd();
            os.writeSequenceEnd();
        });
        assertEquals(List.of("seq{2", "seq{3", "seq}", "seq}"), c.seqEvents);
        assertEquals(List.of(1L, 2L, 3L), c.unsigned);
    }
}
