/*
 * SofaBuffers Java - the shared `header_limits` block (CORELIB_PLAN §6.2.1, §6.3).
 *
 * SPDX-License-Identifier: MIT
 */

package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
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
 * The fourth top-level block of the shared file: the <b>truncated over-ceiling
 * header</b> — bytes that <em>declare</em> a length or a count and then end, with
 * not one payload byte behind them.
 *
 * <pre>
 * 02 a2 06   then EOF
 * ^^ id 0, wire type 2 (fixlen)
 *    ^^^^^ length word (100 &lt;&lt; 3) | 2  -&gt;  a 100-byte STRING is declared
 *            ... and the message ends.
 * </pre>
 *
 * <p>A conformant decoder answers <b>at that word</b>, before the payload is asked
 * for, so the answer is the ceiling's and it is <b>terminal</b>. {@code INCOMPLETE}
 * is the one outcome it may not be: MESSAGE_SPEC §5.2.1 defines that as the outcome
 * more bytes <em>can</em> change, and §5.2.4 has a streaming caller read it as "feed
 * me the next chunk" — both false statements about the state once a ceiling has
 * fired. Three bytes claiming a hundred, or six claiming a gigabyte, would otherwise
 * hold a connection open: the amplification the caps exist to close (ARCHITECTURE
 * §9.5, "a claimed oversize fails fast even if the payload never arrives").
 *
 * <p><b>Which ceiling speaks is the subject.</b> The two give opposite answers on
 * the same word, and a case carries {@code schema} or {@code limits} but never both,
 * because §6.2.1 forbids applying a receiver cap to a field the schema already
 * bounds:
 *
 * <table border="1">
 * <caption>the two ceilings</caption>
 * <tr><th>the case states</th><th>the ceiling</th><th>a breach is</th></tr>
 * <tr><td>{@code "schema": { "maxlen": N }}</td><td>the schema bound</td>
 *     <td>{@link SofabError#INVALID_MSG} (MESSAGE_SPEC §7.1)</td></tr>
 * <tr><td>{@code "limits": { "max_dyn_…": N }}</td><td>the receiver cap</td>
 *     <td>{@link SofabError#LIMIT_EXCEEDED} (§6.2.1, §6.3)</td></tr>
 * </table>
 *
 * <p>{@code header_string_schema_bounded} and {@code header_string_over_cap} carry
 * the <b>identical bytes</b> and differ only in which ceiling the case configures;
 * that pair is what keeps the two categories apart, and
 * {@link #theBlockPairsEveryRejectionWithAnInCapControl} asserts the block still
 * carries it.
 *
 * <p><b>Where this port answers.</b> The comparison is the library's, reachable on
 * its own as {@link PayloadAcc#checkStringLength} / {@link PayloadAcc#checkBlobLength},
 * and this reader makes it from {@link Visitor#fixlenBegin} — the callback the
 * decoder raises at the length word, for exactly this reason — as generated code
 * does (corelib-java#107). The array <b>count</b> has no such helper, because an
 * array destination is the generated layer's ({@link Seq} grows one against elements
 * that actually arrived and never sizes from an announced count), so {@link HeaderDest}
 * states that rule itself out of the same {@link Bound} comparison, standing in for
 * the generated layer as {@code SequenceGrowthTest}'s string path does.
 *
 * <p><b>Bounds are absolute here, not cap-relative.</b> The opposite of
 * {@code sequence_growth}, and for a concrete reason: there the port <em>builds</em>
 * the message, so a cap-relative index can be substituted at run time, while a case
 * here <b>is</b> a fixed byte string with the declared number baked into the varint
 * and therefore <em>tells</em> the port which ceiling to configure. Nothing in this
 * class is a claim about any deployment's configuration — the ceilings live per case
 * and nothing outlives the run.
 */
class HeaderLimitsTest {

    /**
     * Every {@code requires} tag this block can carry. A tag outside the set is one
     * this port has never been told about: the gate refuses it rather than reading it
     * as "unsupported", which would skip the case and still report a pass.
     */
    private static final Set<String> KNOWN_TAGS =
            Set.of("fixlen", "array", "int64", "receiver_caps");

    /**
     * The tags <b>this</b> port satisfies.
     *
     * <p>The wire tags are all of them: this corelib compiles every feature in and
     * has no {@code SOFAB_DISABLE_*} equivalent. {@code receiver_caps} is a
     * <b>profile</b> capability rather than a wire one — a port declares it when its
     * generated code carries §6.2.1 receiver caps <em>distinct from</em> schema
     * bounds — and this port does: {@link Bound#receiver(long)} and
     * {@link Bound#SCHEMA_BOUNDED} are two different arguments to the same call and
     * a breach of one is {@link SofabError#LIMIT_EXCEEDED} where a breach of the
     * other is {@link SofabError#INVALID_MSG}.
     */
    private static final Set<String> SATISFIED = KNOWN_TAGS;

    /** The three §6.2.1 receiver caps a case may configure. */
    private static final Set<String> KNOWN_LIMITS =
            Set.of("max_dyn_string_len", "max_dyn_blob_len", "max_dyn_array_count");

    /**
     * The schema declares no bound on this field, so a receiver cap is what governs
     * it. Negative, because there is no number to compare against.
     */
    private static final int NO_SCHEMA_BOUND = -1;

    /**
     * The ceiling lifted, for the negative control only. {@code Integer.MAX_VALUE} is
     * the format ceiling ({@code SOFAB_ARRAY_MAX}), so this admits every length the
     * decoder will read at all — which is what makes the control a control.
     */
    private static final Bound UNCAPPED = Bound.receiver(Integer.MAX_VALUE);

    /** A byte to feed after a terminal rejection; any byte does. */
    private static final byte[] ONE_MORE_BYTE = { 0x61 };

    private static final List<JsonObject> CASES = load();

    /** Cases actually executed / skipped by {@code requires}; reported by {@link #reportWhatRan}. */
    private static final AtomicInteger RAN = new AtomicInteger();
    private static final AtomicInteger SKIPPED = new AtomicInteger();

    private static List<JsonObject> load() {
        try (InputStream in = HeaderLimitsTest.class.getClassLoader()
                .getResourceAsStream("test_vectors.json")) {
            assertNotNull(in, "assets/test_vectors.json is not on the test classpath");
            JsonObject root = JsonParser.parseReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray block = root.getAsJsonArray("header_limits");
            assertNotNull(block, "no header_limits block: §6.2.1 has no corpus to run");
            List<JsonObject> cases = new ArrayList<>();
            for (JsonElement e : block) {
                cases.add(e.getAsJsonObject());
            }
            assertFalse(cases.isEmpty(), "header_limits block is empty");
            return cases;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- the ceilings a case configures for its own run --------------------------

    /**
     * The two ceilings, as one case states them. Exactly one is ever in play: a
     * schema-bounded case carries no cap at all (every {@code Bound} null), and a
     * capped case carries {@link #NO_SCHEMA_BOUND}.
     *
     * <p>A null cap is deliberately <em>not</em> a large number. §6.2.1 forbids
     * reading an omitted cap as unlimited, and the library answers
     * {@link SofabError#ARGUMENT} for a schema-unbounded field handed none — so a
     * reader that consulted the wrong construct's cap fails loudly instead of quietly
     * passing.
     */
    private record Ceiling(int schemaBound, Bound stringCap, Bound blobCap, Bound arrayCap) {

        boolean schemaBounded() {
            return schemaBound >= 0;
        }
    }

    /**
     * Read the ceiling out of a case. {@code lifted} answers the negative control:
     * every receiver cap raised out of the way, the schema bound left exactly as
     * stated — a schema bound is not a receiver policy, and lifting it would be a
     * different experiment.
     */
    private static Ceiling ceilingOf(JsonObject c, boolean lifted) {
        String name = nameOf(c);
        JsonObject limits = c.has("limits") ? c.getAsJsonObject("limits") : null;
        JsonObject schema = c.has("schema") ? c.getAsJsonObject("schema") : null;
        assertFalse(limits != null && schema != null,
                name + " carries both limits and schema; §6.2.1 forbids capping a "
                        + "schema-bounded field");
        if (schema != null) {
            assertEquals(Set.of("maxlen"), schema.keySet(), name + ": unknown schema key(s)");
            return new Ceiling(schema.get("maxlen").getAsInt(), null, null, null);
        }
        assertNotNull(limits, name + " carries neither limits nor schema");
        assertEquals(1, limits.size(), name + " states " + limits.size()
                + " caps; a case bounds one construct");
        for (String k : limits.keySet()) {
            assertTrue(KNOWN_LIMITS.contains(k), name + ": unknown receiver cap " + k);
        }
        return new Ceiling(NO_SCHEMA_BOUND,
                capOf(limits, "max_dyn_string_len", lifted),
                capOf(limits, "max_dyn_blob_len", lifted),
                capOf(limits, "max_dyn_array_count", lifted));
    }

    private static Bound capOf(JsonObject limits, String key, boolean lifted) {
        if (!limits.has(key)) {
            return null;
        }
        return lifted ? UNCAPPED : Bound.receiver(limits.get(key).getAsLong());
    }

    // --- the destination, standing in for the generated layer --------------------

    /**
     * A visitor that applies the case's ceiling <b>at the header word</b>, which is
     * where §6.2.1 puts the enforcement point: "at the count/length header, before the
     * allocation it is meant to prevent".
     *
     * <p>It records what the decoder announced before it decides anything, so a case
     * that rejects still proves the header was read as the case describes — the number
     * the ceiling was compared against is the one on the wire, not one this reader
     * supplied.
     */
    private static final class HeaderDest implements Visitor {
        private final Ceiling ceiling;
        private final List<String> events = new ArrayList<>();
        private FixlenType subtype;
        private ArrayKind kind;
        private int headerId = -1;
        private int announced = -1;

        HeaderDest(Ceiling ceiling) {
            this.ceiling = ceiling;
        }

        @Override
        public void fixlenBegin(int id, FixlenType subtype, int total) {
            events.add("fixlen:" + id + ":" + subtype + ":" + total);
            this.subtype = subtype;
            this.headerId = id;
            this.announced = total;
            switch (subtype) {
                case STRING -> {
                    // The schema's own rule, applied by the layer that knows the
                    // schema, one line before the library call — as Bound's javadoc
                    // spells the generated pattern out (MESSAGE_SPEC §7.1).
                    if (ceiling.schemaBounded() && total > ceiling.schemaBound()) {
                        throw Sofab.invalid("string length " + total + " above maxlen "
                                + ceiling.schemaBound());
                    }
                    // The library's own comparison, made at the length word.
                    // PayloadAcc.string calls the same routine, so this is ONE
                    // implementation of the rule applied where §6.2.1 requires it —
                    // not a second one (corelib-java#107).
                    PayloadAcc.checkStringLength(total, boundFor(ceiling.stringCap()));
                }
                case BLOB -> {
                    if (ceiling.schemaBounded() && total > ceiling.schemaBound()) {
                        throw Sofab.invalid("blob length " + total + " above maxlen "
                                + ceiling.schemaBound());
                    }
                    PayloadAcc.checkBlobLength(total, boundFor(ceiling.blobCap()));
                }
                // fp32/fp64 carry a width the format fixes; there is nothing to bound.
                case FP32, FP64 -> { }
                default -> throw new AssertionError("unreachable subtype " + subtype);
            }
        }

        @Override
        public void arrayBegin(int id, ArrayKind kind, int count) {
            events.add("array:" + id + ":" + kind + ":" + count);
            this.kind = kind;
            this.headerId = id;
            this.announced = count;
            boundCount(count);
        }

        /**
         * A count ahead of its payload, bound exactly as a length is (§6.2.1) — and
         * unlike a wrapper array, which carries no count on the wire and is bound at
         * the element index instead ({@link Seq#reserveRow}, and the
         * {@code sequence_growth} block).
         *
         * <p>Stated here rather than called out of the library because an array
         * destination is the generated layer's: {@link Seq#ensureCap} grows one
         * against elements that have actually arrived and never sizes from an
         * announced count, so the count itself is refused by the caller that owns the
         * destination. The <em>categories</em> and the comparison are still the
         * library's, through the same {@link Bound}: a declared schema {@code count}
         * makes an over-count message malformed (MESSAGE_SPEC §7.1), a
         * schema-uncounted array is the receiver cap's and its breach is
         * {@link SofabError#LIMIT_EXCEEDED} (§6.3) — or {@link SofabError#ARGUMENT}
         * where the call stated no cap at all, since §6.2.1 forbids reading an omitted
         * cap as unlimited.
         */
        private void boundCount(int count) {
            if (ceiling.schemaBounded()) {
                if (count > ceiling.schemaBound()) {
                    throw Sofab.invalid("element count " + count + " above declared count "
                            + ceiling.schemaBound());
                }
                return;
            }
            if (boundFor(ceiling.arrayCap()).exceededBy(count)) {
                throw Sofab.limitExceeded("element count " + count
                        + " above configured limit " + ceiling.arrayCap().cap());
            }
        }

        /**
         * The bound to hand the library for this construct: the schema's answer where
         * the schema bounds the field (a distinct value carrying no number, because
         * the check above has already run), otherwise the case's cap — and a null one
         * is passed straight through, so an unstated cap is diagnosed as
         * {@code ARGUMENT} rather than obeyed as "unlimited".
         */
        private Bound boundFor(Bound cap) {
            return ceiling.schemaBounded() ? Bound.SCHEMA_BOUNDED : Bound.required(cap, "the cap this case states");
        }
    }

    // --- feeding one case --------------------------------------------------------

    /**
     * The bytes a case is fed as: its {@code chunks} where it has them, otherwise
     * {@code serialized} in one call. A chunked case must carry the same bytes as the
     * whole string — the verdict is a property of the bytes and not of how they were
     * split (§7.2 item 4), which the case can only test if the two agree.
     */
    private static List<byte[]> chunksOf(JsonObject c) {
        String serialized = c.get("serialized").getAsString();
        if (!c.has("chunks")) {
            return List.of(unhex(serialized));
        }
        List<byte[]> parts = new ArrayList<>();
        StringBuilder joined = new StringBuilder();
        for (JsonElement e : c.getAsJsonArray("chunks")) {
            joined.append(e.getAsString());
            parts.add(unhex(e.getAsString()));
        }
        assertEquals(serialized, joined.toString(),
                nameOf(c) + ": chunks are not the serialized bytes");
        return parts;
    }

    /** Feed every part; returns what the LAST feed answered (§5.2.4). */
    private static DecodeStatus feed(IStream in, HeaderDest dest, List<byte[]> parts)
            throws SofabException {
        DecodeStatus st = DecodeStatus.COMPLETE;
        for (byte[] part : parts) {
            st = in.feed(part, dest);
        }
        return st;
    }

    /**
     * What one case's bytes produced: the rejection they were refused with, or — when
     * the decode survived, which is the defect this block exists to catch — the status
     * the last feed answered instead.
     */
    private record Outcome(Throwable thrown, DecodeStatus status) {

        /** What to say when a rejection was expected and none arrived. */
        String survived() {
            return "decode survived and answered " + status + "; ";
        }
    }

    /** Feed every part, expecting a rejection; reports what actually happened. */
    private static Outcome rejectionFrom(IStream in, HeaderDest dest, List<byte[]> parts) {
        try {
            return new Outcome(null, feed(in, dest, parts));
        } catch (SofabException | UncheckedIOException e) {
            return new Outcome(e, null);
        }
    }

    /**
     * The rejection's category, whichever carrier it arrived on. A refusal raised
     * inside the decoder is a bare {@link SofabException}; one raised from a visitor
     * callback — which declares no checked exception — is wrapped in an
     * {@link UncheckedIOException}, and the re-raise a terminal verdict produces may
     * use either.
     */
    private static SofabError categoryOf(Throwable t) {
        if (t instanceof SofabException e) {
            return e.error();
        }
        if (t instanceof UncheckedIOException u && u.getCause() instanceof SofabException e) {
            return e.error();
        }
        throw new AssertionError("not a sofab rejection: " + t, t);
    }

    /**
     * The payload the header announced, so an in-cap control can be <b>carried through
     * to COMPLETE</b>. That is what makes it a control rather than a second rejection:
     * §5.2.1 defines {@code INCOMPLETE} as the outcome more bytes can change, and
     * these bytes change it.
     */
    private static byte[] payloadFor(HeaderDest dest, int declared) {
        byte[] payload = new byte[declared];
        if (dest.subtype == FixlenType.STRING || dest.subtype == FixlenType.BLOB) {
            Arrays.fill(payload, (byte) 0x61);
            return payload;
        }
        if (dest.kind == ArrayKind.UNSIGNED || dest.kind == ArrayKind.SIGNED) {
            // One single-byte varint per element.
            Arrays.fill(payload, (byte) 0x01);
            return payload;
        }
        throw new AssertionError("no header was announced, so there is no payload to complete");
    }

    // --- the cases ---------------------------------------------------------------

    static Stream<String> caseNames() {
        return CASES.stream().map(HeaderLimitsTest::nameOf);
    }

    /**
     * One test per case, so a port that gets a subset wrong is told <em>which</em>
     * subset — the failure list is the diagnosis. Reported per case the way
     * {@code SequenceGrowthTest} reports the growth block.
     */
    @ParameterizedTest
    @MethodSource("caseNames")
    void headerCeilingCaseMatchesItsExpectation(String name) throws SofabException {
        JsonObject c = byName(name);
        List<String> needs = requiresOf(c);
        for (String tag : needs) {
            assertTrue(KNOWN_TAGS.contains(tag), name + ": unknown capability tag " + tag);
        }
        // In THIS block an unsatisfied tag means SKIP, for every tag — not the
        // reduced-build rejection a *vector* gets. These cases already assert a
        // rejection WITH A SPECIFIC CATEGORY, so a build that cannot represent the
        // construct would reject it for an unrelated reason and appear to pass
        // while testing nothing.
        if (!SATISFIED.containsAll(needs)) {
            SKIPPED.incrementAndGet();
            Assumptions.abort(name + " requires " + needs + ", which this port does not provide");
        }
        RAN.incrementAndGet();

        JsonObject expect = c.getAsJsonObject("expect");
        String outcome = expect.get("outcome").getAsString();
        boolean terminal = expect.has("terminal") && expect.get("terminal").getAsBoolean();
        int declared = c.get("declared").getAsInt();
        HeaderDest dest = new HeaderDest(ceilingOf(c, false));
        IStream in = new IStream();
        List<byte[]> parts = chunksOf(c);

        switch (outcome) {
            case "incomplete" -> {
                assertFalse(expect.has("terminal"), name + ": `terminal` on an incomplete case");
                assertEquals(DecodeStatus.INCOMPLETE, feed(in, dest, parts), name + ": status");
                assertHeaderRead(name, c, dest, declared);
                // The control's whole point: the ceiling ADMITS this length, so
                // the payload still decodes and the message completes. A port that
                // rejected every short read would already have failed above.
                assertEquals(DecodeStatus.COMPLETE,
                        in.feed(payloadFor(dest, declared), dest),
                        name + ": the admitted payload completes");
            }
            case "limit_exceeded" -> {
                // A policy rejection of well-formed bytes, never folded into the
                // wire verdict: the same message decodes for a receiver configured
                // more loosely (§6.2.1, §6.3).
                Outcome got = rejectionFrom(in, dest, parts);
                if (got.thrown() == null) {
                    fail(name + ": " + got.survived()
                            + "want a LIMIT_EXCEEDED rejection at the length/count word");
                }
                assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(got.thrown()),
                        name + ": error category");
                assertHeaderRead(name, c, dest, declared);
                if (terminal) {
                    assertTerminal(name, in, dest, SofabError.LIMIT_EXCEEDED);
                }
            }
            case "invalid" -> {
                // The schema bound is a statement about VALIDITY: a longer payload
                // contradicts the schema both peers agreed on (MESSAGE_SPEC §7.1),
                // and §6.2.1 lets no receiver cap touch the field.
                Outcome got = rejectionFrom(in, dest, parts);
                if (got.thrown() == null) {
                    fail(name + ": " + got.survived()
                            + "want an INVALID_MSG rejection at the length word");
                }
                assertEquals(SofabError.INVALID_MSG, categoryOf(got.thrown()),
                        name + ": error category");
                assertHeaderRead(name, c, dest, declared);
                if (terminal) {
                    assertTerminal(name, in, dest, SofabError.INVALID_MSG);
                }
            }
            default -> fail(name + ": unknown expected outcome " + outcome);
        }
    }

    /**
     * State what actually ran, as the conformance runner does for the vectors: the
     * shared suite is only comparable across ports if each says how much of it it
     * executed.
     */
    @AfterAll
    static void reportWhatRan() {
        System.out.println("[test_vectors] header_limits: " + RAN.get() + " cases run, "
                + SKIPPED.get() + " skipped by requires");
        assertTrue(RAN.get() > 0, "every header_limits case was skipped; the block tested nothing");
    }

    /**
     * The decoder announced the header the case describes, so the number the ceiling
     * was compared against is the one on the wire.
     */
    private static void assertHeaderRead(String name, JsonObject c, HeaderDest dest,
            int declared) {
        assertFalse(dest.events.isEmpty(), name + ": the decoder announced no header at all");
        assertEquals(declared, dest.announced, name + ": the header the decoder announced");
        assertEquals(c.get("field_id").getAsInt(), dest.headerId, name + ": field id");
    }

    /**
     * §6.3: the rejection is terminal. A further feed <b>re-raises</b> with the same
     * category and consumes nothing — it is not an invitation to send more, which is
     * exactly what {@code INCOMPLETE} would have promised (§5.2.4).
     */
    private static void assertTerminal(String name, IStream in, HeaderDest dest,
            SofabError want) {
        List<String> before = List.copyOf(dest.events);
        Throwable again = null;
        try {
            in.feed(ONE_MORE_BYTE, dest);
        } catch (SofabException | UncheckedIOException e) {
            again = e;
        }
        if (again == null) {
            fail(name + ": a further feed must re-raise, not consume");
        }
        assertEquals(want, categoryOf(again), name + ": the re-raised category");
        assertEquals(before, dest.events,
                name + ": the further feed was decoded rather than refused");
    }

    /**
     * NEGATIVE CONTROL. Run the block again with the <b>receiver caps lifted</b> and
     * nothing else changed.
     *
     * <p>Every cap-driven rejection must fall back to {@code INCOMPLETE}, which is
     * what shows the verdicts above came from the ceiling and not from something
     * incidental to these byte strings — a decoder that rejected them for an unrelated
     * reason would reject them here too. The schema-bounded case is not part of that:
     * its ceiling is a schema bound, not a receiver policy, so lifting the caps leaves
     * it {@code INVALID} and it is asserted to stay there.
     */
    @Test
    void liftingTheReceiverCapsFallsBackToIncomplete() throws SofabException {
        int fellBack = 0;
        int keptTheirVerdict = 0;

        for (JsonObject c : CASES) {
            String name = nameOf(c);
            if (!SATISFIED.containsAll(requiresOf(c))) {
                continue;
            }
            JsonObject expect = c.getAsJsonObject("expect");
            if ("incomplete".equals(expect.get("outcome").getAsString())) {
                continue;
            }

            HeaderDest dest = new HeaderDest(ceilingOf(c, true));
            IStream in = new IStream();
            if (c.has("schema")) {
                // A schema bound is not a receiver cap and was not lifted.
                Outcome got = rejectionFrom(in, dest, chunksOf(c));
                if (got.thrown() == null) {
                    fail(name + ": " + got.survived()
                            + "the schema bound still speaks, caps lifted or not");
                }
                assertEquals(SofabError.INVALID_MSG, categoryOf(got.thrown()),
                        name + ": error category, caps lifted");
                keptTheirVerdict++;
            } else {
                assertEquals(DecodeStatus.INCOMPLETE, feed(in, dest, chunksOf(c)),
                        name + ": status with the cap lifted");
                fellBack++;
            }
        }

        System.out.println("[test_vectors] header_limits/control: " + fellBack
                + " cap rejections fell back to incomplete, " + keptTheirVerdict
                + " kept a schema verdict");
        assertTrue(fellBack > 0,
                "no rejection fell back; the ceilings were never what decided them");
        assertEquals(1, keptTheirVerdict,
                "the schema-bounded case must not be lifted by a receiver cap");
    }

    /**
     * An inventory guard: floors rather than equalities, so upstream growing the block
     * does not fail this port, while a block that SHRANK — or a control that vanished
     * — is caught.
     *
     * <p>The in-cap controls are the load-bearing half. Without them the block proves
     * nothing: a port that rejects every short read passes all six rejection cases and
     * is badly broken. test_vectors_README.md says to treat a missing control as a bug
     * in the block, so a rejection whose ceiling has no in-cap case fails here.
     */
    @Test
    void theBlockPairsEveryRejectionWithAnInCapControl() {
        assertTrue(CASES.size() >= 10,
                "header_limits carries " + CASES.size() + " cases, want at least 10");

        Set<String> groups = new HashSet<>();
        Set<String> outcomes = new HashSet<>();
        boolean anyChunked = false;
        for (JsonObject c : CASES) {
            groups.add(c.get("group").getAsString());
            outcomes.add(c.getAsJsonObject("expect").get("outcome").getAsString());
            anyChunked |= c.has("chunks");
        }
        assertTrue(groups.contains("limits/header"), "no case in group limits/header");
        for (String o : new String[] {"limit_exceeded", "invalid", "incomplete"}) {
            assertTrue(outcomes.contains(o), "no case expecting " + o);
        }
        assertTrue(anyChunked, "no case splits the length varint across a feed");

        // Which ceiling a case configures, as the key that has to be paired.
        Set<String> admitted = new LinkedHashSet<>();
        for (JsonObject c : CASES) {
            if ("incomplete".equals(c.getAsJsonObject("expect").get("outcome").getAsString())) {
                admitted.add(ceilingKey(c));
            }
        }
        for (JsonObject c : CASES) {
            JsonObject expect = c.getAsJsonObject("expect");
            if ("incomplete".equals(expect.get("outcome").getAsString())) {
                continue;
            }
            assertTrue(admitted.contains(ceilingKey(c)), nameOf(c) + " rejects on "
                    + ceilingKey(c) + " with no in-cap control on the same ceiling");
            assertTrue(expect.has("terminal") && expect.get("terminal").getAsBoolean(),
                    nameOf(c) + ": a rejection is terminal");
        }

        // THE PAIR THAT KEEPS THE TWO CATEGORIES APART: identical bytes, opposite
        // answers, and the only difference is which ceiling the case configures. A port
        // that routes both to one category passes every other case and fails this one.
        JsonObject cap = byName("header_string_over_cap");
        JsonObject bound = byName("header_string_schema_bounded");
        assertEquals(cap.get("serialized").getAsString(), bound.get("serialized").getAsString(),
                "the pair no longer carries identical bytes");
        assertEquals("limit_exceeded",
                cap.getAsJsonObject("expect").get("outcome").getAsString());
        assertEquals("invalid",
                bound.getAsJsonObject("expect").get("outcome").getAsString());
        assertTrue(cap.has("limits") && !cap.has("schema"),
                "header_string_over_cap states a receiver cap");
        assertTrue(bound.has("schema") && !bound.has("limits"),
                "header_string_schema_bounded states a schema bound");
    }

    /**
     * {@code requires} is honoured per case, and every tag the block uses is one this
     * port has been told about. An unknown tag read as "unsupported" would skip the
     * case and still report a pass.
     */
    @Test
    void everyCaseCarriesKnownRequiresTags() {
        for (JsonObject c : CASES) {
            List<String> needs = requiresOf(c);
            assertFalse(needs.isEmpty(), nameOf(c) + " carries no requires tags");
            for (String tag : needs) {
                assertTrue(KNOWN_TAGS.contains(tag), nameOf(c) + ": unknown capability tag " + tag);
            }
            // The cap cases are the ones gated on the profile capability; the
            // schema-bounded pair needs no cap and must stay runnable for a port that
            // has none.
            assertEquals(c.has("limits"), needs.contains("receiver_caps"),
                    nameOf(c) + ": receiver_caps tag vs the ceiling it states");
        }
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

    private static String ceilingKey(JsonObject c) {
        if (!c.has("limits")) {
            return "schema";
        }
        Set<String> keys = c.getAsJsonObject("limits").keySet();
        assertEquals(1, keys.size(), nameOf(c) + " states more than one cap");
        return keys.iterator().next();
    }

    private static JsonObject byName(String name) {
        return CASES.stream().filter(c -> nameOf(c).equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("the block no longer carries " + name));
    }

    /** Lowercase hex, as every {@code serialized}/{@code chunks} string in the file is. */
    private static byte[] unhex(String hex) {
        assertEquals(0, hex.length() % 2, "odd-length hex: " + hex);
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
