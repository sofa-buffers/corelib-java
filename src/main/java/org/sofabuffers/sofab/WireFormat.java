/*
 * SofaBuffers Java - shared wire constants and varint/zigzag codecs.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Wire constants and the base-128 varint / ZigZag helpers shared by the encoder
 * and decoder.
 *
 * <p>Package-private: these are an implementation detail of the {@code sofab}
 * core, not part of the public API.
 */
final class WireFormat {

    private WireFormat() {
    }

    // --- field-header 3-bit type tags (low 3 bits of the id header varint) ---
    static final int T_VARINT_UNSIGNED      = 0x0;
    static final int T_VARINT_SIGNED        = 0x1;
    static final int T_FIXLEN               = 0x2;
    static final int T_VARINTARRAY_UNSIGNED = 0x3;
    static final int T_VARINTARRAY_SIGNED   = 0x4;
    static final int T_FIXLENARRAY          = 0x5;
    static final int T_SEQUENCE_START       = 0x6;
    static final int T_SEQUENCE_END         = 0x7;

    /** Largest valid field id ({@code INT32_MAX}), matching {@code SOFAB_ID_MAX}. */
    static final int ID_MAX = Integer.MAX_VALUE;

    /**
     * Largest array element count / fixlen byte length ({@code INT32_MAX}),
     * matching {@code SOFAB_ARRAY_MAX} / {@code SOFAB_FIXLEN_MAX}.
     */
    static final long ARRAY_MAX = Integer.MAX_VALUE;

    /** Number of value bits; bounds the maximum varint length (64-bit value type). */
    static final int VALUE_BITS = 64;

    /**
     * ZigZag-encode a signed value to its unsigned varint representation.
     *
     * @param v signed value
     * @return the unsigned (bit-pattern) representation
     */
    static long zigzagEncode(long v) {
        return (v << 1) ^ (v >> (Long.SIZE - 1));
    }

    /**
     * ZigZag-decode an unsigned varint back to a signed value.
     *
     * @param u unsigned (bit-pattern) representation
     * @return the signed value
     */
    static long zigzagDecode(long u) {
        return (u >>> 1) ^ -(u & 1L);
    }
}
