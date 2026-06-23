/*
 * SofaBuffers Java - output flush sink.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.IOException;

/**
 * Sink that receives buffered bytes when an {@link OStream}'s buffer fills (or
 * when {@link OStream#flush()} is called).
 *
 * <p>This is a functional interface, so a sink can be written as a lambda or a
 * method reference — for example {@code out::write} for an
 * {@link java.io.OutputStream}. Implementing it lets a message larger than the
 * output buffer (or larger than RAM) be streamed out incrementally: the encoder
 * hands the sink each full buffer and then resumes at the start of the buffer.
 */
@FunctionalInterface
public interface FlushSink {

    /**
     * Consume {@code length} bytes starting at {@code offset} in {@code data}.
     *
     * <p>The array is owned by the encoder and is reused after this call
     * returns; a sink that needs to retain the bytes must copy them.
     *
     * @param data   the encoder's active buffer
     * @param offset start of the pending bytes
     * @param length number of pending bytes
     * @throws IOException if the underlying transport or storage fails
     */
    void flush(byte[] data, int offset, int length) throws IOException;
}
