/*
 * SofaBuffers Java - the README's stated facts are pinned to the files that
 * carry them (corelib-java#79).
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
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * CORELIB_PLAN §9: "every fact, command, version number, dependency, feature flag
 * and API name the README states must match the code as it stands today". A
 * README fact is only true on the day it is written unless something checks it,
 * and each of these guards is a fact that had already gone stale: the JDK matrix,
 * the coverage-gating claim, the benchmark tool inventory, the decode-side
 * strict-UTF-8 mechanism, and the §9.2 badge row.
 *
 * <p>Each guard reads the file that <em>owns</em> the fact — {@code ci.yml},
 * {@code pom.xml}, {@code bench/}, {@code Utf8.java} — rather than a second copy
 * of the prose, so the README cannot drift away from the repository without a
 * red build.
 */
class ReadmeFactsTest {

    private static final Path README = Path.of("README.md");
    private static final Path CI = Path.of(".github", "workflows", "ci.yml");
    private static final Path POM = Path.of("pom.xml");
    private static final Path BENCH_SCRIPTS = Path.of("bench");
    private static final Path BENCH_MAINS =
            Path.of("src", "main", "java", "org", "sofabuffers", "sofab", "bench");
    private static final Path SRC = Path.of("src");
    /** This guard names the forbidden spelling in order to forbid it. */
    private static final Path SELF =
            Path.of("src", "test", "java", "org", "sofabuffers", "sofab", "ReadmeFactsTest.java");

    // --- 1: the decode-side strict-UTF-8 mechanism ---------------------------

    /**
     * §6.4's decode-side check is {@link Utf8#valid}: generated code validates the
     * assembled payload with the validator this corelib ships and raises
     * {@code INVALID_MSG}. No doc — README or source comment — may attribute it to
     * a {@code REPORTing CharsetDecoder}, a mechanism this repository neither
     * ships nor emits, and every {@code Utf8.x} the README names must be a public
     * method of {@link Utf8}.
     */
    @Test
    void docsNameTheUtf8MechanismThisRepoActuallyShips() throws IOException {
        List<String> hits = new ArrayList<>();
        for (Path src : docSources()) {
            String[] lines = read(src).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains("CharsetDecoder")) {
                    hits.add(src + ":" + (i + 1));
                }
            }
        }
        assertTrue(hits.isEmpty(),
                "the decode-side strict-UTF-8 check is Utf8.valid, not a REPORTing "
                        + "CharsetDecoder; these still say otherwise: " + hits);

        String readme = read(README);
        assertTrue(readme.contains("Utf8.valid"),
                "the README must name the validator generated code calls (CORELIB_PLAN §6.4)");

        String utf8 = read(Path.of("src", "main", "java", "org", "sofabuffers", "sofab", "Utf8.java"));
        Matcher named = Pattern.compile("Utf8\\.(\\w+)").matcher(readme);
        while (named.find()) {
            String method = named.group(1);
            assertTrue(utf8.contains(" " + method + "("),
                    "README names Utf8." + method + "(), which Utf8.java does not declare");
        }
    }

    // --- 2: the CI JDK matrix ------------------------------------------------

    /**
     * The Requirements section states which JDKs are supported and built; the
     * matrix in {@code ci.yml} is what is actually built. They must be the same
     * set, and the pom's {@code maven.compiler.release} must be its minimum.
     */
    @Test
    void requirementsListExactlyTheJdksCiBuilds() throws IOException {
        Matcher m = Pattern.compile("java:\\s*\\[([^\\]]*)\\]").matcher(read(CI));
        assertTrue(m.find(), "ci.yml must define a build-test java matrix");
        Set<String> matrix = new TreeSet<>();
        for (String raw : m.group(1).split(",")) {
            matrix.add(raw.trim().replace("'", "").replace("\"", ""));
        }

        Set<String> stated = new TreeSet<>();
        Matcher versions = Pattern.compile("\\b(\\d+)\\b").matcher(section("### Requirements"));
        while (versions.find()) {
            stated.add(versions.group(1));
        }
        assertEquals(matrix, stated,
                "README '### Requirements' must name exactly the JDKs ci.yml builds");

        Matcher release = Pattern.compile("<maven\\.compiler\\.release>(\\d+)<").matcher(read(POM));
        assertTrue(release.find(), "pom.xml must set maven.compiler.release");
        assertEquals(new TreeSet<>(matrix).first(), release.group(1),
                "the lowest JDK in the matrix must be the release the pom targets");
    }

    // --- 3: the coverage-gating claim ---------------------------------------

    /**
     * JaCoCo is wired for {@code prepare-agent} + {@code report} only; a
     * <em>gate</em> is the {@code check} goal with rules. The README may claim one
     * exactly when the pom enforces one — a coverage number that is merely
     * reported and badged must not be described as gating the build.
     */
    @Test
    void theCoverageGateClaimMatchesThePom() throws IOException {
        boolean claimed = Pattern.compile(
                        "(?i)coverage[^.\\n]{0,80}\\bgat(e|ed|es|ing)\\b"
                                + "|\\bgat(e|ed|es|ing)\\b[^.\\n]{0,80}coverage")
                .matcher(read(README)).find();
        boolean enforced = read(POM).replaceAll("\\s+", "").contains("<goal>check</goal>");
        assertEquals(enforced, claimed,
                claimed
                        ? "the README says coverage is gated, but pom.xml runs jacoco "
                                + "prepare-agent + report with no check goal and no rules"
                        : "pom.xml enforces a jacoco check rule the README does not mention");
    }

    // --- 4: the benchmark tool inventory ------------------------------------

    /**
     * §10/§13 require {@code perf}, {@code bench} <em>and</em>
     * {@code run_callgrind.sh} to be present and runnable, and §9 requires the
     * README to document what the repo ships. Every bench script, and every bench
     * main not driven by one, must appear in the Benchmarks section — and the
     * count the prose states must be the count of tools it documents.
     */
    @Test
    void everyBenchmarkToolIsDocumented() throws IOException {
        String bench = section("## Benchmarks");
        List<String> scripts = new ArrayList<>();
        try (Stream<Path> files = Files.list(BENCH_SCRIPTS)) {
            files.filter(p -> p.toString().endsWith(".sh")).sorted()
                    .forEach(p -> scripts.add(p.toString()));
        }
        assertFalse(scripts.isEmpty(), "bench/ must ship run_callgrind.sh (CORELIB_PLAN §13)");

        Set<String> tools = new LinkedHashSet<>();
        for (String script : scripts) {
            assertTrue(bench.contains(script),
                    "README '## Benchmarks' must document " + script + " (CORELIB_PLAN §10, §13)");
            tools.add(script);
        }

        try (Stream<Path> mains = Files.list(BENCH_MAINS)) {
            for (Path main : mains.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (!read(main).contains("static void main")) {
                    continue;
                }
                String cls = main.getFileName().toString().replace(".java", "");
                String fqn = "org.sofabuffers.sofab.bench." + cls;
                boolean drivenByAScript = false;
                for (String script : scripts) {
                    drivenByAScript |= read(Path.of(script)).contains(fqn);
                }
                if (drivenByAScript) {
                    continue; // documented through the script that runs it
                }
                assertTrue(bench.contains(fqn),
                        "README '## Benchmarks' must document the runnable " + fqn);
                tools.add(fqn);
            }
        }

        Matcher counted = Pattern.compile("(?i)\\b(one|two|three|four|five|six)\\s+"
                + "(?:runnable\\s+)?tools?\\b").matcher(bench);
        assertTrue(counted.find(), "the Benchmarks section must say how many tools it documents");
        Map<String, Integer> words =
                Map.of("one", 1, "two", 2, "three", 3, "four", 4, "five", 5, "six", 6);
        assertEquals(tools.size(), words.get(counted.group(1).toLowerCase(Locale.ROOT)).intValue(),
                "the Benchmarks section counts " + counted.group(1).toLowerCase(Locale.ROOT)
                        + " tools but documents " + tools);
    }

    // --- 5: the §9.2 badge row ----------------------------------------------

    /**
     * §9.2 asks for CI, coverage and a <b>Docs</b> badge, and every sibling port
     * spells the third one {@code Docs}. A per-language label costs a reader
     * scanning the family the one badge they are looking for.
     */
    @Test
    void theDocsBadgeIsSpelledLikeTheRestOfTheFamily() throws IOException {
        String readme = read(README);
        Matcher docs = Pattern.compile("\\[!\\[Docs\\]\\([^)]*\\)\\]\\(([^)]*)\\)").matcher(readme);
        assertTrue(docs.find(), "README must carry a 'Docs' badge (CORELIB_PLAN §9.2)");
        assertEquals("https://sofa-buffers.github.io/corelib-java/", docs.group(1),
                "the Docs badge must link to the published API documentation");
        assertFalse(Pattern.compile("\\[!\\[Javadoc\\]").matcher(readme).find(),
                "the family spells this badge 'Docs'; 'Javadoc' is out of family (§9.2)");
    }

    // --- README plumbing ------------------------------------------------------

    /** The text of a section, from its heading to the next heading of any level. */
    private static String section(String heading) throws IOException {
        String readme = read(README);
        int start = readme.indexOf(heading);
        assertTrue(start >= 0, "README must have a '" + heading + "' section");
        Matcher next = Pattern.compile("\\n#{1,4} ").matcher(readme);
        int end = next.find(start + heading.length()) ? next.start() : readme.length();
        return readme.substring(start, end);
    }

    /** Everything a reader of this repository can be misled by: the README and the sources. */
    private static List<Path> docSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        sources.add(README);
        try (Stream<Path> java = Files.walk(SRC)) {
            java.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.equals(SELF))
                    .sorted().forEach(sources::add);
        }
        return sources;
    }

    private static String read(Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }
}
