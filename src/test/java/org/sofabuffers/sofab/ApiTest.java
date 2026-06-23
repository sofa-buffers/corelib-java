/*
 * SofaBuffers Java - API behaviour: offset reserve, flush-sink streaming, and
 * decoding fed in arbitrarily small chunks.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ApiTest {

    @Test
    void offsetReservesHeaderRoom() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf, 4); // reserve 4 bytes up front
        os.writeUnsigned(7, 99);
        // The reserved prefix is left for the caller to fill in later.
        assertEquals(4 + 2, os.bytesUsed()); // header(1) + value(1) for id 7, value 99
        // Decoding only the payload (skipping the reserved prefix) yields the field.
        List<Long> got = new ArrayList<>();
        new IStream().feed(buf, 4, os.bytesUsed() - 4, new Visitor() {
            @Override public void unsigned(int id, long v) {
                assertEquals(7, id);
                got.add(v);
            }
        });
        assertEquals(List.of(99L), got);
    }

    /**
     * Stream a message far larger than the output buffer: an 8-byte buffer with
     * a flush sink that collects the bytes must produce exactly the same wire
     * image as encoding into one large buffer.
     */
    @Test
    void streamLargerThanBuffer() throws IOException {
        final int n = 1000;

        // Reference: encode into a single big buffer.
        byte[] big = new byte[n * 11 + 16];
        OStream ref = new OStream(big);
        for (int i = 0; i < n; i++) {
            ref.writeUnsigned(i % (Integer.MAX_VALUE), (long) i * 0x9E37_79B9L);
        }
        byte[] reference = java.util.Arrays.copyOf(big, ref.bytesUsed());

        // Streamed: tiny 8-byte buffer + collecting sink.
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        FlushSink sink = (data, off, len) -> collected.write(data, off, len);
        OStream os = new OStream(new byte[8], 0, sink);
        for (int i = 0; i < n; i++) {
            os.writeUnsigned(i % (Integer.MAX_VALUE), (long) i * 0x9E37_79B9L);
        }
        os.flush(); // push the tail

        assertArrayEquals(reference, collected.toByteArray());
    }

    /**
     * The same streamed bytes must decode back to the original values, proving
     * the chunked-output path is wire-identical end to end.
     */
    @Test
    void streamedMessageDecodes() throws IOException {
        final int n = 500;
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        FlushSink sink = (data, off, len) -> collected.write(data, off, len);
        OStream os = new OStream(new byte[8], 0, sink);
        for (int i = 0; i < n; i++) {
            os.writeUnsigned(1, i);
        }
        os.flush();

        List<Long> values = new ArrayList<>();
        new IStream().feed(collected.toByteArray(), new Visitor() {
            @Override public void unsigned(int id, long v) {
                values.add(v);
            }
        });
        assertEquals(n, values.size());
        for (int i = 0; i < n; i++) {
            assertEquals((long) i, values.get(i));
        }
    }

    /** A large blob fed to the decoder in 3-byte chunks reassembles correctly. */
    @Test
    void largeBlobChunkedDecode() throws IOException {
        byte[] blob = new byte[1000];
        for (int i = 0; i < blob.length; i++) {
            blob[i] = (byte) (i * 31 + 7);
        }
        byte[] buf = new byte[2048];
        OStream os = new OStream(buf);
        os.writeBlob(42, blob);
        int used = os.bytesUsed();

        byte[] reassembled = new byte[blob.length];
        int[] seen = {0};
        Visitor v = new Visitor() {
            @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
                assertEquals(42, id);
                assertEquals(blob.length, total);
                System.arraycopy(d, o, reassembled, offset, l);
                seen[0] += l;
            }
        };
        IStream is = new IStream();
        for (int i = 0; i < used; i += 3) {
            is.feed(buf, i, Math.min(3, used - i), v);
        }
        assertEquals(blob.length, seen[0]);
        assertArrayEquals(blob, reassembled);
    }
}
