/*
 * SofaBuffers Java - per-operation cost benchmark.
 *
 * Mirror of bench/c/perf.c, bench/cpp/perf.cpp and benches/perf.rs: encodes and
 * decodes the identical message (same field ids, types and values) through the
 * streaming API and prints the same report, so the C, C++, Rust and Java
 * implementations can be compared directly. The message encodes to 170 bytes on
 * every implementation -- BENCH_SPEC's parity check: a different `message size`
 * here means the encoding diverges.
 *
 * Two metrics per workload:
 *   1. cycles/op  -- cost of the code itself, read off a hardware cycle counter.
 *      The JVM exposes no portable cycle counter, so this line reports that it
 *      is unavailable (the C/Rust tools print the same fallback off-arch).
 *   2. throughput MB/s + CPU time/op -- a "speedtest" for this machine, derived
 *      from *thread CPU time* (not wall-clock), the JVM equivalent of the C
 *      tool's clock(). MB = 1e6 bytes.
 *
 * For a CPU-speed-independent figure on this host, run bench/run_callgrind.sh
 * (instructions retired per op). Workloads, timing rules and output grammar are
 * specified in BENCH_SPEC.md; the timed loop itself is Loop.java, shared with
 * Bench.
 *
 * Run with:
 *   mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

import org.sofabuffers.sofab.ArrayKind;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

public final class Perf {

    private Perf() {
    }

    // --- message under test (identical to perf.c / perf.rs) ----------------
    private static final String PERF_STRING = "perf-benchmark-message";
    private static final int[] PERF_SAMPLES =
            {1_000_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000, 6_000_000, 7_000_000, 8_000_000};
    private static final int[] PERF_DELTAS =
            {-100_000, -200_000, -300_000, -400_000, -500_000, -600_000, -700_000, -800_000};
    private static final double[] PERF_FP64 = {3.14159265, 6.28318530, 9.42477795, 12.56637060};

    static int perfEncode(byte[] buf) throws IOException {
        OStream os = new OStream(buf);
        os.writeUnsigned(1, 0xDEAD_BEEFL);
        os.writeSigned(2, -12345);
        os.writeUnsigned(3, 0x0123_4567_89AB_CDEFL);
        os.writeSigned(4, -5_000_000_000_000L);
        os.writeBoolean(5, true);
        os.writeFp32(6, 3.14159f);
        os.writeFp64(7, 2.718281828459045);
        os.writeString(8, PERF_STRING);
        os.writeArrayUnsigned(9, PERF_SAMPLES);
        os.writeArraySigned(10, PERF_DELTAS);
        os.writeArrayFp64(11, PERF_FP64);
        os.writeSequenceBeginLazy(12);
        os.writeUnsigned(1, 99);
        os.writeSigned(2, -7);
        os.writeSequenceEnd();
        return os.bytesUsed();
    }

    /** Decode sink: folds every value into a checksum and captures id 1 / id 8. */
    private static final class PerfOut implements Visitor {
        long acc;
        int depth;
        long u32Top;
        final byte[] strBuf = new byte[32];
        int strLen;

        @Override public void unsigned(int id, long v) {
            acc += v ^ id;
            if (depth == 0 && id == 1) {
                u32Top = v & 0xFFFF_FFFFL;
            }
        }
        @Override public void signed(int id, long v) { acc += v ^ id; }
        @Override public void fp32(int id, float v) { acc += Float.floatToRawIntBits(v); }
        @Override public void fp64(int id, double v) { acc += Double.doubleToRawLongBits(v); }
        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            acc += l;
            if (id == 8) {
                int end = Math.min(offset + l, strBuf.length);
                if (offset < strBuf.length) {
                    System.arraycopy(d, o, strBuf, offset, end - offset);
                    strLen = end;
                }
            }
        }
        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) { acc += l; }
        @Override public void arrayBegin(int id, ArrayKind kind, int count) { /* no-op */ }
        @Override public void sequenceBegin(int id) { depth++; }
        @Override public void sequenceEnd() { depth--; }
    }

    private static void perfDecode(byte[] buf, int len, PerfOut out) throws IOException {
        new IStream().feed(buf, 0, len, out);
    }

    private static void report(String what, Loop.Result r, int bytes) {
        System.out.printf(Locale.ROOT, "%n--- perf: %s ---%n", what);
        System.out.printf(Locale.ROOT, "  iterations    : %d%n", r.iterations());
        System.out.printf(Locale.ROOT, "  message size  : %d bytes%n", bytes);
        System.out.printf(Locale.ROOT, "  cycles/op     : (hardware cycle counter unavailable on the JVM)%n");
        System.out.printf(Locale.ROOT, "  CPU time/op   : %.1f ns  (thread CPU time, not wall-clock)%n",
                r.nanosPerOp());
        System.out.printf(Locale.ROOT, "  throughput    : %.1f MB/s  (speedtest, MB = 1e6 bytes)%n",
                r.megabytesPerSecond(bytes));
    }

    public static void main(String[] args) throws IOException {
        if (!Loop.supported()) {
            System.err.println("perf: thread CPU time not supported on this JVM");
            return;
        }

        byte[] buffer = new byte[512];
        int msgSize = perfEncode(buffer);

        System.out.println("=== SofaBuffers Java per-op cost (cycles/op + throughput MB/s) ===");

        Loop.Result enc = Loop.run(() -> perfEncode(buffer));
        report("serialize (stream API)", enc, msgSize);

        // Self-check that the decode actually reproduced the data.
        PerfOut check = new PerfOut();
        perfDecode(buffer, msgSize, check);
        if (check.u32Top != 0xDEAD_BEEFL
                || !Arrays.equals(Arrays.copyOf(check.strBuf, check.strLen),
                        PERF_STRING.getBytes(StandardCharsets.UTF_8))) {
            System.err.println("perf: decode self-check failed");
            System.exit(1);
        }

        // The per-op PerfOut allocation stays inside the body -- it is part of
        // the op, as the decode destination is in any real caller.
        Loop.Result dec = Loop.run(() -> {
            PerfOut o = new PerfOut();
            perfDecode(buffer, msgSize, o);
            return o.acc;
        });
        report("deserialize (stream API)", dec, msgSize);

        System.out.println();
        System.out.println("cycles/op tracks code cost; MB/s is this machine's throughput.");
        if (Loop.blackhole == 42) {
            System.out.print(""); // keep the blackhole observably live
        }
    }
}
