/*
 * SofaBuffers Java - library constants and the INVALID carrier.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.UncheckedIOException;

/**
 * Library-level constants for the SofaBuffers ({@code sofab}) core.
 *
 * <p>These mirror the normative limits in the SofaBuffers architecture guide
 * (§6.2). {@link #API_VERSION} lets callers and the schema-driven code generator
 * verify compatibility at build or run time.
 *
 * <p>{@link #invalid(String)} is here too, and is not a constant: it is the one
 * way a {@link Visitor} — that is, generated code — reports malformed input, and
 * belongs beside the contract it is part of.
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

    /**
     * The carrier a {@link Visitor} rejects malformed input through: an
     * {@link SofabError#INVALID_MSG} {@link SofabException}, wrapped so it can
     * leave a callback that declares no checked exception.
     *
     * <p>Every {@code Visitor} method is declared {@code throws}-free, because a
     * visitor is called from the middle of the decoder's state machine and most
     * implementations have nothing to report. Generated code does: MESSAGE_SPEC §7
     * puts the schema bounds — a length above a {@code maxlen}, a count above a
     * declared capacity, an index above one, a value outside a declared width, a
     * string that is not UTF-8 — on the side that knows the schema, which is the
     * visitor and not this library. So the rejection travels as an unchecked
     * wrapper, and {@link IStream#feed} recognizes it on the way out: an
     * {@code INVALID_MSG} raised by a visitor latches the decode exactly as one
     * raised by the decoder itself does, so it is terminal and {@code status()}
     * reports {@link DecodeStatus#INVALID} from then on (CORELIB_PLAN §5.2). The
     * wrapper itself reaches the caller unchanged; the {@link SofabException} is
     * its {@code cause}.
     *
     * <p>That makes it a two-sided contract, and this is the side that names it.
     * Throw the result rather than calling it for effect — {@code throw
     * Sofab.invalid(...)} ends the method as far as the compiler is concerned:
     *
     * <pre>{@code
     * if (total > 8) {
     *     throw Sofab.invalid("name: string length above schema maxlen 8");
     * }
     * }</pre>
     *
     * <p>{@link SofabError#LIMIT_EXCEEDED} — a receiver-side policy rejection of
     * well-formed bytes (§6.2.1) — is deliberately not this, and is not latched:
     * wrap it explicitly where it is raised.
     *
     * @param detail human-readable context, naming the field and the bound it broke
     * @return the exception to throw
     */
    public static UncheckedIOException invalid(String detail) {
        return new UncheckedIOException(new SofabException(SofabError.INVALID_MSG, detail));
    }
}
