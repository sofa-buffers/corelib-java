/*
 * SofaBuffers Java - every malformed-input vector, on both decode surfaces.
 *
 * The decoder has two surfaces: a contiguous fast path that advances a pointer
 * over a whole buffer, and a resumable state machine that takes over wherever a
 * construct straddles a feed boundary. Each carries its own copy of the header /
 * length / count varint readers, so a guard added to one but not the other is
 * this decoder's recurring defect (corelib-java#53, #62, #68).
 *
 * The rejection cases are therefore a table, not a suite of hand-written methods:
 * one row per malformed vector, and every row is driven through both surfaces.
 * That is what three separate suites (DecoderErrorsTest, DecoderPathCoverageTest
 * and the malformed half of StateMachineCoverageTest) were collectively doing
 * before corelib-java#77, under three copies of the harness and overlapping
 * names. The accept-controls that pair with the rejections - the values one step
 * inside each ceiling - follow the table.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.sofabuffers.sofab.common.Decode.errorOf;
import static org.sofabuffers.sofab.common.Decode.errorOfChunked;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.sofabuffers.sofab.common.RecordingVisitor;

class DecoderErrorsTest {

    // --- vector builders ----------------------------------------------------

    /** Nine {@code fill} bytes then {@code last}: a ten-byte varint, the longest legal. */
    private static byte[] varint10(int fill, int last) {
        byte[] out = new byte[10];
        Arrays.fill(out, 0, 9, (byte) fill);
        out[9] = (byte) last;
        return out;
    }

    /** {@code n} copies of {@code marker} (one or two bytes), back to back. */
    private static byte[] repeat(byte[] marker, int n) {
        byte[] out = new byte[marker.length * n];
        for (int i = 0; i < n; i++) {
            System.arraycopy(marker, 0, out, i * marker.length, marker.length);
        }
        return out;
    }

    private static Arguments row(String name, byte[] data, SofabError expected) {
        return Arguments.of(name, data, expected);
    }

    // --- the table ----------------------------------------------------------
    //
    // Wire shorthand used throughout: a field header is `(id << 3) | wire_type`
    // (§4.3), so `02` = T_FIXLEN at id 0, `03` = ARRAY_UNSIGNED at id 0, `05` =
    // ARRAY_FIXLEN at id 0, `30` = T_VARINT_UNSIGNED at id 6, `07` = sequence
    // end. A fixlen_word is `(length << 3) | subtype` with subtype 0 = fp32,
    // 1 = fp64, 2 = string, 3 = blob (§4.6).
    //
    // A varint holds 7 payload bits per byte, so a 64-bit value takes ten bytes
    // and in the tenth only bit 0 (value bit 63) may be set (§4.1): `ff * 9`
    // then `02` sets value bit 64, and `80 * 9` then `81` clears the high-bit
    // check but flags a continuation into an out-of-range eleventh byte. Both
    // are overlong and INVALID, never silently truncated.

    static Stream<Arguments> malformedVectors() {
        List<Arguments> rows = new ArrayList<>();

        // --- the field header itself (§4.3, §6.2) ---------------------------
        rows.add(row("id above ID_MAX",
                bytes(0x80, 0x80, 0x80, 0x80, 0x40), SofabError.INVALID_MSG));
        rows.add(row("overlong field header, bit 64 set",
                varint10(0xFF, 0x02), SofabError.INVALID_MSG));
        rows.add(row("field header varint of eleven bytes",
                repeat(bytes(0x80), 11), SofabError.INVALID_MSG));

        // --- the scalar fixlen word (§4.6) ----------------------------------
        rows.add(row("reserved fixlen sub-type 4",
                bytes(0x02, 0x04), SofabError.INVALID_MSG));
        rows.add(row("reserved fixlen sub-type 4 in a two-byte word",
                bytes(0x02, 0x84, 0x01), SofabError.INVALID_MSG));
        rows.add(row("fp64 declared four bytes long",
                bytes(0x02, 0x21), SofabError.INVALID_MSG));
        rows.add(row("fp32 declared five bytes long",
                bytes(0x02, 0x28), SofabError.INVALID_MSG));
        rows.add(row("fp32 declared sixteen bytes long, two-byte word",
                bytes(0x02, 0x80, 0x01), SofabError.INVALID_MSG));
        rows.add(row("fixlen length above ARRAY_MAX",
                bytes(0x02, 0x82, 0x80, 0x80, 0x80, 0x40), SofabError.INVALID_MSG));
        rows.add(row("overlong fixlen length word, bit 64 set",
                concat(bytes(0x02), varint10(0xFF, 0x02)), SofabError.INVALID_MSG));
        rows.add(row("fixlen length word of eleven bytes",
                concat(bytes(0x02), varint10(0x80, 0x81)), SofabError.INVALID_MSG));

        // --- scalar varint values (§4.1) ------------------------------------
        rows.add(row("overlong u64 value, bit 64 set",
                concat(bytes(0x30), varint10(0xFF, 0x02)), SofabError.INVALID_MSG));
        rows.add(row("overlong u64 value, bits 64..69 set",
                concat(bytes(0x30), varint10(0xFF, 0x7F)), SofabError.INVALID_MSG));
        rows.add(row("u64 value varint of eleven bytes",
                concat(bytes(0x30), varint10(0x80, 0x81), bytes(0x00)), SofabError.INVALID_MSG));

        // --- array counts and array elements (§4.7) -------------------------
        //
        // An element is read by the unrolled fast-path reader only when a full
        // ten bytes of room remain, so the element rows end exactly at the tenth
        // element byte.
        rows.add(row("overlong array count, bit 64 set",
                concat(bytes(0x03), varint10(0xFF, 0x02)), SofabError.INVALID_MSG));
        rows.add(row("array count varint of eleven bytes",
                concat(bytes(0x03), varint10(0x80, 0x81)), SofabError.INVALID_MSG));
        rows.add(row("array count above ARRAY_MAX",
                bytes(0x03, 0x80, 0x80, 0x80, 0x80, 0x08), SofabError.INVALID_MSG));
        rows.add(row("overlong array element, bit 64 set",
                concat(bytes(0x03, 0x01), varint10(0xFF, 0x02)), SofabError.INVALID_MSG));
        rows.add(row("array element varint of eleven bytes",
                concat(bytes(0x03, 0x01), varint10(0xFF, 0x81)), SofabError.INVALID_MSG));

        // --- the fixlen-array element word (§4.8) ---------------------------
        rows.add(row("string as a fixlen-array element",
                bytes(0x05, 0x01, 0x0A), SofabError.INVALID_MSG));
        rows.add(row("blob as a fixlen-array element",
                bytes(0x05, 0x01, 0x0B), SofabError.INVALID_MSG));
        rows.add(row("fp32 element declared five bytes long",
                bytes(0x05, 0x01, 0x28), SofabError.INVALID_MSG));
        rows.add(row("fp64 element declared four bytes long",
                bytes(0x05, 0x01, 0x21), SofabError.INVALID_MSG));
        rows.add(row("fixlen-array element length above ARRAY_MAX",
                bytes(0x05, 0x01, 0x80, 0x80, 0x80, 0x80, 0x40), SofabError.INVALID_MSG));
        rows.add(row("overlong fixlen-array element word, bit 64 set",
                concat(bytes(0x05, 0x01), varint10(0xFF, 0x02)), SofabError.INVALID_MSG));
        rows.add(row("fixlen-array element word of eleven bytes",
                concat(bytes(0x05, 0x01), varint10(0x80, 0x81)), SofabError.INVALID_MSG));

        // --- sequences (§4.9) -----------------------------------------------
        //
        // SequenceEndIdTest owns the bare `07` end marker and the id ceiling on
        // it; what is new here is an in-range non-zero id (16 -> `87 01`), whose
        // two-byte header is reassembled by the state machine.
        rows.add(row("dangling sequence end with a two-byte header",
                bytes(0x87, 0x01), SofabError.INVALID_MSG));
        rows.add(row("nesting one past MAX_DEPTH",
                repeat(bytes(0x06), Sofab.MAX_DEPTH + 1), SofabError.INVALID_MSG));
        rows.add(row("nesting one past MAX_DEPTH, two-byte headers",
                repeat(bytes(0x86, 0x01), Sofab.MAX_DEPTH + 1), SofabError.INVALID_MSG));

        return rows.stream();
    }

    /**
     * Every malformed vector, rejected with the same error whether it arrives in
     * one feed (the fast path) or one byte at a time (the state machine). A
     * verdict that depends on how the input was chunked is exactly what §6.4 and
     * §7.2 forbid, so the two assertions belong to one case, not to two suites.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedVectors")
    void rejectedOnBothDecodeSurfaces(String name, byte[] data, SofabError expected) {
        assertEquals(expected, errorOf(data), name + " (one feed)");
        assertEquals(expected, errorOfChunked(data), name + " (byte at a time)");
    }

    // --- accept-controls: one step inside each ceiling ----------------------

    @Test
    void maximumU64Accepted() throws SofabException {
        // ff*9 then 0x01 is exactly 2^64-1, the valid maximum -> accepted, and
        // read as the same value on both surfaces.
        byte[] data = concat(bytes(0x30), varint10(0xFF, 0x01));
        List<String> expected = List.of("u:6=18446744073709551615");

        RecordingVisitor fast = new RecordingVisitor();
        new IStream().feed(data, fast);
        assertEquals(expected, fast.events);

        assertEquals(expected, feedByteByByte(data));
    }

    @Test
    void maximumU64ArrayElementAccepted() throws SofabException {
        // The same maximum as an array element, read by the unrolled reader.
        byte[] data = concat(bytes(0x03, 0x01), varint10(0xFF, 0x01));
        List<String> expected = List.of("arr:0:UNSIGNED:1", "u:0=18446744073709551615");

        RecordingVisitor fast = new RecordingVisitor();
        new IStream().feed(data, fast);
        assertEquals(expected, fast.events);

        assertEquals(expected, feedByteByByte(data));
    }

    @Test
    void nestingAtMaxDepthAccepted() throws SofabException {
        // MAX_DEPTH sequence starts and their ends is the deepest legal nesting.
        byte[] data = concat(repeat(bytes(0x06), Sofab.MAX_DEPTH),
                repeat(bytes(0x07), Sofab.MAX_DEPTH));
        new IStream().feed(data, new Visitor() { });
    }

    @Test
    void nestingAtMaxDepthAcceptedByStateMachine() throws SofabException {
        // The same depth with two-byte headers (id 16), fed one byte at a time so
        // every marker is reassembled by the state machine.
        byte[] data = concat(repeat(bytes(0x86, 0x01), Sofab.MAX_DEPTH),
                repeat(bytes(0x87, 0x01), Sofab.MAX_DEPTH));
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            expected.add("seq{:16");
        }
        for (int i = 0; i < Sofab.MAX_DEPTH; i++) {
            expected.add("seq}");
        }

        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        DecodeStatus after = null;
        for (byte b : data) {
            after = in.feed(new byte[] { b }, v);
        }
        assertEquals(expected, v.events);
        // Every sequence closed, no partial header: a clean field boundary.
        assertEquals(DecodeStatus.COMPLETE, after);
    }

    @Test
    void sequenceStartAndEndDecodedByStateMachine() throws SofabException {
        // seq start (id 16), unsigned 1 = 7, seq end (id 16 -> the end marker
        // carries id 16 too here, which the decoder discards for T_SEQUENCE_END).
        byte[] data = bytes(0x86, 0x01, 0x08, 0x07, 0x87, 0x01);
        assertEquals(List.of("seq{:16", "u:1=7", "seq}"), feedByteByByte(data));
    }

    /** Feed each byte in its own call, recording what the visitor sees. */
    private static List<String> feedByteByByte(byte[] data) throws SofabException {
        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        for (byte b : data) {
            in.feed(new byte[] { b }, v);
        }
        return v.events;
    }
}
