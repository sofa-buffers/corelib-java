/*
 * SofaBuffers Java - library constants.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Library-level constants for the SofaBuffers ({@code sofab}) core.
 *
 * <p>These mirror the normative limits in the SofaBuffers architecture guide
 * (§6.2). {@link #API_VERSION} lets callers and the schema-driven code generator
 * verify compatibility at build or run time.
 */
public final class Sofab {

    private Sofab() {
    }

    /**
     * SofaBuffers core API version. Callers and the generator check this for
     * compatibility; the current contract is version {@code 1}.
     *
     * <p>This tracks the <b>wire contract</b> — normative and identical in every
     * port (CORELIB_PLAN §6.2) — so it moves only when the bytes on the wire change
     * meaning. It is <em>not</em> this library's source-compatibility version: a
     * release that renames or removes a Java method bumps the artifact version in
     * {@code pom.xml} and leaves {@code API_VERSION} alone.
     */
    public static final int API_VERSION = 1;

    /** Largest valid field id, {@code 2^31 - 1} ({@code INT32_MAX}). */
    public static final int ID_MAX = Integer.MAX_VALUE;

    /**
     * Largest array element count / fixed-length byte count, {@code 2^31 - 1}
     * ({@code INT32_MAX}).
     */
    public static final long ARRAY_MAX = Integer.MAX_VALUE;

    /**
     * Smallest output buffer this port accepts <b>for streaming</b> (CORELIB_PLAN
     * §5.1). It is {@code 1}: the encoder splits every atomic unit, so no write has
     * to land contiguously and a single usable byte is enough.
     *
     * <p><b>It binds a buffer installed with a {@link FlushSink}</b> — at
     * construction and at every mid-stream {@link OStream#bufferSet}, both of which
     * reject {@code buffer.length - offset < MIN_OUTPUT_BUFFER} with
     * {@link IllegalArgumentException} where the buffer is handed over, never
     * partway through a message. A buffer installed <em>without</em> a sink is
     * subject to no minimum: no flush can occur there, so a caller sizing from the
     * generated {@code MAX_SIZE} keeps an exact fit.
     *
     * <p>Any size at or above this produces output <b>byte-identical</b> to the
     * one-shot path, so sizing a streaming buffer from this constant trades nothing
     * but flush frequency.
     */
    public static final int MIN_OUTPUT_BUFFER = 1;

    /**
     * Maximum nested-sequence depth (§4.9 / §6.2). An encoder must not open more
     * than {@code MAX_DEPTH} nested sequences, and a decoder rejects a message that
     * nests deeper with {@link SofabError#INVALID_MSG}, bounding recursion / stack
     * growth.
     */
    public static final int MAX_DEPTH = 255;
}
