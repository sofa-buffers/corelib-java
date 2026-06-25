/*
 * SofaBuffers Java - skip tests (architecture guide §7.2 #6).
 *
 * A decoder must be able to ignore individual fields and whole sub-sequences and
 * still resync on the following field. In the visitor model "skip" just means the
 * visitor does not act on a field; the state machine always walks every byte, so
 * the field after a skipped region is delivered correctly.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SkipTest {

    /** Encode: a scalar, a nested (and doubly-nested) sequence, then a trailing scalar. */
    private static byte[] message() throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        os.writeUnsigned(1, 11);
        os.writeSequenceBegin(2);
        os.writeUnsigned(1, 22);
        os.writeArraySigned(3, new int[] {-1, -2, -3});
        os.writeSequenceBegin(4);
        os.writeFp32(1, 1.5f);
        os.writeString(2, "deep");
        os.writeSequenceEnd();
        os.writeSequenceEnd();
        os.writeUnsigned(7, 77); // the field that must resync after the skipped sequence
        return java.util.Arrays.copyOf(buf, os.bytesUsed());
    }

    @Test
    void skipWholeSubSequenceAndResync() throws IOException {
        byte[] msg = message();

        // A visitor that ignores the nested sequence entirely (depth tracking) and
        // records only the top-level unsigned fields.
        List<String> seen = new ArrayList<>();
        new IStream().feed(msg, new Visitor() {
            int depth;
            @Override public void sequenceBegin(int id) { depth++; }
            @Override public void sequenceEnd() { depth--; }
            @Override public void unsigned(int id, long value) {
                if (depth == 0) {
                    seen.add(id + "=" + value);
                }
            }
        });

        // Only the two top-level unsigned fields survive; id 7 proves the decoder
        // resynced correctly after walking the whole nested sequence.
        assertEquals(List.of("1=11", "7=77"), seen);
    }

    @Test
    void skipSelectedFieldsKeepsOthers() throws IOException {
        // Flat message; ignore everything except id 5.
        byte[] buf = new byte[128];
        OStream os = new OStream(buf);
        os.writeUnsigned(1, 1);
        os.writeSigned(2, -2);
        os.writeString(3, "ignored");
        os.writeArrayUnsigned(4, new int[] {9, 9, 9});
        os.writeUnsigned(5, 555);
        os.writeBlob(6, new byte[] {1, 2, 3});

        long[] got = {-1};
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void unsigned(int id, long value) {
                if (id == 5) {
                    got[0] = value;
                }
            }
        });
        assertEquals(555L, got[0]);
    }
}
