/*
 * SofaBuffers Java - CORELIB_PLAN §7.2 items 5b and 6, at the two varint
 * positions nothing else in this suite reaches.
 *
 * Item 5b: "a non-minimal varint (§4.1.2) at a field header, a `fixlen_word`, and
 * an element count" MUST decode to the value it denotes and re-encode
 * canonically, never INVALID. The *field header* position was already covered --
 * SequenceEndIdTest decodes `87 00` at every chunking and asserts it re-encodes
 * as `07` -- but the `fixlen_word` and the element count were not, and they are
 * read by different code: VarintWidthTest only ever generates minimal encodings
 * (its `valuesOfWidth` starts each width at `1 << 7*(width-1)`), so no vector in
 * this repository spelled either word the long way (audit A2-0075).
 *
 * Item 6: a `fixlen_word` cut after its first byte with that byte carrying a
 * RESERVED subtype MUST be INCOMPLETE. The subtype is already settled by the low
 * three bits, so an implementation that evaluates it before the varint ends
 * answers INVALID where §4.1.1 requires INCOMPLETE -- and, §7.2 says, "nothing
 * else in this list exercises the no-partial-evaluation rule". The rows already
 * here carry the COMPLETE reserved word (`02 84 01`), which is the case that must
 * be INVALID, and truncate only AFTER a complete one-byte word.
 *
 * Both are decoded through the whole-buffer fast path and the resumable machine,
 * since a rule enforced on one surface but not the other is this decoder's
 * recurring defect (see common/Decode).
 *
 * Wire shorthand: a header is `(id << 3) | wire_type` (§4.3) -- `02`/`1A`/`22` are
 * T_FIXLEN at ids 0/3/4, `0B` is ARRAY_UNSIGNED at id 1, `15` is ARRAY_FIXLEN at
 * id 2. A `fixlen_word` is `(length << 3) | subtype` with 0 = fp32, 1 = fp64,
 * 2 = string, 3 = blob (§4.6). A varint byte carries seven payload bits and the
 * high bit means "continues", so `41` and `C1 00` denote the same word.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Decode.verdict;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class NonMinimalVarintTest {

    /** Little-endian bytes of {@code E}, the fp64 payload used throughout. */
    private static final byte[] E_LE = fp64Bytes(2.718281828459045);

    // --- 5b: a non-minimal fixlen_word --------------------------------------

    /** fp64 at id 4, its length word spelled in two bytes where one would do. */
    @Test
    void aNonMinimalFixlenWordOnAScalarDecodesAndReencodesCanonically() throws IOException {
        byte[] canonical = concat(bytes(0x22, 0x41), E_LE);
        byte[] nonMinimal = concat(bytes(0x22, 0xC1, 0x00), E_LE);
        byte[] threeBytes = concat(bytes(0x22, 0xC1, 0x80, 0x00), E_LE);

        for (byte[] input : List.of(nonMinimal, threeBytes)) {
            assertDecodesLike(canonical, input);
            assertArrayEquals(canonical, reencode(input),
                    "a non-minimal fixlen_word must re-encode canonically (§4.1.2)");
        }
    }

    /** A string's length word, the other subtype a scalar fixlen_word can carry. */
    @Test
    void aNonMinimalFixlenWordOnAStringDecodesAndReencodesCanonically() throws IOException {
        byte[] text = "abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] canonical = concat(bytes(0x1A, 0x32), text);
        byte[] nonMinimal = concat(bytes(0x1A, 0xB2, 0x00), text);

        assertDecodesLike(canonical, nonMinimal);
        assertArrayEquals(canonical, reencode(nonMinimal));
    }

    /** The per-element word of a fixlen ARRAY: read by the array path, not the scalar one. */
    @Test
    void aNonMinimalFixlenWordOnAnArrayDecodesAndReencodesCanonically() throws IOException {
        byte[] payload = concat(fp32Bytes(1.5f), fp32Bytes(-2.5f));
        byte[] canonical = concat(bytes(0x15, 0x02, 0x20), payload);
        byte[] nonMinimal = concat(bytes(0x15, 0x02, 0xA0, 0x00), payload);

        assertDecodesLike(canonical, nonMinimal);
        assertArrayEquals(canonical, reencode(nonMinimal));
    }

    // --- 5b: a non-minimal element count ------------------------------------

    /** An integer array's count word, spelled long. */
    @Test
    void aNonMinimalElementCountDecodesAndReencodesCanonically() throws IOException {
        byte[] canonical = bytes(0x0B, 0x03, 0x01, 0x02, 0x03);
        byte[] nonMinimal = bytes(0x0B, 0x83, 0x00, 0x01, 0x02, 0x03);
        byte[] threeBytes = bytes(0x0B, 0x83, 0x80, 0x00, 0x01, 0x02, 0x03);

        for (byte[] input : List.of(nonMinimal, threeBytes)) {
            assertDecodesLike(canonical, input);
            assertArrayEquals(canonical, reencode(input),
                    "a non-minimal element count must re-encode canonically (§4.1.2)");
        }
    }

    /** A count of zero written the long way — the empty array is still an array. */
    @Test
    void aNonMinimalZeroElementCountDecodesAndReencodesCanonically() throws IOException {
        assertDecodesLike(bytes(0x0B, 0x00), bytes(0x0B, 0x80, 0x00));
        assertArrayEquals(bytes(0x0B, 0x00), reencode(bytes(0x0B, 0x80, 0x00)));
    }

    /** A fixlen array's count word, which §4.8.1 has the decoder read before the subtype. */
    @Test
    void aNonMinimalFixlenArrayCountDecodesAndReencodesCanonically() throws IOException {
        byte[] payload = concat(fp32Bytes(1.5f), fp32Bytes(-2.5f));
        byte[] canonical = concat(bytes(0x15, 0x02, 0x20), payload);
        byte[] nonMinimal = concat(bytes(0x15, 0x82, 0x00, 0x20), payload);

        assertDecodesLike(canonical, nonMinimal);
        assertArrayEquals(canonical, reencode(nonMinimal));
    }

    // --- 6: no partial evaluation of a truncated fixlen_word -----------------

    /**
     * `02 84`: the low three bits of `84` already say subtype 4, which is reserved
     * and INVALID once the word is complete — but the word is NOT complete, so
     * §4.1.1 requires INCOMPLETE. A decoder peeking at the settled sub-field before
     * the varint's final byte answers INVALID here.
     */
    @Test
    void aReservedSubtypeInATruncatedFixlenWordIsIncomplete() {
        for (int chunk : CHUNKS) {
            assertEquals("I", verdict(bytes(0x02, 0x84), sink(), chunk),
                    "a fixlen_word cut after a first byte carrying reserved subtype 4 must be "
                            + "INCOMPLETE, not INVALID (§4.1.1), at chunk size " + chunk);
        }
    }

    /** The other three reserved subtypes, and the fixlen-array word as well. */
    @Test
    void everyReservedSubtypeInATruncatedWordIsIncomplete() {
        for (int subtype = 0x4; subtype <= 0x7; subtype++) {
            byte[] scalar = bytes(0x02, 0x80 | subtype);
            byte[] array = bytes(0x05, 0x01, 0x80 | subtype);
            for (int chunk : CHUNKS) {
                assertEquals("I", verdict(scalar, sink(), chunk),
                        "scalar fixlen_word, reserved subtype " + subtype + ", chunk " + chunk);
                assertEquals("I", verdict(array, sink(), chunk),
                        "array fixlen_word, reserved subtype " + subtype + ", chunk " + chunk);
            }
        }
    }

    /**
     * The pair that makes the case above mean something: the same bytes with the
     * word finished are INVALID, so the verdict is deferred, not dropped.
     */
    @Test
    void theSameWordCompletedIsInvalid() {
        for (int chunk : CHUNKS) {
            assertEquals("R:INVALID_MSG", verdict(bytes(0x02, 0x84, 0x01), sink(), chunk),
                    "a completed reserved fixlen_word is INVALID at chunk size " + chunk);
            assertEquals("R:INVALID_MSG", verdict(bytes(0x05, 0x01, 0x84, 0x01), sink(), chunk),
                    "a completed reserved array fixlen_word is INVALID at chunk size " + chunk);
        }
    }

    /**
     * The continuation is what settles it: feeding the missing byte to the very
     * decoder that answered INCOMPLETE turns it into the rejection.
     */
    @Test
    void feedingTheMissingByteThenRejects() throws IOException {
        IStream in = new IStream();
        Visitor drop = new Visitor() { };
        assertEquals(DecodeStatus.INCOMPLETE, in.feed(bytes(0x02, 0x84), drop));

        SofabException e = org.junit.jupiter.api.Assertions.assertThrows(SofabException.class,
                () -> in.feed(bytes(0x01), drop));
        assertEquals(SofabError.INVALID_MSG, e.error());
    }

    // --- harness -------------------------------------------------------------

    private static Visitor sink() {
        return new Visitor() { };
    }

    /** Both inputs must decode to the same events, on both decode surfaces. */
    private static void assertDecodesLike(byte[] canonical, byte[] input) throws IOException {
        List<String> want = events(canonical, 0);
        org.junit.jupiter.api.Assertions.assertFalse(want.isEmpty(),
                "the canonical fixture decoded to nothing");
        for (int chunk : CHUNKS) {
            assertEquals(want, events(input, chunk),
                    "the non-minimal spelling decoded differently at chunk size " + chunk);
            assertEquals("A", verdict(input, sink(), chunk),
                    "a non-minimal but well-formed varint must not be INVALID (§4.1.2), at chunk "
                            + "size " + chunk);
        }
    }

    private static List<String> events(byte[] data, int chunk) throws IOException {
        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        DecodeStatus after = null;
        if (chunk <= 0) {
            after = in.feed(data, v);
        } else {
            for (int i = 0; i < data.length; i += chunk) {
                after = in.feed(data, i, Math.min(chunk, data.length - i), v);
            }
        }
        assertEquals(DecodeStatus.COMPLETE, after);
        return v.events;
    }

    /** Decode and immediately re-encode: the output is this encoder's canonical form. */
    private static byte[] reencode(byte[] data) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        new IStream().feed(data, new Reencoder(os));
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /**
     * Writes back out what it decodes. Fixlen payloads and array elements are
     * gathered into a destination sized from the announced total — the generated
     * layer's job (§6.6.1), which is exactly what a visitor is.
     */
    private static final class Reencoder implements Visitor {
        private final OStream os;
        private FixlenType kind;
        private byte[] payload;
        private int at;
        private ArrayKind arrayKind;
        private int arrayId;
        private long[] ints;
        private double[] doubles;
        private int n;

        Reencoder(OStream os) {
            this.os = os;
        }

        @Override
        public void fixlenBegin(int id, FixlenType subtype, int total) {
            if (subtype == FixlenType.STRING || subtype == FixlenType.BLOB) {
                kind = subtype;
                payload = new byte[total];
                at = 0;
            }
        }

        @Override
        public void string(int id, int total, int offset, byte[] data, int off, int len) {
            gather(id, total, data, off, len);
        }

        @Override
        public void blob(int id, int total, int offset, byte[] data, int off, int len) {
            gather(id, total, data, off, len);
        }

        private void gather(int id, int total, byte[] data, int off, int len) {
            System.arraycopy(data, off, payload, at, len);
            at += len;
            if (at == total) {
                write(() -> os.writeFixlen(id, payload, 0, total, kind));
            }
        }

        @Override
        public void arrayBegin(int id, ArrayKind kind2, int count) {
            arrayKind = kind2;
            arrayId = id;
            n = 0;
            ints = kind2 == ArrayKind.UNSIGNED || kind2 == ArrayKind.SIGNED ? new long[count] : null;
            doubles = ints == null ? new double[count] : null;
            if (count == 0) {
                flushArray();
            }
        }

        @Override
        public void unsigned(int id, long value) {
            if (ints != null) {
                ints[n++] = value;
                if (n == ints.length) {
                    flushArray();
                }
            } else {
                write(() -> os.writeUnsigned(id, value));
            }
        }

        @Override
        public void signed(int id, long value) {
            if (ints != null) {
                ints[n++] = value;
                if (n == ints.length) {
                    flushArray();
                }
            } else {
                write(() -> os.writeSigned(id, value));
            }
        }

        @Override
        public void fp32(int id, float value) {
            if (doubles != null) {
                doubles[n++] = value;
                if (n == doubles.length) {
                    flushArray();
                }
            } else {
                write(() -> os.writeFp32(id, value));
            }
        }

        @Override
        public void fp64(int id, double value) {
            if (doubles != null) {
                doubles[n++] = value;
                if (n == doubles.length) {
                    flushArray();
                }
            } else {
                write(() -> os.writeFp64(id, value));
            }
        }

        private void flushArray() {
            ArrayKind k = arrayKind;
            int id = arrayId;
            if (k == ArrayKind.UNSIGNED) {
                long[] v = ints;
                write(() -> os.writeArrayUnsigned(id, v));
            } else if (k == ArrayKind.SIGNED) {
                long[] v = ints;
                write(() -> os.writeArraySigned(id, v));
            } else if (k == ArrayKind.FP32) {
                float[] v = new float[doubles.length];
                for (int i = 0; i < v.length; i++) {
                    v[i] = (float) doubles[i];
                }
                write(() -> os.writeArrayFp32(id, v));
            } else {
                double[] v = doubles;
                write(() -> os.writeArrayFp64(id, v));
            }
            ints = null;
            doubles = null;
        }

        private interface Emit {
            void run() throws IOException;
        }

        private static void write(Emit e) {
            try {
                e.run();
            } catch (IOException io) {
                throw new AssertionError(io);
            }
        }
    }

    // --- literals ------------------------------------------------------------

    private static byte[] fp64Bytes(double v) {
        long bits = Double.doubleToRawLongBits(v);
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (bits >>> (8 * i));
        }
        return out;
    }

    private static byte[] fp32Bytes(float v) {
        int bits = Float.floatToRawIntBits(v);
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            out[i] = (byte) (bits >>> (8 * i));
        }
        return out;
    }
}
