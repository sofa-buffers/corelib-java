/*
 * SofaBuffers Java - the benchmark tools against BENCH_SPEC.
 *
 * BENCH_SPEC is the cross-language contract for `bench` / `perf` /
 * `run_callgrind.sh`: the same workloads on the same data, printed in a grammar
 * a central harness parses into the comparison tables. Two things can silently
 * break that, and neither is visible from inside the library:
 *
 *   * a **dataset** that drifts -- the encoded sizes (the perf message's 170
 *     bytes, the blob message's 1,000,005, the composite message's 956) are the
 *     spec's own parity checks;
 *   * a **row** that goes missing or gets misspelled -- the harness matches row
 *     labels by regex, so a renamed or absent row is dropped from the table
 *     rather than reported, and a workload nobody notices is missing measures
 *     nothing.
 *
 * So the tools are run here (over a millisecond-scale loop, not the reportable
 * ~1 s one) and their output is matched against the spec's own regexes. This is
 * a format and dataset test, never a performance assertion: no timing figure is
 * checked.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sofabuffers.sofab.DecodeStatus;
import org.sofabuffers.sofab.FlushSink;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

class BenchSpecTest {

    // --- the harness's own regexes (BENCH_SPEC "Output grammar") -------------

    private static final Pattern THROUGHPUT_HEADER =
            Pattern.compile("=== SofaBuffers (.+?) throughput");
    private static final Pattern PEROP_HEADER =
            Pattern.compile("=== SofaBuffers (.+?) per-op");
    private static final Pattern ROW = Pattern.compile(
            "^(encode|decode):\\s+(u64 array \\(1000\\)|typical message|blob 1MB one-shot"
                    + "|blob 1MB streaming|blob 1MB passthrough|blob 1MB|composite skip-all"
                    + "|composite)\\s+([\\d.]+)$");

    /**
     * Every row BENCH_SPEC requires, in the order it lists them. The optional
     * {@code blob 1MB passthrough} row is absent on purpose: this port implements
     * no pass-through, and BENCH_SPEC says such a port omits the row rather than
     * printing a placeholder.
     */
    private static final List<String> REQUIRED_ROWS = List.of(
            "encode: u64 array (1000)",
            "encode: typical message",
            "encode: blob 1MB one-shot",
            "encode: blob 1MB streaming",
            "encode: composite",
            "decode: u64 array (1000)",
            "decode: typical message",
            "decode: blob 1MB",
            "decode: composite",
            "decode: composite skip-all");

    /** A measurement loop short enough that these tests check shape, not speed. */
    private static final String FAST_LOOP = "0.001";

    private static final Path SCRIPT = Path.of("bench", "run_callgrind.sh");

    // --- the workload set ----------------------------------------------------

    @Test
    void theWorkloadSetIsExactlyTheRowsBenchSpecRequires() throws IOException {
        List<String> labels = Workloads.all().stream().map(Workloads.Workload::label).toList();
        assertEquals(REQUIRED_ROWS, labels,
                "the tools measure whatever this list holds, so it is the row set");
    }

    // --- datasets ------------------------------------------------------------

    @Test
    void theU64ArrayIsTheLiteralFormula() {
        long[] src = Workloads.makeU64Array();
        assertEquals(1000, src.length);
        assertArrayEquals(IntStream.range(0, 1000).mapToLong(i -> i * 0x9E37_79B9_7F4A_7C15L)
                .toArray(), src);
    }

    @Test
    void theBlobPayloadIsTheLiteralFormula() {
        byte[] blob = Workloads.makeBlob();
        assertEquals(1_000_000, blob.length);
        byte[] want = new byte[1_000_000];
        for (int i = 0; i < want.length; i++) {
            want[i] = (byte) ((i * 0x9E37_79B9_7F4A_7C15L) & 0xFF);
        }
        assertArrayEquals(want, blob);
    }

    @Test
    void theBlobMessageIs1000005Bytes() throws IOException {
        byte[] blob = Workloads.makeBlob();
        byte[] wire = oneShotBlob(blob);

        assertEquals(1_000_005, wire.length, "a cross-port parity check, like perf's 170");
        assertEquals(Workloads.BLOB_ENCODED, wire.length);
        // BENCH_SPEC spells the framing out: a 1-byte header (id 1, FIXLEN) and a
        // 4-byte fixlen_word ((1000000 << 3) | 3), then the payload.
        assertEquals((byte) ((1 << 3) | 2), wire[0]);
        assertArrayEquals(varint(((long) 1_000_000 << 3) | 3), Arrays.copyOfRange(wire, 1, 5));
        assertArrayEquals(blob, Arrays.copyOfRange(wire, 5, wire.length));
    }

    @Test
    void thePerfMessageIs170Bytes() throws IOException {
        assertEquals(170, Perf.perfEncode(new byte[512]));
    }

    @Test
    void theCompositeMessageIs956Bytes() throws IOException {
        assertEquals(956, compositeWire().length, "this port's contribution of a parity check");
    }

    /** Each composite field is in the suite for a reason; check each is there. */
    @Test
    void theCompositeMessageCarriesWhatBenchSpecAsksFor() throws IOException {
        byte[] wire = compositeWire();
        Composite seen = new Composite();
        new IStream().feed(wire, seen);

        // Field 4 is equal to its declared default, so the encoder must not write
        // it: the ids that reach the wire are 1, 2, 3 and 130.
        assertEquals(List.of(1, 2, 3, 130), seen.topIds);

        // id 1: the wrapper array — one field header per element, element id =
        // array index, so ids 0..15 take a one-byte header and 16..63 two.
        assertEquals(IntStream.range(0, 64).boxed().toList(), seen.elementIds);
        assertEquals(IntStream.range(0, 64).mapToObj(i -> "item-" + i).toList(), seen.elements);

        // id 2: 320 UTF-8 bytes across all four sequence widths.
        assertEquals(320, seen.textTotal);
        assertArrayEquals(Workloads.COMPOSITE_TEXT.repeat(32).getBytes(UTF_8),
                seen.text.toByteArray());

        // id 3: nesting at depth 3, carrying 7 and -1.
        assertEquals(3, seen.maxDepth);
        assertEquals(List.of(7L, -1L), seen.nested);

        // id 130: the one two-byte field header in the suite, (130 << 3) | 0.
        assertEquals(0xDEAD_BEEFL, seen.twoByteHeaderField);
        assertArrayEquals(varint((130L << 3)), Arrays.copyOfRange(wire, wire.length - 7,
                wire.length - 5));
    }

    // --- the streaming rows drive the streaming API --------------------------

    /**
     * The streaming row must be the <em>same message</em>, only flushed ~245
     * times. A row driven through a 4096-byte buffer that produced anything other
     * than the one-shot bytes would make the pair's difference — the only number
     * BENCH_SPEC asks anyone to read here — meaningless.
     */
    @Test
    void theStreamingBlobEncodeProducesTheOneShotBytes() throws IOException {
        byte[] blob = Workloads.makeBlob();
        ByteArrayOutputStream flushed = new ByteArrayOutputStream();
        int[] flushes = {0};
        FlushSink capture = (data, off, len) -> {
            flushes[0]++;
            flushed.write(data, off, len);
        };

        OStream os = new OStream(new byte[Workloads.STREAM_BUFFER], 0, capture);
        os.writeBlob(1, blob);
        os.flush();

        assertArrayEquals(oneShotBlob(blob), flushed.toByteArray());
        assertEquals(245, flushes[0],
                "1,000,005 bytes through a 4096-byte window is 245 flushes");
    }

    /**
     * BENCH_SPEC: the streaming sink consumes and discards. An accumulating sink
     * would charge the streaming row a copy the one-shot row never pays.
     */
    @Test
    void theStreamingSinkConsumesAndDiscards() throws IOException {
        Workloads.Discard discard = new Workloads.Discard();
        discard.flush(new byte[] {1, 2}, 0, 2);
        discard.flush(new byte[] {3, 4}, 0, 2);
        assertEquals(1 ^ 3, discard.acc, "one byte per call, folded — nothing kept");

        for (Field f : Workloads.Discard.class.getDeclaredFields()) {
            assertTrue(f.getType().isPrimitive() || Modifier.isStatic(f.getModifiers()),
                    "a sink field that can hold bytes is somewhere to accumulate into: " + f);
        }
    }

    /** The decode row must actually stream: 4096-byte chunks, not one big feed. */
    @Test
    void theBlobDecodeIsFedInChunks() throws IOException {
        byte[] wire = oneShotBlob(Workloads.makeBlob());
        Chunks seen = new Chunks();
        IStream is = new IStream();
        DecodeStatus after = null;
        for (int off = 0; off < wire.length; off += Workloads.STREAM_BUFFER) {
            after = is.feed(wire, off, Math.min(Workloads.STREAM_BUFFER, wire.length - off), seen);
        }
        assertEquals(DecodeStatus.COMPLETE, after);
        assertEquals(1_000_000, seen.bytes, "the whole payload arrived");
        assertTrue(seen.calls >= 244,
                "a payload delivered in one piece is not a streaming decode: " + seen.calls);
    }

    // --- output grammar ------------------------------------------------------

    @Test
    void theBenchOutputMatchesTheSpecGrammar() throws IOException {
        List<String> out = List.of(runTool(Bench::main).split("\\R"));

        Matcher header = THROUGHPUT_HEADER.matcher(out.get(0));
        assertTrue(header.find(), out.get(0));
        assertEquals("Java", header.group(1), "the captured label picks the display name");
        assertEquals(List.of("Workload", "MB/s"), List.of(out.get(1).trim().split("\\s+")));
        assertTrue(out.contains("MB = 1e6 bytes. ~1s CPU-time loop per workload."), out.toString());

        List<Matcher> rows = rowsOf(out);
        assertEquals(REQUIRED_ROWS, rows.stream().map(m -> m.group(1) + ": " + m.group(2)).toList());
        for (Matcher m : rows) {
            assertTrue(Double.parseDouble(m.group(3)) > 0, m.group());
            // label left-justified to 26, value right-justified to 12, 2 decimals
            assertEquals(39, m.end(3), m.group());
            assertTrue(m.group(3).matches(".*\\.\\d\\d"), m.group(3));
        }
    }

    @Test
    void thePerfOutputMatchesTheSpecGrammar() throws IOException {
        String out = runTool(Perf::main);
        List<String> lines = List.of(out.split("\\R"));

        Matcher header = PEROP_HEADER.matcher(lines.get(0));
        assertTrue(header.find(), lines.get(0));
        assertEquals("Java", header.group(1));
        assertTrue(out.contains("--- perf: serialize"), out);
        assertTrue(out.contains("--- perf: deserialize"), out);
        assertTrue(out.strip().endsWith(
                "cycles/op tracks code cost; MB/s is this machine's throughput."), out);

        // Five value lines per section, and the JVM exposes no hardware cycle
        // counter, so BENCH_SPEC's parenthetical stands in for the number.
        assertEquals(2, count(out, "^  iterations    : \\d+$"));
        assertEquals(2, count(out, "^  message size  : 170 bytes$"));
        assertEquals(2, count(out, "^  cycles/op     : \\(.*unavailable.*\\)$"));
        assertEquals(2, count(out, "^  CPU time/op   : [\\d.]+ ns  .*$"));
        assertEquals(2, count(out, "^  throughput    : [\\d.]+ MB/s  .*$"));
    }

    /**
     * Every number in both tables goes through {@code String.format}, which is
     * locale-sensitive: on a JVM started in a comma-decimal locale an unqualified
     * {@code printf} would print {@code 1234,56}, which the harness's
     * {@code [\d.]+} does not match — the row would silently vanish from the
     * comparison table rather than fail.
     */
    @Test
    void theTablesAreLocaleIndependent() throws IOException {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            List<Matcher> rows = rowsOf(List.of(runTool(Bench::main).split("\\R")));
            assertEquals(REQUIRED_ROWS,
                    rows.stream().map(m -> m.group(1) + ": " + m.group(2)).toList());
            assertEquals(2, count(runTool(Perf::main), "^  CPU time/op   : [\\d.]+ ns  .*$"));
        } finally {
            Locale.setDefault(original);
        }
    }

    /** The table's data rows, each already matched against the harness's regex. */
    private static List<Matcher> rowsOf(List<String> out) {
        List<Matcher> rows = new ArrayList<>();
        for (String line : out) {
            if (!line.startsWith("encode:") && !line.startsWith("decode:")) {
                continue;
            }
            Matcher m = ROW.matcher(line);
            assertTrue(m.matches(), "row is unparseable by the harness regex: '" + line + "'");
            rows.add(m);
        }
        assertFalse(rows.isEmpty(), "no rows at all: " + out);
        return rows;
    }

    // --- the Callgrind rep mode ---------------------------------------------

    static List<String> workloadNames() throws IOException {
        return Workloads.all().stream().map(Workloads.Workload::name).toList();
    }

    /**
     * {@code run_callgrind.sh} drives each workload by name at two rep counts; a
     * key that no longer runs would print a dash in the table instead of failing,
     * so it is checked here.
     */
    @ParameterizedTest
    @MethodSource("workloadNames")
    void everyWorkloadRunsOneRep(String name) throws IOException {
        String err = withProperty("sofab.warmup", "1",
                () -> runCapturingStderr(() -> assertEquals(0,
                        Callgrind.run(new String[] {name, "1"}))));
        assertTrue(err.strip().matches("bytes=\\d+ sink=-?\\d+ reps=1"), err);
    }

    @Test
    void anUnknownWorkloadIsRejected() throws IOException {
        String err = runCapturingStderr(
                () -> assertEquals(2, Callgrind.run(new String[] {"encode_nothing", "1"})));
        assertTrue(err.contains("unknown workload"), err);
    }

    @Test
    void tooFewArgumentsAreRejected() throws IOException {
        String err = runCapturingStderr(
                () -> assertEquals(2, Callgrind.run(new String[] {"encode_typical"})));
        assertTrue(err.contains("usage"), err);
    }

    /**
     * The two-rep subtraction only cancels fixed cost if the compile has already
     * happened when the measured loop starts: the script pins one compile tier at
     * a fixed {@code -XX:CompileThreshold}, and a warmup below it would measure
     * the interpreter — or, landing between the two rep counts, charge a single
     * op with the entire C2 compilation. So the warmup is checked against the
     * threshold the script actually passes, not against a remembered one.
     */
    @Test
    void everyWorkloadIsCompiledBeforeItIsMeasured() throws IOException {
        Matcher threshold = Pattern.compile("-XX:CompileThreshold=(\\d+)")
                .matcher(Files.readString(SCRIPT, UTF_8));
        assertTrue(threshold.find(), "the script must pin the compile threshold it warms up past");
        int limit = Integer.parseInt(threshold.group(1));
        for (Workloads.Workload w : Workloads.all()) {
            assertTrue(Callgrind.warmupFor(w.name()) > limit,
                    w.name() + " warms up " + Callgrind.warmupFor(w.name())
                            + " times, below the script's CompileThreshold=" + limit);
        }
    }

    /**
     * The script's workload list and the tool's registry must agree — a workload
     * missing from the script is a row missing from the Ir/op table, and a label
     * only the script knows is a label the harness may not recognise.
     */
    @Test
    void theCallgrindScriptDrivesEveryWorkload() throws IOException {
        String script = Files.readString(SCRIPT, UTF_8);
        for (Workloads.Workload w : Workloads.all()) {
            assertTrue(Pattern.compile("\\b" + Pattern.quote(w.name()) + "\\b").matcher(script)
                    .find(), "bench/run_callgrind.sh never runs " + w.name());
            assertTrue(script.contains(w.label()),
                    "bench/run_callgrind.sh never labels " + w.name() + " as '" + w.label() + "'");
        }
        for (String line : script.split("\\R")) {
            assertTrue(line.stripLeading().startsWith("#") || !line.contains("passthrough"),
                    "BENCH_SPEC's optional row is omitted, not printed as a placeholder: " + line);
        }
    }

    // --- plumbing ------------------------------------------------------------

    /** The BENCH_SPEC blob message, encoded one-shot into a hand-sized buffer. */
    private static byte[] oneShotBlob(byte[] blob) throws IOException {
        byte[] buf = new byte[Workloads.BLOB_ENCODED];
        OStream os = new OStream(buf);
        os.writeBlob(1, blob);
        assertEquals(buf.length, os.bytesUsed());
        return buf;
    }

    private static byte[] compositeWire() throws IOException {
        byte[] buf = new byte[4096];
        OStream os = new OStream(buf);
        Workloads.encodeComposite(os, Workloads.makeItems(),
                Workloads.COMPOSITE_TEXT.repeat(Workloads.COMPOSITE_REPEATS));
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    private static byte[] varint(long value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        return out.toByteArray();
    }

    private static int count(String haystack, String regex) {
        return (int) Pattern.compile(regex, Pattern.MULTILINE).matcher(haystack).results().count();
    }

    @FunctionalInterface
    private interface Tool {
        void run(String[] args) throws IOException;
    }

    @FunctionalInterface
    private interface Block {
        void run() throws IOException;
    }

    /** Run a tool's {@code main} over a millisecond loop and capture its stdout. */
    private static String runTool(Tool tool) throws IOException {
        return withProperty("sofab.bench.seconds", FAST_LOOP, () -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            PrintStream original = System.out;
            System.setOut(new PrintStream(buf, true, UTF_8));
            try {
                tool.run(new String[0]);
            } finally {
                System.setOut(original);
            }
            return buf.toString(UTF_8);
        });
    }

    private static String runCapturingStderr(Block block) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        PrintStream original = System.err;
        System.setErr(new PrintStream(buf, true, UTF_8));
        try {
            block.run();
        } finally {
            System.setErr(original);
        }
        return buf.toString(UTF_8);
    }

    @FunctionalInterface
    private interface Producing<T> {
        T get() throws IOException;
    }

    private static <T> T withProperty(String key, String value, Producing<T> block)
            throws IOException {
        String previous = System.getProperty(key);
        System.setProperty(key, value);
        try {
            return block.get();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    // --- visitors ------------------------------------------------------------

    /** Records everything BENCH_SPEC says the composite message must contain. */
    private static final class Composite implements Visitor {
        final List<Integer> topIds = new ArrayList<>();
        final List<Integer> elementIds = new ArrayList<>();
        final List<String> elements = new ArrayList<>();
        final ByteArrayOutputStream text = new ByteArrayOutputStream();
        final List<Long> nested = new ArrayList<>();
        int textTotal;
        int depth;
        int maxDepth;
        long twoByteHeaderField;

        @Override public void sequenceBegin(int id) {
            if (depth == 0) {
                topIds.add(id);
            }
            depth++;
            maxDepth = Math.max(maxDepth, depth);
        }

        @Override public void sequenceEnd() {
            depth--;
        }

        @Override public void unsigned(int id, long v) {
            if (depth == 0) {
                topIds.add(id);
                twoByteHeaderField = v;
            } else {
                nested.add(v);
            }
        }

        @Override public void signed(int id, long v) {
            if (depth == 0) {
                topIds.add(id);
            } else {
                nested.add(v);
            }
        }

        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            if (depth == 1) { // a wrapper-array element
                elementIds.add(id);
                elements.add(new String(d, o, l, UTF_8));
            } else {
                if (offset == 0) {
                    topIds.add(id);
                    textTotal = total;
                }
                text.write(d, o, l);
            }
        }
    }

    /** Counts the pieces a chunked blob decode arrives in. */
    private static final class Chunks implements Visitor {
        int calls;
        long bytes;

        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
            calls++;
            bytes += l;
        }
    }
}
