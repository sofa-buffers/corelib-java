/*
 * SofaBuffers Java - writeString(id, text, maxlen): the caller's byte bound.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The bounded string write refuses a value past the caller's {@code maxlen} --
 * counted in UTF-8 bytes, not chars -- with {@link SofabError#ARGUMENT} and
 * before a single byte of the field is written; a value at or below the bound
 * encodes to exactly the bytes the unbounded write produces.
 */
class StringMaxlenTest {

    private static byte[] unbounded(int id, String text) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        os.writeString(id, text);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    private static byte[] bounded(int id, String text, int maxlen) throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        os.writeString(id, text, maxlen);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    private static void assertRefused(String text, int maxlen, String detail) {
        OStream os = new OStream(new byte[256]);
        SofabException e = assertThrows(SofabException.class, () -> os.writeString(3, text, maxlen));
        assertEquals(SofabError.ARGUMENT, e.error());
        assertTrue(e.getMessage().contains(detail), e.getMessage());
        assertEquals(0, os.bytesUsed(), "a refused string writes no byte");
    }

    @Test
    void atTheBoundIsTheUnboundedEncoding() throws IOException {
        // ASCII below and above the bulk-copy threshold, every UTF-8 width, empty.
        for (String s : new String[] {"", "abcd", "x".repeat(40), "éé", "€", "😀", "aä€😀z"}) {
            int n = s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            assertArrayEquals(unbounded(5, s), bounded(5, s, n), s);
            assertArrayEquals(unbounded(5, s), bounded(5, s, n + 7), s);
        }
    }

    @Test
    void asciiOverTheBoundIsRefusedBeforeMeasuring() {
        // More chars than bytes allowed: refused on the char count alone.
        assertRefused("xxxxx", 4, "at least 5 UTF-8 bytes is above maxlen 4");
        assertRefused("x".repeat(60), 4, "at least 60 UTF-8 bytes is above maxlen 4");
        assertRefused("a", 0, "above maxlen 0");
    }

    @Test
    void multiByteOverTheBoundIsRefusedOnTheByteLength() {
        // Fewer chars than the bound, more bytes: only the measuring pass sees it.
        assertRefused("ééé", 4, "at least 6 UTF-8 bytes is above maxlen 4");
        assertRefused("xxxé", 4, "at least 5 UTF-8 bytes is above maxlen 4");
        assertRefused("😀", 3, "at least 4 UTF-8 bytes is above maxlen 3");
        assertRefused("é".repeat(40), 79, "at least 80 UTF-8 bytes is above maxlen 79");
    }

    @Test
    void anUnpairedSurrogateInsideTheBoundIsStillInvalidUtf8() {
        assertRefused("a\uD800", 8, "unpaired surrogate");
    }

    @Test
    void aRefusalLeavesTheStreamUsable() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeSequenceBeginLazy(9);
        assertThrows(SofabException.class, () -> os.writeString(0, "toolong", 3));
        // The lazily held sequence header was not committed by the refused field.
        assertEquals(0, os.bytesUsed());
        os.writeString(1, "ok", 3);
        os.writeSequenceEnd();
        byte[] refBuf = new byte[64];
        OStream ref = new OStream(refBuf);
        ref.writeSequenceBeginLazy(9);
        ref.writeString(1, "ok");
        ref.writeSequenceEnd();
        assertArrayEquals(Arrays.copyOf(refBuf, ref.bytesUsed()),
                Arrays.copyOf(buf, os.bytesUsed()));
    }

    @Test
    void aRefusalReachesNoSink() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OStream os = new OStream(new byte[4], 0, out::write);
        assertThrows(SofabException.class, () -> os.writeString(0, "é".repeat(40), 79));
        os.flush();
        assertEquals(0, out.size());
    }
}
