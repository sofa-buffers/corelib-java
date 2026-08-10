/*
 * SofaBuffers Java - throughput benchmark (MB/s, CPU time).
 *
 * Mirror of bench/c/bench.c, bench/cpp/bench.cpp and benches/bench.rs: encode /
 * decode throughput for BENCH_SPEC's workload set -- a 1000-element u64 array, a
 * small "typical" mixed message, an unbounded 1 MB blob and the "composite"
 * message that reaches the paths the flat datasets miss. Each workload runs in a
 * ~1 s CPU-time loop and reports MB/s in the same table layout as the C/C++/Rust
 * tools, so the implementations can be compared directly. MB = 1e6 bytes.
 *
 * **Read the blob 1MB rows against each other, not against the others.** Five
 * bytes of that message are metadata and a million are payload, so its MB/s is
 * this machine's memory bandwidth rather than a statement about the corelib --
 * and the streamed row can even come out ahead of the one-shot one here, since a
 * 4 KiB window stays in L1 while a one-shot encode writes a megabyte out to
 * memory. The flush machinery's own cost (CORELIB_PLAN §5.1) does not survive
 * that: bench/run_callgrind.sh is what measures it, with the caveat the README
 * states about this JVM's array-copy stub.
 *
 * The two `composite` decode rows carry a JVM-specific caveat of their own: they
 * share one process, so the visitor call sites inside IStream see both sinks and
 * neither row runs monomorphic. `skip-all` is the cheaper of the two in Ir/op,
 * where each workload gets a JVM to itself, and that is where to read it.
 *
 * Run with:
 *   mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

public final class Bench {

    private Bench() {
    }

    public static void main(String[] args) throws IOException {
        if (!Loop.supported()) {
            System.err.println("bench: thread CPU time not supported on this JVM");
            return;
        }

        List<Workloads.Workload> workloads = Workloads.all();
        double[] mbs = new double[workloads.size()];
        for (int i = 0; i < workloads.size(); i++) {
            Workloads.Workload w = workloads.get(i);
            mbs[i] = Loop.run(w.body()).megabytesPerSecond(w.bytes());
        }

        System.out.println("=== SofaBuffers Java throughput (CPU time, MB/s) ===");
        System.out.printf(Locale.ROOT, "%-26s %12s%n", "Workload", "MB/s");
        System.out.printf(Locale.ROOT, "%-26s %12s%n", "--------", "----");
        for (int i = 0; i < workloads.size(); i++) {
            System.out.printf(Locale.ROOT, "%-26s %12.2f%n", workloads.get(i).label(), mbs[i]);
        }
        System.out.println();
        System.out.println("MB = 1e6 bytes. ~1s CPU-time loop per workload.");
        System.out.println(
                "blob 1MB is bandwidth-bound: read one-shot vs streaming, not either alone.");
        if (Loop.blackhole == 42) {
            System.out.print(""); // keep the blackhole observably live
        }
    }
}
