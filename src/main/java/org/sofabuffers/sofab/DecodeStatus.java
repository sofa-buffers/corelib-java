/*
 * SofaBuffers Java - decode outcome: the three of MESSAGE_SPEC §7, plus the
 * fourth CORELIB_PLAN §6.3 allows for a receiver-limit rejection.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * The terminal outcome of a decode, per the finish-less three-valued model in
 * MESSAGE_SPEC §7, plus the fourth outcome CORELIB_PLAN §6.3 allows for a
 * receiver-limit rejection. They are identical for one-shot and streaming
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
 *       {@link SofabException} carrying {@link SofabError#INVALID_MSG}, and the
 *       decoder latches it: {@link IStream#status()} returns this constant from
 *       then on. It outranks the other two — input that is both malformed and
 *       truncated is {@code INVALID}, never {@code INCOMPLETE} — and it is
 *       <b>terminal</b>: no further bytes can revise it, only
 *       {@link IStream#reset()} starting a new message.</li>
 *   <li>{@link #LIMIT_EXCEEDED} — the bytes are well-formed, and a receiver-side
 *       limit (§6.2.1) refused them. §6.3 leaves the surfacing open, "either a
 *       fourth decode outcome, or a terminal failure carrying the
 *       {@code LimitExceeded} code on the error channel"; this library does both,
 *       because a status accessor has no error channel — and either way the
 *       rejection <b>MUST NOT</b> be reported as {@code InvalidMessage}, which is
 *       why it is not {@link #INVALID}. Terminal like {@code INVALID}.</li>
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
     * {@link SofabException} with {@link SofabError#INVALID_MSG} and then latched:
     * {@link IStream#status()} reports it until {@link IStream#reset()}, and no
     * continuation can change it back.
     */
    INVALID,

    /**
     * The bytes are well-formed but a receiver-configured limit (§6.2.1) refused
     * them — "a terminal, receiver-local policy rejection" (§6.3). Surfaced as a
     * thrown {@link SofabException} with {@link SofabError#LIMIT_EXCEEDED},
     * wrapped in an {@link java.io.UncheckedIOException} where a {@link Visitor}
     * callback raised it, and then latched: {@link IStream#status()} reports this
     * constant until {@link IStream#reset()}, and every further
     * {@link IStream#feed} repeats the rejection rather than decoding on. Kept
     * apart from {@link #INVALID} because the same message decodes under a looser
     * limit, and §6.3 forbids reporting it as {@code InvalidMessage}.
     */
    LIMIT_EXCEEDED,
}
