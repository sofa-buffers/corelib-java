/*
 * SofaBuffers Java - support layer for generated code: element placement,
 * array growth and the shared empty arrays.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Element placement and array growth for generated decode destinations — the
 * <b>support layer</b>, not the codec.
 *
 * <p>Nothing here touches the wire. These are the operations a generated message
 * class performs <em>around</em> a {@link Visitor} callback: put an element at the
 * index its id names, grow a primitive array as elements actually arrive, re-arm a
 * reused destination. Their code has the same shape for every schema — a
 * {@code count}, a {@code maxlen} or a capacity arrives as an argument and an
 * element type as a type parameter — which is why they live in the corelib rather
 * than being emitted, rationale and all, into every generated package
 * (generator#345).
 *
 * <p>This is the one place in this library that names {@link java.util.List}. The
 * codec itself stays array- and primitive-based; a generated message is what holds
 * a list, because a wrapper array of strings, blobs or sub-messages has no
 * primitive form.
 *
 * <p>Two rules run through all of it. <b>Ids are positions</b> (MESSAGE_SPEC §5.1):
 * an array element's id <em>is</em> its index, an interior element equal to the
 * element default may be omitted, and the highest id present is what gives the
 * decoded array its length — so a missing id fills a gap rather than shifting
 * every later element down by one, and a repeated id replaces rather than appends.
 * <b>A count is untrusted</b>: it is the wire's claim about how many elements
 * follow, bounded by nothing until a schema {@code count} or a receiver limit
 * bounds it, so no method here allocates from a count alone.
 */
public final class Seq {

    private Seq() {
    }

    /**
     * Initial element capacity for an array whose length is not bounded by the
     * schema. The announced count decides the ceiling, never the first allocation:
     * a decoder that sized the destination from an untrusted count would let a
     * three-byte header ask for gigabytes, so growth starts here and
     * {@link #ensureCap(int[], int, int)} doubles it against elements that have
     * actually arrived.
     */
    public static final int ARRAY_INIT_CAP = 16;

    /**
     * Shared zero-length arrays. A generated field initializer references one of
     * these instead of allocating a fresh empty array per instance — decode
     * replaces the value anyway, and a zero-length array has no state to share.
     */
    public static final byte[] EMPTY_BYTES = {};

    /** The shared empty {@code short[]}; see {@link #EMPTY_BYTES}. */
    public static final short[] EMPTY_SHORTS = {};

    /** The shared empty {@code int[]}; see {@link #EMPTY_BYTES}. */
    public static final int[] EMPTY_INTS = {};

    /** The shared empty {@code long[]}; see {@link #EMPTY_BYTES}. */
    public static final long[] EMPTY_LONGS = {};

    /** The shared empty {@code float[]}; see {@link #EMPTY_BYTES}. */
    public static final float[] EMPTY_FLOATS = {};

    /** The shared empty {@code double[]}; see {@link #EMPTY_BYTES}. */
    public static final double[] EMPTY_DOUBLES = {};

    /**
     * Reserve the row at index {@code id} of a matrix — an array whose elements are
     * themselves arrays — as an <b>empty</b> row, growing the outer list with empty
     * rows so that a gap in the ids decodes as an empty row instead of shifting
     * every later row down by one.
     *
     * <p>Gaps are ordinary: an interior row equal to the element default (the empty
     * row) is omitted by a conformant encoder (MESSAGE_SPEC §2), and only the
     * <em>last</em> row is guaranteed present — which is what makes the decoded
     * length, highest present id + 1, exact. The row is replaced rather than merged
     * into, because an array wrapper <em>is</em> the array's value (§7.4).
     *
     * <p>"Replaced" is a statement about the value, not the object: an already
     * present row is emptied in place — the same rule {@link #reset} follows for a
     * reused decode destination — instead of being swapped for a fresh list.
     * Decoding N rows then allocates N lists rather than 2N, one to grow into the
     * slot and one to overwrite it. A caller holding a reference to a row across a
     * decode into the same destination sees it emptied; a decode destination is not
     * shared.
     *
     * <p>{@code id} is the wire's, so the caller bounds it against the outer
     * array's schema capacity <em>before</em> calling: this grows the list to hold
     * it.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param <T>  row element type
     */
    public static <T> void reserveRow(List<List<T>> rows, int id) {
        while (rows.size() < id) {
            rows.add(new ArrayList<>());
        }
        if (rows.size() == id) {
            rows.add(new ArrayList<>());
            return;
        }
        List<T> row = rows.get(id);
        if (row == null) {
            rows.set(id, new ArrayList<>());
        } else {
            row.clear();
        }
    }

    /**
     * {@link #reserveRow} for a <b>primitive</b> row: the same id-keyed placement
     * and the same gap fill with the empty row, but the new row is handed back so
     * the caller can fill it by index instead of reading it out of the list once
     * per element.
     *
     * <p>{@code n} is the caller's capped reservation, never the wire count — an
     * untrusted count must not be able to force an up-front allocation — and
     * {@link #ensureCap(byte[], int, int)} grows the row as elements arrive.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static byte[] reserveRowBytes(List<byte[]> rows, int id, int n) {
        byte[] row = new byte[n];
        while (rows.size() < id) {
            rows.add(EMPTY_BYTES);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * {@link #reserveRowBytes} for a {@code short[]} row.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static short[] reserveRowShorts(List<short[]> rows, int id, int n) {
        short[] row = new short[n];
        while (rows.size() < id) {
            rows.add(EMPTY_SHORTS);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * {@link #reserveRowBytes} for an {@code int[]} row.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static int[] reserveRowInts(List<int[]> rows, int id, int n) {
        int[] row = new int[n];
        while (rows.size() < id) {
            rows.add(EMPTY_INTS);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * {@link #reserveRowBytes} for a {@code long[]} row.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static long[] reserveRowLongs(List<long[]> rows, int id, int n) {
        long[] row = new long[n];
        while (rows.size() < id) {
            rows.add(EMPTY_LONGS);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * {@link #reserveRowBytes} for a {@code float[]} row.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static float[] reserveRowFloats(List<float[]> rows, int id, int n) {
        float[] row = new float[n];
        while (rows.size() < id) {
            rows.add(EMPTY_FLOATS);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * {@link #reserveRowBytes} for a {@code double[]} row.
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param n    initial length of the new row
     * @return the new row, now at index {@code id}
     */
    public static double[] reserveRowDoubles(List<double[]> rows, int id, int n) {
        double[] row = new double[n];
        while (rows.size() < id) {
            rows.add(EMPTY_DOUBLES);
        }
        if (rows.size() == id) {
            rows.add(row);
        } else {
            rows.set(id, row);
        }
        return row;
    }

    /**
     * Empty a list <b>in place</b>, keeping its capacity, and materialize one only
     * when the field is null.
     *
     * <p>A generated {@code reset()} calls this, so re-arming a reused decode
     * destination costs no allocation — which is the whole point of taking a
     * destination from the caller.
     *
     * @param list the list to re-arm, possibly null
     * @param <T>  element type
     * @return {@code list}, emptied, or a fresh list when it was null
     */
    public static <T> List<T> reset(List<T> list) {
        if (list == null) {
            return new ArrayList<>();
        }
        list.clear();
        return list;
    }

    /**
     * The null-absorbing identity a wrapper array is read through on the encode
     * side — by the serialize loop and by the all-default predicate alike.
     *
     * <p>No narrowing happens here and none may: the wire count <em>is</em> a
     * compact array's length and the highest wrapper id <em>is</em> its last index
     * (MESSAGE_SPEC §3 / §5.1), so dropping a trailing default element would not
     * re-shape the bytes, it would shorten the value. What the interior may drop —
     * a leaf equal to the element default, an all-default sequence element — is
     * decided per element inside the loop, never here.
     *
     * @param list the field's value, possibly null
     * @param <T>  element type
     * @return {@code list}, or an immutable empty list when it was null
     */
    public static <T> List<T> orEmpty(List<T> list) {
        return list == null ? Collections.<T>emptyList() : list;
    }

    /**
     * Convert a boolean wrapper array to the {@code long[]} the encoder writes.
     *
     * <p>This is the one boxed-to-primitive conversion the encode side still needs.
     * Every other native array — top level or a matrix row — is already a primitive
     * array of its declared width and reaches
     * {@link OStream#writeArrayUnsigned(int, long[])} and friends unconverted; a
     * boolean array is the one with no primitive overload of its own, {@code bool}
     * being carried on the wire as an unsigned 0 or 1.
     *
     * @param values the field's value; a null element counts as false
     * @return one long per element, 1 for true and 0 for false
     */
    public static long[] boolsToLongs(List<Boolean> values) {
        long[] out = new long[values.size()];
        for (int i = 0; i < out.length; i++) {
            Boolean v = values.get(i);
            out[i] = v != null && v ? 1 : 0;
        }
        return out;
    }

    /**
     * Enlarge {@code a} so index {@code i} can be written, doubling its length but
     * never exceeding {@code cap}.
     *
     * <p>This is the growth policy for an array being filled element by element,
     * and its whole point is that it tracks elements that have <b>actually
     * arrived</b>. The alternative — sizing the destination from the announced
     * count — hands an attacker a multi-gigabyte allocation for a three-byte
     * header, since a count is bounded only by {@link Sofab#ARRAY_MAX} until a
     * schema {@code count} or a receiver limit bounds it. Doubling keeps the fill
     * amortized O(n), and the {@code cap} clamp means a valid array of the
     * announced length still ends up exactly right-sized rather than at the next
     * power of two.
     *
     * <p>{@code cap} is a ceiling on the <em>result</em>, not a bound the caller is
     * relieved of checking: it is the announced count for an unbounded field and
     * the schema capacity for a bounded one, both already validated by the caller,
     * and a fill that stays below its own count therefore never sees it clamp.
     * Returns {@code a} untouched whenever {@code i} already fits, so the call sits
     * on the hot path unguarded.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static byte[] ensureCap(byte[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * {@link #ensureCap(byte[], int, int)} for a {@code short[]}.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static short[] ensureCap(short[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * {@link #ensureCap(byte[], int, int)} for an {@code int[]}.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static int[] ensureCap(int[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * {@link #ensureCap(byte[], int, int)} for a {@code long[]}.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static long[] ensureCap(long[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * {@link #ensureCap(byte[], int, int)} for a {@code float[]}.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static float[] ensureCap(float[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * {@link #ensureCap(byte[], int, int)} for a {@code double[]}.
     *
     * @param a   the array so far
     * @param i   index about to be written
     * @param cap growth ceiling: the announced or declared element count
     * @return {@code a}, or a longer copy of it
     */
    public static double[] ensureCap(double[] a, int i, int cap) {
        if (i < a.length) {
            return a;
        }
        return Arrays.copyOf(a, grownTo(a.length, i, cap));
    }

    /**
     * The one growth rule the {@code ensureCap} overloads share: double, but reach
     * at least index {@code i}, and stop at {@code cap}.
     *
     * <p>Everything is computed in {@code long} — {@code len * 2} and {@code i + 1}
     * both overflow {@code int} near {@link Integer#MAX_VALUE}, and an overflowed
     * length would come back <em>shorter</em> than the index that asked for it.
     */
    private static int grownTo(int len, int i, int cap) {
        long n = (long) len * 2;
        if (n < (long) i + 1) {
            n = (long) i + 1;
        }
        if (n > cap) {
            n = cap;
        }
        return (int) n;
    }
}
