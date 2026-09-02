/*
 * SofaBuffers Java - decode outcome: the three of MESSAGE_SPEC §7, of which
 * `feed` returns the two that are not a refusal.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * The outcome of a decode, per the finish-less three-valued model of
 * CORELIB_PLAN §5.2.1 / MESSAGE_SPEC §7. It is identical for one-shot and
 * streaming decodes, it describes the bytes consumed <b>so far</b>, and there is
 * <b>no</b> finish/finalize step: "the status {@code feed} returns <em>is</em> the
 * answer" (§5.2.4), and the caller owns end-of-input.
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
 *       It names the third outcome; it is not a value {@link IStream#feed}
 *       returns, because this library carries a refusal on the error channel —
 *       see below.</li>
 * </ul>
 *
 * <p><b>Which of the three {@code feed} returns.</b> {@link IStream#feed} returns
 * {@code COMPLETE} or {@code INCOMPLETE} — the two outcomes more bytes can still
 * change. {@code INVALID} arrives instead as a thrown {@link SofabException}
 * carrying {@link SofabError#INVALID_MSG}, the code §6.3 pairs with this outcome
 * ("{@code INVALID} corresponds to {@code InvalidMessage}"), and the same
 * exception channel carries {@link SofabError#LIMIT_EXCEEDED}, the receiver-cap
 * refusal of well-formed bytes that §6.3 keeps distinct from it. §6.0 names the
 * three outcomes; it does not prescribe the carrier, and refusals leave by the
 * one route a Java caller cannot ignore. Both are terminal: the decoder latches
 * them and every further {@code feed} re-throws until {@link IStream#reset()}.
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
     * The bytes are malformed regardless of what follows — the third outcome of
     * §5.2.1, and the one {@link IStream#feed} does not return: it throws a
     * {@link SofabException} with {@link SofabError#INVALID_MSG} instead, then
     * latches the verdict so no continuation can revise it and every further
     * {@code feed} throws again until {@link IStream#reset()}.
     */
    INVALID,
}
