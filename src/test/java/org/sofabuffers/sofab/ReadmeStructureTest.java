/*
 * SofaBuffers Java - the README's shape is the family shape (CORELIB_PLAN §9).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * CORELIB_PLAN §9 fixes the <em>shape</em> of every corelib README so that a reader
 * who knows one port can navigate any other: the same top-level sections, in the
 * same order, with no invented ones. {@link ReadmeFactsTest} pins what the README
 * <em>says</em> to the files that own those facts; this suite pins how it is
 * <em>built</em>, so that shortening or reorganising it cannot quietly drop a
 * required chapter, an example §9.5 lists, or the one number §9.6 puts in the
 * reader's hands.
 *
 * <p><b>§6.4 is deliberately not checked here.</b> The strict-UTF-8 knob
 * ({@code SOFAB_STRICT_UTF8}) is mandatory only for <em>byte-container</em> string
 * targets. Java's {@code String} is a Unicode string type, which §6.4 says "cannot
 * hold non-UTF-8 bytes … for them the option is a no-op and they MAY omit it
 * entirely (documented as always-ON)". This port omits it, so what is checked
 * instead is that the README documents the always-strict behaviour.
 *
 * <p>The README path is overridable with {@code -Dsofab.readme=...} purely so the
 * guard can be negative-tested against a deliberately broken copy; the default is
 * the repository's own README.
 */
class ReadmeStructureTest {

    private static final Path README =
            Path.of(System.getProperty("sofab.readme", "README.md"));

    /** §9's section list, in §9's order. */
    private static final List<String> REQUIRED_SECTIONS = List.of(
            "SofaBuffers Java library",  // §9.2
            "Why this design",           // §9.3
            "Usage",                     // §9.5
            "Memory handling",           // §9.6
            "Build & test",              // §9.7
            "Benchmarks");               // §9.8

    /**
     * §9.5's six examples and the Usage subsection that carries each. One heading
     * may carry more than one item: the OStream wrapper is what both encode
     * examples drive, the IStream wrapper what the chunked decode example drives.
     */
    private static final List<String[]> REQUIRED_EXAMPLES = List.of(
            new String[] {"simple encode", "Serialize", "OStream"},
            new String[] {"OStream (the writer-sink wrapper)", "Serialize", "OStream"},
            new String[] {"streaming a message larger than the buffer",
                    "Serialize stream", "FlushSink"},
            new String[] {"simple decode", "Deserialize", "IStream"},
            new String[] {"IStream (the push-feed wrapper)", "Deserialize stream", "IStream"},
            new String[] {"generator", "Code generator", "encode()"});

    // --- §9: the top-level sections ------------------------------------------

    /**
     * §9: "Do not change the section ordering and do not invent new top-level
     * sections". A port-specific chapter belongs under the chapter it refines, as a
     * subsection — never as a seventh {@code ##}.
     */
    @Test
    void topLevelSectionsAreExactlyThePrescribedListInOrder() throws IOException {
        assertEquals(REQUIRED_SECTIONS, headings(2),
                "the '## ' sections must be CORELIB_PLAN §9's list, in §9's order");
    }

    // --- §9.1: the generic header block --------------------------------------

    /** §9.1: centered logo, title, tagline, org link — in that order, before §9.2. */
    @Test
    void theHeaderBlockIsTheFamilyHeaderBlock() throws IOException {
        String readme = read();
        int[] previous = {-1};
        for (String required : new String[] {
                "<p align=\"center\"><img src=\"assets/sofabuffers_logo.png\"",
                "\n# SofaBuffers\n",
                "<b>Structured Objects For Anyone</b><br>",
                "<i>... so optimized, feels amazing.</i>",
                "https://github.com/sofa-buffers)",
        }) {
            int at = readme.indexOf(required);
            assertTrue(at >= 0, "README header block (§9.1) must carry: " + required.trim());
            assertTrue(at > previous[0],
                    "§9.1's header block is ordered; out of place: " + required.trim());
            previous[0] = at;
        }
        assertTrue(previous[0] < readme.indexOf("\n## "),
                "the whole §9.1 header block precedes the first '## ' section");
    }

    // --- §9.2: the badge row --------------------------------------------------

    /**
     * §9.2 opens the first section with badges "CI, coverage, and a Docs badge" in
     * that order. Extra badges are allowed (this port also renders branch
     * coverage); the three the plan names must appear, in the plan's order, inside
     * the opening section.
     */
    @Test
    void theBadgeBlockCarriesCiCoverageAndDocsInThatOrder() throws IOException {
        String opening = section("## " + REQUIRED_SECTIONS.get(0));
        int ci = opening.indexOf("[![CI]");
        int coverage = opening.indexOf("[![Coverage]");
        int docs = opening.indexOf("[![Docs]");
        assertTrue(ci >= 0, "§9.2: the opening section must carry a CI badge");
        assertTrue(coverage >= 0, "§9.2: the opening section must carry a coverage badge");
        assertTrue(docs >= 0, "§9.2: the opening section must carry a Docs badge");
        assertTrue(ci < coverage && coverage < docs,
                "§9.2 orders the badges CI, coverage, Docs");
    }

    // --- §9.4: no API-documentation chapter ----------------------------------

    /**
     * §9.4: the Docs badge is the single entry point to the API reference. No
     * heading, at any level, may open a second one.
     */
    @Test
    void noApiDocumentationSectionAtAnyLevel() throws IOException {
        Pattern banned = Pattern.compile(
                "^#{1,6}\\s+(source documentation|api documentation|api docs|api reference"
                        + "|reference documentation|javadoc|generated documentation)\\s*$",
                Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
        Matcher m = banned.matcher(read());
        assertFalse(m.find(),
                "§9.4 forbids an API-documentation section; found: "
                        + (m.hitEnd() ? "" : m.group()));
    }

    // --- §9.5: the Usage examples --------------------------------------------

    /**
     * §9.5 lists the examples the Usage chapter shows. Each is carried by a named
     * subsection with runnable code in it; renaming or dropping one is what this
     * catches.
     */
    @Test
    void usageShowsEveryExampleThePlanLists() throws IOException {
        String usage = section("## Usage");
        int previous = -1;
        for (String[] example : REQUIRED_EXAMPLES) {
            String heading = "### " + example[1];
            int at = usage.indexOf(heading + "\n");
            assertTrue(at >= 0,
                    "§9.5's '" + example[0] + "' example must live under '" + heading + "'");
            assertTrue(at >= previous, "Usage subsections must keep §9.5's order: " + heading);
            previous = at;

            String body = subsection(usage, heading);
            assertTrue(body.contains("```java"),
                    heading + " must show runnable code (§9.5: \"concise, runnable examples\")");
            assertTrue(body.contains(example[2]),
                    heading + " must show " + example[2] + " for §9.5's '" + example[0] + "'");
        }
    }

    // --- §6.4: always-strict UTF-8 (the knob itself is legitimately absent) ---

    /**
     * See the class comment: a Unicode-string port MAY omit {@code SOFAB_STRICT_UTF8},
     * but §6.4 requires the always-ON behaviour to be documented — a reader must not
     * have to guess whether a knob exists.
     */
    @Test
    void theAlwaysStrictUtf8BehaviourIsDocumented() throws IOException {
        assertTrue(Pattern.compile("(?i)always\\s+strict").matcher(read()).find(),
                "§6.4: a Unicode-string port omitting the strict-UTF-8 option must "
                        + "document that it is always strict");
    }

    // --- §9.6: MIN_OUTPUT_BUFFER lives in the memory chapter ------------------

    /**
     * §9.6: "State the port's MIN_OUTPUT_BUFFER (§5.1) here" — here being the memory
     * chapter, "the section they read to find out who allocates what". The value
     * stated must be the constant the library exposes.
     */
    @Test
    void minOutputBufferIsStatedInTheMemoryChapter() throws IOException {
        String memory = section("## Memory handling");
        int at = memory.indexOf("MIN_OUTPUT_BUFFER");
        assertTrue(at >= 0,
                "§9.6: '## Memory handling' must state the port's MIN_OUTPUT_BUFFER");
        String claim = memory.substring(at, Math.min(memory.length(), at + 200));
        assertTrue(Pattern.compile("\\b" + Sofab.MIN_OUTPUT_BUFFER + "\\b").matcher(claim).find(),
                "the memory chapter must state MIN_OUTPUT_BUFFER's value ("
                        + Sofab.MIN_OUTPUT_BUFFER + "), as Sofab declares it");
    }

    // --- §6.1.1: the closed generated-object name set -------------------------

    /**
     * §6.1.1 closes the set of generated-object names. The pattern is
     * {@link ReadmeGeneratedObjectApiTest#EXCLUDED}, so the two suites cannot
     * disagree about what is excluded; that suite scans the published Javadoc as
     * well, this one re-checks the README after any restructuring.
     */
    @Test
    void theReadmeNamesNoExcludedGeneratedObjectSpelling() throws IOException {
        List<String> hits = new ArrayList<>();
        String[] lines = read().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            Matcher m = ReadmeGeneratedObjectApiTest.EXCLUDED.matcher(lines[i]);
            while (m.find()) {
                hits.add(README + ":" + (i + 1) + ": " + m.group());
            }
        }
        assertTrue(hits.isEmpty(),
                "CORELIB_PLAN §6.1.1 closes the generated-object name set; the README "
                        + "reads as an excluded spelling at: " + hits);
    }

    // --- links ----------------------------------------------------------------

    /** Every in-document link must land on a heading this document actually has. */
    @Test
    void everyInDocumentAnchorResolves() throws IOException {
        Set<String> slugs = new LinkedHashSet<>();
        for (int level = 1; level <= 6; level++) {
            for (String heading : headings(level)) {
                slugs.add(slug(heading));
            }
        }
        List<String> dangling = new ArrayList<>();
        Matcher links = Pattern.compile("]\\(#([^)]*)\\)").matcher(read());
        while (links.find()) {
            if (!slugs.contains(links.group(1).toLowerCase(Locale.ROOT))) {
                dangling.add("#" + links.group(1));
            }
        }
        assertTrue(dangling.isEmpty(),
                "these in-document links resolve to no heading: " + dangling
                        + " (headings present: " + slugs + ")");
    }

    // --- README plumbing ------------------------------------------------------

    /** The heading texts at one level, in document order. */
    private static List<String> headings(int level) throws IOException {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("^#{" + level + "} (?!#)(.*)$", Pattern.MULTILINE)
                .matcher(read());
        while (m.find()) {
            found.add(m.group(1).trim());
        }
        return found;
    }

    /** A chapter, from its heading to the next heading of the same or a higher level. */
    private static String section(String heading) throws IOException {
        String readme = read();
        int start = readme.indexOf("\n" + heading + "\n");
        assertTrue(start >= 0, "README must have a '" + heading + "' section");
        int level = heading.indexOf(' ');
        Matcher next = Pattern.compile("\\n#{1," + level + "} (?!#)").matcher(readme);
        int end = next.find(start + heading.length()) ? next.start() : readme.length();
        return readme.substring(start, end);
    }

    /** One subsection of an already-extracted chapter. */
    private static String subsection(String chapter, String heading) {
        int start = chapter.indexOf(heading + "\n");
        int next = chapter.indexOf("\n### ", start + heading.length());
        return next < 0 ? chapter.substring(start) : chapter.substring(start, next);
    }

    /** GitHub's heading-to-anchor slug. */
    private static String slug(String heading) {
        return heading.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9 _-]", "")
                .replace(' ', '-');
    }

    private static String read() throws IOException {
        return Files.readString(README, StandardCharsets.UTF_8);
    }
}
