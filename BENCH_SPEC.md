# Benchmark Specification (`corelib-java`)

Single source of truth for the SofaBuffers Java benchmark suite, per the
cross-language specification (`documentation/CORELIB_PLAN.md` §10). The two tools —
[`Perf`](src/main/java/org/sofabuffers/sofab/bench/Perf.java) and
[`Bench`](src/main/java/org/sofabuffers/sofab/bench/Bench.java) — implement exactly
the workloads, timing rules, and output described here so their numbers are directly
comparable with the C, C++, Rust, and Go ports.

## Tools

| Tool | Question answered | Metric |
|------|-------------------|--------|
| `Perf` | "How good is the implementation?" (machine-neutral) | CPU-speed-independent per-op cost — cycles/op via a hardware cycle counter where available; instruction count under a profiler otherwise. |
| `Bench` | "How fast is it here, right now?" | Throughput in MB/s on the current machine (MB = 1e6 bytes). |

### CPU-speed independence of `Perf`

The portable JVM exposes **no** hardware cycle counter and **no** retired-instruction
counter through the standard library, so `Perf` cannot read a true cycles/op figure
on its own. It therefore:

* prints an explicit `cycles/op : (hardware cycle counter unavailable on the JVM)`
  line — matching the fallback the C / Rust tools print off their supported arches —
  so the missing machine-neutral metric is never silently replaced; and
* reports thread-CPU-time ns/op (not wall-clock) as the speed signal it *can* measure.

To obtain a genuinely CPU-speed-independent number on a given host, run `Perf` under
an external counter, e.g.:

```bash
# retired instructions per run, via Linux perf (machine-neutral)
perf stat -e instructions:u \
  mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf
```

Divide the reported `instructions` by the printed `iterations` to get instructions/op.
A JMH or async-profiler run against the same `perfEncode` / `perfDecode` bodies yields
the same metric.

## Timing rules (both tools)

* Timing uses **thread CPU time** (`ThreadMXBean.getCurrentThreadCpuTime`), never
  wall-clock, so unrelated system load does not perturb the result.
* Each measured workload is preceded by **200,000 warm-up iterations** to let the JIT
  reach steady state; warm-up is excluded from timing.
* Each workload then runs in a loop for **at least 1.0 s of CPU time**; the iteration
  count is whatever fits in that window.
* Every decoded value is folded into a checksum (`BLACKHOLE`) consumed after the loop
  so the JIT cannot elide the work.
* Throughput formula: `MB/s = bytes_per_iter * iterations / elapsed_seconds / 1e6`.

## Workloads

### `Perf` — one mixed message (encode and decode)

A single message exercising every wire type, encoded/decoded through the streaming API:

| id | type | value |
|----|------|-------|
| 1 | unsigned | `0xDEADBEEF` |
| 2 | signed | `-12345` |
| 3 | unsigned | `0x0123456789ABCDEF` |
| 4 | signed | `-5_000_000_000_000` |
| 5 | boolean | `true` |
| 6 | fp32 | `3.14159` |
| 7 | fp64 | `2.718281828459045` |
| 8 | string | `"perf-benchmark-message"` |
| 9 | unsigned array | `{1e6, 2e6, … 8e6}` (8 elems) |
| 10 | signed array | `{-1e5, -2e5, … -8e5}` (8 elems) |
| 11 | fp64 array | `{3.14159265, 6.28318530, 9.42477795, 12.56637060}` |
| 12 | sequence | child `{id 1: unsigned 99, id 2: signed -7}` |

Reported separately for `serialize` and `deserialize`.

### `Bench` — two workloads (encode and decode each)

1. **u64 array (1000)** — a 1000-element unsigned 64-bit array; element `i` is
   `i * 0x9E3779B97F4A7C15` (a spread of varint widths).
2. **typical message** — a small mixed message: unsigned `0xDEADBEEF`, signed
   `-12345`, boolean `true`, fp32 `3.14159`, string `"sofab"`, a 4-element unsigned
   array `{10,20,30,40}`, and a nested sequence `{id 1: 99, id 2: -7}`.

Each is measured for both encode and decode → four MB/s figures.

## Output grammar

`Perf` prints, per workload (`serialize`, `deserialize`):

```
--- perf: <workload> ---
  iterations    : <n>
  message size  : <bytes> bytes
  cycles/op     : (hardware cycle counter unavailable on the JVM)
  CPU time/op   : <x.x> ns  (thread CPU time, not wall-clock)
  throughput    : <x.x> MB/s  (speedtest, MB = 1e6 bytes)
```

`Bench` prints a four-row table:

```
Workload                           MB/s
--------                           ----
encode: u64 array (1000)        <x.xx>
encode: typical message         <x.xx>
decode: u64 array (1000)        <x.xx>
decode: typical message         <x.xx>
```
