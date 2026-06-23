/*
 * SofaBuffers Java - array element category.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Element category of an array field, reported to a {@link Visitor} via
 * {@link Visitor#arrayBegin} just before the elements are delivered.
 */
public enum ArrayKind {
    /** Unsigned-integer elements, delivered through {@link Visitor#unsigned}. */
    UNSIGNED,
    /** Signed-integer elements, delivered through {@link Visitor#signed}. */
    SIGNED,
    /** Floating-point elements, delivered through {@code fp32} / {@code fp64}. */
    FIXLEN,
}
