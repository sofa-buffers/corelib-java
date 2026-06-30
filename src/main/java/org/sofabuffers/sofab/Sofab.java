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
     * compatibility; the current wire/API contract is version {@code 1}.
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
     * Maximum nested-sequence depth (§4.9 / §6.2). An encoder must not open more
     * than {@code MAX_DEPTH} nested sequences, and a decoder rejects a message that
     * nests deeper with {@link SofabError#INVALID_MSG}, bounding recursion / stack
     * growth.
     */
    public static final int MAX_DEPTH = 255;
}
