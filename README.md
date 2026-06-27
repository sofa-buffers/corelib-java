<p align="center"><img src="assets/sofabuffers_logo.png" alt="SofaBuffers" height="140"></p>

# SofaBuffers

**Structured Objects For Anyone** \
*... so optimized, feels amazing.*

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

Maven coordinates: `org.sofabuffers:sofab` · package `org.sofabuffers.sofab`.
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

**Constants** — `Sofab.API_VERSION` (`1`), `Sofab.ID_MAX`, `Sofab.ARRAY_MAX`.

**Encoder — `OStream`**

- `writeUnsigned(id, long)`, `writeSigned(id, long)`, `writeBoolean(id, boolean)`
- `writeFp32(id, float)`, `writeFp64(id, double)`, `writeString(id, String)`, `writeBlob(id, byte[])`, `writeFixlen(id, byte[], from, len, FixlenType)`
- `writeArrayUnsigned` / `writeArraySigned` — overloads for `byte[]` / `short[]` / `int[]` / `long[]`; `writeArrayFp32(float[])`, `writeArrayFp64(double[])`
- `writeSequenceBegin(id)`, `writeSequenceEnd()`
- `bytesUsed()`, `flush()`, `bufferSet(byte[], offset)` — drive streaming output through a `FlushSink`

**Decoder — `IStream` + `Visitor`**

- `feed(byte[] data, Visitor)` / `feed(byte[] data, off, len, Visitor)` — accepts arbitrarily small chunks
- `Visitor` callbacks (all default no-ops, so a handler reads only what it cares about and skips the rest): `unsigned`, `signed`, `fp32`, `fp64`, `string`, `blob` (string / blob delivered in chunks), `arrayBegin`, `sequenceBegin`, `sequenceEnd`

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
  that cycles/op is unavailable.
- **`Bench`** reports encode / decode throughput in MB/s (MB = 1e6 bytes) over a
  ~1 s CPU-time loop.

## Layering vs. the C library

| C file | Java type | Status |
|--------|-----------|--------|
| `sofab.h` (types / constants) | `Sofab`, `SofabError`, `FixlenType`, `ArrayKind`, `WireFormat` | ported |
| `ostream.c` | `OStream` (+ `FlushSink`) | ported |
| `istream.c` | `IStream` + `Visitor` | ported (push / visitor model instead of bind-target callbacks) |
| `object.c` (descriptor transcoder) | — | not ported. The idiomatic Java equivalent is generated message classes — a schema-driven generator emitting `Visitor` / encode glue; the streaming core above already covers serialize / deserialize. |
