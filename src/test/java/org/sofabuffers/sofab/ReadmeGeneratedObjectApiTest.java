/*
 * SofaBuffers Java - the README's generated-object example is real, runnable
 * code and uses only the closed name set (corelib-java#78).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The generated-object layer has a <em>closed</em> set of names (CORELIB_PLAN
 * §6.1.1): {@code encode} / {@code decode} / {@code tryDecode} for the one-shot
 * pair and {@code serialize} / {@code decoder} for the streaming one, and the
 * spelling a port must not invent is named explicitly — {@code marshal},
 * {@code unmarshal}, {@code to_bytes}, {@code serialize_to} and friends. A
 * README that teaches one of those costs every reader of this port a name that
 * exists nowhere else in the family <em>and</em> nowhere in the generated code.
 *
 * <p>Two guards, because the defect had two halves. The name guard reads the
 * user-facing prose — the README and the published Javadoc — and fails on any
 * excluded spelling. The example guard pins §9.5's Generator example to the code
 * below: every line of the README snippet must appear in this test source, which
 * compiles and runs it, so the example cannot document a method the corelib does
 * not have, and cannot drop the streaming {@code serialize} / {@code decoder()}
 * half that §9.5 requires alongside the one-shot pair.
 */
class ReadmeGeneratedObjectApiTest {

    private static final Path README = Path.of("README.md");
    private static final Path MAIN = Path.of("src", "main", "java", "org", "sofabuffers", "sofab");
    private static final Path SELF =
            Path.of("src", "test", "java", "org", "sofabuffers", "sofab",
                    "ReadmeGeneratedObjectApiTest.java");

    /**
     * The spellings §6.1.1 closes out, in every casing a Java port might reach for.
     * Shared with {@link ReadmeStructureTest} so the two README suites cannot
     * disagree about what the closed set excludes.
     */
    static final Pattern EXCLUDED = Pattern.compile(
            "\\b(marshal\\w*|unmarshal\\w*|serialize_to|serializeTo|to_bytes|toBytes"
                    + "|from_bytes|fromBytes|decode_from|decodeFrom|decode_into|decodeInto)\\b",
            Pattern.CASE_INSENSITIVE);

    // --- guard 1: no excluded name in anything a user reads ------------------

    /**
     * Neither the README nor the Javadoc may name an operation §6.1.1 excludes.
     * Both are published — the README on the repository front page, the Javadoc on
     * the docs site — so a name invented in either is a name a developer learns
     * for this language only.
     */
    @Test
    void noUserFacingDocTeachesAnExcludedName() throws IOException {
        List<String> hits = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        sources.add(README);
        try (Stream<Path> java = Files.walk(MAIN)) {
            java.filter(p -> p.toString().endsWith(".java")).sorted().forEach(sources::add);
        }
        for (Path src : sources) {
            String[] lines = Files.readString(src, StandardCharsets.UTF_8).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                Matcher m = EXCLUDED.matcher(lines[i]);
                while (m.find()) {
                    hits.add(src + ":" + (i + 1) + ": " + m.group());
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "CORELIB_PLAN §6.1.1 closes the generated-object name set; these read as an "
                        + "excluded spelling: " + hits);
    }

    // --- guard 2: the README's Generator example is this code ----------------

    /**
     * §9.5 wants the Generator example to show both halves: the one-shot
     * {@code encode()} / {@code decode()} helpers <em>and</em> the streaming
     * {@code serialize} / {@code decoder()} path. Each name is checked in the
     * section that has to carry it.
     */
    @Test
    void theGeneratorSectionShowsBothHalves() throws IOException {
        String section = generatorSection();
        for (String required : new String[] {
                "public void serialize(OStream os)",   // streaming out (§5.1)
                "public byte[] encode()",              // one-shot, wrapping it
                "public static Point decode(byte[] data)",
                "public static Decoder decoder()",     // streaming in (§5.2)
                "DecodeStatus feed(byte[] chunk",      // fed in chunks of any size
        }) {
            assertTrue(section.contains(required),
                    "README '### Code generator' must show " + required + " (CORELIB_PLAN §9.5)");
        }
    }

    /**
     * Every code line of that example appears in this test source, which compiles
     * and runs it below. That is what keeps the example honest: a member the
     * corelib does not have cannot survive here, and neither can one that drifts
     * out of the snippet.
     */
    @Test
    void theGeneratorExampleIsTheCodeThisTestRuns() throws IOException {
        String self = normalize(Files.readString(SELF, StandardCharsets.UTF_8));
        List<String> missing = new ArrayList<>();
        for (String line : codeBlock(generatorSection()).split("\n")) {
            String want = normalizeLine(line);
            if (want.isEmpty() || want.startsWith("import ")) {
                continue;
            }
            if (!self.contains(want)) {
                missing.add(want);
            }
        }
        assertTrue(missing.isEmpty(),
                "the README's generated-code example must be the code this test runs; "
                        + "these lines are not in " + SELF + ": " + missing);
    }

    // --- the example itself, verbatim ----------------------------------------

    // generated by: sofabgen --lang java
    static final class Point implements Visitor {
        long x, y;
        static final int MAX_SIZE = 32;

        // streaming out: write the fields into a stream the caller owns
        public void serialize(OStream os) throws IOException {
            os.writeSigned(1, x);
            os.writeSigned(2, y);
        }

        // one-shot: serialize into a MAX_SIZE buffer, hand back an exact-size copy
        public byte[] encode() {
            byte[] buf = new byte[MAX_SIZE];
            OStream os = new OStream(buf);
            try { serialize(os); } catch (IOException e) { throw new RuntimeException(e); }
            return Arrays.copyOf(buf, os.bytesUsed());
        }

        // one-shot: the same streaming decode, over one complete buffer
        public static Point decode(byte[] data) throws SofabException {
            Point p = new Point();
            new IStream().feed(data, p);
            return p;
        }

        // streaming in: a reader fed chunks of any size
        public static Decoder decoder() { return new Decoder(); }

        static final class Decoder {
            private final Point p = new Point();
            private final IStream is = new IStream();

            public DecodeStatus feed(byte[] chunk, int off, int len) throws SofabException {
                is.feed(chunk, off, len, p);
                return is.status();          // COMPLETE / INCOMPLETE; INVALID throws
            }

            public Point message() { return p; }
        }

        @Override public void signed(int id, long v) {
            switch (id) { case 1 -> x = v; case 2 -> y = v; default -> { } }
        }
    }

    /** The one-shot half of the example, run as written. */
    @Test
    void theOneShotPairRoundTrips() throws SofabException {
        Point p = new Point(); p.x = 3; p.y = 4;
        byte[] wire = p.encode();
        Point got = Point.decode(wire);                 // got.x == 3, got.y == 4

        assertEquals(3, got.x);
        assertEquals(4, got.y);
    }

    /** The streaming half, fed one byte at a time — the split must not matter. */
    @Test
    void theStreamingHalfRoundTripsInChunksOfOne() throws SofabException {
        Point p = new Point(); p.x = 3; p.y = 4;
        byte[] wire = p.encode();

        // the streaming half: the same message, one byte at a time
        Point.Decoder dec = Point.decoder();
        for (byte b : wire) dec.feed(new byte[] { b }, 0, 1);
        Point streamed = dec.message();                 // streamed.x == 3, streamed.y == 4

        assertEquals(3, streamed.x);
        assertEquals(4, streamed.y);
        assertEquals(DecodeStatus.COMPLETE, dec.feed(new byte[0], 0, 0));
    }

    // --- README plumbing ------------------------------------------------------

    /** The text of the {@code ### Code generator} section, up to the next heading. */
    private static String generatorSection() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        int start = readme.indexOf("### Code generator");
        assertTrue(start >= 0, "README must have a '### Code generator' section (§9.5)");
        int end = readme.indexOf("\n## ", start);
        return end < 0 ? readme.substring(start) : readme.substring(start, end);
    }

    /** The single fenced java block inside a section. */
    private static String codeBlock(String section) {
        int open = section.indexOf("```java");
        assertTrue(open >= 0, "the '### Code generator' section must carry a java example");
        int body = section.indexOf('\n', open) + 1;
        int close = section.indexOf("```", body);
        assertTrue(close > body, "unterminated code fence in the '### Code generator' section");
        return section.substring(body, close);
    }

    /** Drop a trailing line comment, collapse runs of whitespace. */
    private static String normalizeLine(String line) {
        return line.replaceAll("//.*$", "").trim().replaceAll("\\s+", " ");
    }

    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append(normalizeLine(line)).append('\n');
        }
        return sb.toString();
    }
}
