/*
 * SofaBuffers Java - the bulk offer for integer arrays (Visitor.arrayBulk).
 *
 * The offer is a fast path, so the first thing worth asserting about it is that
 * it is INVISIBLE: a visitor that takes it must end up with exactly the values a
 * visitor that declines it receives one at a time, at every chunking of the
 * input. These tests hold it to that, byte boundary by byte boundary, because
 * the fill is written from two places -- the bulk element loops and the resumable
 * byte-at-a-time machine -- and a message can cross between them at any element.
 *
 * The second is what happens when the visitor takes the offer and gets the size
 * wrong. That is the one place a caller hands this codec storage (CORELIB_PLAN
 * §6.6.3), and a destination too short is REFUSED with ARGUMENT -- not grown, not
 * part-filled, and not quietly swapped for per-element delivery, which would be
 * silent data loss for the visitor shape the offer is documented for
 * (corelib-java#118).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * Offers a destination one element too short. Overrides nothing else on
     * purpose beyond the two observations the tests need: this is the visitor
     * shape the refusal exists for -- every other Visitor method is a default
     * no-op, so a fallback to per-element delivery would discard the whole array
     * without a sound.
     */
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
            DecodeStatus after = is.feed(msg, cut, msg.length - cut, bulk);

            assertEquals(DecodeStatus.COMPLETE, after, "cut at " + cut);
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
        DecodeStatus after = null;
        for (byte b : msg) {
            after = is.feed(new byte[] {b}, bulk);
        }
        assertEquals(DecodeStatus.COMPLETE, after);
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
            // Terminal: the verdict is not read back from an accessor, it is what
            // every further feed keeps throwing, decoding nothing.
            SofabException again = org.junit.jupiter.api.Assertions.assertThrows(
                    SofabException.class, () -> is.feed(msg, v), c + " is terminal");
            assertEquals(SofabError.INVALID_MSG, again.error(), c + " is terminal");
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
            assertEquals(DecodeStatus.COMPLETE, is.feed(msg, cut, msg.length - cut, v),
                    "cut at " + cut);
            assertArrayEquals(want, (byte[]) v.dst, "cut at " + cut);
            assertEquals(1, v.ends, "cut at " + cut);
        }
    }

    /**
     * A type the decoder cannot fill is not guessed at -- and it is not a refusal
     * either: nothing the decoder could have written into was handed over, so the
     * offer was declined and the elements go the ordinary way (§6.6.3).
     */
    @Test
    void aNonIntegerDestinationIsDeclined() throws IOException {
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

    // --- a destination too short is REFUSED (CORELIB_PLAN §6.6.3) -----------
    //
    // The destination is sized by the consumer, from a count the wire supplied.
    // A consumer that gets that wrong must not be able to turn it into an
    // out-of-bounds write -- and must not have it pass unnoticed either: §6.6.3
    // makes the codec refuse a destination too short rather than grow it, with
    // InvalidArgument. Declining the offer (null, or a type the decoder cannot
    // fill) is a different answer and still falls back; see the tests above.

    /**
     * The refusal itself, and the code it carries. Not INVALID_MSG -- the message
     * is well-formed and the same bytes decode for a caller who sizes the
     * destination right -- and not LIMIT_EXCEEDED, which would name a configured
     * receiver cap that does not exist (§6.3's three-way table).
     */
    @Test
    void aTooShortDestinationIsRefusedWithArgument() throws IOException {
        long[] src = mixedUnsigned(25);
        byte[] msg = encode(os -> os.writeArrayUnsigned(9, src));

        TooShort v = new TooShort();
        IStream is = new IStream();
        SofabException e = assertThrows(SofabException.class, () -> is.feed(msg, v));

        assertEquals(SofabError.ARGUMENT, e.error());
        assertTrue(e.getMessage().contains("24") && e.getMessage().contains("25"),
                "the detail must name both sizes, got: " + e.getMessage());
    }

    /**
     * What the refusal is FOR, and the assertion that carries it: nothing is
     * delivered in place of the fill. A visitor overriding arrayBulk is the
     * documented shape for an integer array and every other Visitor method is a
     * default no-op, so a fallback to per-element delivery would hand all 25
     * elements to an inherited no-op, never fire arrayBulkEnd, and still return
     * COMPLETE -- silent data loss on a well-formed message. TooShort overrides
     * the two callbacks that would catch it doing so, and both must stay empty.
     */
    @Test
    void aRefusedOfferReachesNeitherPerElementNorBulkEnd() throws IOException {
        long[] src = mixedUnsigned(25);
        byte[] msg = encode(os -> os.writeArrayUnsigned(9, src));

        TooShort v = new TooShort();
        assertThrows(SofabException.class, () -> new IStream().feed(msg, v));

        assertEquals(List.of(), v.values, "a refused destination is not a fallback");
        assertEquals(0, v.ends, "a refused offer is not a completed fill");
    }

    /** Every width the offer accepts refuses on the same rule. */
    @Test
    void everyDestinationWidthRefusesWhenShort() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, new long[] {1, 2, 3, 4}));
        for (int width : new int[] {1, 2, 4, 8}) {
            Visitor v = new Visitor() {
                @Override
                public Object arrayBulk(int id, ArrayKind kind, int count) {
                    return switch (width) {
                        case 1 -> new byte[count - 1];
                        case 2 -> new short[count - 1];
                        case 4 -> new int[count - 1];
                        default -> new long[count - 1];
                    };
                }
            };
            IStream is = new IStream();
            SofabException e = assertThrows(SofabException.class, () -> is.feed(msg, v),
                    width + "-byte destination");
            assertEquals(SofabError.ARGUMENT, e.error(), width + "-byte destination");
        }
    }

    /** A signed array's offer is the same offer, and refuses the same way. */
    @Test
    void aSignedArrayRefusesTooShortAsWell() throws IOException {
        byte[] msg = encode(os -> os.writeArraySigned(2, new long[] {-1, 0, 1}));
        TooShort v = new TooShort();
        IStream is = new IStream();
        SofabException e = assertThrows(SofabException.class, () -> is.feed(msg, v));
        assertEquals(SofabError.ARGUMENT, e.error());
    }

    /**
     * An empty destination for a one-element array is the same mistake, and the
     * boundary the off-by-one lands on most often.
     */
    @Test
    void anEmptyDestinationForAOneElementArrayIsRefused() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, new long[] {42}));
        TooShort v = new TooShort();
        IStream is = new IStream();
        SofabException e = assertThrows(SofabException.class, () -> is.feed(msg, v));
        assertEquals(SofabError.ARGUMENT, e.error());
    }

    /**
     * The offer is put from two places -- the contiguous header path and the
     * resumable byte-at-a-time machine, which reads the count word when it
     * straddles a feed -- so the refusal has to hold at every chunking, exactly
     * like the fill itself.
     */
    @Test
    void theRefusalHoldsAtEveryChunkBoundary() throws IOException {
        long[] src = mixedUnsigned(12);
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));

        for (int cut = 0; cut <= msg.length; cut++) {
            TooShort v = new TooShort();
            IStream is = new IStream();
            final int at = cut;
            SofabException e = assertThrows(SofabException.class, () -> {
                is.feed(msg, 0, at, v);
                is.feed(msg, at, msg.length - at, v);
            }, "cut at " + cut);
            assertEquals(SofabError.ARGUMENT, e.error(), "cut at " + cut);
            assertEquals(List.of(), v.values, "cut at " + cut);
            assertEquals(0, v.ends, "cut at " + cut);
        }
    }

    /** One byte per feed: the count word is read entirely by the machine. */
    @Test
    void theMachineRefusesToo() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, mixedUnsigned(300)));
        TooShort v = new TooShort();
        IStream is = new IStream();

        SofabException e = assertThrows(SofabException.class, () -> {
            for (byte b : msg) {
                is.feed(new byte[] {b}, v);
            }
        });
        assertEquals(SofabError.ARGUMENT, e.error());
    }

    /**
     * Terminal, for the reason the fill state is: the offer site is behind us and
     * the array's payload was never consumed, so a further feed would re-enter
     * header parsing at a desynchronised byte and read the refused array's own
     * element bytes as fields that were never on the wire. Like the other two
     * terminal codes, the verdict is not read back from an accessor -- it is what
     * every further feed keeps throwing, decoding nothing.
     */
    @Test
    void aRefusedDestinationIsTerminal() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, mixedUnsigned(8)));
        TooShort v = new TooShort();
        IStream is = new IStream();
        assertThrows(SofabException.class, () -> is.feed(msg, v));

        // Well-formed bytes, fed to a decoder that has already refused: rejected
        // on arrival, under the same code, with nothing handed to the visitor.
        byte[] scalar = encode(os -> os.writeUnsigned(1, 7));
        SofabException again = assertThrows(SofabException.class, () -> is.feed(scalar, v));
        assertEquals(SofabError.ARGUMENT, again.error(), "not INVALID_MSG: the bytes were fine");
        assertEquals(List.of(), v.values);

        // An empty feed is not a loophole either.
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> is.feed(new byte[0], v)).error());
    }

    /** reset() is the way on, as it is after a malformed message. */
    @Test
    void resetClearsARefusedDestination() throws IOException {
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, mixedUnsigned(8)));
        TooShort v = new TooShort();
        IStream is = new IStream();
        assertThrows(SofabException.class, () -> is.feed(msg, v));

        is.reset();

        assertEquals(DecodeStatus.COMPLETE, is.feed(encode(os -> os.writeUnsigned(1, 7)), v));
        assertEquals(List.of(7L), v.values);
    }

    /**
     * The other side of the rule: a destination the consumer sized GENEROUSLY is
     * not a mistake. §6.6.3 asks that it hold at least the announced count, not
     * exactly it, and the decoder fills only {@code [0, count)}.
     */
    @Test
    void anOversizedDestinationIsNotRefused() throws IOException {
        long[] src = {1, 2, 3};
        byte[] msg = encode(os -> os.writeArrayUnsigned(1, src));
        long[] dst = new long[16];
        Arrays.fill(dst, -7L);
        int[] ends = new int[1];
        Visitor v = new Visitor() {
            @Override
            public Object arrayBulk(int id, ArrayKind kind, int count) {
                return dst;
            }

            @Override
            public void arrayBulkEnd(int id, int n) {
                ends[0] = n;
            }
        };

        assertEquals(DecodeStatus.COMPLETE, new IStream().feed(msg, v));
        assertEquals(3, ends[0], "arrayBulkEnd reports the announced count, not the capacity");
        assertArrayEquals(new long[] {1, 2, 3}, Arrays.copyOf(dst, 3));
        assertEquals(-7L, dst[3], "nothing is written past the announced count");
    }

    /** A null destination is declining, not a refusal: the default path is intact. */
    @Test
    void decliningTheOfferIsStillTheDefault() throws IOException {
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
                return null;
            }
        };

        assertEquals(DecodeStatus.COMPLETE, new IStream().feed(msg, v));
        assertArrayEquals(src, boxedToLongs(got));
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
        assertEquals(DecodeStatus.INCOMPLETE, is.feed(msg, 0, msg.length / 2, bulk));
        long[] abandoned = bulk.dst;

        is.reset();
        byte[] scalar = encode(os -> os.writeUnsigned(1, 7));
        assertEquals(DecodeStatus.COMPLETE, is.feed(scalar, bulk));
        assertEquals(List.of(7L), bulk.scalars, "the new message's value is not an element");
        assertEquals(0, abandoned[abandoned.length - 1],
                "nothing may be written into the abandoned destination after reset");
    }
}
