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

    /** Invalid API usage (e.g. a decoded value does not fit the requested type). */
    USAGE,

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
     * sofabgen config and baked into generated code as constants; the generated
     * decode visitor guards on the wire count / total length that the corelib
     * exposes <em>before</em> any allocation ({@code arrayBegin(id, kind, count)},
     * {@code string}/{@code blob(id, total, offset, chunk)}) and raises this on a
     * violation.
     *
     * <p><b>Not wire malformation.</b> Exceeding a receiver-configured limit is
     * policy, not a property of the bytes: the same message is accepted by a
     * backend with a higher (or no) limit. This category is therefore kept
     * strictly distinct from {@link #INVALID_MSG} so that policy divergence between
     * backends with different configured limits is not mistaken for a
     * wire-conformance divergence (e.g. by the Crucible differential fuzzer).
     *
     * <p><b>Always a hard error.</b> A limit violation is never clamped and never
     * truncated; the generated code raises it before allocating.
     *
     * <p>This corelib neither enforces these limits nor defines any default
     * values — enforcement lives in the generated decoder. This category exists so
     * that generated code across the codebase reports a limit violation uniformly.
     */
    LIMIT_EXCEEDED,
}
