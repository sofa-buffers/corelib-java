/*
 * SofaBuffers Java - streaming input decoder (port of istream.c).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.sofabuffers.sofab.WireFormat.ARRAY_MAX;
import static org.sofabuffers.sofab.WireFormat.ID_MAX;
import static org.sofabuffers.sofab.WireFormat.T_FIXLEN;
import static org.sofabuffers.sofab.WireFormat.T_FIXLENARRAY;
import static org.sofabuffers.sofab.WireFormat.T_SEQUENCE_END;
import static org.sofabuffers.sofab.WireFormat.T_SEQUENCE_START;
import static org.sofabuffers.sofab.WireFormat.T_VARINTARRAY_SIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINTARRAY_UNSIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINT_SIGNED;
import static org.sofabuffers.sofab.WireFormat.T_VARINT_UNSIGNED;
import static org.sofabuffers.sofab.WireFormat.VALUE_BITS;
import static org.sofabuffers.sofab.WireFormat.zigzagDecode;

/**
 * Streaming SofaBuffers decoder.
 *
 * <p>Feed {@code IStream} arbitrary chunks with {@link #feed}; it parses field
 * headers and pushes decoded fields to your {@link Visitor}. Because all parse
 * state lives inside the decoder, a message may be split across any number of
 * {@code feed} calls at any byte boundary — true streaming on the input side.
 *
 * <p><b>Two decode paths.</b> When a clean field boundary and a contiguous run of
 * bytes are both in hand, the decoder advances a pointer straight over the buffer,
 * reading whole field headers, scalars and array elements with no per-byte state
 * dispatch (the "advance a pointer over a contiguous buffer" technique). The
 * moment a field — or array element — would run past the end of the supplied
 * bytes, a resumable byte-at-a-time state machine takes over, suspends, and
 * resumes on the next {@code feed}. The two paths are byte-for-byte equivalent;
 * the fast path simply removes the per-byte overhead from the common case where a
 * message (or a large chunk of one) arrives in a single feed.
 *
 * <p>Unlike the C decoder there is no per-field "bind a destination" step and no
 * explicit skip bookkeeping: a {@link Visitor} simply ignores fields it does not
 * care about. Scalars and floats are delivered whole; string / blob payloads are
 * delivered in chunks (so they may exceed RAM); array elements are announced
 * with {@link Visitor#arrayBegin} and then delivered through the scalar / float
 * callbacks.
 *
 * <p><b>Three-valued outcome (MESSAGE_SPEC §7).</b> Malformed bytes throw a
 * {@link SofabException} with {@link SofabError#INVALID_MSG} from {@code feed}.
 * Running out of bytes mid-field is <em>not</em> an error: {@code feed} suspends
 * and returns normally, and a subsequent {@code feed} resumes it. To tell a
 * message that is <em>complete</em> from one that was <em>truncated</em>, call
 * {@link #status()} after the final {@code feed}: it returns
 * {@link DecodeStatus#COMPLETE} at a clean field boundary or
 * {@link DecodeStatus#INCOMPLETE} if the last bytes ended inside a field or with
 * an open (unclosed) sequence. {@code status()} is a pure, non-throwing accessor
 * — there is no required finish/finalize step; the caller owns end-of-input.
 *
 * <p>This class is not thread-safe; decode one message from one thread. Reuse an
 * instance for a new message only after the previous one is fully consumed (or
 * by constructing a fresh {@code IStream}).
 *
 * <h2>Example</h2>
 * <pre>{@code
 * class Sink implements Visitor {
 *     long a; long b;
 *     public void unsigned(int id, long v) { if (id == 1) a = v; }
 *     public void signed(int id, long v)   { if (id == 2) b = v; }
 * }
 * Sink sink = new Sink();
 * IStream is = new IStream();
 * is.feed(buf, sink);
 * if (is.status() == DecodeStatus.INCOMPLETE) {
 *     // buf ended mid-message; wait for more bytes (or treat as truncation).
 * }
 * }</pre>
 */
public final class IStream {

    private enum State {
        IDLE,
        VARINT_UNSIGNED,
        VARINT_SIGNED,
        FIXLEN_LEN,
        FIXLEN_VAL,
        FIXLEN_RAW,
        ARRAY_COUNT,
    }

    // incremental varint accumulator
    private long varintValue;
    private int varintShift;
    private long varintOut;

    private State state = State.IDLE;
    private int id;

    // array context
    private ArrayKind arrayKind = ArrayKind.UNSIGNED;
    private int arrayRemaining;
    private boolean inArray;

    // fixlen context
    private FixlenType fixlenType = FixlenType.FP32;
    private int fixlenTotal;
    private int fixlenRemaining;
    private final byte[] acc = new byte[8];
    private int accLen;

    // sequence nesting depth (for balanced start/end validation)
    private long depth;

    /** Create a fresh decoder ready to accept a new message. */
    public IStream() {
    }

    /**
     * Report whether the bytes fed so far end exactly at a field boundary. Call
     * after the final {@link #feed}: returns {@link DecodeStatus#COMPLETE} when the
     * decoder is at a clean field boundary with no open sequence, or
     * {@link DecodeStatus#INCOMPLETE} when the last bytes ended inside a field — a
     * partial varint (field header or value), a fixlen/array payload shorter than
     * declared, an array with elements still pending — or with an open (unclosed)
     * nested sequence ({@code depth != 0}).
     *
     * <p>Per the finish-less spec (MESSAGE_SPEC §7) this is a pure accessor: it
     * never throws, never mutates decoder state, and never promotes an incomplete
     * decode to an error. The caller owns end-of-input and decides whether a
     * trailing {@code INCOMPLETE} is a truncation it cares about. A <em>malformed</em>
     * message has already thrown {@link SofabError#INVALID_MSG} from {@link #feed},
     * so this method returns only {@link DecodeStatus#COMPLETE} or
     * {@link DecodeStatus#INCOMPLETE}, never {@link DecodeStatus#INVALID}.
     *
     * @return {@link DecodeStatus#COMPLETE} at a clean boundary, otherwise
     *         {@link DecodeStatus#INCOMPLETE}
     */
    public DecodeStatus status() {
        // COMPLETE only at a true field boundary: no partial field header varint
        // (varintShift == 0 while IDLE), no in-progress value/payload/array element
        // (state == IDLE covers the resumable machine and mid-array between
        // elements), and every opened sequence closed (depth == 0). Anything else
        // means the bytes ended inside a field or an open sequence — INCOMPLETE.
        if (state == State.IDLE && varintShift == 0 && depth == 0) {
            return DecodeStatus.COMPLETE;
        }
        return DecodeStatus.INCOMPLETE;
    }

    /**
     * Feed a whole chunk of encoded bytes, pushing decoded fields to
     * {@code visitor}.
     *
     * @param data    encoded bytes
     * @param visitor sink for decoded fields
     * @throws SofabException with {@link SofabError#INVALID_MSG} on malformed input
     */
    public void feed(byte[] data, Visitor visitor) throws SofabException {
        feed(data, 0, data.length, visitor);
    }

    /**
     * Feed a slice of encoded bytes, pushing decoded fields to {@code visitor}.
     * Decoding can continue across many {@code feed} calls; the decoder keeps
     * all state internally.
     *
     * @param data    backing array
     * @param off     start offset
     * @param len     number of bytes to consume
     * @param visitor sink for decoded fields
     * @throws SofabException with {@link SofabError#INVALID_MSG} on malformed input
     */
    public void feed(byte[] data, int off, int len, Visitor visitor) throws SofabException {
        int i = off;
        final int end = off + len;
        while (i < end) {
            // Fast path first: at a clean field boundary with bytes in hand,
            // advance a pointer over the contiguous buffer decoding whole fields
            // (and whole array elements) with no per-byte state dispatch. This is
            // the steady state for whole-message feeds, so it is checked before
            // the split-field cases below.
            if (state == State.IDLE && varintShift == 0) {
                i = fastField(data, i, end, visitor);
                continue;
            }

            // Bulk path: stream string/blob payloads with one callback per chunk
            // rather than one per byte.
            if (state == State.FIXLEN_RAW) {
                int take = Math.min(end - i, fixlenRemaining);
                int chunkOffset = fixlenTotal - fixlenRemaining;
                if (fixlenType == FixlenType.STRING) {
                    visitor.string(id, fixlenTotal, chunkOffset, data, i, take);
                } else if (fixlenType == FixlenType.BLOB) {
                    visitor.blob(id, fixlenTotal, chunkOffset, data, i, take);
                } else {
                    throw new SofabException(SofabError.INVALID_MSG, "raw fixlen type");
                }
                fixlenRemaining -= take;
                i += take;
                if (fixlenRemaining == 0) {
                    state = State.IDLE;
                }
                continue;
            }

            // A field whose bytes were split across feeds is finished one byte at a
            // time by the resumable state machine. {@code state != IDLE} covers a
            // partially read value/payload; {@code varintShift != 0} while IDLE
            // covers a partially read field header (header reading is the IDLE
            // state's varint accumulation).
            step(data[i] & 0xFF, visitor);
            i++;
        }
    }

    /**
     * Decode one whole field starting at a clean boundary ({@code state == IDLE},
     * no buffered header), reading directly from {@code data[i..end)}. Returns the
     * index just past the bytes consumed. When the field — or an array element —
     * cannot be completed within the buffer, the resumable state machine is armed
     * (via {@link #state}) and the index is left at the first byte the machine must
     * re-read, so a subsequent feed finishes it byte-at-a-time.
     */
    private int fastField(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        // --- field header varint (id << 3 | type); decoded into locals so nothing
        //     is committed until the whole header is present in this buffer ---
        long header;
        int p = i;
        if (end - p >= 10) {
            // Room for a max-length (10-byte) varint: decode with no per-byte
            // bounds check, continuation flagged by the sign bit of the raw byte.
            int b = data[p++];
            header = b & 0x7F;
            if (b < 0) {
                int shift = 7;
                do {
                    b = data[p++];
                    // Reject an overlong (>64-bit) varint: a terminating byte whose
                    // payload bits would spill past bit 63 is malformed, not truncated
                    // (MESSAGE_SPEC §4.1/§6.3).
                    int room = VALUE_BITS - shift;
                    if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                        throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                    }
                    header |= ((long) (b & 0x7F)) << shift;
                    shift += 7;
                } while (b < 0 && shift < VALUE_BITS);
                if (b < 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                }
            }
        } else {
            header = 0;
            int shift = 0;
            while (true) {
                if (p >= end) {
                    // Header runs past the buffer: feed this byte to the state machine
                    // (which accumulates the header in the IDLE state) and let the feed
                    // loop drive the rest.
                    step(data[i] & 0xFF, visitor);
                    return i + 1;
                }
                int b = data[p++] & 0xFF;
                int room = VALUE_BITS - shift;
                if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                }
                header |= ((long) (b & 0x7F)) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
                if (shift >= VALUE_BITS) {
                    throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                }
            }
        }
        int wireType = (int) (header & 0x07);
        long idValue = header >>> 3;
        if (idValue > ID_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "id " + idValue);
        }
        id = (int) idValue;
        inArray = false;

        switch (wireType) {
            case T_SEQUENCE_START:
                if (depth >= Sofab.MAX_DEPTH) {
                    throw new SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH");
                }
                depth++;
                visitor.sequenceBegin(id);
                return p;
            case T_SEQUENCE_END:
                if (depth == 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "dangling sequence end");
                }
                depth--;
                visitor.sequenceEnd();
                return p;
            case T_VARINT_UNSIGNED:
            case T_VARINT_SIGNED: {
                long val;
                int q = p;
                if (end - q >= 10) {
                    // Bounds-check-free decode (see the header varint above).
                    int b = data[q++];
                    val = b & 0x7F;
                    if (b < 0) {
                        int vs = 7;
                        do {
                            b = data[q++];
                            // Reject an overlong (>64-bit) varint (§4.1/§6.3).
                            int room = VALUE_BITS - vs;
                            if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                            }
                            val |= ((long) (b & 0x7F)) << vs;
                            vs += 7;
                        } while (b < 0 && vs < VALUE_BITS);
                        if (b < 0) {
                            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                        }
                    }
                } else {
                    val = 0;
                    int vs = 0;
                    while (true) {
                        if (q >= end) {
                            // Value spills past the buffer: the machine reads it from p.
                            state = (wireType == T_VARINT_UNSIGNED)
                                    ? State.VARINT_UNSIGNED : State.VARINT_SIGNED;
                            return p;
                        }
                        int b = data[q++] & 0xFF;
                        int room = VALUE_BITS - vs;
                        if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                        }
                        val |= ((long) (b & 0x7F)) << vs;
                        vs += 7;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                        if (vs >= VALUE_BITS) {
                            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                        }
                    }
                }
                if (wireType == T_VARINT_UNSIGNED) {
                    visitor.unsigned(id, val);
                } else {
                    visitor.signed(id, zigzagDecode(val));
                }
                return q;
            }
            case T_FIXLEN:
                return fastFixlenScalar(data, p, end, visitor);
            case T_VARINTARRAY_UNSIGNED:
                arrayKind = ArrayKind.UNSIGNED;
                return fastVarintArray(data, p, end, visitor, false);
            case T_VARINTARRAY_SIGNED:
                arrayKind = ArrayKind.SIGNED;
                return fastVarintArray(data, p, end, visitor, true);
            case T_FIXLENARRAY:
                arrayKind = ArrayKind.FIXLEN;
                return fastFixlenArray(data, p, end, visitor);
            default:
                throw new SofabException(SofabError.INVALID_MSG, "field type " + wireType);
        }
    }

    /** Fast path for a scalar fixlen field; {@code i} points at its length header. */
    private int fastFixlenScalar(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        long fh = 0;
        int shift = 0;
        int p = i;
        while (true) {
            if (p >= end) {
                state = State.FIXLEN_LEN; // machine re-reads the length header from i
                return i;
            }
            int b = data[p++] & 0xFF;
            int room = VALUE_BITS - shift;
            if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
            fh |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                break;
            }
            if (shift >= VALUE_BITS) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
        }
        FixlenType subtype = FixlenType.fromRaw((int) (fh & 0x07));
        long lengthValue = fh >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int length = (int) lengthValue;
        switch (subtype) {
            case FP32:
                if (length != 4) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + length);
                }
                if (end - p < 4) {
                    armFixlenVal(FixlenType.FP32, 4);
                    return p;
                }
                visitor.fp32(id, Float.intBitsToFloat(readLe32(data, p)));
                return p + 4;
            case FP64:
                if (length != 8) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + length);
                }
                if (end - p < 8) {
                    armFixlenVal(FixlenType.FP64, 8);
                    return p;
                }
                visitor.fp64(id, Double.longBitsToDouble(readLe64(data, p)));
                return p + 8;
            case STRING:
            case BLOB:
                fixlenType = subtype;
                fixlenTotal = length;
                fixlenRemaining = length;
                accLen = 0;
                if (length == 0) {
                    if (subtype == FixlenType.STRING) {
                        visitor.string(id, 0, 0, acc, 0, 0);
                    } else {
                        visitor.blob(id, 0, 0, acc, 0, 0);
                    }
                    state = State.IDLE;
                } else {
                    state = State.FIXLEN_RAW; // feed loop streams the payload in bulk
                }
                return p;
            default:
                throw new SofabException(SofabError.INVALID_MSG, "fixlen type");
        }
    }

    /** Fast path for an unsigned/signed varint array; {@code i} points at the count. */
    private int fastVarintArray(byte[] data, int i, int end, Visitor visitor, boolean signed)
            throws SofabException {
        int p = fastArrayHeader(data, i, end, visitor);
        if (p < 0) {
            return i; // count header spilled past the buffer; machine reads it (arrayKind set)
        }
        // Hoist the per-element fields into locals: the inner loop runs once per
        // array element, so reading {@code id} and writing {@code arrayRemaining}
        // straight from memory each time would add a load/store to every element.
        int remaining = arrayRemaining;
        final int fieldId = id;
        final int safe = end - 10; // last start position with a full varint's room
        while (remaining > 0) {
            long val;
            if (p <= safe) {
                // Bounds-check-free element decode: room for a max-length varint
                // is guaranteed. Fully unrolled (protobuf readRawVarint64 style):
                // reads at constant offsets from p let the JIT hoist a single
                // range check for the whole 10-byte window, and the straight-line
                // form drops the per-byte shift-counter/loop-branch overhead.
                int b = data[p++];
                val = b & 0x7F;
                if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 7;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 14;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 21;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 28;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 35;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 42;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 49;
                    if (b < 0) {
                    b = data[p++]; val |= (long) (b & 0x7F) << 56;
                    if (b < 0) {
                    b = data[p++];
                    // 10th byte: only bit 0 (value bit 63) may be set; higher payload
                    // bits are an overlong (>64-bit) varint, not truncation (§4.1/§6.3).
                    if (((b & 0x7F) >>> 1) != 0) {
                        throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                    }
                    val |= (long) (b & 0x7F) << 63;
                    if (b < 0) {
                        throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                    }}}}}}}}}}
            } else {
                val = 0;
                int vs = 0;
                int q = p;
                boolean complete = false;
                while (q < end) {
                    int b = data[q++] & 0xFF;
                    int room = VALUE_BITS - vs;
                    if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                        throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                    }
                    val |= ((long) (b & 0x7F)) << vs;
                    vs += 7;
                    if ((b & 0x80) == 0) {
                        complete = true;
                        break;
                    }
                    if (vs >= VALUE_BITS) {
                        throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
                    }
                }
                if (!complete) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining;
                    state = signed ? State.VARINT_SIGNED : State.VARINT_UNSIGNED;
                    return p;
                }
                p = q;
            }
            if (signed) {
                visitor.signed(fieldId, zigzagDecode(val));
            } else {
                visitor.unsigned(fieldId, val);
            }
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = State.IDLE;
        return p;
    }

    /** Fast path for a fixlen (fp32/fp64) array; {@code i} points at the count. */
    private int fastFixlenArray(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        int p = fastArrayHeader(data, i, end, visitor);
        if (p < 0) {
            return i; // count header spilled past the buffer; machine reads it
        }
        // §4.8: a fixlen array always carries its fixlen_word, even when empty, so
        // an empty fp32 array is distinguishable from an empty fp64 array. Read it
        // unconditionally; the payload loop below runs zero times when empty.
        // Element length header is encoded once and reused for every element.
        long fh = 0;
        int shift = 0;
        int lenStart = p;
        while (true) {
            if (p >= end) {
                state = State.FIXLEN_LEN; // machine re-reads the element header from lenStart
                return lenStart;
            }
            int b = data[p++] & 0xFF;
            int room = VALUE_BITS - shift;
            if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
            fh |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                break;
            }
            if (shift >= VALUE_BITS) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
        }
        FixlenType subtype = FixlenType.fromRaw((int) (fh & 0x07));
        long lengthValue = fh >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int size;
        if (subtype == FixlenType.FP32) {
            if (lengthValue != 4) {
                throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + lengthValue);
            }
            size = 4;
        } else if (subtype == FixlenType.FP64) {
            if (lengthValue != 8) {
                throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + lengthValue);
            }
            size = 8;
        } else {
            // String/blob are not valid as fixlen-array elements.
            throw new SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element");
        }
        fixlenType = subtype;
        fixlenTotal = size;
        int remaining = arrayRemaining;
        final int fieldId = id;
        while (remaining > 0) {
            if (end - p < size) {
                // Element bytes spill past the buffer: machine accumulates from p.
                arrayRemaining = remaining;
                fixlenRemaining = size;
                accLen = 0;
                state = State.FIXLEN_VAL;
                return p;
            }
            if (size == 4) {
                visitor.fp32(fieldId, Float.intBitsToFloat(readLe32(data, p)));
            } else {
                visitor.fp64(fieldId, Double.longBitsToDouble(readLe64(data, p)));
            }
            p += size;
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = State.IDLE;
        return p;
    }

    /**
     * Read and validate an array count header at {@code i}, then emit
     * {@code arrayBegin} and set up the array context ({@link #arrayRemaining},
     * {@link #inArray}). Returns the index after the count, or {@code -1} if the
     * count spilled past the buffer — in which case nothing is emitted and the
     * state machine re-reads the count from {@code i} ({@link #arrayKind} is
     * already set), so {@code arrayBegin} fires exactly once.
     */
    private int fastArrayHeader(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        long count = 0;
        int shift = 0;
        int p = i;
        while (true) {
            if (p >= end) {
                state = State.ARRAY_COUNT;
                return -1;
            }
            int b = data[p++] & 0xFF;
            int room = VALUE_BITS - shift;
            if (room < 7 && ((b & 0x7F) >>> room) != 0) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
            count |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                break;
            }
            if (shift >= VALUE_BITS) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
        }
        // count == 0 is a valid empty array (§4.7/§4.8); only an oversized count is rejected.
        if (Long.compareUnsigned(count, ARRAY_MAX) > 0) {
            throw new SofabException(SofabError.INVALID_MSG, "array count");
        }
        int c = (int) count;
        arrayRemaining = c;
        inArray = true;
        visitor.arrayBegin(id, arrayKind, c);
        return p;
    }

    /** Arm the state machine to accumulate a fixed-size fixlen value (fp32/fp64). */
    private void armFixlenVal(FixlenType type, int size) {
        fixlenType = type;
        fixlenTotal = size;
        fixlenRemaining = size;
        accLen = 0;
        state = State.FIXLEN_VAL;
    }

    /** Read four little-endian bytes from {@code d} at {@code p} as an {@code int}. */
    private static int readLe32(byte[] d, int p) {
        return (d[p] & 0xFF)
                | ((d[p + 1] & 0xFF) << 8)
                | ((d[p + 2] & 0xFF) << 16)
                | ((d[p + 3] & 0xFF) << 24);
    }

    /** Read eight little-endian bytes from {@code d} at {@code p} as a {@code long}. */
    private static long readLe64(byte[] d, int p) {
        return (d[p] & 0xFFL)
                | ((d[p + 1] & 0xFFL) << 8)
                | ((d[p + 2] & 0xFFL) << 16)
                | ((d[p + 3] & 0xFFL) << 24)
                | ((d[p + 4] & 0xFFL) << 32)
                | ((d[p + 5] & 0xFFL) << 40)
                | ((d[p + 6] & 0xFFL) << 48)
                | ((d[p + 7] & 0xFFL) << 56);
    }

    /**
     * Resumable state machine: feed one byte at the current {@link #state}. This
     * is the byte-at-a-time counterpart to the {@code fast*} path, used whenever a
     * field, value or array element was split across {@code feed} calls. Each
     * {@code step*} handler consumes the byte, and on completing its value emits to
     * the visitor and transitions {@link #state} to the next field or element.
     */
    private void step(int b, Visitor visitor) throws SofabException {
        switch (state) {
            case IDLE:            stepIdle(b, visitor); break;
            case VARINT_UNSIGNED: stepVarintUnsigned(b, visitor); break;
            case VARINT_SIGNED:   stepVarintSigned(b, visitor); break;
            case FIXLEN_LEN:      stepFixlenLen(b, visitor); break;
            case FIXLEN_VAL:      stepFixlenVal(b, visitor); break;
            case ARRAY_COUNT:     stepArrayCount(b, visitor); break;
            default: /* FIXLEN_RAW handled in feed's bulk path */ break;
        }
    }

    /**
     * Feed one byte into the varint accumulator.
     *
     * @return {@code true} if a complete value is now in {@link #varintOut};
     *         {@code false} if more bytes are needed
     * @throws SofabException if the varint is longer than the value type allows
     */
    private boolean varintPush(int b) throws SofabException {
        // Reject an overlong (>64-bit) varint: payload bits that would spill past
        // bit 63 are malformed, not silently truncated (MESSAGE_SPEC §4.1/§6.3).
        int room = VALUE_BITS - varintShift;
        if (room < 7 && ((b & 0x7F) >>> room) != 0) {
            varintValue = 0;
            varintShift = 0;
            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
        }
        varintValue |= ((long) (b & 0x7F)) << varintShift;
        varintShift += 7;

        if ((b & 0x80) == 0) {
            varintOut = varintValue;
            varintValue = 0;
            varintShift = 0;
            return true;
        }

        if (varintShift >= VALUE_BITS) {
            varintValue = 0;
            varintShift = 0;
            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
        }
        return false;
    }

    /**
     * Accumulate the field-header varint at a clean boundary; once complete,
     * validate the id, record the wire type, and arm the state for the value that
     * follows. Sequence start/end are emitted here and leave the machine
     * {@code IDLE} (they carry no value).
     */
    private void stepIdle(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long header = varintOut;
        int wireType = (int) (header & 0x07);
        long idValue = header >>> 3;
        if (idValue > ID_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "id " + idValue);
        }
        id = (int) idValue;
        inArray = false;

        switch (wireType) {
            case T_VARINT_UNSIGNED:
                state = State.VARINT_UNSIGNED;
                break;
            case T_VARINT_SIGNED:
                state = State.VARINT_SIGNED;
                break;
            case T_FIXLEN:
                state = State.FIXLEN_LEN;
                break;
            case T_VARINTARRAY_UNSIGNED:
                arrayKind = ArrayKind.UNSIGNED;
                state = State.ARRAY_COUNT;
                break;
            case T_VARINTARRAY_SIGNED:
                arrayKind = ArrayKind.SIGNED;
                state = State.ARRAY_COUNT;
                break;
            case T_FIXLENARRAY:
                arrayKind = ArrayKind.FIXLEN;
                state = State.ARRAY_COUNT;
                break;
            case T_SEQUENCE_START:
                if (depth >= Sofab.MAX_DEPTH) {
                    throw new SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH");
                }
                depth++;
                visitor.sequenceBegin(id);
                // stays IDLE
                break;
            case T_SEQUENCE_END:
                if (depth == 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "dangling sequence end");
                }
                depth--;
                visitor.sequenceEnd();
                // stays IDLE
                break;
            default:
                throw new SofabException(SofabError.INVALID_MSG, "field type " + wireType);
        }
    }

    /**
     * Accumulate an unsigned varint value; on completion emit it and advance to
     * the next array element or back to idle. Serves both scalar fields and
     * unsigned-array elements.
     */
    private void stepVarintUnsigned(int b, Visitor visitor) throws SofabException {
        if (varintPush(b)) {
            visitor.unsigned(id, varintOut);
            advanceAfterElement();
        }
    }

    /**
     * Accumulate a signed varint value (ZigZag-decoded on completion); otherwise
     * the signed counterpart of {@link #stepVarintUnsigned}.
     */
    private void stepVarintSigned(int b, Visitor visitor) throws SofabException {
        if (varintPush(b)) {
            visitor.signed(id, zigzagDecode(varintOut));
            advanceAfterElement();
        }
    }

    /** Shared "next element or back to idle" logic for varint scalars/arrays. */
    private void advanceAfterElement() {
        if (inArray) {
            arrayRemaining--;
            if (arrayRemaining > 0) {
                return; // stay in the same state for the next element
            }
            inArray = false;
        }
        state = State.IDLE;
    }

    /**
     * Accumulate a fixlen length header ({@code (len << 3) | subtype}). Floats arm
     * {@link State#FIXLEN_VAL} to read their bytes; a non-empty string/blob arms
     * {@link State#FIXLEN_RAW} so the payload streams in bulk, while an empty one
     * is emitted immediately. String/blob are rejected as fixlen-array elements.
     */
    private void stepFixlenLen(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long header = varintOut;
        FixlenType subtype = FixlenType.fromRaw((int) (header & 0x07));
        long lengthValue = header >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int length = (int) lengthValue;

        fixlenType = subtype;
        fixlenTotal = length;
        fixlenRemaining = length;
        accLen = 0;

        switch (subtype) {
            case FP32:
                if (length != 4) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + length);
                }
                state = afterFixlenWord();
                break;
            case FP64:
                if (length != 8) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + length);
                }
                state = afterFixlenWord();
                break;
            case STRING:
            case BLOB:
                // String/blob are not valid as fixlen-array elements.
                if (inArray) {
                    throw new SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element");
                }
                if (length == 0) {
                    if (subtype == FixlenType.STRING) {
                        visitor.string(id, 0, 0, acc, 0, 0);
                    } else {
                        visitor.blob(id, 0, 0, acc, 0, 0);
                    }
                    state = State.IDLE;
                } else {
                    state = State.FIXLEN_RAW;
                }
                break;
            default:
                throw new SofabException(SofabError.INVALID_MSG, "fixlen type");
        }
    }

    /**
     * Next state after a fixlen_word has been read. For an empty fixlen array the
     * word is the whole field — no payload follows (§4.8) — so the array is closed
     * and the machine returns to idle; otherwise the fixed-size value bytes follow.
     */
    private State afterFixlenWord() {
        if (inArray && arrayRemaining == 0) {
            inArray = false;
            return State.IDLE;
        }
        return State.FIXLEN_VAL;
    }

    /**
     * Accumulate the fixed-size bytes of a float value into {@link #acc}; once all
     * are in, decode the fp32/fp64 from little-endian, emit it, and advance to the
     * next array element (reusing the element size) or back to idle.
     */
    private void stepFixlenVal(int b, Visitor visitor) throws SofabException {
        acc[accLen++] = (byte) b;
        fixlenRemaining--;
        if (fixlenRemaining != 0) {
            return;
        }

        if (fixlenType == FixlenType.FP32) {
            int bits = (acc[0] & 0xFF)
                    | ((acc[1] & 0xFF) << 8)
                    | ((acc[2] & 0xFF) << 16)
                    | ((acc[3] & 0xFF) << 24);
            visitor.fp32(id, Float.intBitsToFloat(bits));
        } else if (fixlenType == FixlenType.FP64) {
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= ((long) (acc[i] & 0xFF)) << (i * 8);
            }
            visitor.fp64(id, Double.longBitsToDouble(bits));
        } else {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen value type");
        }

        // Next array element (reuse the element size) or back to idle.
        if (inArray) {
            arrayRemaining--;
            if (arrayRemaining > 0) {
                fixlenRemaining = fixlenTotal;
                accLen = 0;
                return;
            }
            inArray = false;
        }
        state = State.IDLE;
    }

    /**
     * Accumulate an array count header; on completion validate it, emit
     * {@link Visitor#arrayBegin} once, set up the array context, and arm the
     * per-element state matching {@link #arrayKind} (varint or fixlen).
     */
    private void stepArrayCount(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long count = varintOut;
        // count == 0 is a valid empty array (§4.7/§4.8); only an oversized count is rejected.
        if (Long.compareUnsigned(count, ARRAY_MAX) > 0) {
            throw new SofabException(SofabError.INVALID_MSG, "array count");
        }
        int c = (int) count;
        arrayRemaining = c;
        inArray = true;
        visitor.arrayBegin(id, arrayKind, c);

        if (c == 0 && arrayKind != ArrayKind.FIXLEN) {
            // Empty varint array: no elements and no fixlen_word follow; the field
            // ends at the count.
            inArray = false;
            state = State.IDLE;
            return;
        }
        // A fixlen array always carries its fixlen_word, even when empty (§4.8), so
        // an empty one still advances into FIXLEN_LEN to consume it; stepFixlenLen
        // finishes an empty array once the word is read (no payload follows).

        switch (arrayKind) {
            case UNSIGNED:
                state = State.VARINT_UNSIGNED;
                break;
            case SIGNED:
                state = State.VARINT_SIGNED;
                break;
            case FIXLEN:
            default:
                state = State.FIXLEN_LEN;
                break;
        }
    }
}
