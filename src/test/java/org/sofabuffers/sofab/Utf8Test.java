/*
 * SofaBuffers Java - decode-side UTF-8 validation of a raw byte range.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8Test {

    private static boolean valid(int... bytes) {
        byte[] b = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            b[i] = (byte) bytes[i];
        }
        return Utf8.valid(b, 0, b.length);
    }

    @Test
    void acceptsWellFormedInput() {
        assertTrue(valid());                                  // empty
        assertTrue(valid(0x00));                              // NUL is ordinary ASCII
        assertTrue(valid(0x7F));
        assertTrue(valid(0xC2, 0x80));                        // U+0080, shortest 2-byte
        assertTrue(valid(0xDF, 0xBF));                        // U+07FF
        assertTrue(valid(0xE0, 0xA0, 0x80));                  // U+0800, shortest 3-byte
        assertTrue(valid(0xEF, 0xBF, 0xBF));                  // U+FFFF
        assertTrue(valid(0xF0, 0x90, 0x80, 0x80));            // U+10000, shortest 4-byte
        assertTrue(valid(0xF4, 0x8F, 0xBF, 0xBF));            // U+10FFFF, the last code point
        byte[] mixed = "aä€𝄞z".getBytes(StandardCharsets.UTF_8);
        assertTrue(Utf8.valid(mixed, 0, mixed.length));
    }

    @Test
    void rejectsOverlongForms() {
        assertFalse(valid(0xC0, 0x80));       // the "Modified UTF-8" NUL
        assertFalse(valid(0xC1, 0xBF));       // 2-byte form of U+007F
        assertFalse(valid(0xE0, 0x80, 0x80)); // 3-byte form of U+0000
        assertFalse(valid(0xE0, 0x9F, 0xBF)); // 3-byte form of U+07FF
        assertFalse(valid(0xF0, 0x80, 0x80, 0x80));
        assertFalse(valid(0xF0, 0x8F, 0xBF, 0xBF)); // 4-byte form of U+FFFF
    }

    @Test
    void rejectsSurrogates() {
        assertFalse(valid(0xED, 0xA0, 0x80)); // U+D800
        assertFalse(valid(0xED, 0xBF, 0xBF)); // U+DFFF
        assertTrue(valid(0xED, 0x9F, 0xBF));  // U+D7FF, just below
        assertTrue(valid(0xEE, 0x80, 0x80));  // U+E000, just above
    }

    @Test
    void rejectsOutOfRange() {
        assertFalse(valid(0xF4, 0x90, 0x80, 0x80)); // U+110000
        assertFalse(valid(0xF5, 0x80, 0x80, 0x80));
        assertFalse(valid(0xFF));
        assertFalse(valid(0xFE));
    }

    @Test
    void rejectsTruncatedAndStrayContinuations() {
        assertFalse(valid(0x80));             // bare continuation
        assertFalse(valid(0xBF));
        assertFalse(valid(0xC2));             // lead with no continuation
        assertFalse(valid(0xE0, 0xA0));       // one byte short
        assertFalse(valid(0xF0, 0x90, 0x80)); // one byte short
        assertFalse(valid(0xC2, 0x41));       // continuation out of range
    }

    @Test
    void validatesOnlyTheGivenRange() {
        // The bytes outside [i, end) must not influence the verdict — the decode
        // path validates one field's slice of a larger buffer.
        byte[] b = {(byte) 0xFF, 'o', 'k', (byte) 0xFF};
        assertTrue(Utf8.valid(b, 1, 3));
        assertFalse(Utf8.valid(b, 0, 4));
    }

    @Test
    void aMultiByteSequenceStraddlingTheEndIsInvalid() {
        // Not "incomplete, ask again": this API is handed a complete field, so a
        // sequence running past `end` is malformed input.
        byte[] b = {(byte) 0xE0, (byte) 0xA0, (byte) 0x80};
        assertTrue(Utf8.valid(b, 0, 3));
        assertFalse(Utf8.valid(b, 0, 2));
    }
}
