/*
 * SofaBuffers Java - each decode rule is written once, and reads the same on
 * every surface that applies it (corelib-java#76).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

/**
 * The decoder reads the same construct from several places — a fixlen word from
 * a scalar field, from a fixlen-array element, and from the resumable machine;
 * a varint from a field header, a scalar value and an array element. Every such
 * rule must exist <em>once</em>, because the recurring defect in this decoder is
 * a guard tightened on one surface and forgotten on another (corelib-java#53 and
 * #62 were both exactly that).
 *
 * <p>Two halves, and both are needed. The structural half holds each rule that
 * <em>can</em> be shared to a single copy, so a future guard cannot be added to
 * one site only: there is no second site to forget. One rule cannot be shared for
 * free — the fp32/fp64 width rule, which needs the sub-type its caller has just
 * dispatched on — so for that one the structural half only pins its two halves
 * together, and the behavioural half does the real work: it drives the whole
 * malformed-word matrix through every reading surface and demands one identical
 * verdict, which is what a single copy is <em>for</em>, and which keeps failing
 * loudly even where the copies must stay.
 */
class DecodeRuleWrittenOnceTest {

    /** The decoder's source, read as text: the structural half counts copies in it. */
    private static final Path DECODER_SOURCE =
            Path.of("src", "main", "java", "org", "sofabuffers", "sofab", "IStream.java");

    // --- structural: one copy of each rule -----------------------------------

    /**
     * The sub-type-independent §4.6 rules — a reserved sub-type, and a length above
     * {@code SOFAB_FIXLEN_MAX} — are written once, for all three reading sites. Each
     * rejection message is unique to its rule, so counting the message literals in
     * the decoder counts the copies of the rule that raise them.
     */
    @Test
    void theSubtypeIndependentFixlenWordRulesAreWrittenOnce() throws IOException {
        String src = decoderSource();
        for (String rule : List.of(
                "\"fixlen type \"",       // reserved sub-type 4..7
                "\"fixlen length \"")) {  // length above SOFAB_FIXLEN_MAX
            assertEquals(1, count(src, rule),
                    "the fixlen-word rule raising " + rule + " must be written exactly once; "
                            + "a copy per reading surface is how a guard gets added to one and "
                            + "not the others");
        }
    }

    /**
     * The width rule — fp32 declares four bytes, fp64 eight — is the one §4.6 rule
     * that stays with the arm that selected the sub-type, because sharing it costs
     * measurable Ir/op on the decode hot path (see {@code checkFixlenWord}). It is
     * still <em>one</em> rule with two halves, so its two halves must appear the
     * same number of times: a site that grew an fp32 check without the matching
     * fp64 one (or vice versa) is exactly the drift this whole class is about, and
     * the behavioural tests below then say which surface disagrees.
     */
    @Test
    void bothHalvesOfTheFixlenWidthRuleAppearAtEverySite() throws IOException {
        String src = decoderSource();
        int fp32 = count(src, "\"fp32 length \"");
        int fp64 = count(src, "\"fp64 length \"");
        assertTrue(fp32 > 0, "the fp32 width rule vanished from the decoder");
        assertEquals(fp32, fp64,
                "fp32 and fp64 width checks must come in pairs — " + fp32 + " fp32 site(s) but "
                        + fp64 + " fp64 site(s)");
    }

    /**
     * One bounded varint reader, not one per caller. The reader used where fewer
     * than ten bytes remain has the shape {@code long f(byte[], int, int)}, and so
     * does the off-hot-path word reader; a third method of that shape is a
     * duplicate of one of them, which is what this pins.
     */
    @Test
    void oneBoundedVarintReaderServesEveryCaller() {
        Class<?>[] shape = { byte[].class, int.class, int.class };
        List<String> readers = new ArrayList<>();
        for (Method m : IStream.class.getDeclaredMethods()) {
            if (m.isSynthetic() || Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            if (m.getReturnType() == long.class && Arrays.equals(m.getParameterTypes(), shape)) {
                readers.add(m.getName());
            }
        }
        readers.sort(String::compareTo);
        assertEquals(List.of("boundedVarint", "readWord"), readers,
                "expected exactly two varint readers — the bounded tail reader shared by the "
                        + "header, scalar-value and array-element sites, and the off-hot-path "
                        + "word reader");
    }

    // --- behavioural: one verdict per word, on every reading surface ----------

    /**
     * A malformed fixlen word is malformed wherever it is read. Every rejection the
     * §4.6 ladder owns is driven through all four reading surfaces — scalar field
     * and fixlen-array element, each one-shot and byte-at-a-time — and every one
     * must answer {@code INVALID}.
     */
    @Test
    void everyMalformedFixlenWordIsRejectedOnEveryReadingSurface() {
        for (int subtype = 0x4; subtype <= 0x7; subtype++) {
            assertRejectedEverywhere(word(4, subtype), "reserved sub-type " + subtype);
        }
        for (int length : new int[] { 0, 1, 2, 3, 5, 8, 16, 4096 }) {
            assertRejectedEverywhere(word(length, 0x0), "fp32 declaring " + length + " bytes");
        }
        for (int length : new int[] { 0, 1, 4, 7, 9, 16, 4096 }) {
            assertRejectedEverywhere(word(length, 0x1), "fp64 declaring " + length + " bytes");
        }
        // Above SOFAB_FIXLEN_MAX (INT32_MAX), for every defined sub-type: the
        // ceiling is judged before the sub-type's own width rule.
        for (int subtype = 0x0; subtype <= 0x3; subtype++) {
            assertRejectedEverywhere(word(1L << 31, subtype), "over-max length, sub-type " + subtype);
            assertRejectedEverywhere(word(Long.MAX_VALUE >>> 3, subtype),
                    "huge length, sub-type " + subtype);
        }
    }

    /**
     * The other side of the same coin: a well-formed float word is accepted on
     * every reading surface, with the identical event stream. Without this, an
     * over-tightened shared rule would pass the rejection test above.
     */
    @Test
    void wellFormedFloatWordsAreAcceptedOnEveryReadingSurface() {
        // id 1, fixlen scalar: fp32 = 1.0f, fp64 = 1.0.
        assertEventsEverywhere(bytes(0x0A, 0x20, 0x00, 0x00, 0x80, 0x3F), List.of("f32:1=1.0"));
        assertEventsEverywhere(
                bytes(0x0A, 0x41, 0, 0, 0, 0, 0, 0, 0xF0, 0x3F), List.of("f64:1=1.0"));
        // id 1, fixlen array of one element, same values.
        assertEventsEverywhere(bytes(0x0D, 0x01, 0x20, 0x00, 0x00, 0x80, 0x3F),
                List.of("arr:1:FP32:1", "f32:1=1.0"));
        assertEventsEverywhere(bytes(0x0D, 0x01, 0x41, 0, 0, 0, 0, 0, 0, 0xF0, 0x3F),
                List.of("arr:1:FP64:1", "f64:1=1.0"));
    }

    /**
     * The bounded reader is entered wherever fewer than ten bytes remain, which is
     * every varint of a short message and the last elements of any array. The same
     * values are therefore read through it as a field header, as a scalar value and
     * as an array element, and must decode identically — including from every
     * possible split of the message, which routes the straddling one through the
     * byte-at-a-time machine instead.
     */
    @Test
    void boundedVarintsDecodeIdenticallyOnEveryReadingSurface() {
        long[] values = {
            0L, 1L, 0x7FL, 0x80L, 300L, 0x3FFFL, 0x4000L,
            1L << 20, 1L << 35, 1L << 55, 1L << 62, Long.MAX_VALUE,
        };

        // Scalar field: the whole message is under ten bytes for the small values
        // and exactly ten for the widest, so header and value both take the bounded
        // reader.
        for (long v : values) {
            byte[] msg = concat(bytes(0x08), varint(v));
            assertEventsEverywhere(msg, List.of("u:1=" + Long.toUnsignedString(v)));
        }

        // Array elements: the fast loop hands over as soon as fewer than ten bytes
        // remain, so the tail of every array is read by the same method.
        List<String> expected = new ArrayList<>();
        expected.add("arr:1:UNSIGNED:" + values.length);
        byte[] msg = concat(bytes(0x0B), varint(values.length));
        for (long v : values) {
            msg = concat(msg, varint(v));
            expected.add("u:1=" + Long.toUnsignedString(v));
        }
        assertEventsEverywhere(msg, expected);
    }

    // --- helpers -------------------------------------------------------------

    private static String decoderSource() throws IOException {
        assertTrue(Files.isRegularFile(DECODER_SOURCE),
                "decoder source not found at " + DECODER_SOURCE.toAbsolutePath());
        return Files.readString(DECODER_SOURCE, StandardCharsets.UTF_8);
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }

    /** A fixlen word: {@code (length << 3) | subtype} (§4.6). */
    private static long word(long length, int subtype) {
        return (length << 3) | subtype;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static byte[] varint(long value) {
        byte[] out = new byte[10];
        int n = 0;
        long v = value;
        while (true) {
            int b = (int) (v & 0x7F);
            v >>>= 7;
            if (v != 0) {
                out[n++] = (byte) (b | 0x80);
            } else {
                out[n++] = (byte) b;
                break;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /**
     * Decode {@code data} and report the outcome as one word: {@code INVALID} for a
     * rejection, otherwise the {@link DecodeStatus}. {@code split} is where the
     * bytes are cut into two feeds ({@code 0} feeds them whole, a negative value
     * feeds them one byte at a time, driving the resumable machine over all of
     * them). Events are appended to {@code events}.
     */
    private static String outcome(byte[] data, int split, List<String> events) {
        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        try {
            if (split < 0) {
                for (byte b : data) {
                    in.feed(new byte[] { b }, v);
                }
            } else if (split == 0) {
                in.feed(data, v);
            } else {
                in.feed(data, 0, split, v);
                in.feed(data, split, data.length - split, v);
            }
        } catch (SofabException e) {
            events.addAll(v.events);
            return e.error() == SofabError.INVALID_MSG ? "INVALID" : e.error().name();
        }
        events.addAll(v.events);
        return in.status().name();
    }

    /** Every split of {@code data}: whole, one byte at a time, and each cut point. */
    private static int[] splitsOf(byte[] data) {
        int[] splits = new int[data.length + 1];
        splits[0] = 0;
        splits[1] = -1;
        for (int i = 1; i < data.length; i++) {
            splits[i + 1] = i;
        }
        return splits;
    }

    private static void assertRejectedEverywhere(long fixlenWord, String what) {
        byte[] w = varint(fixlenWord);
        byte[] scalar = concat(bytes(0x0A), w);          // id 1, fixlen scalar
        byte[] element = concat(bytes(0x0D, 0x01), w);   // id 1, fixlen array of 1
        for (byte[] msg : new byte[][] { scalar, element }) {
            for (int split : splitsOf(msg)) {
                assertEquals("INVALID", outcome(msg, split, new ArrayList<>()),
                        what + " (split " + split + ", " + msg.length + " bytes)");
            }
        }
    }

    private static void assertEventsEverywhere(byte[] msg, List<String> expected) {
        for (int split : splitsOf(msg)) {
            List<String> events = new ArrayList<>();
            String status = outcome(msg, split, events);
            assertEquals(DecodeStatus.COMPLETE.name(), status, "split " + split);
            assertEquals(expected, events, "split " + split);
        }
    }
}
