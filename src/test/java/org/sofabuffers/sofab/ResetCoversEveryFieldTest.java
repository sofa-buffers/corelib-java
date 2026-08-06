/*
 * SofaBuffers Java - reset() must restore EVERY declared field, not the ones
 * whoever wrote it happened to remember.
 *
 * `reset()` is a second declaration site for encoder/decoder state: adding a
 * field to OStream or IStream is a local, obviously-correct change, and the fact
 * that it must also appear in reset() is invisible from there. Nothing fails when
 * it does not -- until a reused instance produces wrong bytes. That has happened
 * four times in one week on the same two classes (corelib-java#63):
 *
 *   * `nPending` was never added when lazy sequence framing landed, so a reset()
 *     after an abandoned marshal left held-back sequence headers pending and
 *     prepended them to the next message -- 3 bytes instead of 2 with one open
 *     sequence, 498 instead of 4 at MAX_DEPTH. Corrupt output on the wire.
 *   * `fixlenArray` was never added when the fixlen-array announce fix landed.
 *   * `State.IDLE` and `fixlenType` were left behind by the varint rework (#58).
 *
 * So this test does not check a list of fields someone maintains by hand. It
 * reflects over every field the class declares, pokes each one to a value that
 * differs from a freshly constructed instance, calls reset(), and asserts the
 * instance is field-for-field indistinguishable from a fresh one. A field added
 * tomorrow is covered the day it is added rather than the day someone remembers.
 *
 * Only fields whose ALLOCATION is deliberately retained across a reset are
 * exempt -- keeping them is the entire point of reuse -- and each exemption is
 * named below with the length counter that makes it unreadable.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ResetCoversEveryFieldTest {

    /**
     * {@code pending} carries held-back sequence ids two and beyond. It is never
     * read while {@code nPending} is zero, which {@link OStream#reset} does clear,
     * and keeping the array is why reuse avoids an allocation at all.
     */
    private static final Set<String> OSTREAM_RETAINED = Set.of("pending");

    /**
     * {@code acc} carries the bytes of a float split across feeds. Only its first
     * {@code accLen} bytes are ever read, and {@link IStream#reset} clears that.
     */
    private static final Set<String> ISTREAM_RETAINED = Set.of("acc");

    // --- the guard ----------------------------------------------------------

    @Test
    void ostreamResetRestoresEveryDeclaredField() throws Exception {
        byte[] buf = new byte[256];
        OStream used = new OStream(buf);
        int poked = dirtyEveryField(used, OSTREAM_RETAINED);
        assertTrue(poked > 0, "no field was poked - the guard would pass vacuously");

        used.reset(buf);

        assertIndistinguishableFromFresh(used, new OStream(buf), OSTREAM_RETAINED);
    }

    @Test
    void istreamResetRestoresEveryDeclaredField() throws Exception {
        IStream used = new IStream();
        int poked = dirtyEveryField(used, ISTREAM_RETAINED);
        assertTrue(poked > 0, "no field was poked - the guard would pass vacuously");

        used.reset();

        assertIndistinguishableFromFresh(used, new IStream(), ISTREAM_RETAINED);
    }

    // --- the same thing driven through the public API ------------------------
    //
    // The reflective poke proves reset() covers every field. These two prove the
    // states it must cover are reachable by encoding and decoding normally -- a
    // poke that set a field no real message can set would guard nothing.

    @Test
    void ostreamResetRestoresStateLeftByAnAbandonedEncode() throws Exception {
        byte[] buf = new byte[256];
        OStream used = new OStream(buf);
        used.writeUnsigned(1, 42);           // bytes written: offset != 0
        used.writeSequenceBeginLazy(3);      // held back: nPending 1, depth 1
        used.writeSequenceBeginLazy(4);      // held back: nPending 2, depth 2, pending[] live
        assertDiffersFromFresh(used, new OStream(buf), OSTREAM_RETAINED);

        byte[] next = new byte[256];
        used.reset(next);

        assertIndistinguishableFromFresh(used, new OStream(next), OSTREAM_RETAINED);
    }

    @Test
    void istreamResetRestoresStateLeftByAnAbandonedDecode() throws Exception {
        Visitor drop = new Visitor() { };
        IStream used = new IStream();
        used.feed(bytes(0x36), drop);           // sequence start, id 6: depth 1
        used.feed(bytes(0x22, 0x41), drop);     // fp64 field id 4, its fixlen_word
        used.feed(bytes(0x01, 0x02, 0x03), drop); // 3 of 8 payload bytes: acc in use
        assertEquals(DecodeStatus.INCOMPLETE, used.status());
        assertDiffersFromFresh(used, new IStream(), ISTREAM_RETAINED);

        used.reset();

        assertEquals(DecodeStatus.COMPLETE, used.status());
        assertIndistinguishableFromFresh(used, new IStream(), ISTREAM_RETAINED);
    }

    // --- the exemptions are real fields, not stale names ---------------------

    @Test
    void everyExemptionNamesAFieldThatStillExists() {
        assertKnownFields(OStream.class, OSTREAM_RETAINED);
        assertKnownFields(IStream.class, ISTREAM_RETAINED);
    }

    private static void assertKnownFields(Class<?> type, Set<String> names) {
        Set<String> declared = new LinkedHashSet<>();
        for (Field f : type.getDeclaredFields()) {
            declared.add(f.getName());
        }
        for (String name : names) {
            assertTrue(declared.contains(name),
                    type.getSimpleName() + " has no field '" + name + "': the exemption in "
                            + "ResetCoversEveryFieldTest outlived it and now hides a real one");
        }
    }

    // --- harness ------------------------------------------------------------

    /**
     * Set every resettable field of {@code target} to a value that differs from a
     * freshly constructed instance, and report how many were poked. Static fields
     * are not instance state; {@code final} ones are fixed at construction and are
     * documented as outside what reset restores (the {@link FlushSink}); the
     * {@code retained} ones are the deliberately kept allocations.
     */
    private static int dirtyEveryField(Object target, Set<String> retained) throws Exception {
        int poked = 0;
        for (Field f : target.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())
                    || retained.contains(f.getName())) {
                continue;
            }
            f.setAccessible(true);
            f.set(target, dirtyValue(f, f.get(target)));
            poked++;
        }
        return poked;
    }

    /**
     * A value for {@code f} that differs from {@code current}, the value a fresh
     * instance holds. An unhandled type fails loudly rather than skipping the
     * field: a field this harness cannot poke is a field this guard does not
     * cover, and that has to be a conscious decision, not a silent gap.
     */
    private static Object dirtyValue(Field f, Object current) {
        Class<?> t = f.getType();
        if (t == int.class) {
            return 0x5A5A5A5A;
        }
        if (t == long.class) {
            return 0x5A5A_5A5A_5A5A_5A5AL;
        }
        if (t == short.class) {
            return (short) 0x5A5A;
        }
        if (t == byte.class) {
            return (byte) 0x5A;
        }
        if (t == char.class) {
            return 'Z';
        }
        if (t == boolean.class) {
            return !((Boolean) current);
        }
        if (t == float.class) {
            return 1.5f;
        }
        if (t == double.class) {
            return 1.5d;
        }
        if (t == byte[].class) {
            return new byte[] {0x5A};
        }
        if (t == int[].class) {
            return new int[] {0x5A};
        }
        if (t.isEnum()) {
            for (Object constant : t.getEnumConstants()) {
                if (constant != current) {
                    return constant;
                }
            }
        }
        return fail("ResetCoversEveryFieldTest cannot poke field '" + f.getName() + "' of type "
                + t.getName() + ". Teach dirtyValue() about it (and make sure reset() clears it) "
                + "rather than leaving the field uncovered - see corelib-java#63.");
    }

    /** Assert {@code actual} is field-for-field what {@code fresh} is. */
    private static void assertIndistinguishableFromFresh(Object actual, Object fresh,
            Set<String> retained) throws Exception {
        List<String> stale = differingFields(actual, fresh, retained);
        assertTrue(stale.isEmpty(),
                "reset() left " + actual.getClass().getSimpleName() + " field(s) " + stale
                        + " unrestored. Every field the class declares has to be listed in "
                        + "reset(), or deliberately exempted as a retained allocation "
                        + "- see corelib-java#63.");
    }

    /** Guard the fixtures: driving the instance must actually leave state behind. */
    private static void assertDiffersFromFresh(Object actual, Object fresh, Set<String> retained)
            throws Exception {
        assertNotEquals(List.of(), differingFields(actual, fresh, retained),
                "the fixture left no state behind, so the reset below proves nothing");
    }

    private static List<String> differingFields(Object actual, Object fresh, Set<String> retained)
            throws Exception {
        List<String> differing = new ArrayList<>();
        for (Field f : fresh.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || retained.contains(f.getName())) {
                continue;
            }
            f.setAccessible(true);
            if (!Objects.deepEquals(f.get(fresh), f.get(actual))) {
                differing.add(f.getName());
            }
        }
        return differing;
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
