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
     * it travels through {@link #limitExceeded(String)} instead.
     *
     * @param detail human-readable context, naming the field and the bound it broke
     * @return the exception to throw
     */
    public static UncheckedIOException invalid(String detail) {
        return new UncheckedIOException(new SofabException(SofabError.INVALID_MSG, detail));
    }

    /**
     * The carrier a receiver-side limit (§6.2.1) is refused through: a
     * {@link SofabError#LIMIT_EXCEEDED} {@link SofabException}, wrapped like
     * {@link #invalid} so it can leave a callback that declares no checked
     * exception.
     *
     * <p>It is the twin of {@link #invalid} and deliberately not the same thing.
     * {@code INVALID_MSG} says <em>these bytes are broken</em> and is latched by
     * {@link IStream#feed}, so the decode is terminal and every later call repeats
     * the rejection. {@code LIMIT_EXCEEDED} says <em>these bytes are fine and this
     * receiver declines to hold that much</em>: the same message decodes under a
     * looser limit, so it is not latched, is never folded into
     * {@link DecodeStatus#INVALID}, and is never clamped or truncated into a
     * shortened value.
     *
     * <p>Raised from two places, and only where a cap was actually supplied:
     * {@link PayloadAcc#string} / {@link PayloadAcc#blob} at a payload's announced
     * length, and the {@link Seq} row reservations at a wrapper array's element
     * index. Both take the number as an argument — see {@link #SCHEMA_BOUNDED}.
     *
     * @param detail human-readable context, naming the value and the limit it broke
     * @return the exception to throw
     */
    public static UncheckedIOException limitExceeded(String detail) {
        return new UncheckedIOException(new SofabException(SofabError.LIMIT_EXCEEDED, detail));
    }

    /**
     * What a caller passes for a receiver cap where the <b>schema</b> bounds the
     * field, so §6.2.1 forbids a receiver cap on it: "they <b>MUST NOT</b> be
     * applied to a field the schema already bounds. There the schema bound governs
     * and its violation is {@code INVALID}".
     *
     * <p><b>It is not "unlimited".</b> It states that this field's ceiling is the
     * schema's — a {@code maxlen} or a {@code count} the caller has already
     * enforced with {@link #invalid}, at the same length or count header — and that
     * there is therefore no second, receiver-configured number to compare against.
     * §6.2.1 admits no unset state and no unlimited mode: passing this on a field
     * the schema leaves <em>unbounded</em> lets the sender choose how much this
     * process holds, and is a defect in the <b>call</b>, not a mode this library
     * offers.
     *
     * <p>Any negative value reads the same way; this is the one to write, because
     * a call site that spells it says which of the two rules governs the field.
     *
     * <p>This library holds no receiver limit and defines no default for one
     * (§6.2.1: a codec "<b>MUST NOT</b> hold a limit of its own, <b>MUST NOT</b>
     * supply a default for one it was not given"). {@link #ARRAY_MAX} and
     * {@link #ID_MAX} are <em>format</em> ceilings — exceeding one is
     * {@link SofabError#INVALID_MSG}, never {@link SofabError#LIMIT_EXCEEDED} —
     * and are not receiver caps of any kind.
     */
    public static final long SCHEMA_BOUNDED = -1L;
}
