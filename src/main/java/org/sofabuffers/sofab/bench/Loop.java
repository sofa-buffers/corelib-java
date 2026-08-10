/*
 * SofaBuffers Java - the measurement loop the timed tools share.
 *
 * BENCH_SPEC's "Timing" section is one rule set for both timed tools: warm up
 * first, then run a ~1 s loop against a *process/thread CPU* clock, never
 * wall-clock, and derive MB/s as message_bytes * iterations / cpu_seconds / 1e6.
 * Bench and Perf differ only in what they print, so the loop lives here once.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

final class Loop {

    private Loop() {
    }

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /**
     * Length of the reportable measurement loop, in CPU seconds. BENCH_SPEC says
     * ~1 s, which is the default; the tools' own tests shrink it to a
     * millisecond, because a check on the <em>shape</em> of the output would
     * otherwise spend ten seconds per tool measuring numbers it does not look at.
     * Read per run rather than latched into a constant, so setting it cannot
     * depend on which class the JVM happened to initialize first.
     *
     * <p>The derived budgets scale with it: a batch is a hundredth of the loop,
     * so the clock read that ends it stays a rounding error against the work it
     * timed ({@code getCurrentThreadCpuTime()} costs on the order of a
     * microsecond, and reading it once per operation would time the clock rather
     * than the codec), and the warmup gets a quarter of it.
     */
    static double seconds() {
        return Double.parseDouble(System.getProperty("sofab.bench.seconds", "1.0"));
    }

    /** Warmup operation cap: past this the JIT has nothing left to learn. */
    private static final int WARMUP_OPS = 200_000;

    /** Consumed after the loops so the JIT cannot elide the measured work. */
    static long blackhole;

    /** Whether this JVM can time a thread's CPU consumption at all. */
    static boolean supported() {
        if (!THREADS.isCurrentThreadCpuTimeSupported()) {
            return false;
        }
        THREADS.setThreadCpuTimeEnabled(true);
        return true;
    }

    /** Thread CPU time in seconds (not wall-clock). */
    static double cpuNow() {
        return THREADS.getCurrentThreadCpuTime() / 1e9;
    }

    /**
     * One measured run.
     *
     * @param iterations operations performed
     * @param seconds    CPU seconds they took
     */
    record Result(long iterations, double seconds) {

        double nanosPerOp() {
            return seconds / iterations * 1e9;
        }

        double megabytesPerSecond(int bytes) {
            return (double) bytes * iterations / seconds / 1e6;
        }
    }

    /** Warm up, then run {@code body} for ~{@link #seconds()} of CPU time. */
    static Result run(Workloads.Body body) throws IOException {
        double seconds = seconds();
        warmup(body, seconds / 4.0);
        long batch = calibrate(body, seconds / 100.0);
        long iterations = 0;
        long acc = 0;
        double t0 = cpuNow();
        double elapsed;
        do {
            for (long k = 0; k < batch; k++) {
                acc += body.run();
            }
            iterations += batch;
            elapsed = cpuNow() - t0;
        } while (elapsed < seconds);
        blackhole += acc;
        return new Result(iterations, elapsed);
    }

    /**
     * Drive the hot methods to their final JIT tier. Bounded by <em>time</em> as
     * well as by {@link #WARMUP_OPS} because the workloads span four orders of
     * magnitude per op: 200 000 operations is a warmup for the typical message
     * and four minutes of memory bandwidth for the 1 MB blob.
     */
    private static void warmup(Workloads.Body body, double budget) throws IOException {
        double deadline = cpuNow() + budget;
        long acc = 0;
        for (int i = 0; i < WARMUP_OPS; i++) {
            acc += body.run();
            if ((i & 0x3F) == 0x3F && cpuNow() >= deadline) {
                break;
            }
        }
        blackhole += acc;
    }

    /** Grow a batch until it spans {@code budget} CPU seconds. */
    private static long calibrate(Workloads.Body body, double budget) throws IOException {
        long acc = 0;
        long batch = 1;
        for (;;) {
            double t0 = cpuNow();
            for (long k = 0; k < batch; k++) {
                acc += body.run();
            }
            if (cpuNow() - t0 >= budget) {
                blackhole += acc;
                return batch;
            }
            batch *= 2;
        }
    }
}
