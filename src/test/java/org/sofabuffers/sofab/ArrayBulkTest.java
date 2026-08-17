/*
 * SofaBuffers Java - the bulk offer for integer arrays (Visitor.arrayBulk).
 *
 * The offer is a fast path, so the only thing worth asserting about it is that
 * it is INVISIBLE: a visitor that takes it must end up with exactly the values a
 * visitor that declines it receives one at a time, at every chunking of the
 * input. These tests hold it to that, byte boundary by byte boundary, because
 * the fill is written from two places -- the bulk element loops and the resumable
 * byte-at-a-time machine -- and a message can cross between them at any element.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ArrayBulkTest {

    // --- visitors -----------------------------------------------------------

    /** Declines the offer: collects elements through the per-element callbacks. */
    private static final class PerElement implements Visitor {
        final List<Long> values = new ArrayList<>();

        @Override
        public void unsigned(int id, long value) {
            values.add(value);
        }

        @Override
        public void signed(int id, long value) {
            values.add(value);
        }
    }

    /** Takes the offer: hands over an exactly-sized destination. */
    private static final class Bulk implements Visitor {
        long[] dst;
        int endN = -1;
        int endId = -1;
        int ends;
        int offers;
        final List<Long> scalars = new ArrayList<>(); // anything NOT taken in bulk

        @Override
        public void unsigned(int id, long value) {
            scalars.add(value);
        }

        @Override
        public void signed(int id, long value) {
            scalars.add(value);
        }

        @Override
        public Object arrayBulk(int id, ArrayKind kind, int count) {
            offers++;
            dst = new long[count];
            return dst;
        }

        @Override
        public void arrayBulkEnd(int id, int n) {
            ends++;
            endId = id;
            endN = n;
        }
    }

    /** Offers a destination one element too short: the decoder must refuse it. */
    private static final class TooShort implements Visitor {
        final List<Long> values = new ArrayList<>();
        int ends;

        @Override
        public void unsigned(int id, long value) {
            values.add(value);
        }

        @Override
        public Object arrayBulk(int id, ArrayKind kind, int count) {
            return new long[Math.max(0, count - 1)];
        }

        @Override
        public void arrayBulkEnd(int id, int n) {
            ends++;
        }
    }

    // --- helpers ------------------------------------------------------------

    private interface Write {
        void run(OStream os) throws IOException;
    }

    private static byte[] encode(Write w) throws IOException {
        byte[] buf = new byte[1 << 16];
        OStream os = new OStream(buf);
        w.run(os);
        byte[] out = new byte[os.bytesUsed()];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    private static long[] boxedToLongs(List<Long> l) {
        long[] a = new long[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }

    /** Mixed 1..9-byte varints, so elements straddle at every kind of offset. */
    private static long[] mixedUnsigned(int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = (i % 11 == 0) ? -1L : (1L << (i % 62)) + i;
        }
        return a;
    }

    private static long[] mixedSigned(int n) {
        long[] a = new long[n];
        for (int i = 0; i < n; i++) {
            a[i] = ((i & 1) == 0 ? 1 : -1) * ((1L << (i % 55)) + i);
        }
        return a;
    }

    // --- the offer is invisible ---------------------------------------------

    @Test
    void unsignedBulkMatchesPerElement() throws IOException {
        long[] src = mixedUnsigned(300);
        byte[] msg = encode(os -> os.writeArrayUnsigned(7, src));

        PerElement one = new PerElement();
        new IStream().feed(msg, one);

        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertArrayEquals(src, boxedToLongs(one.values), "per-element decode");
        assertArrayEquals(src, bulk.dst, "bulk decode");
        assertEquals(1, bulk.offers);
        assertEquals(1, bulk.ends);
        assertEquals(7, bulk.endId);
        assertEquals(src.length, bulk.endN);
        assertTrue(bulk.scalars.isEmpty(), "no element may reach the per-element callbacks");
    }

    @Test
    void signedBulkMatchesPerElementAndIsZigZagDecoded() throws IOException {
        long[] src = mixedSigned(300);
        byte[] msg = encode(os -> os.writeArraySigned(3, src));

        PerElement one = new PerElement();
        new IStream().feed(msg, one);

        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertArrayEquals(src, boxedToLongs(one.values));
        assertArrayEquals(src, bulk.dst, "a bulk element is the value signed() would deliver");
        assertTrue(bulk.scalars.isEmpty());
    }

    /**
     * The one that matters. The fill is written by the bulk element loop while a
     * whole element is in hand and by the byte-at-a-time machine when one is not,
     * and a chunk boundary can fall anywhere -- including inside an element, on an
     * element boundary, inside the count word and inside the field header. Feeding
     * the same message split at EVERY offset walks all of those.
     */
    @Test
    void everyChunkBoundaryDecodesIdentically() throws IOException {
        long[] src = mixedUnsigned(40);
        byte[] msg = encode(os -> os.writeArrayUnsigned(2, src));

        for (int cut = 0; cut <= msg.length; cut++) {
            Bulk bulk = new Bulk();
            IStream is = new IStream();
            is.feed(msg, 0, cut, bulk);
            is.feed(msg, cut, msg.length - cut, bulk);

            assertEquals(DecodeStatus.COMPLETE, is.status(), "cut at " + cut);
            assertArrayEquals(src, bulk.dst, "cut at " + cut);
            assertEquals(1, bulk.ends, "arrayBulkEnd fires exactly once, cut at " + cut);
            assertEquals(src.length, bulk.endN, "cut at " + cut);
            assertTrue(bulk.scalars.isEmpty(), "cut at " + cut);
        }
    }

    /** One byte at a time: every element goes through the machine, none through the loop. */
    @Test
    void byteAtATimeDecodesIdentically() throws IOException {
        long[] src = mixedSigned(60);
        byte[] msg = encode(os -> os.writeArraySigned(1, src));

        Bulk bulk = new Bulk();
        IStream is = new IStream();
        for (byte b : msg) {
            is.feed(new byte[] {b}, bulk);
        }
        assertEquals(DecodeStatus.COMPLETE, is.status());
        assertArrayEquals(src, bulk.dst);
        assertEquals(1, bulk.ends);
        assertEquals(src.length, bulk.endN);
    }

    // --- a narrow destination declares a width ------------------------------

    /** Takes the offer with an array of the width the test asks for. */
    private static final class Narrow implements Visitor {
        private final int width; // 1, 2, 4 or 8 bytes
        Object dst;
        int ends;
        final List<Long> scalars = new ArrayList<>();

        Narrow(int width) {
            this.width = width;
        }

        @Override
        public void unsigned(int id, long value) {
            scalars.add(value);
        }

        @Override
        public void signed(int id, long value) {
            scalars.add(value);
        }

        @Override
        public Object arrayBulk(int id, ArrayKind kind, int count) {
            dst = switch (width) {
                case 1 -> new byte[count];
                case 2 -> new short[count];
                case 4 -> new int[count];
                default -> new long[count];
            };
            return dst;
        }

        @Override
        public void arrayBulkEnd(int id, int n) {
            ends++;
        }
    }

    @Test
    void anUnsignedArrayFillsAByteDestinationWithTheDeclaredWidthsBits() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, new long[] {0, 1, 127, 128, 200, 255}));
        Narrow v = new Narrow(1);
        new IStream().feed(msg, v);

        // The bits of the VALUE, read back through a signed Java byte.
        assertArrayEquals(new byte[] {0, 1, 127, -128, -56, -1}, (byte[]) v.dst);
        assertEquals(1, v.ends);
        assertTrue(v.scalars.isEmpty());
    }

    @Test
    void aSignedArrayFillsAByteDestinationExactly() throws IOException {
        byte[] msg = encode(os -> os.writeArraySigned(1, new long[] {-128, -1, 0, 1, 127}));
        Narrow v = new Narrow(1);
        new IStream().feed(msg, v);

        assertArrayEquals(new byte[] {-128, -1, 0, 1, 127}, (byte[]) v.dst);
    }

    @Test
    void shortAndIntDestinationsTakeTheirWidths() throws IOException {
        byte[] m16 = encode(os -> os.writeArrayUnsigned(1, new long[] {0, 32768, 65535}));
        Narrow v16 = new Narrow(2);
        new IStream().feed(m16, v16);
        assertArrayEquals(new short[] {0, (short) 32768, (short) 65535}, (short[]) v16.dst);

        byte[] m32 = encode(os -> os.writeArrayUnsigned(1, new long[] {0, 2147483648L, 4294967295L}));
        Narrow v32 = new Narrow(4);
        new IStream().feed(m32, v32);
        assertArrayEquals(new int[] {0, (int) 2147483648L, (int) 4294967295L}, (int[]) v32.dst);
    }

    /**
     * The point of narrowing in the decoder rather than after it: a value the
     * destination cannot hold is malformed input, not something to truncate. One
     * bit past the width is enough, and it is INVALID for the unsigned and the
     * signed reading alike.
     */
    @Test
    void aValueWiderThanTheDestinationIsInvalid() throws IOException {
        record Case(int width, boolean signed, long value) {}
        List<Case> cases = List.of(
                new Case(1, false, 256), new Case(1, false, -1),
                new Case(2, false, 65536), new Case(4, false, 4294967296L),
                new Case(1, true, 128), new Case(1, true, -129),
                new Case(2, true, 32768), new Case(4, true, 2147483648L));
        for (Case c : cases) {
            byte[] msg = c.signed()
                    ? encode(os -> os.writeArraySigned(1, new long[] {0, c.value()}))
                    : encode(os -> os.writeArrayUnsigned(1, new long[] {0, c.value()}));
            Narrow v = new Narrow(c.width());
            IStream is = new IStream();
            SofabException e = org.junit.jupiter.api.Assertions.assertThrows(SofabException.class,
                    () -> is.feed(msg, v), c + " must not decode");
            assertEquals(SofabError.INVALID_MSG, e.error(), c.toString());
            assertEquals(DecodeStatus.INVALID, is.status(), c + " is terminal");
        }
    }

    /** The widest value each width DOES hold, so the guard above is not just strict. */
    @Test
    void theWidestValueEachWidthHoldsStillDecodes() throws IOException {
        byte[] u8 = encode(os -> os.writeArrayUnsigned(1, new long[] {255}));
        Narrow v8 = new Narrow(1);
        new IStream().feed(u8, v8);
        assertArrayEquals(new byte[] {-1}, (byte[]) v8.dst);

        byte[] i32 = encode(os -> os.writeArraySigned(1, new long[] {-2147483648L, 2147483647L}));
        Narrow v32 = new Narrow(4);
        new IStream().feed(i32, v32);
        assertArrayEquals(new int[] {-2147483648, 2147483647}, (int[]) v32.dst);
    }

    /** A narrow fill crosses a feed boundary exactly like the long one. */
    @Test
    void aNarrowFillSurvivesEveryChunkBoundary() throws IOException {
        long[] src = {0, 1, 200, 255, 128, 64, 7};
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));
        byte[] want = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            want[i] = (byte) src[i];
        }
        for (int cut = 0; cut <= msg.length; cut++) {
            Narrow v = new Narrow(1);
            IStream is = new IStream();
            is.feed(msg, 0, cut, v);
            is.feed(msg, cut, msg.length - cut, v);
            assertEquals(DecodeStatus.COMPLETE, is.status(), "cut at " + cut);
            assertArrayEquals(want, (byte[]) v.dst, "cut at " + cut);
            assertEquals(1, v.ends, "cut at " + cut);
        }
    }

    /** A type the decoder cannot fill is refused, not guessed at. */
    @Test
    void aNonIntegerDestinationIsRefused() throws IOException {
        long[] src = mixedUnsigned(6);
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));
        List<Long> got = new ArrayList<>();
        Visitor v = new Visitor() {
            @Override
            public void unsigned(int id, long value) {
                got.add(value);
            }

            @Override
            public Object arrayBulk(int id, ArrayKind kind, int count) {
                return new float[count];
            }
        };
        new IStream().feed(msg, v);
        assertArrayEquals(src, boxedToLongs(got), "every element still arrives per-element");
    }

    // --- what is NOT offered ------------------------------------------------

    @Test
    void aLoneScalarIsNeverOffered() throws IOException {
        byte[] msg = encode(os -> {
            os.writeUnsigned(1, 42);
            os.writeSigned(2, -42);
        });
        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertEquals(0, bulk.offers, "the offer is per ARRAY, not per value");
        assertEquals(List.of(42L, -42L), bulk.scalars);
        assertNull(bulk.dst);
    }

    @Test
    void anEmptyArrayIsNeverOffered() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(4, new long[0]));
        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertEquals(0, bulk.offers, "nothing to fill");
        assertEquals(0, bulk.ends);
    }

    @Test
    void anFpArrayIsNeverOffered() throws IOException {
        byte[] msg = encode(os -> {
            os.writeArrayFp32(5, new float[] {1f, 2f, 3f});
            os.writeArrayFp64(6, new double[] {1d, 2d});
        });
        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertEquals(0, bulk.offers, "the offer is long-backed: integer arrays only");
    }

    /**
     * The destination is sized by the consumer, from a count the wire supplied.
     * A consumer that gets that wrong must not be able to turn it into an
     * out-of-bounds write: the decoder refuses the array and delivers the elements
     * the ordinary way instead.
     */
    @Test
    void aTooShortDestinationIsRefusedRatherThanOverrun() throws IOException {
        long[] src = mixedUnsigned(25);
        byte[] msg = encode(os -> os.writeArrayUnsigned(9, src));

        TooShort v = new TooShort();
        new IStream().feed(msg, v);

        assertArrayEquals(src, boxedToLongs(v.values), "every element still arrives");
        assertEquals(0, v.ends, "a refused offer is not a completed fill");
    }

    // --- the fill does not leak past its array ------------------------------

    @Test
    void aScalarAfterTheArrayStillReachesThePerElementCallback() throws IOException {
        long[] src = mixedUnsigned(12);
        byte[] msg = encode(os -> {
            os.writeArrayUnsigned(1, src);
            os.writeUnsigned(2, 99);
        });
        Bulk bulk = new Bulk();
        new IStream().feed(msg, bulk);

        assertArrayEquals(src, bulk.dst);
        assertEquals(List.of(99L), bulk.scalars,
                "the fill ends with its array; the next field is an ordinary value");
    }

    @Test
    void backToBackArraysEachGetTheirOwnDestination() throws IOException {
        byte[] msg = encode(os -> {
            os.writeArrayUnsigned(1, new long[] {1, 2, 3});
            os.writeArrayUnsigned(2, new long[] {4, 5});
        });
        List<long[]> taken = new ArrayList<>();
        Visitor v = new Visitor() {
            @Override
            public Object arrayBulk(int id, ArrayKind kind, int count) {
                long[] dst = new long[count];
                taken.add(dst);
                return dst;
            }
        };
        new IStream().feed(msg, v);

        assertEquals(2, taken.size());
        assertArrayEquals(new long[] {1, 2, 3}, taken.get(0));
        assertArrayEquals(new long[] {4, 5}, taken.get(1));
    }

    /**
     * A message that stops mid-array leaves the fill armed -- that is what lets the
     * next feed continue it -- so reset(), which is how a decoder resynchronises
     * onto a NEW message, has to drop it. Otherwise the next message's elements
     * would land in the abandoned message's destination.
     */
    @Test
    void resetDropsAnUnfinishedFill() throws IOException {
        long[] src = mixedUnsigned(20);
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));

        Bulk bulk = new Bulk();
        IStream is = new IStream();
        is.feed(msg, 0, msg.length / 2, bulk);
        assertEquals(DecodeStatus.INCOMPLETE, is.status());
        long[] abandoned = bulk.dst;

        is.reset();
        byte[] scalar = encode(os -> os.writeUnsigned(1, 7));
        is.feed(scalar, bulk);

        assertEquals(DecodeStatus.COMPLETE, is.status());
        assertEquals(List.of(7L), bulk.scalars, "the new message's value is not an element");
        assertEquals(0, abandoned[abandoned.length - 1],
                "nothing may be written into the abandoned destination after reset");
    }
}
