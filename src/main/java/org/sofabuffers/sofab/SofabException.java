/*
 * SofaBuffers Java - exception type.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.IOException;

/**
 * Thrown by the encoder and decoder on protocol or buffer errors.
 *
 * <p>It extends {@link IOException} so it composes naturally with Java I/O: a
 * {@link FlushSink} that writes to a socket or file may itself throw
 * {@code IOException}, and generated marshal/unmarshal code can simply declare
 * {@code throws IOException}. The specific {@link SofabError} is available via
 * {@link #error()}.
 */
public final class SofabException extends IOException {

    private static final long serialVersionUID = 1L;

    /** The error category that caused this exception. */
    private final SofabError error;

    /**
     * Create an exception for the given error category.
     *
     * @param error the error category
     */
    public SofabException(SofabError error) {
        super("sofab: " + error);
        this.error = error;
    }

    /**
     * Create an exception for the given error category with extra detail.
     *
     * @param error  the error category
     * @param detail human-readable context appended to the message
     */
    public SofabException(SofabError error, String detail) {
        super("sofab: " + error + " (" + detail + ")");
        this.error = error;
    }

    /**
     * The error category that caused this exception.
     *
     * @return the {@link SofabError}
     */
    public SofabError error() {
        return error;
    }
}
