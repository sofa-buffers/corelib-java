/*
 * SofaBuffers Java - library constant checks (architecture guide §6.2).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
