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
import java.util.function.Supplier;

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
 * <p><b>Three reservations, one shape.</b> Every wrapper array a schema can
 * declare reaches this class through one of three calls, which differ only in what
 * a slot holds: {@link #placeElem} puts a decoded {@code string} or {@code blob} at
 * the index its id names; {@link #reserveElem} makes the slot a {@code struct},
 * {@code union} or nested-array element will be routed into; {@link #reserveRow}
 * and its six primitive overloads reserve a matrix row. The schema dependence of
 * all three is exactly a bound, an element type and an element default — an
 * argument, a type parameter and an argument — which is the whole of why they fit
 * here at all.
 *
 * <p>Two rules run through all of it. <b>Ids are positions</b> (MESSAGE_SPEC §5.1):
 * an array element's id <em>is</em> its index, an interior element equal to the
 * element default may be omitted, and the highest id present is what gives the
 * decoded array its length — so a missing id fills a gap rather than shifting
 * every later element down by one, and a repeated id replaces rather than appends.
 * <b>A count is untrusted</b>: it is the wire's claim about how many elements
 * follow, bounded by nothing until a schema {@code count} or a receiver limit
 * bounds it, so no method here allocates from a count alone.
 *
 * <p><b>Receiver caps (CORELIB_PLAN §6.2.1).</b> Every reservation here —
 * {@link #placeElem}, {@link #reserveElem}, {@link #reserveRow} and the six
 * primitive {@code reserveRow*} overloads — takes a {@link Bound} on the array's
 * element <b>index</b> and compares it here, before anything is created and before
 * the list is grown to hold it. A wrapper array announces no count, so the index is
 * what a cap can bind: its length is highest present id + 1 (MESSAGE_SPEC §5.1),
 * and two elements at id 0 and id 65535 are a 65536-slot list. §6.2.1 permits
 * exactly this placement — "a corelib MAY take a limit as an argument and perform
 * the check itself, and a port that does is conformant" — and the rule then has
 * <b>one</b> implementation: a caller that passes the cap does not also guard in
 * front of the call.
 *
 * <p>{@link #checkIndex} is that comparison on its own, published for the one site
 * that has no reservation to ride: a generated {@code fixlenBegin} arm bounds a
 * {@code string} or {@code blob} element's index at the <b>length word</b>, so that
 * a message ending right there is still refused rather than reported
 * {@code INCOMPLETE} (MESSAGE_SPEC §5.2). The placement that follows re-runs it,
 * which costs one comparison against a folded constant and spares the two sites
 * from having to agree by inspection.
 *
 * <p><b>Nothing here holds a limit.</b> The number is the caller's, used for that
 * one comparison and not retained; there is no default, no fallback and no
 * clamping, and {@link Sofab#ARRAY_MAX} is a <em>format</em> ceiling rather than a
 * receiver cap. Where the schema bounds the outer array the caller passes
 * {@link Bound#SCHEMA_BOUNDED} and rejects an over-capacity index itself, as
 * {@code INVALID} (MESSAGE_SPEC §7.1).
 *
 * <p><b>The two answers are separate values, and an unstated one is refused.</b>
 * {@link Bound#receiver(long)} is the only way to reach the comparison with a
 * number, so a caller who never configured a cap cannot arrive here spelling what
 * a schema-bounded field spells; a {@code null} is {@link Sofab#argument} rather
 * than an uncapped index. §6.2.1: "no unset state and no unlimited mode", and a
 * codec "MUST NOT read an omitted argument as unlimited".
 *
 * <p><b>What the caps here do not cover.</b> A {@code string} or {@code blob}
 * element's own {@code maxlen} is not one of these arguments: the payload arrives
 * through the visitor's own callback, and its length must be decided at the
 * <b>length word</b> so that a message truncated right after that word is refused
 * rather than {@code INCOMPLETE} (MESSAGE_SPEC §5.2). There is no reservation here
 * for it to ride — {@link PayloadAcc#checkStringLength} and
 * {@link PayloadAcc#string} own that half.
 *
 * <p>The cap on a row's own element <b>count</b>, and on the count of a top-level
 * native array, is not one of these arguments: {@code n} below is a length the caller has already bounded, and the
 * two numbers can be governed by different rules — an inner array the schema
 * bounds inside an outer one it does not. A native array count also has no call
 * into this class at all; generated code writes {@code new int[count]} straight
 * into its field. Those checks therefore stay in generated code, deliberately,
 * and this class must not grow a helper to hold them: a call invented to carry a
 * check costs more than the guard it replaces.
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
     * <b>Place a leaf element</b> — a wrapper array's {@code string} or
     * {@code blob} — at the index its wire id names, growing the list and filling
     * the gaps that omitted interior elements left (MESSAGE_SPEC §5.1, §2).
     *
     * <p>Three rules of §5.1 sit in this one loop, and not one of them is visible
     * in the bytes: two implementations can disagree about every one and still emit
     * an identical message, which is why they are written here once instead of
     * being re-emitted per schema (CORELIB_PLAN §7.2 item 8 asks for them
     * separately for the same reason).
     *
     * <ul>
     *   <li>A missing id <b>fills a gap</b> with {@code def} rather than shifting
     *       every later element down by one — an interior element equal to the
     *       element default is omitted by a conformant encoder (§2).
     *   <li>The array's length is <b>highest present id + 1</b>, so growing to
     *       {@code id + 1} per element is exactly right and no trailing fill is
     *       ever needed: the last element is never elided.
     *   <li>A repeated id <b>replaces</b> rather than appends (§7.4), which
     *       {@code set} does by construction and an {@code add} could not.
     * </ul>
     *
     * <p><b>The index is bounded before the list grows</b> (§7.2 item 8), so a
     * refused id leaves the list exactly as it was and a lower id delivered
     * afterwards still lands at its own index. Where the schema declares a
     * {@code count} the caller has already rejected a breach as {@code INVALID}
     * (MESSAGE_SPEC §7.1) and passes {@link Bound#SCHEMA_BOUNDED}; where it
     * declares none, {@code bound} carries the receiver cap, the comparison happens
     * here and a breach is {@code LIMIT_EXCEEDED}. Never both (§6.2.1).
     *
     * <p><b>{@code def} is shared, not copied.</b> The gap value of an array of
     * strings or blobs is {@code ""} or {@link #EMPTY_BYTES} — immutable, or
     * zero-length and so with no state to share — so a gap costs no allocation. An
     * element whose default is a <em>mutable</em> object belongs in
     * {@link #reserveElem} instead, which makes one per slot.
     *
     * @param out   the destination list, which this grows
     * @param id    the element's wire id, which is its index
     * @param def   the element default, filling any gap below {@code id}
     * @param value the decoded element
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1), or
     *              {@link Bound#SCHEMA_BOUNDED} where the schema bounds the array
     * @param <T>   element type
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static <T> void placeElem(List<T> out, int id, T def, T value, Bound bound) {
        checkIndex(id, bound);
        while (out.size() <= id) {
            out.add(def);
        }
        out.set(id, value);
    }

    /**
     * <b>Reserve a framed element</b> — the slot a wrapper array's {@code struct},
     * {@code union} or nested-array element is routed into: bound the index, then
     * grow the list to {@code id + 1}, giving each new slot its own element from
     * {@code make} (MESSAGE_SPEC §5.1, §2).
     *
     * <p>The same three rules as {@link #placeElem} and the same ordering — the
     * index is decided before the list grows (§7.2 item 8) — with one deliberate
     * difference: <b>a slot already present is left alone</b>. A framed element's
     * fields arrive one at a time and each is routed into the object this call
     * reserved, so a re-opened element id must <em>merge</em> into what its earlier
     * fields built rather than start the element again (§7.4); replacing the slot
     * would discard them. That is also why nothing is returned: generated code parks
     * {@code id} in its own element-index register and reaches the element through
     * {@code out.get(...)} on the field arms that follow.
     *
     * <p><b>A factory, not a shared default.</b> A struct, union or nested row is
     * mutable and reachable by the caller, and an arriving element decodes
     * <em>into</em> the object placed here, so one shared instance would alias every
     * element of the array onto it — the single reason this is a second method
     * rather than {@link #placeElem} with one more argument. {@code make} is a
     * generated method reference ({@code SomeElem::new}): it names a generated type,
     * which is the schema dependence a type parameter is allowed to carry, and javac
     * lowers a non-capturing one to a constant, so the argument allocates nothing
     * per call.
     *
     * <p>What this does <b>not</b> own is the <b>routing</b> — binding the element
     * index, switching into the element's scope, emptying a re-opened nested row.
     * That has a different shape per schema and stays generated. This owns growth
     * and the bound, and stops at the slot.
     *
     * @param out   the destination list, which this grows
     * @param id    the element's wire id, which is its index
     * @param make  the element factory, called once per slot this creates
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1), or
     *              {@link Bound#SCHEMA_BOUNDED} where the schema bounds the array
     * @param <T>   element type
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static <T> void reserveElem(List<T> out, int id, Supplier<T> make, Bound bound) {
        checkIndex(id, bound);
        while (out.size() <= id) {
            out.add(make.get());
        }
    }

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
     * <p>{@code id} is the wire's, and this grows the list to hold it, so it is
     * bounded here. Which bound depends on the outer array: where the schema
     * declares a capacity the caller checks it before calling and rejects a
     * breach as {@code INVALID} (MESSAGE_SPEC §7.1), passing
     * {@link Bound#SCHEMA_BOUNDED}; where the schema declares none, {@code bound}
     * carries the receiver cap and the comparison happens here (§6.2.1).
     *
     * @param rows the outer list, one entry per row
     * @param id   index of the row to reserve
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1), or
     *              {@link Bound#SCHEMA_BOUNDED} where the schema bounds the outer
     *              array
     * @param <T>  row element type
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static <T> void reserveRow(List<List<T>> rows, int id, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static byte[] reserveRowBytes(List<byte[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static short[] reserveRowShorts(List<short[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static int[] reserveRowInts(List<int[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static long[] reserveRowLongs(List<long[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static float[] reserveRowFloats(List<float[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * @param bound {@link Bound#receiver(long)} carrying the caller's
     *              {@code max_dyn_array_count} (§6.2.1) bounding the row
     *              <em>index</em>, or {@link Bound#SCHEMA_BOUNDED} where the schema
     *              bounds the outer array. It does not bound {@code n}, which the
     *              caller has already bounded.
     * @return the new row, now at index {@code id}
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *                                      {@link SofabException} when {@code id}
     *                                      is at or past the receiver cap, or an
     *                                      {@code ARGUMENT} one when {@code bound}
     *                                      is null
     */
    public static double[] reserveRowDoubles(List<double[]> rows, int id, int n, Bound bound) {
        checkIndex(id, bound);
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
     * The element-index rule of §6.2.1, written once for all nine reservations —
     * and published for the one site that has none to ride. A bound must have been
     * stated, and where it is a receiver cap the index is refused before anything is
     * created and before the list is grown to hold it.
     *
     * <p>The site with no reservation is the <b>length word</b> of a {@code string}
     * or {@code blob} element: generated code bounds the element's index there, so
     * that a message ending right after that word is refused rather than reported
     * {@code INCOMPLETE} (MESSAGE_SPEC §5.2), and the placement that follows —
     * {@link #placeElem} — runs the same comparison again against the same folded
     * constant.
     *
     * <p>The index is compared with {@code >=} rather than {@code >} because a
     * wrapper array's length is highest present id + 1 (MESSAGE_SPEC §5.1): an
     * element at index {@code cap} makes a list of {@code cap + 1}, which is one
     * more than the receiver said it would hold. Rejected, never clamped — placing
     * the element at {@code cap - 1} instead would be data corruption.
     *
     * <p>A missing bound is a defect in the <b>call</b> and answers
     * {@link SofabError#ARGUMENT}, not {@link SofabError#LIMIT_EXCEEDED}, which
     * would promise a limit to raise that was never configured (§6.3), and not
     * silence, which would decode the index uncapped.
     */
    public static void checkIndex(int id, Bound bound) {
        if (Bound.required(bound, "max_dyn_array_count").exceededByIndex(id)) {
            throw overIndexCap(id, bound);
        }
    }

    /**
     * Build the {@link SofabError#LIMIT_EXCEEDED} rejection, out of line so the
     * comparison that guards it stays two instructions on the decode path.
     */
    private static java.io.UncheckedIOException overIndexCap(int id, Bound bound) {
        return Sofab.limitExceeded(
                "array element index " + id + " above configured limit " + bound.cap());
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
