# SofaBuffers `corelib-java` — Conformance Gap Analysis & Remediation Plan

Audit of `corelib-java` against the SofaBuffers language-independent specification
(`CORELIB_PLAN.md`), focused on the §13 Conformance Checklist. Every item was
verified by reading source, tests, and configuration — not inferred from names.

This document is **analysis only**: it proposes fixes but changes no code. The
only file added by this audit is this one.

Evidence base: 549 unit tests pass (`target/surefire-reports/*.txt`), JaCoCo
reports 95% line / 88% branch coverage (`target/site/jacoco/index.html`).

---

## Summary

| Status | Count |
|--------|-------|
| PASS | 14 |
| PARTIAL | 3 |
| GAP | 1 |
| **Total** | **18** |

**Headline findings**

- **GAP — `MAX_DEPTH = 255` is not enforced** (encoder or decoder). The decoder
  tracks depth as a `long` and only rejects at `Long.MAX_VALUE`; there is no
  `MAX_DEPTH` constant. Normative requirement of §4.9 / §6.2.
- **PARTIAL — devcontainer running container name** is `sofa-java-dev`, not the
  `java-devcontainer` pattern mandated by §11.3 (the image tag *is* correct).
- **PARTIAL — Maven package name** is `sofab`, while §6 fixes the registry
  package name at `SofaBuffers`.
- **PARTIAL — `perf` is not CPU-speed-independent** (no cycles/op or instruction
  count) and no `BENCH_SPEC.md` is present (§10).

---

## Per-checklist-item results

| # | Item (§) | Status | Evidence | Notes |
|---|----------|--------|----------|-------|
| 1 | All public symbols under `sofab` namespace (§6) | PARTIAL | `package-info.java:35` package `org.sofabuffers.sofab` (leaf `sofab`); `pom.xml:7-8` `groupId=org.sofabuffers`, `artifactId=sofab`; `README.md:38` "Maven coordinates: `org.sofabuffers:sofab`" | Namespace `sofab` satisfied (leaf package). But §6 fixes the *registry package name* at `SofaBuffers`; the Maven artifact is `sofab`, so users do not install `SofaBuffers`. |
| 2 | API version constant returns `1` (§6) | PASS | `Sofab.java:24` `public static final int API_VERSION = 1;` | Constant present, value 1. |
| 3 | Varint & zig-zag match §4.1–4.2 | PASS | `WireFormat.java:48-60` zigzag enc/dec; `OStream.java:182-210` varint write w/ 10-byte guard; `IStream.java:550-567` `varintPush` overflow guard at `VALUE_BITS=64` | LEB128 little-endian, arithmetic-shift zigzag, overlong/overflow rejected. |
| 4 | Field header `(id<<3)\|type` + all 8 wire types (§4.3) | PASS | `OStream.java:252-257` `writeIdType`; tags `WireFormat.java:21-28`; decode dispatch `IStream.java:207-264` and `:588-628` | All 8 tags (0x0–0x7) encoded and decoded; unknown type → `INVALID_MSG`. |
| 5 | Fixlen word `(len<<3)\|subtype`, LE floats, UTF-8 no terminator, blobs (§4.6) | PASS | `OStream.java:311-437` fixlen/fp32/fp64/string/blob; `FixlenType.java:13-54`; UTF-8 emitted directly `OStream.java:355-413` (no NUL); LE via `putLe32/64` | Floats raw IEEE-754 LE; reserved subtypes 4–7 rejected (`FixlenType.fromRaw`). |
| 6 | Integer arrays + fixlen arrays w/ single shared word; no dynamic subtypes in fixlen arrays (§4.7–4.8) | PASS | `OStream.java:457-667` array writers; single fixlen word `:642-644,:661-663`; decode `IStream.java:387-456`; string/blob rejected as fixlen-array element `IStream.java:429,:706` + test `DecoderErrorsTest.java:52-57` | Empty arrays rejected on encode (`ARGUMENT`); count `1..ARRAY_MAX` validated on decode. |
| 7 | Sequence framing, fresh scope, single-byte `0x07` end, skip-by-walking w/ depth, **reject nesting beyond `MAX_DEPTH`=255** (§4.9) | **GAP** | Framing OK: `OStream.java:679-690` (`writeSequenceEnd` → `0x07`); decode depth `IStream.java:208-221,:610-625`. **But** depth is `long`, only rejected at `Long.MAX_VALUE` (`:209,:611`); no `MAX_DEPTH` constant in `Sofab.java`; encoder does not track depth at all | The normative 255-depth limit is entirely unenforced on both sides. See Remediation #1. |
| 8 | Streaming encode into smaller buffer via flush/sink + mid-stream buffer swap (§5.1) | PASS | `OStream.java:118-155` `flush`/`flushFull`; `:134-144` `bufferSet`; offset ctor `:89-100`; test `VectorConformanceTest.java:121-126,:269-279` (encode with buffer smaller than message) | `BUFFER_FULL` when no sink; wire output buffer-size-independent. |
| 9 | Streaming decode via `feed` of small chunks, push/pull, lazy binding, auto-skip (§5.2) | PASS | `IStream.java:123-165` resumable state machine; `Visitor` default no-ops = skip (`Visitor.java:39-115`); chunked tests `VectorConformanceTest.java:78-86` (1/3/7-byte) and `IStreamTest.java:33` | State suspends/resumes at any byte boundary; unhandled fields auto-dropped. |
| 10 | Error reporting follows §6.3 baseline (idiomatic exceptions allowed) (§6.3) | PASS | `SofabError.java` `ARGUMENT/USAGE/BUFFER_FULL/INVALID_MSG`; `SofabException.java` extends `IOException` w/ `error()` | Maps to InvalidArgument/UsageError/BufferFull/InvalidMessage; OK = normal return. Minor: `USAGE` is declared but never thrown in main src (visitor model has no typed-read mismatch path). |
| 11 | Streaming primitives sufficient for a thin generated-object layer that also streams; one-shot helpers are thin wrappers (§6.1) | PASS | Primitives present: `OStream` flush/sink/`bufferSet`; `IStream.feed` + `Visitor` + `sequenceBegin/End`; `package-info.java:13-26` & `README.md:262-269` describe the generated-code layering | Sufficiency is by design (generator is a separate repo). No in-repo generated-object example or `serialize()/deserialize()` convenience wrapper exists, so sufficiency is asserted, not demonstrated by a test. |
| 12 | Shared test vectors pass encode+decode, plus chunked, roundtrip, malformed, skip (§7) | PASS | `VectorConformanceTest.java` (encode/decode/chunked/skip over 67 vectors → 477 dynamic tests); `RoundTripTest`, `SkipTest`, `IStreamTest`, `DecoderErrorsTest` (malformed: overflow, dangling end, bad lengths); 549 tests, 0 failures | 95% line coverage. No `MAX_DEPTH` test (feature unimplemented — see #1). |
| 13 | `assets/` populated: branding + `test_vectors.json` (§8) | PASS | `assets/sofabuffers_logo.png`, `assets/sofabuffers_icon.png`, `assets/test_vectors.json` (`format: sofabuffers-test-vectors`, 67 vectors); wired to test classpath `pom.xml:52-62` | All three required assets present. |
| 14 | README follows family format w/ badges & required sections (§9) | PASS | `README.md`: header logo/tagline/org link `:1-8`; CI+Coverage+Branches+Javadoc badges `:12-15`; Why this design `:41`; Usage (basic + streaming-larger-than-buffer) `:53-111`; API summary `:113`; Feature flags `:208`; Build & test `:225`; Benchmarks `:244` | All mandated sections present. Minor: no copy-paste dependency snippet (only `org.sofabuffers:sofab` coordinates + "Requires JDK 17+"). |
| 15 | `perf` (CPU-independent) and `bench` (MB/s) tools present & runnable (§10) | PARTIAL | `bench/Bench.java` (MB/s, 2 workloads) and `bench/Perf.java` present & runnable via `exec:java` (`pom.xml:116-128`). But `Perf.java:123` reports "cycles/op (hardware cycle counter unavailable)" and falls back to ns/op — no instruction count; no `BENCH_SPEC.md` in repo | `bench` fully conformant. `perf` does not deliver a CPU-speed-independent metric; §10's `BENCH_SPEC.md` source-of-truth file is absent. See Remediation #4. |
| 16 | `.devcontainer/` complete; extensions incl. `anthropic.claude-code`; `.env` gitignored (§11) | PARTIAL | Files all present (`Dockerfile`, `build.sh`, `start.sh`, `attach.sh`, `devcontainer.json`, `.env.example`); extensions incl. `anthropic.claude-code` (`devcontainer.json:10`); `.env` gitignored & untracked (`git check-ignore` ✓). **But** running container name is `sofa-java-dev` (`start.sh:17`, `attach.sh:4`), not the `java-devcontainer` pattern required by §11.3 (image tag `java-devcontainer` is correct, `build.sh:6`) | Everything else conforms; only the running container name violates the `<lang>-devcontainer` rule. See Remediation #2. |
| 17 | `ci.yml` builds & tests on push and PR; matrix where useful; coverage + badge (§12.1) | PASS | `ci.yml`: triggers push+PR `:3-7`; matrix JDK 17/21 `fail-fast:false` `:21-25`; `mvn -B verify` `:39`; JaCoCo → shields endpoints + `badges` branch `:65-91`; badges in `README.md:13-14` | Coverage surfaced via shields endpoint JSON ("or equivalent" to Codecov). |
| 18 | `docs.yml` generates HTML docs, publishes to Pages via Actions (no `gh-pages`); Docs badge links to site (§12.2) | PASS | `docs.yml`: Javadoc `:34-35`; `configure-pages` (enablement) `:37-41`; `upload-pages-artifact@v3` + `deploy-pages@v4` `:43-57`; `permissions: pages/id-token` `:9-12`; Docs badge → `https://sofa-buffers.github.io/corelib-java/` (`README.md:15`) | Actions-based deployment, no `gh-pages` branch for docs. |

---

## Remediation Plan

Ordered by severity. Each fix is additive and does not change wire output for
already-conformant messages.

### 1. Enforce `MAX_DEPTH = 255` on encode and decode (GAP — HIGH)

**Problem.** §4.9 and §6.2 require `MAX_DEPTH = 255`: an encoder must not open
more than 255 nested sequences, and a decoder must reject a message nesting
deeper than 255 with `InvalidMessage`. The decoder only rejects at
`Long.MAX_VALUE` (`IStream.java:209`, `:611`); the encoder tracks no depth at
all; and there is no `MAX_DEPTH` constant. A hostile/buggy stream can nest
arbitrarily deep without error (and the limit is silently absent from the public
constants).

**Fix.**
- Add `public static final int MAX_DEPTH = 255;` to `Sofab.java` (and an internal
  mirror in `WireFormat.java` if preferred for the codec).
- **Decoder** (`IStream.java`): in both sequence-start paths (`fastField`
  `case T_SEQUENCE_START` and `stepIdle` `case T_SEQUENCE_START`), reject when
  `depth >= MAX_DEPTH` with `new SofabException(SofabError.INVALID_MSG, "max depth")`
  *before* incrementing. `depth` may be narrowed to `int`.
- **Encoder** (`OStream.java`): track an `int depth`; increment in
  `writeSequenceBegin`, decrement in `writeSequenceEnd`, and throw
  `SofabError.ARGUMENT` (or `INVALID_ARGUMENT` equivalent) when a begin would
  exceed `MAX_DEPTH`. Optionally also reject `writeSequenceEnd` with no open
  sequence.

**Files.** `Sofab.java`, `IStream.java`, `OStream.java`; new tests in
`DecoderErrorsTest.java` (256-deep message → `INVALID_MSG`; 255-deep → OK) and
`OStreamTest.java` (256 `writeSequenceBegin` → error).

**Acceptance criteria.**
- `Sofab.MAX_DEPTH == 255` is public.
- Decoding a message with 256 nested `sequence start` markers throws
  `SofabException` with `INVALID_MSG`; 255 levels decode cleanly.
- Encoding a 256th nested `writeSequenceBegin` throws; 255 levels encode cleanly.
- All 549 existing tests still pass; shared vectors unaffected.

### 2. Rename the running devcontainer container to `java-devcontainer` (PARTIAL — MEDIUM)

**Problem.** §11.3 fixes the running container name to the `<lang>-devcontainer`
pattern. `start.sh:17` runs `--name sofa-java-dev` and `attach.sh:4` execs into
`sofa-java-dev`. The image tag is already correct (`java-devcontainer`).

**Fix.** Change `--name sofa-java-dev` → `--name java-devcontainer` in
`start.sh`, and `docker exec -it sofa-java-dev` → `docker exec -it java-devcontainer`
in `attach.sh`. Optionally set `devcontainer.json` `"name"` to match.

**Files.** `.devcontainer/start.sh`, `.devcontainer/attach.sh` (and optionally
`.devcontainer/devcontainer.json`).

**Acceptance criteria.** `start.sh` launches a container named `java-devcontainer`
and `attach.sh` attaches to that exact name; image tag remains `java-devcontainer`.

### 3. Align the registry package name with `SofaBuffers` (PARTIAL — MEDIUM)

**Problem.** §6 fixes the package name registered with the package manager at
`SofaBuffers` ("the name users type in their dependency manifest"), distinct from
the `sofab` namespace. The Maven artifact is `org.sofabuffers:sofab`
(`pom.xml:8`), so users do not install `SofaBuffers`.

**Fix.** Set `<artifactId>SofaBuffers</artifactId>` (keep `groupId`
`org.sofabuffers` and the Java package `org.sofabuffers.sofab` unchanged), and
update the README "Maven coordinates" line accordingly. If retaining `sofab` is a
deliberate choice, document the rationale instead — but the spec text reads as
normative.

**Files.** `pom.xml`, `README.md` (`:38`, `:115`).

**Acceptance criteria.** Maven coordinates published as `org.sofabuffers:SofaBuffers`
(namespace/import path stays `org.sofabuffers.sofab`); README dependency snippet
matches; build still produces a runnable artifact.

### 4. Make `perf` CPU-speed-independent and add `BENCH_SPEC.md` (PARTIAL — LOW)

**Problem.** §10 requires `perf` to report a CPU-speed-independent metric
(cycles/op via a cycle counter, or instruction count under a profiler).
`Perf.java:123` prints "cycles/op … unavailable" and reports only thread-CPU
ns/op. §10 also names `BENCH_SPEC.md` as the single source of truth for
workloads/timing/output; that file is absent.

**Fix.**
- Add a machine-neutral signal to `perf`: e.g. instruction count via
  `perf stat`/async-profiler integration, or document a JFR/JMH-based
  instruction-count path. At minimum, add `BENCH_SPEC.md` documenting the exact
  workloads, timing rules, throughput formula, and output grammar already
  implemented in `Bench.java`/`Perf.java`, and reference it from the README
  Benchmarks section.
- Keep the graceful ns/op fallback where no counter is available.

**Files.** `src/main/java/org/sofabuffers/sofab/bench/Perf.java`, new
`BENCH_SPEC.md`, `README.md` (`:244-260`).

**Acceptance criteria.** `BENCH_SPEC.md` exists and matches the implemented
workloads/output; `perf` either emits a CPU-independent metric where the platform
allows or clearly documents the limitation per `BENCH_SPEC.md`.

---

## Items confirmed conformant (no action)

§13 items 2, 3, 4, 5, 6, 8, 9, 10, 11, 12, 13, 14, 17, 18 — see the per-item
table for the precise evidence. The wire codec (varint/zigzag, header packing,
all 8 wire types, fixlen word + subtypes, integer & fixlen arrays), the streaming
encode/decode state machines, the shared-vector test suite (encode, decode,
chunked, roundtrip, malformed, skip), assets, README, CI, and the Javadoc/Pages
docs workflow all conform.
