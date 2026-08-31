/*
 * SofaBuffers Java - "the schema bounds this field" and "I forgot to state a cap"
 * are not the same value (CORELIB_PLAN 6.2.1).
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * CORELIB_PLAN §6.2.1 gives a receiver cap exactly two possible provenances, and
 * they are opposites: either the <b>schema</b> bounds the field — "they MUST NOT be
 * applied to a field the schema already bounds. There the schema bound governs and
 * its violation is INVALID" — or the <b>deployment</b> stated a number, and "there
 * is no unset state and no unlimited mode". A codec "MUST NOT read an omitted
 * argument as <em>unlimited</em>".
 *
 * <p>This library used to spell both with one {@code long} and a negative sentinel
 * ({@code Sofab.SCHEMA_BOUNDED = -1}), guarded as {@code max >= 0 && …}. A caller
 * asserting <em>the schema bounds this field</em> and a caller who had simply never
 * configured a cap therefore handed over the identical bit pattern, and the second
 * one decoded with no cap, no report and no way for a reader to tell the two apart.
 * One sentinel cannot carry two opposite meanings.
 *
 * <p><b>These tests are written through reflection on purpose.</b> The defect is
 * the <em>shape</em> of the API, so the test has to be able to compile against the
 * shape that had it — which is what lets it fail on the unfixed library instead of
 * merely failing to build. Every assertion below holds on the API as it is now and
 * breaks on the one that carried the sentinel.
 */
class ForgottenCapTest {

    /** The one type that says which of §6.2.1's two rules bounds a field. */
    private static final String BOUND = "org.sofabuffers.sofab.Bound";

    /** The calls that compare a receiver cap; each must take the bound as a type. */
    private static final List<String> CAP_TAKING_METHODS = List.of(
            "PayloadAcc.string", "PayloadAcc.blob",
            "Seq.reserveRow", "Seq.reserveRowBytes", "Seq.reserveRowShorts",
            "Seq.reserveRowInts", "Seq.reserveRowLongs", "Seq.reserveRowFloats",
            "Seq.reserveRowDoubles");

    // --- the shape: a cap is a type, not a number ----------------------------

    /**
     * Every call that compares a receiver cap takes it as the dedicated type, whose
     * only numeric constructor is {@code receiver(long)}. That is what makes "the
     * schema bounds this" unrepresentable as a number: there is no value of any
     * numeric type a caller can pass to mean it, so a cap that was never configured
     * cannot be spelled the same way.
     */
    @Test
    void everyCapArrivesAsABoundAndNotAsANumber() throws Exception {
        Class<?> bound = boundClass();
        for (String qualified : CAP_TAKING_METHODS) {
            String owner = qualified.substring(0, qualified.indexOf('.'));
            String name = qualified.substring(qualified.indexOf('.') + 1);
            List<Method> found = new ArrayList<>();
            for (Method m : Class.forName("org.sofabuffers.sofab." + owner).getDeclaredMethods()) {
                if (m.getName().equals(name) && Modifier.isPublic(m.getModifiers())) {
                    found.add(m);
                }
            }
            assertTrue(!found.isEmpty(), qualified + " must exist");
            for (Method m : found) {
                Class<?>[] params = m.getParameterTypes();
                assertSame(bound, params[params.length - 1],
                        qualified + " must take its bound as " + BOUND + ", not as a bare number: "
                                + "a number cannot say \"the schema bounds this field\" without "
                                + "reserving a value that a caller who stated nothing also passes");
            }
        }
    }

    /**
     * No constant anywhere in this library is a negative number. A negative is not a
     * length, a count or an id, so the only thing one can be here is a sentinel
     * standing for "not a value" — which is the shape this test exists to keep out.
     * The format ceilings ({@code ID_MAX}, {@code ARRAY_MAX}, {@code MAX_DEPTH},
     * {@code MIN_OUTPUT_BUFFER}) are all real magnitudes and pass unchanged.
     */
    @Test
    void noConstantOfThisLibraryIsANegativeSentinel() throws Exception {
        for (Class<?> c : publicClasses()) {
            for (Field f : c.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) {
                    continue;
                }
                Class<?> t = f.getType();
                if (t != long.class && t != int.class && t != short.class && t != byte.class) {
                    continue;
                }
                f.setAccessible(true);
                long value = ((Number) f.get(null)).longValue();
                assertTrue(value >= 0,
                        c.getSimpleName() + "." + f.getName() + " = " + value
                                + ": a negative constant in this library is a sentinel, and a "
                                + "sentinel sharing a parameter with real limits is how "
                                + "\"the schema bounds this\" and \"I stated nothing\" became "
                                + "one bit pattern (§6.2.1)");
            }
        }
    }

    /**
     * The schema statement is an object with no number in it, and the only way to
     * produce a bound carrying one is the receiver factory. So the two answers do
     * not share a representation at all — not merely different values of one.
     */
    @Test
    void theSchemaStatementCarriesNoNumber() throws Exception {
        Class<?> bound = boundClass();
        Object schema = bound.getField("SCHEMA_BOUNDED").get(null);
        assertNotNull(schema);
        assertSame(bound, schema.getClass());

        for (Field f : bound.getDeclaredFields()) {
            if (Modifier.isPublic(f.getModifiers()) && Modifier.isStatic(f.getModifiers())) {
                assertSame(bound, f.getType(),
                        "Bound." + f.getName() + " must be a Bound, never a number a caller "
                                + "could pass somewhere a cap belongs");
            }
        }
        for (Method m : bound.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) {
                continue;
            }
            assertEquals("receiver", m.getName(),
                    "receiver(long) is the only public factory: every other one would be a "
                            + "second way to reach the comparison");
        }
    }

    // --- the two ways a cap gets forgotten, both diagnosed -------------------

    /**
     * The values a forgotten cap actually arrives as are refused where the cap is
     * stated, with §6.3's {@code InvalidArgument}. {@code 0} is what Java writes
     * into an unassigned {@code long} or {@code int} field — the idiomatic "never
     * configured" — and a negative is the retired sentinel. Neither is a policy
     * anybody chose, and reading either as one is exactly the defect.
     */
    @Test
    void theValuesAForgottenCapArrivesAsAreRefused() throws Exception {
        Method receiver = boundClass().getMethod("receiver", long.class);
        for (long forgotten : new long[] {0L, -1L, -8L, Long.MIN_VALUE}) {
            InvocationTargetException e = assertThrows(InvocationTargetException.class,
                    () -> receiver.invoke(null, forgotten),
                    "Bound.receiver(" + forgotten + ") must not become a limit");
            assertInstanceOf(IllegalArgumentException.class, e.getCause(),
                    "a cap that was never configured is a defect in the call (§6.3 "
                            + "InvalidArgument), not a limit to raise and not malformed bytes");
        }
        assertNotNull(receiver.invoke(null, 1L), "1 is the smallest real cap");
    }

    /**
     * A bound that was never handed over at all is refused as {@code ARGUMENT} on
     * every call that compares one — never read as "no cap". §6.2.1: a codec "MUST
     * NOT read an omitted argument as <em>unlimited</em>", and §6.3 keeps the
     * category off {@code LIMIT_EXCEEDED}, which "would promise a limit to raise
     * that was never configured", and off {@code INVALID_MSG}, which would call
     * well-formed bytes malformed.
     */
    @Test
    void anOmittedBoundIsReportedAndNotObeyed() throws Exception {
        byte[] p = new byte[64];
        java.util.Arrays.fill(p, (byte) 'a');

        assertEquals(SofabError.ARGUMENT, categoryOf(
                () -> call(PayloadAcc.class, "string", new PayloadAcc(),
                        p.length, 0, p, 0, p.length, null)));
        assertEquals(SofabError.ARGUMENT, categoryOf(
                () -> call(PayloadAcc.class, "blob", new PayloadAcc(),
                        p.length, 0, p, 0, p.length, null)));

        List<int[]> rows = new ArrayList<>();
        assertEquals(SofabError.ARGUMENT, categoryOf(
                () -> call(Seq.class, "reserveRowInts", null, rows, 3, 1, null)));
        assertEquals(0, rows.size(), "refused, so nothing was reserved or grown");

        List<List<String>> wrapper = new ArrayList<>();
        assertEquals(SofabError.ARGUMENT, categoryOf(
                () -> call(Seq.class, "reserveRow", null, wrapper, 3, null)));
        assertEquals(0, wrapper.size());
    }

    /**
     * And the two stated answers still do opposite things, which is the point of
     * telling them apart: the schema statement compares nothing here (the caller
     * has already rejected a breach as {@code INVALID}), while a stated cap refuses
     * the same payload as {@code LIMIT_EXCEEDED}.
     */
    @Test
    void theTwoStatedAnswersAreNotInterchangeable() throws Exception {
        byte[] p = new byte[64];
        java.util.Arrays.fill(p, (byte) 'a');
        Object schema = boundClass().getField("SCHEMA_BOUNDED").get(null);
        Object cap = boundClass().getMethod("receiver", long.class).invoke(null, 8L);

        assertEquals(64, ((String) call(PayloadAcc.class, "string", new PayloadAcc(),
                p.length, 0, p, 0, p.length, schema)).length());
        assertEquals(SofabError.LIMIT_EXCEEDED, categoryOf(
                () -> call(PayloadAcc.class, "string", new PayloadAcc(),
                        p.length, 0, p, 0, p.length, cap)));
    }

    // --- helpers -------------------------------------------------------------

    /** The bound type, by name: absent on the API this test is a regression against. */
    private static Class<?> boundClass() throws Exception {
        return Class.forName(BOUND);
    }

    /** Every public class of the library package, benchmarks excluded. */
    private static List<Class<?>> publicClasses() throws Exception {
        Path dir = Path.of("src", "main", "java", "org", "sofabuffers", "sofab");
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String simple = f.getFileName().toString().replace(".java", "");
                out.add(Class.forName("org.sofabuffers.sofab." + simple));
            }
        }
        assertTrue(out.size() > 5, "the package should have been scanned");
        return out;
    }

    /** Invoke one of the cap-taking calls, unwrapping reflection's own wrapper. */
    private static Object call(Class<?> owner, String name, Object self, Object... args)
            throws Exception {
        for (Method m : owner.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == args.length) {
                try {
                    return m.invoke(self, args);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException r) {
                        throw r;
                    }
                    throw e;
                }
            }
        }
        throw new AssertionError("no " + owner.getSimpleName() + "." + name
                + " taking " + args.length + " arguments");
    }

    /** The {@link SofabError} the call refuses with. */
    private static SofabError categoryOf(ThrowingCall call) {
        UncheckedIOException e = assertThrows(UncheckedIOException.class, call::run);
        return assertInstanceOf(SofabException.class, e.getCause()).error();
    }

    /** A call that may throw anything, for {@link #categoryOf}. */
    private interface ThrowingCall {
        void run() throws Exception;
    }
}
