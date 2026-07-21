/*
 * SofaBuffers Java - decoder tests over the C reference byte vectors plus
 * malformed-input handling and chunk-boundary streaming.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
    void zeroLengthArrayAccepted() throws SofabException {
        // §4.7: a zero-count array is valid — header [ (0<<3)|3 ] then count 0.
        // arrayBegin fires once with count 0 and no element callbacks follow.
        assertEquals(List.of("arr:0:UNSIGNED:0"), decode(bytes(0x03, 0x00)));
        assertEquals(List.of("arr:0:UNSIGNED:0"), decodeByteByByte(bytes(0x03, 0x00)));
    }

    @Test
    void badFp32LengthRejected() {
        // fixlen header: id 0, fp32 subtype but length 5 (must be 4).
        SofabException ex = assertThrows(SofabException.class, () -> decode(bytes(0x02, 0x28)));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }

    // --- three-valued outcome (MESSAGE_SPEC §7): COMPLETE / INCOMPLETE / INVALID -

    /** Feed a whole buffer, then report the terminal decode status. */
    private static DecodeStatus statusOf(byte[] data) throws SofabException {
        IStream is = new IStream();
        is.feed(data, new Visitor() { });
        return is.status();
    }

    @Test
    void completeMessageReportsComplete() throws SofabException {
        // A full unsigned scalar ends exactly at a field boundary.
        assertEquals(DecodeStatus.COMPLETE, statusOf(bytes(0x00, 0x2A)));
        // A balanced nested sequence with trailing fields is also COMPLETE.
        assertEquals(
                DecodeStatus.COMPLETE,
                statusOf(bytes(0x00, 0x2A, 0x0E, 0x00, 0x2A, 0x11, 0x53, 0x07, 0x11, 0x53)));
    }

    @Test
    void loneContinuationByteIsIncompleteNotComplete() throws SofabException {
        // A lone 0x80 is a varint with the continuation bit set and no terminating
        // byte: the field header never finished. It is NOT malformed (more bytes
        // could complete it) and NOT a clean boundary — it must read as INCOMPLETE,
        // distinct from a normal COMPLETE return.
        IStream is = new IStream();
        is.feed(bytes(0x80), new Visitor() { }); // returns normally, no throw
        assertEquals(DecodeStatus.INCOMPLETE, is.status());
    }

    @Test
    void loneContinuationByteDoesNotThrow() {
        // Reinforce: a partial varint is not a SofabException (that is reserved for
        // malformed input). Feeding it must complete without throwing.
        assertDoesNotThrow(() -> new IStream().feed(bytes(0x80), new Visitor() { }));
    }

    @Test
    void truncatedValueIsIncomplete() throws SofabException {
        // Unsigned field header (0x00) with a value varint whose continuation bit is
        // set but the following byte never arrives: ended inside the value.
        assertEquals(DecodeStatus.INCOMPLETE, statusOf(bytes(0x00, 0x80)));
    }

    @Test
    void openSequenceIsIncomplete() throws SofabException {
        // A message that opens a sequence (0x06 = id 0, SEQUENCE_START) and then
        // truncates before the matching end leaves depth != 0. Today this is
        // silently accepted; it must now read as INCOMPLETE (an unclosed sequence),
        // without throwing.
        assertEquals(DecodeStatus.INCOMPLETE, statusOf(bytes(0x06)));
        // Even with a complete inner field, an unclosed sequence is still INCOMPLETE.
        assertEquals(DecodeStatus.INCOMPLETE, statusOf(bytes(0x06, 0x00, 0x2A)));
    }

    @Test
    void truncatedArrayIsIncomplete() throws SofabException {
        // Unsigned array (0x03) with count 3 but only two elements delivered: the
        // array still has an element pending, so the decoder is mid-message.
        assertEquals(DecodeStatus.INCOMPLETE, statusOf(bytes(0x03, 0x03, 0x01, 0x02)));
    }

    @Test
    void truncatedStringPayloadIsIncomplete() throws SofabException {
        // fixlen string (0x02), length 5 (header 0x2A), but only 3 payload bytes:
        // the declared payload is short, so the field never finished.
        assertEquals(DecodeStatus.INCOMPLETE, statusOf(bytes(0x02, 0x2A, 0x48, 0x65, 0x6C)));
    }

    @Test
    void oversizeVarintIsInvalidNotIncomplete() {
        // A varint longer than 64 bits is malformed regardless of what follows: it
        // must throw INVALID_MSG, distinct from the non-throwing INCOMPLETE outcome.
        byte[] bad = bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80);
        SofabException ex = assertThrows(SofabException.class, () -> statusOf(bad));
        assertEquals(SofabError.INVALID_MSG, ex.error());
    }

    @Test
    void statusIsAPureAccessor() throws SofabException {
        // status() must not mutate decoder state: calling it repeatedly, and before
        // more bytes arrive, is stable and lets a resumed feed still complete.
        IStream is = new IStream();
        is.feed(bytes(0x00), new Visitor() { }); // field header only, value pending
        assertEquals(DecodeStatus.INCOMPLETE, is.status());
        assertEquals(DecodeStatus.INCOMPLETE, is.status());
        is.feed(bytes(0x2A), new Visitor() { }); // value arrives; message completes
        assertEquals(DecodeStatus.COMPLETE, is.status());
    }

    /** reset() returns a used decoder to a fresh state: reusing one instance across
     *  two messages must record exactly what two fresh instances would. */
    @Test
    void resetMakesDecoderReusable() throws SofabException {
        byte[] a = bytes(0x00, 0x2A);       // id 0, unsigned 42
        byte[] b = bytes(0x08, 0x63);       // id 1, unsigned 99
        RecordingVisitor va = new RecordingVisitor();
        IStream is = new IStream();
        is.feed(a, va);
        is.reset();
        RecordingVisitor vb = new RecordingVisitor();
        is.feed(b, vb);
        assertEquals(decode(a), va.events);
        assertEquals(decode(b), vb.events);
    }

    /** reset() discards a half-fed message, so a decoder can resynchronise onto the
     *  next message after abandoning a partial one. */
    @Test
    void resetDiscardsPartialMessage() throws SofabException {
        IStream is = new IStream();
        is.feed(bytes(0x00), new RecordingVisitor());   // id/type header, value byte withheld
        assertEquals(DecodeStatus.INCOMPLETE, is.status());
        is.reset();
        RecordingVisitor v = new RecordingVisitor();
        is.feed(bytes(0x08, 0x63), v);                  // a complete, unrelated message
        assertEquals(DecodeStatus.COMPLETE, is.status());
        assertEquals(decode(bytes(0x08, 0x63)), v.events);
    }

    /** After a malformed message throws INVALID, reset() lets the same instance
     *  decode a clean message — the stream-decoder resync path. */
    @Test
    void resetRecoversAfterInvalid() throws SofabException {
        IStream is = new IStream();
        assertThrows(SofabException.class,
                () -> is.feed(bytes(0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80),
                        new RecordingVisitor()));
        is.reset();
        RecordingVisitor v = new RecordingVisitor();
        is.feed(bytes(0x00, 0x2A), v);
        assertEquals(decode(bytes(0x00, 0x2A)), v.events);
    }

}
