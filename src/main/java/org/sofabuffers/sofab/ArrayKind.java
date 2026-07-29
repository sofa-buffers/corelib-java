/*
 * SofaBuffers Java - array element category.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Element category of an array field, reported to a {@link Visitor} via
 * {@link Visitor#arrayBegin} just before the elements are delivered.
 *
 * <p>A fixlen array names its concrete element subtype ({@link #FP32} /
 * {@link #FP64}), never a collapsed "floating point" category: CORELIB_PLAN §4.8
 * has the element subtype decide whether a field contradicts the schema and must
 * be skipped (MESSAGE_SPEC §7.3), so the subtype has to reach the visitor. The
 * ordinals are normative across the family: {@code UNSIGNED = 0},
 * {@code SIGNED = 1}, {@code FP32 = 2}, {@code FP64 = 3}.
 */
public enum ArrayKind {
    /** Unsigned-integer elements, delivered through {@link Visitor#unsigned}. */
    UNSIGNED,
    /** Signed-integer elements, delivered through {@link Visitor#signed}. */
    SIGNED,
    /** IEEE-754 32-bit float elements, delivered through {@link Visitor#fp32}. */
    FP32,
    /** IEEE-754 64-bit double elements, delivered through {@link Visitor#fp64}. */
    FP64,
}
