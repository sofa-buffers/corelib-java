/*
 * SofaBuffers Java - machine-independent instruction cost (Callgrind Ir/op).
 *
 * Companion to Bench.java (throughput) and Perf.java (per-op timing). Reports
 * instructions retired per operation (Ir/op): unlike wall-clock or cycle
 * counts, an instruction count is deterministic and independent of the host's
 * clock speed and scheduler, so the numbers compare across machines (and
 * against the C/C++/Rust/Go/Python/TypeScript tools -- the workloads, ids and
 * values are identical).
 *
 * The JVM has no native `run_<workload>` symbol Callgrind could toggle on (the
 * hot code is JIT-compiled at runtime), so bench/run_callgrind.sh uses the same
 * two-rep-count subtraction as the Python and TypeScript ports: it runs this
 * program at two rep counts R1 and R2 and subtracts the total instruction
 * counts,
 *
 *     Ir/op = ( Ir(R2) - Ir(R1) ) / ( R2 - R1 )
 *
 * which cancels *all* fixed cost exactly -- JVM startup, class loading, JIT
 * compilation and the one-time setup -- leaving the pure per-op cost. For the
 * subtraction to be clean the two runs must differ *only* in the measured rep
 * count, so this program does a fixed WARMUP (independent of `reps`) that drives
 * the hot methods to their final compiled tier before the measured loop begins;
 * run_callgrind.sh pins compilation and disables GC (EpsilonGC) so nothing else
 * varies between the two runs. This program takes:  <workload> <reps>  and runs
 * exactly `reps` measured operations, then prints `bytes=<n>` on stderr.
 *
 * Run via: bash bench/run_callgrind.sh
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;

import org.sofabuffers.sofab.ArrayKind;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

public final class Callgrind {

    private Callgrind() {
    }

    private static final int N = 1000;

    /**
     * Fixed warmup ops per run (independent of {@code reps}, so it cancels in
     * the subtraction). Large enough to drive the hot methods past the compile
     * threshold to their final JIT tier before the measured loop, so measured
     * ops run at steady cost. Tunable via {@code -Dsofab.warmup=} for very
     * cheap or very expensive workloads.
     */
    private static final int WARMUP = Integer.getInteger("sofab.warmup", 5_000);

    static long BLACKHOLE;

    /** Decode sink that folds every value into a checksum (defeats elision). */
    private static final class Checksum implements Visitor {
        long acc;
        @Override public void unsigned(int id, long v) { acc += v ^ id; }
        @Override public void signed(int id, long v) { acc += v ^ id; }
        @Override public void fp32(int id, float v) { acc += Float.floatToRawIntBits(v); }
        @Override public void fp64(int id, double v) { acc += Double.doubleToRawLongBits(v); }
        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) { acc += l; }
        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) { acc += l; }
        @Override public void arrayBegin(int id, ArrayKind kind, int count) { /* no-op */ }
    }

    private static long[] makeSrc() {
        long[] a = new long[N];
        for (int i = 0; i < N; i++) {
            a[i] = i * 0x9E37_79B9_7F4A_7C15L;
        }
        return a;
    }

    private static void encodeTypical(OStream os) throws IOException {
        os.writeUnsigned(1, 0xDEAD_BEEFL);
        os.writeSigned(2, -12345);
        os.writeBoolean(3, true);
        os.writeFp32(4, 3.14159f);
        os.writeString(5, "sofab");
        os.writeArrayUnsigned(6, new short[] {10, 20, 30, 40});
        os.writeSequenceBegin(7);
        os.writeUnsigned(1, 99);
        os.writeSigned(2, -7);
        os.writeSequenceEnd();
    }

    @FunctionalInterface
    private interface Body {
        void run() throws IOException;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Callgrind <workload> <reps>");
            System.exit(2);
        }
        final String workload = args[0];
        final int reps = Integer.parseInt(args[1]);

        long[] src = makeSrc();

        // Pre-encode both messages (byte sizes + decode input).
        byte[] u64Buf = new byte[N * 11 + 16];
        int u64Used;
        {
            OStream os = new OStream(u64Buf);
            os.writeArrayUnsigned(1, src);
            u64Used = os.bytesUsed();
        }
        byte[] u64Wire = java.util.Arrays.copyOf(u64Buf, u64Used);

        byte[] typBuf = new byte[256];
        int typUsed;
        {
            OStream os = new OStream(typBuf);
            encodeTypical(os);
            typUsed = os.bytesUsed();
        }
        byte[] typWire = java.util.Arrays.copyOf(typBuf, typUsed);

        // Reused encode targets (allocation outside the measured loop).
        byte[] encU64Out = new byte[N * 11 + 16];
        byte[] encTypOut = new byte[256];
        long[] sink = {0};

        final int bytes;
        final Body body;
        switch (workload) {
            case "encode_u64_array" -> {
                bytes = u64Used;
                body = () -> {
                    OStream os = new OStream(encU64Out);
                    os.writeArrayUnsigned(1, src);
                    sink[0] += os.bytesUsed();
                };
            }
            case "encode_typical" -> {
                bytes = typUsed;
                body = () -> {
                    OStream os = new OStream(encTypOut);
                    encodeTypical(os);
                    sink[0] += os.bytesUsed();
                };
            }
            case "decode_u64_array" -> {
                bytes = u64Used;
                body = () -> {
                    Checksum c = new Checksum();
                    new IStream().feed(u64Wire, c);
                    sink[0] += c.acc;
                };
            }
            case "decode_typical" -> {
                bytes = typUsed;
                body = () -> {
                    Checksum c = new Checksum();
                    new IStream().feed(typWire, c);
                    sink[0] += c.acc;
                };
            }
            default -> {
                System.err.println("unknown workload: " + workload);
                System.exit(2);
                return;
            }
        }

        // Fixed warmup (cancels in the subtraction), then the measured ops.
        for (int i = 0; i < WARMUP; i++) {
            body.run();
        }
        for (int i = 0; i < reps; i++) {
            body.run();
        }
        BLACKHOLE = sink[0];

        // stderr feeds the size column; sink keeps the work observable.
        System.err.println("bytes=" + bytes + " sink=" + BLACKHOLE);
    }
}
