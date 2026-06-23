/*
 * SofaBuffers Java - decoder tests over the C reference byte vectors plus
 * malformed-input handling and chunk-boundary streaming.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

class IStreamTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static List<String> decode(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(data, v);
        return v.events;
    }

    /** Feed one byte at a time to prove decoding survives any chunk boundary. */
    private static List<String> decodeByteByByte(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream is = new IStream();
        for (byte b : data) {
            is.feed(new byte[] {b}, v);
        }
        return v.events;
    }

    @Test
    void unsignedScalar() throws SofabException {
        assertEquals(List.of("u:0=42"), decode(bytes(0x00, 0x2A)));
    }

    @Test
    void signedScalar() throws SofabException {
        assertEquals(List.of("s:2=-42"), decode(bytes(0x11, 0x53)));
    }

    @Test
    void fp32Scalar() throws SofabException {
        assertEquals(List.of("f32:0=3.1415"), decode(bytes(0x02, 0x20, 0x56, 0x0E, 0x49, 0x40)));
    }

    @Test
    void stringScalar() throws SofabException {
        assertEquals(
                List.of("str:0=Hello Couch!"),
                decode(bytes(0x02, 0x62, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x43, 0x6F, 0x75, 0x63, 0x68, 0x21)));
    }

    @Test
    void unsignedArray() throws SofabException {
        assertEquals(
                List.of("arr:0:UNSIGNED:5", "u:0=1", "u:0=2", "u:0=3", "u:0=2147483648", "u:0=4294967295"),
                decode(bytes(0x03, 0x05, 0x01, 0x02, 0x03, 0x80, 0x80, 0x80, 0x80, 0x08, 0xFF, 0xFF, 0xFF, 0xFF, 0x0F)));
    }

    @Test
    void fp64Array() throws SofabException {
        List<String> ev = decode(bytes(
                0x05, 0x05, 0x41, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xF0, 0x3F, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x40, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x08, 0x40, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0xFF, 0xFF,
                0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xEF, 0x7F));
        assertEquals(
                List.of("arr:0:FIXLEN:5", "f64:0=1.0", "f64:0=2.0", "f64:0=3.0",
                        "f64:0=" + (-Double.MAX_VALUE), "f64:0=" + Double.MAX_VALUE),
                ev);
    }

    @Test
    void nestedSequence() throws SofabException {
        assertEquals(
                List.of("u:0=42", "seq{:1", "u:0=42", "s:2=-42", "seq}", "s:2=-42"),
                decode(bytes(0x00, 0x2A, 0x0E, 0x00, 0x2A, 0x11, 0x53, 0x07, 0x11, 0x53)));
    }

    @Test
    void byteByByteMatchesWhole() throws SofabException {
        byte[] msg = bytes(0x00, 0x2A, 0x0E, 0x00, 0x2A, 0x11, 0x53, 0x07, 0x11, 0x53,
                0x02, 0x62, 0x48, 0x65, 0x6C, 0x6C, 0x6F, 0x20, 0x43, 0x6F, 0x75, 0x63, 0x68, 0x21);
        assertEquals(decode(msg), decodeByteByByte(msg));
    }

    // --- malformed input ----------------------------------------------------

    @Test
    void varintOverflowRejected() {
        // 10 continuation bytes overflow the 64-bit value type.
        byte[] bad = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        SofabException ex = assertThrows(SofabException.class, () -> decode(bad));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }

    @Test
    void danglingSequenceEndRejected() {
        SofabException ex = assertThrows(SofabException.class, () -> decode(bytes(0x07)));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }

    @Test
    void zeroLengthArrayRejected() {
        SofabException ex = assertThrows(SofabException.class, () -> decode(bytes(0x03, 0x00)));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }

    @Test
    void badFp32LengthRejected() {
        // fixlen header: id 0, fp32 subtype but length 5 (must be 4).
        SofabException ex = assertThrows(SofabException.class, () -> decode(bytes(0x02, 0x28)));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }
}
