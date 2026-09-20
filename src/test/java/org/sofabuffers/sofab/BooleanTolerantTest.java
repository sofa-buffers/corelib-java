/*
 * SofaBuffers Java - the shared `boolean_tolerant` block (CORELIB_PLAN §4.4).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Decode.verdict;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The fifth top-level block of the shared file: a boolean read from bytes
 * <b>someone else's encoder</b> wrote.
 *
 * <pre>
 * 00 80 02
 * ^^ id 0, wire type 0 (unsigned varint) — a boolean has no wire type of its own
 *    ^^^^^ the varint 256
 * </pre>
 *
 * <p>CORELIB_PLAN §4.4 is two rules, and only the first one the positive
 * {@code vectors} block can reach: <b>canonical on encode, tolerant on decode</b>.
 * An encoder MUST write {@code true} as {@code 1}; a decoder MUST read
 * <em>every</em> value other than {@code 0} as {@code true} — that is not
 * {@link SofabError#INVALID_MSG}, it is normalized away, and a re-encode emits
 * {@code 1}. A boolean is therefore <b>not</b> bound the way an {@code enum} or a
 * {@code bitfield} is (MESSAGE_SPEC §1): those carry the width their declaration
 * implies and a value outside it <em>is</em> invalid, while a boolean carries no
 * width bound at all. Bytes carrying {@code 2}, {@code 256} or {@code 2^64-1} at a
 * boolean position only ever arrive from a foreign encoder, which is why they are
 * hand-authored here instead of replayed out of {@code fields} like a vector.
 *
 * <p><b>Three defects, three separate assertions.</b> Dropping any one of them
 * certifies a decoder that violates §4.4:
 *
 * <table border="1">
 * <caption>what each half of a case catches</caption>
 * <tr><th>defect</th><th>looks like</th><th>caught by</th></tr>
 * <tr><td>rejection</td><td>{@code 256} answered {@code INVALID_MSG}, as if a
 *     boolean were one byte wide</td><td>the outcome assertion</td></tr>
 * <tr><td>truncation</td><td>{@code 256} masked to eight bits before the zero test,
 *     so it decodes to <b>false</b> while the outcome stays {@code COMPLETE}</td>
 *     <td>the value assertion</td></tr>
 * <tr><td>missing normalization</td><td>the raw {@code 2} kept, which is
 *     &quot;true&quot; under every truthiness test there is</td>
 *     <td>the <b>re-encode</b> assertion — it emits {@code 2} where §4.4 demands
 *     {@code 1}</td></tr>
 * </table>
 *
 * <p><b>Where this port's boolean surface is.</b> The decoder is push-based and has
 * no boolean callback: a boolean arrives as {@link Visitor#unsigned} carrying the
 * whole varint, and the zero test that makes it a {@code boolean} belongs to the
 * receiver, exactly as generated code writes it. So the library's obligation here is
 * to <em>deliver the value undamaged</em> — the full varint, on both decode
 * surfaces — and {@link BoolDest} is the receiver half, stating §4.4's read rule
 * once, against the delivered value and never against a narrowed copy of it. The
 * encode half is the library's outright: {@link OStream#writeBoolean} for a scalar
 * and {@link Seq#boolsToLongs} into {@link OStream#writeArrayUnsigned} for an array,
 * which is the bridge generated code uses because {@code bool} has no primitive
 * array overload of its own.
 *
 * <p><b>The destination width is a bound, and a boolean has none.</b>
 * {@link Visitor#arrayBulk} documents that handing back an array narrower than
 * {@code long[]} <em>declares</em> the elements that wide, so an element that does
 * not fit is {@code INVALID_MSG} rather than silently truncated. That contract is
 * right for an integer array (§4.7) and is precisely what a boolean array may not be
 * read through — {@link #narrowingABooleanArrayDestinationDeclaresAWidthItDoesNotHave}
 * pins both halves of that, so the choice of a {@code long[]} destination here is a
 * documented rule rather than an accident.
 */
class BooleanTolerantTest {

    /**
     * The capability vocabulary this port knows. A tag outside it is <b>not</b>
     * treated as unsupported: this block's rule is that an unrecognized name
     * contributes nothing to the needed set, so the case runs positively and every
     * port agrees on what happens when the corpus grows a tag. {@link #reportWhatRan}
     * prints any it met, so a new tag is visible rather than silent.
     */
    private static final Set<String> KNOWN_TAGS =
            Set.of("fixlen", "array", "sequence", "fp64", "int64");

    /**
     * The tags <b>this</b> build satisfies: all of them. This corelib compiles every
     * feature in — it has no {@code SOFAB_DISABLE_*} equivalent and no narrowed
     * scalar-width profile — so the capability set is complete, every case runs
     * positively and {@link #assertRejected} is unreachable here. The gate is
     * implemented anyway: a port that ever grows a reduced profile narrows this set
     * and the reject path starts firing, with no other change.
     */
    private static final Set<String> SATISFIED = KNOWN_TAGS;

    /** A byte to feed after a rejection, to show it is terminal; any byte does. */
    private static final byte[] ONE_MORE_BYTE = { 0x00 };

    /** The floor the block must still meet; a shrunken block is caught, a grown one is not. */
    private static final int CASE_FLOOR = 8;

    private static final List<JsonObject> CASES = load();

    /** Cases found / run positively / asserted rejected, and the assertions made. */
    private static final AtomicInteger DECODED = new AtomicInteger();
    private static final AtomicInteger REJECTED = new AtomicInteger();
    private static final AtomicInteger CHECKS = new AtomicInteger();

    /** Count one executed check — before the assertion, so a failing one counts too. */
    private static void check() {
        CHECKS.incrementAndGet();
    }

    private static List<JsonObject> load() {
        try (InputStream in = BooleanTolerantTest.class.getClassLoader()
                .getResourceAsStream("test_vectors.json")) {
            assertNotNull(in, "assets/test_vectors.json is not on the test classpath");
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray block = root.getAsJsonArray("boolean_tolerant");
            // A copy of the shared file that predates the block yields no array here,
            // the loop below iterates nothing and the suite goes green having tested
            // none of §4.4. Refresh the copy from corelib-c-cpp@main (the daily
            // `Shared vectors` job compares the sha256) rather than weakening this.
            assertNotNull(block, "no boolean_tolerant block: the copy of "
                    + "assets/test_vectors.json predates it and §4.4 has no corpus to run");
            List<JsonObject> cases = new ArrayList<>();
            for (JsonElement e : block) {
                cases.add(e.getAsJsonObject());
            }
            assertFalse(cases.isEmpty(), "boolean_tolerant block is empty");
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- the receiver: §4.4's read rule, stated once -----------------------------

    /**
     * The receiver half of a boolean read. The decoder hands over the varint it
     * accumulated; this is the zero test that turns it into a {@code boolean},
     * written once and applied to the <b>whole</b> delivered value — masking it to
     * the destination width first is the truncation this block exists to catch.
     *
     * <p>Nothing here asserts. A callback runs inside the decoder, where a thrown
     * assertion can be absorbed at the language boundary or leave the stream in a
     * state that masks the failure, so every complaint is recorded and read back
     * after the feed returns ({@link #problemsOr}).
     */
    private static final class BoolDest implements Visitor {
        /** The field id the case's bytes carry; anything else is a complaint. */
        private final int id;
        /** One slot per expected element, poisoned with the complement of it. */
        private final boolean[] slots;
        /** The varint the decoder delivered for each slot, for the failure message. */
        private final long[] raw;
        /** Take the elements through {@link Visitor#arrayBulk} instead of one at a time. */
        private final boolean bulk;
        private final List<String> problems = new ArrayList<>();
        private long[] bulkDest;
        private int delivered;
        private int announcedCount = -1;
        private ArrayKind announcedKind;

        BoolDest(int id, boolean[] expected, boolean bulk) {
            this.id = id;
            this.bulk = bulk;
            this.raw = new long[expected.length];
            // §8.4 poison: every slot starts at the complement of what it must end
            // at, so a decoder that never writes one fails instead of matching a
            // zero-initialized buffer on the `false` cases.
            this.slots = new boolean[expected.length];
            for (int i = 0; i < expected.length; i++) {
                slots[i] = !expected[i];
            }
        }

        private void record(long value) {
            int i = delivered++;
            if (i >= slots.length) {
                problems.add("element " + delivered + " arrived, the case carries " + slots.length);
                return;
            }
            raw[i] = value;
            // CORELIB_PLAN §4.4: every value other than 0 is true.
            slots[i] = value != 0;
        }

        @Override
        public void unsigned(int fieldId, long value) {
            if (fieldId != id) {
                problems.add("unsigned at field id " + fieldId + ", the case carries " + id);
                return;
            }
            record(value);
        }

        @Override
        public Object arrayBulk(int fieldId, ArrayKind kind, int count) {
            if (!bulk) {
                return null; // per-element delivery through unsigned()
            }
            if (count > slots.length) {
                problems.add("bulk offer for " + count + " elements, the case carries "
                        + slots.length);
                return null;
            }
            // long[], never narrower: a narrower destination declares a width, and
            // §4.4 says a boolean has none. See the class javadoc and
            // narrowingABooleanArrayDestinationDeclaresAWidthItDoesNotHave.
            bulkDest = new long[count];
            return bulkDest;
        }

        @Override
        public void arrayBulkEnd(int fieldId, int n) {
            if (fieldId != id) {
                problems.add("bulk end at field id " + fieldId + ", the case carries " + id);
                return;
            }
            for (int i = 0; i < n; i++) {
                record(bulkDest[i]);
            }
        }

        @Override
        public void arrayBegin(int fieldId, ArrayKind kind, int count) {
            announcedKind = kind;
            announcedCount = count;
            if (fieldId != id) {
                problems.add("array at field id " + fieldId + ", the case carries " + id);
            }
        }

        // A boolean rides the unsigned varint wire type and its array rides the
        // unsigned varint array: no other callback may fire for these bytes.

        @Override
        public void signed(int fieldId, long value) {
            problems.add("signed(" + fieldId + ") — a boolean is an unsigned varint");
        }

        @Override
        public void fp32(int fieldId, float value) {
            problems.add("fp32(" + fieldId + ") — a boolean is an unsigned varint");
        }

        @Override
        public void fp64(int fieldId, double value) {
            problems.add("fp64(" + fieldId + ") — a boolean is an unsigned varint");
        }

        @Override
        public void fixlenBegin(int fieldId, FixlenType subtype, int total) {
            problems.add("fixlenBegin(" + fieldId + ":" + subtype + ") — a boolean is a varint");
        }

        @Override
        public void sequenceBegin(int fieldId) {
            problems.add("sequenceBegin(" + fieldId + ") — these bytes carry one field");
        }

        /** What the decoder did, spelled out for a failing assertion. */
        private String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("delivered ").append(delivered).append(" element(s) as");
            for (int i = 0; i < delivered && i < slots.length; i++) {
                sb.append(i == 0 ? " " : ", ")
                        .append(Long.toUnsignedString(raw[i]))
                        .append("->").append(slots[i]);
            }
            if (announcedCount >= 0) {
                sb.append("; arrayBegin announced ").append(announcedKind)
                        .append("x").append(announcedCount);
            }
            return sb.toString();
        }

        /** {@code prefix} plus everything observed, as one failure message. */
        private String problemsOr(String prefix) {
            return prefix + " [" + describe()
                    + (problems.isEmpty() ? "" : "; complaints: " + problems) + "]";
        }
    }

    // --- one case ----------------------------------------------------------------

    static Stream<String> caseNames() {
        return CASES.stream().map(BooleanTolerantTest::nameOf);
    }

    /**
     * One test per case, so a port that gets a subset wrong is told <em>which</em>
     * subset. Both halves always run: decode the bytes and check the normalized
     * values, then re-encode <b>what the decoder produced</b> — never
     * {@code expect.values} out of the JSON, which would make the second half
     * trivially true and leave the first unverified.
     */
    @ParameterizedTest
    @MethodSource("caseNames")
    void booleanToleranceCaseMatchesItsExpectation(String name) throws IOException {
        JsonObject c = byName(name);
        int id = c.get("id").getAsInt();
        byte[] wire = unhex(c.get("serialized_hex").getAsString());
        JsonObject expect = c.getAsJsonObject("expect");
        String outcome = expect.get("outcome").getAsString();
        boolean[] expected = expectedValues(expect);
        String reencoded = expect.get("reencoded_hex").getAsString();

        List<String> missing = missingCaps(c);
        if (!missing.isEmpty()) {
            // Not a skip. A build that cannot represent the construct must REJECT
            // the message (§7 of the block's spec, CORELIB_PLAN §6.2/§6.2.2):
            // reading it by truncation is the corruption this block is for, and
            // skipping it asserts nothing in the build most likely to have it.
            REJECTED.incrementAndGet();
            assertRejected(name, wire, missing);
            return;
        }
        DECODED.incrementAndGet();

        // Read the key rather than assume it: every case in the block is `complete`
        // today — a tolerated value is not a rejected one — and a future case with
        // another outcome must fail loudly here instead of being mis-run.
        if (!"complete".equals(outcome)) {
            fail(name + ": expect.outcome is " + outcome + "; this block is decode-"
                    + "then-re-encode only and this runner knows no other outcome");
        }

        // Decode on every feed shape: one whole feed takes the contiguous fast path,
        // the splits drive the resumable state machine, and cases 6 and 8 carry a
        // ten-byte varint that then spans feed boundaries.
        boolean[] decoded = null;
        for (int chunk : CHUNKS) {
            decoded = assertDecodes(name, id, wire, expected, chunk, false);
        }
        if (expected.length > 1) {
            // The other read surface for an array: the elements land in a long[] the
            // receiver handed over, with no per-element callback at all.
            for (int chunk : CHUNKS) {
                assertDecodes(name, id, wire, expected, chunk, true);
            }
        }

        // Re-encode WHAT THE DECODER PRODUCED, on both encoder surfaces: into a
        // buffer sized exactly from the expectation (an encoder writing one byte too
        // many runs off the end rather than into slack space), and through a buffer
        // far too small plus a flush sink, so nothing hinges on a half-emitted buffer.
        check();
        assertEquals(reencoded, hex(reencodeToBuffer(id, decoded, reencoded.length() / 2)),
                name + ": re-encode of the decoded value");
        check();
        assertEquals(reencoded, hex(reencodeThroughSink(id, decoded)),
                name + ": re-encode of the decoded value, streamed through a flush sink");
    }

    /**
     * Feed {@code wire} to a fresh decoder in {@code chunk}-byte slices and return
     * the values it produced, having asserted the outcome and every slot.
     *
     * @param bulk take an array's elements through {@link Visitor#arrayBulk}
     */
    private static boolean[] assertDecodes(String name, int id, byte[] wire,
            boolean[] expected, int chunk, boolean bulk) {
        String how = (chunk == 0 ? "one feed" : chunk + "-byte feeds")
                + (bulk ? ", bulk destination" : "");
        // Fresh decoder and fresh, poisoned destination per run: a terminal verdict
        // or a retained buffer from a previous one must not reach this.
        BoolDest dest = new BoolDest(id, expected, bulk);
        check();
        assertEquals("A", verdict(wire, dest, chunk),
                dest.problemsOr(name + " (" + how + "): the message must decode to COMPLETE; "
                        + "a tolerated value is not a rejected one (§4.4)"));
        assertEquals(List.of(), dest.problems, name + " (" + how + "): the decoder's callbacks");
        assertEquals(expected.length, dest.delivered,
                dest.problemsOr(name + " (" + how + "): elements delivered"));
        if (expected.length > 1) {
            assertEquals(ArrayKind.UNSIGNED, dest.announcedKind,
                    name + " (" + how + "): a boolean array rides the unsigned varint array");
            assertEquals(expected.length, dest.announcedCount,
                    name + " (" + how + "): the element count the wire announced");
        } else {
            assertEquals(-1, dest.announcedCount,
                    name + " (" + how + "): a scalar boolean announced an array header");
        }
        // The value check the outcome cannot make: 256 masked into eight bits is 0,
        // which is `false` with a perfect COMPLETE in front of it.
        assertArrayEquals(expected, dest.slots,
                dest.problemsOr(name + " (" + how + "): the normalized values"));
        return dest.slots;
    }

    /**
     * An unsatisfied tag in <b>this</b> block means REJECT, not skip, and that
     * narrowing is normative for it: §4.4 lifts the width bound the <em>type</em>
     * carries, never the one a particular <em>build</em> has. Under a 32-bit
     * accumulator (§6.2.2, "scalar value width 32-bit") a boolean carrying
     * {@code 2^64-1} overflows before any boolean rule can apply, and §6.2 makes that
     * {@link SofabError#INVALID_MSG} (§5.2.2) — while skipping the case would assert
     * nothing in exactly the build most likely to truncate it. The rejection is also
     * <b>terminal</b>, because a verdict a later feed lifts is a defect of its own.
     *
     * <p>Unreachable in this port, whose capability set is complete. It is written
     * anyway so a narrowed profile needs no new test, only a smaller
     * {@link #SATISFIED}.
     */
    private static void assertRejected(String name, byte[] wire, List<String> missing) {
        IStream in = new IStream();
        // Binds nothing: what is asserted is the verdict, not what arrived first.
        Visitor sink = new Visitor() { };
        check();
        SofabError first = rejectionOf(in, wire, sink);
        assertEquals(SofabError.INVALID_MSG, first, name + ": this build lacks " + missing
                + ", so the message is INVALID — not skipped, and not read by truncation");
        assertEquals(SofabError.INVALID_MSG, rejectionOf(in, ONE_MORE_BYTE, sink),
                name + ": the rejection must be terminal");
    }

    /** Feed {@code data} and report the rejection it raised, or fail if it survived. */
    private static SofabError rejectionOf(IStream in, byte[] data, Visitor sink) {
        try {
            DecodeStatus st = in.feed(data, sink);
            return fail("decode survived and answered " + st + "; want a rejection");
        } catch (SofabException e) {
            return e.error();
        } catch (UncheckedIOException e) {
            // How a refusal raised from a visitor callback, which declares no checked
            // exception, reaches the caller.
            return ((SofabException) e.getCause()).error();
        }
    }

    // --- the encode half ---------------------------------------------------------

    /**
     * Write {@code values} at {@code id} through this port's boolean write surface:
     * {@link OStream#writeBoolean} for a scalar, and for an array the
     * {@link Seq#boolsToLongs} bridge into {@link OStream#writeArrayUnsigned} —
     * {@code bool} has no primitive array overload, and the element width never
     * reaches the wire (§4.7).
     */
    private static void writeBooleans(OStream os, int id, boolean[] values) throws IOException {
        if (values.length == 1) {
            os.writeBoolean(id, values[0]);
            return;
        }
        List<Boolean> boxed = new ArrayList<>(values.length);
        for (boolean v : values) {
            boxed.add(v);
        }
        os.writeArrayUnsigned(id, Seq.boolsToLongs(boxed));
    }

    /** Encode into a buffer of exactly {@code size} bytes, so an over-long encode overflows. */
    private static byte[] reencodeToBuffer(int id, boolean[] values, int size) throws IOException {
        OStream os = new OStream(new byte[size], 0);
        writeBooleans(os, id, values);
        assertEquals(size, os.flush(), "bytes pending after the re-encode");
        assertEquals(size, os.bytesUsed(), "bytes written by the re-encode");
        return os.copyOfBytesUsed();
    }

    /** Encode through a buffer too small to hold the message plus a flush sink. */
    private static byte[] reencodeThroughSink(int id, boolean[] values) throws IOException {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        FlushSink sink = collected::write;
        OStream os = new OStream(new byte[4], 0, sink);
        writeBooleans(os, id, values);
        os.flush();
        return collected.toByteArray();
    }

    // --- controls and inventory ---------------------------------------------------

    /**
     * NEGATIVE CONTROL for the destination this runner hands the decoder.
     *
     * <p>{@link Visitor#arrayBulk} makes the destination's width a <b>bound</b>: a
     * {@code byte[]} says the elements are declared eight bits wide, and an element
     * that does not fit is {@link SofabError#INVALID_MSG} rather than a silent
     * truncation. Right for an integer array — and exactly why a boolean array may
     * not be read through one, because §4.4 says a boolean carries no width bound at
     * all. The two halves are asserted together: the same bytes that decode cleanly
     * into a {@code long[]} are refused by a narrowed destination, which is what
     * shows the {@code long[]} in {@link BoolDest} is load-bearing rather than
     * incidental, and that this library truncates nothing on the way.
     */
    @Test
    void narrowingABooleanArrayDestinationDeclaresAWidthItDoesNotHave() {
        JsonObject c = widestArrayCase();
        Assumptions.assumeTrue(missingCaps(c).isEmpty(),
                "the array cases are gated out of this build");
        byte[] wire = unhex(c.get("serialized_hex").getAsString());
        check();
        assertEquals("R:INVALID_MSG", verdict(wire, new Visitor() {
            @Override
            public Object arrayBulk(int id, ArrayKind kind, int count) {
                return new byte[count];
            }
        }, 0), nameOf(c) + ": an element above the destination's width is refused, "
                + "never truncated — which is why a boolean is read through a long[]");
    }

    /**
     * An inventory guard: floors rather than equalities, so the block growing
     * upstream does not fail this port, while a block that SHRANK — or lost the shape
     * that makes it worth running — is caught. Every failure mode that runs fewer
     * cases than the file carries goes green without one.
     */
    @Test
    void theBlockStillCarriesEveryShapeItTests() {
        assertTrue(CASES.size() >= CASE_FLOOR, "boolean_tolerant carries " + CASES.size()
                + " cases, want at least " + CASE_FLOOR);

        int scalars = 0;
        int arrays = 0;
        int normalizing = 0;
        int wideVarints = 0;
        for (JsonObject c : CASES) {
            String name = nameOf(c);
            assertEquals("boolean/tolerant", c.get("group").getAsString(), name + ": group");
            assertTrue(c.has("id"), name + ": no field id to decode at or re-encode to");
            JsonObject expect = c.getAsJsonObject("expect");
            assertEquals("complete", expect.get("outcome").getAsString(), name + ": outcome");
            String serialized = c.get("serialized_hex").getAsString();
            String reencoded = expect.get("reencoded_hex").getAsString();
            assertFalse(reencoded.isEmpty(), name + ": no re-encode to compare against");
            int values = expectedValues(expect).length;
            assertTrue(values > 0, name + ": no expected values");
            if (values == 1) {
                scalars++;
            } else {
                arrays++;
            }
            if (!serialized.equals(reencoded)) {
                normalizing++;
            }
            // Ten hex digits is five bytes: a varint no eight-bit accumulator holds.
            if (serialized.length() >= 10) {
                wideVarints++;
            }
        }

        assertTrue(scalars >= 5, "only " + scalars + " scalar cases; the unconditional "
                + "floor of the block is five untagged scalars");
        assertTrue(arrays >= 2, "only " + arrays + " array cases; a runner that filtered on "
                + "a single value would lose the whole element-level half of §4.4");
        assertTrue(normalizing >= 5, "only " + normalizing + " cases re-encode to something "
                + "other than their own bytes; normalization is what the re-encode half tests");
        assertTrue(wideVarints >= 2, "only " + wideVarints + " cases carry a varint wider than "
                + "one byte; the truncation trap is gone from the block");

        // The untagged floor: the cases every build runs positively, however reduced.
        long untagged = CASES.stream().filter(c -> requiresOf(c).isEmpty()).count();
        assertTrue(untagged >= 5, "only " + untagged + " cases are untagged; those are the "
                + "ones a reduced build still has to read as true");
    }

    /** State what actually ran, as the other block runners do. */
    @AfterAll
    static void reportWhatRan() {
        Set<String> unknown = new LinkedHashSet<>();
        for (JsonObject c : CASES) {
            for (String tag : requiresOf(c)) {
                if (!KNOWN_TAGS.contains(tag)) {
                    unknown.add(tag);
                }
            }
        }
        System.out.println("[test_vectors] boolean_tolerant: " + CASES.size() + " found, "
                + DECODED.get() + " decoded, " + REJECTED.get() + " rejected by requires; "
                + CHECKS.get() + " checks executed"
                + (unknown.isEmpty() ? "" : " (ran positively past unrecognized tag(s) " + unknown + ")"));
        assertTrue(CASES.size() > 0, "boolean_tolerant found no cases; §4.4 was not tested");
        assertEquals(CASES.size(), DECODED.get() + REJECTED.get(),
                "cases found is not cases decoded plus cases rejected");
    }

    // --- reading the block --------------------------------------------------------

    private static String nameOf(JsonObject c) {
        return c.get("name").getAsString();
    }

    private static List<String> requiresOf(JsonObject c) {
        List<String> tags = new ArrayList<>();
        if (c.has("requires")) {
            for (JsonElement e : c.getAsJsonArray("requires")) {
                tags.add(e.getAsString());
            }
        }
        return tags;
    }

    /**
     * The case's tags this build cannot provide. A tag outside {@link #KNOWN_TAGS} is
     * not one of them — see that field: an unrecognized name runs the case positively.
     */
    private static List<String> missingCaps(JsonObject c) {
        List<String> missing = new ArrayList<>();
        for (String tag : requiresOf(c)) {
            if (KNOWN_TAGS.contains(tag) && !SATISFIED.contains(tag)) {
                missing.add(tag);
            }
        }
        return missing;
    }

    private static boolean[] expectedValues(JsonObject expect) {
        JsonArray values = expect.getAsJsonArray("values");
        boolean[] out = new boolean[values.size()];
        for (int i = 0; i < out.length; i++) {
            // Strictly the JSON boolean, never a coercion of something else: reading
            // the expectation through a truthiness wrapper is how a stored 2 passes.
            JsonElement e = values.get(i);
            assertTrue(e.getAsJsonPrimitive().isBoolean(), "expect.values holds a non-boolean");
            out[i] = e.getAsBoolean();
        }
        return out;
    }

    private static JsonObject byName(String name) {
        return CASES.stream().filter(c -> nameOf(c).equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("the block no longer carries " + name));
    }

    /** The array case with the most elements — the one carrying the widest element. */
    private static JsonObject widestArrayCase() {
        return CASES.stream()
                .filter(c -> expectedValues(c.getAsJsonObject("expect")).length > 1)
                .max((a, b) -> Integer.compare(
                        expectedValues(a.getAsJsonObject("expect")).length,
                        expectedValues(b.getAsJsonObject("expect")).length))
                .orElseThrow(() -> new AssertionError("the block carries no array case"));
    }

    /** Lowercase hex, as every {@code *_hex} string in the file is. */
    private static byte[] unhex(String hex) {
        assertEquals(0, hex.length() % 2, "odd-length hex: " + hex);
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
