/*
 * SofaBuffers Java - shared decode harnesses for tests.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.common;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.UncheckedIOException;
import java.util.List;

import org.sofabuffers.sofab.DecodeStatus;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.SofabError;
import org.sofabuffers.sofab.SofabException;
import org.sofabuffers.sofab.Visitor;

/**
 * The decoder has two surfaces — a contiguous fast path that advances a pointer
 * over a whole buffer, and a resumable state machine that takes over wherever a
 * construct straddles a feed boundary — and a rule enforced on one but not the
 * other is this decoder's recurring defect. Every decode test therefore runs its
 * vectors through both, which is what these harnesses are: {@link #errorOf} for
 * one feed, {@link #errorOfChunked} for byte-at-a-time, and {@link #verdict} for
 * the three-valued accept / incomplete / rejected reduction over {@link #CHUNKS}.
 */
public final class Decode {

    /**
     * Feed sizes that together cover both surfaces: {@code 0} means one whole
     * feed (the fast path), and the small splits drive the state machine.
     */
    public static final List<Integer> CHUNKS = List.of(0, 1, 3);

    private Decode() {
    }

    /** Feed {@code data} in one call — the contiguous fast path — and return its error. */
    public static SofabError errorOf(byte[] data) {
        SofabException ex = assertThrows(SofabException.class,
                () -> new IStream().feed(data, new Visitor() { }));
        return ex.error();
    }

    /** Feed {@code data} one byte at a time — the resumable state machine — and return its error. */
    public static SofabError errorOfChunked(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            IStream in = new IStream();
            Visitor v = new Visitor() { };
            for (byte b : data) {
                in.feed(new byte[] { b }, v);
            }
        });
        return ex.error();
    }

    /**
     * Feed {@code data} to a fresh decoder in {@code chunk}-byte slices (0 = one
     * whole feed, which takes the pointer-advancing fast path) and reduce the
     * outcome to Crucible's three-valued verdict: {@code "A"} accept,
     * {@code "I"} incomplete, {@code "R:<error>"} rejected.
     */
    public static String verdict(byte[] data, Visitor sink, int chunk) {
        IStream in = new IStream();
        try {
            if (chunk <= 0) {
                in.feed(data, sink);
            } else {
                for (int i = 0; i < data.length; i += chunk) {
                    in.feed(data, i, Math.min(chunk, data.length - i), sink);
                }
            }
        } catch (SofabException e) {
            return "R:" + e.error();
        } catch (UncheckedIOException e) {
            // How sofabgen's Java backend aborts from a visitor callback, whose
            // signature declares no checked exception.
            return "R:" + ((SofabException) e.getCause()).error();
        }
        return in.status() == DecodeStatus.COMPLETE ? "A" : "I";
    }
}
