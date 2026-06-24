<p align="center"><img src="assets/sofabuffers_logo.png" alt="SofaBuffers Logo" height="140"></p>

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
unit tests here use the exact byte vectors from the
[C corelib](https://github.com/sofa-buffers/corelib-c-cpp)'s reference suite
(`test/c/test_ostream.c`) to guarantee byte-for-byte interoperability with the C,
C++, Rust and Go implementations.

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

## Source documentation

[Documentation](https://sofa-buffers.github.io/corelib-java/) — Javadoc HTML for
the package, generated and published to GitHub Pages on every push to `main`.

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

## Format coverage

The Java build always includes the full format — unsigned / signed varints,
`fp32` / `fp64`, strings, blobs, arrays and nested sequences — because the
desktop and cloud targets it is built for are not code-size constrained. The C
library's compile-time `SOFAB_DISABLE_*` switches (which strip whole code paths
for tiny microcontrollers) therefore have no Java equivalent. The value type is
64-bit (`long` / unsigned `long`), matching the C default configuration so the
wire image and varint lengths are identical.

## Layering vs. the C library

| C file | Java type | Status |
|--------|-----------|--------|
| `sofab.h` (types / constants) | `SofabError`, `FixlenType`, `ArrayKind`, `WireFormat` | ported |
| `ostream.c` | `OStream` (+ `FlushSink`) | ported |
| `istream.c` | `IStream` + `Visitor` | ported (push / visitor model instead of bind-target callbacks) |
| `object.c` (descriptor transcoder) | — | not ported. The idiomatic Java equivalent is generated message classes — a schema-driven generator emitting `Visitor` / encode glue; the streaming core above already covers serialize / deserialize. |

## Testing

```bash
mvn test
```

Tests live in `src/test/java/org/sofabuffers/sofab/` as focused suites:

- `OStreamTest.java` — encoder, byte-exact vs. the C reference vectors
- `IStreamTest.java` — decoder over the same vectors + malformed-input errors + byte-at-a-time feeding
- `RoundTripTest.java` — encode → decode value preservation (scalars, arrays, strings/blobs, sequences)
- `ApiTest.java` — offset reserve, flush-sink streaming larger than the buffer, chunked decode
- `common/RecordingVisitor.java` — shared recording `Visitor`
