/*
 * SofaBuffers Java - the library carries no code that no input can reach.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.sofabuffers.sofab.common.RecordingVisitor;

/**
 * Guards against re-growing unreachable code (corelib-java#75).
 *
 * <p>Two kinds of case live here. The first pins the <em>public surface</em>: an
 * exported entry point that nothing in this library, its tests or the generator's
 * Java backend calls is dead weight that no test can cover, and the enum-based
 * fixlen decoder is exactly that — the decoder maps the 3-bit sub-type inline, at
 * every one of its four reading sites.
 *
 * <p>The second pins the <em>narrowing checks</em> that make the impossible
 * branches impossible. Each of those sites rejects a reserved sub-type (or an
 * out-of-range id) before it dispatches, which is why the dispatch needs no
 * fallback arm; drop the check and the arm becomes reachable again. Every reserved
 * encoding is therefore driven through all four reading sites — scalar and array
 * element, one-shot and byte-at-a-time — so the fold cannot be undone unnoticed.
 */
class NoDeadCodeTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static SofabError errorOf(byte[] data) {
        SofabException ex = assertThrows(SofabException.class,
                () -> new IStream().feed(data, new Visitor() { }));
        return ex.error();
    }

    /** Feed {@code data} one byte at a time, so the resumable machine reads it. */
    private static SofabError errorOfChunked(byte[] data) {
        SofabException ex = assertThrows(SofabException.class, () -> {
            IStream in = new IStream();
            Visitor v = new Visitor() { };
            for (byte b : data) {
                in.feed(new byte[] { b }, v);
            }
        });
        return ex.error();
    }

    // --- public surface -----------------------------------------------------

    /**
     * {@link FixlenType} exposes the wire tag and nothing else. A {@code fromRaw}
     * style decoder would be public API no caller has: the decoder narrows the
     * 3-bit sub-type itself at each of its reading sites, so such a method could
     * never be exercised, and it would drag {@link SofabException} into an enum
     * that otherwise cannot fail.
     */
    @Test
    void fixlenTypeExposesOnlyTheWireTag() {
        List<String> declared = new ArrayList<>();
        for (Method m : FixlenType.class.getDeclaredMethods()) {
            if (m.isSynthetic() || !Modifier.isPublic(m.getModifiers())) {
                continue;
            }
            // values()/valueOf() are generated for every enum; they are not API
            // this library chose to expose.
            if (m.getName().equals("values") || m.getName().equals("valueOf")) {
                continue;
            }
            declared.add(m.getName());
            assertEquals(0, m.getExceptionTypes().length,
                    "FixlenType." + m.getName() + " must not declare a checked exception");
        }
        assertEquals(List.of("raw"), declared);
    }

    /** The tags themselves are the wire values, and stay so. */
    @Test
    void fixlenTypeTagsAreTheWireValues() {
        assertEquals(0x0, FixlenType.FP32.raw());
        assertEquals(0x1, FixlenType.FP64.raw());
        assertEquals(0x2, FixlenType.STRING.raw());
        assertEquals(0x3, FixlenType.BLOB.raw());
        assertEquals(4, FixlenType.values().length);
    }

    // --- the narrowing checks behind the folded arms ------------------------

    /**
     * Reserved sub-types 4..7 (§4.6) are rejected at the fixlen word, on all four
     * reading sites: scalar field and fixlen-array element, each one-shot and
     * byte-at-a-time. This is the check that leaves the sub-type dispatch below it
     * with no reachable fallback.
     */
    @Test
    void reservedFixlenSubtypeRejectedOnEveryReadingSite() {
        for (int subtype = 0x4; subtype <= 0x7; subtype++) {
            // Scalar fixlen field (id 0): header 0x02, then the fixlen word.
            byte[] scalar = bytes(0x02, subtype);
            assertEquals(SofabError.INVALID_MSG, errorOf(scalar), "scalar subtype " + subtype);
            assertEquals(SofabError.INVALID_MSG, errorOfChunked(scalar),
                    "scalar subtype " + subtype + " (chunked)");

            // Fixlen array (id 0): header 0x05, count 1, then the element word.
            byte[] array = bytes(0x05, 0x01, subtype);
            assertEquals(SofabError.INVALID_MSG, errorOf(array), "array subtype " + subtype);
            assertEquals(SofabError.INVALID_MSG, errorOfChunked(array),
                    "array subtype " + subtype + " (chunked)");
        }
    }

    /**
     * String/blob are the two sub-types that never carry a fixlen-array element
     * (§4.8) and are rejected there, on both reading sites — the other half of what
     * keeps the array dispatch exhaustive over fp32/fp64 alone.
     */
    @Test
    void dynamicFixlenArrayElementRejectedOnBothReadingSites() {
        for (int subtype : new int[] { 0x02, 0x03 }) { // STRING(2), BLOB(3)
            byte[] array = bytes(0x05, 0x01, (1 << 3) | subtype);
            assertEquals(SofabError.INVALID_MSG, errorOf(array), "element subtype " + subtype);
            assertEquals(SofabError.INVALID_MSG, errorOfChunked(array),
                    "element subtype " + subtype + " (chunked)");
        }
    }

    /**
     * Every one of the eight wire types is a real case in the header dispatch, so
     * that dispatch needs no fallback either: a field header carrying each type in
     * turn is decoded (or rejected for its own stated reason), never as an unknown
     * type. Fed byte-at-a-time to drive the resumable header path as well.
     */
    @Test
    void everyWireTypeIsHandledByTheHeaderDispatch() throws IOException {
        // All eight types on field id 1, each with just enough payload to be well
        // formed: unsigned, signed, fixlen string "A", unsigned array [42],
        // signed array [42], empty fp32 array, sequence start, sequence end.
        byte[] msg = bytes(
                0x08, 0x2A,
                0x09, 0x54,
                0x0A, 0x0A, 0x41,
                0x0B, 0x01, 0x2A,
                0x0C, 0x01, 0x54,
                0x0D, 0x00, 0x20,
                0x0E,
                0x0F);

        RecordingVisitor oneShot = new RecordingVisitor();
        IStream in = new IStream();
        in.feed(msg, oneShot);
        assertEquals(DecodeStatus.COMPLETE, in.status());

        // Byte-at-a-time drives the resumable header dispatch over the same eight.
        RecordingVisitor chunked = new RecordingVisitor();
        IStream slow = new IStream();
        for (byte b : msg) {
            slow.feed(new byte[] { b }, chunked);
        }
        assertEquals(DecodeStatus.COMPLETE, slow.status());

        List<String> expected = List.of(
                "u:1=42", "s:1=42", "str:1=A",
                "arr:1:UNSIGNED:1", "u:1=42",
                "arr:1:SIGNED:1", "s:1=42",
                "arr:1:FP32:0",
                "seq{:1", "seq}");
        assertEquals(expected, oneShot.events);
        assertEquals(expected, chunked.events);
        assertFalse(expected.isEmpty());
    }

    /**
     * The id ceiling is {@code INT32_MAX} (§6.2), so on the encoder every
     * {@code int} id is in range except a negative one — the whole of the check
     * {@code beginField} and the lazy sequence opener carry. Both accept
     * {@code ID_MAX} and reject {@code -1}.
     */
    @Test
    void encoderAcceptsIdMaxAndRejectsOnlyNegativeIds() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeUnsigned(WireFormat.ID_MAX, 1L);
        os.writeSequenceBeginLazy(WireFormat.ID_MAX);
        os.writeUnsigned(0, 2L);
        os.writeSequenceEnd();
        assertTrue(os.bytesUsed() > 0);

        RecordingVisitor v = new RecordingVisitor();
        new IStream().feed(buf, 0, os.bytesUsed(), v);
        assertTrue(v.events.contains("u:" + WireFormat.ID_MAX + "=1"));

        OStream neg = new OStream(new byte[16]);
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> neg.writeUnsigned(-1, 0L)).error());
        assertEquals(SofabError.ARGUMENT,
                assertThrows(SofabException.class, () -> neg.writeSequenceBeginLazy(-1)).error());
        assertEquals(0, neg.bytesUsed());
    }

    /**
     * An array count comes from a Java array's {@code length} and so is never
     * negative; a zero-length one is legal (§4.7) and is what the array writers
     * actually have to get right. Every overload, empty.
     */
    @Test
    void everyArrayWriterAcceptsAnEmptyArray() throws IOException {
        byte[] buf = new byte[64];
        OStream os = new OStream(buf);
        os.writeArrayUnsigned(1, new byte[0]);
        os.writeArrayUnsigned(2, new short[0]);
        os.writeArrayUnsigned(3, new int[0]);
        os.writeArrayUnsigned(4, new long[0]);
        os.writeArraySigned(5, new byte[0]);
        os.writeArraySigned(6, new short[0]);
        os.writeArraySigned(7, new int[0]);
        os.writeArraySigned(8, new long[0]);
        os.writeArrayFp32(9, new float[0]);
        os.writeArrayFp64(10, new double[0]);

        RecordingVisitor v = new RecordingVisitor();
        IStream in = new IStream();
        in.feed(buf, 0, os.bytesUsed(), v);
        assertEquals(DecodeStatus.COMPLETE, in.status());
        assertEquals(10, v.events.stream().filter(e -> e.startsWith("arr:")).count());
    }
}
