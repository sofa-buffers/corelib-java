/*
 * SofaBuffers Java - library constant checks (architecture guide §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Wire.bytes;

import java.io.UncheckedIOException;

import org.junit.jupiter.api.Test;

class SofabTest {

    @Test
    void apiVersionIsOne() {
        assertEquals(1, Sofab.API_VERSION);
    }

    @Test
    void normativeLimits() {
        assertEquals(2147483647, Sofab.ID_MAX);
        assertEquals(2147483647L, Sofab.ARRAY_MAX);
    }

    // --- the INVALID carrier -------------------------------------------------

    /**
     * {@link Sofab#invalid} builds the exception rather than throwing it, so
     * {@code throw Sofab.invalid(...)} ends the method for the compiler too — a
     * guard that only called it would fall through into the code it was meant to
     * prevent.
     */
    @Test
    void invalidWrapsAnInvalidMsgExceptionWithItsDetail() {
        UncheckedIOException e = Sofab.invalid("name: string length above schema maxlen 8");
        SofabException cause = assertInstanceOf(SofabException.class, e.getCause());
        assertEquals(SofabError.INVALID_MSG, cause.error());
        assertTrue(cause.getMessage().contains("name: string length above schema maxlen 8"),
                "the detail must reach the message: " + cause.getMessage());
    }

    /**
     * The other side of the contract: a {@link Visitor} declares no checked
     * exception, so a schema rejection travels out of a callback wrapped — and
     * {@link IStream#feed} recognizes it there, giving it exactly the outcome the
     * decoder's own rejections have, latched as terminal (CORELIB_PLAN §5.2).
     */
    @Test
    void feedUnwrapsItIntoATerminalRejection() {
        // id 0, unsigned 42 - well formed; the visitor is what rejects it.
        byte[] message = bytes(0x00, 0x2A);
        IStream in = new IStream();
        Visitor v = new Visitor() {
            @Override
            public void unsigned(int id, long value) {
                throw Sofab.invalid("count: value outside declared width u8");
            }
        };

        UncheckedIOException e =
                assertThrows(UncheckedIOException.class, () -> in.feed(message, v));
        SofabException cause = assertInstanceOf(SofabException.class, e.getCause());
        assertEquals(SofabError.INVALID_MSG, cause.error());
        // Latched: the decoder decodes nothing further and repeats the verdict,
        // which is the only place there is to read it.
        SofabException again = assertThrows(SofabException.class,
                () -> in.feed(bytes(0x00, 0x2A), v));
        assertEquals(SofabError.INVALID_MSG, again.error());

        SofabException next = assertThrows(SofabException.class, () -> in.feed(message, v),
                "a rejection is terminal: the next feed rejects without decoding");
        assertEquals(SofabError.INVALID_MSG, next.error());
    }
}
