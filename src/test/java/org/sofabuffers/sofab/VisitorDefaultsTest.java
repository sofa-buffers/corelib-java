/*
 * SofaBuffers Java - a Visitor that overrides nothing must silently drop every
 * field kind (the "not interested" / skip behaviour), exercising every default
 * no-op method.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class VisitorDefaultsTest {

    @Test
    void defaultVisitorIgnoresEveryFieldKind() throws IOException {
        // Encode a message touching every field kind the decoder can emit.
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        os.writeUnsigned(1, 42);
        os.writeSigned(2, -42);
        os.writeBoolean(3, true);
        os.writeFp32(4, 1.5f);
        os.writeFp64(5, 2.5);
        os.writeString(6, "hi");
        os.writeBlob(7, new byte[] {1, 2, 3});
        os.writeArrayUnsigned(8, new int[] {1, 2});
        os.writeArraySigned(9, new int[] {-1, -2});
        os.writeArrayFp64(10, new double[] {1.0, 2.0});
        os.writeSequenceBegin(11);
        os.writeUnsigned(1, 7);
        os.writeSequenceEnd();
        int used = os.bytesUsed();

        // A bare Visitor overrides nothing: all default no-op methods run.
        Visitor ignoreAll = new Visitor() { };
        assertDoesNotThrow(() -> new IStream().feed(buf, 0, used, ignoreAll));
    }
}
