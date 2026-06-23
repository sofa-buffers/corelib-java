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
 * the scalar / float callbacks with the same {@code id}.
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
     * @param id    field id
     * @param kind  element category
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
