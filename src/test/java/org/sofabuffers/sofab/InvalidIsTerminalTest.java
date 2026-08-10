/*
 * SofaBuffers Java - the INVALID decode outcome is TERMINAL (corelib-java#71).
 *
 * CORELIB_PLAN §5.2: INVALID means "malformed regardless of what follows" and the
 * outcome table's last column reads "no - terminal". A decoder therefore MUST NOT
 * report INCOMPLETE - let alone COMPLETE - for input it has already determined to
 * be malformed, and no continuation of bytes may change that verdict. The status
 * a decode reports IS the answer; there is no finalize step that revises it.
 *
 * Before the fix IStream surfaced INVALID only as a thrown SofabException and kept
 * no record of it: status() answered COMPLETE right after the throw and a further
 * feed happily decoded the next bytes into the visitor - a caller whose loop logs
 * and continues on IOException resumed mid-stream on a message it had proven
 * malformed and finally read the verdict COMPLETE.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.UncheckedIOException;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.sofabuffers.sofab.common.RecordingVisitor;

class InvalidIsTerminalTest {

    /** Feed {@code data} and assert it is rejected as INVALID_MSG. */
    private static void assertRejects(IStream is, Visitor v, byte[] data) {
        SofabException e = assertThrows(SofabException.class, () -> is.feed(data, v));
        assertEquals(SofabError.INVALID_MSG, e.error());
    }

    // --- the verdict itself -------------------------------------------------

    @Test
    void statusReportsInvalidAfterADanglingSequenceEnd() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x07));           // sequence end, no open sequence

        assertEquals(DecodeStatus.INVALID, is.status());
    }

    /**
     * The malformed construct sits in the middle of an otherwise decodable stream:
     * an fp64 field (id 2) whose fixlen_word declares length 11 instead of 8.
     * Fields decoded before it stay delivered - the visitor saw what it saw - but
     * the outcome for the bytes consumed so far is INVALID.
     */
    @Test
    void statusReportsInvalidAfterAMalformedFixlenWordMidMessage() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x08, 0x01, 0x12, 0x59));

        assertEquals(List.of("u:1=1"), v.events);
        assertEquals(DecodeStatus.INVALID, is.status());
    }

    /** A malformed message that is ALSO truncated is INVALID, never INCOMPLETE. */
    @Test
    void invalidWinsOverIncomplete() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        // Sequence start (id 6), then an fp64 fixlen_word declaring length 11 - the
        // word alone proves the message malformed - and then it simply stops, with
        // the sequence still open.
        assertRejects(is, v, bytes(0x36, 0x22, 0x59));

        assertEquals(DecodeStatus.INVALID, is.status());
    }

    // --- terminal: no continuation may revise it ----------------------------

    @Test
    void aFurtherFeedNeitherDecodesNorClearsTheVerdict() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x07));

        // Well-formed bytes, fed to a decoder that has already answered INVALID:
        // rejected on arrival, with nothing handed to the visitor.
        assertRejects(is, v, bytes(0x00, 0x2A));

        assertEquals(List.of(), v.events);
        assertEquals(DecodeStatus.INVALID, is.status());
    }

    @Test
    void theSliceOverloadIsGuardedToo() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x07));

        byte[] more = bytes(0xFF, 0x00, 0x2A, 0xFF);
        SofabException e = assertThrows(SofabException.class, () -> is.feed(more, 1, 2, v));
        assertEquals(SofabError.INVALID_MSG, e.error());
        assertEquals(List.of(), v.events);
        assertEquals(DecodeStatus.INVALID, is.status());
    }

    /** An empty feed is not a loophole: the verdict stands and the call still throws. */
    @Test
    void anEmptyFeedDoesNotClearTheVerdict() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x07));

        assertRejects(is, v, new byte[0]);
        assertEquals(DecodeStatus.INVALID, is.status());
    }

    /**
     * Latching happens wherever the decoder rejects, including deep in the
     * byte-at-a-time state machine that runs when a field straddles a feed
     * boundary - one byte per feed here, so nothing takes the contiguous fast path.
     */
    @Test
    void latchesOnTheResumableStateMachineToo() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        // fp32 field id 1, fixlen_word declaring length 5 (only 4 is legal), split
        // one byte per feed so the fixlen_word is read by the resumable machine.
        assertThrows(SofabException.class, () -> {
            is.feed(bytes(0x0A), v);                 // header: fixlen, id 1
            is.feed(bytes(0x28), v);                 // fixlen_word: (5 << 3) | fp32
        });

        assertEquals(DecodeStatus.INVALID, is.status());
        assertRejects(is, v, bytes(0x00));
    }

    /** Every INVALID condition of §5.2's table latches, not just the two above. */
    @Test
    void everyMalformedConstructLatches() {
        byte[][] malformed = {
            bytes(0x07),                                                   // dangling sequence end
            bytes(0x0A, 0x2C),                                             // reserved fixlen subtype 0x4
            bytes(0x0A, 0x28),                                             // fp32 length != 4
            bytes(0x0A, 0x59),                                             // fp64 length != 8
            bytes(0x08, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80,
                  0x80, 0x80, 0x01),                                       // varint over 64 bits
            bytes(0xF8, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F),                     // id above ID_MAX
            bytes(0x0B, 0x80, 0x80, 0x80, 0x80, 0x08),                     // array count above max
        };
        for (byte[] m : malformed) {
            IStream is = new IStream();
            RecordingVisitor v = new RecordingVisitor();
            assertRejects(is, v, m);
            assertEquals(DecodeStatus.INVALID, is.status(), "not latched: " + hex(m));
            assertRejects(is, v, bytes(0x00, 0x2A));
        }
    }

    @Test
    void nestingPastMaxDepthLatches() {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();
        byte[] open = bytes(0x0E);                    // sequence start, id 1
        assertThrows(SofabException.class, () -> {
            for (int i = 0; i <= Sofab.MAX_DEPTH; i++) {
                is.feed(open, v);
            }
        });
        assertEquals(DecodeStatus.INVALID, is.status());
    }

    // --- reset() is the only way back ---------------------------------------

    @Test
    void resetClearsTheVerdictAndResynchronises() throws Exception {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        assertRejects(is, v, bytes(0x07));
        assertEquals(DecodeStatus.INVALID, is.status());

        is.reset();

        assertEquals(DecodeStatus.COMPLETE, is.status());
        is.feed(bytes(0x00, 0x2A), v);
        assertEquals(List.of("u:0=42"), v.events);
        assertEquals(DecodeStatus.COMPLETE, is.status());
    }

    // --- what does NOT latch ------------------------------------------------

    /** A merely truncated message stays INCOMPLETE and keeps decoding (§5.2). */
    @Test
    void truncationDoesNotLatch() throws Exception {
        IStream is = new IStream();
        RecordingVisitor v = new RecordingVisitor();

        is.feed(bytes(0x80), v);                     // a dangling varint prefix
        assertEquals(DecodeStatus.INCOMPLETE, is.status());

        is.feed(bytes(0x01, 0x2A), v);               // ... completed by the next chunk
        assertEquals(List.of("u:16=42"), v.events);
        assertEquals(DecodeStatus.COMPLETE, is.status());
    }

    /**
     * A receiver-side limit (§6.2.1) is a policy rejection of well-formed bytes: it
     * is explicitly NOT the INVALID outcome, so it must not latch one. Generated
     * code raises it from a visitor callback, wrapped because {@link Visitor}
     * declares no checked exception.
     */
    @Test
    void aLimitExceededFromTheVisitorDoesNotLatchInvalid() {
        IStream is = new IStream();
        Visitor limiter = new Visitor() {
            @Override
            public void unsigned(int id, long value) {
                throw new UncheckedIOException(new SofabException(SofabError.LIMIT_EXCEEDED, "policy"));
            }
        };

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> is.feed(bytes(0x00, 0x2A), limiter));
        assertEquals(SofabError.LIMIT_EXCEEDED, ((SofabException) e.getCause()).error());
        assertTrue(is.status() != DecodeStatus.INVALID, "a policy limit is not INVALID");
    }

    /**
     * A visitor can also fail for reasons of its own - here the sink it writes decoded
     * fields into, surfaced as an {@link UncheckedIOException} whose cause is a plain
     * {@link java.io.IOException} rather than a {@link SofabException}. That says
     * nothing about the wire either: latching INVALID on it would condemn a message
     * never shown to be malformed and make every later feed throw, so the failure
     * passes through untouched and the decoder stays usable.
     */
    @Test
    void aVisitorsOwnIoFailureDoesNotLatchInvalid() throws Exception {
        IStream is = new IStream();
        Visitor failing = new Visitor() {
            @Override
            public void unsigned(int id, long value) {
                throw new UncheckedIOException(new java.io.IOException("downstream sink failed"));
            }
        };

        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> is.feed(bytes(0x00, 0x2A), failing));
        assertEquals("downstream sink failed", e.getCause().getMessage());
        assertTrue(is.status() != DecodeStatus.INVALID, "a sink failure is not a wire verdict");

        RecordingVisitor v = new RecordingVisitor();
        is.feed(bytes(0x08, 0x07), v);
        assertEquals(List.of("u:1=7"), v.events);
        assertEquals(DecodeStatus.COMPLETE, is.status());
    }

    /**
     * The mirror image: generated code rejects a schema bound (MESSAGE_SPEC §7.1)
     * with INVALID_MSG from a visitor callback, and that IS the INVALID outcome -
     * so the decoder it was rejected in must report it, not COMPLETE.
     */
    @Test
    void anInvalidMsgFromTheVisitorLatches() {
        IStream is = new IStream();
        Visitor bound = new Visitor() {
            @Override
            public void fixlenBegin(int id, FixlenType subtype, int total) {
                if (total > 2) {
                    throw new UncheckedIOException(
                            new SofabException(SofabError.INVALID_MSG, "s: length above schema maxlen 2"));
                }
            }
        };

        // string field id 0, declared length 3 - over the visitor's schema bound.
        assertThrows(UncheckedIOException.class,
                () -> is.feed(bytes(0x02, 0x1A, 0x61, 0x62, 0x63), bound));

        assertEquals(DecodeStatus.INVALID, is.status());
        assertRejects(is, bound, bytes(0x00, 0x2A));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}
