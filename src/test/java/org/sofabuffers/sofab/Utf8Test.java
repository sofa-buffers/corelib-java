/*
 * SofaBuffers Java - decode-side UTF-8 validation of a raw byte range.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
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
    void rejectsABadContinuationAfterTheSecondByte() {
        // The second byte carries the lead-specific range that rules out the
        // overlongs and the surrogates; every byte after it must be a plain
        // continuation 80..BF. A validator that checked only the second one would
        // accept all of these — and each of them is a *different* three-byte or
        // four-byte code point than the bytes claim to be.
        assertFalse(valid(0xE0, 0xA0, 0x41));       // third byte is ASCII
        assertFalse(valid(0xE0, 0xA0, 0xC2));       // third byte is a lead byte
        assertFalse(valid(0xEF, 0xBF, 0x7F));
        assertFalse(valid(0xF0, 0x90, 0x41, 0x80)); // third byte of a 4-byte form
        assertFalse(valid(0xF0, 0x90, 0xF0, 0x80));
        assertFalse(valid(0xF0, 0x90, 0x80, 0x41)); // fourth byte below 80
        assertFalse(valid(0xF0, 0x90, 0x80, 0xC2)); // fourth byte above BF
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

    // --- decode: validate, then materialize ----------------------------------

    /** The ordinary case, over a slice of a larger buffer. */
    @Test
    void decodeMaterializesTheGivenRangeOnly() {
        byte[] b = "xxhello worldxx".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello world", Utf8.decode(b, 2, 11));
        assertEquals("", Utf8.decode(b, 2, 0));
        byte[] mixed = "aä€𝄞z".getBytes(StandardCharsets.UTF_8);
        assertEquals("aä€𝄞z", Utf8.decode(mixed, 0, mixed.length));
    }

    /**
     * The whole point of validating first: {@code new String(b, UTF_8)} would
     * return "\uFFFD" here and no later check could tell. MESSAGE_SPEC §7 wants
     * the message rejected, not the string repaired.
     */
    @Test
    void decodeRejectsRatherThanSubstitutingTheReplacementCharacter() {
        byte[] lone = {(byte) 0xED, (byte) 0xA0, (byte) 0x80};
        assertTrue(new String(lone, StandardCharsets.UTF_8).contains("\uFFFD"),
                "the JDK conversion repairs it, which is why it cannot be the check");

        UncheckedIOException e =
                assertThrows(UncheckedIOException.class, () -> Utf8.decode(lone, 0, 3));
        SofabException cause = assertInstanceOf(SofabException.class, e.getCause());
        assertEquals(SofabError.INVALID_MSG, cause.error());
    }

    /** The verdict is the validator's, on exactly the range decode was given. */
    @Test
    void decodeRejectsWhateverValidRejects() {
        byte[][] bad = {
            {(byte) 0xC0, (byte) 0x80},                            // overlong NUL
            {(byte) 0xF5, (byte) 0x80, (byte) 0x80, (byte) 0x80},  // above U+10FFFF
            {(byte) 0x80},                                         // bare continuation
            {(byte) 0xE0, (byte) 0xA0},                            // truncated sequence
        };
        for (byte[] b : bad) {
            assertFalse(Utf8.valid(b, 0, b.length));
            assertThrows(UncheckedIOException.class, () -> Utf8.decode(b, 0, b.length));
        }
    }

    /**
     * A sequence that is only complete <em>outside</em> the range is still
     * malformed: decode is handed a whole field, not a stream position.
     */
    @Test
    void decodeJudgesTheRangeItWasGivenNotTheBuffer() {
        byte[] b = {(byte) 0xE0, (byte) 0xA0, (byte) 0x80};
        assertEquals("\u0800", Utf8.decode(b, 0, 3));
        assertThrows(UncheckedIOException.class, () -> Utf8.decode(b, 0, 2));
    }
}
