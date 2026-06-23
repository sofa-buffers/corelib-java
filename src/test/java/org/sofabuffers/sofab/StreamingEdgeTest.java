/*
 * SofaBuffers Java - decoder edge cases: empty string/blob fields and an array
 * whose element count needs a multi-byte varint (split across the state machine).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class StreamingEdgeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    void emptyStringEmitsOnce() throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(bytes(0x02, 0x02), v); // fixlen id 0, STRING length 0
        assertEquals(java.util.List.of("str:0="), v.events);
    }

    @Test
    void emptyBlobEmitsOnce() throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(bytes(0x02, 0x03), v); // fixlen id 0, BLOB length 0
        assertEquals(java.util.List.of("blob:0="), v.events);
    }

    @Test
    void arrayWithMultiByteCount() throws IOException {
        // 200 elements -> the count varint is two bytes (0xC8 0x01), exercising the
        // "need more bytes" path of the array-count state.
        int n = 200;
        long[] src = new long[n];
        for (int i = 0; i < n; i++) {
            src[i] = i;
        }
        byte[] buf = new byte[n * 2 + 16];
        OStream os = new OStream(buf);
        os.writeArrayUnsigned(1, src);

        int[] begin = {-1};
        long[] count = {0};
        long[] sum = {0};
        new IStream().feed(buf, 0, os.bytesUsed(), new Visitor() {
            @Override public void arrayBegin(int id, ArrayKind kind, int c) {
                begin[0] = c;
            }
            @Override public void unsigned(int id, long value) {
                count[0]++;
                sum[0] += value;
            }
        });
        assertEquals(n, begin[0]);
        assertEquals(n, count[0]);
        assertEquals((long) n * (n - 1) / 2, sum[0]); // 0+1+...+199
    }
}
