/*
 * SofaBuffers Java - the fixlen field header hook fires at the length word.
 *
 * CORELIB_PLAN §5.2 makes INVALID dominate INCOMPLETE: once the bytes seen so
 * far are already malformed, running out of input cannot downgrade the verdict.
 * A `maxlen` violation is fully established by the fixlen length word — the
 * number that exceeds the bound is already on the wire and no later byte can
 * make it legal.
 *
 * Generated code cannot latch it there unless the decoder says so. The payload
 * callbacks (`string` / `blob`) carry `total`, but they only fire once payload
 * bytes exist, so a message truncated immediately after the length word used to
 * produce no visitor event at all: the verdict decayed to INCOMPLETE while the
 * same bytes arriving whole were INVALID. That is a chunk-boundary-dependent
 * outcome, which §6.4 and §7.2 item 4 forbid outright.
 *
 * `Visitor.fixlenBegin(id, subtype, total)` closes that gap — the same thing
 * `arrayBegin` already does one field kind over (see FixlenArrayHeaderOrderTest).
 * These tests drive the decoder with a visitor shaped like sofabgen's emitted
 * one for a scope declaring id 3 = string(maxlen 8) and id 5 = blob(maxlen 8),
 * and assert both halves: the over-bound field is INVALID at every chunking, and
 * the in-bound control stays INCOMPLETE. This is an ORDERING fix, not a blanket
 * reject.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class FixlenHeaderAnnounceTest {

    // --- wire vectors -------------------------------------------------------
    //
    // `1a` = T_FIXLEN at id 3 (the declared string), `2a` = T_FIXLEN at id 5 (the
    // declared blob), `22` = T_FIXLEN at id 4 (a declared fp32). A fixlen_word is
    // `(length << 3) | subtype`, subtype 0 = fp32, 1 = fp64, 2 = string, 3 = blob:
    // `52` = string/10, `32` = string/6, `02` = string/0, `53` = blob/10,
    // `20` = fp32/4, `41` = fp64/8.

    /** THE row: string of 10 > maxlen 8, message ends exactly at the length word. */
    private static final byte[] R1_OVER_TRUNC_AT_WORD = bytes(0x1A, 0x52);

    /** THE control: same truncation, length 6 inside the bound -> still INCOMPLETE. */
    private static final byte[] R2_INBOUND_TRUNC_AT_WORD = bytes(0x1A, 0x32);

    /** Over-bound with the payload present: INVALID before and after this change. */
    private static final byte[] R3_OVER_WITH_PAYLOAD =
            concat(bytes(0x1A, 0x52), new byte[10]);

    /** Happy path: string of 6, payload present. */
    private static final byte[] R4_INBOUND_WITH_PAYLOAD =
            concat(bytes(0x1A, 0x32), "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8));

    /** Empty string: the word is the whole field, and it is still announced. */
    private static final byte[] R5_EMPTY = bytes(0x1A, 0x02);

    /** Blob of 10 > maxlen 8 at the declared blob id, truncated at the word. */
    private static final byte[] R6_BLOB_OVER_TRUNC_AT_WORD = bytes(0x2A, 0x53);

    /**
     * Subtype contradiction: a blob of 10 arrives where a string is declared. The
     * field is a §7.3 skip, so the string's bound must NOT be applied to it.
     */
    private static final byte[] R7_MISTYPED_OVER_TRUNC_AT_WORD = bytes(0x1A, 0x53);

    /** fp32 field truncated at its length word — nothing to bound, but announced. */
    private static final byte[] R8_FP32_TRUNC_AT_WORD = bytes(0x22, 0x20);

    /** fp64 field truncated at its length word. */
    private static final byte[] R9_FP64_TRUNC_AT_WORD = bytes(0x22, 0x41);

    /** A fixlen ARRAY: announced by arrayBegin, so no fixlenBegin may fire. */
    private static final byte[] R10_FIXLEN_ARRAY =
            concat(bytes(0x05, 0x03, 0x20), new byte[12]);

    // --- the primary row ----------------------------------------------------

    @Test
    void overMaxlenIsInvalidWhenTruncatedAtTheLengthWord() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // Before fixlenBegin the visitor saw nothing at all here and this read
            // "I" for every chunking, contradicting the whole-payload verdict.
            assertEquals("R:INVALID_MSG", verdict(R1_OVER_TRUNC_AT_WORD, s, chunk),
                    "chunk=" + chunk);
            assertEquals(List.of("fix:3:STRING:10"), s.events);
        }
    }

    // --- the control: an ordering fix, not a blanket reject -----------------

    @Test
    void inBoundLengthTruncatedAtTheLengthWordStaysIncomplete() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("I", verdict(R2_INBOUND_TRUNC_AT_WORD, s, chunk), "chunk=" + chunk);
            // Announced, accepted, and waiting for the six payload bytes.
            assertEquals(List.of("fix:3:STRING:6"), s.events);
        }
    }

    // --- the verdict no longer depends on where the chunks fall -------------

    @Test
    void theVerdictIsTheSameWithAndWithoutThePayload() {
        for (int chunk : CHUNKS) {
            assertEquals("R:INVALID_MSG", verdict(R3_OVER_WITH_PAYLOAD, new Sink(), chunk));
            assertEquals(verdict(R3_OVER_WITH_PAYLOAD, new Sink(), chunk),
                    verdict(R1_OVER_TRUNC_AT_WORD, new Sink(), chunk),
                    "truncating at the length word must not change the verdict");
        }
    }

    // --- exactly once per field, whatever the chunking ----------------------

    @Test
    void announcedExactlyOncePerFieldNotPerPayloadChunk() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R4_INBOUND_WITH_PAYLOAD, s, chunk), "chunk=" + chunk);
            assertEquals(List.of("fix:3:STRING:6", "str:3=abcdef"), s.events,
                    "one header event, before any payload event");
        }
    }

    @Test
    void anEmptyFieldIsAnnouncedToo() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R5_EMPTY, s, chunk), "chunk=" + chunk);
            assertEquals(List.of("fix:3:STRING:0", "str:3="), s.events);
        }
    }

    @Test
    void everyFixlenSubtypeIsAnnounced() {
        for (int chunk : CHUNKS) {
            Sink blob = new Sink();
            assertEquals("R:INVALID_MSG", verdict(R6_BLOB_OVER_TRUNC_AT_WORD, blob, chunk));
            assertEquals(List.of("fix:5:BLOB:10"), blob.events);

            Sink f32 = new Sink();
            assertEquals("I", verdict(R8_FP32_TRUNC_AT_WORD, f32, chunk));
            assertEquals(List.of("fix:4:FP32:4"), f32.events);

            Sink f64 = new Sink();
            assertEquals("I", verdict(R9_FP64_TRUNC_AT_WORD, f64, chunk));
            assertEquals(List.of("fix:4:FP64:8"), f64.events);
        }
    }

    // --- the subtype that ARRIVED, so a §7.3 skip stays a skip --------------

    @Test
    void aContradictingSubtypeIsNotMeasuredAgainstTheFieldsBound() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            // A blob at the declared string's id is a wire-type contradiction: the
            // field was never this field's value, so its length is not this field's
            // length and the maxlen bound must not fire on it (§7.3).
            assertEquals("I", verdict(R7_MISTYPED_OVER_TRUNC_AT_WORD, s, chunk), "chunk=" + chunk);
            assertEquals(List.of("fix:3:BLOB:10"), s.events,
                    "the hook must carry the subtype that arrived, not the declared one");
        }
    }

    // --- a fixlen ARRAY is arrayBegin's business, not this hook's -----------

    @Test
    void aFixlenArrayIsNotAnnouncedAsAFixlenField() {
        for (int chunk : CHUNKS) {
            Sink s = new Sink();
            assertEquals("A", verdict(R10_FIXLEN_ARRAY, s, chunk), "chunk=" + chunk);
            assertTrue(s.events.stream().noneMatch(e -> e.startsWith("fix:")),
                    "the per-element fixlen_word is the array's header: " + s.events);
            assertEquals(1, s.events.stream().filter(e -> e.startsWith("arr:")).count());
        }
    }

    // --- the hook is optional -----------------------------------------------

    @Test
    void aVisitorThatIgnoresFixlenBeginStillDecodes() {
        // fixlenBegin is a default no-op; every visitor written before it existed
        // must keep behaving exactly as it did.
        List<String> seen = new ArrayList<>();
        assertEquals("A", verdict(R4_INBOUND_WITH_PAYLOAD, new Visitor() {
            @Override
            public void string(int id, int total, int off, byte[] d, int co, int cl) {
                seen.add("str:" + id + ":" + total + ":" + off + ":" + cl);
            }
        }, 0));
        assertEquals(List.of("str:3:6:0:6"), seen);
    }

    // --- harness ------------------------------------------------------------

    /** Whole-buffer (fast path), byte-at-a-time and 3-byte chunks (state machine). */
    private static final int[] CHUNKS = { 0, 1, 3 };

    /**
     * Feed {@code data} to a fresh decoder in {@code chunk}-byte slices (0 = one
     * whole feed, which takes the pointer-advancing fast path) and reduce the
     * outcome to the three-valued verdict: {@code "A"} accept, {@code "I"}
     * incomplete, {@code "R:<error>"} rejected.
     */
    private static String verdict(byte[] data, Visitor sink, int chunk) {
        IStream in = new IStream();
        try {
            if (chunk <= 0) {
                in.feed(data, sink);
            } else {
                for (int i = 0; i < data.length; i += chunk) {
                    in.feed(data, i, Math.min(chunk, data.length - i), sink);
                }
            }
        } catch (SofabException e) {
            return "R:" + e.error();
        } catch (UncheckedIOException e) {
            // How sofabgen's Java backend aborts from a visitor callback, whose
            // signature declares no checked exception.
            return "R:" + ((SofabException) e.getCause()).error();
        }
        return in.status() == DecodeStatus.COMPLETE ? "A" : "I";
    }

    /**
     * A visitor shaped like sofabgen's emitted one for a scope declaring
     * {@code id 3 = string(maxlen 8)}, {@code id 4 = fp32} and
     * {@code id 5 = blob(maxlen 8)}. The bound lives inside the arm matching the
     * declared subtype, so a contradicting subtype falls through to the skip path
     * instead of being measured against a bound that does not apply to it.
     */
    private static final class Sink implements Visitor {

        final List<String> events = new ArrayList<>();

        @Override
        public void fixlenBegin(int id, FixlenType subtype, int total) {
            events.add("fix:" + id + ":" + subtype + ":" + total);
            switch (id) {
                case 3:
                    if (subtype != FixlenType.STRING) {
                        break; // §7.3 skip: not this field's value, not its length
                    }
                    if (total > 8) {
                        throw invalid("id 3: string length above schema maxlen 8");
                    }
                    break;
                case 5:
                    if (subtype != FixlenType.BLOB) {
                        break;
                    }
                    if (total > 8) {
                        throw invalid("id 5: blob length above schema maxlen 8");
                    }
                    break;
                default:
                    break;
            }
        }

        /** Payload reassembled across chunks, so the events stay chunking-independent. */
        private final StringBuilder payload = new StringBuilder();

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            payload.append(new String(data, chunkOffset, chunkLength,
                    java.nio.charset.StandardCharsets.UTF_8));
            if (offset + chunkLength >= total) {
                events.add("str:" + id + "=" + payload);
                payload.setLength(0);
            }
        }

        @Override
        public void blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            if (offset + chunkLength >= total) {
                events.add("blob:" + id + ":" + total);
            }
        }

        @Override
        public void fp32(int id, float value) {
            events.add("f32:" + id + "=" + value);
        }

        @Override
        public void fp64(int id, double value) {
            events.add("f64:" + id + "=" + value);
        }

        @Override
        public void arrayBegin(int id, ArrayKind kind, int count) {
            events.add("arr:" + id + ":" + kind + ":" + count);
        }

        private static UncheckedIOException invalid(String detail) {
            return new UncheckedIOException(new SofabException(SofabError.INVALID_MSG, detail));
        }
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] out = new byte[n];
        int at = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, at, p.length);
            at += p.length;
        }
        return out;
    }
}
