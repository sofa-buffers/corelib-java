/*
 * SofaBuffers Java - machine-independent instruction cost (Callgrind Ir/op).
 *
 * Companion to Bench.java (throughput) and Perf.java (per-op timing). Reports
 * instructions retired per operation (Ir/op): unlike wall-clock or cycle
 * counts, an instruction count is deterministic and independent of the host's
 * clock speed and scheduler, so the numbers compare across machines (and
 * against the C/C++/Rust/Go/Python/TypeScript tools -- the workloads, ids and
 * values are identical, because they all come from Workloads.java).
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
 * count, so this program does a fixed warmup (independent of `reps`) that drives
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
import java.util.List;

public final class Callgrind {

    private Callgrind() {
    }

    /**
     * Warmup operations per run, independent of {@code reps} so it cancels in
     * the subtraction.
     *
     * <p>Its job is to put every measured op in <em>compiled</em> code: the
     * script pins one compile tier at {@code -XX:CompileThreshold=2000}, so a
     * warmup below that would measure the interpreter -- and, worse, a warmup
     * close to it would let the compile land inside the high-rep run only,
     * leaving the subtraction to charge one op with the whole compilation.
     * Everything here is comfortably above the threshold; {@code BenchSpecTest}
     * checks that against the number the script actually passes.
     *
     * <p>The {@code blob 1MB} rows get the smaller of the two figures because
     * they carry a megabyte of copying per op, which is slow under Callgrind, and
     * 2500 ops already clear the threshold with room to spare. Override with
     * {@code -Dsofab.warmup=}.
     */
    static int warmupFor(String workload) {
        return Integer.getInteger("sofab.warmup", workload.contains("blob") ? 2_500 : 5_000);
    }

    public static void main(String[] args) throws IOException {
        int status = run(args);
        if (status != 0) {
            System.exit(status);
        }
    }

    /**
     * One rep-mode run: the body of {@link #main}, returning the exit status
     * instead of calling {@code System.exit} — so a caller in the same JVM (this
     * tool's own test) can drive every workload and check the rejection path
     * without taking the JVM down with it.
     *
     * @param args {@code <workload> <reps>}
     * @return process exit status: 0 on success, 2 on a usage error
     * @throws IOException if a workload's encode or decode fails
     */
    static int run(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: Callgrind <workload> <reps>");
            return 2;
        }
        final String name = args[0];
        final int reps = Integer.parseInt(args[1]);

        Workloads.Workload workload = null;
        List<Workloads.Workload> all = Workloads.all();
        for (Workloads.Workload w : all) {
            if (w.name().equals(name)) {
                workload = w;
            }
        }
        if (workload == null) {
            System.err.println("unknown workload: " + name);
            return 2;
        }

        long sink = 0;
        // Fixed warmup (cancels in the subtraction), then the measured ops.
        for (int i = warmupFor(name); i > 0; i--) {
            sink += workload.body().run();
        }
        for (int i = 0; i < reps; i++) {
            sink += workload.body().run();
        }
        Loop.blackhole = sink;

        // stderr feeds the size column; the sink keeps the work observable.
        System.err.println("bytes=" + workload.bytes() + " sink=" + Loop.blackhole
                + " reps=" + reps);
        return 0;
    }
}
