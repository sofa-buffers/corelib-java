/*
 * SofaBuffers Java - the schema's own bound, compared by the corelib call it is
 * handed to (MESSAGE_SPEC §7.1, CORELIB_PLAN §6.2.1, §7.2 item 8).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Bound#schema(long)}: a schema {@code count} handed to the reservation
 * that grows the list, and compared there.
 *
 * <p>A {@code count} is a <b>capacity</b>, not a length — the list starts empty and
 * the wire carries the length — so the last index that fits is {@code count - 1}
 * and {@code id >= count} is {@code INVALID_MSG}. The receiver cap is the other,
 * exclusive answer (§6.2.1): a schema bound never reports {@code LIMIT_EXCEEDED}.
 * And the index is compared before anything grows (§7.2 item 8), so a refused id
 * leaves the list as it was and a lower id delivered afterwards still lands.
 */
class SchemaBoundTest {

    private static final int COUNT = 5;
    private static final Bound SCHEMA = Bound.schema(COUNT);

    private static SofabError categoryOf(UncheckedIOException e) {
        return assertInstanceOf(SofabException.class, e.getCause()).error();
    }

    private static SofabError refusal(Runnable call) {
        return categoryOf(assertThrows(UncheckedIOException.class, call::run));
    }

    /**
     * Every reservation that takes a {@link Bound}, as a call on one list: the
     * leaf placement, the framed slot, the list row and the six primitive rows.
     */
    private static List<Reservation> reservations() {
        return List.of(
                new Reservation("placeElem", (out, id) -> Seq.placeElem(strings(out), id, "", "v", SCHEMA)),
                new Reservation("reserveElem", (out, id) -> Seq.reserveElem(objects(out), id, Object::new, SCHEMA)),
                new Reservation("reserveRow", (out, id) -> Seq.reserveRow(lists(out), id, SCHEMA)),
                new Reservation("reserveRowBytes", (out, id) -> Seq.reserveRowBytes(bytes(out), id, 1, SCHEMA)),
                new Reservation("reserveRowShorts", (out, id) -> Seq.reserveRowShorts(shorts(out), id, 1, SCHEMA)),
                new Reservation("reserveRowInts", (out, id) -> Seq.reserveRowInts(ints(out), id, 1, SCHEMA)),
                new Reservation("reserveRowLongs", (out, id) -> Seq.reserveRowLongs(longs(out), id, 1, SCHEMA)),
                new Reservation("reserveRowFloats", (out, id) -> Seq.reserveRowFloats(floats(out), id, 1, SCHEMA)),
                new Reservation("reserveRowDoubles", (out, id) -> Seq.reserveRowDoubles(doubles(out), id, 1, SCHEMA)));
    }

    /** At the bound: index {@code count - 1} is the last one that fits. */
    @Test
    void theLastIndexTheCapacityHoldsLands() {
        for (Reservation r : reservations()) {
            List<Object> out = new ArrayList<>();
            r.call.accept(out, COUNT - 1);
            assertEquals(COUNT, out.size(), r.name + ": grown to count, gaps filled");
        }
        Seq.checkIndex(COUNT - 1, SCHEMA);
    }

    /**
     * Past the bound: index {@code count} is INVALID_MSG, and the list is not
     * extended toward it — so a lower id delivered afterwards still lands at its
     * own index rather than behind a run of defaults.
     */
    @Test
    void anIndexAtTheCountIsInvalidAndLeavesTheListUnextended() {
        for (Reservation r : reservations()) {
            List<Object> out = new ArrayList<>();
            r.call.accept(out, 1);
            assertEquals(SofabError.INVALID_MSG, refusal(() -> r.call.accept(out, COUNT)),
                    r.name + ": id == count breaches the capacity");
            assertEquals(SofabError.INVALID_MSG, refusal(() -> r.call.accept(out, 1 << 20)),
                    r.name + ": far past it too");
            assertEquals(2, out.size(), r.name + ": the refused id neither grew nor shifted the list");
            r.call.accept(out, 0);
            assertEquals(2, out.size(), r.name + ": a lower id delivered afterwards still lands");
        }
        assertEquals(SofabError.INVALID_MSG, refusal(() -> Seq.checkIndex(COUNT, SCHEMA)));
    }

    /**
     * The breach is judged before the element is made: the factory here throws, so
     * a check made after the growth would surface that instead of the refusal.
     */
    @Test
    void theIndexIsJudgedBeforeTheElementIsMade() {
        List<Object> out = new ArrayList<>();
        assertEquals(SofabError.INVALID_MSG, refusal(() -> Seq.reserveElem(out, COUNT, () -> {
            throw new AssertionError("the element was made for a refused index");
        }, SCHEMA)));
        assertEquals(0, out.size());
    }

    /**
     * §6.2.1: the schema bound and the receiver cap are exclusive, and so are their
     * verdicts. The same index against the same number is INVALID_MSG under the
     * schema and LIMIT_EXCEEDED under a cap — never the other way round, and a
     * schema bound never reports LIMIT_EXCEEDED however far the index lies.
     */
    @Test
    void aSchemaBoundNeverReportsLimitExceeded() {
        for (int id : new int[] {COUNT, COUNT + 1, 1 << 16, Integer.MAX_VALUE}) {
            assertEquals(SofabError.INVALID_MSG, refusal(() -> Seq.checkIndex(id, SCHEMA)));
            for (Reservation r : reservations()) {
                assertNotEquals(SofabError.LIMIT_EXCEEDED, refusal(() -> r.call.accept(new ArrayList<>(), id)),
                        r.name + " at " + id);
            }
        }
        assertEquals(SofabError.LIMIT_EXCEEDED,
                refusal(() -> Seq.checkIndex(COUNT, Bound.receiver(COUNT))),
                "the receiver cap keeps its own verdict");
    }

    /**
     * A {@code maxlen} handed to {@link PayloadAcc} as a schema bound is compared
     * at the announced length and is INVALID_MSG above it — before a byte is
     * taken — and never LIMIT_EXCEEDED.
     */
    @Test
    void aSchemaMaxlenOnAPayloadIsInvalidAboveIt() {
        Bound maxlen = Bound.schema(4);
        byte[] p = "abcde".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        PayloadAcc.checkStringLength(4, maxlen);
        PayloadAcc.checkBlobLength(4, maxlen);
        assertEquals(SofabError.INVALID_MSG, refusal(() -> PayloadAcc.checkStringLength(5, maxlen)));
        assertEquals(SofabError.INVALID_MSG, refusal(() -> PayloadAcc.checkBlobLength(5, maxlen)));

        PayloadAcc acc = new PayloadAcc();
        assertEquals(SofabError.INVALID_MSG, refusal(() -> acc.string(5, 0, p, 0, 2, maxlen)));
        // Nothing was taken: an in-bound payload through the same accumulator is whole.
        assertNull(acc.blob(4, 0, p, 0, 2, maxlen));
        assertEquals("abcd", new String(acc.blob(4, 2, p, 2, 2, maxlen),
                java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void itNamesItsRule() {
        assertEquals("Bound.schema(5)", SCHEMA.toString());
        assertNotEquals(Bound.receiver(COUNT).toString(), SCHEMA.toString());
    }

    // --- fixtures ------------------------------------------------------------

    /** One reservation under test, applied to an untyped list. */
    private record Reservation(String name, java.util.function.BiConsumer<List<Object>, Integer> call) {
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(List<Object> out) {
        return (List<String>) (List<?>) out;
    }

    private static List<Object> objects(List<Object> out) {
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<List<Object>> lists(List<Object> out) {
        return (List<List<Object>>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<byte[]> bytes(List<Object> out) {
        return (List<byte[]>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<short[]> shorts(List<Object> out) {
        return (List<short[]>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<int[]> ints(List<Object> out) {
        return (List<int[]>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<long[]> longs(List<Object> out) {
        return (List<long[]>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<float[]> floats(List<Object> out) {
        return (List<float[]>) (List<?>) out;
    }

    @SuppressWarnings("unchecked")
    private static List<double[]> doubles(List<Object> out) {
        return (List<double[]>) (List<?>) out;
    }
}
