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
 * <p><b>One question, one answer: the outcome is what {@code feed} returns</b>
 * (CORELIB_PLAN §5.2.4, "the status {@code feed}/{@code decode} returns <em>is</em>
 * the answer"). Every {@code feed} reports on the bytes consumed <em>so far</em>:
 * {@link DecodeStatus#COMPLETE} when they end exactly at a field boundary with no
 * open sequence, {@link DecodeStatus#INCOMPLETE} when they end inside a field — a
 * partial varint, a fixlen/array payload shorter than declared, an array with
 * elements still pending — or with an open (unclosed) nested sequence. There is no
 * second way to ask and no finish/finalize step; the caller owns end-of-input and
 * decides whether a trailing {@code INCOMPLETE} is a truncation it cares about.
 *
 * <p><b>{@code INCOMPLETE} is not an error.</b> Running out of bytes mid-field
 * suspends the decode and returns normally; the next {@code feed} resumes it at
 * the byte it stopped on.
 *
 * <p><b>A refusal travels on the error channel, carrying its code.</b> Malformed
 * bytes — the {@code INVALID} outcome of §5.2, {@code InvalidMessage} in §6.3's
 * table — throw {@link SofabException} with {@link SofabError#INVALID_MSG}; a
 * receiver-cap rejection of <em>well-formed</em> bytes (§6.2.1) throws
 * {@link SofabError#LIMIT_EXCEEDED}, the code §6.3 keeps distinct from it. Neither
 * is ever <em>returned</em>: a rejected decode leaves through the {@code throw},
 * which is the one place the caller learns of it.
 *
 * <p><b>Both rejections are terminal.</b> Malformed bytes are malformed regardless
 * of what follows (§5.2), and a limit rejection is "a terminal, receiver-local
 * policy rejection" (§6.3), so the verdict sticks: every further {@code feed}
 * re-throws the same code without decoding a byte. A caller that catches the
 * exception and keeps feeding therefore cannot resume mid-stream on a message the
 * decoder has already refused, nor read a {@code COMPLETE} out of it.
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
 * if (is.feed(buf, sink) == DecodeStatus.INCOMPLETE) {
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
     * Carry buffer for a float payload split across feeds. Eight bytes — the
     * widest fixlen value — <b>sized at construction</b>, never afterwards:
     * CORELIB_PLAN §6.6 permits this landing zone as bounded working state
     * precisely because a constant of that document caps it, and requires it to
     * be sized "to its full extent when the codec is constructed", so no
     * allocation happens on a {@code feed} path.
     */
    private final byte[] acc = new byte[8];
    private int accLen;

    /**
     * Destination of an accepted {@link Visitor#arrayBulk} offer. {@link #bulkW}
     * says which of the four is live -- W_NONE when the elements go through the
     * per-element callbacks instead -- and it is resolved ONCE per array, so the
     * element loops branch on a hoisted local rather than a type test. Live only
     * between the offer and {@link Visitor#arrayBulkEnd}, so the fill also survives
     * a feed boundary inside the array: both the bulk element loops and the
     * resumable machine write through it, and {@link #bulkAt} counts what has been
     * written so far.
     */
    private byte[] bulkB;
    private short[] bulkS;
    private int[] bulkI;
    private long[] bulkL;
    private int bulkW;
    private int bulkAt;

    /** {@link #bulkW} values: no offer taken, then the four destination widths. */
    private static final int W_NONE = 0;
    private static final int W_BYTE8 = 1;
    private static final int W_SHORT16 = 2;
    private static final int W_INT32 = 3;
    private static final int W_LONG64 = 4;

    // sequence nesting depth (for balanced start/end validation)
    private long depth;

    /**
     * The latched terminal verdict, or {@code null} while the decode is still
     * live: the {@link SofabError} of the rejection that ended it. Two codes end a
     * decode and both latch here — {@link SofabError#INVALID_MSG}, the bytes are
     * malformed and CORELIB_PLAN §5.2 makes that <b>terminal</b>, and
     * {@link SofabError#LIMIT_EXCEEDED}, "a <b>terminal</b>, receiver-local policy
     * rejection" of well-formed bytes (§6.3) — and the code is kept rather than a
     * flag because §6.3 forbids ever reporting the second as {@code InvalidMessage}.
     * Set from {@link #feed}'s handler on the way out, so every rejection latches,
     * wherever it is raised (in this class, or by the {@link Visitor} generated
     * code drives). Once set, {@code feed} decodes nothing further and re-throws
     * this code until {@link #reset()} starts a new message.
     */
    private SofabError terminal;

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

    /**
     * Create a fresh decoder ready to accept a new message.
     *
     * <p>This is the one moment a decoder allocates (CORELIB_PLAN §6.6): the
     * object itself and the eight-byte {@link #acc} landing zone. {@link #feed}
     * and everything it reaches allocate nothing.
     */
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
     * is terminal (CORELIB_PLAN §5.2), so until this call {@link #feed} decodes
     * nothing and keeps re-throwing the rejection.
     *
     * <p>{@link #acc} keeps its allocation — it is sized once at construction and
     * never re-made — and only its first {@code accLen} bytes are ever read,
     * which is zeroed here.
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
        bulkB = null;
        bulkS = null;
        bulkI = null;
        bulkL = null;
        bulkW = W_NONE;
        bulkAt = 0;
        depth = 0;
        terminal = null;
        machineBytes = 0;
        // Pure scratch — every path writes it before it reads it — but cleared
        // anyway so "reset restores every declared field" needs no exception for
        // it, and the reflective guard can hold the whole class to it.
        scratchPos = 0;
    }

    /**
     * Feed a whole chunk of encoded bytes, pushing decoded fields to
     * {@code visitor}, and report where the decode stands.
     *
     * @param data    encoded bytes
     * @param visitor sink for decoded fields
     * @return {@link DecodeStatus#COMPLETE} when the bytes consumed so far end at a
     *         clean field boundary with no open sequence, else
     *         {@link DecodeStatus#INCOMPLETE}
     * @throws SofabException with {@link SofabError#INVALID_MSG} on malformed input
     */
    public DecodeStatus feed(byte[] data, Visitor visitor) throws SofabException {
        return feed(data, 0, data.length, visitor);
    }

    /**
     * Feed a slice of encoded bytes, pushing decoded fields to {@code visitor}, and
     * report where the decode stands. Decoding can continue across many
     * {@code feed} calls; the decoder keeps all state internally.
     *
     * <p><b>The returned status is the answer</b> (CORELIB_PLAN §5.2.4). It
     * describes the bytes consumed <em>so far</em>, not just this slice:
     * {@link DecodeStatus#COMPLETE} at a clean field boundary with every opened
     * sequence closed, {@link DecodeStatus#INCOMPLETE} when the bytes ended inside
     * a field — a partial varint (field header or value), a fixlen/array payload
     * shorter than declared, an array with elements still pending — or with an open
     * nested sequence. {@code INCOMPLETE} is not an error and needs no finish step
     * to resolve: the decode suspends, the next call resumes it, and the caller
     * owns end-of-input.
     *
     * <p><b>A rejection leaves by the exception, never by the return.</b> The
     * {@link SofabError#INVALID_MSG} outcome is <b>terminal</b> (CORELIB_PLAN §5.2):
     * once any fed bytes have been rejected as malformed, this method decodes
     * nothing further and rethrows {@code INVALID_MSG} for every subsequent call
     * until {@link #reset()} begins a new message.
     * {@link SofabError#LIMIT_EXCEEDED} — a receiver-cap refusal of well-formed
     * bytes (§6.2.1) — is terminal too (§6.3) and behaves the same way, rethrown in
     * the carrier it arrived in and under its own code, never folded into
     * {@code INVALID_MSG}. Running out of bytes mid-field is <em>not</em> that: it
     * suspends and resumes on the next call, as before.
     *
     * @param data    backing array
     * @param off     start offset
     * @param len     number of bytes to consume
     * @param visitor sink for decoded fields
     * @return {@link DecodeStatus#COMPLETE} when the bytes consumed so far end at a
     *         clean field boundary with no open sequence, else
     *         {@link DecodeStatus#INCOMPLETE}
     * @throws SofabException with {@link SofabError#INVALID_MSG} on malformed input,
     *         or on any call after malformed input was already rejected
     * @throws java.io.UncheckedIOException wrapping a {@code LIMIT_EXCEEDED}
     *         {@link SofabException} where a receiver limit refuses this message,
     *         and on every call after that
     */
    public DecodeStatus feed(byte[] data, int off, int len, Visitor visitor) throws SofabException {
        if (terminal == SofabError.LIMIT_EXCEEDED) {
            // Repeated in the carrier it was raised through, so the caller's
            // existing catch still sees it, and still under its own code: §6.3
            // MUST NOT report a receiver-limit rejection as InvalidMessage.
            throw Sofab.limitExceeded(
                    "decode already refused by a receiver limit; reset() to start a new message");
        }
        if (terminal != null) {
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
            if (isTerminal(e.error())) {
                terminal = e.error();
            }
            throw e;
        } catch (UncheckedIOException e) {
            // A Visitor cannot declare a checked exception, so generated code
            // reports a schema bound it must reject (MESSAGE_SPEC §7.1: an
            // over-maxlen length, an over-capacity count, an invalid-UTF-8 string)
            // by wrapping a SofabException, and refuses a receiver cap (§6.2.1) the
            // same way. Both end the decode, so both latch here, each keeping its
            // own code: §6.3 makes the policy rejection terminal too and forbids
            // reporting it as InvalidMessage.
            if (e.getCause() instanceof SofabException cause && isTerminal(cause.error())) {
                terminal = cause.error();
            }
            throw e;
        }
        // The decode survived, so the outcome is one of the two §5.2.1 values that
        // more bytes could still change, computed from the decoder's own state at
        // this byte boundary (§5.2.4 — no finish step, and nothing to latch).
        // COMPLETE only at a true field boundary: no partial field header varint
        // (that is its own state, S_HEADER), no in-progress value/payload/array
        // element (S_IDLE covers the resumable machine and mid-array between
        // elements), and every opened sequence closed (depth == 0). Anything else
        // means the bytes ended inside a field or an open sequence — INCOMPLETE.
        return state == S_IDLE && depth == 0 ? DecodeStatus.COMPLETE : DecodeStatus.INCOMPLETE;
    }

    /**
     * The rejections that end a decode, wherever they were raised: the bytes are
     * malformed ({@link SofabError#INVALID_MSG}, terminal per CORELIB_PLAN §5.2) or
     * a receiver limit refused them ({@link SofabError#LIMIT_EXCEEDED}, "a terminal
     * … policy rejection" per §6.3). The remaining codes belong to the encoder and
     * to argument checks and say nothing about the decode, so they pass through
     * without latching — as a visitor's own I/O failure does.
     */
    private static boolean isTerminal(SofabError error) {
        return error == SofabError.INVALID_MSG || error == SofabError.LIMIT_EXCEEDED;
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
            } else if (data[p] >= 0) {
                // Single-byte varint in the buffer's last nine bytes: the shape
                // every small id takes, read without the out-of-line reader and its
                // hand-back field. `i < end` is the loop's own condition, so the
                // byte is there.
                header = data[p];
                p = i + 1;
            } else {
                header = readWord(data, p, end);
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
                } else if (p < end && data[p] >= 0) {
                    val = data[p];
                    p++;
                } else {
                    val = readWord(data, p, end);
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
            // S_FIXLEN_RAW is armed for F_STRING and F_BLOB only — the two sub-types
            // with a streaming payload — so there is no third case to guard against.
            if (fixlenSubtype == F_STRING) {
                visitor.string(id, fixlenTotal, chunkOffset, data, i, take);
            } else {
                visitor.blob(id, fixlenTotal, chunkOffset, data, i, take);
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
        int b8 = data[p + 8];
        v |= (long) (b8 & 0x7F) << 56;
        if (b8 >= 0) {
            scratchPos = p + 9;
            return v;
        }
        scratchPos = p + 10;
        return v | tenthByte(data, p);
    }

    /**
     * Decode a field whose shape needs more than the straight-line code in
     * {@link #feed}: a fixlen scalar or either kind of array. {@code p} points just
     * past the field header, whose {@code wireType} is passed in. Returns the index
     * just past the bytes consumed; when the field cannot be completed within the
     * buffer the resumable state machine is armed and the index is left at the
     * first byte the machine must re-read.
     *
     * <p>Only wire types 2..5 arrive here: {@link #feed} decodes 0/1 inline and
     * handles both sequence markers before the call, so the four cases below are
     * exhaustive and the last one needs no separate fallback.
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
            default:
                // arrayKind stays unsettled until the fixlen_word names the subtype.
                fixlenArray = true;
                return fastFixlenArray(data, p, end, visitor);
        }
    }

    /**
     * The decoder's one bounded varint reader: consume a varint from
     * {@code data[i..end)} wherever the buffer might end inside it — a field header,
     * a scalar value or an array element in the last nine bytes, and a
     * {@code fixlen_word} or array count anywhere.
     *
     * <p>The value itself is the return value — every 64-bit pattern is a legal one,
     * so completion is signalled out of band: {@link #scratchPos} is left at the
     * position just past the varint, or set to {@code -1} when it runs past
     * {@code end} and the caller must arm the state machine.
     *
     * <p>The {@code >64}-bit overflow test (§4.1/§6.3) can only fire on the tenth
     * byte — the sole shift (63) at which a payload bit spills past bit 63 — so it
     * is tested as {@code shift == 63} rather than recomputed from a per-byte
     * {@code room} subtraction.
     *
     * <p>Kept out of line, and the only reader: every caller short-circuits the
     * one-byte form before reaching it, so what arrives here is the rare
     * multi-byte-near-the-boundary case. A second, check-free copy for the hot sites
     * used to sit alongside it and <em>cost</em> them — two similar readers inlined
     * into the array element loops measured 12% worse on the 1000-element u64 decode
     * than this one does.
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

    /**
     * Value contributed by the <em>tenth</em> byte of a maximal varint, at
     * {@code p + 9}, once the ninth byte at {@code p + 8} has been found to carry a
     * continuation flag. Bit 63 is the only value bit left at that point, so this
     * byte's payload is 0 or 1 and anything else — a higher payload bit, or an
     * eleventh byte implied by a continuation flag — exceeds the 64-bit value range
     * and is malformed (§4.1/§6.3). This is the only place a varint can do so.
     *
     * <p>The ninth byte itself is read by each caller inline rather than here: it
     * ends the varint in the common case, and returning both its contribution and
     * the new position from one call meant handing one of them back through a field
     * that the caller then reloaded — a store/load per long array element. The
     * caller guarantees ten readable bytes from {@code p}, so no length test is
     * needed on either byte.
     */
    private static long tenthByte(byte[] data, int p) throws SofabException {
        int b = data[p + 9];
        if ((b & 0xFE) != 0) {
            throw new SofabException(SofabError.INVALID_MSG, "varint overflow");
        }
        return (long) b << 63;
    }

    /**
     * Judge the sub-type-independent half of a {@code fixlen_word} (§4.6) and
     * return its sub-type. The decoder reads that word from three places — a scalar
     * field ({@link #fastFixlenScalar}), a fixlen-array element
     * ({@link #fastFixlenArray}) and the resumable machine ({@link #stepFixlenLen})
     * — and these two rules belong to the word rather than to the reader, so they
     * live here once: a reserved sub-type (4..7) is malformed, and a declared length
     * above {@code SOFAB_FIXLEN_MAX} is malformed whatever the sub-type turns out to
     * be. Their order is fixed here too, so a word that breaks both names the same
     * rule wherever it is read.
     *
     * <p>The third §4.6 rule — fp32 declares four bytes, fp64 eight — deliberately
     * stays behind, in each caller's arm for that sub-type. It is the one rule that
     * needs the sub-type already selected, and every caller selects on it anyway:
     * moving it here re-tests what the arm has just decided, which <em>measures</em>
     * as +6 Ir/op (+0.5%) on the decode-typical workload, on a decoder whose whole
     * typical message costs 1195. Since it cannot be shared for free, all three
     * sites carry a comment naming the other two so it stays changed in lockstep,
     * and {@code DecodeRuleWrittenOnceTest} drives every wrong width through all
     * four reading surfaces so a copy that drifts fails the suite.
     *
     * @param word the fixlen word, {@code (length << 3) | subtype}
     * @return the sub-type, one of {@link #F_FP32}, {@link #F_FP64},
     *         {@link #F_STRING}, {@link #F_BLOB}
     * @throws SofabException {@link SofabError#INVALID_MSG} if the word carries a
     *         reserved sub-type or a length above the format ceiling
     */
    private static int checkFixlenWord(long word) throws SofabException {
        int subtype = (int) (word & 0x07);
        // Reserved sub-types 0x4..0x7 are malformed (§4.6), and are rejected before
        // the length is judged — the order the enum-based reader had.
        if (subtype > F_BLOB) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + subtype);
        }
        // The shift is unsigned, so a word with the top bits set yields a huge
        // length rather than a negative one, and the ceiling catches it.
        long length = word >>> 3;
        if (length > ARRAY_MAX) {
            throw new SofabException(SofabError.INVALID_MSG, "fixlen length " + length);
        }
        return subtype;
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
        int subtype = checkFixlenWord(fh);
        // Judged against the ceiling above, so the cast cannot lose bits.
        int length = (int) (fh >>> 3);
        // The width rule below is the half of §4.6 that stays with the arm that
        // selects the sub-type (see checkFixlenWord): fastFixlenArray and
        // stepFixlenLen carry it too, and the three must change together.
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
            default: // the check above leaves 0..3, so these two are what remains
                boolean isString = subtype == F_STRING;
                fixlenSubtype = subtype;
                fixlenTotal = length;
                fixlenRemaining = length;
                accLen = 0;
                visitor.fixlenBegin(id, isString ? FixlenType.STRING : FixlenType.BLOB, length);
                if (length == 0) {
                    if (isString) {
                        visitor.string(id, 0, 0, EMPTY, 0, 0);
                    } else {
                        visitor.blob(id, 0, 0, EMPTY, 0, 0);
                    }
                    state = S_IDLE;
                    return p;
                }
                state = S_FIXLEN_RAW; // the payload streams in chunks from here
                if (end - p < length) {
                    return p; // it straddles: resume() takes the rest chunk by chunk
                }
                // The whole payload is in hand, which is the one-shot case and the
                // common streaming one alike. Deliver it here rather than returning
                // to the feed loop only to be dispatched straight back in: the
                // callback and the window handed to it are identical either way.
                if (isString) {
                    visitor.string(id, length, 0, data, p, length);
                } else {
                    visitor.blob(id, length, 0, data, p, length);
                }
                fixlenRemaining = 0;
                state = S_IDLE;
                return p + length;
        }
    }

    /**
     * Fast path for an unsigned/signed varint array; {@code i} points at the count.
     *
     * <p>The element loop is specialised per signedness rather than testing a flag
     * inside it. The two loops are otherwise identical, and folding them into one
     * that takes the flag reads better — but the flag does not fold away even though
     * every call site passes a constant, and the shared loop measures <b>+4.4%</b>
     * Ir/op on the 1000-element u64 decode (58 047 → 60 605). The duplication is
     * bought, not accidental.
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

    /** Elements of an unsigned varint array; {@code p} points at element 0. */
    private int unsignedElements(byte[] data, int p, int end, Visitor visitor) throws SofabException {
        // Hoist the per-element fields into locals: the loop runs once per array
        // element, so reading {@code id} and writing {@code arrayRemaining} straight
        // from memory each time would add a load/store to every element.
        int remaining = arrayRemaining;
        final int fieldId = id;
        // Resolved once at the header and hoisted whole: the destination cannot
        // change while the array runs, so the loop reads locals, not fields.
        final int bw = bulkW; // W_NONE = per-element
        final byte[] dB = bulkB;
        final short[] dS = bulkS;
        final int[] dI = bulkI;
        final long[] dL = bulkL;
        int k = bulkAt;
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
                        int b8 = data[p + 8];
                        val |= (long) (b8 & 0x7F) << 56;
                        if (b8 >= 0) {
                            p += 9;
                        } else {
                            val |= tenthByte(data, p);
                            p += 10;
                        }
                    }
                }
            } else if (p < end && data[p] >= 0) {
                // Single-byte element in the buffer's last nine bytes.
                val = data[p];
                p++;
            } else {
                long tail = readWord(data, p, end);
                if (scratchPos < 0) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining;
                    bulkAt = k;
                    state = S_VARINT_UNSIGNED;
                    return p;
                }
                val = tail;
                p = scratchPos;
            }
            // One predictable branch instead of a call: the whole array takes the
            // same arm, and the destination was resolved once at the header.
            switch (bw) {
                case W_LONG64  -> dL[k++] = val;
                case W_INT32   -> dI[k++] = narrowU32(val);
                case W_SHORT16 -> dS[k++] = narrowU16(val);
                case W_BYTE8   -> dB[k++] = narrowU8(val);
                default        -> visitor.unsigned(fieldId, val); // W_NONE
            }
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = S_IDLE;
        if (bw != W_NONE) {
            bulkAt = k;
            endBulk(visitor, fieldId);
        }
        return p;
    }

    /** Elements of a signed (ZigZag) varint array; {@code p} points at element 0. */
    private int signedElements(byte[] data, int p, int end, Visitor visitor) throws SofabException {
        int remaining = arrayRemaining;
        final int fieldId = id;
        // Resolved once at the header and hoisted whole: the destination cannot
        // change while the array runs, so the loop reads locals, not fields.
        final int bw = bulkW; // W_NONE = per-element
        final byte[] dB = bulkB;
        final short[] dS = bulkS;
        final int[] dI = bulkI;
        final long[] dL = bulkL;
        int k = bulkAt;
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
                        int b8 = data[p + 8];
                        val |= (long) (b8 & 0x7F) << 56;
                        if (b8 >= 0) {
                            p += 9;
                        } else {
                            val |= tenthByte(data, p);
                            p += 10;
                        }
                    }
                }
            } else if (p < end && data[p] >= 0) {
                // Single-byte element in the buffer's last nine bytes.
                val = data[p];
                p++;
            } else {
                long tail = readWord(data, p, end);
                if (scratchPos < 0) {
                    // Element spills past the buffer: machine finishes it from p. The
                    // straddling element is still uncounted, so write back its count.
                    arrayRemaining = remaining;
                    bulkAt = k;
                    state = S_VARINT_SIGNED;
                    return p;
                }
                val = tail;
                p = scratchPos;
            }
            switch (bw) {
                case W_LONG64  -> dL[k++] = zigzagDecode(val);
                case W_INT32   -> dI[k++] = narrowI32(zigzagDecode(val));
                case W_SHORT16 -> dS[k++] = narrowI16(zigzagDecode(val));
                case W_BYTE8   -> dB[k++] = narrowI8(zigzagDecode(val));
                default        -> visitor.signed(fieldId, zigzagDecode(val)); // W_NONE
            }
            remaining--;
        }
        arrayRemaining = remaining;
        inArray = false;
        state = S_IDLE;
        if (bw != W_NONE) {
            bulkAt = k;
            endBulk(visitor, fieldId);
        }
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
        int subtype = checkFixlenWord(fh);
        long lengthValue = fh >>> 3;
        // Width rule, the per-arm half of §4.6 (see checkFixlenWord): the same rule
        // lives in fastFixlenScalar and stepFixlenLen, and the three change together.
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
        } else {
            // What the shared check above leaves: string/blob, which are not valid
            // as fixlen-array elements (§4.8). This is a FORMAT violation, judged
            // before the visitor is offered the field, so it can never be turned
            // into a §7.3 skip.
            throw new SofabException(SofabError.INVALID_MSG, "dynamic fixlen array element");
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
            // emitBegin is false only for a fixlen array, whose kind is not settled
            // until its fixlen_word: this is the integer-array path, the one the
            // bulk offer covers.
            visitor.arrayBegin(id, arrayKind, c);
            armBulk(visitor, c);
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

    /**
     * Put the bulk offer to the visitor for an integer array of {@code c} elements
     * and arm the fill if it is taken. A destination shorter than the announced
     * count is refused rather than partially filled: {@code count} is the wire's
     * claim, and a consumer that sized against a different number must not be able
     * to turn that into an out-of-bounds write.
     */
    private void armBulk(Visitor visitor, int c) {
        bulkB = null;
        bulkS = null;
        bulkI = null;
        bulkL = null;
        bulkW = W_NONE;
        bulkAt = 0;
        if (c == 0) {
            return;
        }
        Object dst = visitor.arrayBulk(id, arrayKind, c);
        // One virtual call and one type resolution per ARRAY. Anything that is not
        // a primitive integer array long enough for the announced count is refused
        // and the elements go the ordinary way, so neither a miscounted nor a
        // mistyped destination can overrun.
        if (dst instanceof long[] a) {
            if (a.length >= c) {
                bulkL = a;
                bulkW = W_LONG64;
            }
        } else if (dst instanceof int[] a) {
            if (a.length >= c) {
                bulkI = a;
                bulkW = W_INT32;
            }
        } else if (dst instanceof short[] a) {
            if (a.length >= c) {
                bulkS = a;
                bulkW = W_SHORT16;
            }
        } else if (dst instanceof byte[] a) {
            if (a.length >= c) {
                bulkB = a;
                bulkW = W_BYTE8;
            }
        }
    }

    /**
     * Narrow one decoded element to the width of the destination the consumer
     * handed back, rejecting a value that width cannot hold.
     *
     * <p>Handing back an array narrower than {@code long[]} declares the elements
     * that wide, so a value with a bit past it is malformed input (MESSAGE_SPEC
     * §7.1) and never silently truncated. Unsigned widths test the bits above the
     * width; signed ones test that narrowing and widening again gives the value
     * back, which is the same statement.
     */
    private static int narrowU32(long v) throws SofabException {
        if ((v & ~0xFFFFFFFFL) != 0) {
            throw tooWide();
        }
        return (int) v;
    }

    private static short narrowU16(long v) throws SofabException {
        if ((v & ~0xFFFFL) != 0) {
            throw tooWide();
        }
        return (short) v;
    }

    private static byte narrowU8(long v) throws SofabException {
        if ((v & ~0xFFL) != 0) {
            throw tooWide();
        }
        return (byte) v;
    }

    private static int narrowI32(long v) throws SofabException {
        if ((int) v != v) {
            throw tooWide();
        }
        return (int) v;
    }

    private static short narrowI16(long v) throws SofabException {
        if ((short) v != v) {
            throw tooWide();
        }
        return (short) v;
    }

    private static byte narrowI8(long v) throws SofabException {
        if ((byte) v != v) {
            throw tooWide();
        }
        return (byte) v;
    }

    private static SofabException tooWide() {
        return new SofabException(SofabError.INVALID_MSG, "array element wider than its destination");
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
     *
     * <p>The wire type is three bits, and all eight of its values are real cases
     * below (§4.3), so the dispatch is exhaustive and carries no unknown-type arm.
     */
    private void stepIdle(int b, Visitor visitor) throws SofabException {
        if (!varintPush(b)) {
            state = S_HEADER; // header still incomplete: feed returns INCOMPLETE
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
            default: // 0..7 are all named above; this is type 7
                if (depth == 0) {
                    throw new SofabException(SofabError.INVALID_MSG, "dangling sequence end");
                }
                depth--;
                state = S_IDLE;
                visitor.sequenceEnd();
                break;
        }
    }

    /**
     * Accumulate an unsigned varint value; on completion emit it and advance to
     * the next array element or back to idle. Serves both scalar fields and
     * unsigned-array elements.
     */
    private void stepVarintUnsigned(int b, Visitor visitor) throws SofabException {
        if (varintPush(b)) {
            if (inArray && bulkW != W_NONE) {
                storeBulk(varintOut);
            } else {
                visitor.unsigned(id, varintOut);
            }
            advanceAfterElement();
            endBulkIfArrayOver(visitor);
        }
    }

    /**
     * Accumulate a signed varint value (ZigZag-decoded on completion); otherwise
     * the signed counterpart of {@link #stepVarintUnsigned}.
     */
    private void stepVarintSigned(int b, Visitor visitor) throws SofabException {
        if (varintPush(b)) {
            if (inArray && bulkW != W_NONE) {
                storeBulkSigned(zigzagDecode(varintOut));
            } else {
                visitor.signed(id, zigzagDecode(varintOut));
            }
            advanceAfterElement();
            endBulkIfArrayOver(visitor);
        }
    }

    /**
     * Close an armed bulk fill once {@link #advanceAfterElement} has taken the array
     * off the machine's hands. The bulk element loops close their own; this is the
     * path where the LAST element of the array straddled a feed boundary, so the
     * machine, not the loop, delivered it.
     */
    private void endBulkIfArrayOver(Visitor visitor) {
        if (bulkW != W_NONE && !inArray) {
            endBulk(visitor, id);
        }
    }

    /**
     * The element loops' store, for the one element the byte-at-a-time machine
     * delivers when an element straddles a feed boundary. Off the hot path, so it
     * reads the fields rather than hoisting them.
     */
    private void storeBulk(long v) throws SofabException {
        switch (bulkW) {
            case W_LONG64  -> bulkL[bulkAt++] = v;
            case W_INT32   -> bulkI[bulkAt++] = narrowU32(v);
            case W_SHORT16 -> bulkS[bulkAt++] = narrowU16(v);
            default        -> bulkB[bulkAt++] = narrowU8(v);
        }
    }

    /** {@link #storeBulk} for a SIGNED array. */
    private void storeBulkSigned(long v) throws SofabException {
        switch (bulkW) {
            case W_LONG64  -> bulkL[bulkAt++] = v;
            case W_INT32   -> bulkI[bulkAt++] = narrowI32(v);
            case W_SHORT16 -> bulkS[bulkAt++] = narrowI16(v);
            default        -> bulkB[bulkAt++] = narrowI8(v);
        }
    }

    /** Release the armed destination and tell the consumer how much was written. */
    private void endBulk(Visitor visitor, int fieldId) {
        int n = bulkAt;
        bulkB = null;
        bulkS = null;
        bulkI = null;
        bulkL = null;
        bulkW = W_NONE;
        visitor.arrayBulkEnd(fieldId, n);
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
        int subtype = checkFixlenWord(header);
        // Judged against the ceiling above, so the cast cannot lose bits.
        int length = (int) (header >>> 3);

        fixlenSubtype = subtype;
        fixlenTotal = length;
        fixlenRemaining = length;
        accLen = 0;

        // Width rule, the per-arm half of §4.6 (see checkFixlenWord): the same rule
        // lives in fastFixlenScalar and fastFixlenArray, and the three change together.
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
            default: // the check above leaves 0..3, so these two are what remains
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
     *
     * <p>{@link #S_FIXLEN_VAL} is armed for fp32 and fp64 alone — string and blob
     * stream through {@link #S_FIXLEN_RAW} instead — so fp64 is simply the other
     * case, with nothing left over to reject.
     */
    private void stepFixlenVal(int b, Visitor visitor) {
        byte[] a = acc; // sized at construction (§6.6): nothing to allocate here
        a[accLen++] = (byte) b;
        fixlenRemaining--;
        if (fixlenRemaining != 0) {
            return;
        }

        // The carry buffer is a plain byte[], so the same little-endian views the
        // one-shot path reads the wire with decode it here — one read each instead
        // of a shift/or chain and a loop, and the two paths cannot drift.
        if (fixlenSubtype == F_FP32) {
            visitor.fp32(id, Float.intBitsToFloat((int) LE_INT.get(a, 0)));
        } else {
            visitor.fp64(id, Double.longBitsToDouble((long) LE_LONG.get(a, 0)));
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
        armBulk(visitor, c);

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
