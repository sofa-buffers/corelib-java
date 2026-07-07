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
the SofaBuffers (*Sofab*) serialization format — the runtime stream core, runnable
anywhere a JVM does.

Like protobuf-java's `CodedInputStream` / `CodedOutputStream`, this library is
driven by **generated code**: a schema-driven generator emits one class per message
plus marshal / unmarshal methods that call the primitives here. Decoding uses the
**visitor pattern**, so a generated message is typically a single `switch` over the
field id. The wire format is specified language-neutrally in the

### Package name

Maven coordinates `org.sofabuffers:corelib` (version `0.1.0`); the import namespace
is the package `org.sofabuffers.sofab`.

```xml
<dependency>
  <groupId>org.sofabuffers</groupId>
  <artifactId>corelib</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Requirements

- **JDK 17+** (the build targets release 17; CI also runs on LTS 21).

### Dependencies

- **Runtime: none** — only the Java standard library.
- **Test-only:** JUnit 5 (Jupiter) and Gson (used to parse `test_vectors.json` with
  exact `u64` precision; never on the runtime classpath).

## Why this design

| Goal | How |
|------|-----|
| No per-field allocation | State lives in caller-provided buffers plus small `OStream` / `IStream` objects. Scalars stay primitive (`long` / `double`) — no autoboxing on the hot path. |
| No reflection, no runtime codegen | Pure method calls; the decoder pushes to a `Visitor` interface. Suitable for GraalVM native-image and locked-down runtimes. |
| Streaming **out** | `OStream` writes into a small caller buffer and invokes a `FlushSink` whenever it fills, so a message can exceed the buffer — and even RAM. |
| Streaming **in** | `IStream` accepts arbitrarily small chunks; a message may split across `feed` calls at any byte boundary, and large string / blob payloads arrive in pieces. |
| Reserve-offset | `new OStream(buf, offset)` leaves room at the front for a lower-layer protocol header, saving a copy. |
| Explicit endianness | IEEE-754 values are written / read little-endian with explicit bit shifts, so behaviour is identical on every JVM. |
| Generated-code friendly | Every `Visitor` method has a default no-op, so sinks override only what they need. |

## Usage

Every encode / decode call can throw `SofabException` (which extends
`IOException`); the snippets below elide `throws IOException`.

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

Decoding is push-based: implement a `Visitor` and override only the field kinds you
care about (every method defaults to a no-op, so unknown fields are skipped).

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

Give `OStream` a `FlushSink` and it writes into a small window, handing each full
buffer to the sink and resuming at the buffer's start — so the message never has to
fit in RAM. `out::write` on any `java.io.OutputStream` satisfies `FlushSink`
directly:

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

Because all parse state lives inside `IStream`, you can `feed` it whatever chunks an
`InputStream` returns — even one byte at a time — and it resumes across boundaries.
A `Visitor` receives string / blob payloads as one or more chunks, each carrying the
field `total` length and the chunk `offset`, so a payload larger than the input
chunk (or larger than RAM) never needs to be held in one piece:

```java
import java.io.InputStream;
import org.sofabuffers.sofab.IStream;

InputStream in = /* a socket, file, ... */;
IStream is = new IStream();
byte[] chunk = new byte[4096];
int n;
while ((n = in.read(chunk)) != -1) {
    is.feed(chunk, 0, n, new Visitor() {
        @Override public void blob(int id, int total, int offset,
                                   byte[] data, int chunkOffset, int chunkLength) {
            // append data[chunkOffset .. chunkOffset+chunkLength) to your sink
        }
    });
}
```

### Generated object code

The common real use is driving the runtime through **generated code**: the schema
compiler emits one class per message whose `encode` / `decode` methods call the
primitives above. A generated class looks essentially like this (a two-field `Point`):

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

See `src/test/java/org/sofabuffers/sofab/common/RecordingVisitor.java` for a fuller
worked visitor.

## Memory handling

The library never allocates the payload buffer; the API is `byte[]`-based
throughout, with state in caller-provided arrays plus a small fixed object.

- **Encode (`OStream`).** The caller owns and sizes the output `byte[]`; the encoder
  writes straight in with an advancing cursor and **never grows it**. When the buffer
  fills, a `FlushSink` (if set) receives the bytes and writing resumes at the start
  of the *same* buffer — so a message can exceed the buffer or RAM; with no sink, a
  full buffer raises `BUFFER_FULL`. The sink's array is reused after the call
  returns, so a sink that keeps the bytes must copy them. (`writeString` encodes
  UTF-8 **directly into the buffer**, with no intermediate `byte[]`.)
- **Decode (`IStream` + `Visitor`).** `feed` runs a cursor over the caller's input
  `byte[]`, **aliasing** it. Scalars and floats are passed **by value** (`long` /
  `double`); strings and blobs are handed to the visitor as a **window** (`data`,
  `chunkOffset`, `chunkLength`) into that array, valid **only for the duration of the
  callback** — no `String` or fresh `byte[]` is constructed, so a visitor that
  retains bytes must copy the range itself.

## Feature flags

**None** — the build always ships the full format.

## Build & test

```bash
mvn -B verify          # compile, run the JUnit suite, and produce JaCoCo coverage
mvn -B test            # tests only
```

`verify` runs every suite — including the shared conformance vectors — and writes a
JaCoCo report to `target/site/jacoco/` (coverage is gated in CI). The suites live in
`src/test/java/org/sofabuffers/sofab/`.

## Benchmarks

Two runnable tools mirror the other ports' `perf` and `bench` tooling — same
workloads (a 1000-element `u64` array and a mixed message) and output format, so
results are comparable across languages:

```bash
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf   # per-op cost
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench  # throughput (MB/s)
```

`Perf` reports thread-CPU-time ns/op (the JVM exposes no portable cycle counter; run
under an external counter such as `perf stat -e instructions:u …` for a
CPU-speed-independent number). `Bench` reports encode / decode throughput in MB/s
over a ~1 s CPU-time loop. The exact workloads and output grammar are specified in
