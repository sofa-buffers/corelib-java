/*
 * SofaBuffers Java - throughput benchmark (MB/s, CPU time).
 *
 * Mirror of bench/c/bench.c, bench/cpp/bench.cpp and benches/bench.rs: encode /
 * decode throughput for two workloads -- a 1000-element u64 array and a small
 * "typical" mixed message. Each workload runs in a ~1 s CPU-time loop and
 * reports MB/s in the same table layout as the C/C++/Rust tools, so the
 * implementations can be compared directly. MB = 1e6 bytes.
 *
 * Run with:
 *   mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;

import org.sofabuffers.sofab.ArrayKind;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

public final class Bench {

    private Bench() {
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final int N = 1000;
    private static final double MIN_SECONDS = 1.0;

    static long BLACKHOLE;

    private static double cpuNow() {
        return THREADS.getCurrentThreadCpuTime() / 1e9;
    }

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

    /** Run {@code body} for ~1 s of CPU time (after warmup) -> MB/s. */
    private static double measure(int bytes, Body body) throws IOException {
        for (int i = 0; i < 200_000; i++) {
            body.run(); // warmup / JIT
        }
        long it = 0;
        double t0 = cpuNow();
        double el;
        do {
            body.run();
            it++;
            el = cpuNow() - t0;
        } while (el < MIN_SECONDS);
        return (double) bytes * it / el / 1e6;
    }

    public static void main(String[] args) throws IOException {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) {
            System.err.println("bench: thread CPU time not supported on this JVM");
            return;
        }
        THREADS.setThreadCpuTimeEnabled(true);

        long[] src = makeSrc();

        // Pre-encode to learn byte sizes and to use as decode input.
        byte[] u64Buf = new byte[N * 11 + 16];
        int u64Used;
        {
            OStream os = new OStream(u64Buf);
            os.writeArrayUnsigned(1, src);
            u64Used = os.bytesUsed();
        }
        byte[] u64Wire = Arrays.copyOf(u64Buf, u64Used);

        byte[] typBuf = new byte[256];
        int typUsed;
        {
            OStream os = new OStream(typBuf);
            encodeTypical(os);
            typUsed = os.bytesUsed();
        }
        byte[] typWire = Arrays.copyOf(typBuf, typUsed);

        final int ba = u64Used;
        final int bt = typUsed;

        // Reused encode targets (allocation outside the timed loop).
        byte[] encU64Out = new byte[N * 11 + 16];
        byte[] encTypOut = new byte[256];

        long[] sink = {0};

        double encU64 = measure(ba, () -> {
            OStream os = new OStream(encU64Out);
            os.writeArrayUnsigned(1, src);
            sink[0] += os.bytesUsed();
        });
        double encTyp = measure(bt, () -> {
            OStream os = new OStream(encTypOut);
            encodeTypical(os);
            sink[0] += os.bytesUsed();
        });
        double decU64 = measure(ba, () -> {
            Checksum c = new Checksum();
            new IStream().feed(u64Wire, c);
            sink[0] += c.acc;
        });
        double decTyp = measure(bt, () -> {
            Checksum c = new Checksum();
            new IStream().feed(typWire, c);
            sink[0] += c.acc;
        });
        BLACKHOLE = sink[0];

        System.out.println("=== SofaBuffers Java throughput (CPU time, MB/s) ===");
        System.out.printf("%-26s %12s%n", "Workload", "MB/s");
        System.out.printf("%-26s %12s%n", "--------", "----");
        System.out.printf("%-26s %12.2f%n", "encode: u64 array (1000)", encU64);
        System.out.printf("%-26s %12.2f%n", "encode: typical message", encTyp);
        System.out.printf("%-26s %12.2f%n", "decode: u64 array (1000)", decU64);
        System.out.printf("%-26s %12.2f%n", "decode: typical message", decTyp);
        System.out.println();
        System.out.println("MB = 1e6 bytes. ~1s CPU-time loop per workload.");
        if (BLACKHOLE == 42) {
            System.out.print("");
        }
    }
}
