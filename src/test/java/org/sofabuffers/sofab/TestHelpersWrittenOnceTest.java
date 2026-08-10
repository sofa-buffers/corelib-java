/*
 * SofaBuffers Java - the test suite's own helpers are written once
 * (corelib-java#77).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sofabuffers.sofab.common.Decode.CHUNKS;
import static org.sofabuffers.sofab.common.Decode.errorOf;
import static org.sofabuffers.sofab.common.Decode.errorOfChunked;
import static org.sofabuffers.sofab.common.Decode.verdict;
import static org.sofabuffers.sofab.common.Wire.bytes;
import static org.sofabuffers.sofab.common.Wire.concat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The helpers every decode test needs — a byte-vector literal, the two
 * "what error does this message raise" wrappers and the three-valued verdict —
 * live in {@code org.sofabuffers.sofab.common} and nowhere else.
 *
 * <p>They were copy-pasted into sixteen classes before this guard existed, which
 * is how the suite ended up asserting the same malformed vector on the same
 * surface from three places under three names: a helper that is cheap to
 * re-declare makes a case cheap to re-assert. Counting the declarations is the
 * check that keeps both from re-growing, and it is a check no behavioural test
 * can make.
 */
class TestHelpersWrittenOnceTest {

    /** Where the shared helpers live; every other test source must only call them. */
    private static final Path COMMON =
            Path.of("src", "test", "java", "org", "sofabuffers", "sofab", "common");

    private static final Path TEST_ROOT =
            Path.of("src", "test", "java", "org", "sofabuffers", "sofab");

    /** Helper name -> the declaration pattern that would re-introduce a copy of it. */
    private static final Map<String, Pattern> SHARED_HELPERS = Map.of(
            "bytes(int...)", Pattern.compile("byte\\[\\]\\s+bytes\\s*\\(\\s*int\\s*\\.\\.\\."),
            "concat(byte[]...)", Pattern.compile("byte\\[\\]\\s+concat\\s*\\(\\s*byte\\[\\]"),
            "errorOf(byte[])", Pattern.compile("SofabError\\s+errorOf\\s*\\("),
            "errorOfChunked(byte[])", Pattern.compile("SofabError\\s+errorOfChunked\\s*\\("),
            "verdict(byte[], Visitor, int)", Pattern.compile("String\\s+verdict\\s*\\("),
            "CHUNKS", Pattern.compile("int(\\[\\])?\\s+CHUNKS\\s*="));

    /** Every {@code *.java} under the test tree, outside the shared {@code common} package. */
    private static List<Path> testSourcesOutsideCommon() throws IOException {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(TEST_ROOT)) {
            for (Path p : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (!p.startsWith(COMMON)) {
                    out.add(p);
                }
            }
        }
        out.sort(Path::compareTo);
        return out;
    }

    @Test
    void noTestClassRedeclaresASharedHelper() throws IOException {
        List<Path> sources = testSourcesOutsideCommon();
        assertFalse(sources.isEmpty(), "found no test sources to scan");

        List<String> offenders = new ArrayList<>();
        for (Path src : sources) {
            String text = Files.readString(src, StandardCharsets.UTF_8);
            SHARED_HELPERS.forEach((name, declaration) -> {
                if (declaration.matcher(text).find()) {
                    offenders.add(src.getFileName() + " re-declares " + name);
                }
            });
        }
        offenders.sort(String::compareTo);
        assertEquals(List.of(), offenders,
                "these helpers live in org.sofabuffers.sofab.common; call them, do not copy them");
    }

    /** The shared copies exist and behave, so the scan above is not vacuous. */
    @Test
    void theSharedHelpersAreTheOnesTheSuiteUses() {
        assertArrayEqualsAsInts(new int[] { 0x05, 0x01, 0x20 }, bytes(0x05, 0x01, 0x20));
        assertArrayEqualsAsInts(new int[] { 0x01, 0x02, 0x03 },
                concat(bytes(0x01), bytes(0x02, 0x03)));

        // 02 04 is a reserved fixlen sub-type: INVALID on both decode surfaces,
        // and "R:INVALID_MSG" through the verdict reduction.
        byte[] reserved = bytes(0x02, 0x04);
        assertEquals(SofabError.INVALID_MSG, errorOf(reserved));
        assertEquals(SofabError.INVALID_MSG, errorOfChunked(reserved));
        for (int chunk : CHUNKS) {
            assertEquals("R:INVALID_MSG", verdict(reserved, new Visitor() { }, chunk));
        }
        assertTrue(CHUNKS.size() >= 2,
                "the verdict harness must cover a whole feed and at least one split");
    }

    /**
     * The malformed-vector table asserts each message once. Two rows carrying the
     * same bytes are the duplication this issue removed, in its table-driven form.
     */
    @Test
    void theMalformedVectorTableHasNoDuplicateVectors() {
        Set<String> seen = new HashSet<>();
        List<String> duplicates = new ArrayList<>();
        DecoderErrorsTest.malformedVectors().forEach(args -> {
            Object[] row = args.get();
            String hex = hex((byte[]) row[1]);
            if (!seen.add(hex)) {
                duplicates.add(row[0] + " repeats a vector already in the table");
            }
        });
        assertEquals(List.of(), duplicates);
        assertTrue(seen.size() >= 25, "the table lost rows: only " + seen.size() + " vectors");
    }

    private static void assertArrayEqualsAsInts(int[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i] & 0xFF, "byte " + i);
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
