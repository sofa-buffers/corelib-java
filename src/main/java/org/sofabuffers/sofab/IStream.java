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
 * <p>{@code IStream} is a byte-at-a-time state machine. Feed it arbitrary chunks
 * with {@link #feed}; it parses field headers and pushes decoded fields to your
 * {@link Visitor}. Because all parse state lives inside the decoder, a message
 * may be split across any number of {@code feed} calls at any byte boundary —
 * true streaming on the input side.
 *
 * <p>Unlike the C decoder there is no per-field "bind a destination" step and no
 * explicit skip bookkeeping: a {@link Visitor} simply ignores fields it does not
 * care about. Scalars and floats are delivered whole; string / blob payloads are
 * delivered in chunks (so they may exceed RAM); array elements are announced
 * with {@link Visitor#arrayBegin} and then delivered through the scalar / float
 * callbacks.
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
 * new IStream().feed(buf, sink);
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
        final int endExclusive = off + len;
        while (i < endExclusive) {
            // Fast path: stream string/blob payloads in bulk rather than one
            // callback per byte.
            if (state == State.FIXLEN_RAW) {
                int take = Math.min(endExclusive - i, fixlenRemaining);
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

            step(data[i] & 0xFF, visitor);
            i++;
        }
    }

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
                if (depth == Long.MAX_VALUE) {
                    throw new SofabException(SofabError.INVALID_MSG, "sequence too deep");
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

    private void stepVarintUnsigned(int b, Visitor visitor) throws SofabException {
        if (varintPush(b)) {
            visitor.unsigned(id, varintOut);
            advanceAfterElement();
        }
    }

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
                state = State.FIXLEN_VAL;
                break;
            case FP64:
                if (length != 8) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + length);
                }
                state = State.FIXLEN_VAL;
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

    private void stepArrayCount(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long count = varintOut;
        if (count == 0 || Long.compareUnsigned(count, ARRAY_MAX) > 0) {
            throw new SofabException(SofabError.INVALID_MSG, "array count");
        }
        int c = (int) count;
        arrayRemaining = c;
        inArray = true;
        visitor.arrayBegin(id, arrayKind, c);

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
