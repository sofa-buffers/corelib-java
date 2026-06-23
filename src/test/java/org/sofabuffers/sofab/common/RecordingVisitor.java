/*
 * SofaBuffers Java - shared recording visitor for tests.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.common;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.sofabuffers.sofab.ArrayKind;
import org.sofabuffers.sofab.Visitor;

/**
 * A {@link Visitor} that records every callback as a flat list of human-readable
 * events, and reassembles chunked string/blob payloads. Tests assert against the
 * event list, which keeps them independent of how the decoder chunks input.
 */
public final class RecordingVisitor implements Visitor {

    /** Recorded events, one per decoded field (string/blob coalesced). */
    public final List<String> events = new ArrayList<>();

    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private String pendingKind;
    private int pendingId;
    private int pendingTotal;

    @Override
    public void unsigned(int id, long value) {
        events.add("u:" + id + "=" + Long.toUnsignedString(value));
    }

    @Override
    public void signed(int id, long value) {
        events.add("s:" + id + "=" + value);
    }

    @Override
    public void fp32(int id, float value) {
        events.add("f32:" + id + "=" + value);
    }

    @Override
    public void fp64(int id, double value) {
        events.add("f64:" + id + "=" + value);
    }

    @Override
    public void string(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        accumulate("str", id, total, data, chunkOffset, chunkLength);
    }

    @Override
    public void blob(int id, int total, int offset, byte[] data, int chunkOffset, int chunkLength) {
        accumulate("blob", id, total, data, chunkOffset, chunkLength);
    }

    private void accumulate(String kind, int id, int total, byte[] data, int chunkOffset, int chunkLength) {
        if (pendingKind == null) {
            pendingKind = kind;
            pendingId = id;
            pendingTotal = total;
            pending.reset();
        }
        pending.write(data, chunkOffset, chunkLength);
        if (pending.size() >= pendingTotal) {
            byte[] full = pending.toByteArray();
            if (pendingKind.equals("str")) {
                events.add("str:" + pendingId + "=" + new String(full, StandardCharsets.UTF_8));
            } else {
                events.add("blob:" + pendingId + "=" + hex(full));
            }
            pendingKind = null;
        }
    }

    @Override
    public void arrayBegin(int id, ArrayKind kind, int count) {
        events.add("arr:" + id + ":" + kind + ":" + count);
    }

    @Override
    public void sequenceBegin(int id) {
        events.add("seq{:" + id);
    }

    @Override
    public void sequenceEnd() {
        events.add("seq}");
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xFF));
        }
        return sb.toString();
    }
}
