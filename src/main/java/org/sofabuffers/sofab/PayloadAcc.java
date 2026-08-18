/*
 * SofaBuffers Java - support layer for generated code: reassembly of a string
 * or blob payload delivered in chunks.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.util.Arrays;

/**
 * Reassembles a {@code string} or {@code blob} payload that arrives in more than
 * one piece — the <b>support layer</b>, not the codec.
 *
 * <p>{@link Visitor#string} and {@link Visitor#blob} deliver a payload in one or
 * more chunks, split wherever the input happened to be split, and the {@code data}
 * array they hand over is only valid for the duration of the call. A consumer that
 * wants the whole value therefore has to buffer the pieces. That is all this is,
 * and its code has the same shape for every schema, so it lives here rather than
 * being emitted into every generated package (generator#345).
 *
 * <p>Hold one per visitor and pass the callback's arguments straight through; the
 * value comes back on the chunk that completes it, and {@code null} before that:
 *
 * <pre>{@code
 * public void string(int id, int total, int offset, byte[] data, int co, int cl) {
 *     String s = acc.string(total, offset, data, co, cl);
 *     if (s == null) {
 *         return;                  // more chunks to come
 *     }
 *     ...                          // route s to its field
 * }
 * }</pre>
 *
 * <p><b>A payload that arrives whole never touches the buffer.</b> The common case
 * — one chunk carrying the entire field — is answered straight out of the caller's
 * input array, so an accumulator that is never needed never allocates one byte.
 *
 * <p><b>{@code total} is not an allocation.</b> The announced length is the wire's
 * claim, bounded by nothing this class knows about, so the buffer grows by
 * doubling against bytes that have actually arrived. A caller with a schema
 * {@code maxlen} or a receiver limit rejects an oversized {@code total} before the
 * first chunk reaches here (MESSAGE_SPEC §7.1); a caller without one still cannot
 * be made to allocate more than the peer actually sent.
 *
 * <p><b>No re-arming step.</b> Every payload's first chunk is reported at offset
 * 0, and that is where the buffer is emptied — so an accumulator still holding the
 * remains of a payload that never completed (a stream that ended mid-field) is
 * correct again the moment the next one starts, whether or not the visitor around
 * it was reused.
 *
 * <p><b>The split must not be observable.</b> CORELIB_PLAN §6.4 forbids an outcome
 * that depends on where a chunk boundary fell — for the same bytes, the value and
 * the UTF-8 verdict are the same whether they arrive in one piece or one byte at a
 * time.
 */
public final class PayloadAcc {

    /** Accumulated bytes of the payload in flight; empty until one is split. */
    private byte[] buf = Seq.EMPTY_BYTES;

    /** How much of {@link #buf} is filled. */
    private int len;

    /** A fresh accumulator, holding no payload and no buffer. */
    public PayloadAcc() {
    }

    /**
     * Offer a chunk of a {@code string} payload; returns the decoded string once
     * the last chunk has arrived, {@code null} while more are expected.
     *
     * <p>Validation happens on the reassembled payload, once, at the point it is
     * complete — never per chunk, which would reject a multi-byte character split
     * across a boundary.
     *
     * @param total       full payload length in bytes, as {@link Visitor#string}
     *                    reports it
     * @param offset      byte position of this chunk within the payload
     * @param data        backing array containing the chunk
     * @param chunkOffset start of the chunk within {@code data}
     * @param chunkLength number of bytes in the chunk
     * @return the completed string, or null if the payload is still incomplete
     * @throws java.io.UncheckedIOException wrapping an {@code INVALID_MSG}
     *                                      {@link SofabException} when the
     *                                      completed payload is not valid UTF-8
     */
    public String string(int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        if (offset == 0 && chunkLength >= total) {
            return Utf8.decode(data, chunkOffset, total);
        }
        if (!append(total, offset, data, chunkOffset, chunkLength)) {
            return null;
        }
        len = 0;
        return Utf8.decode(buf, 0, total);
    }

    /**
     * Offer a chunk of a {@code blob} payload; returns the payload once the last
     * chunk has arrived, {@code null} while more are expected.
     *
     * <p>The returned array is the caller's to keep: it is a copy, never a view
     * into the decoder's input buffer or into this accumulator.
     *
     * @param total       full payload length in bytes, as {@link Visitor#blob}
     *                    reports it
     * @param offset      byte position of this chunk within the payload
     * @param data        backing array containing the chunk
     * @param chunkOffset start of the chunk within {@code data}
     * @param chunkLength number of bytes in the chunk
     * @return the completed payload, or null if it is still incomplete
     */
    public byte[] blob(int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        if (offset == 0 && chunkLength >= total) {
            return Arrays.copyOfRange(data, chunkOffset, chunkOffset + total);
        }
        if (!append(total, offset, data, chunkOffset, chunkLength)) {
            return null;
        }
        len = 0;
        return Arrays.copyOf(buf, total);
    }

    /**
     * Append a chunk, growing the buffer as bytes actually arrive.
     *
     * @return true once {@code total} bytes stand in the buffer
     */
    private boolean append(int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        if (offset == 0) {
            // A payload starting over: whatever stands here belongs to one that
            // never completed — a stream that ended mid-field — and must not be
            // prefixed onto this one.
            len = 0;
        }
        int need = len + chunkLength;
        if (need > buf.length) {
            // Double, but never below what this chunk needs, and never above the
            // announced total: a payload that arrives in n pieces is copied a
            // logarithmic number of times, and one that arrives whole after a first
            // partial chunk lands in an exactly-sized buffer.
            long grown = (long) buf.length * 2;
            if (grown < need) {
                grown = need;
            }
            if (grown > total) {
                grown = Math.max(total, need);
            }
            buf = Arrays.copyOf(buf, (int) grown);
        }
        System.arraycopy(data, chunkOffset, buf, len, chunkLength);
        len = need;
        return len >= total;
    }
}
