/*
 * SofaBuffers Java - the shared `sequence_growth` block (CORELIB_PLAN §7.2 item 8).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The third top-level block of the shared file, run per CORELIB_PLAN §7.2 item 8.
 *
 * <p>A wrapper (sequence) array carries no element count on the wire: its length is
 * <em>highest present id + 1</em> (MESSAGE_SPEC §5.1), so the size is known only
 * once the array ends and the container <b>grows</b> as elements arrive. That is the
 * one allocation shape where growth is conformant, and it happens in the static
 * helper layer — {@link Seq} — never in the codec (§6.6.1).
 *
 * <p><b>Why these cases cannot be vectors.</b> Two ports that grow differently emit
 * <em>identical bytes</em> and reach identical outcomes, so no {@code serialized.hex}
 * can tell them apart. The block is therefore keyed by a <b>delivery sequence of
 * element ids</b>, and the port builds the message itself from {@code deliver} and
 * asserts {@code expect}: container length and outcome only, no allocator
 * instrumentation, which is what makes the cases portable across the family.
 *
 * <p><b>What this port owns.</b> The struct cases run through {@link Seq#reserveRow},
 * the library's own wrapper-array placement: it compares the element index against
 * the {@link Bound} it is handed (§6.2.1), fills a gap with the empty row rather than
 * shifting later rows down, and replaces rather than merges. A struct element here is
 * a framed sub-sequence carrying one unsigned field, which is exactly a row holding
 * one value. The string cases have no such helper — a {@code List<String>} destination
 * is placed by generated code — so that path uses the library's cap comparison through
 * {@link Bound} and states the gap fill itself, standing in for the generated layer.
 */
class SequenceGrowthTest {

    /**
     * THIS port's {@code max_dyn_array_count} for the block's run.
     *
     * <p>The block never names an absolute boundary: a receiver cap is per-target
     * configuration and §6.2.1 fixes no family-wide number, so every case's
     * {@code id_from_cap} / {@code length_from_cap} is an OFFSET onto whatever the
     * port picks (-1 → cap-1, 0 → cap). The cases assume a cap of at least 4; 4 is
     * the smallest value that satisfies them.
     */
    private static final int CAP = 4;

    /**
     * This port's answer to the {@code dynamic_arrays} capability the block gates on:
     * a Java {@code List} destination grows as elements arrive, so the block runs.
     *
     * <p>Not a wire capability like the tags on a vector — it states how a port
     * ALLOCATES, not what it can parse, which is why it is the one tag a full-format
     * port still has to honour (test_vectors_README.md, "Gating").
     */
    private static final boolean GROWS_DYNAMIC_ARRAYS = true;

    private static final List<JsonObject> CASES = load();

    private static List<JsonObject> load() {
        try (InputStream in = SequenceGrowthTest.class.getClassLoader()
                .getResourceAsStream("test_vectors.json")) {
            assertNotNull(in, "assets/test_vectors.json is not on the test classpath");
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray block = root.getAsJsonArray("sequence_growth");
            assertNotNull(block, "no sequence_growth block: §7.2 item 8 has no corpus to run");
            List<JsonObject> cases = new ArrayList<>();
            for (int i = 0; i < block.size(); i++) {
                cases.add(block.get(i).getAsJsonObject());
            }
            assertTrue(!cases.isEmpty(), "sequence_growth block is empty");
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Stream<String> caseNames() {
        return CASES.stream().map(c -> c.get("name").getAsString());
    }

    private static JsonObject byName(String name) {
        return CASES.stream()
                .filter(c -> c.get("name").getAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Turn a possibly cap-relative index into an absolute one. Exactly one of the two
     * keys is present per the README; neither, or both, is a corrupt case rather than
     * something to guess at.
     */
    private static int resolve(JsonObject owner, String absKey, String capKey, String what) {
        boolean hasAbs = owner.has(absKey);
        boolean hasRel = owner.has(capKey);
        if (hasAbs && hasRel) {
            throw new IllegalStateException(what + " carries both " + absKey + " and " + capKey);
        }
        if (hasAbs) {
            return owner.get(absKey).getAsInt();
        }
        if (hasRel) {
            return CAP + owner.get(capKey).getAsInt();
        }
        throw new IllegalStateException(what + " carries neither " + absKey + " nor " + capKey);
    }

    // --- building the message the delivery sequence describes ---------------------

    private static byte[] build(JsonObject c) throws IOException {
        byte[] buf = new byte[4096];
        OStream out = new OStream(buf);
        boolean structElements = "struct".equals(c.get("element_type").getAsString());

        // The frame is KEPT even when empty: element presence is what carries the
        // array's length, so an empty wrapper is framed rather than omitted (§5.1).
        out.writeSequenceBeginLazy(c.get("field_id").getAsInt());
        JsonArray deliver = c.getAsJsonArray("deliver");
        for (int i = 0; i < deliver.size(); i++) {
            JsonObject d = deliver.get(i).getAsJsonObject();
            int id = resolve(d, "id", "id_from_cap", c.get("name").getAsString() + ": deliver[" + i + "]");
            if (structElements) {
                out.writeSequenceBeginLazy(id);
                out.writeUnsigned(0, d.get("value").getAsLong());
                out.writeSequenceEndKeep();
            } else {
                out.writeString(id, d.get("value").getAsString());
            }
        }
        out.writeSequenceEndKeep();

        byte[] message = new byte[out.bytesUsed()];
        System.arraycopy(buf, 0, message, 0, message.length);
        return message;
    }

    // --- the destination, standing in for the generated layer ---------------------

    /**
     * The wrapper-array destination. The struct path goes through
     * {@link Seq#reserveRow}, which is the library's placement and its cap comparison;
     * the string path states the same contract for a {@code List<String>}, which has
     * no helper here.
     *
     * <p>The order matters and is what the growth/reject case is about: §6.2.1 bounds
     * the index <em>before</em> the container it indexes into is extended, so a
     * rejected id must leave no partial extension behind.
     */
    private static final class GrowthDest implements Visitor {
        private final boolean structElements;
        private final Bound bound = Bound.receiver(CAP);
        private final List<String> strings = new ArrayList<>();
        private final List<List<Long>> rows = new ArrayList<>();
        private int depth;
        private int element = -1;
        private byte[] payload = Seq.EMPTY_BYTES;

        GrowthDest(boolean structElements) {
            this.structElements = structElements;
        }

        /**
         * The decoded length, read off the CONTAINER rather than tracked beside it.
         *
         * <p>That is deliberate and is what gives the growth/reject case its teeth: a
         * separate counter updated only after a successful placement would report the
         * right length even if the container had already been extended toward a
         * rejected index, which is precisely the defect §6.2.1's "before the container
         * it indexes into is extended" forbids.
         */
        int length() {
            return structElements ? rows.size() : strings.size();
        }

        String stringAt(int i) {
            return i < strings.size() ? strings.get(i) : "";
        }

        long numberAt(int i) {
            List<Long> row = i < rows.size() ? rows.get(i) : null;
            return row == null || row.isEmpty() ? 0L : row.get(0);
        }

        @Override
        public void sequenceBegin(int id) {
            depth++;
            // depth 1 is the wrapper itself; depth 2 is a struct element.
            if (depth == 2 && structElements) {
                // The library's own placement: bounds the index against the cap,
                // gap-fills with the empty row, and never shifts later rows down.
                Seq.reserveRow(rows, id, bound);
                element = id;
            }
        }

        @Override
        public void sequenceEnd() {
            if (depth == 2) {
                element = -1;
            }
            depth--;
        }

        @Override
        public void unsigned(int id, long value) {
            if (depth == 2 && structElements && element >= 0 && id == 0) {
                rows.get(element).add(value);
            }
        }

        @Override
        public void string(int id, int total, int offset, byte[] data, int chunkOffset,
                int chunkLength) {
            if (depth != 1 || structElements) {
                return;
            }
            // The index is bounded at the FIRST piece, before any payload is kept: a
            // rejection must not depend on the payload arriving whole.
            if (offset == 0) {
                if (bound.exceededByIndex(id)) {
                    throw Sofab.limitExceeded(
                            "array element index " + id + " above configured limit " + CAP);
                }
                // MESSAGE_SPEC §5.1: every destination slot is initialised to its
                // ELEMENT DEFAULT before the array is applied — "" for a string, not
                // null. Only a gap case ever looks at a slot nothing was written to.
                while (strings.size() <= id) {
                    strings.add("");
                }
                payload = new byte[total];
            }
            System.arraycopy(data, chunkOffset, payload, offset, chunkLength);
            if (offset + chunkLength == total) {
                strings.set(id, new String(payload, StandardCharsets.UTF_8));
            }
        }
    }

    // --- the cases ----------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("caseNames")
    void growthCaseMatchesExpectation(String name) throws IOException {
        // A statically bounded profile declares dynamic_arrays false and states that
        // in its README instead (§7.2 item 8); this port grows, so it runs.
        if (!GROWS_DYNAMIC_ARRAYS) {
            return;
        }
        JsonObject c = byName(name);
        boolean structElements = "struct".equals(c.get("element_type").getAsString());

        byte[] message = build(c);
        GrowthDest dest = new GrowthDest(structElements);
        IStream in = new IStream();

        UncheckedIOException caught = null;
        try {
            in.feed(message, dest);
        } catch (UncheckedIOException e) {
            caught = e;
        }
        final UncheckedIOException thrown = caught;

        JsonObject expect = c.getAsJsonObject("expect");
        String outcome = expect.get("outcome").getAsString();

        if ("complete".equals(outcome)) {
            assertNull(thrown, () -> "decode threw " + thrown);
            assertEquals(resolve(expect, "length", "length_from_cap", name + ": expect"),
                    dest.length(), "container length");

            // A gap below the cap holds the element default, and neither shortens nor
            // shifts the array (§5.1).
            if (expect.has("default_ids")) {
                JsonArray ids = expect.getAsJsonArray("default_ids");
                for (int i = 0; i < ids.size(); i++) {
                    int id = ids.get(i).getAsInt();
                    assertTrue(id < dest.length(),
                            "default id " + id + " past the container length " + dest.length());
                    if (structElements) {
                        assertEquals(0L, dest.numberAt(id), "element " + id);
                    } else {
                        assertEquals("", dest.stringAt(id), "element " + id);
                    }
                }
            }
        } else {
            // A policy rejection, not INVALID: the same bytes decode under a looser
            // cap (§6.2.1, §6.3).
            if (thrown == null) {
                fail("decode completed; want a LIMIT_EXCEEDED rejection");
            }
            assertTrue(thrown.getCause() instanceof SofabException, "cause is a SofabException");
            assertEquals(SofabError.LIMIT_EXCEEDED, ((SofabException) thrown.getCause()).error());

            // The bound is applied BEFORE the container is extended, so the length
            // never passes what legitimately arrived — and the rejection is terminal,
            // so an element delivered after it does not land either.
            if (expect.has("max_length")) {
                int max = expect.get("max_length").getAsInt();
                assertTrue(dest.length() <= max,
                        "container length " + dest.length() + ", want at most " + max
                                + " — extended toward the rejected index");
            }
            if (expect.has("terminal") && expect.get("terminal").getAsBoolean()) {
                // Terminal: the rejection is not folded into the wire-conformance
                // outcome (§6.3), so the status is never INVALID and never COMPLETE.
                assertTrue(in.status() != DecodeStatus.INVALID, "status is not INVALID");
                assertTrue(in.status() != DecodeStatus.COMPLETE, "status is not COMPLETE");
            }
        }
    }

    /**
     * The block is the one place a full-format port still honours {@code requires}:
     * the tag says how the port ALLOCATES, not what it can parse, so a statically
     * bounded build must skip these cases even though it runs every vector.
     */
    @Test
    void everyCaseIsGatedOnDynamicArrays() {
        for (JsonObject c : CASES) {
            JsonArray requires = c.getAsJsonArray("requires");
            assertNotNull(requires, c.get("name").getAsString() + " carries no requires");
            boolean found = false;
            for (int i = 0; i < requires.size(); i++) {
                if ("dynamic_arrays".equals(requires.get(i).getAsString())) {
                    found = true;
                }
            }
            assertTrue(found, c.get("name").getAsString() + " does not carry the dynamic_arrays tag");
        }
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the block
     * does not fail this port, while a block that SHRANK — or a case kind that
     * vanished — is caught.
     */
    @Test
    void theBlockCarriesEveryCaseKind() {
        assertTrue(CASES.size() >= 8,
                "sequence_growth carries " + CASES.size() + " cases, want at least 8");

        Set<String> groups = new HashSet<>();
        Set<String> kinds = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        for (JsonObject c : CASES) {
            groups.add(c.get("group").getAsString());
            kinds.add(c.get("element_type").getAsString());
            outcomes.add(c.getAsJsonObject("expect").get("outcome").getAsString());
        }

        for (String g : new String[] {"growth/index", "growth/gap", "growth/reject",
                "growth/length"}) {
            assertTrue(groups.contains(g), "no case in group " + g);
        }
        // Both element kinds are mandatory: a string element reaches the destination
        // through the leaf path and a struct element through the sequence path, and a
        // port can get one right and the other wrong.
        for (String k : new String[] {"string", "struct"}) {
            assertTrue(kinds.contains(k), "no case with element_type " + k);
        }
        for (String o : new String[] {"complete", "limit_exceeded"}) {
            assertTrue(outcomes.contains(o), "no case expecting outcome " + o);
        }
    }
}
