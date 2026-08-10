/*
 * SofaBuffers Java - a taking flush sink must be able to re-arm its start offset.
 *
 * CORELIB_PLAN 5.1, "What a returning flush callback leaves behind": a sink that
 * TAKES the buffer it was handed must install a replacement before returning, and
 * "the start offset belongs to the installation, not to the buffer" - each
 * buffer-set call begins a new installation whose cursor starts at THAT call's
 * offset. Only a callback that returns WITHOUT installing anything (it copied)
 * resumes at 0. Re-installing the same buffer is a new installation like any
 * other; that is how a sink reserves framing-header room in every flushed unit.
 *
 * corelib-java#70: the encoder reset the cursor to 0 unconditionally after the
 * callback returned, so the offset a bufferSet() inside the sink installed was
 * thrown away and only the very first unit carried its reservation.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TakingFlushSinkTest {

    /** Reserved bytes a taking sink re-arms in front of every unit it hands on. */
    private static final int HEADER = 3;

    /** Filler for the reserved room, so an overwritten reservation is visible. */
    private static final byte MARK = (byte) 0x5A;

    /** The message every case below encodes: bigger than any window used here. */
    private static void message(OStream os) throws IOException {
        os.writeUnsigned(1, 0xFFFFFFFFFFFFFFFFL);
        os.writeString(2, "payload that is longer than any tiny window");
        os.writeSigned(3, -123456789L);
    }

    private static byte[] freshBuffer(int size) {
        byte[] b = new byte[size];
        Arrays.fill(b, MARK);
        return b;
    }

    /**
     * Encode {@link #message} in one pass into a buffer large enough to hold it,
     * which is what every streamed variant below must reproduce byte for byte.
     */
    private static byte[] oneShot() throws IOException {
        byte[] buf = new byte[256];
        OStream os = new OStream(buf);
        message(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    /**
     * A sink that takes each buffer it is handed - it keeps the array instead of
     * copying out of it - and therefore installs a replacement before returning,
     * re-arming {@link #HEADER} bytes of framing room in every unit.
     */
    @Test
    void everyTakenUnitKeepsTheInstalledReservation() throws IOException {
        List<byte[]> units = new ArrayList<>();
        OStream[] self = new OStream[1];
        FlushSink taking = (data, off, len) -> {
            units.add(Arrays.copyOfRange(data, off, off + len)); // stands for "hand to transport"
            self[0].bufferSet(freshBuffer(16), HEADER);          // take: install a replacement
        };

        OStream os = new OStream(freshBuffer(16), HEADER, taking);
        self[0] = os;
        message(os);
        os.flush();

        assertTrue(units.size() > 2, "the window is too large to prove anything: " + units.size());

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < units.size(); i++) {
            byte[] unit = units.get(i);
            assertTrue(unit.length > HEADER, "unit " + i + " carries no payload");
            assertArrayEquals(new byte[] {MARK, MARK, MARK}, Arrays.copyOf(unit, HEADER),
                    "unit " + i + " lost the reservation its bufferSet(buf, " + HEADER
                            + ") installed - the flush overwrote the installed offset");
            payload.write(unit, HEADER, unit.length - HEADER);
        }
        assertArrayEquals(oneShot(), payload.toByteArray(),
                "the streamed message bytes must equal the one-shot encoding");
    }

    /**
     * Re-installing the <em>same</em> buffer is a new installation like any other -
     * the spec's stated way to get fresh header room in every unit. The reserved
     * bytes must therefore survive the whole encode: nothing is ever written below
     * the installed offset.
     */
    @Test
    void reinstallingTheSameBufferRearmsTheReservation() throws IOException {
        byte[] window = freshBuffer(16);
        List<byte[]> units = new ArrayList<>();
        OStream[] self = new OStream[1];
        FlushSink drainAndRearm = (data, off, len) -> {
            units.add(Arrays.copyOfRange(data, off, off + len));
            self[0].bufferSet(window, HEADER); // same array, new installation
        };

        OStream os = new OStream(window, HEADER, drainAndRearm);
        self[0] = os;
        message(os);
        os.flush();

        assertTrue(units.size() > 2, "the window is too large to prove anything: " + units.size());
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < units.size(); i++) {
            byte[] unit = units.get(i);
            assertArrayEquals(new byte[] {MARK, MARK, MARK}, Arrays.copyOf(unit, HEADER),
                    "unit " + i + " was written over the room the re-installation reserved");
            payload.write(unit, HEADER, unit.length - HEADER);
        }
        assertArrayEquals(oneShot(), payload.toByteArray());
    }

    /** A copying sink is unchanged: returning without installing resumes at 0. */
    @Test
    void copyingSinkStillResumesAtZero() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] window = freshBuffer(16);
        OStream os = new OStream(window, 0, out::write);
        message(os);
        os.flush();

        assertArrayEquals(oneShot(), out.toByteArray());
    }

    /**
     * A sink that installs only on some flushes: the installation is consumed, so
     * the next flush the callback returns from bare resumes at 0.
     */
    @Test
    void anInstallationIsConsumedByTheFlushThatFollowsIt() throws IOException {
        List<byte[]> units = new ArrayList<>();
        OStream[] self = new OStream[1];
        int[] calls = new int[1];
        FlushSink alternating = (data, off, len) -> {
            units.add(Arrays.copyOfRange(data, off, off + len));
            if (calls[0]++ == 0) {
                self[0].bufferSet(freshBuffer(16), HEADER); // only the first flush installs
            }
        };

        OStream os = new OStream(freshBuffer(16), 0, alternating);
        self[0] = os;
        message(os);
        os.flush();

        assertTrue(units.size() > 2, "the window is too large to prove anything: " + units.size());
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(units.get(0), 0, units.get(0).length);
        // Unit 1 came from the installation and carries its reservation; every unit
        // after it came from a bare return and starts at 0.
        byte[] second = units.get(1);
        assertArrayEquals(new byte[] {MARK, MARK, MARK}, Arrays.copyOf(second, HEADER));
        payload.write(second, HEADER, second.length - HEADER);
        for (int i = 2; i < units.size(); i++) {
            payload.write(units.get(i), 0, units.get(i).length);
        }
        assertArrayEquals(oneShot(), payload.toByteArray());
    }

    /**
     * The public {@link OStream#flush()} is the same handover as an
     * automatic one, so a sink may install from it too.
     */
    @Test
    void explicitFlushHonoursAnInstallationToo() throws IOException {
        OStream[] self = new OStream[1];
        byte[] replacement = freshBuffer(64);
        FlushSink taking = (data, off, len) -> self[0].bufferSet(replacement, HEADER);

        OStream os = new OStream(freshBuffer(64), 0, taking);
        self[0] = os;
        os.writeUnsigned(1, 7);
        os.flush();

        assertEquals(HEADER, os.bytesUsed(), "flush() discarded the installed offset");
        os.writeUnsigned(2, 8);
        assertArrayEquals(new byte[] {MARK, MARK, MARK}, Arrays.copyOf(replacement, HEADER));
    }

    /**
     * A replacement with no room left is a caller error, not a livelock: honouring
     * the installed offset means the encoder can be handed a buffer it cannot write
     * a single byte into, and it has to say so.
     */
    @Test
    void installingAFullyReservedBufferReportsBufferFull() {
        OStream[] self = new OStream[1];
        FlushSink noRoom = (data, off, len) -> self[0].bufferSet(freshBuffer(16), 16);

        OStream os = new OStream(freshBuffer(16), 0, noRoom);
        self[0] = os;

        SofabException e = assertThrows(SofabException.class, () -> message(os));
        assertEquals(SofabError.BUFFER_FULL, e.error());
    }

    /**
     * A stale installation must not leak into a later flush: bufferSet() called
     * outside a sink arms nothing for the flush after it.
     */
    @Test
    void bufferSetOutsideASinkDoesNotArmTheNextFlush() throws IOException {
        List<byte[]> units = new ArrayList<>();
        FlushSink copying = (data, off, len)
                -> units.add(Arrays.copyOfRange(data, off, off + len));

        OStream os = new OStream(freshBuffer(16), 0, copying);
        os.bufferSet(freshBuffer(16), HEADER); // an installation, but not from a flush
        message(os);
        os.flush();

        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        byte[] first = units.get(0);
        payload.write(first, HEADER, first.length - HEADER); // only unit 0 was reserved
        for (int i = 1; i < units.size(); i++) {
            payload.write(units.get(i), 0, units.get(i).length);
        }
        assertArrayEquals(oneShot(), payload.toByteArray());
    }
}
