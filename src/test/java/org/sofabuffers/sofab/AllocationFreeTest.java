/*
 * SofaBuffers Java - the "measure" half of CORELIB_PLAN §6.6.4.
 *
 * §6.6 permits the codec exactly one moment to allocate -- construction -- and
 * binds everything after it: "`write`, `feed`, `flush` and every path they reach
 * perform **zero** allocations". §6.6.4 says reading the source is not enough on
 * its own and requires the second half as well: "an allocation count, or the heap
 * high-water mark, over a complete encode and a complete decode, measured after
 * the codec's one-time construction", which on a runtime that does not box the
 * codec's values MUST be zero. Java does not box them -- every value on these
 * paths is a `long`, a `double` or a byte of the caller's array -- so zero is the
 * number this file asserts.
 *
 * The JVM offers exactly the facility the clause asks for:
 * `com.sun.management.ThreadMXBean.getThreadAllocatedBytes`, an exact per-thread
 * byte count maintained by the allocator itself, not a sampled or GC-derived
 * estimate. A source read alone would not have caught the two sites this file
 * exists for -- a lazily sized float landing zone and a lazily grown pending run
 * -- since both looked like ordinary reuse.
 *
 * Discipline for anything added here: everything the measured window touches is
 * constructed *before* the window opens, including the chunk arrays fed to the
 * decoder, and the visitor folds into primitive fields so it cannot allocate on
 * the codec's behalf. Warm up first, or class loading and JIT compilation
 * dominate the count.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.management.ManagementFactory;

import org.junit.jupiter.api.Test;

class AllocationFreeTest {

    /** Warm-up repetitions: enough to load every class and reach compiled code. */
    private static final int WARMUP = 20_000;

    /** Measured repetitions: a per-message allocation is multiplied by this. */
    private static final int REPS = 1_000;

    /**
     * Freshly constructed codecs driven inside a measured window. The lazily sized
     * state A2-0069 named cost its bytes on an instance's <em>first</em> message
     * only, so a test that reuses one instance cannot see it at all.
     */
    private static final int FRESH = 200;

    // --- the two paths A2-0069 named ----------------------------------------

    /**
     * A whole encode, including the nested sequence framing that used to grow the
     * pending run, and the deepest run this encoder can hold back.
     */
    @Test
    void aCompleteEncodeAllocatesNothingAfterConstruction() throws IOException {
        byte[] buf = new byte[512];
        OStream os = new OStream(buf);

        for (int i = 0; i < WARMUP; i++) {
            encode(os, buf);
        }
        long spent = allocatedOver(() -> {
            for (int i = 0; i < REPS; i++) {
                encode(os, buf);
            }
        });
        assertEquals(0, spent, "OStream allocated " + spent + " bytes over " + REPS
                + " encodes; §6.6 allows allocation only in the constructor");
    }

    /**
     * The full {@code MAX_DEPTH} hold-back run on <b>freshly constructed</b>
     * encoders — the depth whose overflow array is the state §6.0.1 requires to be
     * sized at construction, driven on the one instance where lazy sizing is
     * visible.
     *
     * <p>Measuring a <em>reused</em> encoder would not see it: a run grown on the
     * first message is still there for the second, so the whole defect hides in
     * the warm-up. The encoders are therefore built before the window opens —
     * construction MAY allocate (§6.6) — and only driven inside it.
     */
    @Test
    void theDeepestRunOnAFreshEncoderAllocatesNothing() throws IOException {
        byte[] buf = new byte[4096];
        OStream[] fresh = new OStream[FRESH];

        for (int i = 0; i < FRESH; i++) {          // warm up on throwaway instances
            deepRun(new OStream(buf), buf);
        }
        for (int i = 0; i < FRESH; i++) {
            fresh[i] = new OStream(buf);
        }
        long spent = allocatedOver(() -> {
            for (int i = 0; i < FRESH; i++) {
                deepRun(fresh[i], buf);
            }
        });
        assertEquals(0, spent, "holding back " + Sofab.MAX_DEPTH + " sequence headers allocated "
                + spent + " bytes over " + FRESH + " fresh encoders; the pending run must be "
                + "sized at construction (§6.0.1)");
    }

    /**
     * A whole decode fed one byte at a time, so every scalar and both float
     * payloads straddle a chunk boundary and the landing zone is in use — the
     * exact path whose carry buffer used to be allocated on first use.
     *
     * <p>On <b>freshly constructed</b> decoders, for the same reason the encoder
     * test above uses fresh ones: a landing zone allocated for the first
     * straddling float survives {@code reset()}, so a reused decoder shows the
     * cost once, during warm-up, and never again.
     */
    @Test
    void aByteAtATimeDecodeOnAFreshDecoderAllocatesNothing() throws IOException {
        byte[] buf = new byte[512];
        int n = encode(new OStream(buf), buf);
        byte[] message = new byte[n];
        System.arraycopy(buf, 0, message, 0, n);

        Folding sink = new Folding();
        byte[] chunk = new byte[1];
        IStream[] fresh = new IStream[FRESH];

        for (int i = 0; i < FRESH; i++) {          // warm up on throwaway instances
            feedByteAtATime(new IStream(), sink, message, chunk);
        }
        for (int i = 0; i < FRESH; i++) {
            fresh[i] = new IStream();
        }
        long spent = allocatedOver(() -> {
            for (int i = 0; i < FRESH; i++) {
                feedByteAtATime(fresh[i], sink, message, chunk);
            }
        });
        assertEquals(0, spent, "IStream allocated " + spent + " bytes over " + FRESH
                + " fresh byte-at-a-time decodes; the float landing zone must be sized at "
                + "construction (§6.6.2)");
    }

    /** The same message on one reused decoder: per-message cost is zero too. */
    @Test
    void aReusedDecoderCostsNothingPerMessage() throws IOException {
        byte[] buf = new byte[512];
        int n = encode(new OStream(buf), buf);
        byte[] message = new byte[n];
        System.arraycopy(buf, 0, message, 0, n);

        IStream is = new IStream();
        Folding sink = new Folding();
        byte[] chunk = new byte[1];

        for (int i = 0; i < 2_000; i++) {
            feedByteAtATime(is, sink, message, chunk);
        }
        long spent = allocatedOver(() -> {
            for (int i = 0; i < 2_000; i++) {
                feedByteAtATime(is, sink, message, chunk);
            }
        });
        assertEquals(0, spent, "IStream allocated " + spent + " bytes over 2000 chunked decodes");
    }

    /** The same message fed whole, the shape a one-shot {@code decode} uses. */
    @Test
    void aWholeMessageDecodeAllocatesNothingAfterConstruction() throws IOException {
        byte[] buf = new byte[512];
        OStream os = new OStream(buf);
        int n = encode(os, buf);

        IStream is = new IStream();
        Folding sink = new Folding();

        for (int i = 0; i < WARMUP; i++) {
            is.reset();
            is.feed(buf, 0, n, sink);
        }
        long spent = allocatedOver(() -> {
            for (int i = 0; i < REPS; i++) {
                is.reset();
                is.feed(buf, 0, n, sink);
            }
        });
        assertEquals(0, spent, "IStream allocated " + spent + " bytes over " + REPS + " decodes");
    }

    /**
     * §6.6's own test — "can a sender make this allocation bigger by sending
     * different bytes?" — asked directly: a payload a thousand times longer costs
     * the decoder the same zero bytes.
     */
    @Test
    void aThousandTimesLongerPayloadCostsTheDecoderNothingMore() throws IOException {
        byte[] small = blobMessage(16);
        byte[] large = blobMessage(16_000);

        IStream is = new IStream();
        Folding sink = new Folding();
        for (int i = 0; i < 5_000; i++) {
            is.reset();
            is.feed(small, sink);
            is.reset();
            is.feed(large, sink);
        }

        long forSmall = allocatedOver(() -> {
            for (int i = 0; i < 500; i++) {
                is.reset();
                is.feed(small, sink);
            }
        });
        long forLarge = allocatedOver(() -> {
            for (int i = 0; i < 500; i++) {
                is.reset();
                is.feed(large, sink);
            }
        });
        assertEquals(0, forSmall, "a 16-byte blob cost the decoder " + forSmall + " bytes");
        assertEquals(forSmall, forLarge, "a 16000-byte blob cost the decoder " + forLarge
                + " bytes against " + forSmall + " for a 16-byte one: the wire is sizing an "
                + "allocation (§6.6)");
    }

    // --- fixtures ------------------------------------------------------------

    /** One message per §7.1's categories, ending in a nested sequence. */
    private static int encode(OStream os, byte[] buf) throws IOException {
        os.reset(buf);
        os.writeUnsigned(1, 0xDEAD_BEEFL);
        os.writeSigned(2, -12345);
        os.writeBoolean(3, true);
        os.writeFp32(4, 3.14159f);
        os.writeFp64(5, 2.718281828459045);
        os.writeString(6, "allocation-free");
        os.writeArrayUnsigned(7, UNSIGNED);
        os.writeArraySigned(8, SIGNED);
        os.writeArrayFp64(9, FP64);
        os.writeSequenceBeginLazy(10);
        os.writeUnsigned(1, 99);
        os.writeSequenceBeginLazy(2);
        os.writeSigned(1, -7);
        os.writeSequenceEnd();
        os.writeSequenceEnd();
        return os.bytesUsed();
    }

    private static void deepRun(OStream os, byte[] buf) throws IOException {
        os.reset(buf);
        for (int d = 0; d < Sofab.MAX_DEPTH; d++) {
            os.writeSequenceBeginLazy(d);
        }
        os.writeUnsigned(1, 1);          // commits the whole held-back run
        for (int d = 0; d < Sofab.MAX_DEPTH; d++) {
            os.writeSequenceEnd();
        }
    }

    private static void feedByteAtATime(IStream is, Visitor sink, byte[] message, byte[] chunk)
            throws IOException {
        is.reset();
        for (byte b : message) {
            chunk[0] = b;
            is.feed(chunk, sink);
        }
    }

    private static byte[] blobMessage(int payload) throws IOException {
        byte[] buf = new byte[payload + 32];
        OStream os = new OStream(buf);
        os.writeBlob(1, new byte[payload]);
        byte[] out = new byte[os.bytesUsed()];
        System.arraycopy(buf, 0, out, 0, out.length);
        return out;
    }

    private static final int[] UNSIGNED = {1_000_000, 2_000_000, 3_000_000, 4_000_000};
    private static final int[] SIGNED = {-100_000, -200_000, -300_000, -400_000};
    private static final double[] FP64 = {3.14159265, 6.28318530, 9.42477795};

    /**
     * A visitor whose every field is primitive: it cannot allocate, so what the
     * measurement sees is the codec's own behaviour and nothing else.
     */
    private static final class Folding implements Visitor {
        long acc;

        @Override
        public void unsigned(int id, long value) {
            acc += value ^ id;
        }

        @Override
        public void signed(int id, long value) {
            acc += value ^ id;
        }

        @Override
        public void fp32(int id, float value) {
            acc += Float.floatToRawIntBits(value);
        }

        @Override
        public void fp64(int id, double value) {
            acc += Double.doubleToRawLongBits(value);
        }

        @Override
        public void string(int id, int total, int offset, byte[] data, int at, int len) {
            for (int i = 0; i < len; i++) {
                acc += data[at + i];
            }
        }

        @Override
        public void blob(int id, int total, int offset, byte[] data, int at, int len) {
            for (int i = 0; i < len; i++) {
                acc += data[at + i];
            }
        }
    }

    // --- the measurement -----------------------------------------------------

    /** Anything the measured window runs; {@link IOException} is the encoder's. */
    private interface Body {
        void run() throws IOException;
    }

    /**
     * Bytes this thread allocated while {@code body} ran. The bean's own call
     * costs nothing measurable — it returns a counter the allocator maintains —
     * and the two reads bracket the window with no allocation between them.
     */
    private static long allocatedOver(Body body) throws IOException {
        com.sun.management.ThreadMXBean bean = threadBean();
        long id = Thread.currentThread().getId();
        long before = bean.getThreadAllocatedBytes(id);
        body.run();
        long after = bean.getThreadAllocatedBytes(id);
        return after - before;
    }

    private static com.sun.management.ThreadMXBean threadBean() {
        java.lang.management.ThreadMXBean plain = ManagementFactory.getThreadMXBean();
        assumeTrue(plain instanceof com.sun.management.ThreadMXBean,
                "this JVM exposes no per-thread allocation counter");
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean) plain;
        assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "this JVM does not support thread allocation measurement");
        bean.setThreadAllocatedMemoryEnabled(true);
        return bean;
    }
}
