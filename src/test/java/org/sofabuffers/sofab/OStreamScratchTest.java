/*
 * SofaBuffers Java - support layer: the per-thread encode scratch buffer.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

/**
 * {@link OStream#overScratch} is the mechanism a one-shot {@code encode()} rests
 * on: a buffer big enough for the worst case, allocated (and zeroed) once per
 * thread instead of once per call. CORELIB_PLAN §5.1 keeps the <em>size</em> with
 * the caller, who knows the message; what is checked here is everything else — that
 * the buffer really is reused, that it grows and never shrinks, that it is confined
 * to its thread, and that {@link OStream#copyOfBytesUsed()} is the only way bytes
 * leave it.
 */
class OStreamScratchTest {

    /** Encoding through the scratch buffer produces the caller-buffer bytes exactly. */
    @Test
    void theScratchPathEncodesWhatTheCallerBufferPathDoes() throws IOException {
        byte[] own = new byte[64];
        OStream a = new OStream(own);
        a.writeUnsigned(0, 42);
        a.writeString(1, "hi");
        byte[] expected = Arrays.copyOf(own, a.bytesUsed());

        OStream b = OStream.overScratch(64);
        b.writeUnsigned(0, 42);
        b.writeString(1, "hi");
        assertArrayEquals(expected, b.copyOfBytesUsed());
    }

    /**
     * The buffer is reused, which is the whole point and also the hazard: bytes in
     * it are valid only until the same thread asks again. Two streams over it see
     * one another's writes.
     */
    @Test
    void oneBufferIsSharedByEveryStreamOnTheThread() throws IOException {
        OStream first = OStream.overScratch(64);
        first.writeUnsigned(0, 1);

        OStream second = OStream.overScratch(64);
        second.writeUnsigned(0, 2);

        assertArrayEquals(bytes(0x00, 0x02), second.copyOfBytesUsed());
        assertArrayEquals(bytes(0x00, 0x02), first.copyOfBytesUsed(),
                "the first stream's bytes were overwritten in place");
    }

    /**
     * It grows to the largest size the thread has asked for and never shrinks back,
     * so encoding a big message and then a small one leaves the big one's room
     * available.
     */
    @Test
    void theBufferGrowsAndNeverShrinks() throws IOException {
        OStream.overScratch(4096);

        OStream small = OStream.overScratch(4);
        for (int i = 0; i < 300; i++) {
            small.writeUnsigned(0, 0);           // 600 bytes, far past the 4 asked for
        }
        assertEquals(600, small.bytesUsed());
    }

    /** A message past the buffer is BUFFER_FULL: there is no sink to flush to. */
    @Test
    void aMessagePastTheBufferIsBufferFull() {
        OStream os = OStream.overScratch(2);
        SofabException e = assertThrows(SofabException.class, () -> {
            for (int i = 0; i < 50000; i++) {
                os.writeUnsigned(0, 0);
            }
        });
        assertEquals(SofabError.BUFFER_FULL, e.error());
    }

    /** The scratch is thread-confined: two threads never write over each other. */
    @Test
    void everyThreadHasItsOwn() throws Exception {
        OStream mine = OStream.overScratch(64);
        mine.writeUnsigned(0, 1);

        AtomicReference<byte[]> theirs = new AtomicReference<>();
        Thread other = new Thread(() -> {
            try {
                OStream os = OStream.overScratch(64);
                os.writeUnsigned(0, 2);
                theirs.set(os.copyOfBytesUsed());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });
        other.start();
        other.join();

        assertArrayEquals(bytes(0x00, 0x02), theirs.get());
        assertArrayEquals(bytes(0x00, 0x01), mine.copyOfBytesUsed(),
                "the other thread wrote into its own buffer");
    }

    /**
     * The <em>encoder</em> is reused too, not only its buffer. Since §6.6 moved the
     * MAX_DEPTH hold-back run into construction, building one per one-shot
     * {@code encode()} would reserve a kilobyte per message for state the previous
     * message has finished with.
     */
    @Test
    void oneEncoderIsSharedByEveryCallOnTheThread() {
        assertSame(OStream.overScratch(64), OStream.overScratch(64),
                "a one-shot encode() must not build an encoder per message");
    }

    /**
     * Re-arming it is what makes the reuse safe: an encode that threw part-way
     * leaves the cursor advanced, the depth counter non-zero and sequence headers
     * held back, and none of that may reach the next message.
     */
    @Test
    void theSharedEncoderIsReArmedAtEveryCall() throws IOException {
        OStream abandoned = OStream.overScratch(64);
        abandoned.writeUnsigned(0, 42);
        abandoned.writeSequenceBeginLazy(3);      // held back, depth 1
        abandoned.writeSequenceBeginLazy(4);      // held back, depth 2

        OStream next = OStream.overScratch(64);
        assertEquals(0, next.bytesUsed(), "the cursor carried over");
        next.writeUnsigned(0, 7);
        assertArrayEquals(bytes(0x00, 0x07), next.copyOfBytesUsed(),
                "a stale held-back sequence header reached the next message");
    }

    /** A grown buffer re-arms the shared encoder onto the new array, not the old. */
    @Test
    void theSharedEncoderFollowsTheBufferWhenItGrows() throws IOException {
        OStream.overScratch(8);
        OStream big = OStream.overScratch(4096);
        for (int i = 0; i < 300; i++) {
            big.writeUnsigned(0, 0);              // 600 bytes: past the first buffer
        }
        assertEquals(600, big.bytesUsed());
    }

    /**
     * A caller that installs a buffer of its own into the shared encoder does not
     * poison the next {@code overScratch}: the re-arm names the scratch buffer, not
     * whatever the previous holder left installed.
     */
    @Test
    void anInstalledForeignBufferDoesNotSurviveTheNextCall() throws IOException {
        OStream os = OStream.overScratch(64);
        byte[] foreign = new byte[64];
        os.bufferSet(foreign, 0);
        os.writeUnsigned(0, 1);

        OStream next = OStream.overScratch(64);
        next.writeUnsigned(0, 2);
        assertArrayEquals(bytes(0x00, 0x02), next.copyOfBytesUsed());
        assertEquals(1, foreign[1],
                "the next message landed in the foreign buffer, overwriting its first");
    }

    /** A size that could not hold anything is refused where it is asked for. */
    @Test
    void aNonPositiveSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> OStream.overScratch(0));
        assertThrows(IllegalArgumentException.class, () -> OStream.overScratch(-1));
    }

    // --- copyOfBytesUsed -----------------------------------------------------

    /** Exactly the bytes written, and a copy: the buffer keeps moving underneath. */
    @Test
    void theCopyIsExactSizedAndDetached() throws IOException {
        OStream os = OStream.overScratch(64);
        os.writeUnsigned(0, 42);
        byte[] taken = os.copyOfBytesUsed();
        assertEquals(os.bytesUsed(), taken.length);
        assertArrayEquals(bytes(0x00, 0x2A), taken);

        os.writeUnsigned(1, 7);
        assertArrayEquals(bytes(0x00, 0x2A), taken, "the copy did not follow the buffer");
        assertArrayEquals(bytes(0x00, 0x2A, 0x08, 0x07), os.copyOfBytesUsed());
    }

    /** Nothing written is an empty message, not a null and not the whole buffer. */
    @Test
    void anEmptyMessageCopiesOutEmpty() {
        assertEquals(0, OStream.overScratch(64).copyOfBytesUsed().length);
    }

    /**
     * It copies {@code [0, bytesUsed())}, and {@code bytesUsed()} counts from the
     * start of the buffer — so a stream given a reserve offset for a lower-layer
     * header hands back that header's room along with the message, which is what
     * makes the reserve useful.
     */
    @Test
    void aReservedHeaderIsPartOfTheCopy() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf, 3);
        os.writeUnsigned(0, 42);
        assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x2A), os.copyOfBytesUsed());
    }
}
