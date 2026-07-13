/*
 * SofaBuffers Java - the LIMIT_EXCEEDED error category is distinct from
 * INVALID_MSG (issue #34: receiver-configured decode limits are policy, not wire
 * malformation).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The corelib does not enforce decode limits (that lives in generated code), so
 * these tests exercise the error category itself: that generated code can raise a
 * limit violation uniformly and that it stays strictly distinguishable from the
 * wire-malformation category {@link SofabError#INVALID_MSG}.
 */
class LimitExceededErrorTest {

    /**
     * Stand-in for a generated decode visitor's guard: the corelib exposes the
     * wire count / total length before any allocation, so generated code checks it
     * against a receiver-configured cap and raises {@link SofabError#LIMIT_EXCEEDED}
     * <em>before</em> allocating. Never clamps, never truncates — a hard error.
     */
    private static void guardDynArrayCount(int count, int maxDynArrayCount) throws SofabException {
        if (count > maxDynArrayCount) {
            throw new SofabException(
                    SofabError.LIMIT_EXCEEDED,
                    "dyn array count " + count + " > max " + maxDynArrayCount);
        }
    }

    @Test
    void limitExceededCarriesItsOwnCategory() {
        SofabException ex = new SofabException(SofabError.LIMIT_EXCEEDED);
        assertEquals(SofabError.LIMIT_EXCEEDED, ex.error());
    }

    @Test
    void limitExceededIsDistinctFromInvalidMsg() {
        // Exceeding a receiver-configured limit is policy, not wire malformation:
        // the two categories must never compare equal, otherwise a differential
        // fuzzer would read a limit-policy divergence as a wire-conformance one.
        assertNotEquals(SofabError.INVALID_MSG, SofabError.LIMIT_EXCEEDED);
    }

    @Test
    void limitViolationRaisesBeforeAllocating() {
        // cap 4, wire announces 5 -> guard fires on the count, before any element
        // buffer is allocated.
        SofabException ex = assertThrows(
                SofabException.class, () -> guardDynArrayCount(5, 4));
        assertEquals(SofabError.LIMIT_EXCEEDED, ex.error());
        assertTrue(ex.getMessage().contains("5"), "detail should name the offending count");
    }

    @Test
    void withinLimitDoesNotRaise() throws SofabException {
        // At or below the cap is accepted (no limit configured is modelled as a
        // very high cap): a limit is a ceiling, not a trigger.
        guardDynArrayCount(4, 4);
        guardDynArrayCount(0, Integer.MAX_VALUE);
    }

    @Test
    void notCaughtByHandlerThatOnlyExpectsInvalidMsg() {
        // A handler that only recognises wire malformation (branches on
        // INVALID_MSG) must not swallow a limit violation: the LIMIT_EXCEEDED
        // escapes it untouched, exactly as a backend with a different configured
        // limit would need it to.
        boolean handledAsInvalidMessage;
        try {
            guardDynArrayCount(9, 1);
            handledAsInvalidMessage = false; // unreachable: the guard throws
        } catch (SofabException ex) {
            if (ex.error() == SofabError.INVALID_MSG) {
                handledAsInvalidMessage = true; // an INVALID_MSG-only handler would land here
            } else {
                // Re-raise anything that is not wire malformation, unchanged.
                assertEquals(SofabError.LIMIT_EXCEEDED, ex.error());
                handledAsInvalidMessage = false;
            }
        }
        assertFalse(handledAsInvalidMessage, "LIMIT_EXCEEDED must not be treated as INVALID_MSG");
    }

    @Test
    void composesWithJavaIo() {
        // SofabException extends IOException, so generated unmarshal code that
        // declares `throws IOException` propagates a limit violation naturally.
        SofabException ex = new SofabException(SofabError.LIMIT_EXCEEDED, "dyn string len 1_000_000 > max 65_536");
        assertInstanceOf(IOException.class, ex);
        assertTrue(ex.getMessage().contains("LIMIT_EXCEEDED"));
    }
}
