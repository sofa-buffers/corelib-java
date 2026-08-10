/*
 * SofaBuffers Java - shared wire-vector literals for tests.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.common;

/**
 * Builders for the byte vectors the decode/encode tests assert against. Every
 * test spells its vectors as {@code bytes(0x05, 0x01, 0x20)} so the source reads
 * like the wire dump it stands for; {@link #concat} glues a header to a payload.
 */
public final class Wire {

    private Wire() {
    }

    /** The bytes {@code values}, each truncated to its low eight bits. */
    public static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    /** {@code parts} laid end to end in a fresh array. */
    public static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] p : parts) {
            total += p.length;
        }
        byte[] out = new byte[total];
        int n = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, n, p.length);
            n += p.length;
        }
        return out;
    }
}
