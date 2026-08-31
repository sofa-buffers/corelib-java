/*
 * SofaBuffers Java - error codes.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Error categories raised by the encoder and decoder.
 *
 * <p>Mirrors the C {@code sofab_ret_t} status codes (minus {@code OK}, which the
 * Java API models as a normal return), plus {@link #LIMIT_EXCEEDED}, a
 * receiver-side policy category that has no wire-format equivalent (analogous to
 * corelib-go's {@code ErrLimitExceeded} sentinel). Every {@link SofabException}
 * carries one of these so callers can branch on the cause without string matching.
 */
public enum SofabError {
    /** Invalid caller argument (e.g. a field id outside {@code 0..ID_MAX}). */
    ARGUMENT,

    /** The output buffer is full and no {@link FlushSink} is available. */
    BUFFER_FULL,

    /**
     * The input bytes are not a valid Sofab message (varint overflow, bad type
     * tag, zero-length array, dangling sequence end, ...).
     *
     * <p>This is a statement about the <em>wire bytes</em>: they are malformed
     * regardless of what follows or how the receiver is configured. It is
     * deliberately distinct from {@link #LIMIT_EXCEEDED}, which is a policy
     * decision about otherwise-well-formed bytes.
     */
    INVALID_MSG,

    /**
     * A well-formed message field exceeds a receiver-configured decode limit for
     * an unbounded (dynamic) field — one whose schema declares no
     * {@code count}/{@code maxlen}. The limits ({@code max_dyn_array_count},
     * {@code max_dyn_string_len}, {@code max_dyn_blob_len}) are configured in the
     * sofabgen config and baked into generated code as constants (CORELIB_PLAN
     * §6.2.1: "The numbers and the allocation are not the codec's").
     *
     * <p><b>Where the comparison runs.</b> §6.2.1 leaves that open — "A corelib
     * MAY take a limit as an argument and perform the check itself" — and this
     * library takes it on the calls generated code already makes at the length or
     * index the limit guards: {@link PayloadAcc#string} / {@link PayloadAcc#blob},
     * and the {@link Seq} row reservations. Each is raised through
     * {@link Sofab#limitExceeded}. What has no such call — a native array count,
     * the element index of a flat wrapper array — is still guarded by generated
     * code on the count the visitor is handed ({@code arrayBegin(id, kind, count)},
     * {@code string}/{@code blob(id, total, offset, chunk)}), before it allocates.
     * Never both for one rule: §6.2.1 admits one implementation per check.
     *
     * <p><b>Not wire malformation.</b> Exceeding a receiver-configured limit is
     * policy, not a property of the bytes: the same message is accepted by a
     * backend with a higher (or no) limit. This category is therefore kept
     * strictly distinct from {@link #INVALID_MSG} so that policy divergence between
     * backends with different configured limits is not mistaken for a
     * wire-conformance divergence (e.g. by the Crucible differential fuzzer).
     *
     * <p><b>Always a hard error.</b> A limit violation is never clamped and never
     * truncated; it is raised before the allocation it prevents, and
     * {@link IStream#feed} does not latch it as {@link #INVALID_MSG} would be.
     *
     * <p><b>This corelib defines no limit and no default for one.</b> It holds no
     * {@code max_dyn_*} value, supplies none to a caller who states none, and
     * never presents a format ceiling ({@link Sofab#ARRAY_MAX},
     * {@link Sofab#ID_MAX}) as a receiver cap — breaching one of those is
     * {@link #INVALID_MSG}. A number it is handed is used for that one comparison
     * and not retained (§6.2.1).
     */
    LIMIT_EXCEEDED,
}
