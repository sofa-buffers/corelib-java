/*
 * SofaBuffers Java - the fixlen-array header hook fires after the fixlen_word.
 *
 * CORELIB_PLAN §4.8 fixes the decode order for a fixlen array (wire type 0b101):
 *
 *   1. read `element_count`, enforcing only the FORMAT ceiling ARRAY_MAX and
 *      allocating nothing on the strength of it;
 *   2. read the `fixlen_word` (element subtype + per-element length) — EOF before
 *      or inside it is INCOMPLETE, not INVALID;
 *   3. validate that word as a format matter (only fp32/4 and fp64/8 are legal
 *      fixlen-array elements);
 *   4. only then announce the array, so the visitor learns the element SUBTYPE.
 *      A subtype that contradicts the declared element type makes the field a
 *      MESSAGE_SPEC §7.3 skip, and a skipped field's element count is not this
 *      array's count — the schema `count` bound MUST NOT be applied to it.
 *
 * The corelib itself has no schema, so the bound lives in generated code inside
 * `arrayBegin`. These tests therefore drive the decoder with a visitor shaped
 * exactly like sofabgen's emitted one for the fuzz probe's
 * `arrays` (id 100) -> `nested` (id 10) scope, which declares
 * id 0 = array<fp32, count 5> and id 1 = array<fp64, count 5>.
 * What is under test is which kind the hook carries and when it fires; the
 * verdicts below are what Crucible observes across the 13 drivers (finding
 * F-0042, corelib-java#53).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Decode.verdict;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class FixlenArrayHeaderOrderTest {

    // --- wire vectors -------------------------------------------------------
    //
    // `a6 06` = sequence start, id 100 (`arrays`); `56` = sequence start, id 10
    // (`nested`); `05` = ARRAY_FIXLEN at id 0, declared array<fp32, count 5>.
    // `20` = fixlen_word fp32/4 (the matching subtype), `41` = fp64/8 (the
    // contradicting one), `22` = string/4 (illegal as an array element).
    // `07` = sequence end.

    /** Row 1: count 3, fp64 at the fp32 slot -> §7.3 skip of 3*8 payload bytes. */
    private static final byte[] R1_INCOUNT_MISTYPED =
            concat(bytes(0xA6, 0x06, 0x56, 0x05, 0x03, 0x41), new byte[24], bytes(0x07, 0x07));

    /** Row 2 (primary): count 8 > 5, but fp64 at the fp32 slot -> skip, no bound. */
    private static final byte[] R2_OVERCOUNT_MISTYPED =
            concat(bytes(0xA6, 0x06, 0x56, 0x05, 0x08, 0x41), new byte[64], bytes(0x07, 0x07));

    /** Row 3 (control): count 8 > 5 with the MATCHING fp32 subtype -> INVALID. */
    private static final byte[] R3_OVERCOUNT_MATCHING =
            concat(bytes(0xA6, 0x06, 0x56, 0x05, 0x08, 0x20), new byte[32], bytes(0x07, 0x07));

    /** Row 4 (primary): EOF between the count word and the fixlen_word -> INCOMPLETE. */
    private static final byte[] R4_TRUNC_BETWEEN_WORDS = bytes(0xA6, 0x06, 0x56, 0x05, 0x08);

    /** Row 5 (control): matching subtype, over-count, EOF before any payload -> INVALID. */
    private static final byte[] R5_OVERCOUNT_MATCHING_NOPAYLOAD =
            bytes(0xA6, 0x06, 0x56, 0x05, 0x08, 0x20);

    /** Row 6 (happy-path control): count 3, fp32, full payload -> ACCEPT. */
    private static final byte[] R6_CTL_VALID =
            concat(bytes(0xA6, 0x06, 0x56, 0x05, 0x03, 0x20), new byte[12], bytes(0x07, 0x07));

    /** Row 7: zero-count fixlen array still carries its fixlen_word (fp64 here). */
    private static final byte[] R7_ZERO_COUNT_MISTYPED =
            bytes(0xA6, 0x06, 0x56, 0x05, 0x00, 0x41, 0x07, 0x07);

    /** Row 8: fixlen_word 0x22 = subtype string / len 4 -> FORMAT violation, INVALID. */
    private static final byte[] R8_STRING_SUBTYPE =
            concat(bytes(0xA6, 0x06, 0x56, 0x05, 0x03, 0x22), new byte[12], bytes(0x07, 0x07));

    /** Row 9: an ARRAY_UNSIGNED header at the fp32 slot -> skip, bound inoperative. */
    private static final byte[] R9_INTEGER_ARRAY_AT_FIXLEN_SLOT =
            concat(bytes(0xA6, 0x06, 0x56, 0x03, 0x08), new byte[8], bytes(0x07, 0x07));

    // --- row 1: mistyped subtype, count within the bound --------------------

    @Test
    void mistypedSubtypeIsSkipped() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R1_INCOUNT_MISTYPED, s, chunk));
            // The hook fired once, naming the WIRE subtype (fp64), not a collapsed
            // "floating point" category and not the declared fp32.
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP64:3", "seq}", "seq}"), s.events);
            assertNull(s.fp32, "the declared fp32 field must keep its default");
            assertNull(s.fp64);
        }
    }

    // --- row 2: THE primary row ---------------------------------------------

    @Test
    void overCountWithMistypedSubtypeIsSkippedNotRejected() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // count 8 > the declared count 5, but the subtype contradicts the
            // declared fp32 FIRST, so the field was never this array's value and
            // its element count is not this array's count (§4.8 step 3).
            assertEquals("A", verdict(R2_OVERCOUNT_MISTYPED, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP64:8", "seq}", "seq}"), s.events);
            assertNull(s.fp32, "the declared fp32 field must keep its default");
        }
    }

    // --- row 3: THE control - the bound is reordered, not weakened ----------

    @Test
    void overCountWithMatchingSubtypeStaysInvalid() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("R:INVALID_MSG", verdict(R3_OVERCOUNT_MATCHING, s, chunk));
            // The hook still fired, with the matching kind - that is what let
            // generated code apply the bound.
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP32:8"), s.events);
        }
    }

    // --- row 4: the second primary row --------------------------------------

    @Test
    void eofBetweenCountAndFixlenWordIsIncomplete() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // The decoder cannot yet know whether this is a field it must bound,
            // so §5.2's precedence does not reach INVALID.
            assertEquals("I", verdict(R4_TRUNC_BETWEEN_WORDS, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10"), s.events,
                    "arrayBegin must not fire before the fixlen_word");
        }
    }

    // --- row 5: INVALID dominates INCOMPLETE once the subtype matches -------

    @Test
    void overCountWithMatchingSubtypeAndNoPayloadStaysInvalid() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // The fixlen_word arrived and matches, so the bound applies right then:
            // malformed regardless of what follows (§5.2), never INCOMPLETE.
            assertEquals("R:INVALID_MSG", verdict(R5_OVERCOUNT_MATCHING_NOPAYLOAD, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP32:8"), s.events);
        }
    }

    // --- row 6: happy path ---------------------------------------------------

    @Test
    void matchingSubtypeWithinBoundAccepts() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R6_CTL_VALID, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP32:3", "seq}", "seq}"), s.events);
            assertEquals(List.of(0.0f, 0.0f, 0.0f), s.fp32);
        }
    }

    @Test
    void matchingSubtypeRoundTripsByteIdentically() throws IOException {
        // Row 6 is the only vector in the set whose re-encode equals its input.
        Sink s = new Sink();
        assertEquals("A", verdict(R6_CTL_VALID, s, 0));

        float[] values = new float[s.fp32.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = s.fp32.get(i);
        }
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeSequenceBeginLazy(100);
        os.writeSequenceBeginLazy(10);
        os.writeArrayFp32(0, values);
        os.writeSequenceEnd();
        os.writeSequenceEnd();
        assertArrayEquals(R6_CTL_VALID, Arrays.copyOf(buf, os.bytesUsed()));
    }

    // --- row 7: the zero-count case the call-site move could drop -----------

    @Test
    void zeroCountFixlenArrayStillAnnouncesItsSubtype() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R7_ZERO_COUNT_MISTYPED, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP64:0", "seq}", "seq}"), s.events);
            assertNull(s.fp32, "a mistyped empty array leaves the declared field alone");
        }
    }

    @Test
    void emptyFp32ArrayStaysDistinguishableFromEmptyFp64() {
        for (int chunk : CHUNKS) {
            Sink fp32 = new Sink();
            assertEquals("A", verdict(bytes(0xA6, 0x06, 0x56, 0x05, 0x00, 0x20, 0x07, 0x07), fp32, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP32:0", "seq}", "seq}"), fp32.events);
            assertEquals(List.of(), fp32.fp32, "a matching empty array is materialized, empty");

            Sink fp64 = new Sink();
            assertEquals("A", verdict(bytes(0xA6, 0x06, 0x56, 0x05, 0x00, 0x41, 0x07, 0x07), fp64, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:FP64:0", "seq}", "seq}"), fp64.events);
        }
    }

    // --- row 8: format violation, judged before the hook --------------------

    @Test
    void illegalArraySubtypeIsInvalidNotSkipped() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // §4.8 permits only fp32 and fp64 as fixlen-array elements. A string
            // subtype is a FORMAT violation and must NOT be routed to the §7.3
            // skip path even though it also contradicts the declared fp32.
            assertEquals("R:INVALID_MSG", verdict(R8_STRING_SUBTYPE, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10"), s.events,
                    "a format-illegal word is rejected before the field is offered");
        }
    }

    @Test
    void fixlenArrayWidthMismatchIsInvalid() {
        for (int chunk : CHUNKS) {
            // 0x21 = subtype fp64 with elem_len 4; 0x40 = subtype fp32 with elem_len 8.
            for (int word : new int[] { 0x21, 0x40 }) {
                Sink s = new Sink();
                assertEquals("R:INVALID_MSG",
                        verdict(bytes(0xA6, 0x06, 0x56, 0x05, 0x03, word), s, chunk));
                assertEquals(List.of("seq{:100", "seq{:10"), s.events);
            }
        }
    }

    // --- row 9: the same reasoning one step earlier on the wire -------------

    @Test
    void integerArrayHeaderAtFixlenSlotIsSkippedWithoutTheBound() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R9_INTEGER_ARRAY_AT_FIXLEN_SLOT, s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:UNSIGNED:8", "seq}", "seq}"), s.events);
            assertNull(s.fp32);
        }
    }

    // --- what must NOT move -------------------------------------------------

    @Test
    void integerArrayIsAnnouncedOnTheCountWord() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // Nothing follows the count for an integer array - no second word - so
            // the hook must still fire immediately, before any element arrives.
            assertEquals("I", verdict(bytes(0xA6, 0x06, 0x56, 0x03, 0x08), s, chunk));
            assertEquals(List.of("seq{:100", "seq{:10", "arr:0:UNSIGNED:8"), s.events);
        }
    }

    @Test
    void arrayMaxCeilingStillFiresOnTheCountWord() {
        for (int chunk : CHUNKS) {
            for (int type : new int[] { 0x05, 0x03, 0x04 }) {
                Sink s = new Sink();
                // count = 2^31 exceeds ARRAY_MAX (2^31 - 1). It is a FORMAT bound,
                // so it fires on the count word whatever the subtype turns out to
                // be - INVALID, never INCOMPLETE, and nothing is announced.
                assertEquals("R:INVALID_MSG",
                        verdict(bytes(0xA6, 0x06, 0x56, type, 0x80, 0x80, 0x80, 0x80, 0x08), s, chunk));
                assertEquals(List.of("seq{:100", "seq{:10"), s.events);
            }
        }
    }

    @Test
    void skippedOccurrenceDoesNotClobberAnEarlierCorrectOne() {
        // MESSAGE_SPEC §7.4: an occurrence skipped under §7.3 is not an occurrence.
        byte[] msg = concat(
                bytes(0xA6, 0x06, 0x56),
                concat(bytes(0x05, 0x03, 0x20), new byte[12], new byte[0]),   // fp32, kept
                concat(bytes(0x05, 0x03, 0x41), new byte[24], bytes(0x07, 0x07))); // fp64, skipped
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(msg, s, chunk));
            assertEquals(
                    List.of("seq{:100", "seq{:10", "arr:0:FP32:3", "arr:0:FP64:3", "seq}", "seq}"),
                    s.events);
            assertEquals(List.of(0.0f, 0.0f, 0.0f), s.fp32,
                    "the mistyped later occurrence must not clear the earlier value");
        }
    }

    @Test
    void arrayBeginFiresExactlyOncePerFieldNotPerElement() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R6_CTL_VALID, s, chunk));
            assertEquals(1, s.events.stream().filter(e -> e.startsWith("arr:")).count());
        }
    }

    @Test
    void aVisitorThatIgnoresArrayBeginStillDecodes() {
        // The hook is a default no-op; a visitor that never overrides it must not
        // see a behaviour change from the call-site move.
        List<String> seen = new ArrayList<>();
        assertEquals("A", verdict(R6_CTL_VALID, new Visitor() {
            @Override public void fp32(int id, float v) { seen.add("f32:" + id); }
        }, 0));
        assertEquals(List.of("f32:0", "f32:0", "f32:0"), seen);
    }

    // --- harness ------------------------------------------------------------
    //
    // The rows above are read through Decode.verdict at Decode.CHUNKS: whole
    // buffer (the fast path), byte-at-a-time and 3-byte chunks (the state
    // machine).

    @Test
    void harnessReportsAnUnrelatedRejectionUnchanged() {
        // Guard the harness itself: a plainly malformed message still surfaces as R.
        assertThrows(SofabException.class, () -> new IStream().feed(bytes(0x02, 0x04), new Sink()));
    }

    /**
     * A visitor shaped like sofabgen's emitted one for the probe schema's
     * {@code arrays.nested} scope: {@code id 0 = array<fp32, count 5>},
     * {@code id 1 = array<fp64, count 5>}. It keys the discard counter and the
     * schema {@code count} bound on the announced element kind, and applies the
     * bound only inside the arm matching the declared element type.
     */
    private static final class Sink implements Visitor {

        private static final int ROOT = 0;
        private static final int ARRAYS = 2;
        private static final int NESTED = 3;

        final List<String> events = new ArrayList<>();
        /** Declared fields; null means "still at its default" (never materialized). */
        List<Float> fp32;
        List<Double> fp64;

        private int cur = ROOT;
        private final List<Integer> stack = new ArrayList<>();
        /** Elements still to discard from a field skipped under §7.3. */
        private int askip;

        @Override
        public void sequenceBegin(int id) {
            events.add("seq{:" + id);
            stack.add(cur);
            if (cur == ROOT && id == 100) {
                cur = ARRAYS;
            } else if (cur == ARRAYS && id == 10) {
                cur = NESTED;
            }
        }

        @Override
        public void sequenceEnd() {
            events.add("seq}");
            cur = stack.isEmpty() ? ROOT : stack.remove(stack.size() - 1);
        }

        @Override
        public void arrayBegin(int id, ArrayKind kind, int count) {
            events.add("arr:" + id + ":" + kind + ":" + count);
            // An array delivered at an id that does not declare one of the SAME
            // element kind is a wire-type contradiction: arm a discard counter so
            // the element callbacks drop exactly `count` elements (§7.3).
            askip = count;
            if (cur == NESTED && ((kind == ArrayKind.FP32 && id == 0) || (kind == ArrayKind.FP64 && id == 1))) {
                askip = 0;
            }
            if (cur != NESTED) {
                return;
            }
            // The schema bound lives INSIDE the matching arm: a non-matching kind
            // breaks out above it, straight to the skip path.
            switch (id) {
                case 0:
                    if (kind != ArrayKind.FP32) {
                        break;
                    }
                    if (count > 5) {
                        throw invalid("fp32: array count above schema capacity 5");
                    }
                    fp32 = new ArrayList<>();
                    break;
                case 1:
                    if (kind != ArrayKind.FP64) {
                        break;
                    }
                    if (count > 5) {
                        throw invalid("fp64: array count above schema capacity 5");
                    }
                    fp64 = new ArrayList<>();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void fp32(int id, float value) {
            if (askip > 0) {
                askip--;
                return;
            }
            if (cur == NESTED && id == 0 && fp32 != null) {
                fp32.add(value);
            }
        }

        @Override
        public void fp64(int id, double value) {
            if (askip > 0) {
                askip--;
                return;
            }
            if (cur == NESTED && id == 1 && fp64 != null) {
                fp64.add(value);
            }
        }

        @Override
        public void unsigned(int id, long value) {
            if (askip > 0) {
                askip--;
            }
        }

        @Override
        public void signed(int id, long value) {
            if (askip > 0) {
                askip--;
            }
        }

        private static UncheckedIOException invalid(String detail) {
            return new UncheckedIOException(new SofabException(SofabError.INVALID_MSG, detail));
        }
    }
}
