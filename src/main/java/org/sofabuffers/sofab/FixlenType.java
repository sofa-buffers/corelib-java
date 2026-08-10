/*
 * SofaBuffers Java - fixed-length field sub-types.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Sub-type of a fixed-length field — the 3-bit tag encoded in the low bits of a
 * fixlen length header (see the SofaBuffers documentation, "Fixlen Length and
 * Type").
 *
 * <p>The tag travels <em>outwards</em> only: encoders name a sub-type with one of
 * these constants and {@link #raw()} turns it into the wire tag, while the decoder
 * narrows an incoming tag itself at each site that reads a fixlen word — rejecting
 * the reserved values 0x4..0x7 there — and hands the visitor the matching constant.
 * There is deliberately no wire-tag-to-constant entry point: it would be public API
 * with no caller and no reachable failure mode.
 */
public enum FixlenType {
    /** 32-bit IEEE-754 float, little-endian on the wire. */
    FP32(0x0),
    /** 64-bit IEEE-754 double, little-endian on the wire. */
    FP64(0x1),
    /** UTF-8 / raw text, no NUL terminator on the wire. */
    STRING(0x2),
    /** Arbitrary raw bytes. */
    BLOB(0x3);

    private final int raw;

    FixlenType(int raw) {
        this.raw = raw;
    }

    /**
     * The 3-bit wire tag for this sub-type.
     *
     * @return the raw tag value (0..3)
     */
    public int raw() {
        return raw;
    }
}
