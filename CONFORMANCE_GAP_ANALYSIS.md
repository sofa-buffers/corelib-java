# SofaBuffers `corelib-java` — Conformance Gap Analysis & Remediation Plan

Audit of `corelib-java` against the SofaBuffers language-independent specification
(`CORELIB_PLAN.md`), focused on the §13 Conformance Checklist. Every item was
verified by reading source, tests, and configuration — not inferred from names.

This document is **analysis only**: it proposes fixes but changes no code. The
only file added/changed by this audit is this one.

Evidence base: the existing test suite (549 unit tests, `target/surefire-reports/*.txt`)
and JaCoCo (95% line / 88% branch, `target/site/jacoco/index.html`) reflect the
current code, which is **unchanged** since the previous audit; the deltas below are
driven purely by the spec revision.

---

## Spec revision

Refreshed against the updated `CORELIB_PLAN.md` (commit `dcb85d6`, 2026-06-30):
**zero-length arrays and empty sequences are now legal wire constructs.** The audit
basis remains the §13 Conformance Checklist plus §4–§12 detail.

The three normative changes:

- **§4.7** — `element_count` range is now `0 .. 2,147,483,647` (was `1..`). A
  **zero-count integer array** (unsigned or signed) is valid and is exactly
  `[ header_varint ] [ element_count_varint = 0 ]`, nothing after. Whether an
  explicit empty array differs from an absent field is a *code-generator* concern,
  not a wire-level one.
- **§4.8** — a **zero-count fixlen array** (fp32/fp64) has **no `fixlen_word` and no
  payload** — exactly `[ header_varint ] [ element_count_varint = 0 ]`.
- **§4.9** — an **empty sequence** (`sequence start` immediately followed by `0x07`)
  is legal and a decoder **must** accept it. It is the composite-type counterpart of
  a zero-count array.

Consequence for conformance: a port that **rejects** a zero-count array or an empty
sequence (on encode *or* decode) is now **non-conformant**; accepting count-0 is
required.

### What changed vs previous revision

- **Item 6 (arrays, §4.7–4.8): PASS → GAP.** The previous audit recorded "Empty
  arrays rejected on encode (`ARGUMENT`); count `1..ARRAY_MAX` validated on decode"
  as *conformant*. Under the updated spec that rejection is the violation:
  `corelib-java` rejects zero-count arrays on **both** sides, and the fixlen-array
  decoder cannot even represent the no-`fixlen_word` layout. New **Remediation #1**.
- **Empty sequence (§4.9): newly-required, already PASS.** The encoder places no
  restriction on a begin-immediately-end pair and the decoder handles it; the shared
  vectors now include `empty_sequence`, `nested_empty_sequences`, and
  `empty_sequence_between_fields`, all of which pass. No action needed — but it is now
  an explicit, verified conformance point rather than an untested edge.
- **New invalidated test noted.** `EncoderOverloadsTest.java:111 emptyArraysRejected`
  actively asserts the now-non-conformant rejection; it must be inverted as part of
  Remediation #1 (does not change the §13 classification on its own).
- **Carried forward unchanged:** MAX_DEPTH gap (item 7), devcontainer container name
  (item 16), Maven registry package name (item 1), `perf` not CPU-independent +
  missing `BENCH_SPEC.md` (item 15). None were affected by the spec change.

---

## Summary

| Status | Count | Δ vs previous |
|--------|-------|---------------|
| PASS | 13 | −1 |
| PARTIAL | 3 | 0 |
| GAP | 2 | +1 |
| **Total** | **18** | |

**Headline findings**

- **GAP — zero-length arrays & empty fixlen arrays are rejected** (NEW, HIGH). The
  encoder throws `ARGUMENT` for any empty array (`OStream.java:442-448`,
  `:639-640`, `:658-659`); the decoder throws `INVALID_MSG` for `count == 0`
  (`IStream.java:485-487`, `:775-777`). The fixlen-array decoder additionally always
  reads a `fixlen_word` after the count, so it cannot parse the §4.8 zero-count layout
  even if the count guard were relaxed. Empty sequences (§4.9), by contrast, already
  work.
- **GAP — `MAX_DEPTH = 255` is not enforced** (encoder or decoder). The decoder
  tracks depth as a `long` and only rejects at `Long.MAX_VALUE`; there is no
  `MAX_DEPTH` constant. Normative requirement of §4.9 / §6.2. (Carried forward.)
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
| 1 | All public symbols under `sofab` namespace (§6) | PARTIAL | `package-info.java:35` package `org.sofabuffers.sofab` (leaf `sofab`); `pom.xml:7-8` `groupId=org.sofabuffers`, `artifactId=sofab`; `README.md:38` "Maven coordinates: `org.sofabuffers:sofab`" | Namespace `sofab` satisfied (leaf package). But §6 fixes the *registry package name* at `SofaBuffers`; the Maven artifact is `sofab`, so users do not install `SofaBuffers`. See Remediation #4. |
| 2 | API version constant returns `1` (§6) | PASS | `Sofab.java:24` `public static final int API_VERSION = 1;` | Constant present, value 1. |
| 3 | Varint & zig-zag match §4.1–4.2 | PASS | `WireFormat.java:48-60` zigzag enc/dec; `OStream.java:182-210` varint write w/ 10-byte guard; `IStream.java:550-567` `varintPush` overflow guard at `VALUE_BITS=64` | LEB128 little-endian, arithmetic-shift zigzag, overlong/overflow rejected. |
| 4 | Field header `(id<<3)\|type` + all 8 wire types (§4.3) | PASS | `OStream.java:252-257` `writeIdType`; tags `WireFormat.java:21-28`; decode dispatch `IStream.java:207-264` and `:588-628` | All 8 tags (0x0–0x7) encoded and decoded; unknown type → `INVALID_MSG`. |
| 5 | Fixlen word `(len<<3)\|subtype`, LE floats, UTF-8 no terminator, blobs (§4.6) | PASS | `OStream.java:311-437` fixlen/fp32/fp64/string/blob; `FixlenType.java`; UTF-8 emitted directly `OStream.java:355-413` (no NUL); LE via `putLe32/64`; empty string/blob (len 0) round-trip `IStream.java:320-326,:708-714` | Floats raw IEEE-754 LE; reserved subtypes 4–7 rejected (`FixlenType.fromRaw`). |
| 6 | Integer arrays + fixlen arrays w/ single shared word; **count may be 0** (§4.7–4.8) | **GAP** | Non-empty arrays fully work: writers `OStream.java:457-667`, decode `IStream.java:337-456`, single fixlen word `:642-644,:661-663`, dynamic subtype rejected `:429,:706`. **But zero-count is rejected both ways:** encode `OStream.java:442-448` (`writeArrayHeader count<=0 → ARGUMENT`), `:639-640`/`:658-659` (fp arrays); decode `IStream.java:485-487`/`:775-777` (`count==0 → INVALID_MSG`). The fixlen-array decoder also unconditionally reads the `fixlen_word` after the count (`:393-410`), so the §4.8 no-`fixlen_word` zero-count layout is unrepresentable. Test `EncoderOverloadsTest.java:111-121` asserts the (now-wrong) rejection | §4.7 now allows `element_count = 0`; §4.8 omits `fixlen_word`/payload at count 0. All four array types fail. See Remediation #1. |
| 7 | Sequence framing, fresh scope, single-byte `0x07` end, **empty sequence accepted**, skip-by-walking w/ depth, **reject nesting beyond `MAX_DEPTH`=255** (§4.9) | **GAP** | Empty sequence ✅: encoder unrestricted `OStream.java:679-690`, decoder handles start/end `IStream.java:208-221,:610-625`, vectors `empty_sequence` (`0e07`), `nested_empty_sequences` (`0e160707`), `empty_sequence_between_fields` (`00070e07110d`) all pass. Framing ✅: `writeSequenceEnd → 0x07`. **But** depth is `long`, only rejected at `Long.MAX_VALUE` (`IStream.java:209,:611`); no `MAX_DEPTH` constant in `Sofab.java`; encoder tracks no depth | New §4.9 empty-sequence requirement is met; the long-standing 255-depth limit remains unenforced on both sides. See Remediation #2. |
| 8 | Streaming encode into smaller buffer via flush/sink + mid-stream buffer swap (§5.1) | PASS | `OStream.java:118-155` `flush`/`flushFull`; `:134-144` `bufferSet`; offset ctor `:89-100`; tests in `VectorConformanceTest`/`OStreamTest` (encode with buffer smaller than message) | `BUFFER_FULL` when no sink; wire output buffer-size-independent. |
| 9 | Streaming decode via `feed` of small chunks, push/pull, lazy binding, auto-skip (§5.2) | PASS | `IStream.java:123-165` resumable state machine; `Visitor` default no-ops = skip; chunked tests `VectorConformanceTest` (1/3/7-byte) and `IStreamTest` | State suspends/resumes at any byte boundary; unhandled fields auto-dropped. |
| 10 | Error reporting follows §6.3 baseline (idiomatic exceptions allowed) (§6.3) | PASS | `SofabError.java` `ARGUMENT/USAGE/BUFFER_FULL/INVALID_MSG`; `SofabException.java` extends `IOException` w/ `error()` | Maps to InvalidArgument/UsageError/BufferFull/InvalidMessage; OK = normal return. Minor: `USAGE` is declared but never thrown in main src (visitor model has no typed-read mismatch path). |
| 11 | Streaming primitives sufficient for a thin generated-object layer that also streams; one-shot helpers are thin wrappers (§6.1) | PASS | Primitives present: `OStream` flush/sink/`bufferSet`; `IStream.feed` + `Visitor` + `sequenceBegin/End`; `package-info.java` & README describe the generated-code layering | Sufficiency is by design (generator is a separate repo). No in-repo generated-object example / `serialize()/deserialize()` wrapper exists, so sufficiency is asserted, not demonstrated by a test. |
| 12 | Shared test vectors pass encode+decode, plus chunked, roundtrip, malformed, skip (§7) | PASS | `VectorConformanceTest.java` (encode/decode/chunked/skip over 67 vectors); `RoundTripTest`, `SkipTest`, `IStreamTest`, `DecoderErrorsTest` (malformed: overflow, dangling end, bad lengths); 549 tests, 0 failures; 95% line | All 67 shared vectors pass, incl. the three empty-sequence vectors. Coverage gaps (not vector failures): the shared set contains **no zero-count array vector**, and the local `emptyArraysRejected` test enforces the non-conformant behavior — both addressed by Remediation #1. No `MAX_DEPTH` test (feature unimplemented — see #2). |
| 13 | `assets/` populated: branding + `test_vectors.json` (§8) | PASS | `assets/sofabuffers_logo.png`, `assets/sofabuffers_icon.png`, `assets/test_vectors.json` (`format: sofabuffers-test-vectors`, version 1, 67 vectors); wired to test classpath `pom.xml:52-62` | All three required assets present. |
| 14 | README follows family format w/ badges & required sections (§9) | PASS | `README.md`: header logo/tagline/org link `:1-8`; CI+Coverage+Branches+Javadoc badges `:12-15`; Why this design; Usage (basic + streaming-larger-than-buffer); API summary; Feature flags; Build & test; Benchmarks | All mandated sections present. Minor: no copy-paste dependency snippet (only `org.sofabuffers:sofab` coordinates + "Requires JDK 17+"). |
| 15 | `perf` (CPU-independent) and `bench` (MB/s) tools present & runnable (§10) | PARTIAL | `bench/Bench.java` (MB/s) and `bench/Perf.java` present & runnable via `exec:java` (`pom.xml:116-128`). But `Perf.java:123` reports "cycles/op (hardware cycle counter unavailable)" and falls back to ns/op — no instruction count; no `BENCH_SPEC.md` in repo | `bench` fully conformant. `perf` does not deliver a CPU-speed-independent metric; §10's `BENCH_SPEC.md` source-of-truth file is absent. See Remediation #5. |
| 16 | `.devcontainer/` complete; extensions incl. `anthropic.claude-code`; `.env` gitignored (§11) | PARTIAL | Files all present (`Dockerfile`, `build.sh`, `start.sh`, `attach.sh`, `devcontainer.json`, `.env.example`); extensions incl. `anthropic.claude-code` (`devcontainer.json`); `.env` gitignored. **But** running container name is `sofa-java-dev` (`start.sh:17`, `attach.sh:4`), not the `java-devcontainer` pattern required by §11.3 (image tag `java-devcontainer` is correct, `build.sh:6`, `start.sh:22`) | Everything else conforms; only the running container name violates the `<lang>-devcontainer` rule. See Remediation #3. |
| 17 | `ci.yml` builds & tests on push and PR; matrix where useful; coverage + badge (§12.1) | PASS | `ci.yml`: triggers push+PR; matrix JDK 17/21 `fail-fast:false`; `mvn -B verify`; JaCoCo → shields endpoints + `badges` branch; badges in `README.md` | Coverage surfaced via shields endpoint JSON ("or equivalent" to Codecov). |
| 18 | `docs.yml` generates HTML docs, publishes to Pages via Actions (no `gh-pages`); Docs badge links to site (§12.2) | PASS | `docs.yml`: Javadoc; `configure-pages`; `upload-pages-artifact@v3` + `deploy-pages@v4`; `permissions: pages/id-token`; Docs badge → `https://sofa-buffers.github.io/corelib-java/` | Actions-based deployment, no `gh-pages` branch for docs. |

---

## Remediation Plan

Ordered by severity. Each fix is additive and does not change wire output for
already-conformant (non-empty) messages.

### 1. Accept zero-count arrays on encode and decode (GAP — HIGH, NEW)

**Problem.** §4.7 now permits `element_count = 0` for unsigned/signed integer
arrays, and §4.8 permits a zero-count fixlen array that carries **no `fixlen_word`
and no payload**. `corelib-java` rejects all of these:

- Encode: `OStream.writeArrayHeader` throws `ARGUMENT` for `count <= 0`
  (`OStream.java:442-448`); `writeArrayFp32`/`writeArrayFp64` throw `ARGUMENT` for a
  zero-length input (`:639-640`, `:658-659`).
- Decode: `fastArrayHeader` and `stepArrayCount` throw `INVALID_MSG` for `count == 0`
  (`IStream.java:485-487`, `:775-777`).
- Structural: even with the count guard removed, the fixlen-array decoder reads a
  `fixlen_word` immediately after the count (`fastFixlenArray:393-410`; slow path
  arms `FIXLEN_LEN` from `stepArrayCount`), so a zero-count fixlen array (which has no
  `fixlen_word`) would be mis-parsed against the following field's bytes.
- A unit test, `EncoderOverloadsTest.java:111-121 emptyArraysRejected`, asserts the
  rejection and would need to be inverted.

**Fix.**
- **Encode.** Drop the `count <= 0` rejection: in `writeArrayHeader` accept `count == 0`
  (still reject negative as an argument error) and emit `[ header ][ count=0 ]` only.
  In `writeArrayFp32`/`writeArrayFp64`, when `data.length == 0` write the header and
  `count = 0` and **stop — do not emit the `fixlen_word`** (per §4.8).
- **Decode.** In `fastArrayHeader`/`stepArrayCount`, allow `count == 0` (keep the
  `> ARRAY_MAX` upper-bound guard). On `count == 0`:
  - emit `arrayBegin(id, kind, 0)` and return to `IDLE` for **all** kinds;
  - for `FIXLEN`, **skip reading the element `fixlen_word`** entirely — the field ends
    at the count. (Branch in `fastFixlenArray` and in `stepArrayCount`'s FIXLEN arm.)
  - integer kinds already terminate cleanly with `arrayRemaining == 0`, but verify the
    fast/slow paths don't dereference a non-existent first element.
- **Tests.** Invert `EncoderOverloadsTest.emptyArraysRejected` into an
  "empty array round-trips" test; add encode+decode+chunked cases for a zero-count
  unsigned array (`[hdr][00]`), zero-count signed array, and zero-count fp32/fp64
  array (`[hdr][00]`, **no** trailing word). If/when the shared `test_vectors.json`
  gains zero-count vectors, they must pass unchanged.

**Files.** `OStream.java`, `IStream.java`; `EncoderOverloadsTest.java`,
`VectorConformanceTest`/`RoundTripTest`/`StreamingEdgeTest` (new cases).

**Acceptance criteria.**
- Encoding an empty `int[]`/`long[]`/`float[]`/`double[]` produces exactly
  `[ header ][ element_count = 0 ]` (fixlen arrays carry **no** `fixlen_word`).
- Decoding those bytes fires `arrayBegin(..., 0)` once, emits no elements, and resumes
  cleanly on the following field — including byte-at-a-time feeding.
- A zero-count array followed by another field decodes both fields correctly.
- All existing shared vectors still pass.

### 2. Enforce `MAX_DEPTH = 255` on encode and decode (GAP — HIGH, carried)

**Problem.** §4.9 and §6.2 require `MAX_DEPTH = 255`: an encoder must not open more
than 255 nested sequences, and a decoder must reject deeper nesting with
`InvalidMessage`. The decoder only rejects at `Long.MAX_VALUE` (`IStream.java:209`,
`:611`); the encoder tracks no depth; there is no `MAX_DEPTH` constant. (Empty
sequences — the *other* §4.9 change — already work and need no fix.)

**Fix.**
- Add `public static final int MAX_DEPTH = 255;` to `Sofab.java`.
- **Decoder** (`IStream.java`): in both sequence-start paths (`fastField`
  `case T_SEQUENCE_START` and `stepIdle` `case T_SEQUENCE_START`), reject when
  `depth >= MAX_DEPTH` with `INVALID_MSG` *before* incrementing; `depth` may be
  narrowed to `int`.
- **Encoder** (`OStream.java`): track an `int depth`; increment in
  `writeSequenceBegin`, decrement in `writeSequenceEnd`, and throw `ARGUMENT` when a
  begin would exceed `MAX_DEPTH`. Optionally reject `writeSequenceEnd` with no open
  sequence.

**Files.** `Sofab.java`, `IStream.java`, `OStream.java`; new tests in
`DecoderErrorsTest.java` (256-deep → `INVALID_MSG`; 255-deep → OK) and
`OStreamTest.java` (256th `writeSequenceBegin` → error).

**Acceptance criteria.**
- `Sofab.MAX_DEPTH == 255` is public.
- Decoding 256 nested `sequence start` markers throws `INVALID_MSG`; 255 decode cleanly.
- Encoding a 256th nested `writeSequenceBegin` throws; 255 encode cleanly.
- All existing tests still pass; shared vectors unaffected.

### 3. Rename the running devcontainer container to `java-devcontainer` (PARTIAL — MEDIUM)

**Problem.** §11.3 fixes the running container name to the `<lang>-devcontainer`
pattern. `start.sh:17` runs `--name sofa-java-dev` and `attach.sh:4` execs into
`sofa-java-dev`. The image tag is already correct (`java-devcontainer`).

**Fix.** Change `--name sofa-java-dev` → `--name java-devcontainer` in `start.sh`,
and `docker exec -it sofa-java-dev` → `docker exec -it java-devcontainer` in
`attach.sh`. Optionally set `devcontainer.json` `"name"` to match.

**Files.** `.devcontainer/start.sh`, `.devcontainer/attach.sh` (and optionally
`.devcontainer/devcontainer.json`).

**Acceptance criteria.** `start.sh` launches a container named `java-devcontainer`
and `attach.sh` attaches to that exact name; image tag remains `java-devcontainer`.

### 4. Align the registry package name with `SofaBuffers` (PARTIAL — MEDIUM)

**Problem.** §6 fixes the package name registered with the package manager at
`SofaBuffers` ("the name users type in their dependency manifest"), distinct from the
`sofab` namespace. The Maven artifact is `org.sofabuffers:sofab` (`pom.xml:8`), so
users do not install `SofaBuffers`.

**Fix.** Set `<artifactId>SofaBuffers</artifactId>` (keep `groupId`
`org.sofabuffers` and the Java package `org.sofabuffers.sofab` unchanged), and update
the README "Maven coordinates" line accordingly. If retaining `sofab` is deliberate,
document the rationale instead — but the spec text reads as normative.

**Files.** `pom.xml`, `README.md` (`:38`, `:115`).

**Acceptance criteria.** Maven coordinates published as `org.sofabuffers:SofaBuffers`
(namespace/import path stays `org.sofabuffers.sofab`); README dependency snippet
matches; build still produces a runnable artifact.

### 5. Make `perf` CPU-speed-independent and add `BENCH_SPEC.md` (PARTIAL — LOW)

**Problem.** §10 requires `perf` to report a CPU-speed-independent metric (cycles/op
via a cycle counter, or instruction count under a profiler). `Perf.java:123` prints
"cycles/op … unavailable" and reports only thread-CPU ns/op. §10 also names
`BENCH_SPEC.md` as the single source of truth for workloads/timing/output; that file
is absent.

**Fix.**
- Add a machine-neutral signal to `perf` (e.g. instruction count via `perf stat` /
  async-profiler, or a documented JFR/JMH path). At minimum, add `BENCH_SPEC.md`
  documenting the exact workloads, timing rules, throughput formula, and output
  grammar already implemented in `Bench.java`/`Perf.java`, and reference it from the
  README Benchmarks section.
- Keep the graceful ns/op fallback where no counter is available.

**Files.** `src/main/java/org/sofabuffers/sofab/bench/Perf.java`, new `BENCH_SPEC.md`,
`README.md`.

**Acceptance criteria.** `BENCH_SPEC.md` exists and matches the implemented
workloads/output; `perf` either emits a CPU-independent metric where the platform
allows or clearly documents the limitation per `BENCH_SPEC.md`.

---

## Items confirmed conformant (no action)

§13 items 2, 3, 4, 5, 8, 9, 10, 11, 12, 13, 14, 17, 18 — see the per-item table for
the precise evidence. The wire codec (varint/zigzag, header packing, all 8 wire
types, fixlen word + subtypes, **non-empty** integer & fixlen arrays), the streaming
encode/decode state machines, **empty-sequence handling (§4.9)**, the shared-vector
test suite (encode, decode, chunked, roundtrip, malformed, skip — including the three
empty-sequence vectors), assets, README, CI, and the Javadoc/Pages docs workflow all
conform. The two open GAPs are zero-count array support (#1) and `MAX_DEPTH`
enforcement (#2); the three PARTIALs are the devcontainer container name (#3), the
Maven registry package name (#4), and `perf`/`BENCH_SPEC.md` (#5).
