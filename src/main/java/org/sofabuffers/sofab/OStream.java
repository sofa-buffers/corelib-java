/*
 * SofaBuffers Java - streaming output encoder (port of ostream.c).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static org.sofabuffers.sofab.WireFormat.T_FIXLEN;
import static org.sofabuffers.sofab.WireFormat.T_FIXLENARRAY;
import static org.sofabuffers.sofab.WireFormat.T_SEQUENCE_END;
import static org.sofabuffers.sofab.WireFormat.T_SEQUENCE_START;
import static org.sofabuffers.sofab.WireFormat.T_VARINTARRAY_SIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINTARRAY_UNSIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINT_SIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINT_UNSIGNED;
import static org.sofabuffers.sofab.WireFormat.zigzagEncode;

/**
 * Streaming SofaBuffers encoder writing into a caller-provided byte buffer.
 *
 * <p>The encoder never allocates the output buffer itself: it writes into the
 * array you hand it. When that array fills, the accumulated bytes are passed to
 * an optional {@link FlushSink} and writing resumes at the start of the buffer,
 * so a message larger than the buffer (or larger than RAM) can be streamed out.
 * With no sink, a full buffer raises {@link SofabError#BUFFER_FULL}.
 *
 * <p>An initial {@code offset} reserves space at the front of the buffer for a
 * lower-layer protocol header, avoiding a copy.
 *
 * <p>A buffer installed <b>with</b> a sink must leave at least
 * {@link Sofab#MIN_OUTPUT_BUFFER} usable bytes and is rejected where it is handed
 * over if it does not; a buffer installed without one is subject to no minimum
 * (CORELIB_PLAN §5.1).
 *
 * <p>Writes take a fast path that advances a cursor over the buffer with no
 * per-byte bounds check whenever the remaining room is known to be sufficient
 * (a varint is at most ten bytes; a float four or eight); a buffer-spanning slow
 * path that flushes mid-value is used only when the buffer is too small to hold
 * the value outright. Raw string / blob payloads are copied in bulk up to each
 * buffer boundary. The wire output is identical regardless of buffer size.
 *
 * <p>A field is written with as few stores as its shape allows: a multi-byte
 * varint is assembled in a register and written with one eight-byte store, a
 * one-byte header and one-byte value go out together as one two-byte store, and a
 * whole {@code fp32} field — header, {@code fixlen_word} and payload — fits one
 * eight-byte store. A store may therefore reach past the field it wrote, leaving
 * up to seven <b>scratch bytes</b> in the buffer immediately after the current
 * write position. They are never part of the message and never escape: they sit
 * strictly between {@link #bytesUsed()} and the end of the buffer, are overwritten
 * by the next write, and only {@code [0, bytesUsed())} is ever handed to a
 * {@link FlushSink}. Bytes before the starting {@code offset} — the region
 * reserved for a lower-layer header — are
 * never touched. A buffer with fewer than ten bytes free falls back to the
 * byte-at-a-time path, so small buffers see no scratch writes at all.
 *
 * <p>Sequences are framed <b>lazily</b>: {@link #writeSequenceBeginLazy(int)}
 * holds the header back until the sequence turns out to have content. A
 * sequence-typed <b>field</b> that receives none is therefore dropped entirely
 * rather than emitted as an empty frame (MESSAGE_SPEC §2) — "always framed" is no
 * longer true for a field. It is still true for a wrapper-array <b>element</b>,
 * whose presence carries the array's length (§5.1); that position closes with
 * {@link #writeSequenceEndKeep()}, which forces the frame out. Held-back ids are
 * encoder state, not buffer content, so this never interacts with flushing, and
 * the run grows to the full {@link Sofab#MAX_DEPTH} so the output is canonical at
 * every legal depth.
 *
 * <p>This class is not thread-safe; encode one message from one thread.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * byte[] buf = new byte[64];
 * OStream os = new OStream(buf);
 * os.writeUnsigned(1, 42);
 * os.writeSigned(2, -7);
 * os.writeString(3, "hi");
 * int used = os.bytesUsed();
 * }</pre>
 */
public final class OStream {

    /**
     * Initial capacity of the held-back-header run. It is a starting size, not a
     * limit: the run grows on demand and can reach {@link Sofab#MAX_DEPTH}, which
     * is what makes this encoder canonical at every legal nesting depth
     * (CORELIB_PLAN §6, "How deep the hold-back reaches" — only a heap-free
     * profile may bound the run and frame eagerly beyond it).
     */
    private static final int PENDING_INITIAL = 8;

    /**
     * Little-endian views over a {@code byte[]}. A float payload — and the
     * eight-byte window a varint is assembled in — is written with one
     * intrinsified unaligned store instead of a byte at a time, paying a single
     * bounds check rather than one per byte.
     */
    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_SHORT =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);

    /** One per byte lane: the continuation bit of each of eight varint bytes. */
    private static final long CONT_BITS = 0x8080_8080_8080_8080L;

    /**
     * Bytes of room that let {@link #putVarint} assemble a varint in a single
     * eight-byte store: ten covers the longest varint, and the store itself
     * always touches eight bytes from the write position.
     */
    private static final int VARINT_ROOM = 10;

    /**
     * Room needed to write a field header and a scalar value with one cursor and
     * one bounds test: a header is at most five bytes ({@code id << 3} spans 34
     * bits) and a value at most ten.
     */
    private static final int FIELD_ROOM = 15;

    private byte[] buffer;
    private int end;
    private int offset;
    private final FlushSink sink;

    /**
     * Set by {@link #bufferSet}, read and cleared by the flush the call happened
     * inside. CORELIB_PLAN §5.1: a sink that <b>takes</b> the buffer it was handed
     * installs a replacement before returning, and writing then resumes at
     * <em>that installation's</em> offset; a sink that returns without installing
     * anything copied, and writing resumes at offset 0 in the still-active buffer.
     * The encoder cannot tell the two apart by any other means, so this flag is
     * what keeps a flush from overwriting the cursor an installation just set.
     *
     * <p>The offset belongs to the installation and is consumed by the flush it
     * belongs to, so the flag is cleared at every handover: a {@code bufferSet}
     * made outside a sink arms nothing for a later flush.
     */
    private boolean installed;

    /** Number of nested sequences currently open; bounded by {@link Sofab#MAX_DEPTH}. */
    private int depth;

    /**
     * Ids of the innermost open sequences whose header has not been written yet
     * (MESSAGE_SPEC §2 lazy framing). Always a contiguous suffix of the open
     * sequences — every entry is held back, and nothing below it is — so
     * {@link #writeSequenceEnd()} can simply pop the last entry. Writing any
     * field commits the whole run at once, and there is no other way to leave it;
     * the invariant therefore holds by construction.
     *
     * <p>Allocated on the first {@link #writeSequenceBeginLazy(int)} — an encoder
     * that never opens a sequence pays nothing for it — and grown on demand, so
     * the hold-back reaches the full {@link Sofab#MAX_DEPTH}.
     */
    private int[] pending;

    /**
     * The outermost held-back id, kept out of {@link #pending} so that a stream
     * whose sequences never nest — much the commonest shape — holds one back
     * without allocating an array at all. {@link #pending} carries entries two and
     * beyond, and is still allocated only if that depth is reached.
     */
    private int pending0;

    /** Total number of held-back ids: {@link #pending0} plus {@link #pending}. */
    private int nPending;

    /**
     * Create an encoder over {@code buffer} with no flush sink. Writing past the
     * end of the buffer raises {@link SofabError#BUFFER_FULL}.
     *
     * @param buffer caller-owned output buffer (length &gt; 0)
     */
    public OStream(byte[] buffer) {
        this(buffer, 0, null);
    }

    /**
     * Like {@link #OStream(byte[])} but begin writing at {@code offset} bytes
     * into the buffer, reserving room for a lower-layer header.
     *
     * @param buffer caller-owned output buffer
     * @param offset initial write position ({@code 0..buffer.length})
     */
    public OStream(byte[] buffer, int offset) {
        this(buffer, offset, null);
    }

    /**
     * Create an encoder with a flush {@code sink}. When the buffer fills, the
     * accumulated bytes are passed to {@code sink} and writing resumes at the
     * start of the buffer.
     *
     * <p>With a sink the buffer must leave at least {@link Sofab#MIN_OUTPUT_BUFFER}
     * usable bytes — {@code buffer.length - offset} — and is rejected here if it
     * does not (CORELIB_PLAN §5.1).
     *
     * @param buffer caller-owned output buffer (length &gt; 0)
     * @param offset initial write position ({@code 0..buffer.length})
     * @param sink   flush sink, or {@code null} for none
     * @throws IllegalArgumentException if the buffer is empty, the offset is out of
     *                                  range, or a sink is given and the buffer
     *                                  leaves less than {@link Sofab#MIN_OUTPUT_BUFFER}
     *                                  usable bytes
     */
    public OStream(byte[] buffer, int offset, FlushSink sink) {
        checkHandover(buffer, offset, sink);
        this.buffer = buffer;
        this.end = buffer.length;
        this.offset = offset;
        this.sink = sink;
    }

    /**
     * Validate a buffer where it is handed over — at construction and at every
     * mid-stream {@link #bufferSet} — so a buffer that cannot be written into is
     * refused there rather than partway through a message (CORELIB_PLAN §5.1).
     *
     * <p>{@link Sofab#MIN_OUTPUT_BUFFER} applies only when a {@code sink} is
     * present. Without one no flush can occur, so §5.1 imposes no minimum: the
     * buffer either holds the message or reports {@link SofabError#BUFFER_FULL},
     * and a caller sizing from the generated {@code MAX_SIZE} keeps an exact fit.
     */
    private static void checkHandover(byte[] buffer, int offset, FlushSink sink) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("buffer must be non-empty");
        }
        if (offset < 0 || offset > buffer.length) {
            throw new IllegalArgumentException("offset out of range");
        }
        if (sink != null && buffer.length - offset < Sofab.MIN_OUTPUT_BUFFER) {
            throw new IllegalArgumentException(
                    "streaming buffer leaves " + (buffer.length - offset)
                            + " usable bytes, minimum is " + Sofab.MIN_OUTPUT_BUFFER);
        }
    }

    /**
     * Number of bytes written to the active buffer since the last flush.
     *
     * @return the byte count
     */
    public int bytesUsed() {
        return offset;
    }

    /**
     * Flush any pending bytes to the sink (if one is set) and report how many
     * bytes were pending. With no sink the buffer is left intact.
     *
     * <p>This is a buffer handover like the automatic one: a sink that takes the
     * buffer may install a replacement with {@link #bufferSet}, and writing then
     * resumes at that installation's offset rather than at 0.
     *
     * @return number of bytes that were pending
     * @throws IOException if the sink fails
     */
    public int flush() throws IOException {
        int used = offset;
        if (used > 0 && sink != null) {
            handOver(used);
        }
        return used;
    }

    /**
     * Replace the active buffer (typically from within a flush sink), resuming
     * writes at {@code offset} in the new buffer.
     *
     * <p>Called from within a {@link FlushSink} this is how a sink that <b>takes</b>
     * the buffer it was handed hands the encoder a replacement (CORELIB_PLAN §5.1);
     * a sink that returns without calling it copied, and writing resumes at 0 in
     * the buffer that is still active. The start offset belongs to the
     * installation, not to the buffer: passing the <b>same</b> array again is a new
     * installation like any other, which is how a sink re-arms header room in every
     * flushed unit. The offset is consumed by the flush it was installed in, so the
     * next flush the sink returns from bare resumes at 0 again.
     *
     * <p>On a stream that carries a sink the replacement must leave at least
     * {@link Sofab#MIN_OUTPUT_BUFFER} usable bytes — {@code buffer.length - offset}
     * — and is rejected here if it does not, which is why a sink cannot hand back
     * storage the encoder could not write a single byte into (CORELIB_PLAN §5.1).
     * On a sink-less stream no minimum applies.
     *
     * @param buffer new caller-owned output buffer (length &gt; 0)
     * @param offset initial write position ({@code 0..buffer.length})
     * @throws IllegalArgumentException if the buffer is empty, the offset is out of
     *                                  range, or this stream carries a sink and the
     *                                  buffer leaves less than
     *                                  {@link Sofab#MIN_OUTPUT_BUFFER} usable bytes
     */
    public void bufferSet(byte[] buffer, int offset) {
        checkHandover(buffer, offset, this.sink);
        this.buffer = buffer;
        this.end = buffer.length;
        this.offset = offset;
        this.installed = true;
    }

    /**
     * Return this stream to its just-constructed state, writing into {@code buffer}
     * from its start, so a caller encoding many messages in a row (a server loop,
     * the generated {@code encode} helper) can hold one instance instead of letting
     * each encode allocate and immediately discard one.
     *
     * <p>The {@link FlushSink} is <b>not</b> part of what is reset: it is fixed at
     * construction, so this resets a sink-carrying stream to a sink-carrying one and
     * a sink-less stream to a sink-less one. Reuse therefore stays within one
     * output discipline — an instance built for streaming cannot be recycled into a
     * one-shot encode, or the other way round.
     *
     * <p>Unlike {@link #bufferSet} this also clears the sequence nesting depth and
     * the held-back sequence run. That is the whole point for reuse: an encode that
     * threw part-way leaves the depth counter non-zero and can leave sequence
     * headers pending, and carrying either into the next message on the same thread
     * would corrupt its nesting validation or prepend a stale {@code sequence start}
     * to it — three bytes instead of two for one open sequence, and 498 instead of
     * four at {@link Sofab#MAX_DEPTH}.
     *
     * <p>The {@link #pending} array keeps its allocation: retaining it is the point
     * of reuse, and it is never read while {@link #nPending} is zero, which is
     * cleared here. Everything else this class declares is restored, and
     * {@code ResetCoversEveryFieldTest} holds that to every field added later.
     *
     * @param buffer caller-owned output buffer (length &gt; 0)
     */
    public void reset(byte[] buffer) {
        bufferSet(buffer, 0);
        this.depth = 0;
        this.nPending = 0;
        this.pending0 = 0;
        // bufferSet marks an installation; a reset is not one made from inside a
        // flush, so it arms nothing for the next handover.
        this.installed = false;
    }

    // --- primitives ---------------------------------------------------------

    /** Hand the full buffer to the sink and resume writing, or fail if there is none. */
    private void flushFull() throws IOException {
        if (sink == null) {
            throw new SofabException(SofabError.BUFFER_FULL);
        }
        handOver(offset);
        if (offset >= end) {
            // The sink installed a buffer with no room left, and a write is in
            // flight: report it here rather than let the caller loop forever
            // copying zero bytes at a time.
            throw new SofabException(SofabError.BUFFER_FULL);
        }
    }

    /**
     * Hand {@code used} buffered bytes to the sink and settle where writing goes
     * next (CORELIB_PLAN §5.1): the offset of the replacement the sink installed if
     * it took the buffer, or 0 in the still-active buffer if it copied. The flag is
     * cleared first, so only an installation made from inside <em>this</em> call
     * counts, and it is consumed here.
     */
    private void handOver(int used) throws IOException {
        installed = false;
        sink.flush(buffer, 0, used);
        if (installed) {
            installed = false;
        } else {
            offset = 0;
        }
    }

    /** Append one byte, flushing the full buffer first if it has no room. */
    private void pushByte(int b) throws IOException {
        if (offset >= end) {
            flushFull();
        }
        buffer[offset++] = (byte) b;
    }

    private void pushRaw(byte[] data, int from, int len) throws IOException {
        // Copy in bulk up to each buffer boundary instead of byte-by-byte, so a
        // large payload streams out in a handful of System.arraycopy calls.
        int src = from;
        int remaining = len;
        while (remaining > 0) {
            if (offset >= end) {
                flushFull();
            }
            int n = Math.min(end - offset, remaining);
            System.arraycopy(data, src, buffer, offset, n);
            offset += n;
            src += n;
            remaining -= n;
        }
    }

    private void writeVarint(long value) throws IOException {
        // Fast path: a base-128 varint is at most 10 bytes. When that much room is
        // guaranteed, write it with no per-byte bounds or flush check (the protobuf
        // "write into a contiguous buffer" technique). Single-byte values (field
        // headers, small scalars) are by far the most common and cost one store.
        int p = offset;
        if (end - p >= VARINT_ROOM) {
            offset = putVarint(buffer, p, value);
            return;
        }
        writeVarintSlow(value);
    }

    /** Buffer-spanning varint write: flushes mid-value when the buffer is tiny. */
    private void writeVarintSlow(long value) throws IOException {
        do {
            int b = (int) (value & 0x7F);
            value >>>= 7;
            if (value != 0) {
                b |= 0x80;
            }
            pushByte(b);
        } while (value != 0);
    }

    /** Write four little-endian bytes, fast when the buffer has room. */
    private void putLe32(int bits) throws IOException {
        int p = offset;
        if (end - p >= 4) {
            LE_INT.set(buffer, p, bits);
            offset = p + 4;
            return;
        }
        pushByte(bits & 0xFF);
        pushByte((bits >>> 8) & 0xFF);
        pushByte((bits >>> 16) & 0xFF);
        pushByte((bits >>> 24) & 0xFF);
    }

    /** Write eight little-endian bytes, fast when the buffer has room. */
    private void putLe64(long bits) throws IOException {
        int p = offset;
        if (end - p >= 8) {
            LE_LONG.set(buffer, p, bits);
            offset = p + 8;
            return;
        }
        for (int i = 0; i < 8; i++) {
            pushByte((int) ((bits >>> (i * 8)) & 0xFF));
        }
    }

    /**
     * Validate {@code id} and write the field header varint
     * {@code (id << 3) | wireType}.
     *
     * <p>This is the single choke point every field write in this class passes
     * through — the scalar, fixlen, float, string, blob and both array writers all
     * compose their header here — so it is also where a held-back sequence run is
     * committed: the field about to be written is content, which means every
     * enclosing sequence is non-default and must be framed after all
     * (MESSAGE_SPEC §2).
     */
    private void writeIdType(int id, int wireType) throws IOException {
        beginField(id);
        writeVarint(((long) id << 3) | wireType);
    }

    /**
     * Validate {@code id} and settle any held-back sequence run, the two things
     * every field write does before its first byte reaches the buffer. Split out of
     * {@link #writeIdType} so a writer that emits its header and value together can
     * share them without also being forced through a separate header write.
     */
    private void beginField(int id) throws IOException {
        // The id ceiling is ID_MAX == INT32_MAX (§6.2), so an int argument can only
        // leave the range downwards: the sign test is the whole check, and this is
        // the encoder's per-field choke point.
        if (id < 0) {
            throw new SofabException(SofabError.ARGUMENT, "id " + id);
        }
        // No wire-type exemption is needed here. A sequence *header* never passes
        // through this method — writeSequenceBeginLazy holds it back and
        // commitPending emits it directly — and both closers settle the run
        // themselves before they get here (end() returns early when its own header
        // is still pending; endKeep() commits first), so the end marker only ever
        // arrives with an empty run. Committing unconditionally is therefore both
        // exact and the safe behaviour for any future caller.
        if (nPending != 0) {
            commitPending();
        }
    }

    /**
     * Write a field header and a scalar varint value together. Both are at most
     * fifteen bytes, so one room test and one cursor cover the pair, halving the
     * bounds/flush checks and the {@code offset} round trips a field costs.
     */
    private void writeIdTypeValue(int id, int wireType, long value) throws IOException {
        beginField(id);
        int p = offset;
        if (end - p >= FIELD_ROOM) {
            byte[] b = buffer;
            long header = ((long) id << 3) | wireType;
            if (((header | value) & ~0x7FL) == 0) {
                // Both halves are one byte — an id below 16 with a small value,
                // which is most of a typical message — so the whole field is one
                // two-byte store instead of two bounds-checked single-byte ones.
                LE_SHORT.set(b, p, (short) (header | (value << 8)));
                offset = p + 2;
                return;
            }
            offset = putVarint(b, putVarint(b, p, header), value);
            return;
        }
        writeVarint(((long) id << 3) | wireType);
        writeVarint(value);
    }

    /**
     * Write out the held-back sequence headers, outermost first. Runs at most once
     * per non-default sequence run, never per field.
     */
    private void commitPending() throws IOException {
        int n = nPending;
        nPending = 0;
        writeVarint(((long) pending0 << 3) | T_SEQUENCE_START);
        for (int i = 0; i < n - 1; i++) {
            writeVarint(((long) pending[i] << 3) | T_SEQUENCE_START);
        }
    }

    // --- scalar writers -----------------------------------------------------

    /**
     * Write an unsigned-integer field. The {@code long} is treated as an
     * unsigned 64-bit value.
     *
     * @param id    field id ({@code 0..ID_MAX})
     * @param value unsigned value
     * @throws IOException on buffer overflow (no sink) or sink failure
     */
    public void writeUnsigned(int id, long value) throws IOException {
        writeIdTypeValue(id, T_VARINT_UNSIGNED, value);
    }

    /**
     * Write a signed-integer field (ZigZag + varint).
     *
     * @param id    field id
     * @param value signed value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeSigned(int id, long value) throws IOException {
        writeIdTypeValue(id, T_VARINT_SIGNED, zigzagEncode(value));
    }

    /**
     * Write a boolean as an unsigned {@code 0} / {@code 1}.
     *
     * @param id    field id
     * @param value boolean value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeBoolean(int id, boolean value) throws IOException {
        writeUnsigned(id, value ? 1 : 0);
    }

    // --- fixed-length writers ----------------------------------------------

    /**
     * Write a fixed-length field: the id header, a {@code (len << 3) | subtype}
     * length header, then {@code length} raw bytes from {@code data} (already in
     * wire / little-endian order for floats).
     *
     * <p>This is the <b>byte-container</b> string entry point, so it carries the
     * same strict-UTF-8 encode obligation as {@link #writeString} (CORELIB_PLAN
     * §6.4, MESSAGE_SPEC §8): with {@link FixlenType#STRING} the payload range is
     * validated and a malformed one is refused with {@link SofabError#ARGUMENT}
     * <em>before</em> any byte is emitted, so this API cannot produce a message
     * that the family's own decoders reject. Every other sub-type — including
     * {@link FixlenType#BLOB}, the type for opaque bytes — passes through
     * unvalidated at the cost of one enum comparison.
     *
     * @param id      field id
     * @param data    payload bytes (may be {@code null} only if {@code length} is 0)
     * @param from    start offset within {@code data}
     * @param length  number of payload bytes
     * @param subtype fixed-length sub-type
     * @throws SofabException with {@link SofabError#ARGUMENT} if {@code length} is
     *         negative, or if {@code subtype} is {@link FixlenType#STRING} and the
     *         payload range is not well-formed UTF-8
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeFixlen(int id, byte[] data, int from, int length, FixlenType subtype) throws IOException {
        if (length < 0) {
            throw new SofabException(SofabError.ARGUMENT, "length " + length);
        }
        if (subtype == FixlenType.STRING && !Utf8.valid(data, from, from + length)) {
            throw new SofabException(SofabError.ARGUMENT, "invalid UTF-8 string payload");
        }
        writeIdTypeValue(id, T_FIXLEN, ((long) length << 3) | subtype.raw());
        pushRaw(data, from, length);
    }

    /**
     * Write a 32-bit float field.
     *
     * @param id    field id
     * @param value value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeFp32(int id, float value) throws IOException {
        int bits = Float.floatToRawIntBits(value);
        beginField(id);
        int p = offset;
        // Header (<=5) + the constant one-byte fixlen_word + four payload bytes.
        if (end - p >= FIELD_ROOM) {
            byte[] b = buffer;
            long header = ((long) id << 3) | T_FIXLEN;
            if ((header & ~0x7FL) == 0) {
                // Six bytes — a one-byte header, the constant fixlen_word and the
                // payload — fit one eight-byte store, so the commonest float field
                // costs a single bounds-checked write. FIELD_ROOM covers the two
                // scratch bytes past it.
                LE_LONG.set(b, p, header
                        | ((long) ((4 << 3) | FixlenType.FP32.raw()) << 8)
                        | ((bits & 0xFFFF_FFFFL) << 16));
                offset = p + 6;
                return;
            }
            p = putVarint(b, p, header);
            b[p] = (byte) ((4 << 3) | FixlenType.FP32.raw());
            LE_INT.set(b, p + 1, bits);
            offset = p + 5;
            return;
        }
        writeVarint(((long) id << 3) | T_FIXLEN);
        writeVarint((4L << 3) | FixlenType.FP32.raw());
        putLe32(bits);
    }

    /**
     * Write a 64-bit float field.
     *
     * @param id    field id
     * @param value value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeFp64(int id, double value) throws IOException {
        long bits = Double.doubleToRawLongBits(value);
        beginField(id);
        int p = offset;
        // Header (<=5) + the constant one-byte fixlen_word + eight payload bytes.
        if (end - p >= FIELD_ROOM) {
            byte[] b = buffer;
            long header = ((long) id << 3) | T_FIXLEN;
            if ((header & ~0x7FL) == 0) {
                // A one-byte header and the constant fixlen_word go out together,
                // then the payload: two stores for the commonest fp64 field.
                LE_SHORT.set(b, p,
                        (short) (header | (((8 << 3) | FixlenType.FP64.raw()) << 8)));
                LE_LONG.set(b, p + 2, bits);
                offset = p + 10;
                return;
            }
            p = putVarint(b, p, header);
            b[p] = (byte) ((8 << 3) | FixlenType.FP64.raw());
            LE_LONG.set(b, p + 1, bits);
            offset = p + 9;
            return;
        }
        writeVarint(((long) id << 3) | T_FIXLEN);
        writeVarint((8L << 3) | FixlenType.FP64.raw());
        putLe64(bits);
    }

    /**
     * Write a string field (raw UTF-8 bytes, no NUL on the wire).
     *
     * <p>Encoding is <b>always strict</b> UTF-8 (MESSAGE_SPEC §8): a {@code String}
     * is a Unicode string type, so the only value it can hold that is not
     * representable as well-formed UTF-8 is an unpaired UTF-16 surrogate. Such a
     * string is rejected with {@link SofabError#ARGUMENT} <em>before</em> any bytes
     * are written, rather than being silently lossily replaced. There is no strict
     * mode to toggle — for a Unicode string type the check is unconditional.
     *
     * @param id   field id
     * @param text string value
     * @throws SofabException with {@link SofabError#ARGUMENT} if {@code text}
     *         contains an unpaired surrogate (invalid UTF-8)
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeString(int id, String text) throws IOException {
        // Encode UTF-8 straight into the output buffer instead of allocating an
        // intermediate byte[] per call (String.getBytes). One pass measures the
        // byte length for the fixlen header, the second emits the bytes.
        //
        // An ALL-ASCII string gets the second pass nearly for free. The measuring
        // pass has to look for the first non-ASCII char anyway; when there is none,
        // the byte length IS the char count and every byte is that char, so the
        // payload is a BULK COPY of the string's own storage instead of a
        // char-at-a-time loop. String.getBytes(int,int,byte[],int) writes exactly
        // those low bytes, and for a Latin-1-coded String -- which an all-ASCII one
        // always is -- that is a System.arraycopy. Identifiers, codes, field names,
        // most of what a schema carries, take this path.
        //
        // getBytes(int,int,byte[],int) is deprecated for the reason that makes it
        // right here: it copies low bytes, not an encoding, so it is wrong for any
        // string that is not known to be single-byte. This one is -- the scan above
        // proved every char below 0x80 -- and it is the only way to reach the
        // string's storage without allocating a byte[] first (which is exactly what
        // encoding into the output buffer exists to avoid). Deprecated since 1.1,
        // never marked forRemoval.
        //
        // Length-gated because the copy is a call with its own bounds checks: below
        // ASCII_BULK_MIN the char loop is already fewer instructions than setting
        // one up. Room-gated because the bulk copy cannot flush mid-string, and the
        // room is read AFTER the header: writing it runs beginField, which may
        // commit held-back sequence headers and flush, so the offset before it is
        // not the offset the payload starts at. A string that does not fit falls
        // through to writeUtf8, which is what this path replaces and handles both
        // the in-room loop and the buffer-spanning one.
        int ascii = asciiPrefix(text);
        int len = text.length();
        if (ascii == len) {
            writeIdTypeValue(id, T_FIXLEN, ((long) len << 3) | FixlenType.STRING.raw());
            if (len >= ASCII_BULK_MIN && end - offset >= len) {
                text.getBytes(0, len, buffer, offset);
                offset += len;
            } else {
                writeUtf8(text, len);
            }
            return;
        }
        int n = utf8Length(text, ascii);
        writeIdTypeValue(id, T_FIXLEN, ((long) n << 3) | FixlenType.STRING.raw());
        writeUtf8(text, n);
    }

    /**
     * Shortest string the ASCII bulk copy in {@link #writeString} is used for.
     * Below it the char-at-a-time loop is cheaper than the copy's call and bounds
     * checks; the exact crossover is not sharp, and this sits comfortably past it.
     */
    private static final int ASCII_BULK_MIN = 16;

    /** Index of the first char at or above {@code U+0080}, or the length if none. */
    private static int asciiPrefix(String s) {
        int len = s.length();
        int i = 0;
        while (i < len && s.charAt(i) < 0x80) {
            i++;
        }
        return i;
    }

    /**
     * Exact UTF-8 byte length, matching {@link #writeUtf8}. Doubles as the strict
     * UTF-8 validation pass: {@code String} is a Unicode string type, so the only
     * way it can fail to encode to well-formed UTF-8 is an unpaired UTF-16
     * surrogate (MESSAGE_SPEC §8: strings are always strict UTF-8). Running this
     * before {@link #writeString} emits any bytes means an invalid string is
     * rejected without producing partial wire output.
     *
     * <p>{@code from} is the caller's already-scanned ASCII prefix length (see
     * {@link #asciiPrefix}); those chars are one byte each and are not re-examined.
     *
     * @throws SofabException with {@link SofabError#ARGUMENT} on an unpaired surrogate
     */
    private static int utf8Length(String s, int from) throws SofabException {
        int len = s.length();
        int i = from;
        int bytes = i;
        for (; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                bytes += 1;
            } else if (c < 0x800) {
                bytes += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                bytes += 4;
                i++;
            } else if (Character.isSurrogate(c)) {
                throw new SofabException(SofabError.ARGUMENT,
                        "invalid UTF-8: unpaired surrogate U+"
                                + Integer.toHexString(c).toUpperCase(java.util.Locale.ROOT)
                                + " at index " + i);
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /**
     * Emit {@code s} as strict, well-formed UTF-8. {@code n} is the exact byte
     * length already measured by {@link #utf8Length}, which also rejected any
     * unpaired surrogate, so this pass never encounters one; the surrogate branch
     * throws defensively rather than emitting a replacement byte. When the buffer
     * has room for {@code n} bytes they are written with a local cursor and no
     * per-byte bounds/flush check.
     */
    private void writeUtf8(String s, int n) throws IOException {
        int len = s.length();
        int p = offset;
        if (end - p >= n) {
            byte[] b = buffer;
            int i = 0;
            // ASCII run: the common case is one byte per char.
            for (; i < len; i++) {
                char c = s.charAt(i);
                if (c >= 0x80) {
                    break;
                }
                b[p++] = (byte) c;
            }
            for (; i < len; i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    b[p++] = (byte) c;
                } else if (c < 0x800) {
                    b[p++] = (byte) (0xC0 | (c >> 6));
                    b[p++] = (byte) (0x80 | (c & 0x3F));
                } else if (Character.isHighSurrogate(c) && i + 1 < len
                        && Character.isLowSurrogate(s.charAt(i + 1))) {
                    int cp = Character.toCodePoint(c, s.charAt(++i));
                    b[p++] = (byte) (0xF0 | (cp >> 18));
                    b[p++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                    b[p++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                    b[p++] = (byte) (0x80 | (cp & 0x3F));
                } else if (Character.isSurrogate(c)) {
                    throw new SofabException(SofabError.ARGUMENT,
                            "invalid UTF-8: unpaired surrogate");
                } else {
                    b[p++] = (byte) (0xE0 | (c >> 12));
                    b[p++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                    b[p++] = (byte) (0x80 | (c & 0x3F));
                }
            }
            offset = p;
            return;
        }
        writeUtf8Slow(s);
    }

    /** Buffer-spanning UTF-8 write: per-byte pushes that can flush mid-string. */
    private void writeUtf8Slow(String s) throws IOException {
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                pushByte(c);
            } else if (c < 0x800) {
                pushByte(0xC0 | (c >> 6));
                pushByte(0x80 | (c & 0x3F));
            } else if (Character.isHighSurrogate(c) && i + 1 < len
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                int cp = Character.toCodePoint(c, s.charAt(++i));
                pushByte(0xF0 | (cp >> 18));
                pushByte(0x80 | ((cp >> 12) & 0x3F));
                pushByte(0x80 | ((cp >> 6) & 0x3F));
                pushByte(0x80 | (cp & 0x3F));
            } else if (Character.isSurrogate(c)) {
                throw new SofabException(SofabError.ARGUMENT,
                        "invalid UTF-8: unpaired surrogate");
            } else {
                pushByte(0xE0 | (c >> 12));
                pushByte(0x80 | ((c >> 6) & 0x3F));
                pushByte(0x80 | (c & 0x3F));
            }
        }
    }

    /**
     * Write a binary blob field.
     *
     * @param id   field id
     * @param data blob bytes
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeBlob(int id, byte[] data) throws IOException {
        writeFixlen(id, data, 0, data.length, FixlenType.BLOB);
    }

    /**
     * Write a slice of a byte array as a binary blob field.
     *
     * @param id     field id
     * @param data   backing array
     * @param from   start offset
     * @param length number of bytes
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeBlob(int id, byte[] data, int from, int length) throws IOException {
        writeFixlen(id, data, from, length, FixlenType.BLOB);
    }

    // --- array writers ------------------------------------------------------

    /**
     * Widest varint an element of each integer array type can encode to: a
     * zero-extended byte spans two bytes, a short three, an int five, and a
     * {@code long} the full ten — and ZigZag never widens past its own type's
     * bound, since it is a bijection on the same bit width. These are what
     * {@link #bulkRoom} multiplies by, so each writer's bulk path is judged
     * against the room its own elements can actually need.
     */
    private static final int W_BYTE = 2;
    private static final int W_SHORT = 3;
    private static final int W_INT = 5;
    private static final int W_LONG = 10;

    /**
     * Spread the low 56 bits of {@code v} into eight byte lanes, seven payload bits
     * each — the inverse of the decoder's lane gather. Group {@code i} sits at bits
     * {@code [7i, 7i+7)} and belongs at {@code [8i, 8i+7)}.
     *
     * <p>Done by repeated halving rather than one term per group: split 56 bits into
     * two 28-bit halves and open a 4-bit gap, then each half into 14-bit quarters
     * with a 2-bit gap, then each quarter into 7-bit eighths with a 1-bit gap. Three
     * mask/shift/or stages replace eight, which matters because this is the whole
     * cost of encoding a multi-byte varint.
     */
    private static long scatter7(long v) {
        long x = (v & 0x0FFF_FFFFL) | ((v & 0x00FF_FFFF_F000_0000L) << 4);
        x = (x & 0x0000_3FFF_0000_3FFFL) | ((x & 0x0FFF_C000_0FFF_C000L) << 2);
        return (x & 0x007F_007F_007F_007FL) | ((x & 0x3F80_3F80_3F80_3F80L) << 1);
    }

    /**
     * Emit {@code v} as a base-128 varint into {@code b} at {@code p}, returning the
     * next write position. The caller guarantees at least {@link #VARINT_ROOM} bytes
     * of room ({@code end - p >= 10}).
     *
     * <p>Single-byte values — field headers, small scalars, most array elements —
     * take a direct store. Anything longer is assembled whole in a 64-bit register
     * (seven payload bits per lane, continuation bits set, then cleared on the final
     * lane) and written with <b>one</b> eight-byte store, rather than a per-byte
     * loop that pays a test, a shift and a bounds-checked store for every byte. The
     * ninth and tenth bytes of a maximal varint follow individually.
     *
     * <p>The eight-byte store always touches eight bytes even when the varint is
     * shorter, so up to seven scratch bytes can land in the buffer <em>past</em> the
     * new write position. They are never part of the message: the caller advances by
     * the varint's true length, the next write overwrites them, and only
     * {@code [0, bytesUsed())} is ever handed to a sink. The ten-byte room
     * requirement keeps the store inside the buffer.
     */
    private static int putVarint(byte[] b, int p, long v) {
        if ((v & ~0x7FL) == 0) {
            b[p] = (byte) v;
            return p + 1;
        }
        // ceil(bits / 7) where bits = 64 - numberOfLeadingZeros(v); 2..10 here.
        int n = (70 - Long.numberOfLeadingZeros(v)) / 7;
        long w = scatter7(v) | CONT_BITS;
        if (n <= 8) {
            // Clear the continuation bit of the last lane; higher lanes are scratch.
            LE_LONG.set(b, p, w & ~(0x80L << ((n - 1) << 3)));
            return p + n;
        }
        LE_LONG.set(b, p, w);
        long hi = v >>> 56;
        // For n == 10 bit 63 of v is bit 7 of hi, which is exactly the continuation
        // flag the ninth byte needs; for n == 9 it is clear, which is what it needs.
        // Both trailing bytes go out in one two-byte store: the tenth is scratch
        // when n == 9, and the ten-byte room requirement keeps it inside the buffer
        // either way — one bounds check and one branch fewer per maximal varint.
        LE_SHORT.set(b, p + 8, (short) ((hi & 0xFF) | ((hi >>> 7) << 8)));
        return p + n;
    }

    /**
     * Write an array field header (id header then element count). A zero count is
     * valid (§4.7) and yields exactly {@code [ header ][ count = 0 ]}. The count is
     * a Java array's {@code length} at every call site, so it needs no range test:
     * it is non-negative by construction and {@code ARRAY_MAX} is {@code INT32_MAX}.
     */
    private void writeArrayHeader(int id, int wireType, int count) throws IOException {
        writeIdTypeValue(id, wireType, count);
    }

    /**
     * Whether {@code count} elements of at most {@code maxBytes} varint bytes each
     * are certain to fit in what is left of the buffer, so the element loop can run
     * with no per-element room test and no flush check.
     *
     * <p>The bound is not {@code count * maxBytes}: {@link #putVarint} assembles a
     * varint with an eight-byte store and so needs {@link #VARINT_ROOM} bytes of
     * room at <em>every</em> element, the last one included. What must fit is
     * therefore the first {@code count - 1} elements at their worst case plus a full
     * varint's room for the last — which for a {@code long} array is exactly
     * {@code count * 10}, the bound this path has always used.
     */
    private boolean bulkRoom(int count, int maxBytes) {
        return count > 0
                && (long) end - offset >= (long) (count - 1) * maxBytes + VARINT_ROOM;
    }

    /**
     * Write an array of unsigned 8-bit integers (each element zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, byte[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        if (bulkRoom(data.length, W_BYTE)) {
            byte[] b = buffer;
            int p = offset;
            for (byte elem : data) {
                p = putVarint(b, p, elem & 0xFFL);
            }
            offset = p;
            return;
        }
        for (byte elem : data) {
            writeVarint(elem & 0xFFL);
        }
    }

    /**
     * Write an array of unsigned 16-bit integers (each element zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, short[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        if (bulkRoom(data.length, W_SHORT)) {
            byte[] b = buffer;
            int p = offset;
            for (short elem : data) {
                p = putVarint(b, p, elem & 0xFFFFL);
            }
            offset = p;
            return;
        }
        for (short elem : data) {
            writeVarint(elem & 0xFFFFL);
        }
    }

    /**
     * Write an array of unsigned 32-bit integers (each element zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, int[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        if (bulkRoom(data.length, W_INT)) {
            byte[] b = buffer;
            int p = offset;
            for (int elem : data) {
                p = putVarint(b, p, elem & 0xFFFFFFFFL);
            }
            offset = p;
            return;
        }
        for (int elem : data) {
            writeVarint(elem & 0xFFFFFFFFL);
        }
    }

    /**
     * Write an array of unsigned 64-bit integers (each {@code long} treated as an
     * unsigned value).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, long[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        if (bulkRoom(data.length, W_LONG)) {
            byte[] b = buffer;
            int p = offset;
            for (long elem : data) {
                p = putVarint(b, p, elem);
            }
            offset = p;
            return;
        }
        for (long elem : data) {
            writeVarint(elem);
        }
    }

    /**
     * Write an array of signed 8-bit integers.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArraySigned(int id, byte[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.length);
        if (bulkRoom(data.length, W_BYTE)) {
            byte[] b = buffer;
            int p = offset;
            for (byte elem : data) {
                p = putVarint(b, p, zigzagEncode(elem));
            }
            offset = p;
            return;
        }
        for (byte elem : data) {
            writeVarint(zigzagEncode(elem));
        }
    }

    /**
     * Write an array of signed 16-bit integers.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArraySigned(int id, short[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.length);
        if (bulkRoom(data.length, W_SHORT)) {
            byte[] b = buffer;
            int p = offset;
            for (short elem : data) {
                p = putVarint(b, p, zigzagEncode(elem));
            }
            offset = p;
            return;
        }
        for (short elem : data) {
            writeVarint(zigzagEncode(elem));
        }
    }

    /**
     * Write an array of signed 32-bit integers.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArraySigned(int id, int[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.length);
        if (bulkRoom(data.length, W_INT)) {
            byte[] b = buffer;
            int p = offset;
            for (int elem : data) {
                p = putVarint(b, p, zigzagEncode(elem));
            }
            offset = p;
            return;
        }
        for (int elem : data) {
            writeVarint(zigzagEncode(elem));
        }
    }

    /**
     * Write an array of signed 64-bit integers.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArraySigned(int id, long[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_SIGNED, data.length);
        if (bulkRoom(data.length, W_LONG)) {
            byte[] b = buffer;
            int p = offset;
            for (long elem : data) {
                p = putVarint(b, p, zigzagEncode(elem));
            }
            offset = p;
            return;
        }
        for (long elem : data) {
            writeVarint(zigzagEncode(elem));
        }
    }

    /**
     * Write an array of 32-bit floats.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayFp32(int id, float[] data) throws IOException {
        writeArrayHeader(id, T_FIXLENARRAY, data.length);
        // §4.8: a fixlen array always carries its fixlen_word (the shared element
        // subtype/width), even when empty, so an empty fp32 array is
        // distinguishable on the wire from an empty fp64 array. The payload loop
        // simply runs zero times when the array is empty.
        writeVarint((4L << 3) | FixlenType.FP32.raw());
        int p = offset;
        if ((long) end - p >= (long) data.length * 4) {
            // The whole payload fits: no element can overflow, so run a tight loop
            // with no per-element room check or flush test.
            byte[] b = buffer;
            for (float v : data) {
                LE_INT.set(b, p, Float.floatToRawIntBits(v));
                p += 4;
            }
            offset = p;
            return;
        }
        for (float v : data) {
            putLe32(Float.floatToRawIntBits(v));
        }
    }

    /**
     * Write an array of 64-bit floats.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayFp64(int id, double[] data) throws IOException {
        writeArrayHeader(id, T_FIXLENARRAY, data.length);
        // §4.8: a fixlen array always carries its fixlen_word (the shared element
        // subtype/width), even when empty, so an empty fp64 array is
        // distinguishable on the wire from an empty fp32 array. The payload loop
        // simply runs zero times when the array is empty.
        writeVarint((8L << 3) | FixlenType.FP64.raw());
        int p = offset;
        if ((long) end - p >= (long) data.length * 8) {
            byte[] b = buffer;
            for (double v : data) {
                LE_LONG.set(b, p, Double.doubleToRawLongBits(v));
                p += 8;
            }
            offset = p;
            return;
        }
        for (double v : data) {
            putLe64(Double.doubleToRawLongBits(v));
        }
    }

    // --- sequence writers ---------------------------------------------------

    /**
     * Open a nested sequence with the given field {@code id}, whose header is
     * <b>held back</b> until the sequence turns out to have content. Fields written
     * until the matching close belong to the sequence and form a fresh id scope.
     *
     * <p>MESSAGE_SPEC §2 omits a sequence-typed field whose value equals its
     * declared default, and "not one child was written" is exactly that condition —
     * evaluated per child field, recursively, for free, because the message layer
     * already omits every child equal to its default. A sequence closed with
     * nothing in it therefore emits <b>nothing</b> instead of a two-byte empty
     * frame, and an all-default message becomes the empty byte string.
     *
     * <p>The predicate is never a byte image of the object, so struct padding
     * cannot influence it, and a non-zero nested default is handled by the caller's
     * ordinary per-field test.
     *
     * <p>Held-back ids are encoder <em>state</em>, not buffer content, so a flush
     * can never split a pending run: an output buffer far smaller than the message
     * produces exactly the one-shot bytes.
     *
     * <p>The hold-back reaches the full {@link Sofab#MAX_DEPTH}: the pending run
     * grows on demand, so this encoder is canonical at every legal nesting depth.
     * Bounding the run and framing eagerly beyond the bound is an allowance for
     * heap-free profiles only (CORELIB_PLAN §6), and the JVM is not one.
     *
     * <p>This is the only way to open a sequence. How it closes decides whether a
     * contentless one survives: {@link #writeSequenceEnd()} drops it,
     * {@link #writeSequenceEndKeep()} forces the frame out.
     *
     * @param id field id of the sequence
     * @throws IOException on buffer overflow or sink failure
     * @throws SofabException with {@link SofabError#ARGUMENT} if opening this
     *         sequence would exceed {@link Sofab#MAX_DEPTH} nesting levels, or if
     *         {@code id} is out of range
     */
    public void writeSequenceBeginLazy(int id) throws IOException {
        if (depth >= Sofab.MAX_DEPTH) {
            throw new SofabException(SofabError.ARGUMENT, "sequence nesting exceeds MAX_DEPTH");
        }
        // As in beginField: ID_MAX is INT32_MAX, so the sign test is the whole
        // range check. This opener does not route its header through beginField —
        // it holds it back — so it carries its own.
        if (id < 0) {
            throw new SofabException(SofabError.ARGUMENT, "id " + id);
        }
        if (nPending == 0) {
            // Depth-one hold-back: a scalar, so an encoder whose sequences never
            // nest never allocates the overflow array at all.
            pending0 = id;
            nPending = 1;
        } else {
            int slot = nPending - 1; // pending[] carries entries two and beyond
            if (pending == null) {
                pending = new int[PENDING_INITIAL];
            } else if (slot == pending.length) {
                // Grow rather than fall back to eager framing. nPending <= depth <
                // MAX_DEPTH, so the run can never need more than MAX_DEPTH slots.
                int grown = Math.min(pending.length * 2, Sofab.MAX_DEPTH);
                pending = Arrays.copyOf(pending, grown);
            }
            pending[slot] = id;
            nPending++;
        }
        depth++;
    }

    /**
     * Close the most recently opened nested sequence, letting it <b>vanish</b> if
     * it received no content.
     *
     * <p>Use it wherever absence encodes the same value as an empty frame: a
     * {@code struct}/{@code union} field, and an array field whose declared
     * {@code default} is the empty collection (MESSAGE_SPEC §2). Where the frame
     * must be visible, close with {@link #writeSequenceEndKeep()} instead.
     *
     * <p>An end with no matching begin is not rejected here: the encoder writes
     * what it is told and the resulting bytes are then malformed, which is the
     * decoder's verdict to make. Every other port behaves this way; the depth
     * counter simply stops at zero so the {@code MAX_DEPTH} check on begin
     * cannot be fooled by an underflow.
     *
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeSequenceEnd() throws IOException {
        if (nPending != 0) {
            // The innermost open sequence is the last held-back one: drop it,
            // header and end marker both.
            nPending--;
            if (depth > 0) {
                depth--;
            }
            return;
        }
        writeIdType(0, T_SEQUENCE_END);
        if (depth > 0) {
            depth--;
        }
    }

    /**
     * Close the most recently opened nested sequence, <b>keeping</b> its frame even
     * when it received no content.
     *
     * <p>Behaves like a write: it first emits any held-back headers — this frame's
     * and every enclosing one's — and then the end marker, so an empty sequence
     * reaches the wire as {@code begin} + {@code end}.
     *
     * <p>Required wherever the frame carries information beyond its contents:
     * <ul>
     *   <li>a <b>wrapper-array element</b> ({@code struct}/{@code union}/nested
     *       row): element presence is what carries a dynamic array's length —
     *       <em>highest present id + 1</em> (MESSAGE_SPEC §5.1) — so dropping an
     *       all-default element would change the decoded length, not just the
     *       bytes;</li>
     *   <li>an array field already known to <b>differ from a non-empty declared
     *       {@code default}</b>: absence would reconstruct that default, so the
     *       empty frame is the only encoding of "explicitly empty" (§2, §3).</li>
     * </ul>
     *
     * <p>The two failure directions are not symmetric, which is why this is the
     * safe choice when in doubt: using it where {@link #writeSequenceEnd()} would
     * do costs one non-canonical empty frame that a decoder normalizes away, while
     * the reverse silently changes an array's length.
     *
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeSequenceEndKeep() throws IOException {
        if (nPending != 0) {
            commitPending();
        }
        writeIdType(0, T_SEQUENCE_END);
        if (depth > 0) {
            depth--;
        }
    }
}
