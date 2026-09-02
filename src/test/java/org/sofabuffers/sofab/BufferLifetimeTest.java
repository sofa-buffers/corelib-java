/*
 * SofaBuffers Java - CORELIB_PLAN §7.2 item 4: the three cases that check where
 * memory comes from and how long it stays valid.
 *
 * Item 4 lists eight streaming cases. Five were already covered elsewhere in this
 * suite (MinOutputBufferTest, TakingFlushSinkTest, VectorConformanceTest); the
 * three here were not, and each one is the only case in the list that would
 * notice its defect (audit A2-0074):
 *
 *   * "No foreign memory, ever -- encode a `blob` several times the buffer size
 *     and assert that EVERY callback argument lies within the installed buffer."
 *     §5.1.6 forbids handing a sink a run of the caller's input directly, however
 *     divisible it is, and the MIN_OUTPUT_BUFFER cases would not see it: a sink
 *     that copies out of `data` and returns produces identical bytes either way.
 *
 *   * "Overwrite every chunk after `feed` returns ... and assert the decoded
 *     message is unchanged." This makes §6.0's chunk lifetime a checked property
 *     rather than a stated one. Nothing else in the list would notice a decoder
 *     that kept a slice into a fed chunk and read it back on the next feed --
 *     the byte-at-a-time case feeds live memory and reads the right answer.
 *
 *   * "Overwrite the one-shot buffer too -- run `decode(buffer)`, scrub the whole
 *     buffer, and assert the decoded message is unchanged." §6.7.1 gives the
 *     one-shot path no view exemption, and §7.2 says outright that a port
 *     borrowing from the buffer it was handed "passes every other item on this
 *     list".
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class BufferLifetimeTest {

    /** Fill byte a scrub leaves behind: not a byte any fixture value contains. */
    private static final byte SCRUB = (byte) 0x5A;

    // --- no foreign memory, ever (§5.1.6, §7.2 item 4) -----------------------

    /**
     * A blob many times the window, through a copying sink: every argument must be
     * the installed buffer itself, and the range must lie inside it. A decoder-side
     * pass-through would show up here as a {@code data} array that is the caller's
     * payload rather than the encoder's window.
     */
    @Test
    void everyFlushArgumentLiesInsideTheInstalledBuffer() throws IOException {
        byte[] window = new byte[16];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] flushes = {0};

        FlushSink copying = (data, off, len) -> {
            assertSame(window, data, "flush " + flushes[0] + " was handed memory that is not the "
                    + "installed buffer: pass-through is forbidden (§5.1.6)");
            assertTrue(off >= 0 && len >= 0 && off + len <= window.length,
                    "flush " + flushes[0] + " named [" + off + ", " + (off + len)
                            + ") outside the installed buffer");
            flushes[0]++;
            out.write(data, off, len);
        };

        OStream os = new OStream(window, 0, copying);
        os.writeBlob(1, payload(10_000));
        os.writeString(2, "and a string past the window as well, for the divisible-run path");
        os.flush();

        assertTrue(flushes[0] > 100, "the window is too large to prove anything: " + flushes[0]);
        assertArrayEquals(oneShot(), out.toByteArray(),
                "the streamed bytes must equal the one-shot encoding");
    }

    /**
     * The same, across a <b>taking</b> sink that installs a different buffer at
     * every flush: the argument must be the buffer that was installed <em>for that
     * flush</em>, never the previous one and never the caller's payload.
     */
    @Test
    void everyFlushArgumentIsTheBufferInstalledForIt() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[][] installed = {new byte[16]};
        OStream[] self = new OStream[1];
        List<byte[]> seen = new ArrayList<>();

        FlushSink taking = (data, off, len) -> {
            assertSame(installed[0], data,
                    "flush " + seen.size() + " was handed a buffer that is not the installed one");
            seen.add(data);
            out.write(data, off, len);
            installed[0] = new byte[16];              // take: install a replacement
            self[0].bufferSet(installed[0], 0);
        };

        OStream os = new OStream(installed[0], 0, taking);
        self[0] = os;
        os.writeBlob(1, payload(4_000));
        os.flush();

        assertTrue(seen.size() > 100, "too few flushes to prove anything: " + seen.size());
        assertEquals(seen.size(), seen.stream().distinct().count(),
                "a taken buffer was handed to a later flush");
    }

    // --- the chunk lifetime is checked, not stated (§6.0, §7.2 item 4) -------

    /**
     * Every chunk is scrubbed the instant {@code feed} returns, so a decoder that
     * kept a slice into one reads the fill pattern instead of the message.
     */
    @Test
    void scrubbingEveryChunkAfterFeedReturnsChangesNothing() throws IOException {
        byte[] message = message();
        RecordingVisitor expected = new RecordingVisitor();
        new IStream().feed(message, expected);
        assertTrue(expected.events.size() > 12,
                "the fixture decodes to " + expected.events.size() + " events; too few to prove "
                        + "anything");

        for (int chunk : new int[] {1, 3, 7, 64}) {
            RecordingVisitor got = new RecordingVisitor();
            IStream in = new IStream();
            byte[] window = new byte[chunk];
            DecodeStatus after = null;
            for (int i = 0; i < message.length; i += chunk) {
                int len = Math.min(chunk, message.length - i);
                System.arraycopy(message, i, window, 0, len);
                after = in.feed(window, 0, len, got);
                Arrays.fill(window, SCRUB);           // the caller reuses its memory
            }
            assertEquals(DecodeStatus.COMPLETE, after, "chunk size " + chunk);
            assertEquals(expected.events, got.events,
                    "a chunk scrubbed after feed returned changed the decoded message at chunk "
                            + "size " + chunk + ": the decoder kept a slice into it (§6.0)");
        }
    }

    /**
     * The one-shot path has no exemption (§6.7.1): the whole buffer is scrubbed
     * after the single {@code feed} returns, and the message must be unchanged.
     */
    @Test
    void scrubbingTheOneShotBufferAfterDecodeReturnsChangesNothing() throws IOException {
        byte[] message = message();
        RecordingVisitor expected = new RecordingVisitor();
        new IStream().feed(message, expected);

        byte[] buffer = message.clone();
        RecordingVisitor got = new RecordingVisitor();
        IStream in = new IStream();
        DecodeStatus after = in.feed(buffer, got);
        Arrays.fill(buffer, SCRUB);

        assertEquals(DecodeStatus.COMPLETE, after);
        assertEquals(expected.events, got.events,
                "scrubbing the one-shot buffer changed the decoded message: the decoder borrowed "
                        + "from the buffer it was handed (§6.7.1)");
    }

    // --- fixtures ------------------------------------------------------------

    /** A payload with no {@link #SCRUB} byte in it, so a scrub is always visible. */
    private static byte[] payload(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) ((i % 89) + 1);             // 1..89, never 0x5A
        }
        return b;
    }

    /** What {@link #everyFlushArgumentLiesInsideTheInstalledBuffer} encodes. */
    private static byte[] oneShot() throws IOException {
        byte[] buf = new byte[16_384];
        OStream os = new OStream(buf);
        os.writeBlob(1, payload(10_000));
        os.writeString(2, "and a string past the window as well, for the divisible-run path");
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /** One message per §7.1's categories, with payloads longer than any chunk here. */
    private static byte[] message() throws IOException {
        byte[] buf = new byte[4_096];
        OStream os = new OStream(buf);
        os.writeUnsigned(1, 0xDEAD_BEEF_CAFE_BABEL);
        os.writeSigned(2, -5_000_000_000_000L);
        os.writeFp32(3, 3.14159f);
        os.writeFp64(4, 2.718281828459045);
        os.writeString(5, "a string that is longer than every chunk size this test feeds");
        os.writeBlob(6, payload(700));
        os.writeArrayUnsigned(7, new int[] {1_000_000, 2_000_000, 3_000_000});
        os.writeArraySigned(8, new int[] {-100_000, -200_000});
        os.writeArrayFp64(9, new double[] {1.5, 2.5, 3.5});
        os.writeSequenceBeginLazy(10);
        os.writeString(1, "nested, and also longer than a chunk of seven bytes");
        os.writeSequenceEnd();
        return Arrays.copyOf(buf, os.bytesUsed());
    }
}
