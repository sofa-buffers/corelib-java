/*
 * SofaBuffers Java - the receiver caps of CORELIB_PLAN 6.2.1 are taken as an
 * argument and compared here, and this library holds none of its own.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Decode.verdict;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The receiver-side limits of CORELIB_PLAN §6.2.1 — {@code max_dyn_string_len},
 * {@code max_dyn_blob_len} and {@code max_dyn_array_count} — are compared inside
 * {@link PayloadAcc} and {@link Seq}, on the call generated code already makes at
 * the point the limit exists to guard.
 *
 * <p>§6.2.1 fixes where the <b>numbers</b> come from (generated code, which knows
 * the schema and the target) but leaves the <b>site of the comparison</b> open:
 * "A corelib MAY take a limit as an argument and perform the check itself, and a
 * port that does is conformant." These tests pin the half that is this library's:
 * that a cap it is handed is compared before the allocation it is meant to
 * prevent, that a breach is {@link SofabError#LIMIT_EXCEEDED} and never
 * {@link SofabError#INVALID_MSG}, that the refusal is a refusal rather than a
 * clamped value, and — the part a reader has to be able to check by reading —
 * that no limit is held, defaulted, retained past its call or invented from a
 * format ceiling.
 */
class ReceiverCapTest {

    /** The number behind {@link #CAP}, where a test needs the value itself. */
    private static final int CAP_VALUE = 8;

    /** A receiver cap small enough that the payloads below run past it. */
    private static final Bound CAP = Bound.receiver(CAP_VALUE);

    /** A payload of {@code n} bytes of ASCII 'a' — valid UTF-8, so only the cap can refuse it. */
    private static byte[] payload(int n) {
        byte[] p = new byte[n];
        java.util.Arrays.fill(p, (byte) 'a');
        return p;
    }

    /** The {@link SofabError} a visitor-side rejection carries out of a callback. */
    private static SofabError categoryOf(UncheckedIOException e) {
        return assertInstanceOf(SofabException.class, e.getCause()).error();
    }

    // --- string and blob: the announced length ------------------------------

    /**
     * The check is at the length header: a {@code total} above the cap is refused
     * on the chunk that announces it, whether or not the bytes have arrived.
     */
    @Test
    void aStringLongerThanItsCapIsRefused() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(64);
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> acc.string(p.length, 0, p, 0, p.length, CAP));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));
    }

    @Test
    void aBlobLongerThanItsCapIsRefused() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(64);
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> acc.blob(p.length, 0, p, 0, p.length, CAP));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));
    }

    /** The cap is a ceiling the value may reach: {@code total == max} is accepted. */
    @Test
    void aPayloadExactlyAtItsCapIsAccepted() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(CAP_VALUE);
        assertEquals("aaaaaaaa", acc.string(p.length, 0, p, 0, p.length, CAP));
        assertArrayEquals(p, acc.blob(p.length, 0, p, 0, p.length, CAP));
    }

    /**
     * The enforcement point is the announced length, not the arriving bytes: a
     * split payload is refused on its <b>first</b> chunk, before the accumulator
     * takes a byte of it.
     *
     * <p>That is what makes the cap a bound on what this process holds rather than
     * a verdict it reaches after holding it — the corelib-cpp defect this work
     * removed was exactly the other order.
     */
    @Test
    void anOverCapPayloadIsRefusedOnItsFirstChunk() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(64);
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> acc.string(p.length, 0, p, 0, 1, CAP));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));

        // Nothing of it was kept: the accumulator serves the next payload as if
        // the refused one had never been offered.
        byte[] ok = payload(4);
        assertEquals("aaaa", acc.string(ok.length, 0, ok, 0, ok.length, CAP));
    }

    // --- the same comparison, offered at the LENGTH WORD ---------------------

    /**
     * The check reachable on its own, for the caller to make from
     * {@link Visitor#fixlenBegin} — the point §6.2.1 actually names: "at the
     * count/length header — before the allocation it is meant to prevent".
     *
     * <p>{@link PayloadAcc#string} cannot be that point by itself. It fires only
     * once a payload byte exists, so a message whose length word declares 100
     * bytes and then <em>ends</em> reaches no chunk, no call, and no verdict — and
     * a decode that had already established the refusal answers {@code INCOMPLETE}
     * instead, which §6.3 makes the wrong category (the refusal is terminal) and
     * §5.2.4 makes an active invitation to feed more of a stream this receiver has
     * refused. Three bytes claiming a hundred is the shape that matters.
     */
    @Test
    void anOverCapLengthIsRefusedWithNoPayloadAtAll() {
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> PayloadAcc.checkStringLength(100, CAP));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));

        UncheckedIOException b = assertThrows(UncheckedIOException.class,
                () -> PayloadAcc.checkBlobLength(1 << 20, CAP));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(b));
    }

    /** A length at or below the cap passes the header check silently. */
    @Test
    void anInCapLengthPassesTheHeaderCheck() {
        PayloadAcc.checkStringLength(CAP_VALUE, CAP);
        PayloadAcc.checkStringLength(0, CAP);
        PayloadAcc.checkBlobLength(CAP_VALUE, CAP);
    }

    /**
     * A schema-bounded field is not the receiver cap's business (§6.2.1: the caps
     * "MUST NOT be applied to a field the schema already bounds"), so the header
     * check passes any length for one — the caller's own {@code maxlen} reject,
     * which is {@code INVALID_MSG}, has already run at that same word.
     */
    @Test
    void theHeaderCheckLeavesASchemaBoundedFieldAlone() {
        PayloadAcc.checkStringLength(1 << 20, Bound.SCHEMA_BOUNDED);
        PayloadAcc.checkBlobLength(1 << 20, Bound.SCHEMA_BOUNDED);
    }

    /** An omitted bound is a caller defect here too, never read as "unlimited". */
    @Test
    void theHeaderCheckRefusesAnUnstatedBound() {
        assertEquals(SofabError.ARGUMENT, categoryOf(assertThrows(UncheckedIOException.class,
                () -> PayloadAcc.checkStringLength(1, null))));
        assertEquals(SofabError.ARGUMENT, categoryOf(assertThrows(UncheckedIOException.class,
                () -> PayloadAcc.checkBlobLength(1, null))));
    }

    /**
     * One implementation, two application points (§6.2.1, "one implementation,
     * wherever it runs"). The payload methods answer identically to the header
     * check for the same number, so an accumulator driven by hand — without the
     * header call — is still bounded, and a caller that makes both calls cannot
     * get two different verdicts out of one length.
     */
    @Test
    void theHeaderCheckAndThePayloadCallAgreeOnEveryLength() {
        for (int total : new int[] {0, 1, CAP_VALUE - 1, CAP_VALUE, CAP_VALUE + 1, 100, 1 << 20}) {
            SofabError header = null;
            try {
                PayloadAcc.checkStringLength(total, CAP);
            } catch (UncheckedIOException e) {
                header = categoryOf(e);
            }
            SofabError chunk = null;
            try {
                // One byte of a `total`-byte payload: enough to reach the guard,
                // never enough to complete anything.
                new PayloadAcc().string(total, 0, payload(Math.max(total, 1)), 0, Math.min(total, 1), CAP);
            } catch (UncheckedIOException e) {
                chunk = categoryOf(e);
            }
            assertEquals(header, chunk, "length " + total + " must get one verdict, not two");
        }
    }

    /** {@code string} and {@code blob} are separate limits (§6.2.1's table). */
    @Test
    void aStringCapDoesNotBindABlob() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(16);
        assertThrows(UncheckedIOException.class,
                () -> acc.string(p.length, 0, p, 0, p.length, CAP));
        assertArrayEquals(p, acc.blob(p.length, 0, p, 0, p.length, Bound.receiver(32)));
    }

    /**
     * A cap is used for the one comparison it was passed for and not retained:
     * the next call sees only its own.
     */
    @Test
    void noCapSurvivesToTheNextCall() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(16);
        assertThrows(UncheckedIOException.class,
                () -> acc.string(p.length, 0, p, 0, p.length, CAP));
        assertEquals("a".repeat(16), acc.string(p.length, 0, p, 0, p.length, Bound.receiver(16)));
        assertEquals("a".repeat(16), acc.string(p.length, 0, p, 0, p.length, Bound.SCHEMA_BOUNDED));
    }

    /**
     * {@link Bound#SCHEMA_BOUNDED} says the schema's own bound governs this field,
     * so no receiver cap is compared here — §6.2.1 forbids applying one "to a field
     * the schema already bounds", where a breach is {@code INVALID} and the caller's
     * to raise.
     */
    @Test
    void theSchemaBoundedSentinelAppliesNoCapOfItsOwn() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(4096);
        assertEquals("a".repeat(4096), acc.string(p.length, 0, p, 0, p.length, Bound.SCHEMA_BOUNDED));
    }

    /**
     * A format ceiling is not a receiver cap and is never reported as one: an
     * announced length this library will not have the bytes for is still not a
     * {@code LIMIT_EXCEEDED} until a caller states a cap it breaks.
     */
    @Test
    void anAnnouncedTotalIsNotCappedByAnyCeilingOfItsOwn() {
        PayloadAcc acc = new PayloadAcc();
        assertNull(acc.blob(Integer.MAX_VALUE, 0, bytes('a', 'b', 'c'), 0, 3, Bound.SCHEMA_BOUNDED));
    }

    // --- wrapper arrays: the element index ----------------------------------

    /**
     * A wrapper array announces no count, so the cap binds the element
     * <b>index</b>: its length is highest present id + 1 (MESSAGE_SPEC §5.1), and
     * an element at index {@code max} would make it {@code max + 1} long.
     */
    @Test
    void aRowIndexAtItsCapIsRefused() {
        List<List<String>> rows = new ArrayList<>();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRow(rows, 4, Bound.receiver(4)));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));
        assertEquals(0, rows.size(), "refused, so the list never grew");

        Seq.reserveRow(rows, 3, Bound.receiver(4));
        assertEquals(4, rows.size(), "and index max - 1 is the last one that fits");
    }

    /** The same bound, on each of the six primitive row reservations. */
    @Test
    void everyPrimitiveRowReservationTakesTheSameCap() {
        List<byte[]> b = new ArrayList<>();
        List<short[]> s = new ArrayList<>();
        List<int[]> i = new ArrayList<>();
        List<long[]> l = new ArrayList<>();
        List<float[]> f = new ArrayList<>();
        List<double[]> d = new ArrayList<>();

        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowBytes(b, 4, 1, Bound.receiver(4)))));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowShorts(s, 4, 1, Bound.receiver(4)))));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowInts(i, 4, 1, Bound.receiver(4)))));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowLongs(l, 4, 1, Bound.receiver(4)))));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowFloats(f, 4, 1, Bound.receiver(4)))));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowDoubles(d, 4, 1, Bound.receiver(4)))));

        for (List<?> rows : List.of(b, s, i, l, f, d)) {
            assertEquals(0, rows.size(), "a refused index grows nothing");
        }
    }

    /**
     * The comparison runs <b>before</b> the row is allocated. The reservation
     * length here is one no heap can serve, so a check made after the allocation
     * would fail this test with an {@link OutOfMemoryError} rather than a refusal.
     */
    @Test
    void theIndexIsRefusedBeforeTheRowIsAllocated() {
        List<int[]> rows = new ArrayList<>();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowInts(rows, 9, Integer.MAX_VALUE, Bound.receiver(4)));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));
        assertEquals(0, rows.size());
    }

    /** Rejected, never clamped: no row is placed at the cap instead of past it. */
    @Test
    void anOverCapIndexIsNotClampedIntoTheList() {
        List<int[]> rows = new ArrayList<>();
        Seq.reserveRowInts(rows, 0, 2, Bound.receiver(4));
        assertThrows(UncheckedIOException.class, () -> Seq.reserveRowInts(rows, 7, 2, Bound.receiver(4)));
        assertEquals(1, rows.size(), "the refused row was neither appended nor moved down");
    }

    /** As for a payload, the schema-bounded sentinel compares nothing here. */
    @Test
    void aSchemaBoundedRowIndexIsTheCallersToCheck() {
        List<int[]> rows = new ArrayList<>();
        Seq.reserveRowInts(rows, 40, 1, Bound.SCHEMA_BOUNDED);
        assertEquals(41, rows.size());
    }

    // --- the category, and the decode it terminates -------------------------

    /**
     * {@code LIMIT_EXCEEDED} is a policy rejection of well-formed bytes, so it is
     * not the {@code INVALID} outcome: the same message decodes for a receiver
     * configured with a looser limit, and calling it malformed would report a wire
     * divergence where there is none. {@link IStream} latches it all the same —
     * §6.3 calls it terminal — but under its own code, which the exception keeps
     * and every further feed repeats; that half is pinned by
     * {@code LimitExceededIsTerminalTest}.
     */
    @Test
    void aCapBreachIsNotTheInvalidOutcome() throws IOException {
        IStream in = new IStream();
        byte[] msg = oneString(64);
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> in.feed(msg, new CappedVisitor(CAP)));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e));
        // The latched verdict keeps that code too: asking again (an empty feed adds
        // no bytes) repeats LIMIT_EXCEEDED and never INVALID_MSG.
        UncheckedIOException again = assertThrows(UncheckedIOException.class,
                () -> in.feed(new byte[0], new CappedVisitor(CAP)));
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(again),
                "well-formed bytes this receiver declines are not malformed bytes");
    }

    /** The refusal reaches the caller from every feed shape, whole or split. */
    @Test
    void theRefusalIsReachedOnBothDecodeSurfaces() throws IOException {
        byte[] msg = oneString(64);
        for (int chunk : CHUNKS) {
            assertEquals("R:LIMIT_EXCEEDED", verdict(msg, new CappedVisitor(CAP), chunk),
                    "chunk " + chunk);
        }
    }

    /** Under a cap the value clears, the same bytes decode to the value. */
    @Test
    void theSameBytesDecodeUnderALooserLimit() throws IOException {
        byte[] msg = oneString(64);
        for (int chunk : CHUNKS) {
            CappedVisitor v = new CappedVisitor(Bound.receiver(1024));
            assertEquals("A", verdict(msg, v, chunk), "chunk " + chunk);
            assertEquals(64, v.value.length(), "chunk " + chunk);
        }
    }

    /**
     * A skipped field is never capped (§6.2.1). The message carries an unsigned
     * array of 64 elements at the id this visitor reads as a {@code string}: the
     * wire type contradicts the declared one, so MESSAGE_SPEC §7.3 skips the field
     * — it is walked, not materialized, allocates nothing, and meets no cap. The
     * decode stays COMPLETE.
     */
    @Test
    void aFieldSkippedByTheTagTestIsNeverCapped() throws IOException {
        byte[] msg = oneUnsignedArray(64);
        for (int chunk : CHUNKS) {
            CappedVisitor v = new CappedVisitor(CAP);
            assertEquals("A", verdict(msg, v, chunk), "chunk " + chunk);
            assertNull(v.value, "the field was skipped, so nothing was read or capped");
        }
    }

    /**
     * The same for a string at an id this message does not read: the destination
     * switch drops it before an accumulator is reached, so an over-cap payload
     * nobody wanted leaves the decode COMPLETE.
     */
    @Test
    void aFieldThisMessageDoesNotReadIsNeverCapped() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeString(7, "a".repeat(40));
        byte[] wire = os.copyOfBytesUsed();
        for (int chunk : CHUNKS) {
            CappedVisitor v = new CappedVisitor(CAP);
            assertEquals("A", verdict(wire, v, chunk), "chunk " + chunk);
            assertNull(v.value);
        }
    }

    /**
     * A bound that was never stated is refused, not read as "no cap". §6.2.1 lets a
     * corelib take the number as an argument but forbids it to "read an omitted
     * argument as <em>unlimited</em>", and §6.3 puts a defect in the <b>call</b>
     * under {@code ARGUMENT}: {@code LIMIT_EXCEEDED} "would promise a limit to
     * raise that was never configured", and {@code INVALID_MSG} would call
     * well-formed bytes malformed.
     */
    @Test
    void anUnstatedBoundIsRefusedRatherThanReadAsNoCap() {
        PayloadAcc acc = new PayloadAcc();
        byte[] p = payload(64);
        assertEquals(SofabError.ARGUMENT, categoryOf(assertThrows(UncheckedIOException.class,
                () -> acc.string(p.length, 0, p, 0, p.length, null))));
        assertEquals(SofabError.ARGUMENT, categoryOf(assertThrows(UncheckedIOException.class,
                () -> acc.blob(p.length, 0, p, 0, p.length, null))));

        List<int[]> rows = new ArrayList<>();
        assertEquals(SofabError.ARGUMENT, categoryOf(assertThrows(UncheckedIOException.class,
                () -> Seq.reserveRowInts(rows, 3, 1, null))));
        assertEquals(0, rows.size(), "refused before anything was reserved");
    }

    /**
     * The two answers of §6.2.1 are separate values and neither can be reached by
     * omission. "The schema bounds this field" carries no number at all, and the
     * two values a forgotten cap actually arrives as — Java's unassigned {@code 0},
     * and the negative that used to be the sentinel — are refused where the cap is
     * stated rather than interpreted as a policy.
     */
    @Test
    void aForgottenCapCannotBeSpelledAsASchemaBound() {
        assertThrows(IllegalArgumentException.class, () -> Bound.receiver(0),
                "0 is an unassigned field, not a configured limit");
        assertThrows(IllegalArgumentException.class, () -> Bound.receiver(-1),
                "the retired sentinel is not a cap and is not \"the schema bounds this\"");
        assertEquals("Bound.receiver(1)", Bound.receiver(1).toString(), "1 is a real cap");
        assertNotEquals(Bound.SCHEMA_BOUNDED, Bound.receiver(Sofab.ARRAY_MAX),
                "not even the format ceiling is the schema statement (§6.2.1)");
    }

    /** This library states no receiver limit of its own, anywhere in its API. */
    @Test
    void thisLibraryHoldsNoReceiverLimit() {
        // The three §6.2.1 caps are arguments, never constants here: what Sofab
        // exposes are the format ceilings, whose breach is INVALID_MSG. The one
        // Bound this library defines states which RULE governs a field and carries
        // no number at all, so it cannot be read as a limit.
        assertEquals("Bound.SCHEMA_BOUNDED", Bound.SCHEMA_BOUNDED.toString());
        assertTrue(Bound.SCHEMA_BOUNDED.cap() < 0, "it states a rule, not a limit value");
        assertEquals(Integer.MAX_VALUE, Sofab.ID_MAX);
        assertEquals(Integer.MAX_VALUE, Sofab.ARRAY_MAX);
    }

    // --- fixtures ------------------------------------------------------------

    /** A one-field message: id 1, a {@code string} of {@code n} ASCII bytes. */
    private static byte[] oneString(int n) throws IOException {
        byte[] buf = new byte[n + 16];
        OStream os = new OStream(buf);
        os.writeString(1, new String(payload(n), StandardCharsets.UTF_8));
        return os.copyOfBytesUsed();
    }

    /** A one-field message: id 1, an unsigned array of {@code n} elements. */
    private static byte[] oneUnsignedArray(int n) throws IOException {
        byte[] buf = new byte[n * 2 + 16];
        OStream os = new OStream(buf);
        os.writeArrayUnsigned(1, new long[n]);
        return os.copyOfBytesUsed();
    }

    /**
     * The shape generated code has for a schema-unbounded {@code string} field: a
     * destination switch that drops every id it does not read, then the corelib
     * accumulator, handed the configured cap. The guard that used to stand in
     * front of this call is gone — one implementation, and it is inside.
     */
    private static final class CappedVisitor implements Visitor {

        private final PayloadAcc acc = new PayloadAcc();
        private final Bound maxDynStringLen;

        String value;

        CappedVisitor(Bound maxDynStringLen) {
            this.maxDynStringLen = maxDynStringLen;
        }

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            if (id != 1) {
                return; // not a destination of this message: skipped, never capped
            }
            String s = acc.string(total, offset, data, chunkOffset, chunkLength, maxDynStringLen);
            if (s == null) {
                return;
            }
            value = s;
        }
    }
}
