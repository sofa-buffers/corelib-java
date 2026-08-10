/*
 * SofaBuffers Java - streaming input decoder (port of istream.c).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.UncheckedIOException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

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
 * resumes on the next {@code feed}. Only that one construct pays for the split:
 * the moment it completes, the rest of the chunk goes back to the fast path —
 * within an array as much as between fields, so a boundary inside a long array
 * costs one element and not its remainder. The two paths are byte-for-byte
 * equivalent; the fast path simply removes the per-byte overhead from the common
 * case where a message (or a large chunk of one) arrives in a single feed.
 *
 * <p>Unlike the C decoder there is no per-field "bind a destination" step and no
 * explicit skip bookkeeping: a {@link Visitor} simply ignores fields it does not
 * care about. Scalars and floats are delivered whole; string / blob payloads are
 * delivered in chunks (so they may exceed RAM); array elements are announced
 * with {@link Visitor#arrayBegin} and then delivered through the scalar / float
 * callbacks. Every fixlen field is announced with {@link Visitor#fixlenBegin} at
 * its length word, before any payload byte, so a length bound can be enforced
 * there rather than after the payload assembles.
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
 * <p><b>{@code INVALID} is terminal.</b> Malformed bytes are malformed regardless
 * of what follows, so a rejection sticks: {@code status()} answers
 * {@link DecodeStatus#INVALID} from then on and every further {@code feed} throws
 * {@link SofabError#INVALID_MSG} without decoding, so a caller that catches the
 * exception and keeps feeding cannot resume mid-stream on a message the decoder
 * has already proven broken, nor read a {@code COMPLETE} verdict for it.
 * {@link #reset()} — resynchronising onto the next message — is what clears it.
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

    // --- decoder states -----------------------------------------------------
    //
    // Plain int constants rather than an enum: a `switch` over an enum compiles
    // to a synthetic $SwitchMap[] load plus an ordinal() call per dispatch, and
    // the feed loop tests the state once per field.

    /** At a clean field boundary — no field header or value partially read. */
    private static final int S_IDLE = 0;
    /** A field-header varint is partially accumulated. */
    private static final int S_HEADER = 1;
    private static final int S_VARINT_UNSIGNED = 2;
    private static final int S_VARINT_SIGNED = 3;
    private static final int S_FIXLEN_LEN = 4;
    private static final int S_FIXLEN_VAL = 5;
    private static final int S_FIXLEN_RAW = 6;
    private static final int S_ARRAY_COUNT = 7;

    // --- fixlen subtype tags (the low 3 bits of a fixlen_word) ---------------

    private static final int F_FP32 = 0x0;
    private static final int F_FP64 = 0x1;
    private static final int F_STRING = 0x2;
    private static final int F_BLOB = 0x3;

    /**
     * Little-endian views over a {@code byte[]}. A float payload is four or eight
     * contiguous wire bytes, so one intrinsified unaligned load replaces the
     * eight loads / seven shifts / seven ors of a hand-assembled read — and pays
     * a single bounds check instead of one per byte.
     */
    private static final VarHandle LE_INT =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LE_LONG =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /** Shared zero-length payload handed to the visitor for an empty string/blob. */
    private static final byte[] EMPTY = new byte[0];

    /** One per byte lane: the continuation bit of each of eight varint bytes. */
    private static final long CONT_BITS = 0x8080_8080_8080_8080L;

    /**
     * Gather the 7-bit payloads of eight varint bytes packed one per lane of
     * {@code x} into a contiguous 56-bit value — the inverse of the encoder's
     * {@link OStream} lane spread.
     *
     * <p>Lane {@code i} holds its payload at bits {@code [8i, 8i+7)} and it belongs
     * at {@code [7i, 7i+7)}, so each lane is shifted down by its own index and
     * masked to its destination. Eight bytes of a varint therefore cost one
     * unaligned load and this straight-line arithmetic instead of eight
     * bounds-checked loads, eight tests and eight shift/or pairs.
     */
    private static long gather7(long x) {
        // Closes the gaps by repeated halving — the exact inverse of the encoder's
        // three-stage spread, and three mask/shift/or stages instead of one term
        // per lane.
        long y = (x & 0x007F_007F_007F_007FL) | ((x >>> 1) & 0x3F80_3F80_3F80_3F80L);
        y = (y & 0x0000_3FFF_0000_3FFFL) | ((y >>> 2) & 0x0FFF_C000_0FFF_C000L);
        return (y & 0x0FFF_FFFFL) | ((y >>> 4) & 0x00FF_FFFF_F000_0000L);
    }

    // incremental varint accumulator
    private long varintValue;
    private int varintShift;
    private long varintOut;

    private int state = S_IDLE;
    private int id;

    // array context
    private ArrayKind arrayKind = ArrayKind.UNSIGNED;
    private int arrayRemaining;
    private boolean inArray;
    /**
     * Whether the array being read is a fixlen (fp32/fp64) array. Its element
     * kind is not known at the count word — it is carried by the
     * {@code fixlen_word} that follows — so {@link #arrayKind} is only settled,
     * and {@link Visitor#arrayBegin} only fired, once that word has been read
     * (CORELIB_PLAN §4.8). Integer arrays settle both at the count word.
     */
    private boolean fixlenArray;

    // fixlen context
    private int fixlenSubtype = F_FP32;
    private int fixlenTotal;
    private int fixlenRemaining;
    /**
     * Carry buffer for a float payload split across feeds. Allocated on first
     * use: a decode whose fp32/fp64 values never straddle a chunk boundary — the
     * whole-message case — never allocates it at all.
     */
    private byte[] acc;
    private int accLen;

    // sequence nesting depth (for balanced start/end validation)
    private long depth;

    /**
     * Latched {@link DecodeStatus#INVALID}: the bytes fed so far were determined
     * malformed, which CORELIB_PLAN §5.2 makes <b>terminal</b> — no continuation
     * can make them valid. Set from {@link #feed}'s handler on the way out, so
     * every rejection latches, wherever in this class it is raised (and a
     * schema-bound rejection raised by the {@link Visitor} does too). Once set,
     * {@link #status()} answers {@code INVALID} and {@code feed} refuses further
     * bytes until {@link #reset()} starts a new message.
     */
    private boolean invalid;

    /**
     * Bytes of the current message handed to the resumable byte-at-a-time machine
     * ({@link #step}). The decoder never reads it: the two decode paths are
     * byte-for-byte equivalent, so this counter is the only thing that tells them
     * apart, and {@code StreamingArrayFastPathTest} reads it to prove that a chunk
     * boundary inside an array costs the machine the one straddling element rather
     * than the array's whole remainder (corelib-java#74). Package-private, not
     * public API. One increment on a path that already pays a state dispatch per
     * byte; the bulk paths never touch it.
     */
    long machineBytes;

    /** Create a fresh decoder ready to accept a new message. */
    public IStream() {
    }

    /**
     * Return this decoder to its just-constructed state so it can decode another
     * message. Equivalent to allocating a new {@code IStream}, without the
     * allocation: a decoder is one small object plus its scalar accumulator, so a
     * caller decoding many messages in a row (a server loop, the generated
     * {@code decode} helpers) can hold one instance and reset it per message
     * instead of letting each decode allocate and immediately discard one.
     *
     * <p>Discards any partially decoded field and any open sequence nesting, so it
     * must not be called mid-message unless that is the intent — after an
     * {@link SofabError#INVALID_MSG} it is exactly how a stream decoder
     * resynchronises onto the next message, and the <em>only</em> way: that outcome
     * is terminal (CORELIB_PLAN §5.2), so until this call {@link #status()} keeps
     * answering {@link DecodeStatus#INVALID} and {@link #feed} keeps refusing bytes.
     *
     * <p>{@link #acc} keeps its allocation: retaining it is the point of reuse, and
     * only its first {@code accLen} bytes are ever read, which is zeroed here.
     * Everything else this class declares is restored, and
     * {@code ResetCoversEveryFieldTest} holds that to every field added later.
     */
    public void reset() {
        varintValue = 0;
        varintShift = 0;
        varintOut = 0;
        state = S_IDLE;
        id = 0;
        arrayKind = ArrayKind.UNSIGNED;
        arrayRemaining = 0;
        inArray = false;
        fixlenArray = false;
        fixlenSubtype = F_FP32;
        fixlenTotal = 0;
        fixlenRemaining = 0;
        accLen = 0;
        depth = 0;
        invalid = false;
        machineBytes = 0;
        // Pure scratch — every path writes these before it reads them — but cleared
        // anyway so "reset restores every declared field" needs no exception for
        // them, and the reflective guard can hold the whole class to it.
        scratchPos = 0;
        tailBits = 0;
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
     * <p>A <em>malformed</em> message answers {@link DecodeStatus#INVALID}, which
     * outranks both other outcomes and is <b>terminal</b> (CORELIB_PLAN §5.2):
     * {@link #feed} threw {@link SofabError#INVALID_MSG} when it read the malformed
     * construct and the verdict is latched from there on, so no continuation — and
     * in particular no later {@code feed} that would have ended at a clean field
     * boundary — can turn it back into {@code COMPLETE} or {@code INCOMPLETE}.
     * {@link #reset()} clears it, because that starts a new message.
     *
     * <p>Per the finish-less spec (MESSAGE_SPEC §7) this is a pure accessor: it
     * never throws, never mutates decoder state, and never promotes an incomplete
     * decode to an error. The caller owns end-of-input and decides whether a
     * trailing {@code INCOMPLETE} is a truncation it cares about.
     *
     * @return {@link DecodeStatus#INVALID} once any fed bytes were rejected as
     *         malformed, else {@link DecodeStatus#COMPLETE} at a clean boundary,
     *         otherwise {@link DecodeStatus#INCOMPLETE}
     */
    public DecodeStatus status() {
        // INVALID first: it is a property of bytes already consumed and no later
        // state can revise it (§5.2, "INVALID wins over INCOMPLETE" and terminal).
        if (invalid) {
            return DecodeStatus.INVALID;
        }
        // COMPLETE only at a true field boundary: no partial field header varint
        // (that is its own state, S_HEADER), no in-progress value/payload/array
        // element (S_IDLE covers the resumable machine and mid-array between
        // elements), and every opened sequence closed (depth == 0). Anything else
        // means the bytes ended inside a field or an open sequence — INCOMPLETE.
        if (state == S_IDLE && depth == 0) {
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
     * <p>The {@link SofabError#INVALID_MSG} outcome is <b>terminal</b>
     * (CORELIB_PLAN §5.2): once any fed bytes have been rejected as malformed, this
     * method decodes nothing further and rethrows {@code INVALID_MSG} for every
     * subsequent call, and {@link #status()} keeps reporting
     * {@link DecodeStatus#INVALID}, until {@link #reset()} begins a new message.
     * Running out of bytes mid-field is <em>not</em> that: it suspends and resumes
     * on the next call, as before.
     *
     * @param data    backing array
     * @param off     start offset
     * @param len     number of bytes to consume
     * @param visitor sink for decoded fields
     * @throws SofabException with {@link SofabError#INVALID_MSG} on malformed input,
     *         or on any call after malformed input was already rejected
     */
    public void feed(byte[] data, int off, int len, Visitor visitor) throws SofabException {
        if (invalid) {
            throw new SofabException(SofabError.INVALID_MSG,
                    "decode already INVALID; reset() to start a new message");
        }
        try {
            decode(data, off, len, visitor);
        } catch (SofabException e) {
            // Latch on the way out rather than at each of the two dozen throw
            // sites: a rejection raised anywhere in this class — fast path,
            // resumable state machine, or a helper added later — is terminal, and
            // this is the one place all of them pass through.
            if (e.error() == SofabError.INVALID_MSG) {
                invalid = true;
            }
            throw e;
        } catch (UncheckedIOException e) {
            // A Visitor cannot declare a checked exception, so generated code
            // reports a schema bound it must reject (MESSAGE_SPEC §7.1: an
            // over-maxlen length, an over-capacity count, an invalid-UTF-8 string)
            // by wrapping a SofabException. That is the same INVALID outcome and is
            // latched the same way — while LIMIT_EXCEEDED, a receiver-side policy
            // rejection of well-formed bytes (§6.2.1), deliberately is not.
            if (e.getCause() instanceof SofabException cause
                    && cause.error() == SofabError.INVALID_MSG) {
                invalid = true;
            }
            throw e;
        }
    }

    /**
     * Decode {@code len} bytes of {@code data} from {@code off}. The body of
     * {@link #feed}, split out so the decode loop itself carries no exception
     * handler and stays the shape the JIT compiles today.
     */
    private void decode(byte[] data, int off, int len, Visitor visitor) throws SofabException {
        int i = off;
        final int end = off + len;
        while (i < end) {
            if (state != S_IDLE) {
                i = resume(data, i, end, visitor);
                continue;
            }

            // --- clean field boundary: decode a whole field in place -------------
            //
            // The header, the scalar-value read and the visitor hand-off are all
            // written out here rather than behind a call. A field is only a few
            // dozen instructions of real work, so an out-of-line call per field —
            // with its own frame, spills and reloads — is a large fraction of the
            // cost of decoding one. Only the shapes that genuinely need more code
            // (fixlen, arrays) are reached through a call.
            long header;
            int p = i;
            if (end - p >= 10) {
                long w = (long) LE_LONG.get(data, p);
                if ((w & 0x80L) == 0) {
                    // One byte — every field header with id < 16.
                    header = w & 0x7FL;
                    p = i + 1;
                } else if ((w & 0x8000L) == 0) {
                    header = (w & 0x7FL) | ((w >>> 1) & (0x7FL << 7));
                    p = i + 2;
                } else {
                    header = wideVarint(data, p, w);
                    p = scratchPos;
                }
            } else {
                header = boundedVarint(data, p, end);
                p = scratchPos;
                if (p < 0) {
                    // Header runs past the buffer: hand this byte to the state
                    // machine (which accumulates it in S_HEADER) and let the loop
                    // drive the rest.
                    step(data[i] & 0xFF, visitor);
                    i++;
                    continue;
                }
            }
            int wireType = (int) (header & 0x07);
            long idValue = header >>> 3;
            // ID_MAX bounds every header's id, sequence end included (§6.2) — the
            // id is validated where it is read, before any branch on wire type.
            if (idValue > ID_MAX) {
                throw new SofabException(SofabError.INVALID_MSG, "id " + idValue);
            }
            final int fieldId = (int) idValue;
            id = fieldId;
            inArray = false;

            if (wireType <= T_VARINT_SIGNED) {
                long val;
                if (end - p >= 10) {
                    long w = (long) LE_LONG.get(data, p);
                    if ((w & 0x80L) == 0) {
                        val = w & 0x7FL;
                        p += 1;
                    } else if ((w & 0x8000L) == 0) {
                        val = (w & 0x7FL) | ((w >>> 1) & (0x7FL << 7));
                        p += 2;
                    } else {
                        val = wideVarint(data, p, w);
                        p = scratchPos;
                    }
                } else {
                    val = boundedVarint(data, p, end);
                    int q = scratchPos;
                    if (q < 0) {
                        // Value spills past the buffer: the machine reads it from p.
                        state = (wireType == T_VARINT_UNSIGNED)
                                ? S_VARINT_UNSIGNED : S_VARINT_SIGNED;
                        i = p;
                        continue;
                    }
                    p = q;
                }
                if (wireType == T_VARINT_UNSIGNED) {
                    visitor.unsigned(fieldId, val);
                } else {
                    visitor.signed(fieldId, zigzagDecode(val));
                }
                i = p;
                continue;
            }

            if (wireType == T_SEQUENCE_START) {
                if (depth >= Sofab.MAX_DEPTH) {
                    throw new SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH");
                }
                depth++;
                visitor.sequenceBegin(fieldId);
                i = p;
                continue;
            }
            if (wireType == T_SEQUENCE_END) {
                if (depth == 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "dangling sequence end");
                }
                depth--;
                visitor.sequenceEnd();
                i = p;
                continue;
            }
            i = fastCompound(data, p, end, visitor, wireType);
        }
    }

    /**
     * Continue a field whose bytes were split across {@code feed} calls: stream a
     * string/blob payload in bulk, or hand one byte to the resumable state machine.
     * Returns the index just past the bytes consumed.
     *
     * <p>Only the construct that actually straddled the boundary needs the machine.
     * When that byte completes an array element (or the count / {@code fixlen_word}
     * that arms one) and elements remain, the rest of the chunk goes straight back
     * to the bulk element loop, so a boundary anywhere inside an array costs one
     * element rather than every element after it (corelib-java#74).
     */
    private int resume(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        // Bulk path: stream string/blob payloads with one callback per chunk
        // rather than one per byte.
        if (state == S_FIXLEN_RAW) {
            int take = Math.min(end - i, fixlenRemaining);
            int chunkOffset = fixlenTotal - fixlenRemaining;
            if (fixlenSubtype == F_STRING) {
                visitor.string(id, fixlenTotal, chunkOffset, data, i, take);
            } else if (fixlenSubtype == F_BLOB) {
                visitor.blob(id, fixlenTotal, chunkOffset, data, i, take);
            } else {
                throw new SofabException(SofabError.INVALID_MSG, "raw fixlen type");
            }
            fixlenRemaining -= take;
            if (fixlenRemaining == 0) {
                state = S_IDLE;
            }
            return i + take;
        }
        // Every other non-idle state covers a partially read value or payload, and
        // S_HEADER covers a partially read field header.
        step(data[i] & 0xFF, visitor);
        int p = i + 1;
        if (inArray && p < end) {
            // Back at an element boundary with elements left and bytes left: the
            // element loops pick up exactly where the machine stopped, reading
            // arrayRemaining and id from the fields the machine just updated.
            // "Nothing accumulated" is what says the machine is between elements —
            // varintShift == 0 for a varint array, accLen == 0 for a float one —
            // and it also holds right after a count word or fixlen_word completes,
            // which arms the same states with the array already announced.
            switch (state) {
                case S_VARINT_UNSIGNED:
                    if (varintShift == 0) {
                        return unsignedElements(data, p, end, visitor);
                    }
                    break;
                case S_VARINT_SIGNED:
                    if (varintShift == 0) {
                        return signedElements(data, p, end, visitor);
                    }
                    break;
                case S_FIXLEN_VAL:
                    if (accLen == 0) {
                        return fixlenElements(data, p, end, visitor, fixlenTotal);
                    }
                    break;
                default:
                    break;
            }
        }
        return p;
    }

    /**
     * Read a varint of three or more bytes starting at {@code p}, given {@code w},
     * the eight bytes already loaded there. Leaves the next position in
     * {@link #scratchPos}. Kept out of line so the one- and two-byte forms that
     * dominate real messages stay in the caller's straight-line code.
     */
    private long wideVarint(byte[] data, int p, long w) throws SofabException {
        long stop = ~w & CONT_BITS;
        if (stop != 0) {
            int nz = Long.numberOfTrailingZeros(stop); // 23, 31, ... 63
            scratchPos = p + (nz >>> 3) + 1;
            return gather7(w & (-1L >>> (63 - nz)));
        }
        long v = gather7(w);
        scratchPos = finishLongVarint(data, p);
        return v | tailBits;
    }

    /**
     * Read a varint from the last few bytes of the buffer, where the
     * eight-at-a-time path has no room. Fewer than ten bytes remain, so at most
     * nine can be read and no {@code >64}-bit varint can complete here — the
     * overflow test lives only on the ten-byte path. Leaves the next position in
     * {@link #scratchPos}, or {@code -1} there when the varint runs past
     * {@code end}.
     */
    private long boundedVarint(byte[] data, int p, int end) {
        long v = 0;
        int shift = 0;
        while (p < end) {
            int b = data[p++] & 0xFF;
            v |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                scratchPos = p;
                return v;
            }
        }
        scratchPos = -1;
        return 0;
    }

    /**
     * Decode a field whose shape needs more than the straight-line code in
     * {@link #feed}: a fixlen scalar or either kind of array. {@code p} points just
     * past the field header, whose {@code wireType} is passed in. Returns the index
     * just past the bytes consumed; when the field cannot be completed within the
     * buffer the resumable state machine is armed and the index is left at the
     * first byte the machine must re-read.
     */
    private int fastCompound(byte[] data, int p, int end, Visitor visitor, int wireType)
            throws SofabException {
        switch (wireType) {
            case T_FIXLEN:
                return fastFixlenScalar(data, p, end, visitor);
            case T_VARINTARRAY_UNSIGNED:
                arrayKind = ArrayKind.UNSIGNED;
                fixlenArray = false;
                return fastVarintArray(data, p, end, visitor, false);
            case T_VARINTARRAY_SIGNED:
                arrayKind = ArrayKind.SIGNED;
                fixlenArray = false;
                return fastVarintArray(data, p, end, visitor, true);
            case T_FIXLENARRAY:
                // arrayKind stays unsettled until the fixlen_word names the subtype.
                fixlenArray = true;
                return fastFixlenArray(data, p, end, visitor);
            default:
                throw new SofabException(SofabError.INVALID_MSG, "field type " + wireType);
        }
    }

    /**
     * Read a varint that is <em>not</em> on the per-element hot path (a fixlen_word
     * or an array element count) from {@code data[i..end)}.
     *
     * <p>The word itself is the return value — every 64-bit pattern is a legal one,
     * so completion is signalled out of band: {@link #scratchPos} is left at the
     * position just past the word, or set to {@code -1} when the word runs past
     * {@code end} and the caller must arm the state machine.
     *
     * <p>The {@code >64}-bit overflow test (§4.1/§6.3) can only fire on the tenth
     * byte — the sole shift (63) at which a payload bit spills past bit 63 — so it
     * is tested as {@code shift == 63} rather than recomputed from a per-byte
     * {@code room} subtraction.
     */
    private long readWord(byte[] data, int i, int end) throws SofabException {
        long v = 0;
        int shift = 0;
        int p = i;
        while (true) {
            if (p >= end) {
                scratchPos = -1;
                return 0;
            }
            int b = data[p++] & 0xFF;
            if (shift == 63 && (b & 0x7F) > 1) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
            v |= ((long) (b & 0x7F)) << shift;
            shift += 7;
            if ((b & 0x80) == 0) {
                break;
            }
            if (shift >= VALUE_BITS) {
                throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
            }
        }
        scratchPos = p;
        return v;
    }

    /**
     * Position just past the word most recently read by {@link #readWord}, or
     * {@code -1} when that word ran past the end of the supplied bytes.
     */
    private int scratchPos;

    /** Bits 56..63 contributed by the ninth/tenth byte of a long varint. */
    private long tailBits;

    /**
     * Finish a varint whose first eight bytes all carried a continuation bit by
     * consuming the ninth — and, if that one continues too, the tenth — byte at
     * {@code p + 8}. Their contribution to bits 56..63 is left in
     * {@link #tailBits}; the return value is the position just past the varint.
     *
     * <p>The caller guarantees ten readable bytes from {@code p}, so this never
     * runs short. It is the only place a varint can exceed the 64-bit value range,
     * and both overlong forms — a tenth byte with a payload bit above bit 0, and an
     * eleventh byte implied by a tenth that still continues — are rejected here
     * (§4.1/§6.3).
     */
    private int finishLongVarint(byte[] data, int p) throws SofabException {
        int b = data[p + 8];
        long bits = (long) (b & 0x7F) << 56;
        if (b >= 0) {
            tailBits = bits;
            return p + 9;
        }
        b = data[p + 9];
        if (((b & 0x7F) >>> 1) != 0) {
            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
        }
        if (b < 0) {
            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
        }
        tailBits = bits | ((long) (b & 0x7F) << 63);
        return p + 10;
    }

    /** Fast path for a scalar fixlen field; {@code i} points at its length header. */
    private int fastFixlenScalar(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        // A fixlen_word is one byte for any payload up to 15 bytes — every float,
        // and most strings — so that case is read here instead of through the
        // general reader.
        long fh;
        int p;
        if (i < end && data[i] >= 0) {
            fh = data[i];
            p = i + 1;
        } else {
            fh = readWord(data, i, end);
            p = scratchPos;
            if (p < 0) {
                state = S_FIXLEN_LEN; // machine re-reads the length header from i
                return i;
            }
        }
        int subtype = (int) (fh & 0x07);
        // Reserved subtypes 0x4..0x7 are malformed (§4.6), and are rejected before
        // the length is judged — the order the enum-based reader had.
        if (subtype > F_BLOB) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
        long lengthValue = fh >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int length = (int) lengthValue;
        switch (subtype) {
            case F_FP32:
                if (length != 4) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + length);
                }
                // §5.2: announce before the payload-availability test below, so a
                // message ending right here still delivers the header event and can
                // be judged INVALID rather than decaying to INCOMPLETE.
                visitor.fixlenBegin(id, FixlenType.FP32, 4);
                if (end - p < 4) {
                    armFixlenVal(F_FP32, 4);
                    return p;
                }
                visitor.fp32(id, Float.intBitsToFloat((int) LE_INT.get(data, p)));
                return p + 4;
            case F_FP64:
                if (length != 8) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + length);
                }
                visitor.fixlenBegin(id, FixlenType.FP64, 8);
                if (end - p < 8) {
                    armFixlenVal(F_FP64, 8);
                    return p;
                }
                visitor.fp64(id, Double.longBitsToDouble((long) LE_LONG.get(data, p)));
                return p + 8;
            case F_STRING:
            case F_BLOB:
                fixlenSubtype = subtype;
                fixlenTotal = length;
                fixlenRemaining = length;
                accLen = 0;
                visitor.fixlenBegin(id, subtype == F_STRING ? FixlenType.STRING : FixlenType.BLOB,
                        length);
                if (length == 0) {
                    if (subtype == F_STRING) {
                        visitor.string(id, 0, 0, EMPTY, 0, 0);
                    } else {
                        visitor.blob(id, 0, 0, EMPTY, 0, 0);
                    }
                    state = S_IDLE;
                } else {
                    state = S_FIXLEN_RAW; // feed loop streams the payload in bulk
                }
                return p;
            default:
                throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
    }

    /**
     * Fast path for an unsigned/signed varint array; {@code i} points at the count.
     *
     * <p>The element loop is specialised per signedness rather than testing a flag
     * inside it. The two loops are otherwise identical, but a shared one would
     * carry the test — and the ZigZag decision — into every iteration unless the
     * JIT happened to inline this method into its caller and constant-fold the
     * flag; splitting them makes the hot loop independent of that.
     */
    private int fastVarintArray(byte[] data, int i, int end, Visitor visitor, boolean signed)
            throws SofabException {
        int p = fastArrayHeader(data, i, end, visitor, true);
        if (p < 0) {
            return i; // count header spilled past the buffer; machine reads it (arrayKind set)
        }
        return signed ? signedElements(data, p, end, visitor)
                      : unsignedElements(data, p, end, visitor);
    }

    /**
     * Read an array element from the last few bytes of the buffer, where the
     * eight-at-a-time path has no room. Fewer than ten bytes remain, so at most
     * nine can be read and no {@code >64}-bit varint can complete here. Kept out of
     * the element loops: folding it in enlarges them enough to cost the hot
     * eight-at-a-time path registers. Leaves the next position in
     * {@link #scratchPos}, or {@code -1} there when the element runs past
     * {@code end}.
     */
    private long tailElement(byte[] data, int p, int end) {
        long val = 0;
        int vs = 0;
        int q = p;
        while (q < end) {
            int b = data[q++] & 0xFF;
            val |= ((long) (b & 0x7F)) << vs;
            vs += 7;
            if ((b & 0x80) == 0) {
                scratchPos = q;
                return val;
            }
        }
        scratchPos = -1;
        return 0;
    }

    /** Elements of an unsigned varint array; {@code p} points at element 0. */
    private int unsignedElements(byte[] data, int p, int end, Visitor visitor) throws SofabException {
        // Hoist the per-element fields into locals: the loop runs once per array
        // element, so reading {@code id} and writing {@code arrayRemaining} straight
        // from memory each time would add a load/store to every element.
        int remaining = arrayRemaining;
        final int fieldId = id;
        final int safe = end - 10; // last start position with a full varint's room
        while (remaining > 0) {
            long val;
            if (p <= safe) {
                long w = (long) LE_LONG.get(data, p);
                if ((w & 0x80L) == 0) {
                    // Single-byte element: the whole u8/u16 small-value case, for
                    // two instructions on top of the load the wide path needs anyway.
                    val = w & 0x7FL;
                    p += 1;
                } else {
                    long stop = ~w & CONT_BITS;
                    if (stop != 0) {
                        int nz = Long.numberOfTrailingZeros(stop);
                        val = gather7(w & (-1L >>> (63 - nz)));
                        p += (nz >>> 3) + 1;
                    } else {
                        val = gather7(w);
                        p = finishLongVarint(data, p);
                        val |= tailBits;
                    }
                }
            } else {
                long tail = tailElement(data, p, end);
                if (scratchPos < 0) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining;
                    state = S_VARINT_UNSIGNED;
                    return p;
                }
                val = tail;
                p = scratchPos;
            }
            visitor.unsigned(fieldId, val);
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = S_IDLE;
        return p;
    }

    /** Elements of a signed (ZigZag) varint array; {@code p} points at element 0. */
    private int signedElements(byte[] data, int p, int end, Visitor visitor) throws SofabException {
        int remaining = arrayRemaining;
        final int fieldId = id;
        final int safe = end - 10;
        while (remaining > 0) {
            long val;
            if (p <= safe) {
                long w = (long) LE_LONG.get(data, p);
                if ((w & 0x80L) == 0) {
                    // Single-byte element: the whole u8/u16 small-value case, for
                    // two instructions on top of the load the wide path needs anyway.
                    val = w & 0x7FL;
                    p += 1;
                } else {
                    long stop = ~w & CONT_BITS;
                    if (stop != 0) {
                        int nz = Long.numberOfTrailingZeros(stop);
                        val = gather7(w & (-1L >>> (63 - nz)));
                        p += (nz >>> 3) + 1;
                    } else {
                        val = gather7(w);
                        p = finishLongVarint(data, p);
                        val |= tailBits;
                    }
                }
            } else {
                long tail = tailElement(data, p, end);
                if (scratchPos < 0) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining;
                    state = S_VARINT_SIGNED;
                    return p;
                }
                val = tail;
                p = scratchPos;
            }
            visitor.signed(fieldId, zigzagDecode(val));
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = S_IDLE;
        return p;
    }

    /** Fast path for a fixlen (fp32/fp64) array; {@code i} points at the count. */
    private int fastFixlenArray(byte[] data, int i, int end, Visitor visitor) throws SofabException {
        // §4.8 step 1/2: the count word only sets up the array context — the format
        // ceiling fires there, but arrayBegin does NOT, because the element subtype
        // it must report is still one varint away.
        int p = fastArrayHeader(data, i, end, visitor, false);
        if (p < 0) {
            return i; // count header spilled past the buffer; machine reads it
        }
        // §4.8: a fixlen array always carries its fixlen_word, even when empty, so
        // an empty fp32 array is distinguishable from an empty fp64 array. Read it
        // unconditionally; the payload loop below runs zero times when empty.
        // Element length header is encoded once and reused for every element.
        int lenStart = p;
        long fh;
        if (p < end && data[p] >= 0) {
            fh = data[p];
            p = lenStart + 1;
        } else {
            fh = readWord(data, lenStart, end);
            p = scratchPos;
            if (p < 0) {
                state = S_FIXLEN_LEN; // machine re-reads the element header from lenStart
                return lenStart;
            }
        }
        int subtype = (int) (fh & 0x07);
        if (subtype > F_BLOB) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
        long lengthValue = fh >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int size;
        if (subtype == F_FP32) {
            if (lengthValue != 4) {
                throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + lengthValue);
            }
            size = 4;
        } else if (subtype == F_FP64) {
            if (lengthValue != 8) {
                throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + lengthValue);
            }
            size = 8;
        } else if (subtype == F_STRING || subtype == F_BLOB) {
            // String/blob are not valid as fixlen-array elements (§4.8). This is a
            // FORMAT violation, judged before the visitor is offered the field, so
            // it can never be turned into a §7.3 skip.
            throw new SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element");
        } else {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
        fixlenSubtype = subtype;
        fixlenTotal = size;
        // §4.8 step 3: the word is format-valid, so the subtype is now known and
        // the array can be announced. Firing here (rather than on the count word)
        // is what lets a visitor skip a field whose subtype contradicts its schema
        // without judging the count against a bound that does not apply to it.
        arrayBeginFixlen(size == 4 ? ArrayKind.FP32 : ArrayKind.FP64, visitor);
        return fixlenElements(data, p, end, visitor, size);
    }

    /**
     * Payload elements of a fixlen (fp32/fp64) array; {@code p} points at the next
     * element and {@code size} is its width, 4 or 8. Split out of
     * {@link #fastFixlenArray} because {@link #resume} re-enters it once the
     * machine has finished an element that straddled a chunk boundary: by then the
     * count, the {@code fixlen_word} and {@code arrayBegin} are all behind us, so
     * only the payload loop may run again.
     */
    private int fixlenElements(byte[] data, int p, int end, Visitor visitor, int size) {
        int remaining = arrayRemaining;
        final int fieldId = id;
        if (size == 4) {
            while (remaining > 0) {
                if (end - p < 4) {
                    return spillFixlenArray(remaining, 4, p);
                }
                visitor.fp32(fieldId, Float.intBitsToFloat((int) LE_INT.get(data, p)));
                p += 4;
                remaining--;
            }
        } else {
            while (remaining > 0) {
                if (end - p < 8) {
                    return spillFixlenArray(remaining, 8, p);
                }
                visitor.fp64(fieldId, Double.longBitsToDouble((long) LE_LONG.get(data, p)));
                p += 8;
                remaining--;
            }
        }
        arrayRemaining = remaining;
        inArray = false;
        state = S_IDLE;
        return p;
    }

    /** Element bytes spill past the buffer: arm the machine to accumulate from {@code p}. */
    private int spillFixlenArray(int remaining, int size, int p) {
        arrayRemaining = remaining;
        fixlenRemaining = size;
        accLen = 0;
        state = S_FIXLEN_VAL;
        return p;
    }

    /**
     * Read and validate an array count header at {@code i} and set up the array
     * context ({@link #arrayRemaining}, {@link #inArray}), emitting
     * {@code arrayBegin} when {@code emitBegin} is set. Returns the index after
     * the count, or {@code -1} if the count spilled past the buffer — in which
     * case nothing is emitted and the state machine re-reads the count from
     * {@code i} ({@link #arrayKind} / {@link #fixlenArray} are already set), so
     * {@code arrayBegin} fires exactly once.
     *
     * <p>{@code emitBegin} is {@code false} for a fixlen array: its element kind
     * is only known once the {@code fixlen_word} after the count has been read,
     * so the caller fires the hook there (CORELIB_PLAN §4.8). The {@code ARRAY_MAX}
     * format ceiling below is <em>not</em> deferred with it — §4.8 step 1 keeps it
     * on the count word, whatever the subtype turns out to be, and nothing is
     * allocated on the strength of the count either way.
     */
    private int fastArrayHeader(byte[] data, int i, int end, Visitor visitor, boolean emitBegin)
            throws SofabException {
        long count = readWord(data, i, end);
        if (scratchPos < 0) {
            state = S_ARRAY_COUNT;
            return -1;
        }
        // count == 0 is a valid empty array (§4.7/§4.8); only an oversized count is
        // rejected. The comparison is unsigned: a count with bit 63 set is a huge
        // value, not a negative one.
        if (Long.compareUnsigned(count, ARRAY_MAX) > 0) {
            throw new SofabException(SofabError.INVALID_MSG, "array count");
        }
        int c = (int) count;
        arrayRemaining = c;
        inArray = true;
        if (emitBegin) {
            visitor.arrayBegin(id, arrayKind, c);
        }
        return scratchPos;
    }

    /**
     * Announce a fixlen array now that its {@code fixlen_word} has been read and
     * found format-valid: settle {@link #arrayKind} to the concrete subtype and
     * fire {@link Visitor#arrayBegin} exactly once for the field.
     */
    private void arrayBeginFixlen(ArrayKind kind, Visitor visitor) {
        arrayKind = kind;
        visitor.arrayBegin(id, kind, arrayRemaining);
    }

    /** Arm the state machine to accumulate a fixed-size fixlen value (fp32/fp64). */
    private void armFixlenVal(int subtype, int size) {
        fixlenSubtype = subtype;
        fixlenTotal = size;
        fixlenRemaining = size;
        accLen = 0;
        state = S_FIXLEN_VAL;
    }

    /**
     * Resumable state machine: feed one byte at the current {@link #state}. This
     * is the byte-at-a-time counterpart to the {@code fast*} path, used whenever a
     * field, value or array element was split across {@code feed} calls. Each
     * {@code step*} handler consumes the byte, and on completing its value emits to
     * the visitor and transitions {@link #state} to the next field or element.
     */
    private void step(int b, Visitor visitor) throws SofabException {
        machineBytes++;
        switch (state) {
            case S_IDLE:
            case S_HEADER:          stepIdle(b, visitor); break;
            case S_VARINT_UNSIGNED: stepVarintUnsigned(b, visitor); break;
            case S_VARINT_SIGNED:   stepVarintSigned(b, visitor); break;
            case S_FIXLEN_LEN:      stepFixlenLen(b, visitor); break;
            case S_FIXLEN_VAL:      stepFixlenVal(b, visitor); break;
            case S_ARRAY_COUNT:     stepArrayCount(b, visitor); break;
            default: /* S_FIXLEN_RAW handled in feed's bulk path */ break;
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
        // Shift 63 is the only one at which that can happen.
        if (varintShift == 63 && (b & 0x7F) > 1) {
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
     * {@code S_IDLE} (they carry no value).
     */
    private void stepIdle(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            state = S_HEADER; // header still incomplete: status() reports INCOMPLETE
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
                state = S_VARINT_UNSIGNED;
                break;
            case T_VARINT_SIGNED:
                state = S_VARINT_SIGNED;
                break;
            case T_FIXLEN:
                state = S_FIXLEN_LEN;
                break;
            case T_VARINTARRAY_UNSIGNED:
                arrayKind = ArrayKind.UNSIGNED;
                fixlenArray = false;
                state = S_ARRAY_COUNT;
                break;
            case T_VARINTARRAY_SIGNED:
                arrayKind = ArrayKind.SIGNED;
                fixlenArray = false;
                state = S_ARRAY_COUNT;
                break;
            case T_FIXLENARRAY:
                // arrayKind stays unsettled until the fixlen_word names the subtype.
                fixlenArray = true;
                state = S_ARRAY_COUNT;
                break;
            case T_SEQUENCE_START:
                if (depth >= Sofab.MAX_DEPTH) {
                    throw new SofabException(SofabError.INVALID_MSG, "sequence nesting exceeds MAX_DEPTH");
                }
                depth++;
                state = S_IDLE;
                visitor.sequenceBegin(id);
                break;
            case T_SEQUENCE_END:
                if (depth == 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "dangling sequence end");
                }
                depth--;
                state = S_IDLE;
                visitor.sequenceEnd();
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
        state = S_IDLE;
    }

    /**
     * Accumulate a fixlen length header ({@code (len << 3) | subtype}). Floats arm
     * {@link #S_FIXLEN_VAL} to read their bytes; a non-empty string/blob arms
     * {@link #S_FIXLEN_RAW} so the payload streams in bulk, while an empty one
     * is emitted immediately. String/blob are rejected as fixlen-array elements.
     */
    private void stepFixlenLen(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long header = varintOut;
        int subtype = (int) (header & 0x07);
        if (subtype > F_BLOB) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
        long lengthValue = header >>> 3;
        if (lengthValue > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + lengthValue);
        }
        int length = (int) lengthValue;

        fixlenSubtype = subtype;
        fixlenTotal = length;
        fixlenRemaining = length;
        accLen = 0;

        switch (subtype) {
            case F_FP32:
                if (length != 4) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp32 length " + length);
                }
                // §4.8 step 3: the array's deferred arrayBegin lands here, once the
                // word is known format-valid and its subtype settled (see
                // Visitor#arrayBegin). inArray is only true for a fixlen array in
                // this state; a scalar fixlen field announces itself instead, and
                // announces here for the same reason the fast path does (§5.2):
                // this is the last point before the verdict could decay to
                // INCOMPLETE on a message that ends at the length word.
                if (inArray) {
                    arrayBeginFixlen(ArrayKind.FP32, visitor);
                } else {
                    visitor.fixlenBegin(id, FixlenType.FP32, 4);
                }
                state = afterFixlenWord();
                break;
            case F_FP64:
                if (length != 8) {
                    throw new SofabException(SofabError.INVALID_MSG, "fp64 length " + length);
                }
                if (inArray) {
                    arrayBeginFixlen(ArrayKind.FP64, visitor);
                } else {
                    visitor.fixlenBegin(id, FixlenType.FP64, 8);
                }
                state = afterFixlenWord();
                break;
            case F_STRING:
            case F_BLOB:
                // String/blob are not valid as fixlen-array elements: a FORMAT
                // violation (§4.8), rejected before the field is ever offered to
                // the visitor, so it can never become a §7.3 skip.
                if (inArray) {
                    throw new SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element");
                }
                visitor.fixlenBegin(id, subtype == F_STRING ? FixlenType.STRING : FixlenType.BLOB,
                        length);
                if (length == 0) {
                    if (subtype == F_STRING) {
                        visitor.string(id, 0, 0, EMPTY, 0, 0);
                    } else {
                        visitor.blob(id, 0, 0, EMPTY, 0, 0);
                    }
                    state = S_IDLE;
                } else {
                    state = S_FIXLEN_RAW;
                }
                break;
            default:
                throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
    }

    /**
     * Next state after a fixlen_word has been read. For an empty fixlen array the
     * word is the whole field — no payload follows (§4.8) — so the array is closed
     * and the machine returns to idle; otherwise the fixed-size value bytes follow.
     */
    private int afterFixlenWord() {
        if (inArray && arrayRemaining == 0) {
            inArray = false;
            return S_IDLE;
        }
        return S_FIXLEN_VAL;
    }

    /**
     * Accumulate the fixed-size bytes of a float value into {@link #acc}; once all
     * are in, decode the fp32/fp64 from little-endian, emit it, and advance to the
     * next array element (reusing the element size) or back to idle.
     */
    private void stepFixlenVal(int b, Visitor visitor) throws SofabException {
        byte[] a = acc;
        if (a == null) {
            a = acc = new byte[8]; // first straddling float on this stream
        }
        a[accLen++] = (byte) b;
        fixlenRemaining--;
        if (fixlenRemaining != 0) {
            return;
        }

        if (fixlenSubtype == F_FP32) {
            int bits = (a[0] & 0xFF)
                    | ((a[1] & 0xFF) << 8)
                    | ((a[2] & 0xFF) << 16)
                    | ((a[3] & 0xFF) << 24);
            visitor.fp32(id, Float.intBitsToFloat(bits));
        } else if (fixlenSubtype == F_FP64) {
            long bits = 0;
            for (int i = 0; i < 8; i++) {
                bits |= ((long) (a[i] & 0xFF)) << (i * 8);
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
        state = S_IDLE;
    }

    /**
     * Accumulate an array count header; on completion validate it, set up the
     * array context, and arm the per-element state. An integer array is announced
     * here via {@link Visitor#arrayBegin} — its count word is the whole header. A
     * fixlen array is not: it advances into {@link #S_FIXLEN_LEN} and
     * {@link #stepFixlenLen} announces it once the {@code fixlen_word} has named
     * the element subtype (CORELIB_PLAN §4.8). Either way the hook fires exactly
     * once per array field.
     */
    private void stepArrayCount(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            return;
        }
        long count = varintOut;
        // §4.8 step 1: the format ceiling applies to the count word itself,
        // whatever the element subtype turns out to be. count == 0 is a valid empty
        // array (§4.7/§4.8); only an oversized count is rejected. Nothing is
        // allocated on the strength of the count.
        if (Long.compareUnsigned(count, ARRAY_MAX) > 0) {
            throw new SofabException(SofabError.INVALID_MSG, "array count");
        }
        int c = (int) count;
        arrayRemaining = c;
        inArray = true;

        if (fixlenArray) {
            // A fixlen array always carries its fixlen_word, even when empty (§4.8),
            // so an empty one still advances into S_FIXLEN_LEN to consume it.
            // stepFixlenLen fires arrayBegin there and finishes an empty array once
            // the word is read (no payload follows). Ending the message between the
            // two words therefore announces nothing at all: INCOMPLETE, not INVALID.
            state = S_FIXLEN_LEN;
            return;
        }

        visitor.arrayBegin(id, arrayKind, c);

        if (c == 0) {
            // Empty varint array: no elements and no fixlen_word follow; the field
            // ends at the count.
            inArray = false;
            state = S_IDLE;
            return;
        }
        state = arrayKind == ArrayKind.SIGNED ? S_VARINT_SIGNED : S_VARINT_UNSIGNED;
    }
}
