/*
 * SofaBuffers Java - three-valued decode outcome (MESSAGE_SPEC §7).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * The terminal outcome of a decode, per the finish-less three-valued model in
 * MESSAGE_SPEC §7. The three outcomes are identical for one-shot and streaming
 * decodes, and there is <b>no</b> finish/finalize step: the caller owns
 * end-of-input.
 *
 * <ul>
 *   <li>{@link #COMPLETE} — the consumed bytes end exactly at a field boundary
 *       (a valid message).</li>
 *   <li>{@link #INCOMPLETE} — the consumed bytes end <em>inside</em> a field (a
 *       partial varint, a fixlen/array payload shorter than declared) or with an
 *       open, unclosed sequence. This is <b>not</b> an error: more bytes could
 *       complete the message, and the caller decides whether a trailing
 *       {@code INCOMPLETE} is a truncation it cares about.</li>
 *   <li>{@link #INVALID} — the bytes are malformed regardless of what follows
 *       (varint over 64 bits, bad type/subtype tag, length/count/id over max,
 *       dangling sequence-end, nesting past {@code MAX_DEPTH}, invalid UTF-8).
 *       Malformed input surfaces as a thrown
 *       {@link SofabException} carrying {@link SofabError#INVALID_MSG}; this
 *       constant names that outcome for completeness of the three-valued model.
 *       {@link IStream#status()} therefore only ever returns {@link #COMPLETE}
 *       or {@link #INCOMPLETE}.</li>
 * </ul>
 */
public enum DecodeStatus {
    /** The consumed bytes end exactly at a field boundary — a valid message. */
    COMPLETE,

    /**
     * The consumed bytes end inside a field or with an open sequence; more bytes
     * could complete the message. Not an error.
     */
    INCOMPLETE,

    /**
     * The bytes are malformed regardless of what follows. Surfaced as a thrown
     * {@link SofabException} with {@link SofabError#INVALID_MSG}; never returned
     * by {@link IStream#status()}.
     */
    INVALID,
}
