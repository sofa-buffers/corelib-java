/*
 * SofaBuffers Java - streaming output encoder (port of ostream.c).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.IOException;

import static org.sofabuffers.sofab.WireFormat.ID_MAX;
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
 * <p>Writes take a fast path that advances a cursor over the buffer with no
 * per-byte bounds check whenever the remaining room is known to be sufficient
 * (a varint is at most ten bytes; a float four or eight); a buffer-spanning slow
 * path that flushes mid-value is used only when the buffer is too small to hold
 * the value outright. Raw string / blob payloads are copied in bulk up to each
 * buffer boundary. The wire output is identical regardless of buffer size.
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

    private byte[] buffer;
    private int end;
    private int offset;
    private final FlushSink sink;

    /** Number of nested sequences currently open; bounded by {@link Sofab#MAX_DEPTH}. */
    private int depth;

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
     * @param buffer caller-owned output buffer (length &gt; 0)
     * @param offset initial write position ({@code 0..buffer.length})
     * @param sink   flush sink, or {@code null} for none
     */
    public OStream(byte[] buffer, int offset, FlushSink sink) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("buffer must be non-empty");
        }
        if (offset < 0 || offset > buffer.length) {
            throw new IllegalArgumentException("offset out of range");
        }
        this.buffer = buffer;
        this.end = buffer.length;
        this.offset = offset;
        this.sink = sink;
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
     * @return number of bytes that were pending
     * @throws IOException if the sink fails
     */
    public int flush() throws IOException {
        int used = offset;
        if (used > 0 && sink != null) {
            sink.flush(buffer, 0, used);
            offset = 0;
        }
        return used;
    }

    /**
     * Replace the active buffer (typically from within a flush sink), resuming
     * writes at {@code offset} in the new buffer.
     *
     * @param buffer new caller-owned output buffer (length &gt; 0)
     * @param offset initial write position ({@code 0..buffer.length})
     */
    public void bufferSet(byte[] buffer, int offset) {
        if (buffer == null || buffer.length == 0) {
            throw new IllegalArgumentException("buffer must be non-empty");
        }
        if (offset < 0 || offset > buffer.length) {
            throw new IllegalArgumentException("offset out of range");
        }
        this.buffer = buffer;
        this.end = buffer.length;
        this.offset = offset;
    }

    // --- primitives ---------------------------------------------------------

    /** Hand the full buffer to the sink and resume at its start, or fail if none. */
    private void flushFull() throws IOException {
        if (sink == null) {
            throw new SofabException(SofabError.BUFFER_FULL);
        }
        sink.flush(buffer, 0, offset);
        offset = 0;
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
        // guaranteed, advance a cursor over the buffer with no per-byte bounds or
        // flush check (the protobuf "write into a contiguous buffer" technique).
        int p = offset;
        if (end - p >= 10) {
            byte[] b = buffer;
            while ((value & ~0x7FL) != 0) {
                b[p++] = (byte) ((value & 0x7F) | 0x80);
                value >>>= 7;
            }
            b[p++] = (byte) value;
            offset = p;
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
            byte[] b = buffer;
            b[p] = (byte) bits;
            b[p + 1] = (byte) (bits >>> 8);
            b[p + 2] = (byte) (bits >>> 16);
            b[p + 3] = (byte) (bits >>> 24);
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
            byte[] b = buffer;
            b[p] = (byte) bits;
            b[p + 1] = (byte) (bits >>> 8);
            b[p + 2] = (byte) (bits >>> 16);
            b[p + 3] = (byte) (bits >>> 24);
            b[p + 4] = (byte) (bits >>> 32);
            b[p + 5] = (byte) (bits >>> 40);
            b[p + 6] = (byte) (bits >>> 48);
            b[p + 7] = (byte) (bits >>> 56);
            offset = p + 8;
            return;
        }
        for (int i = 0; i < 8; i++) {
            pushByte((int) ((bits >>> (i * 8)) & 0xFF));
        }
    }

    /** Validate {@code id} and write the field header varint {@code (id << 3) | wireType}. */
    private void writeIdType(int id, int wireType) throws IOException {
        if (id < 0 || id > ID_MAX) {
            throw new SofabException(SofabError.ARGUMENT, "id " + id);
        }
        writeVarint(((long) id << 3) | wireType);
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
        writeIdType(id, T_VARINT_UNSIGNED);
        writeVarint(value);
    }

    /**
     * Write a signed-integer field (ZigZag + varint).
     *
     * @param id    field id
     * @param value signed value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeSigned(int id, long value) throws IOException {
        writeIdType(id, T_VARINT_SIGNED);
        writeVarint(zigzagEncode(value));
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
     * @param id      field id
     * @param data    payload bytes (may be {@code null} only if {@code length} is 0)
     * @param from    start offset within {@code data}
     * @param length  number of payload bytes
     * @param subtype fixed-length sub-type
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeFixlen(int id, byte[] data, int from, int length, FixlenType subtype) throws IOException {
        if (length < 0) {
            throw new SofabException(SofabError.ARGUMENT, "length " + length);
        }
        writeIdType(id, T_FIXLEN);
        writeVarint(((long) length << 3) | subtype.raw());
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
        writeIdType(id, T_FIXLEN);
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
        writeIdType(id, T_FIXLEN);
        writeVarint((8L << 3) | FixlenType.FP64.raw());
        putLe64(bits);
    }

    /**
     * Write a string field (raw UTF-8 bytes, no NUL on the wire).
     *
     * @param id   field id
     * @param text string value
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeString(int id, String text) throws IOException {
        // Encode UTF-8 straight into the output buffer instead of allocating an
        // intermediate byte[] per call (String.getBytes). One pass measures the
        // byte length for the fixlen header, the second emits the bytes.
        int n = utf8Length(text);
        writeIdType(id, T_FIXLEN);
        writeVarint(((long) n << 3) | FixlenType.STRING.raw());
        writeUtf8(text);
    }

    /** Exact UTF-8 byte length, matching {@link #writeUtf8} (malformed surrogate -&gt; '?'). */
    private static int utf8Length(String s) {
        int len = s.length();
        int bytes = 0;
        for (int i = 0; i < len; i++) {
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
                bytes += 1; // unpaired surrogate -> replacement '?'
            } else {
                bytes += 3;
            }
        }
        return bytes;
    }

    /** Emit {@code s} as UTF-8, matching {@code String.getBytes(UTF_8)} byte-for-byte. */
    private void writeUtf8(String s) throws IOException {
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
                pushByte('?');
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
     * Write an array field header (id header then element count). A zero count is
     * valid (§4.7) and yields exactly {@code [ header ][ count = 0 ]}; only a
     * negative count is rejected.
     */
    private void writeArrayHeader(int id, int wireType, int count) throws IOException {
        if (count < 0) {
            throw new SofabException(SofabError.ARGUMENT, "negative count " + count);
        }
        writeIdType(id, wireType);
        writeVarint(count);
    }

    /**
     * Write an array of unsigned 8-bit integers (each byte zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, byte[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        for (byte e : data) {
            writeVarint(e & 0xFFL);
        }
    }

    /**
     * Write an array of unsigned 16-bit integers (each short zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, short[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        for (short e : data) {
            writeVarint(e & 0xFFFFL);
        }
    }

    /**
     * Write an array of unsigned 32-bit integers (each int zero-extended).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, int[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        byte[] b = buffer;
        int p = offset;
        int e = end;
        for (int elem : data) {
            long v = elem & 0xFFFFFFFFL;
            if (e - p < 10) {
                offset = p;
                writeVarintSlow(v);
                b = buffer;
                p = offset;
                e = end;
                continue;
            }
            while ((v & ~0x7FL) != 0) {
                b[p++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b[p++] = (byte) v;
        }
        offset = p;
    }

    /**
     * Write an array of unsigned 64-bit integers (each {@code long} treated as
     * an unsigned value).
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayUnsigned(int id, long[] data) throws IOException {
        writeArrayHeader(id, T_VARINTARRAY_UNSIGNED, data.length);
        byte[] b = buffer;
        int p = offset;
        int e = end;
        for (long elem : data) {
            long v = elem;
            if (e - p < 10) {
                offset = p;
                writeVarintSlow(v);
                b = buffer;
                p = offset;
                e = end;
                continue;
            }
            while ((v & ~0x7FL) != 0) {
                b[p++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b[p++] = (byte) v;
        }
        offset = p;
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
        for (byte e : data) {
            writeVarint(zigzagEncode(e));
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
        for (short e : data) {
            writeVarint(zigzagEncode(e));
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
        byte[] b = buffer;
        int p = offset;
        int e = end;
        for (int elem : data) {
            long v = zigzagEncode(elem);
            if (e - p < 10) {
                offset = p;
                writeVarintSlow(v);
                b = buffer;
                p = offset;
                e = end;
                continue;
            }
            while ((v & ~0x7FL) != 0) {
                b[p++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b[p++] = (byte) v;
        }
        offset = p;
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
        byte[] b = buffer;
        int p = offset;
        int e = end;
        for (long elem : data) {
            long v = zigzagEncode(elem);
            if (e - p < 10) {
                offset = p;
                writeVarintSlow(v);
                b = buffer;
                p = offset;
                e = end;
                continue;
            }
            while ((v & ~0x7FL) != 0) {
                b[p++] = (byte) ((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            b[p++] = (byte) v;
        }
        offset = p;
    }

    /**
     * Write an array of 32-bit floats.
     *
     * @param id   field id
     * @param data elements
     * @throws IOException on buffer overflow or sink failure
     */
    public void writeArrayFp32(int id, float[] data) throws IOException {
        writeIdType(id, T_FIXLENARRAY);
        writeVarint(data.length);
        // §4.8: a fixlen array always carries its fixlen_word (the shared element
        // subtype/width), even when empty, so an empty fp32 array is
        // distinguishable on the wire from an empty fp64 array. The payload loop
        // simply runs zero times when the array is empty.
        writeVarint((4L << 3) | FixlenType.FP32.raw());
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
        writeIdType(id, T_FIXLENARRAY);
        writeVarint(data.length);
        // §4.8: a fixlen array always carries its fixlen_word (the shared element
        // subtype/width), even when empty, so an empty fp64 array is
        // distinguishable on the wire from an empty fp32 array. The payload loop
        // simply runs zero times when the array is empty.
        writeVarint((8L << 3) | FixlenType.FP64.raw());
        for (double v : data) {
            putLe64(Double.doubleToRawLongBits(v));
        }
    }

    // --- sequence writers ---------------------------------------------------

    /**
     * Open a nested sequence with the given field {@code id}. Fields written
     * until the matching {@link #writeSequenceEnd()} belong to the sequence and
     * form a fresh id scope.
     *
     * @param id field id of the sequence
     * @throws IOException on buffer overflow or sink failure
     * @throws SofabException with {@link SofabError#ARGUMENT} if opening this
     *         sequence would exceed {@link Sofab#MAX_DEPTH} nesting levels
     */
    public void writeSequenceBegin(int id) throws IOException {
        if (depth >= Sofab.MAX_DEPTH) {
            throw new SofabException(SofabError.ARGUMENT, "sequence nesting exceeds MAX_DEPTH");
        }
        writeIdType(id, T_SEQUENCE_START);
        depth++;
    }

    /**
     * Close the most recently opened nested sequence.
     *
     * @throws IOException on buffer overflow or sink failure
     * @throws SofabException with {@link SofabError#USAGE} if there is no open
     *         sequence to close
     */
    public void writeSequenceEnd() throws IOException {
        if (depth == 0) {
            throw new SofabException(SofabError.USAGE, "no open sequence");
        }
        writeIdType(0, T_SEQUENCE_END);
        depth--;
    }
}
