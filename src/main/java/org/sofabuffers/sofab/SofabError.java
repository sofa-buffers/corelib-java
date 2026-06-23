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
 * Java API models as a normal return). Every {@link SofabException} carries one
 * of these so callers can branch on the cause without string matching.
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
     */
    INVALID_MSG,
}
