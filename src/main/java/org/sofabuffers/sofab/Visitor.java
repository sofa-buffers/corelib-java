/*
 * SofaBuffers Java - decoder visitor.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Receives decoded fields pushed by an {@link IStream}.
 *
 * <p>The decoder follows the <em>visitor pattern</em>: rather than binding a
 * destination buffer per field (as the C API does), it calls back into a
 * {@code Visitor} as each field is decoded. Every method has a default no-op
 * implementation, so an implementor overrides only the field kinds it cares
 * about; unhandled fields are simply dropped (the equivalent of "not
 * interested" / skip in the C API). This keeps generated message classes small:
 * a generated {@code Visitor} is typically one {@code switch} on the field id.
 *
 * <p><b>Streaming contract.</b> Scalars and floats are delivered whole. String
 * and blob payloads are delivered in one or more chunks so they can exceed the
 * input chunk size (and even RAM); each chunk reports the field {@code total}
 * length and the byte {@code offset} of the chunk within the field. Array
 * elements are announced once via {@link #arrayBegin} and then delivered through
 * the scalar / float callbacks with the same {@code id}. A fixlen field —
 * string, blob, fp32 or fp64 — is announced once via {@link #fixlenBegin} at its
 * length word, before any payload byte.
 *
 * <p><b>Buffer ownership.</b> The {@code data} array handed to {@link #string}
 * and {@link #blob} is the caller's input buffer; it is only valid for the
 * duration of the call. A visitor that needs to retain bytes must copy the
 * {@code [chunkOffset, chunkOffset + chunkLength)} range.
 */
public interface Visitor {

    /** An unsigned-integer field, or an unsigned array element.
     *
     * @param id    field id
     * @param value the value (unsigned 64-bit; interpret with
     *              {@link Long#toUnsignedString} / {@link Long#compareUnsigned})
     */
    default void unsigned(int id, long value) {
    }

    /** A signed-integer field, or a signed array element.
     *
     * @param id    field id
     * @param value the value
     */
    default void signed(int id, long value) {
    }

    /** A 32-bit float field, or an {@code fp32} array element.
     *
     * @param id    field id
     * @param value the value
     */
    default void fp32(int id, float value) {
    }

    /** A 64-bit float field, or an {@code fp64} array element.
     *
     * @param id    field id
     * @param value the value
     */
    default void fp64(int id, double value) {
    }

    /**
     * Start of a fixlen field — string, blob, fp32 or fp64. Announced once the
     * {@code fixlen_word} has been read and validated as a format matter, and
     * before any payload byte is delivered. Called exactly once per fixlen field,
     * {@code total == 0} included; the payload then follows through
     * {@link #string} / {@link #blob} (in chunks) or {@link #fp32} /
     * {@link #fp64} (whole).
     *
     * <p><b>Why the decoder announces it here.</b> CORELIB_PLAN §5.2 makes INVALID
     * dominate INCOMPLETE: once the bytes seen so far are already malformed,
     * running out of input cannot downgrade the verdict. A {@code maxlen}
     * violation is fully established by the length word — the number that exceeds
     * the bound is already on the wire, and no later byte can make it legal. The
     * payload callbacks carry {@code total} too, but they only fire once payload
     * bytes exist, so a message truncated immediately after the length word would
     * produce no event at all and degrade to INCOMPLETE, while the same bytes
     * arriving in one chunk are INVALID. That is a chunk-boundary-dependent
     * outcome, which §6.4 and §7.2 item 4 forbid outright. Announcing at the
     * length word is what lets generated code latch the violation there;
     * <em>raising</em> from this callback is what turns the field INVALID.
     *
     * <p>{@code subtype} is the subtype that <em>arrived</em>, which the corelib
     * knows, not the one the schema <em>declared</em>, which it does not: a
     * subtype contradicting the declared field type must be ignored as a
     * MESSAGE_SPEC §7.3 skip rather than measured against this field's bound —
     * the same rule {@link #arrayBegin} follows one field kind over.
     *
     * <p>This fires for a fixlen <em>field</em> only. A fixlen array's per-element
     * {@code fixlen_word} is the array's header, and is announced by
     * {@link #arrayBegin} instead; its elements produce no {@code fixlenBegin}.
     *
     * @param id      field id
     * @param subtype the fixlen subtype read from the wire
     * @param total   declared payload length in bytes (4 for fp32, 8 for fp64)
     */
    default void fixlenBegin(int id, FixlenType subtype, int total) {
    }

    /**
     * A chunk of a string field (raw UTF-8 bytes, no NUL terminator).
     *
     * <p>For an empty string this is called once with {@code total == 0} and
     * {@code chunkLength == 0}.
     *
     * @param id          field id
     * @param total       full field length in bytes
     * @param offset      byte position of this chunk within the field
     * @param data        backing array containing the chunk
     * @param chunkOffset start of the chunk within {@code data}
     * @param chunkLength number of bytes in the chunk
     */
    default void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
    }

    /**
     * A chunk of a blob field. See {@link #string} for the chunking model.
     *
     * @param id          field id
     * @param total       full field length in bytes
     * @param offset      byte position of this chunk within the field
     * @param data        backing array containing the chunk
     * @param chunkOffset start of the chunk within {@code data}
     * @param chunkLength number of bytes in the chunk
     */
    default void blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
    }

    /**
     * Start of an array field. The {@code count} elements follow through the
     * scalar / float callbacks with the same {@code id}.
     *
     * <p><b>When it fires.</b> For an integer array (wire types
     * {@code ARRAY_UNSIGNED} / {@code ARRAY_SIGNED}) this is called immediately
     * after the element-count varint — that word is the whole header. For a
     * fixlen array ({@code ARRAY_FIXLEN}) it is called only after the
     * {@code fixlen_word} that follows the count has been read <em>and</em>
     * validated as a format matter, so {@code kind} always names the concrete
     * element subtype ({@link ArrayKind#FP32} / {@link ArrayKind#FP64}).
     * CORELIB_PLAN §4.8 requires that ordering: a fixlen array whose subtype
     * contradicts the declared element type is skipped under MESSAGE_SPEC §7.3
     * and its {@code count} must not be judged against a schema bound, so the
     * subtype has to be known before the visitor is asked about the field.
     * Either way the call happens exactly once per array field, before any
     * element callback; a message that ends between the two words produces no
     * call at all (it is INCOMPLETE, not INVALID).
     *
     * @param id    field id
     * @param kind  element category, naming the fixlen subtype for a fixlen array
     * @param count number of elements
     */
    default void arrayBegin(int id, ArrayKind kind, int count) {
    }

    /** Start of a nested sequence (a new id scope).
     *
     * @param id field id of the sequence
     */
    default void sequenceBegin(int id) {
    }

    /** End of the current nested sequence. */
    default void sequenceEnd() {
    }
}
