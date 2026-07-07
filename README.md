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
the SofaBuffers (*Sofab*) serialization format. It is the **runtime stream core** —
a port of the C `corelib`'s `ostream.c` / `istream.c` — that runs anywhere a JVM
does, from desktops and servers to cloud containers.

Like protobuf-java's `CodedInputStream` / `CodedOutputStream`, this library is
meant to be driven by **generated code**: a schema-driven generator emits one
class per message plus marshal / unmarshal methods that call the primitives here.
The decoder uses the **visitor pattern**, so a generated message is typically a
single `switch` over the field id.

The wire format is specified, language-neutrally, in the
[SofaBuffers documentation](https://github.com/sofa-buffers/documentation). The
unit tests replay the shared, language-agnostic conformance suite
(`assets/test_vectors.json`, copied verbatim from the documentation repo) for both
encode and decode, guaranteeing byte-for-byte interoperability with the C, C++,
Rust and Go implementations.

### Package name

Maven coordinates `org.sofabuffers:corelib` (version `0.1.0`); the import
namespace is the package `org.sofabuffers.sofab`.

```xml
<dependency>
  <groupId>org.sofabuffers</groupId>
  <artifactId>corelib</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Requirements

- **JDK 17+** (the build sets `maven.compiler.release` to 17; CI additionally
  runs the suite on the current LTS, 21).

### Dependencies

- **Runtime: none.** The published library depends only on the Java standard
  library.
- **Test-only:** JUnit 5 (Jupiter) and Gson — Gson is used solely to parse the
  shared `test_vectors.json` conformance suite with exact `u64` precision, and is
  never on the runtime classpath.

## Why this design

| Goal | How |
|------|-----|
| No per-field allocation | All state lives in caller-provided buffers plus small `OStream` / `IStream` objects. Scalars stay primitive (`long` / `double`) — no autoboxing, nothing escapes to the heap on the hot path. |
| No reflection, no runtime codegen | Pure method calls; the decoder pushes to a `Visitor` interface rather than reflecting over fields. Suitable for GraalVM native-image and locked-down runtimes. |
| Streaming **out** | `OStream` writes into a small caller buffer and invokes a `FlushSink` (a `@FunctionalInterface`, e.g. `out::write`) whenever it fills, so a message can exceed the buffer — and even RAM. |
| Streaming **in** | `IStream` accepts arbitrarily small chunks; a message may be split across `feed` calls at any byte boundary. A resumable state machine finishes fields split across a boundary; large string / blob payloads are delivered to your `Visitor` in pieces. |
| Reserve-offset | `new OStream(buf, offset)` leaves room at the front of the buffer for a lower-layer protocol header (saves a copy). |
| Explicit endianness | IEEE-754 values are written / read little-endian with explicit bit shifts, so behaviour is identical on every JVM. |
| Generated-code friendly | Every `Visitor` method has a default no-op, so generated (and hand-written) sinks override only what they need and ignore the rest. |

## Usage

Every encode / decode call can throw `SofabException` (which extends
`IOException`); the snippets below elide the `throws IOException` for brevity.

### Simple encode

```java
import org.sofabuffers.sofab.OStream;

byte[] buf = new byte[64];
OStream os = new OStream(buf);        // caller owns the buffer
os.writeUnsigned(1, 42L);
os.writeSigned(2, -7L);
os.writeString(3, "hi");
int used = os.bytesUsed();            // bytes written to buf
```

### Simple decode

Decoding is push-based: implement a `Visitor` and only override the field kinds
you care about (every method defaults to a no-op, so unknown fields are skipped).

```java
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.Visitor;

class My implements Visitor {
    long a, b;
    @Override public void unsigned(int id, long v) { if (id == 1) a = v; }
    @Override public void signed(int id, long v)   { if (id == 2) b = v; }
    // fp32(), fp64(), string(), blob(), arrayBegin(), sequenceBegin(), ... as needed
}
My sink = new My();
new IStream().feed(buf, 0, used, sink);
```

### Streaming a message larger than the buffer (OStream)

`OStream` is the streaming **output** primitive. Give it a `FlushSink` and it
writes into a small window, handing each full buffer to the sink and resuming at
the buffer's start — so the message never has to fit in RAM. `out::write` on any
`java.io.OutputStream` satisfies `FlushSink` directly:

```java
import java.io.OutputStream;
import org.sofabuffers.sofab.OStream;

OutputStream out = /* a socket, file, ... */;
byte[] window = new byte[16];                       // tiny buffer
OStream os = new OStream(window, 0, out::write);    // FlushSink = out::write
for (int i = 0; i < 1000; i++) {
    os.writeUnsigned(i, i);
}
os.flush();                                         // push the tail
```

### Streaming a message in from an InputStream (IStream)

`IStream` is the streaming **input** primitive. Because all parse state lives
inside it, you can `feed` it whatever chunks an `InputStream` returns — even one
byte at a time — and it resumes across the boundaries:

```java
import java.io.InputStream;
import org.sofabuffers.sofab.IStream;

InputStream in = /* a socket, file, ... */;
IStream is = new IStream();
byte[] chunk = new byte[4096];
int n;
while ((n = in.read(chunk)) != -1) {
    is.feed(chunk, 0, n, sink);       // sink is your Visitor
}
```

A `Visitor` receives string / blob payloads as one or more chunks, each carrying
the field `total` length and the byte `offset` of the chunk, so a payload larger
than the input chunk (or larger than RAM) never needs to be held in one piece:

```java
new IStream().feed(buf, 0, used, new Visitor() {
    @Override public void blob(int id, int total, int offset,
                               byte[] data, int chunkOffset, int chunkLength) {
        // append data[chunkOffset .. chunkOffset+chunkLength) to your sink
    }
});
```

### Generator usage (generated object code)

The most common real use is driving the runtime through **generated code**: the
schema compiler emits one class per message whose `encode` / `decode` methods call
the primitives above. A generated class looks essentially like this (here written
by hand for a two-field `Point` message):

```java
final class Point {
    long x, y;

    void encode(OStream os) throws IOException {   // marshal
        os.writeSigned(1, x);
        os.writeSigned(2, y);
    }

    static Point decode(IStream is, byte[] data, int off, int len) throws IOException {
        Point p = new Point();
        is.feed(data, off, len, new Visitor() {    // unmarshal: one switch on id
            @Override public void signed(int id, long v) {
                switch (id) {
                    case 1 -> p.x = v;
                    case 2 -> p.y = v;
                    default -> { }                 // unknown field: ignore
                }
            }
        });
        return p;
    }
}
```

The decoder's default no-op callbacks are what keep a generated `Visitor` down to
a single `switch`. See `src/test/java/org/sofabuffers/sofab/common/RecordingVisitor.java`
for a fuller worked visitor.

## API summary

**Constants** (`org.sofabuffers.sofab.Sofab`) — `API_VERSION` (`1`),
`ID_MAX` (`2^31 - 1`, the largest field id), `ARRAY_MAX` (`2^31 - 1`, the largest
array element count / fixlen byte length), and `MAX_DEPTH` (`255`, the deepest
nested-sequence level the encoder and decoder allow).

### Encoding — `OStream`

`OStream` wraps a caller-owned `byte[]` and exposes typed `write*` methods that
each emit one field: `writeUnsigned` / `writeSigned` / `writeBoolean` (64-bit
varints), `writeFp32` / `writeFp64`, `writeString` (UTF-8, no NUL) and
`writeBlob`, plus `writeFixlen` for a raw fixed-length payload. Integer and float
**arrays** are written whole through overloaded `writeArrayUnsigned` /
`writeArraySigned` (accepting `byte[]` / `short[]` / `int[]` / `long[]`, one
overload per width) and `writeArrayFp32` / `writeArrayFp64`; an **empty array is
valid** and encodes to just its header (a fixlen array still carries its element
`fixlen_word`). `writeSequenceBegin` / `writeSequenceEnd` open and close nested id
scopes. `bytesUsed`, `flush` and `bufferSet` drive streaming output.

Errors are reported by throwing `SofabException` (carrying a `SofabError`), not
by a sticky per-call result: `ARGUMENT` for a bad argument (a negative field id,
a negative length/count), `USAGE` for a misuse (`writeSequenceEnd` with no open
sequence), and `BUFFER_FULL` when the buffer fills and no `FlushSink` is set.
Because `SofabException extends IOException`, encode code composes with a sink
that does real I/O.

### Decoding — `IStream` + `Visitor`

Decoding is **push / visitor-based**, not pull: `feed(data, visitor)` or
`feed(data, off, len, visitor)` parses each field and invokes the matching
`Visitor` callback. There is no per-field "read into this variable" call and no
explicit `skip` — every `Visitor` method has a **default no-op**, so a handler
implements only the field kinds it wants and **unknown fields are skipped
automatically**. Internally the decoder takes a fast pointer-advancing path when a
whole field (or array element) is already in the buffer, and falls back to a
resumable byte-at-a-time state machine only across a `feed` boundary; the two
paths are byte-for-byte equivalent.

| Wire field | `Visitor` callback | Value handed to caller |
|------------|--------------------|------------------------|
| unsigned int | `unsigned(int id, long value)` | `long` (interpret as unsigned via `Long.toUnsignedString` / `Long.compareUnsigned`) |
| signed int | `signed(int id, long value)` | `long` (ZigZag already decoded) |
| fp32 / fp64 | `fp32(int id, float)` / `fp64(int id, double)` | `float` / `double` |
| string / blob | `string(...)` / `blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength)` | a **view** into the caller's input buffer (raw UTF-8, no NUL), delivered in one or more chunks |
| int / fp array | `arrayBegin(int id, ArrayKind kind, int count)`, then one scalar / float callback per element (same `id`) | elements delivered individually through `unsigned` / `signed` / `fp32` / `fp64` |
| sequence | `sequenceBegin(int id)` … `sequenceEnd()` | descend / ascend a nested id scope |

An empty string / blob fires its callback once with `total == 0` and
`chunkLength == 0`; an empty array fires `arrayBegin` once with `count == 0` and
no element callbacks. Malformed input raises `SofabException` with
`SofabError.INVALID_MSG` (varint overflow, bad type tag, dangling sequence end,
over-deep nesting, a string/blob used as a fixlen-array element, …).

**Types.** Integers are `u8/u16/u32/u64` and `i8/i16/i32/i64` on the wire, but
scalar `writeUnsigned` / `writeSigned` always take a full 64-bit `long` (the
narrower widths exist only as the array overloads, which zero-extend or ZigZag
each element) and every integer is decoded back as a `long`. Floats are `fp32`
(`float`) and `fp64` (`double`), little-endian. `string` and `blob` are
fixed-length fields; a **fixlen array** may hold only `fp32` or `fp64` elements —
`string` / `blob` are not valid as array elements (decoding one raises
`INVALID_MSG`).

### Memory handling

This is the core of the design: **the library never allocates the payload
buffer.** The API is `byte[]`-based throughout (there is no `ByteBuffer`
surface); state lives in caller-provided arrays plus a small fixed
`OStream` / `IStream` object, and scalars stay primitive with no autoboxing on the
hot path.

- **Output buffer (`OStream`).** The caller owns and sizes the output `byte[]`;
  the encoder writes straight into it with an advancing cursor and **never grows
  it** — there is no per-write allocation. When the buffer fills, a `FlushSink`
  (if set) receives the accumulated bytes via `flush(buffer, 0, used)` and writing
  resumes at the start of the *same* buffer, so a message can exceed the buffer or
  RAM; with no sink, a full buffer raises `BUFFER_FULL`. The sink's array is
  reused after the call returns, so a sink that needs to keep the bytes must copy
  them. `writeString` encodes UTF-8 **directly into the output buffer** (a
  measuring pass then an emit pass) rather than allocating an intermediate
  `byte[]` the way `String.getBytes` would.
- **Input buffer (`IStream`).** `feed` runs a cursor over the caller's input
  `byte[]`; the decoder **aliases** it, it never copies it wholesale. Scalars and
  floats are passed **by value** (`long` / `double`). Strings and blobs are handed
  to the visitor as a **window** (`data`, `chunkOffset`, `chunkLength`) into that
  input array that is valid **only for the duration of the callback** — no
  `String` and no fresh `byte[]` is ever constructed by the decoder; a visitor
  that needs to retain bytes must copy the range itself. Array elements are pushed
  individually, never collected into an array.
- **Message object.** There is none. Unlike the C decoder's "bind a destination"
  step, the Java port pushes each decoded value to the visitor the moment it is
  parsed. The only heap the decoder allocates is a fixed **8-byte scratch buffer**
  per `IStream`, used to reassemble a single `fp32` / `fp64` whose bytes were split
  across two `feed` calls.

## Feature flags

**None — the Java build always ships the full format.** Unlike the C library's
compile-time `SOFAB_DISABLE_*` switches (which strip code paths for tiny
microcontrollers), there are no build toggles here: `fixlen` (fp32 / fp64, string,
blob), `array` (unsigned / signed / fixlen arrays), `sequence` nesting and `fp64`
are always on, because the desktop and cloud targets this port serves are not
code-size constrained. The scalar value type is 64-bit (`long` / unsigned `long`),
matching the C default configuration, so the wire image and varint lengths are
identical across ports.

## Build & test

```bash
mvn -B verify          # compile, run the JUnit suite, and produce JaCoCo coverage
mvn -B test            # tests only
```

`verify` runs every suite — including the shared conformance vectors — and writes a
JaCoCo report to `target/site/jacoco/` (coverage is gated in CI and surfaced by the
coverage / branches badges above). The test suites live in
`src/test/java/org/sofabuffers/sofab/` and cover the shared `test_vectors.json`
suite (`VectorConformanceTest`), byte-exact encode/decode and byte-at-a-time
feeding (`OStreamTest` / `IStreamTest`), round-trip value preservation
(`RoundTripTest`), offset reserve and flush-sink streaming (`ApiTest`),
skip / resync (`SkipTest`), and the overload, error-path, edge-case and
visitor-default suites.

## Benchmarks

Two runnable tools mirror the C / C++ / Rust / Go `perf` and `bench` tooling —
same workloads (a 1000-element `u64` array and a "typical" mixed message) and the
same output format — so results are comparable across languages:

```bash
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf   # per-op cost
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench  # throughput (MB/s)
```

- **`Perf`** reports per-operation cost. The JVM exposes no portable hardware
  cycle counter, so — unlike the C / Rust tools on x86 / AArch64 — `Perf` reports
  thread-CPU-time ns/op and notes that cycles/op is unavailable. For a
  CPU-speed-independent number, run `Perf` under an external counter (e.g.
  `perf stat -e instructions:u …`).
- **`Bench`** reports encode / decode throughput in MB/s (MB = 1e6 bytes) over a
  ~1 s CPU-time loop.

The exact workloads, timing rules, throughput formula, and output grammar are the
single source of truth in [`BENCH_SPEC.md`](BENCH_SPEC.md), so the numbers stay
comparable across the language ports.
