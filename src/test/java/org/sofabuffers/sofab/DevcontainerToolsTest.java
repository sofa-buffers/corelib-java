/*
 * SofaBuffers Java - the shipped environment can run the shipped tools
 * (corelib-java#80).
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * CORELIB_PLAN §13 asks for {@code perf}, {@code bench} and
 * {@code run_callgrind.sh} to be "present and runnable", and §11 asks the
 * {@code .devcontainer/} to be a ready-to-use environment for <em>this</em>
 * repository. Present is not runnable: {@code bench/run_callgrind.sh} is the one
 * tool of the three that produces the machine-independent number, and it shells
 * out to {@code valgrind}, which the image did not install — so the shipped
 * environment could not run the shipped tool.
 *
 * <p>The guard is derived, not hard-coded: a bench script declares its hard
 * prerequisites in its own {@code command -v <tool>} preflight, and every tool so
 * declared must be installed by the image. A future
 * script that adds a preflight for a tool the image lacks fails here too.
 */
class DevcontainerToolsTest {

    private static final Path DOCKERFILE = Path.of(".devcontainer", "Dockerfile");
    private static final Path BENCH = Path.of("bench");

    /** Tools whose apt package is not spelled like the command it installs. */
    private static final Map<String, String> PACKAGE_OF = Map.of("mvn", "maven");

    /**
     * Every tool a bench script refuses to start without must be installed by the
     * devcontainer image.
     */
    @Test
    void theImageInstallsEveryToolTheBenchScriptsRequire() throws IOException {
        Map<String, Path> required = benchPrereqs();
        assertFalse(required.isEmpty(),
                "no bench script declares a `command -v` prerequisite — the parse below "
                        + "would then guard nothing");

        Set<String> installed = aptPackages();
        for (Map.Entry<String, Path> prereq : required.entrySet()) {
            String pkg = PACKAGE_OF.getOrDefault(prereq.getKey(), prereq.getKey());
            assertTrue(installed.contains(pkg),
                    prereq.getValue() + " cannot start without `" + prereq.getKey()
                            + "`, which " + DOCKERFILE + " does not install (apt packages: "
                            + installed + ")");
        }
    }

    /**
     * The preflight the two guards above read is a real one: with the tool absent
     * the script stops on the spot — before the Maven build and before the first
     * measured run — and says what to install. Without this, the parse could pass
     * over a comment.
     */
    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void theCallgrindScriptStopsOnTheMissingToolBeforeDoingAnyWork() throws Exception {
        Path script = BENCH.resolve("run_callgrind.sh");
        assertTrue(Files.isRegularFile(script), script + " must exist (CORELIB_PLAN §13)");

        // A PATH holding only what the script needs *before* its preflight, so the
        // preflight is what fails and not the prologue.
        Path bin = Files.createTempDirectory("sofab-nopath-bin");
        for (String tool : List.of("dirname", "pwd")) {
            Path real = which(tool);
            if (real != null) {
                Files.createSymbolicLink(bin.resolve(tool), real);
            }
        }

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());
        pb.environment().put("PATH", bin.toString());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the preflight must fail fast: " + out);

        assertEquals(1, p.exitValue(), "expected a clean refusal, got: " + out);
        assertTrue(out.contains("valgrind"), "the refusal must name the missing tool: " + out);
        assertFalse(out.contains("compiling"),
                "the script must refuse before it builds anything: " + out);

        try (Stream<Path> scratch = Files.walk(bin)) {
            scratch.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort scratch cleanup
                }
            });
        }
    }

    // --- plumbing -------------------------------------------------------------

    /** Every {@code command -v <tool>} preflight in {@code bench/}, mapped to its script. */
    private static Map<String, Path> benchPrereqs() throws IOException {
        Map<String, Path> tools = new LinkedHashMap<>();
        Pattern guard = Pattern.compile("command -v ([A-Za-z0-9_.+-]+)");
        try (Stream<Path> files = Files.list(BENCH)) {
            for (Path script : files.filter(p -> p.toString().endsWith(".sh")).sorted().toList()) {
                Matcher m = guard.matcher(read(script));
                while (m.find()) {
                    tools.putIfAbsent(m.group(1), script);
                }
            }
        }
        return tools;
    }

    /** The packages the Dockerfile's {@code apt-get install} layers name. */
    private static Set<String> aptPackages() throws IOException {
        String joined = read(DOCKERFILE).replaceAll("\\\\\\s*\\n", " ");
        Set<String> packages = new TreeSet<>();
        Matcher install = Pattern.compile("apt-get install([^\\n&]*)").matcher(joined);
        while (install.find()) {
            for (String token : install.group(1).trim().split("\\s+")) {
                if (token.isEmpty() || token.startsWith("-")) {
                    continue;
                }
                if (token.startsWith("$")) {
                    break; // a shell substitution: nothing after it is a literal package
                }
                packages.add(token);
            }
        }
        return packages;
    }

    private static Path which(String tool) {
        for (String dir : List.of("/usr/bin", "/bin", "/usr/local/bin")) {
            Path candidate = Path.of(dir, tool);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
