/*
 * SofaBuffers Java - encoder tests (byte-exact vs. the C reference vectors).
 *
 * The expected byte arrays are copied verbatim from the C corelib reference
 * suite (test/c/test_ostream.c) to guarantee byte-for-byte interoperability.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class OStreamTest {

    /** Encode via {@code body} into a fresh buffer and return exactly the used bytes. */
    private static byte[] encode(EncodeBody body) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        body.run(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    @FunctionalInterface
    private interface EncodeBody {
        void run(OStream os) throws IOException;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void unsignedIdMin() throws IOException {
        assertArrayEquals(bytes(0x00, 0x00), encode(os -> os.writeUnsigned(0, 0)));
    }

    @Test
    void unsignedIdMax() throws IOException {
        assertArrayEquals(
                bytes(0xF8, 0xFF, 0xFF, 0xFF, 0x3F, 0x00),
                encode(os -> os.writeUnsigned(Integer.MAX_VALUE, 0)));
    }

    @Test
    void unsignedMax() throws IOException {
        // UINT64_MAX -> ten 0xFF payload bytes then 0x01.
        assertArrayEquals(
                bytes(0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeUnsigned(0, -1L)));
    }

    @Test
    void signedMin() throws IOException {
        assertArrayEquals(
                bytes(0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeSigned(0, Long.MIN_VALUE)));
    }

    @Test
    void signedMax() throws IOException {
        assertArrayEquals(
                bytes(0x01, 0xFE, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0x01),
                encode(os -> os.writeSigned(0, Long.MAX_VALUE)));
    }

    @Test
    void booleanTrue() throws IOException {
        assertArrayEquals(bytes(0x00, 0x01), encode(os -> os.writeBoolean(0, true)));
    }

    @Test
    void fp32() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x20, 0x56, 0x0E, 0x49, 0x40),
                encode(os -> os.writeFp32(0, 3.1415f)));
    }

    @Test
    void fp64() throws IOException {
        // The C reference widens a float literal: (double) 3.14159265f.
        assertArrayEquals(
                bytes(0x02, 0x41, 0x00, 0x00, 0x00, 0x60, 0xFB, 0x21, 0x09, 0x40),
                encode(os -> os.writeFp64(0, (double) 3.14159265f)));
    }

    @Test
    void string() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x62, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x43, 0x6F, 0x75, 0x63, 0x68, 0x21),
                encode(os -> os.writeString(0, "Hello Couch!")));
    }

    @Test
    void stringEmpty() throws IOException {
        assertArrayEquals(bytes(0x02, 0x02), encode(os -> os.writeString(0, "")));
    }

    @Test
    void blob() throws IOException {
        assertArrayEquals(
                bytes(0x02, 0x2B, 0x01, 0x02, 0x03, 0x04, 0x05),
                encode(os -> os.writeBlob(0, bytes(0x01, 0x02, 0x03, 0x04, 0x05))));
    }

    @Test
    void blobEmpty() throws IOException {
        assertArrayEquals(bytes(0x02, 0x03), encode(os -> os.writeBlob(0, new byte[0])));
    }

    @Test
    void arrayUnsigned32() throws IOException {
        int[] a = {1, 2, 3, 0x80000000, 0xFFFFFFFF};
        assertArrayEquals(
                bytes(0x03, 0x05, 0x01, 0x02, 0x03, 0x80, 0x80, 0x80, 0x80, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F),
                encode(os -> os.writeArrayUnsigned(0, a)));
    }

    @Test
    void arrayUnsigned16() throws IOException {
        short[] a = {1, 2, 3, 0, (short) 0xFFFF};
        assertArrayEquals(
                bytes(0x03, 0x05, 0x01, 0x02, 0x03, 0x00, 0xFF, 0xFF, 0x03),
                encode(os -> os.writeArrayUnsigned(0, a)));
    }

    @Test
    void arraySigned32() throws IOException {
        int[] a = {-1, -2, -3, Integer.MIN_VALUE, Integer.MAX_VALUE};
        assertArrayEquals(
                bytes(0x04, 0x05, 0x01, 0x03, 0x05, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F, 0xFE, 0xFF, 0xFF, 0xFF, 0x0F),
                encode(os -> os.writeArraySigned(0, a)));
    }

    @Test
    void arrayFp32() throws IOException {
        float[] a = {1.0f, 2.0f, 3.0f, -Float.MAX_VALUE, Float.MAX_VALUE};
        assertArrayEquals(
                bytes(0x05, 0x05, 0x20, 0x00, 0x00, 0x80, 0x3F, 0x00, 0x00, 0x00, 0x40, 0x00,
                        0x00, 0x40, 0x40, 0xFF, 0xFF, 0x7F, 0xFF, 0xFF, 0xFF, 0x7F, 0x7F),
                encode(os -> os.writeArrayFp32(0, a)));
    }

    @Test
    void arrayFp64() throws IOException {
        double[] a = {1.0, 2.0, 3.0, -Double.MAX_VALUE, Double.MAX_VALUE};
        assertArrayEquals(
                bytes(0x05, 0x05, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F, 0x00,
                        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,
                        0x00, 0x08, 0x40, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0xFF, 0xFF,
                        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0x7F),
                encode(os -> os.writeArrayFp64(0, a)));
    }

    @Test
    void nestedSequence() throws IOException {
        assertArrayEquals(
                bytes(0x00, 0x2A, 0x0E, 0x00, 0x2A, 0x11, 0x53, 0x07, 0x11, 0x53),
                encode(os -> {
                    os.writeUnsigned(0, 42);
                    os.writeSequenceBeginLazy(1);
                    os.writeUnsigned(0, 42);
                    os.writeSigned(2, -42);
                    os.writeSequenceEnd();
                    os.writeSigned(2, -42);
                }));
    }

    @Test
    void nestedSequenceWithArray() throws IOException {
        assertArrayEquals(
                bytes(0x00, 0x2A, 0x1E, 0x00, 0x2A, 0x1C, 0x03, 0x53, 0x55, 0x57, 0x07, 0x11, 0x53),
                encode(os -> {
                    os.writeUnsigned(0, 42);
                    os.writeSequenceBeginLazy(3);
                    os.writeUnsigned(0, 42);
                    os.writeArraySigned(3, new int[] {-42, -43, -44});
                    os.writeSequenceEnd();
                    os.writeSigned(2, -42);
                }));
    }

    // --- lazy sequence framing (MESSAGE_SPEC §2) ----------------------------

    /**
     * An all-default sequence carries no information, so the field is omitted --
     * where the eager API would have written the two-byte empty frame {@code 0E 07}.
     */
    @Test
    void lazySequenceWithoutContentEmitsNothing() throws IOException {
        assertArrayEquals(bytes(), encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeSequenceEnd();
        }));
    }

    /**
     * {@code writeSequenceEndKeep} forces a contentless frame onto the wire -- the
     * array-element and explicit-empty cases of §2 / §5.1.
     */
    @Test
    void endKeepFramesAContentlessSequence() throws IOException {
        assertArrayEquals(bytes(0x0E, 0x07), encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeSequenceEndKeep();
        }));
    }

    /**
     * Forcing a frame forces its ancestors too: the outer sequence got content (the
     * inner frame), so it is framed as well.
     */
    @Test
    void endKeepCommitsTheEnclosingRun() throws IOException {
        assertArrayEquals(bytes(0x0E, 0x16, 0x07, 0x07), encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeSequenceBeginLazy(2);
            os.writeSequenceEndKeep();
            os.writeSequenceEnd();
        }));
    }

    /** With content it makes no difference -- the headers are already out. */
    @Test
    void endKeepMatchesEndOnceContentExists() throws IOException {
        byte[] withKeep = encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeUnsigned(0, 42);
            os.writeSequenceEndKeep();
        });
        byte[] withEnd = encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeUnsigned(0, 42);
            os.writeSequenceEnd();
        });
        assertArrayEquals(bytes(0x0E, 0x00, 0x2A, 0x07), withKeep);
        assertArrayEquals(withKeep, withEnd);
    }

    /**
     * One child field commits the whole held-back run, outermost header first, so a
     * non-default leaf deep inside brings every enclosing frame back in wire order.
     */
    @Test
    void lazySequenceCommitsTheWholeRunOnFirstContent() throws IOException {
        assertArrayEquals(bytes(0x0E, 0x16, 0x00, 0x2A, 0x07, 0x07), encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeSequenceBeginLazy(2);
            os.writeUnsigned(0, 42);
            os.writeSequenceEnd();
            os.writeSequenceEnd();
        }));
    }

    /**
     * Only the empty inner sequence drops; the outer one has content (the leaf) and
     * is framed. This is the interleaving a naive "drop the whole run" would get
     * wrong.
     */
    @Test
    void lazySequenceDropsOnlyTheEmptyInnerOne() throws IOException {
        assertArrayEquals(bytes(0x0E, 0x00, 0x2A, 0x07), encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeSequenceBeginLazy(2);
            os.writeSequenceEnd();
            os.writeUnsigned(0, 42);
            os.writeSequenceEnd();
        }));
    }

    /**
     * A lazily framed sequence <em>after</em> content in the same scope, and the
     * sibling order, stay intact.
     */
    @Test
    void lazySequenceAfterContentIsIndependent() throws IOException {
        assertArrayEquals(bytes(0x00, 0x01, 0x10, 0x03), encode(os -> {
            os.writeUnsigned(0, 1);
            os.writeSequenceBeginLazy(1);
            os.writeSequenceEnd();
            os.writeUnsigned(2, 3);
        }));
    }

    /**
     * Held-back headers are encoder state, not buffer content, so a flush can never
     * split a pending run: a 3-byte output buffer sees exactly the one-shot bytes.
     */
    @Test
    void lazyFramingIsBufferSizeIndependent() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        OStream os = new OStream(new byte[3], 0, out::write);
        os.writeSequenceBeginLazy(1);
        os.writeSequenceBeginLazy(2);
        os.writeSequenceEnd();
        os.writeUnsigned(0, 42);
        os.writeSequenceEnd();
        os.flush();
        assertArrayEquals(bytes(0x0E, 0x00, 0x2A, 0x07), out.toByteArray());
    }

    /**
     * Every writer commits the pending run before its first byte -- the audit of
     * MESSAGE_SPEC §2's choke-point requirement. Each case opens one lazy sequence,
     * writes exactly one field through a different writer, and must produce the
     * sequence header {@code 0x0E} first.
     */
    @Test
    void everyWriterCommitsThePendingRun() throws IOException {
        List<EncodeBody> writers = List.of(
                os -> os.writeUnsigned(0, 1),
                os -> os.writeSigned(0, -1),
                os -> os.writeBoolean(0, true),
                os -> os.writeFixlen(0, new byte[] {1}, 0, 1, FixlenType.BLOB),
                os -> os.writeFp32(0, 1.5f),
                os -> os.writeFp64(0, 1.5),
                os -> os.writeString(0, "x"),
                os -> os.writeBlob(0, new byte[] {1}),
                os -> os.writeBlob(0, new byte[] {1, 2}, 0, 1),
                os -> os.writeArrayUnsigned(0, new byte[] {1}),
                os -> os.writeArrayUnsigned(0, new short[] {1}),
                os -> os.writeArrayUnsigned(0, new int[] {1}),
                os -> os.writeArrayUnsigned(0, new long[] {1}),
                os -> os.writeArraySigned(0, new byte[] {1}),
                os -> os.writeArraySigned(0, new short[] {1}),
                os -> os.writeArraySigned(0, new int[] {1}),
                os -> os.writeArraySigned(0, new long[] {1}),
                os -> os.writeArrayFp32(0, new float[] {1.5f}),
                os -> os.writeArrayFp64(0, new double[] {1.5}));
        for (EncodeBody w : writers) {
            byte[] got = encode(os -> {
                os.writeSequenceBeginLazy(1);
                w.run(os);
                os.writeSequenceEnd();
            });
            assertEquals(0x0E, got[0] & 0xFF, "writer did not commit the pending run");
            assertEquals(0x07, got[got.length - 1] & 0xFF, "sequence not closed");
        }
    }

    /**
     * The bulk array path (>= BULK_MIN elements, whole worst case fits the buffer)
     * is a separate branch inside the array writers; it must commit the run too.
     */
    @Test
    void bulkArrayPathCommitsThePendingRun() throws IOException {
        long[] many = new long[32];
        Arrays.fill(many, 7L);
        byte[] got = encode(os -> {
            os.writeSequenceBeginLazy(1);
            os.writeArrayUnsigned(0, many);
            os.writeSequenceEnd();
        });
        assertEquals(0x0E, got[0] & 0xFF);
        assertEquals(0x03, got[1] & 0xFF);
    }

    /**
     * A string that is not valid UTF-8 is rejected before anything is written, so
     * the pending run stays held back and the sequence still vanishes.
     */
    @Test
    void rejectedWriteDoesNotCommitThePendingRun() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeSequenceBeginLazy(1);
        SofabException ex = assertThrows(SofabException.class,
                () -> os.writeString(0, "\uD800"));
        assertEquals(SofabError.ARGUMENT, ex.error());
        os.writeSequenceEnd();
        assertEquals(0, os.bytesUsed());
    }

    // --- error / argument handling -----------------------------------------

    @Test
    void idOverflowRejected() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[16]).writeUnsigned(-1, 0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void bufferFullWithoutSink() {
        SofabException ex = assertThrows(SofabException.class,
                () -> new OStream(new byte[2]).writeUnsigned(0, -1L));
        assertEquals(SofabError.BUFFER_FULL, ex.error());
    }

    @Test
    void maxDepthNestingAccepted() throws IOException {
        // Opening (and closing) MAX_DEPTH = 255 nested sequences is the deepest legal
        // nesting. None of them gets content, so the frames reach the wire only
        // because they are closed with the frame-keeping form.
        byte[] buf = new byte[2 * Sofab.MAX_DEPTH];
        OStream os = new OStream(buf);
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBeginLazy(0);
        }
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceEndKeep();
        }
        assertEquals(2 * Sofab.MAX_DEPTH, os.bytesUsed());
        byte[] expect = new byte[2 * Sofab.MAX_DEPTH];
        Arrays.fill(expect, 0, Sofab.MAX_DEPTH, (byte) 0x06);
        Arrays.fill(expect, Sofab.MAX_DEPTH, 2 * Sofab.MAX_DEPTH, (byte) 0x07);
        assertArrayEquals(expect, buf);
    }

    @Test
    void maxDepthNestingContentlessDropsDownToTheEagerFallback() throws IOException {
        // The same nesting closed with the dropping form. Every frame is contentless,
        // so only those already framed eagerly past the LAZY_SEQ_DEPTH hold-back
        // window survive; the run is a contiguous suffix of the open sequences, so
        // each end pops exactly its own frame and none of the eager ones is left
        // without its 0x07. An empty frame is still readable and decodes to the same
        // value (MESSAGE_SPEC §2), which is why the fallback stays conformant.
        byte[] buf = new byte[4 * Sofab.MAX_DEPTH];
        OStream os = new OStream(buf);
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBeginLazy(0);
        }
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceEnd();
        }
        // Every surviving frame is balanced: as many 0x06 headers as 0x07 markers.
        int used = os.bytesUsed();
        int begins = 0;
        int ends = 0;
        for (int i = 0; i < used; i++) {
            if ((buf[i] & 0xFF) == 0x06) {
                begins++;
            } else if ((buf[i] & 0xFF) == 0x07) {
                ends++;
            }
        }
        assertEquals(begins, ends);
        assertEquals(2 * begins, used);
        // Depth unwound to zero, so a full MAX_DEPTH nesting is available again.
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBeginLazy(0);
        }
        SofabException ex = assertThrows(SofabException.class, () -> os.writeSequenceBeginLazy(0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void nestingBeyondMaxDepthRejected() throws IOException {
        OStream os = new OStream(new byte[2 * Sofab.MAX_DEPTH + 8]);
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            os.writeSequenceBeginLazy(0);
        }
        SofabException ex = assertThrows(SofabException.class, () -> os.writeSequenceBeginLazy(0));
        assertEquals(SofabError.ARGUMENT, ex.error());
    }

    @Test
    void unbalancedSequenceEndIsWrittenNotRejected() throws Exception {
        // The encoder writes what it is told; an end with no matching begin makes
        // the *bytes* malformed, which is the decoder's verdict, not the
        // encoder's. Every other port behaves this way.
        byte[] buf = new byte[16];
        OStream os = new OStream(buf);
        os.writeSequenceEnd();
        os.flush();
        assertEquals(0x07, buf[0] & 0xFF);
    }
}
