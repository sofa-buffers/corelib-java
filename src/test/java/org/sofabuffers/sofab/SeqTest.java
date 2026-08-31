/*
 * SofaBuffers Java - support layer: placement, growth and the shared empties.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * {@link Seq} is not wire-visible: two implementations can disagree about a
 * gap-filled row, or about how much an announced count may allocate, and still
 * emit byte-identical output. The shared vectors cannot cover that in principle,
 * which is why the rules live here — and why they could not be tested at all while
 * this code was emitted per schema, where {@code ensureCap} was pinned by a
 * substring match on the generated text and could not be called with
 * {@code i = Integer.MAX_VALUE} at all (generator#345).
 *
 * <p>Three groups: the capacity edges of the growth policy, its behaviour against
 * an adversarial announced count, and the gap/replace semantics MESSAGE_SPEC §5.1
 * gives an id used as an index.
 */
class SeqTest {

    // --- growth: capacity edges ---------------------------------------------

    /** Below the length it already has, the array is handed straight back. */
    @Test
    void anIndexThatAlreadyFitsGrowsNothing() {
        int[] a = new int[4];
        assertSame(a, Seq.ensureCap(a, 0, 8));
        assertSame(a, Seq.ensureCap(a, 3, 8));   // id == cap - 1 of the array itself
        assertNotSame(a, Seq.ensureCap(a, 4, 8)); // id == cap: one past the end
    }

    /** From nothing, the first element is the whole allocation. */
    @Test
    void growthFromZeroReachesExactlyTheIndexAsked() {
        assertEquals(1, Seq.ensureCap(Seq.EMPTY_INTS, 0, 8).length);
        assertEquals(4, Seq.ensureCap(Seq.EMPTY_INTS, 3, 8).length);
    }

    /** Doubling, and the announced count is where it stops. */
    @Test
    void growthDoublesAndStopsAtTheAnnouncedCount() {
        assertEquals(8, Seq.ensureCap(new int[4], 4, 100).length);
        assertEquals(6, Seq.ensureCap(new int[4], 4, 6).length, "clamped to cap, not 8");
        assertEquals(4, Seq.ensureCap(new int[4], 4, 4).length, "already at cap");
    }

    /** Existing elements survive the copy; the tail is zeroed. */
    @Test
    void growthKeepsWhatWasAlreadyThere() {
        int[] a = { 1, 2 };
        int[] grown = Seq.ensureCap(a, 2, 100);
        assertArrayEquals(new int[] { 1, 2, 0, 0 }, grown);
    }

    /** A fill that runs to the announced count ends exactly right-sized. */
    @Test
    void aCompleteFillEndsExactlyRightSized() {
        for (int count : new int[] { 1, 2, 15, 16, 17, 100, 1000 }) {
            int[] a = new int[Math.min(count, Seq.ARRAY_INIT_CAP)];
            for (int i = 0; i < count; i++) {
                if (i >= a.length) {
                    a = Seq.ensureCap(a, i, count);
                }
                a[i] = i;
            }
            assertEquals(count, a.length, "count " + count);
            for (int i = 0; i < count; i++) {
                assertEquals(i, a[i]);
            }
        }
    }

    // --- growth: an untrusted count ------------------------------------------

    /**
     * The adversarial case the generated original guarded against and no schema
     * could reach: a count near {@code 2^31} is a three-byte header, and must buy
     * exactly as much memory as the elements that actually arrive.
     */
    @Test
    void anAnnouncedCountNearTwoToThe31AllocatesNothing() {
        int[] a = Seq.ensureCap(Seq.EMPTY_INTS, 0, Integer.MAX_VALUE);
        assertEquals(1, a.length);
        for (int i = 1; i < 1000; i++) {
            if (i >= a.length) {
                a = Seq.ensureCap(a, i, Integer.MAX_VALUE);
            }
            a[i] = i;
        }
        assertTrue(a.length < 2048, "1000 elements bought " + a.length + " slots");
    }

    /**
     * The index itself may be {@link Integer#MAX_VALUE}. Both halves of the rule
     * are computed in {@code long} for that reason: {@code i + 1} overflows to
     * {@link Integer#MIN_VALUE} there, and an int-arithmetic version returns a
     * <em>shorter</em> array than the index that asked for it — a silent
     * out-of-bounds at the next store.
     */
    @Test
    void anIndexAtIntegerMaxValueStillGrowsToTheCap() {
        assertEquals(8, Seq.ensureCap(Seq.EMPTY_BYTES, Integer.MAX_VALUE, 8).length);
        assertEquals(8, Seq.ensureCap(Seq.EMPTY_BYTES, Integer.MAX_VALUE - 1, 8).length);
        assertEquals(8, Seq.ensureCap(new byte[4], Integer.MAX_VALUE, 8).length);
    }

    /** Every element width has the same policy, with its own overload. */
    @Test
    void everyPrimitiveWidthGrowsAlike() {
        assertEquals(8, Seq.ensureCap(new byte[4], 4, 100).length);
        assertEquals(8, Seq.ensureCap(new short[4], 4, 100).length);
        assertEquals(8, Seq.ensureCap(new int[4], 4, 100).length);
        assertEquals(8, Seq.ensureCap(new long[4], 4, 100).length);
        assertEquals(8, Seq.ensureCap(new float[4], 4, 100).length);
        assertEquals(8, Seq.ensureCap(new double[4], 4, 100).length);

        byte[] bytes = new byte[4];
        short[] shorts = new short[4];
        int[] ints = new int[4];
        long[] longs = new long[4];
        float[] floats = new float[4];
        double[] doubles = new double[4];
        assertSame(bytes, Seq.ensureCap(bytes, 3, 100));
        assertSame(shorts, Seq.ensureCap(shorts, 3, 100));
        assertSame(ints, Seq.ensureCap(ints, 3, 100));
        assertSame(longs, Seq.ensureCap(longs, 3, 100));
        assertSame(floats, Seq.ensureCap(floats, 3, 100));
        assertSame(doubles, Seq.ensureCap(doubles, 3, 100));

        assertArrayEquals(new byte[] { 7, 0 }, Seq.ensureCap(new byte[] { 7 }, 1, 2));
        assertArrayEquals(new short[] { 7, 0 }, Seq.ensureCap(new short[] { 7 }, 1, 2));
        assertArrayEquals(new long[] { 7, 0 }, Seq.ensureCap(new long[] { 7 }, 1, 2));
        assertArrayEquals(new float[] { 7, 0 }, Seq.ensureCap(new float[] { 7 }, 1, 2));
        assertArrayEquals(new double[] { 7, 0 }, Seq.ensureCap(new double[] { 7 }, 1, 2));
    }

    // --- placement: gaps, and replace-not-append -----------------------------

    /** An id past the end fills the space below it with empty rows, not with a shift. */
    @Test
    void aWrapperRowGapFillsRatherThanShifts() {
        List<List<String>> rows = new ArrayList<>();
        Seq.reserveRow(rows, 2, Sofab.SCHEMA_BOUNDED);
        rows.get(2).add("third");

        assertEquals(3, rows.size());
        assertEquals(List.of(), rows.get(0));
        assertEquals(List.of(), rows.get(1));
        assertEquals(List.of("third"), rows.get(2));
    }

    /**
     * A repeated id replaces the row's value (MESSAGE_SPEC §7.4) — and does it by
     * emptying the row in place, so N rows cost N lists rather than 2N.
     */
    @Test
    void aRepeatedWrapperRowIsEmptiedInPlace() {
        List<List<String>> rows = new ArrayList<>();
        Seq.reserveRow(rows, 0, Sofab.SCHEMA_BOUNDED);
        List<String> first = rows.get(0);
        first.add("stale");

        Seq.reserveRow(rows, 0, Sofab.SCHEMA_BOUNDED);
        assertSame(first, rows.get(0), "the row object is reused");
        assertEquals(List.of(), rows.get(0), "its value is replaced, not merged into");
        assertEquals(1, rows.size());
    }

    /** A null row — a destination the caller left holes in — is materialized. */
    @Test
    void aNullWrapperRowIsMaterialized() {
        List<List<String>> rows = new ArrayList<>();
        rows.add(null);
        Seq.reserveRow(rows, 0, Sofab.SCHEMA_BOUNDED);
        assertEquals(List.of(), rows.get(0));
    }

    /** The same placement for a primitive row, which is handed back to fill by index. */
    @Test
    void aPrimitiveRowGapFillsWithTheSharedEmptyRow() {
        List<int[]> rows = new ArrayList<>();
        int[] row = Seq.reserveRowInts(rows, 2, 3, Sofab.SCHEMA_BOUNDED);

        assertEquals(3, rows.size());
        assertSame(Seq.EMPTY_INTS, rows.get(0), "a gap costs no allocation");
        assertSame(Seq.EMPTY_INTS, rows.get(1));
        assertSame(row, rows.get(2), "the row handed back is the row placed");
        assertEquals(3, row.length, "n is the caller's reservation");
    }

    /** A repeated id replaces the primitive row outright: the array is its value. */
    @Test
    void aRepeatedPrimitiveRowIsReplaced() {
        List<int[]> rows = new ArrayList<>();
        int[] first = Seq.reserveRowInts(rows, 0, 2, Sofab.SCHEMA_BOUNDED);
        first[0] = 9;
        int[] second = Seq.reserveRowInts(rows, 0, 2, Sofab.SCHEMA_BOUNDED);

        assertNotSame(first, second);
        assertSame(second, rows.get(0));
        assertArrayEquals(new int[] { 0, 0 }, rows.get(0));
        assertEquals(1, rows.size());
    }

    /**
     * Every element width has its own row factory, its own shared empty row — and
     * the same replace-in-place rule for an id that already has a row.
     */
    @Test
    void everyPrimitiveRowWidthPlacesAlike() {
        List<byte[]> bytes = new ArrayList<>();
        assertEquals(2, Seq.reserveRowBytes(bytes, 1, 2, Sofab.SCHEMA_BOUNDED).length);
        assertSame(Seq.EMPTY_BYTES, bytes.get(0));
        assertSame(Seq.reserveRowBytes(bytes, 1, 3, Sofab.SCHEMA_BOUNDED), bytes.get(1));

        List<short[]> shorts = new ArrayList<>();
        assertEquals(2, Seq.reserveRowShorts(shorts, 1, 2, Sofab.SCHEMA_BOUNDED).length);
        assertSame(Seq.EMPTY_SHORTS, shorts.get(0));
        assertSame(Seq.reserveRowShorts(shorts, 1, 3, Sofab.SCHEMA_BOUNDED), shorts.get(1));

        List<long[]> longs = new ArrayList<>();
        assertEquals(2, Seq.reserveRowLongs(longs, 1, 2, Sofab.SCHEMA_BOUNDED).length);
        assertSame(Seq.EMPTY_LONGS, longs.get(0));
        assertSame(Seq.reserveRowLongs(longs, 1, 3, Sofab.SCHEMA_BOUNDED), longs.get(1));

        List<float[]> floats = new ArrayList<>();
        assertEquals(2, Seq.reserveRowFloats(floats, 1, 2, Sofab.SCHEMA_BOUNDED).length);
        assertSame(Seq.EMPTY_FLOATS, floats.get(0));
        assertSame(Seq.reserveRowFloats(floats, 1, 3, Sofab.SCHEMA_BOUNDED), floats.get(1));

        List<double[]> doubles = new ArrayList<>();
        assertEquals(2, Seq.reserveRowDoubles(doubles, 1, 2, Sofab.SCHEMA_BOUNDED).length);
        assertSame(Seq.EMPTY_DOUBLES, doubles.get(0));
        assertSame(Seq.reserveRowDoubles(doubles, 1, 3, Sofab.SCHEMA_BOUNDED), doubles.get(1));
    }

    /** A row reserved at the very next index appends rather than growing a gap. */
    @Test
    void aRowAtTheNextIndexAppends() {
        List<int[]> rows = new ArrayList<>();
        Seq.reserveRowInts(rows, 0, 1, Sofab.SCHEMA_BOUNDED);
        Seq.reserveRowInts(rows, 1, 1, Sofab.SCHEMA_BOUNDED);
        assertEquals(2, rows.size());

        List<List<String>> wrappers = new ArrayList<>();
        Seq.reserveRow(wrappers, 0, Sofab.SCHEMA_BOUNDED);
        Seq.reserveRow(wrappers, 1, Sofab.SCHEMA_BOUNDED);
        assertEquals(2, wrappers.size());
    }

    // --- re-arming and the encode-side identity ------------------------------

    /** reset() empties in place — the point of taking a destination from the caller. */
    @Test
    void resetEmptiesInPlaceAndMaterializesANullField() {
        List<String> held = new ArrayList<>(List.of("a", "b"));
        assertSame(held, Seq.reset(held));
        assertEquals(List.of(), held);

        List<String> made = Seq.reset(null);
        assertEquals(List.of(), made);
        made.add("writable");
    }

    /** orEmpty absorbs null and narrows nothing. */
    @Test
    void orEmptyAbsorbsNullAndChangesNothingElse() {
        assertEquals(List.of(), Seq.orEmpty(null));

        List<String> trailingDefaults = new ArrayList<>(Arrays.asList("x", "", ""));
        assertSame(trailingDefaults, Seq.orEmpty(trailingDefaults));
        assertEquals(3, Seq.orEmpty(trailingDefaults).size(),
                "a trailing default element is part of the array's length (§5.1)");
    }

    /** The shared empties are shared, and empty. */
    @Test
    void theSharedEmptyArraysAreEmpty() {
        assertEquals(0, Seq.EMPTY_BYTES.length);
        assertEquals(0, Seq.EMPTY_SHORTS.length);
        assertEquals(0, Seq.EMPTY_INTS.length);
        assertEquals(0, Seq.EMPTY_LONGS.length);
        assertEquals(0, Seq.EMPTY_FLOATS.length);
        assertEquals(0, Seq.EMPTY_DOUBLES.length);
    }

    // --- the boxed-to-primitive conversion -----------------------------------

    /** bool has no primitive array overload on the encoder; this is the bridge. */
    @Test
    void boolsToLongsWritesOneAndZero() {
        assertArrayEquals(new long[] { 1, 0, 1 },
                Seq.boolsToLongs(List.of(true, false, true)));
        assertArrayEquals(new long[0], Seq.boolsToLongs(List.of()));
        assertArrayEquals(new long[] { 0, 1 },
                Seq.boolsToLongs(Arrays.asList(null, true)), "a null element is false");
    }

    /** The initial reservation is bounded, and it is what a caller starts from. */
    @Test
    void theInitialCapacityIsTheBoundedReservation() {
        assertEquals(16, Seq.ARRAY_INIT_CAP);
        int[] a = new int[Math.min(Integer.MAX_VALUE, Seq.ARRAY_INIT_CAP)];
        assertEquals(16, a.length, "an unbounded count buys 16 slots, not 2^31 - 1");
    }
}
