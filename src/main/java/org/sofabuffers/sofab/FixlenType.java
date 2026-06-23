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

    /**
     * Decode a 3-bit fixlen tag from the wire.
     *
     * @param raw the tag value (low 3 bits of the fixlen header)
     * @return the matching {@link FixlenType}
     * @throws SofabException with {@link SofabError#INVALID_MSG} for a reserved
     *                        or unsupported tag
     */
    public static FixlenType fromRaw(int raw) throws SofabException {
        switch (raw) {
            case 0x0: return FP32;
            case 0x1: return FP64;
            case 0x2: return STRING;
            case 0x3: return BLOB;
            default:  throw new SofabException(SofabError.INVALID_MSG, "fixlen type " + raw);
        }
    }
}
