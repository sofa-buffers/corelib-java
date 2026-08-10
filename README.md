<p align="center"><img src="assets/sofabuffers_logo.png" alt="SofaBuffers" height="140"></p>

# SofaBuffers

<b>Structured Objects For Anyone</b><br>
<i>... so optimized, feels amazing.</i>

[Would you like to know more?](https://github.com/sofa-buffers)

## SofaBuffers Java library

[![CI](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Coverage](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-java%2Fbadges%2Fcoverage.json)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Branches](https://img.shields.io/endpoint?url=https%3A%2F%2Fraw.githubusercontent.com%2Fsofa-buffers%2Fcorelib-java%2Fbadges%2Fbranches.json)](https://github.com/sofa-buffers/corelib-java/actions/workflows/ci.yml)
[![Docs](https://img.shields.io/badge/docs-javadoc-blue)](https://sofa-buffers.github.io/corelib-java/)

[GitHub repository](https://github.com/sofa-buffers/corelib-java)

A **dependency-free**, **allocation-light**, **streaming** Java implementation of
the SofaBuffers (*Sofab*) serialization format — the runtime stream core, runnable
anywhere a JVM does.

Like protobuf-java's `CodedInputStream` / `CodedOutputStream`, this library is
driven by **generated code**: a schema-driven generator emits one class per message
plus the `serialize` / `deserialize` pair that calls the primitives here. Decoding
uses the **visitor pattern**, so a generated message is typically a single `switch`
over the field id. The wire format is specified language-neutrally in the
[SofaBuffers documentation](https://github.com/sofa-buffers/documentation).

### Package name

Maven coordinates `org.sofabuffers:corelib` (version `0.10.0`); the import namespace
is the package `org.sofabuffers.sofab`.

```xml
<dependency>
  <groupId>org.sofabuffers</groupId>
  <artifactId>corelib</artifactId>
  <version>0.10.0</version>
</dependency>
```

### Requirements

- **JDK 17+** (the build targets release 17; CI builds on JDK 17, 21 and 25).

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
| Streaming **in** | `IStream` accepts arbitrarily small chunks; a message may split across `feed` calls at any byte boundary, and large string / blob payloads arrive in pieces. Malformed bytes throw `SofabException` (`INVALID_MSG`); running out of bytes mid-field is **not** an error — `feed` suspends and resumes on the next chunk. Call `status()` after the final `feed` to tell a `COMPLETE` message from a truncated `INCOMPLETE` one (MESSAGE_SPEC §7); it never throws and needs no finish/finalize step. A rejection is **terminal** (CORELIB_PLAN §5.2): `status()` reports `INVALID` from then on and every further `feed` rethrows `INVALID_MSG` without decoding, so a caller that catches the exception and keeps feeding cannot resume mid-stream on a message already proven malformed — `reset()` starts the next one. |
| Sparse sequence framing, still one pass | `writeSequenceBeginLazy` holds a sequence header back until a child field is actually written, so a sequence-typed **field** that receives no content is omitted rather than framed empty (MESSAGE_SPEC §2) — decided in a single forward pass, with no sub-message buffering. Held-back ids are encoder state, not buffer content, so a tiny output buffer still produces the one-shot bytes. `writeSequenceEnd` drops such a sequence; `writeSequenceEndKeep` forces the frame out where it carries information — a wrapper-array **element** is still always framed, because its presence is what gives the array its length (§5.1). The pending run grows on demand to the full `MAX_DEPTH` (255), so the output is canonical at every legal nesting depth: no fixed window, no eager fallback (CORELIB_PLAN §6 reserves that allowance for heap-free profiles). |
| Reserve-offset | `new OStream(buf, offset)` leaves room at the front for a lower-layer protocol header, saving a copy. |
| Explicit endianness | IEEE-754 values are written / read little-endian with explicit bit shifts, so behaviour is identical on every JVM. |
| Generated-code friendly | Every `Visitor` method has a default no-op, so sinks override only what they need. |

## Usage

The codec has four use cases — serialize a message that fits in one buffer,
serialize one too large for the buffer (streamed out in chunks), deserialize a
whole message, and deserialize one arriving in chunks — plus the generated-code
path that wraps them. Every encode / decode call can throw `SofabException` (which
extends `IOException`); the snippets below elide `throws IOException`.

### Serialize

Write fields into a caller-owned `byte[]` sized to hold the whole message, then
read the byte count:

```java
import org.sofabuffers.sofab.OStream;

byte[] buf = new byte[64];
OStream os = new OStream(buf);        // caller owns the buffer
os.writeUnsigned(1, 42L);
os.writeSigned(2, -7L);
os.writeString(3, "hi");
int used = os.bytesUsed();            // bytes written to buf
```

### Serialize stream

Give `OStream` a `FlushSink` and it writes into a small window, handing each full
buffer to the sink and resuming at the buffer's start — so the message never has
to fit in RAM. `out::write` on any `java.io.OutputStream` satisfies `FlushSink`:

```java
import java.io.OutputStream;
import org.sofabuffers.sofab.OStream;

OutputStream out = /* a socket, file, ... */;
byte[] window = new byte[16];                       // tiny buffer
OStream os = new OStream(window, 0, out::write);    // FlushSink = out::write
for (int i = 0; i < 1000; i++)
    os.writeUnsigned(i, i);
os.flush();                                         // push the tail
```

A sink that *takes* the buffer instead of copying out of it — a zero-copy
transport — must hand the encoder a replacement before it returns, and the cursor
then starts at that installation's offset. That is also how a sink reserves
framing-header room in **every** flushed unit, since the offset belongs to the
installation and is consumed by the flush it was made in:

```java
byte[][] window = { fresh(16) };                    // fresh() reserves 3 header bytes
OStream[] self = new OStream[1];
OStream os = new OStream(window[0], 3, (data, off, len) -> {
    transport.send(data, off, len);                 // takes the array
    self[0].bufferSet(fresh(16), 3);                // re-arms 3 bytes in the next unit
});
self[0] = os;
```

Returning **without** `bufferSet` means the sink copied: the same buffer stays
active and writing resumes at offset 0.

### Deserialize

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
// fixlenBegin(id, subtype, total) announces a string/blob/float field at its
// length word, before any payload byte, so a length bound can be rejected there.
My sink = new My();
new IStream().feed(buf, 0, used, sink);
```

### Deserialize stream

Because all parse state lives inside `IStream`, `feed` it whatever chunks arrive —
even one byte at a time — and it resumes across boundaries. A `Visitor` receives
string / blob payloads as one or more chunks tagged with the field `total` length
and chunk `offset`, so a payload never needs to be held in one piece:

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

**Chunking costs only what straddles.** Whenever a whole field is in hand the decoder
advances a pointer straight over the buffer; only a field, array element or
`fixlen_word` that would run past the end of the supplied bytes goes through the
resumable byte-at-a-time machine, and the moment that one construct completes the
rest of the chunk goes back to the bulk path — inside an array too, not just between
fields. A boundary anywhere in a 100 000-element array therefore costs one element,
not the 99 999 after it, so chunked decoding runs at one-shot speed wherever the
chunks happen to fall (`fp64` array in 4 KiB chunks: 31.6 → 0.9 ns/element). Feed
whatever sizes your transport produces.

### Code generator

The common real use is driving the runtime through **generated code**: `sofabgen`
emits one class per message whose whole wire surface is the family-wide name set —
`serialize(OStream)` writes the fields into a stream the caller owns, `byte[]
encode()` wraps it over a `MAX_SIZE` buffer, `decode(byte[])` (and its
status-returning sibling `tryDecode(byte[], out)`) wraps the decoder over one
complete buffer, and `decoder()` hands back the streaming reader. **Both halves are
the same code path**: the one-shot pair is a thin wrapper over the streaming one,
which is why a message that streams and a message that fits in a buffer produce
identical bytes.

A hand-written stand-in — the generator emits the visitor as its own class, folded
into the message here for brevity — encoded, decoded, then decoded again in chunks:

```java
import java.io.IOException;
import java.util.Arrays;
import org.sofabuffers.sofab.*;

// generated by: sofabgen --lang java
final class Point implements Visitor {
    long x, y;
    static final int MAX_SIZE = 32;

    // streaming out: write the fields into a stream the caller owns
    public void serialize(OStream os) throws IOException {
        os.writeSigned(1, x);
        os.writeSigned(2, y);
    }

    // one-shot: serialize into a MAX_SIZE buffer, hand back an exact-size copy
    public byte[] encode() {
        byte[] buf = new byte[MAX_SIZE];
        OStream os = new OStream(buf);
        try { serialize(os); } catch (IOException e) { throw new RuntimeException(e); }
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    // one-shot: the same streaming decode, over one complete buffer
    public static Point decode(byte[] data) throws SofabException {
        Point p = new Point();
        new IStream().feed(data, p);
        return p;
    }

    // streaming in: a reader fed chunks of any size
    public static Decoder decoder() { return new Decoder(); }

    static final class Decoder {
        private final Point p = new Point();
        private final IStream is = new IStream();

        public DecodeStatus feed(byte[] chunk, int off, int len) throws SofabException {
            is.feed(chunk, off, len, p);
            return is.status();          // COMPLETE / INCOMPLETE; INVALID throws
        }

        public Point message() { return p; }
    }

    @Override public void signed(int id, long v) {
        switch (id) { case 1 -> x = v; case 2 -> y = v; default -> { } }
    }
}

Point p = new Point(); p.x = 3; p.y = 4;
byte[] wire = p.encode();
Point got = Point.decode(wire);                 // got.x == 3, got.y == 4

// the streaming half: the same message, one byte at a time
Point.Decoder dec = Point.decoder();
for (byte b : wire) dec.feed(new byte[] { b }, 0, 1);
Point streamed = dec.message();                 // streamed.x == 3, streamed.y == 4
```

`feed` returns the outcome for the bytes seen so far — `COMPLETE` on a field
boundary, `INCOMPLETE` mid-field, and malformed bytes throw (`INVALID`, terminal).
The top level has no end marker, so *the caller's* framing decides when the input is
over; a still-`INCOMPLETE` status at that point is a truncated message. Give the
encoder's `OStream` a `FlushSink` and the same `serialize` streams a message larger
than the buffer, so neither direction ever needs the whole message in RAM.

## Memory handling

The library never allocates the payload buffer; the API is `byte[]`-based
throughout, with state in caller-provided arrays plus a small fixed object.

- **Encode (`OStream`).** The caller owns and sizes the output `byte[]`; the encoder
  writes straight in with an advancing cursor and **never grows it**. When the buffer
  fills, a `FlushSink` (if set) receives the bytes and writing resumes at the start
  of the *same* buffer — so a message can exceed the buffer or RAM; with no sink, a
  full buffer raises `BUFFER_FULL`. The sink's array is reused after the call
  returns, so a sink that keeps the bytes must copy them — **or take the buffer**
  and install a replacement with `bufferSet(buf, offset)` before returning
  (CORELIB_PLAN §5.1). Which of the two happened is stated by that call and by
  nothing else: return without it and the encoder resumes at offset 0 in the
  still-active buffer; install one and it resumes at *that* call's offset. **The
  start offset belongs to the installation, not to the buffer** — it is consumed by
  the flush it was made in, so a sink that wants framing-header room in *every* unit
  re-arms it on each flush (passing the same array again is a new installation like
  any other), while a `bufferSet` made outside a sink reserves room in the current
  unit only. **`MIN_OUTPUT_BUFFER` is `1`** (`Sofab.MIN_OUTPUT_BUFFER`, CORELIB_PLAN
  §5.1): the encoder splits every atomic unit — no varint, string run or array
  element has to land contiguously — so one usable byte is enough, and any size at or
  above it produces output byte-identical to the one-shot path. **It binds a buffer
  installed *with* a sink**, at construction and at every `bufferSet`, both of which
  reject `buf.length - offset < MIN_OUTPUT_BUFFER` with `IllegalArgumentException`
  where the buffer is handed over — so a replacement with no room left
  (`offset == buf.length`) fails *in that `bufferSet` call*, not at some later write.
  A buffer installed **without** a sink is subject to no minimum: no flush can occur,
  so it either holds the message or raises `BUFFER_FULL`, and sizing from the
  generated `MAX_SIZE` stays exact. A multi-byte varint is
  assembled in a register and stored eight bytes at a time, so the encoder may leave
  up to **seven scratch bytes** in the buffer just past the write position; they are
  never part of the message, sit strictly between `bytesUsed()` and the end of the
  buffer, are overwritten by the next write, and are never flushed — read back only
  `[0, bytesUsed())`. Bytes before the starting `offset` reserved for a lower-layer
  header are never touched, and a buffer with fewer than ten bytes free falls back to
  the byte-at-a-time path, so small buffers see no scratch writes at all.
  (`writeString` encodes
  UTF-8 **directly into the buffer**, with no intermediate `byte[]`.) String
  encoding is **always strict** UTF-8 (MESSAGE_SPEC §8): a `String` is a Unicode
  string type, so the only value it cannot represent as well-formed UTF-8 is an
  unpaired UTF-16 surrogate; `writeString` rejects such a string with
  `SofabException` (`ARGUMENT`) **before** emitting any bytes, never lossily
  substituting a replacement character. There is no strict mode to toggle. The
  same rule binds the **byte-container** entry point: `writeFixlen(id, data, from,
  length, FixlenType.STRING)` takes raw bytes, so it validates that range with
  `Utf8.valid` and refuses a malformed payload with `ARGUMENT`, again before a
  byte is written — this API cannot emit a string the family's own decoders would
  reject. `FixlenType.BLOB` is the type for opaque bytes and is never validated,
  so the other fixlen writers (`writeBlob`, `writeFp32/64`) pay only one enum
  comparison. On the decode side the check lives in generated code too, and it is
  the **same validator**: once the declared payload is complete, generated code
  runs `Utf8.valid` over the assembled bytes and raises `INVALID_MSG` if they are
  malformed — a multi-byte sequence merely split across two chunks is still
  arriving, not invalid. The corelib itself never validates a payload it only
  streams, which is what makes a *skipped* string a pure length jump with no
  per-byte work (§6.4).
- **Decode (`IStream` + `Visitor`).** `feed` runs a cursor over the caller's input
  `byte[]`, **aliasing** it. Scalars and floats are passed **by value** (`long` /
  `double`); strings and blobs are handed to the visitor as a **window** (`data`,
  `chunkOffset`, `chunkLength`) into that array, valid **only for the duration of the
  callback** — no `String` or fresh `byte[]` is constructed, so a visitor that
  retains bytes must copy the range itself. `FixlenType` travels **outwards only**:
  the writers take one of its constants and `raw()` turns it into the wire tag, while
  the decoder narrows an incoming tag itself — rejecting the reserved values
  `0x4..0x7` in the single check that every site reading a `fixlen_word` runs — and
  hands the visitor the matching constant. There is deliberately no tag-to-constant entry point on the
  enum — the `fromRaw` lookup that used to sit there was removed after 0.10.0 as
  public API with no caller and no reachable failure mode.

## Feature flags

**None** — the build always ships the full format.

## Build & test

```bash
mvn -B verify          # compile, run the JUnit suite, and produce JaCoCo coverage
mvn -B test            # tests only
```

`verify` runs every suite — including the shared conformance vectors — and writes a
JaCoCo report to `target/site/jacoco/`; CI publishes that report as the coverage
and branches badges above. The suites live in
`src/test/java/org/sofabuffers/sofab/`, and the helpers they share live once in
`.../sofab/common/`: `Wire.bytes` / `Wire.concat` build a wire vector, `Decode.errorOf`
and `Decode.errorOfChunked` feed one whole buffer and one byte at a time, and
`Decode.verdict` reduces a decode to accept / incomplete / rejected. Malformed input is
one table — `DecoderErrorsTest.malformedVectors()`, one row per vector, every row driven
through both decode surfaces — so a new rejection case is a row there rather than a new
suite.

## Benchmarks

Three runnable tools mirror the other ports' `perf`, `bench` and
`run_callgrind.sh` tooling — same workloads (a 1000-element `u64` array and a
mixed message) and output format, so results are comparable across languages:

```bash
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf   # per-op cost
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench  # throughput (MB/s)
bash bench/run_callgrind.sh                                                  # instructions/op
```

`Perf` reports thread-CPU-time ns/op (the JVM exposes no portable cycle counter; run
under an external counter such as `perf stat -e instructions:u …` for a
CPU-speed-independent number). `Bench` reports encode / decode throughput in MB/s
over a ~1 s CPU-time loop. `bench/run_callgrind.sh` (needs `valgrind`) reports
**instructions retired per op** (Ir/op) under Callgrind — deterministic and
independent of clock speed and scheduler, so the numbers compare across machines and
against the sibling ports. There is no JIT-compiled `run_<workload>` symbol to toggle
collection on, so — like the Python and TypeScript ports — it runs each workload at
two rep counts and subtracts, which cancels JVM startup, class loading and JIT cost
exactly. The exact workloads and output grammar are specified in
the [SofaBuffers documentation](https://github.com/sofa-buffers/documentation).
