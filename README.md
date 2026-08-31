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

Maven coordinates `org.sofabuffers:corelib` (version `0.12.0`); the import namespace
is the package `org.sofabuffers.sofab`.

```xml
<dependency>
  <groupId>org.sofabuffers</groupId>
  <artifactId>corelib</artifactId>
  <version>0.12.0</version>
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
| Streaming **in** | `IStream` accepts arbitrarily small chunks; a message may split across `feed` calls at any byte boundary, and large string / blob payloads arrive in pieces. Malformed bytes throw `SofabException` (`INVALID_MSG`); running out of bytes mid-field is **not** an error — `feed` suspends and resumes on the next chunk. Call `status()` after the final `feed` to tell a `COMPLETE` message from a truncated `INCOMPLETE` one; it never throws and needs no finish/finalize step. A rejection is **terminal**: `status()` reports `INVALID` from then on and every further `feed` rethrows `INVALID_MSG` without decoding; `reset()` starts the next one. |
| Sparse sequence framing, still one pass | `writeSequenceBeginLazy` holds a sequence header back until a child field is actually written, so a sequence-typed **field** that receives no content is omitted rather than framed empty — decided in a single forward pass, with no sub-message buffering. Held-back ids are encoder state, not buffer content, so a tiny output buffer still produces the one-shot bytes. `writeSequenceEnd` drops such a sequence; `writeSequenceEndKeep` forces the frame out, and a wrapper-array **element** is always framed. The pending run grows on demand to the full `MAX_DEPTH` (255), so the output is canonical at every legal nesting depth. |
| Reserve-offset | `new OStream(buf, offset)` leaves room at the front for a lower-layer protocol header, saving a copy. |
| Explicit endianness | IEEE-754 values are written / read little-endian with explicit bit shifts, so behaviour is identical on every JVM. |
| Generated-code friendly | Every `Visitor` method has a default no-op, so sinks override only what they need. |

## Usage

Every encode / decode call can throw `SofabException` (which extends
`IOException`); the snippets below elide `throws IOException`.

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
transport — must install a replacement with `bufferSet` before it returns; the
cursor then starts at that installation's offset, which is how a sink reserves
framing-header room in **every** flushed unit:

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
not the 99 999 after it (`fp64` array in 4 KiB chunks: 31.6 → 0.9 ns/element).

### Code generator

The common real use is driving the runtime through **generated code**: `sofabgen`
emits one class per message whose whole wire surface is the family-wide name set —
`serialize(OStream)` writes the fields into a stream the caller owns, `byte[]
encode()` wraps it over a `MAX_SIZE` buffer, `decode(byte[])` (and its
status-returning sibling `tryDecode(byte[], out)`) wraps the decoder over one
complete buffer, and `decoder()` hands back the streaming reader. The one-shot pair
is a thin wrapper over the streaming one, so both produce identical bytes.

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
than the buffer.

### Generated-code support layer

Generated code leans on a schema-agnostic support layer the corelib ships rather
than emitting its own copy into every package: a `count`, a `maxlen` or a capacity
is an argument, an element type is a type parameter.

| symbol | what it is |
|---|---|
| `Seq.reserveRow` / `reserveRowBytes` … `reserveRowDoubles` | place a matrix row at the index its id names, filling a gap with the empty row rather than shifting every later row down (MESSAGE_SPEC §5.1 / §7.4); the receiver cap on that index arrives as a `Bound` and is compared here (§6.2.1) |
| `Seq.ensureCap` (one per primitive width) | the array-growth policy: double, stop at the announced count, and never allocate from a count the wire claimed but has not delivered |
| `Seq.ARRAY_INIT_CAP`, `Seq.EMPTY_BYTES` … `EMPTY_DOUBLES` | the bounded first reservation, and the shared zero-length arrays a field initializer points at |
| `Seq.reset` / `Seq.orEmpty` / `Seq.boolsToLongs` | re-arm a reused destination in place; absorb a null field on the encode side; the one boxed-to-primitive conversion `bool` still needs |
| `PayloadAcc` | reassemble a `string` / `blob` payload split across feeds — a payload that arrives whole never touches its buffer, and the value never depends on where the split fell; the receiver cap on the announced length arrives as a `Bound` and is compared before the first chunk is taken (§6.2.1) |
| `Utf8.decode` | validate a byte range and materialize it, in that order — the only order in which invalid UTF-8 can still be rejected (§6.4) |
| `Sofab.invalid` | the carrier a `Visitor` rejects malformed input through, since a callback declares no checked exception; `IStream.feed` latches it as terminal like its own rejections |
| `Sofab.limitExceeded` / `Sofab.argument` | the twin carrier for a receiver-limit refusal, which is **not** latched as `INVALID`; and the carrier for a defect in the call, such as a bound the caller never stated |
| `Bound.receiver` / `Bound.SCHEMA_BOUNDED` | which of §6.2.1's two rules bounds one field: the deployment's configured cap, or the schema's own `count`/`maxlen` |
| `OStream.overScratch` / `copyOfBytesUsed` | a per-thread buffer for a one-shot `encode()`, so the worst case is allocated once per thread rather than once per call. The **size** stays with the caller (CORELIB_PLAN §5.1): generated code passes its own `MAX_SIZE` |

These are ordinary public API, usable directly; they are simply shaped by what
generated code needs.

### Receiver limits

The library holds **no** receiver-side limit (CORELIB_PLAN §6.2.1). It defines no
`max_dyn_string_len`, `max_dyn_blob_len` or `max_dyn_array_count`, no default for
one, and no fallback: the numbers belong to generated code, which knows the schema
and the target. `ID_MAX`, `ARRAY_MAX` and `MAX_DEPTH` are **format** ceilings —
breaching one is `INVALID_MSG`, never `LIMIT_EXCEEDED`.

The **comparison** runs here, on the call generated code already makes at the point
the limit guards:

| the cap | the call that takes it | compared against |
|---|---|---|
| `max_dyn_string_len` | `PayloadAcc.string(…, bound)` | the announced `total`, before a byte is buffered |
| `max_dyn_blob_len` | `PayloadAcc.blob(…, bound)` | the same |
| `max_dyn_array_count` | `Seq.reserveRow` / `reserveRow*(…, bound)` | the row **index**, before the row and the list grow |

A breach is `SofabError.LIMIT_EXCEEDED` — a policy rejection of well-formed bytes,
never clamped into a shortened value and never the `INVALID` outcome. A caller that
passes a cap here does not also guard in front of the call: the rule has one
implementation.

`Bound` is what each of those calls takes, and it has exactly the two answers
§6.2.1 admits — never one number with a reserved value for the second:

```java
private static final Bound TAGS_CAP = Bound.receiver(MAX_DYN_ARRAY_COUNT);

Seq.reserveRow(rows, id, TAGS_CAP);              // schema declares no count:
Seq.reserveRow(rows, id, Bound.SCHEMA_BOUNDED);  // count: N — checked one line above
```

`Bound.receiver(n)` is the deployment's configured number for a schema-unbounded
field. `Bound.SCHEMA_BOUNDED` states that the schema's `maxlen`/`count` governs
instead (a breach there is the caller's `Sofab.invalid`, `INVALID_MSG`) — it carries
no number, because this library does not apply the schema bound and a second copy of
that rule here is what §6.2.1's *one implementation* forbids.

**A cap that was never stated is reported, not obeyed.** §6.2.1 admits "no unset
state and no unlimited mode" and forbids reading an omitted argument as *unlimited*,
so there is no numeric value that means "the schema bounds this" and nothing to
default to: `Bound.receiver` refuses `0` (Java's unassigned field) and every
negative (the sentinel this shape replaced), and a `null` bound is
`SofabError.ARGUMENT` — a defect in the **call**, not `LIMIT_EXCEEDED`, which would
promise a limit to raise that was never configured (§6.3). `ARRAY_MAX` is a format
ceiling and is not available as a fallback either.

Build each `Bound` once, into a `static final`: they are constants of the
deployment, so nothing is allocated per call or per message.

Two checks stay in generated code, because no call into this library carries them:
a **native array count** (`new int[count]` is written straight into the field) and
the element **index of a flat wrapper array** of strings, blobs or sub-messages
(placed by an inline `while (list.size() <= id)`).

## Memory handling

**No wire value decides an allocation in the codec.** `OStream` and `IStream`
allocate exactly once, in their constructors: the object itself and its
fixed-size working state — the eight-byte landing zone for a float split across
feeds, and the `MAX_DEPTH`-wide run of held-back sequence headers (1032 bytes per
encoder). Every size comes from a constant of the format, never from a count or
a length on the wire. After construction `write`, `feed` and `flush` allocate
nothing at all, and there is no library-owned accumulator for a chunk-straddling
field: each piece is passed through to the visitor as it arrives.
`AllocationFreeTest` measures it. Holding one encoder or decoder and re-arming it
with `reset` costs that construction once instead of once per message; the
one-shot `OStream.overScratch` helper already keeps one per thread.

The storage every decoded field lands in comes from the caller — the destination
a visitor hands back, or the visitor's own fields. The library ships a **static
helper layer** beside the codec (`Seq`, `PayloadAcc`, `Utf8`, `OStream.overScratch`,
listed under *Generated-code support layer* above): that layer **does** allocate,
on the generated layer's behalf and with sizes the generated layer supplies, and
it is not part of the codec.

- **Encode (`OStream`).** The caller owns and sizes the output `byte[]`; the encoder
  writes straight in with an advancing cursor and **never grows it**. When the buffer
  fills, a `FlushSink` (if set) receives the bytes and writing resumes at the start
  of the *same* buffer — so a message can exceed the buffer or RAM; with no sink, a
  full buffer raises `BUFFER_FULL`. The sink's array is reused after the call
  returns, so a sink that keeps the bytes must copy them — **or take the buffer**
  and install a replacement with `bufferSet(buf, offset)` before returning.
  **The start offset belongs to the installation, not to the buffer**: return
  without `bufferSet` and the encoder resumes at offset 0 in the still-active
  buffer; install one and it resumes at *that* call's offset, so a sink that wants
  framing-header room in *every* unit re-arms it on each flush. **`MIN_OUTPUT_BUFFER`
  is `1`** (`Sofab.MIN_OUTPUT_BUFFER`): the encoder splits every atomic unit — no
  varint, string run or array element has to land contiguously — so one usable byte
  is enough, and any size at or above it produces output byte-identical to the
  one-shot path. **It binds a buffer installed *with* a sink**, at construction and
  at every `bufferSet`, both of which reject a buffer with
  `buf.length - offset < MIN_OUTPUT_BUFFER` — an `IllegalArgumentException` raised
  where the buffer is handed over, never partway through a message. A buffer installed **without** a sink is subject to no minimum: it either
  holds the message or raises `BUFFER_FULL`, and sizing from the generated
  `MAX_SIZE` stays exact. **A sink is only ever handed memory inside the installed
  buffer** — the `data` argument is that array itself and `[offset, offset+length)`
  a range within it. A long payload is never passed through straight from the
  caller's own array, whatever its size. The encoder may leave up to **seven scratch bytes** in the
  buffer just past the write position; they are never part of the message, sit
  strictly between `bytesUsed()` and the end of the buffer, are overwritten by the
  next write, and are never flushed — read back only `[0, bytesUsed())`. Bytes
  before the starting `offset` reserved for a lower-layer header are never touched,
  and a buffer with fewer than ten bytes free sees no scratch writes at all.
  (`writeString` encodes UTF-8 **directly into the buffer**, with no intermediate
  `byte[]`.) String encoding is **always strict** UTF-8: a `String` is a Unicode
  string type, so the only value it cannot represent as well-formed UTF-8 is an
  unpaired UTF-16 surrogate, and `writeString` rejects such a string with
  `SofabException` (`ARGUMENT`) **before** emitting any bytes, never lossily
  substituting a replacement character. There is no strict mode to toggle. The
  byte-container entry point `writeFixlen(id, data, from, length,
  FixlenType.STRING)` takes raw bytes, so it validates that range with `Utf8.valid`
  and refuses a malformed payload with `ARGUMENT`, again before a byte is written.
  `FixlenType.BLOB` is the type for opaque bytes and is never validated. On the
  decode side the check lives in generated code and is the **same validator**: once
  the declared payload is complete, generated code runs `Utf8.valid` over the
  assembled bytes and raises `INVALID_MSG` if they are malformed. The corelib itself
  never validates a payload it only streams, so a *skipped* string is a pure length
  jump with no per-byte work.
- **Decode (`IStream` + `Visitor`).** The caller owns the input bytes, and must keep
  them alive only **for the duration of the `feed` call**: `feed` runs a cursor over
  the array and copies nothing out of it, so the moment it returns the caller may
  reuse, overwrite or drop that chunk. **Nothing outlives the callback.** Values
  reach the caller by the second of the two routes the format allows — passed
  *through* the callback rather than written into storage the library holds: scalars
  and floats **by value** (`long` / `double`), strings and blobs as a **window**
  (`data`, `chunkOffset`, `chunkLength`) into the caller's own array, valid **only
  until the callback returns** — no `String` or fresh `byte[]` is constructed, so a
  visitor that retains bytes copies the range itself. This holds on the one-shot
  path exactly as on the streaming one: there is no position to read a payload back
  from afterwards, and no value that stays valid until the next `feed`. `FixlenType` travels **outwards only**:
  the writers take one of its constants and `raw()` turns it into the wire tag, while
  the decoder narrows an incoming tag itself — rejecting the reserved values
  `0x4..0x7` in the single check that every site reading a `fixlen_word` runs — and
  hands the visitor the matching constant.

## Build & test

```bash
mvn -B verify          # compile, run the JUnit suite, and produce JaCoCo coverage
mvn -B test            # tests only
```

`verify` runs every suite — including the shared conformance vectors from
`assets/` — and writes a JaCoCo report to `target/site/jacoco/`; CI publishes that
report as the coverage and branches badges above. The suites live in
`src/test/java/org/sofabuffers/sofab/`.

### Feature flags

**None** — the build always ships the full format.

## Benchmarks

Three runnable tools mirror the other ports' `perf`, `bench` and
`run_callgrind.sh` tooling — same workloads and output format, so results are
comparable across languages:

```bash
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Perf   # per-op cost
mvn -q compile exec:java -Dexec.mainClass=org.sofabuffers.sofab.bench.Bench  # throughput (MB/s)
bash bench/run_callgrind.sh                                                  # instructions/op
```

The workload set is the family's, defined once in
`src/main/java/org/sofabuffers/sofab/bench/Workloads.java` and driven by all three
tools: a 1000-element `u64` array, a small mixed `typical` message, an unbounded
**1 MB `blob`** encoded both one-shot and streamed through a 4096-byte buffer with
a flush sink (and decoded from 4096-byte chunks), and a **`composite`** message
holding what the flat datasets never reach — a wrapper array with a header per
element, 320 bytes of non-ASCII UTF-8, nesting at depth 3, a field equal to its
default that the encoder must *not* write, and a two-byte field header. The encoded
sizes are cross-port parity checks: 170 bytes for the `perf` message, 1,000,005 for
the blob and 956 for the composite.

`Perf` reports thread-CPU-time ns/op (the JVM exposes no portable cycle counter; run
`bench/run_callgrind.sh` for a CPU-speed-independent number). `Bench` reports encode
/ decode throughput in MB/s over a ~1 s CPU-time loop per workload.
`bench/run_callgrind.sh` (needs `valgrind`, which the `.devcontainer/` image
installs) reports **instructions retired per op** (Ir/op) under Callgrind —
deterministic and independent of clock speed and scheduler, so the numbers compare
across machines and against the sibling ports. Each workload gets a JVM of its own
and is run at two rep counts and subtracted, which cancels JVM startup, class
loading and JIT cost.

**Read the two `blob 1MB` encode rows against each other, not against the rest.**
Five of that message's bytes are metadata and a million are payload, so its MB/s is
this machine's memory bandwidth rather than a statement about the library — and the
streamed row can even edge ahead of the one-shot one, because a 4 KiB window stays in
L1 while a one-shot encode writes a megabyte out to memory. The instruction counts
need the same care: the gap between the two is the JVM's array-copy strategy, not
the flush path. The one-shot message's payload starts at offset 5 — its own
header — and for a destination that is not 8-byte aligned the JIT's copy stub
costs about one instruction per byte, while the streamed row copies into a fresh
window at offset 0.

One process, ten workloads: that costs the `decode: composite skip-all` row too.
It shares a JVM with `decode: composite`, so the visitor call sites inside
`IStream` see both sinks and neither row runs monomorphic — what not-decoding is
worth shows up in Ir/op, where each workload gets its own JVM, and not in the
MB/s pair.

Measured figures are not reproduced here — they belong to the cross-language
benchmark arena, which runs every port on one host under one methodology. This
section says how to obtain them, not what they came out as.

The exact workloads, timing rules and output grammar are specified in
the [SofaBuffers documentation](https://github.com/sofa-buffers/documentation);
`BenchSpecTest` holds the tools to them.
