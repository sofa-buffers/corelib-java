#!/usr/bin/env bash
#
# SofaBuffers Java — machine-independent instruction cost.
#
# Reports instructions retired per operation (Ir/op) under Callgrind. Unlike
# wall-clock or cycle counts, instruction counts are deterministic and
# independent of the host's clock speed and scheduler, so the numbers compare
# across machines (and against the C/C++/Rust/Go/Python/TypeScript tools — the
# workloads, ids and values are identical).
#
# The JVM has no native `run_<workload>` symbol to `--toggle-collect` on (the
# hot code is JIT-compiled at runtime), so — like the Python and TypeScript
# ports — each workload is run at two rep counts (R1, R2) and the total
# instruction counts are subtracted:
#
#     Ir/op = ( Ir(R2) - Ir(R1) ) / ( R2 - R1 )
#
# which cancels *all* fixed cost exactly — JVM startup, class loading, JIT
# compilation and the one-time setup — leaving the pure per-op cost. For the
# subtraction to be clean the two runs must differ *only* in the measured rep
# count, so the JVM is pinned to make everything else identical between runs:
#
#   -XX:-TieredCompilation -XX:-BackgroundCompilation -XX:CompileThreshold=2000
#         one synchronous compile tier reached after 2000 invocations;
#         Callgrind.java's fixed WARMUP (default 5000) drives the hot methods to
#         C2 before the measured loop, so no tier transition happens during it.
#   -XX:+UseEpsilonGC -Xms/-Xmx equal
#         no garbage collection and a fully-committed heap, so GC and heap
#         growth add no variable instructions (the bounded run never fills it).
#   -XX:hashCode=2
#         a constant identity hashCode, removing the last startup non-determinism
#         (the default scheme seeds identity hashes from a per-run PRNG).
#
# What survives the subtraction is a small, run-to-run-stable startup jitter
# (~0.03% of a run's total); the measured rep delta per workload is chosen so
# that jitter is <0.1% of the reported per-op number. Cheap ops (typical) use a
# large delta; the 1000-element array ops carry a huge per-op signal already, so
# a small delta keeps them fast without losing precision.
#
# Prereqs: valgrind, a JDK (with EpsilonGC — OpenJDK 11+), and Maven.
# Usage:   bash bench/run_callgrind.sh
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# Per-workload measured rep counts (R1 R2): cheap ops need a large delta so the
# startup jitter is negligible; expensive array ops need only a small delta.
REPS_CHEAP="${REPS_CHEAP:-10000 110000}"
REPS_ARRAY="${REPS_ARRAY:-200 1200}"
reps_for() {
    case "$1" in
        encode_u64_array|decode_u64_array) echo "$REPS_ARRAY";;
        *)                                 echo "$REPS_CHEAP";;
    esac
}

if ! command -v valgrind >/dev/null 2>&1; then
    echo "error: valgrind not found (needed for instruction counts)." >&2
    echo "       install it, e.g.  apt-get install valgrind" >&2
    exit 1
fi

echo ">> compiling (mvn -q compile) ..." >&2
mvn -q compile
CP="$ROOT/target/classes"
MAIN="org.sofabuffers.sofab.bench.Callgrind"
JVM_FLAGS=(
    -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC
    -Xms1g -Xmx1g
    -XX:-TieredCompilation -XX:-BackgroundCompilation -XX:CompileThreshold=2000
    -XX:hashCode=2
)

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT
WORKLOADS=(encode_u64_array encode_typical decode_u64_array decode_typical)

run_cg() { # $1 workload, $2 reps, $3 tag
    valgrind --tool=callgrind --callgrind-out-file="$OUT/$3.out" \
        java "${JVM_FLAGS[@]}" -cp "$CP" "$MAIN" "$1" "$2" \
        >/dev/null 2>"$OUT/$3.log"
}

ir_of()    { grep -m1 '^summary:' "$OUT/$1.out" 2>/dev/null | awk '{print $2}'; }
bytes_of() { grep -ohE 'bytes=[0-9]+' "$OUT/$1.log" 2>/dev/null | head -1 | cut -d= -f2; }

label() {
    case "$1" in
        encode_u64_array) echo "encode: u64 array (1000)";;
        encode_typical)   echo "encode: typical message";;
        decode_u64_array) echo "decode: u64 array (1000)";;
        decode_typical)   echo "decode: typical message";;
    esac
}

echo ">> Measuring instructions/op under Callgrind (two rep counts per workload; this is slow) ..." >&2
echo
echo "==============================================================================="
echo " SofaBuffers Java instruction cost   (Callgrind, Ir/op)"
echo " instructions/op: lower is better. Deterministic & machine-independent."
echo "==============================================================================="
printf "%-26s %16s %9s\n" "Workload" "instr/op" "bytes"
printf "%-26s %16s %9s\n" "--------" "--------" "-----"

for w in "${WORKLOADS[@]}"; do
    read -r r1 r2 <<<"$(reps_for "$w")"
    run_cg "$w" "$r1" "$w.lo"
    run_cg "$w" "$r2" "$w.hi"
    lo="$(ir_of "$w.lo")"; hi="$(ir_of "$w.hi")"
    b="$(bytes_of "$w.hi")"
    iperop="$(awk -v lo="${lo:-0}" -v hi="${hi:-0}" -v ops="$(( r2 - r1 ))" \
        'BEGIN{ if (ops>0) printf "%d", (hi-lo)/ops; else print "-" }')"
    printf "%-26s %16s %9s\n" "$(label "$w")" "${iperop:--}" "${b:--}"
done
echo
echo "Ir = instructions retired (Callgrind). Independent of CPU clock and OS"
echo "scheduling; depends only on the executed code, so it compares across machines."
