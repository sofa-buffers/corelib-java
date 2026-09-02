/*
 * SofaBuffers Java - the BENCH_SPEC workload set, defined once.
 *
 * BENCH_SPEC is a cross-language contract: the same messages, built from the
 * same literal values, driven the same way, so the numbers from every port are
 * directly comparable. That only holds if a workload is defined in exactly one
 * place -- Bench (throughput) and Callgrind (instructions/op) must measure the
 * same code, or their tables describe two different libraries.
 *
 * So the datasets and the one-operation bodies live here, in BENCH_SPEC's own
 * order, and the two tools are thin drivers over {@link #all()}: Bench times
 * every row, Callgrind repeats one row by name. The row labels the harness
 * parses are part of a workload's definition too, so a renamed row cannot get
 * out of step with the code that produced it.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab.bench;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.sofabuffers.sofab.ArrayKind;
import org.sofabuffers.sofab.FlushSink;
import org.sofabuffers.sofab.IStream;
import org.sofabuffers.sofab.OStream;
import org.sofabuffers.sofab.Visitor;

final class Workloads {

    private Workloads() {
    }

    /** Elements in the {@code u64 array (1000)} dataset. */
    static final int N = 1000;

    /**
     * The one magic number in BENCH_SPEC's datasets: the {@code u64} array holds
     * {@code i * GOLDEN} and the blob payload its low byte, so both derive from
     * the same constant in every port.
     */
    static final long GOLDEN = 0x9E37_79B9_7F4A_7C15L;

    /** {@code blob 1MB} payload length, so MB/s reads against {@code MB = 1e6}. */
    static final int BLOB_LEN = 1_000_000;

    /**
     * Encoded size of the {@code blob 1MB} message: a one-byte header
     * {@code (1 << 3) | 2}, a four-byte {@code fixlen_word}
     * {@code (1000000 << 3) | 3} and the payload. A cross-port parity check, as
     * the perf message's 170 is.
     */
    static final int BLOB_ENCODED = BLOB_LEN + 5;

    /**
     * Buffer size for the streaming {@code blob 1MB} rows -- fixed by BENCH_SPEC
     * at 4096 rather than taken from this port's own sizing, so the rows stay
     * comparable across languages. {@code MIN_OUTPUT_BUFFER} does not enter into
     * it: it is at most 20, so 4096 always satisfies it.
     */
    static final int STREAM_BUFFER = 4096;

    /**
     * One cycle of the composite string field: {@code a}, {@code ä}, {@code €}
     * and U+1D11E -- 1-, 2-, 3- and 4-byte UTF-8, ten bytes in all. Written as
     * escapes so the bytes cannot depend on how a tool re-encodes this file.
     */
    static final String COMPOSITE_TEXT = "a\u00E4\u20AC\uD834\uDD1E";

    /** Repetitions of {@link #COMPOSITE_TEXT}, giving 320 UTF-8 bytes. */
    static final int COMPOSITE_REPEATS = 32;

    /** Elements in the composite message's wrapper array. */
    static final int COMPOSITE_ITEMS = 64;

    /** The {@code typical} message's {@code u16} array, hoisted out of the op. */
    private static final short[] TYPICAL_ARRAY = {10, 20, 30, 40};

    /** One operation of a workload; the returned value is fed to a blackhole. */
    @FunctionalInterface
    interface Body {
        long run() throws IOException;
    }

    /**
     * One measurable workload.
     *
     * @param name  the key {@code bench/run_callgrind.sh} drives it by
     * @param label the row label BENCH_SPEC's output grammar prescribes
     * @param bytes encoded size of the message, the row's MB/s numerator
     * @param body  exactly one operation
     */
    record Workload(String name, String label, int bytes, Body body) {
    }

    /** Decode sink that folds every value into a checksum (defeats elision). */
    static final class Checksum implements Visitor {
        long acc;

        @Override public void unsigned(int id, long v) {
            acc += v ^ id;
        }

        @Override public void signed(int id, long v) {
            acc += v ^ id;
        }

        @Override public void fp32(int id, float v) {
            acc += Float.floatToRawIntBits(v);
        }

        @Override public void fp64(int id, double v) {
            acc += Double.doubleToRawLongBits(v);
        }

        @Override public void string(int id, int total, int offset, byte[] d, int o, int l) {
            acc += l;
        }

        @Override public void blob(int id, int total, int offset, byte[] d, int o, int l) {
            acc += l;
        }

        @Override public void arrayBegin(int id, ArrayKind kind, int count) {
            /* no-op */
        }
    }

    /**
     * Sink for the streaming {@code blob 1MB} row. BENCH_SPEC is explicit that it
     * <b>consumes and discards</b>: accumulating the bytes would charge the
     * streaming row a copy the one-shot row never pays, and I/O is not
     * deterministic under Callgrind. Folding one byte per call is the minimum
     * that keeps the call from being optimised away. It never calls
     * {@code bufferSet}, so it is a <em>copying</em> sink and the encoder resumes
     * in the same buffer (CORELIB_PLAN §5.1) -- which is what the row measures.
     */
    static final class Discard implements FlushSink {
        byte acc;

        @Override public void flush(byte[] data, int offset, int length) {
            if (length > 0) {
                acc ^= data[offset];
            }
        }
    }

    /**
     * {@code decode: composite skip-all}: a visitor that overrides nothing. In a
     * push port that is what "materialize nothing" means -- the decoder still
     * walks every header, count and payload length, but no value reaches a
     * destination. Its distance from {@code decode: composite} is what
     * not-decoding is worth.
     */
    private static final Visitor SKIP_ALL = new Visitor() {
    };

    static long[] makeU64Array() {
        long[] a = new long[N];
        for (int i = 0; i < N; i++) {
            a[i] = i * GOLDEN;
        }
        return a;
    }

    /** {@code b[i] = (i * GOLDEN) & 0xFF}, exactly 1,000,000 bytes. */
    static byte[] makeBlob() {
        byte[] b = new byte[BLOB_LEN];
        for (int i = 0; i < BLOB_LEN; i++) {
            b[i] = (byte) (i * GOLDEN);
        }
        return b;
    }

    /** {@code "item-0" .. "item-63"}, the composite wrapper array's elements. */
    static String[] makeItems() {
        String[] items = new String[COMPOSITE_ITEMS];
        for (int i = 0; i < items.length; i++) {
            items[i] = "item-" + i;
        }
        return items;
    }

    /** A small mixed message: scalars, a float, a short string, an array, a sequence. */
    static void encodeTypical(OStream os) throws IOException {
        os.writeUnsigned(1, 0xDEAD_BEEFL);
        os.writeSigned(2, -12345);
        os.writeBoolean(3, true);
        os.writeFp32(4, 3.14159f);
        os.writeString(5, "sofab");
        os.writeArrayUnsigned(6, TYPICAL_ARRAY);
        os.writeSequenceBeginLazy(7);
        os.writeUnsigned(1, 99);
        os.writeSigned(2, -7);
        os.writeSequenceEnd();
    }

    /**
     * The {@code composite} message: every encoder path the flat datasets miss.
     *
     * <ul>
     * <li>id 1 -- the suite's only <b>wrapper array</b> (MESSAGE_SPEC §5.1): one
     * field header per element, element id = array index, so ids 0-15 take a
     * one-byte header and 16-63 a two-byte one.</li>
     * <li>id 2 -- 320 UTF-8 bytes covering 1-, 2-, 3- and 4-byte sequences, so
     * the §6.4 validator runs on something that is not ASCII (and, in a UTF-16
     * runtime such as this one, on a surrogate pair).</li>
     * <li>id 3 -- nesting at depth 3, so the lazy hold-back run grows past the
     * single level {@code typical} and {@code perf} reach.</li>
     * <li>id 4 -- a struct equal to its declared default: every child is then
     * equal to its own default and omitted, so the sequence never receives
     * content and {@code writeSequenceEnd} discards the held-back frame
     * (MESSAGE_SPEC §2). The one field in the suite the encoder must
     * <em>not</em> write.</li>
     * <li>id 130 -- the suite's only two-byte field header, {@code (130 << 3) | 0}.</li>
     * </ul>
     */
    static void encodeComposite(OStream os, String[] items, String text) throws IOException {
        os.writeSequenceBeginLazy(1);
        for (int i = 0; i < items.length; i++) {
            os.writeString(i, items[i]);
        }
        os.writeSequenceEnd();

        os.writeString(2, text);

        os.writeSequenceBeginLazy(3);
        os.writeSequenceBeginLazy(1);
        os.writeSequenceBeginLazy(1);
        os.writeUnsigned(1, 7);
        os.writeSequenceEnd();
        os.writeSequenceEnd();
        os.writeSigned(2, -1);
        os.writeSequenceEnd();

        os.writeSequenceBeginLazy(4);
        os.writeSequenceEnd();

        os.writeUnsigned(130, 0xDEAD_BEEFL);
    }

    /** Encode once into a scratch buffer of {@code room} bytes -> the exact wire bytes. */
    private static byte[] wireOf(int room, Encoding what) throws IOException {
        byte[] buf = new byte[room];
        OStream os = new OStream(buf);
        what.encode(os);
        return Arrays.copyOf(buf, os.bytesUsed());
    }

    @FunctionalInterface
    private interface Encoding {
        void encode(OStream os) throws IOException;
    }

    /**
     * Every workload, in the order BENCH_SPEC's output grammar lists it.
     *
     * <p>All setup -- building the datasets, encoding the decode inputs and
     * allocating the encode targets -- happens here, so an operation is the codec
     * call and nothing else. {@code encode: blob 1MB passthrough} is BENCH_SPEC's
     * one optional row and is absent: this port implements no pass-through
     * (CORELIB_PLAN §5.1 makes it a MAY), so the row is omitted entirely rather
     * than printed as a placeholder.
     *
     * @return the workload list
     * @throws IOException if the setup encodes fail (they cannot: the buffers fit)
     */
    static List<Workload> all() throws IOException {
        long[] src = makeU64Array();
        byte[] blob = makeBlob();
        String[] items = makeItems();
        String text = COMPOSITE_TEXT.repeat(COMPOSITE_REPEATS);

        byte[] u64Wire = wireOf(N * 11 + 16, os -> os.writeArrayUnsigned(1, src));
        byte[] typWire = wireOf(256, Workloads::encodeTypical);
        byte[] blobWire = wireOf(BLOB_ENCODED, os -> os.writeBlob(1, blob));
        byte[] compWire = wireOf(4096, os -> encodeComposite(os, items, text));

        // Reused encode targets: allocation belongs to the setup, not to the op.
        byte[] encU64Out = new byte[N * 11 + 16];
        byte[] encTypOut = new byte[256];
        byte[] encBlobOut = new byte[BLOB_ENCODED]; // sized by hand, per BENCH_SPEC
        byte[] encBlobScratch = new byte[STREAM_BUFFER];
        byte[] encCompOut = new byte[compWire.length];
        Discard discard = new Discard();

        List<Workload> ws = new ArrayList<>();
        ws.add(new Workload("encode_u64_array", "encode: u64 array (1000)", u64Wire.length, () -> {
            OStream os = new OStream(encU64Out);
            os.writeArrayUnsigned(1, src);
            return os.bytesUsed();
        }));
        ws.add(new Workload("encode_typical", "encode: typical message", typWire.length, () -> {
            OStream os = new OStream(encTypOut);
            encodeTypical(os);
            return os.bytesUsed();
        }));
        // The floor: one contiguous write into a buffer that holds the whole
        // message, with no sink and so no flush logic at all. (Its *instruction*
        // count is not a floor on this port: the payload lands at offset 5, and
        // the JIT's copy stub takes a byte-wise path for an unaligned destination
        // that Callgrind counts at ~1 Ir per byte. See the README.)
        ws.add(new Workload("encode_blob_oneshot", "encode: blob 1MB one-shot", BLOB_ENCODED, () -> {
            OStream os = new OStream(encBlobOut);
            os.writeBlob(1, blob);
            return os.bytesUsed();
        }));
        // The same bytes through ~245 flushes of a 4096-byte buffer. The gap to
        // the row above is the divisible-run path (CORELIB_PLAN §5.1) -- the only
        // place in this suite where it runs at all.
        ws.add(new Workload("encode_blob_streaming", "encode: blob 1MB streaming", BLOB_ENCODED, () -> {
            OStream os = new OStream(encBlobScratch, 0, discard);
            os.writeBlob(1, blob);
            return os.flush() + discard.acc;
        }));
        ws.add(new Workload("encode_composite", "encode: composite", compWire.length, () -> {
            OStream os = new OStream(encCompOut);
            encodeComposite(os, items, text);
            return os.bytesUsed();
        }));
        ws.add(new Workload("decode_u64_array", "decode: u64 array (1000)", u64Wire.length, () -> {
            Checksum c = new Checksum();
            new IStream().feed(u64Wire, c);
            return c.acc;
        }));
        ws.add(new Workload("decode_typical", "decode: typical message", typWire.length, () -> {
            Checksum c = new Checksum();
            new IStream().feed(typWire, c);
            return c.acc;
        }));
        // Fed in 4096-byte chunks: the streaming decode surface, not one feed of
        // a megabyte.
        ws.add(new Workload("decode_blob", "decode: blob 1MB", blobWire.length, () -> {
            Checksum c = new Checksum();
            IStream is = new IStream();
            for (int off = 0; off < blobWire.length; off += STREAM_BUFFER) {
                is.feed(blobWire, off, Math.min(STREAM_BUFFER, blobWire.length - off), c);
            }
            return c.acc;
        }));
        ws.add(new Workload("decode_composite", "decode: composite", compWire.length, () -> {
            Checksum c = new Checksum();
            new IStream().feed(compWire, c);
            return c.acc;
        }));
        ws.add(new Workload("decode_composite_skip", "decode: composite skip-all", compWire.length, () -> {
            IStream is = new IStream();
            return is.feed(compWire, SKIP_ALL).ordinal();
        }));
        return ws;
    }
}
