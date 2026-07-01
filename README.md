<p align="center"><img src="assets/sofabuffers_logo.png" alt="SofaBuffers" height="140"></p>

# SofaBuffers

<b>Structured Objects For Anyone</b><br>
<i>... so optimized, feels amazing.</i>

[Would you like to know more?](https://github.com/sofa-buffers)

## SofaBuffers Java library

[![CI](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-java%2Fbadges%2Fcoverage.json)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Branches](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-java%2Fbadges%2Fbranches.json)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Javadoc](https://img.shields.io/badge/javadoc-online-blue)](https://sofa-buffers.github.io/corelib-java/)

[GitHub repository](https://github.com/sofa-buffers/corelib-java)

A **dependency-free**, **allocation-light**, **streaming** Java implementation of
the SofaBuffers (*Sofab*) serialization format. It is the **runtime stream core**
(equivalent to the C `corelib`'s `istream` / `ostream`), a port of the C
`corelib` (`istream.c` / `ostream.c`) that runs anywhere a JVM does — from
desktops and servers to containers in the cloud.

Like protobuf-java's `CodedInputStream` / `CodedOutputStream`, this library is
meant to be driven by **generated code**: a schema-driven generator emits one
class per message plus marshal / unmarshal methods that call the primitives
here. The decoder uses the **visitor pattern**, so a generated message is
typically a single `switch` over the field id.

The wire format is specified, language-neutrally, in the
[SofaBuffers documentation](https://github.com/sofa-buffers/documentation). The
unit tests replay the shared, language-agnostic conformance suite
(`assets/test_vectors.json`, copied verbatim from the documentation repo) for both
encode and decode, guaranteeing byte-for-byte interoperability with the C, C++,
Rust and Go implementations.

Maven coordinates: `org.sofabuffers:corelib` · package `org.sofabuffers.sofab`
(the registry artifact is `org.sofabuffers:corelib`; the import namespace stays `sofab`, §6).
The published artifact coordinate is `org.sofabuffers:corelib`.
Requires JDK 17+.

## Why this design

| Goal | How |
|------|-----|
| No per-field allocation | All state lives in caller-provided buffers and small `OStream` / `IStream` objects. Scalars stay primitive (`long` / `double`) — no autoboxing, nothing escapes to the heap on the hot path. |
| No reflection, no runtime codegen | Pure method calls; the decoder pushes to a `Visitor` interface rather than reflecting over fields. Suitable for GraalVM native-image and locked-down runtimes. |
| Streaming **out** | `OStream` writes into a small caller buffer and invokes a `FlushSink` (a `@FunctionalInterface`, e.g. `out::write`) whenever it fills, so a message can exceed the buffer — and even RAM. |
| Streaming **in** | `IStream` is a byte-at-a-time state machine fed arbitrary chunks; large string / blob payloads are delivered in pieces to your `Visitor`. |
| Reserve-offset | `new OStream(buf, offset)` leaves room at the front of the buffer for a lower-layer protocol header (saves a copy). |
| Explicit endianness | IEEE-754 values are written / read little-endian with explicit bit shifts, so behaviour is identical on every JVM. |
| Generated-code friendly | A `Visitor` has a default no-op for every field kind, so generated (and hand-written) sinks override only what they need and ignore the rest. |

## Usage

```java
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

// ---- encode (fixed buffer, no per-write allocation) ----
byte[] buf = new byte[64];
OStream os = new OStream(buf);
os.writeUnsigned(1, 42L);
os.writeSigned(2, -7L);
os.writeString(3, "hi");
int used = os.bytesUsed();

// ---- decode (push to your Visitor) ----
class My implements Visitor {
    long a;
    long b;
    @Override public void unsigned(int id, long v) { if (id == 1) a = v; }
    @Override public void signed(int id, long v)   { if (id == 2) b = v; }
    // fp32(), fp64(), string(), blob(), arrayBegin(), sequenceBegin(), ... as needed
}
My sink = new My();
new IStream().feed(buf, 0, used, sink);
```

Encoder and decoder report problems through `SofabException` (which extends
`IOException`, so it composes with Java I/O and a flush sink that does real I/O);
the specific cause is available via `SofabException.error()`.

### Streaming a message larger than the buffer

```java
import java.io.ByteArrayOutputStream;
import org.sofabuffers.sofab.OStream;

byte[] scratch = new byte[16];                 // tiny buffer
ByteArrayOutputStream out = new ByteArrayOutputStream(); // or a socket / file
OStream os = new OStream(scratch, 0, out::write);        // FlushSink = out::write
for (int i = 0; i < 1000; i++) {
    os.writeUnsigned(i, i);
}
os.flush();                                    // push the tail
```

### Reading a large payload in chunks

A `Visitor` receives string / blob payloads as one or more chunks, each carrying
the field `total` length and the byte `offset` of the chunk, so the payload need
never be held in one piece:

```java
new IStream().feed(buf, 0, used, new Visitor() {
    @Override public void blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        // append data[chunkOffset .. chunkOffset+chunkLength) to your sink
    }
});
```

## API summary

**Constants** — `Sofab.API_VERSION` (`1`), `Sofab.ID_MAX` (max field id), `Sofab.ARRAY_MAX`
(max element count / fixlen length).

**Encoder — `OStream`**

- Construct: `new OStream(byte[] buf)`, `new OStream(byte[] buf, int offset)` (reserve a header gap),
  `new OStream(byte[] buf, int offset, FlushSink sink)` (stream past the buffer)
- `writeUnsigned(id, long)`, `writeSigned(id, long)`, `writeBoolean(id, boolean)`
- `writeFp32(id, float)`, `writeFp64(id, double)`, `writeString(id, String)`, `writeBlob(id, byte[])`,
  `writeBlob(id, byte[], from, len)`, `writeFixlen(id, byte[], from, len, FixlenType)`
- `writeArrayUnsigned` / `writeArraySigned` — overloads for `byte[]` / `short[]` / `int[]` / `long[]`
  (u8/u16/u32/u64 and i8/i16/i32/i64); `writeArrayFp32(float[])`, `writeArrayFp64(double[])`.
  Arrays must be non-empty (an empty array raises `SofabError.ARGUMENT`).
- `writeSequenceBegin(id)`, `writeSequenceEnd()` — open / close a nested id scope
- `bytesUsed()`, `flush()`, `bufferSet(byte[], offset)` — drive streaming output through a `FlushSink`

**Decoder — `IStream` + `Visitor`**

- `feed(byte[] data, Visitor)` / `feed(byte[] data, off, len, Visitor)` — accepts arbitrarily small chunks;
  parse state lives inside the `IStream`, so a message may be split across feeds at any byte boundary.

### Read operations

Decoding is **push-based**: there is no per-field "read into this variable" call and no explicit
`skip`. Instead `feed` parses each field and invokes the matching `Visitor` callback; every callback
has a **default no-op**, so a handler implements only the field kinds it wants and *unhandled fields
are skipped automatically* (the equivalent of the C API's "not interested"). The value the decoder
hands the caller is:

| Wire field | `Visitor` callback | Value handed to caller |
|------------|--------------------|------------------------|
| unsigned int | `unsigned(int id, long value)` | `long` (interpret as unsigned 64-bit via `Long.toUnsignedString` / `Long.compareUnsigned`) |
| signed int | `signed(int id, long value)` | `long` (ZigZag already decoded) |
| fp32 | `fp32(int id, float value)` | `float` |
| fp64 | `fp64(int id, double value)` | `double` |
| string | `string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength)` | a window into the caller's input buffer (raw UTF-8, no NUL); delivered in one or more chunks |
| blob | `blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength)` | a window into the caller's input buffer; chunked like `string` |
| unsigned / signed / fp array | `arrayBegin(int id, ArrayKind kind, int count)`, then one scalar / float callback per element (same `id`) | elements delivered individually through `unsigned` / `signed` / `fp32` / `fp64` |
| sequence | `sequenceBegin(int id)` … `sequenceEnd()` | descend / ascend a nested id scope (visitor nesting, not a cursor) |

For string / blob, `total` is the full field length and `offset` is the byte position of the chunk
within the field, so a payload larger than the input chunk (or larger than RAM) never needs to be
held in one piece. An empty string / blob fires its callback once with `total == 0` and
`chunkLength == 0`. Malformed input raises `SofabException` with `SofabError.INVALID_MSG`.

### Allowed types

- **Integers** — `u8`/`u16`/`u32`/`u64` and `i8`/`i16`/`i32`/`i64`. Scalar `writeUnsigned` / `writeSigned`
  always take a full 64-bit `long` (the wire value type is 64-bit); the narrower widths exist only as
  the `byte[]` / `short[]` / `int[]` / `long[]` array overloads, which zero-extend (unsigned) or
  ZigZag (signed) each element. On decode every integer is delivered as a `long`.
- **Floats** — `fp32` = `float`, `fp64` = `double`, written / read little-endian.
- **String / blob** — UTF-8 text and raw bytes; both are `fixlen` fields.
- **Disallowed** — `string` / `blob` are **not** valid as fixlen-array elements: a fixlen array may
  hold only `fp32` or `fp64`. Encoding such an array is not expressible in the API, and decoding one
  raises `SofabError.INVALID_MSG` ("dynamic fixlen array element"). Empty arrays are also rejected on
  encode.

### Memory handling

This is the core of the design: **the library never allocates the payload buffer.** State lives in
caller-provided arrays plus a small fixed `OStream` / `IStream` object; scalars stay primitive
(`long` / `double`) with no autoboxing on the hot path.

**Encode (`OStream`)**

- The caller owns the output `byte[]`; `OStream` writes straight into it via an advancing cursor
  (`bytesUsed()` reports the count). There is **no per-write allocation and the buffer never grows.**
- When the buffer fills: with a `FlushSink`, the accumulated bytes are handed to
  `sink.flush(buffer, 0, used)` and writing resumes at the start of the same buffer — so a message can
  exceed the buffer, or RAM. With **no** sink, a full buffer raises `SofabError.BUFFER_FULL`.
- `flush()` pushes the pending tail to the sink; `bufferSet(byte[], offset)` swaps in a fresh buffer
  (typically from inside the sink). An initial `offset` reserves a front gap for a lower-layer header.
- `writeString` encodes UTF-8 **directly into the output buffer** (a measuring pass for the length
  header, then an emit pass) — it does *not* allocate an intermediate `byte[]` the way
  `String.getBytes` would.

**Decode (`IStream`)**

- `feed` runs a cursor over the caller's input `byte[]`. Scalars and floats are copied immediately and
  passed **by value** (`long` / `double`) to the visitor — nothing escapes to the heap.
- **Strings / blobs are never materialised into a `String` or a fresh `byte[]`.** The decoder hands the
  visitor a *window* into the caller's input array (`data`, `chunkOffset`, `chunkLength`) that is valid
  **only for the duration of the call**; a visitor that needs to retain bytes must copy that range
  itself. No `String` is ever constructed by the decoder.
- **Array elements are not collected into an array**; each is pushed individually through the scalar /
  float callbacks after `arrayBegin`. The caller decides whether (and where) to store them.
- The only heap the decoder uses is a fixed **8-byte scratch buffer** allocated once per `IStream`, used
  to reassemble a single `fp32` / `fp64` value whose bytes were split across two `feed` calls.

Unlike the C decoder there is **no "bind a destination" step**: where C binds a target pointer that is
filled later, the Java port pushes the decoded value to the visitor at the moment it is parsed.

## Feature flags

Unlike the C library's compile-time `SOFAB_DISABLE_*` switches (which strip whole
code paths for tiny microcontrollers), the Java build always ships the **full**
format — there are no build toggles, because the desktop and cloud targets it is
built for are not code-size constrained.

| Feature | State |
|---------|-------|
| `fixlen` (fp32 / fp64, string, blob) | always on |
| `array` (unsigned / signed / fixlen arrays) | always on |
| `sequence` (nested scopes) | always on |
| `fp64` | always on |

The scalar value type is 64-bit (`long` / unsigned `long`), matching the C default
configuration, so the wire image and varint lengths are identical across ports.

## Build & test

```bash
mvn -B verify          # compile, run the JUnit suite, and produce JaCoCo coverage
mvn -B test            # tests only
```

`verify` runs every suite — including the shared conformance vectors — and writes a
JaCoCo report to `target/site/jacoco/` (coverage is gated in CI and surfaced by the
coverage badge above). Test suites in `src/test/java/org/sofabuffers/sofab/`:

- `VectorConformanceTest.java` — replays the shared `assets/test_vectors.json` suite for encode + decode (one dynamic test per vector)
- `OStreamTest.java` / `IStreamTest.java` — byte-exact encoder / decoder checks, malformed input, byte-at-a-time feeding
- `RoundTripTest.java` — encode → decode value preservation
- `ApiTest.java` — offset reserve, flush-sink streaming larger than the buffer, chunked decode
- `SkipTest.java` — skipping fields and whole sub-sequences with correct resync
- `EncoderOverloadsTest.java` / `DecoderErrorsTest.java` / `StreamingEdgeTest.java` / `VisitorDefaultsTest.java` — overloads, error paths, edge cases
- `common/RecordingVisitor.java` — shared recording `Visitor`

## Benchmarks

Two tools mirror the C / C++ / Rust / Go `perf` and `bench` tooling — same
workloads (a 1000-element `u64` array and a "typical" mixed message) and the same
output format — so results are comparable across languages:

```bash
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf   # per-op cost
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench  # throughput (MB/s)
```

- **`Perf`** reports per-operation cost. The JVM exposes no portable hardware cycle
  counter, so — unlike the C / Rust tools on x86 / AArch64 — `Perf` reports
  thread-CPU-time ns/op (the machine-independent signal it can measure) and notes
  that cycles/op is unavailable. For a genuinely CPU-speed-independent number, run
  `Perf` under an external counter (e.g. `perf stat -e instructions:u …`); see
  [`BENCH_SPEC.md`](BENCH_SPEC.md).
- **`Bench`** reports encode / decode throughput in MB/s (MB = 1e6 bytes) over a
  ~1 s CPU-time loop.

The exact workloads, timing rules, throughput formula, and output grammar are the
single source of truth in [`BENCH_SPEC.md`](BENCH_SPEC.md), so the numbers stay
comparable across the language ports.

## Layering vs. the C library

| C file | Java type | Status |
|--------|-----------|--------|
| `sofab.h` (types / constants) | `Sofab`, `SofabError`, `FixlenType`, `ArrayKind`, `WireFormat` | ported |
| `ostream.c` | `OStream` (+ `FlushSink`) | ported |
| `istream.c` | `IStream` + `Visitor` | ported (push / visitor model instead of bind-target callbacks) |
| `object.c` (descriptor transcoder) | — | not ported. The idiomatic Java equivalent is generated message classes — a schema-driven generator emitting `Visitor` / encode glue; the streaming core above already covers serialize / deserialize. |
