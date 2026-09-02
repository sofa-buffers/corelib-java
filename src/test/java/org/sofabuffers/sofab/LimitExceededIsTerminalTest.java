/*
 * SofaBuffers Java - a LIMIT_EXCEEDED refusal is TERMINAL for the decode
 * (finding CORELIB_JAVA-10).
 *
 * CORELIB_PLAN §6.3 calls LimitExceeded "a TERMINAL, receiver-local policy
 * rejection" and leaves only its SURFACING open: "either a fourth decode
 * outcome, or a terminal failure carrying the LimitExceeded code on the error
 * channel". Terminal either way - so a decode a receiver refused on policy
 * grounds may not be resumed, and the verdict it reports afterwards may not be
 * COMPLETE (§5.2.1: that outcome means "a valid message may end here") nor
 * INCOMPLETE (§5.2.3: that outcome invites the caller to feed more, which is
 * exactly what must not happen).
 *
 * IStream.feed latches the code of whichever rejection ended the decode, and
 * LIMIT_EXCEEDED is one of the two that end one (INVALID_MSG is the other). A cap
 * refusal therefore closes the decode: every further feed repeats the refusal under
 * that same code instead of decoding on, and only reset() starts a new message.
 * Without that latch the decoder was left parked mid-field, reported the message
 * COMPLETE, and a further feed re-entered header parsing at a desynchronised
 * position, handing the refused payload's own bytes to the visitor as fields the
 * sender never wrote ('h' = 0x68 -> id 13, wire type 0; 'e' = 0x65 -> the value
 * 101). That last part is why the finding is critical: the receiver acts on data no
 * sender sent.
 *
 * These tests are the mirror image of InvalidIsTerminalTest, which pins the same
 * property for the malformed-bytes outcome. Of §6.3's two surfacings - "a fourth
 * decode outcome, or a terminal failure carrying the LimitExceeded code on the
 * error channel" - this port takes the second, so the verdict is asked for the only
 * way it can be: by feeding again. A refused decoder answers with the refusal, not
 * with either of the two non-terminal outcomes.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class LimitExceededIsTerminalTest {

    /** The finding this class pins, repeated in every failure message. */
    private static final String FINDING = "CORELIB_JAVA-10";

    /** A receiver cap (§6.2.1) small enough that the string below runs past it. */
    private static final int CAP = 2;

    /** The one string this port's own encoder writes, and the one the cap refuses. */
    private static final String TEXT = "hello";

    /** The payload of that string on its own - the bytes the refusal parks in front of. */
    private static final byte[] PAYLOAD = TEXT.getBytes(StandardCharsets.UTF_8);

    /** The single field the sender ever wrote; anything else reaching the visitor is fabricated. */
    private static final String THE_ONLY_FIELD_SENT = "fixlenBegin:1:STRING:" + PAYLOAD.length;

    /** One string field, id 1, encoded by this library's own {@link OStream}. */
    private static byte[] message() throws IOException {
        byte[] buf = new byte[32];
        OStream os = new OStream(buf);
        os.writeString(1, TEXT);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /**
     * Generated code's half of §6.2.1: it holds the receiver cap, compares it at
     * the length header, and refuses through {@link Sofab#limitExceeded} - the
     * only channel a {@link Visitor} callback has, since it declares no checked
     * exception. Every other callback is recorded, so a test can ask what the
     * decoder actually delivered.
     */
    private static final class Capping implements Visitor {

        final List<String> events = new ArrayList<>();

        @Override
        public void fixlenBegin(int id, FixlenType subtype, int total) {
            events.add("fixlenBegin:" + id + ":" + subtype + ":" + total);
            if (total > CAP) {
                throw Sofab.limitExceeded("s: length " + total + " above receiver cap " + CAP);
            }
        }

        @Override
        public void unsigned(int id, long value) {
            events.add("unsigned:" + id + "=" + Long.toUnsignedString(value));
        }

        @Override
        public void signed(int id, long value) {
            events.add("signed:" + id + "=" + value);
        }

        @Override
        public void fp32(int id, float value) {
            events.add("fp32:" + id + "=" + value);
        }

        @Override
        public void fp64(int id, double value) {
            events.add("fp64:" + id + "=" + value);
        }

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            events.add("string:" + id + "/" + total);
        }

        @Override
        public void blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
            events.add("blob:" + id + "/" + total);
        }

        @Override
        public void arrayBegin(int id, ArrayKind kind, int count) {
            events.add("arrayBegin:" + id + ":" + kind + "/" + count);
        }

        @Override
        public void sequenceBegin(int id) {
            events.add("sequenceBegin:" + id);
        }

        @Override
        public void sequenceEnd() {
            events.add("sequenceEnd");
        }
    }

    /** Feed {@code data} to {@code in} in {@code chunk}-byte slices (0 = one whole feed). */
    private static void feedIn(IStream in, byte[] data, Visitor v, int chunk) throws IOException {
        if (chunk <= 0) {
            in.feed(data, v);
            return;
        }
        for (int i = 0; i < data.length; i += chunk) {
            in.feed(data, i, Math.min(chunk, data.length - i), v);
        }
    }

    /** The error a visitor-side rejection carries out of a callback. */
    private static SofabError categoryOf(UncheckedIOException e) {
        return ((SofabException) e.getCause()).error();
    }

    /**
     * A fresh decoder fed the capped message in {@code chunk}-byte slices, up to
     * and including the LIMIT_EXCEEDED refusal that §6.2.1 says the cap must
     * raise. Returns the decoder, parked exactly where the refusal left it.
     */
    private static IStream refused(Capping v, int chunk) throws IOException {
        IStream in = new IStream();
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> feedIn(in, message(), v, chunk), FINDING + ": the cap must refuse, chunk=" + chunk);
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(e),
                FINDING + ": a receiver cap is LIMIT_EXCEEDED, never INVALID_MSG (§6.3), chunk=" + chunk);
        assertEquals(List.of(THE_ONLY_FIELD_SENT), v.events,
                FINDING + ": the refusal happens at the length header, chunk=" + chunk);
        return in;
    }

    // --- (a) the verdict after the refusal ----------------------------------

    /**
     * §6.3: LimitExceeded is "a terminal, receiver-local policy rejection", and
     * the surfacing choice is between "a fourth decode outcome" and "a terminal
     * failure carrying the LimitExceeded code". Neither of those is COMPLETE -
     * §5.2.1 defines that as "a valid message may end here", and this decode
     * stopped inside a field the receiver refused - and neither is INCOMPLETE,
     * which §5.2.3 reserves for input a continuation may still complete.
     */
    @Test
    void theOutcomeIsTerminalAfterALimitRefusal_CORELIB_JAVA_10() throws IOException {
        for (int chunk : CHUNKS) {
            Capping v = new Capping();
            IStream in = refused(v, chunk);

            // Asking again is the only way to ask: the outcome travels with the
            // feed call. An empty feed adds no bytes and so puts exactly the old
            // question - "where do I stand?" - and a refused decode answers it with
            // the refusal rather than with COMPLETE or INCOMPLETE.
            UncheckedIOException after = assertThrows(UncheckedIOException.class,
                    () -> in.feed(new byte[0], v),
                    FINDING + ": a refused message neither completed (§6.3, §5.2.1) nor may "
                            + "invite more bytes (§6.3, §5.2.3), chunk=" + chunk);
            assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(after),
                    FINDING + ": the refusal keeps its own code (§6.3), chunk=" + chunk);
        }
    }

    // --- (b) no continuation may resume it ----------------------------------

    /**
     * "Terminal" means the decode is over: a caller that catches the refusal and
     * keeps feeding gets the refusal again, not more fields. The continuation
     * here is a perfectly well-formed {@code unsigned id 0 = 42} - the same
     * vector {@link InvalidIsTerminalTest} feeds after the other terminal
     * outcome, INVALID.
     */
    @Test
    void aFurtherFeedDoesNotResumeAfterALimitRefusal_CORELIB_JAVA_10() throws IOException {
        for (int chunk : CHUNKS) {
            Capping v = new Capping();
            IStream in = refused(v, chunk);

            UncheckedIOException again = assertThrows(UncheckedIOException.class,
                    () -> in.feed(bytes(0x00, 0x2A), v),
                    FINDING + ": a feed after a policy refusal must be refused too (§6.3), chunk="
                            + chunk);
            assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(again),
                    FINDING + ": the repeat must keep the LimitExceeded code and must not be "
                            + "reported as InvalidMessage (§6.3), chunk=" + chunk);
            assertEquals(List.of(THE_ONLY_FIELD_SENT), v.events,
                    FINDING + ": nothing may be decoded after the refusal, chunk=" + chunk);
        }
    }

    // --- (c) no field the sender never wrote reaches the visitor -------------

    /**
     * The critical half. The refusal leaves the decoder parked before the string
     * payload, so a further feed must not re-enter header parsing there: those
     * bytes are payload, and read as field headers they fabricate fields - 'h' =
     * 0x68 becomes id 13 wire type 0, and 'e' = 0x65 becomes its value 101. The
     * latch is what stops it, and this case asserts the consequence rather than
     * the mechanism: whatever the second feed does, the visitor is handed nothing
     * further. §5.2.1's outcomes describe bytes a sender wrote; a receiver may
     * never be handed a field that was never on the wire.
     */
    @Test
    void noFabricatedFieldReachesTheVisitorAfterALimitRefusal_CORELIB_JAVA_10() throws IOException {
        for (int chunk : CHUNKS) {
            Capping v = new Capping();
            IStream in = refused(v, chunk);

            try {
                in.feed(PAYLOAD, v);
            } catch (UncheckedIOException | SofabException expected) {
                // A terminal decoder refuses the continuation; that is the point
                // of case (b). What this case asserts is what reached the visitor.
            }

            assertEquals(List.of(THE_ONLY_FIELD_SENT), v.events,
                    FINDING + ": the refused payload's own bytes were delivered as fields the "
                            + "sender never wrote, chunk=" + chunk);
        }
    }

    // --- the two decode surfaces must agree ---------------------------------

    /**
     * The whole-buffer fast path and the resumable state machine must reach the
     * same verdict for the same bytes; a rule applied on one surface and not the
     * other is this decoder's recurring defect (see DecodeRuleWrittenOnceTest).
     */
    @Test
    void wholeAndChunkedFeedsAgreeAfterALimitRefusal_CORELIB_JAVA_10() throws IOException {
        Set<String> verdicts = new LinkedHashSet<>();
        for (int chunk : CHUNKS) {
            Capping v = new Capping();
            IStream in = refused(v, chunk);
            UncheckedIOException after = assertThrows(UncheckedIOException.class,
                    () -> in.feed(new byte[0], v));
            verdicts.add(chunk + " -> " + categoryOf(after));
        }
        assertEquals(1, verdicts.stream().map(s -> s.substring(s.indexOf("-> "))).distinct().count(),
                FINDING + ": the two decode surfaces disagree after the same refusal: " + verdicts);
    }
}
