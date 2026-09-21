/*
 * SofaBuffers Java - which rule bounds one field: the schema's, or the
 * receiver's configured cap.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

import java.io.UncheckedIOException;

/**
 * Which of the two rules of CORELIB_PLAN §6.2.1 bounds <b>this</b> field: the
 * <b>schema's</b> {@code count}/{@code maxlen}, or a <b>receiver</b> cap the
 * deployment configured — and, with it, the number and the verdict a breach earns.
 *
 * <p>The two are mutually exclusive — §6.2.1: receiver limits "<b>MUST NOT</b> be
 * applied to a field the schema already bounds. There the schema bound governs and
 * its violation is {@code INVALID}" — and they are the only two answers. This type
 * exists so that a call site has to give one of them, and so that <em>saying which</em>
 * is part of the value rather than a convention beside it:
 *
 * <ul>
 *   <li>{@link #schema(long)} — the schema declares the ceiling {@code n}. The call
 *       it is handed to compares against it, and a breach is
 *       {@link SofabError#INVALID_MSG} (MESSAGE_SPEC §7.1): the bytes contradict the
 *       schema both peers agreed on. It never reports
 *       {@link SofabError#LIMIT_EXCEEDED}.
 *   <li>{@link #receiver(long)} — the schema declares none, and this is the
 *       deployment's {@code max_dyn_string_len} / {@code max_dyn_blob_len} /
 *       {@code max_dyn_array_count} for the field. A breach is
 *       {@link SofabError#LIMIT_EXCEEDED}: the bytes are well-formed and the same
 *       message decodes under a looser cap.
 *   <li>{@link #SCHEMA_BOUNDED} — the schema declares a {@code maxlen} for a
 *       {@code string}/{@code blob} the caller has <em>already</em> enforced at the
 *       same header, so a {@link PayloadAcc} length check has no second number to
 *       compare. It carries no number, and so it cannot bound an array
 *       <em>index</em>: every {@link Seq} reservation refuses it as
 *       {@link SofabError#ARGUMENT} and wants {@link #schema(long)} instead.
 * </ul>
 *
 * <p><b>One implementation of each rule.</b> The {@link Seq} reservations take the
 * schema {@code count} as {@code Bound.schema(count)} and compare it themselves,
 * exactly where they compare a receiver cap, so a caller that passes a bound does
 * <b>not</b> also guard in front of the call. §6.2.1's "never both" then holds by
 * construction: one argument, one number, one verdict. The index is compared
 * <em>before</em> anything is created or grown (CORELIB_PLAN §7.2 item 8).
 *
 * <p><b>Why a type and not a number.</b> These answers used to share one
 * {@code long} parameter, with a negative sentinel for "the schema bounds this" —
 * so a caller who had <em>forgotten</em> to state a cap and a caller asserting the
 * schema's rule handed over the identical bit pattern, and the forgotten one decoded
 * uncapped and unreported. Here the verdict travels with the number, and the only
 * ways to make a bound carrying a number are the two named factories.
 *
 * <p><b>Both "forgot" values are diagnosed, not obeyed.</b> Java writes an
 * unassigned {@code long} field as {@code 0} and the retired sentinel was
 * {@code -1}; both factories refuse both with {@link SofabError#ARGUMENT}, and a
 * {@code null} reference reaching one of the calls is refused the same way rather
 * than read as <em>no cap</em>. §6.2.1 admits "no unset state and no unlimited
 * mode", and a codec "<b>MUST NOT</b> read an omitted argument as
 * <em>unlimited</em>".
 *
 * <p><b>This library still holds no limit.</b> A {@code Bound} is built by the
 * caller out of the caller's number, used for one comparison and not retained;
 * nothing here defaults one, falls back to one, or clamps to one.
 * {@link Sofab#ARRAY_MAX} and {@link Sofab#ID_MAX} are <em>format</em> ceilings,
 * and §6.2.1 forbids presenting one as a receiver cap — so there is deliberately no
 * {@code Bound} that means "the format ceiling".
 *
 * <p><b>Where the value comes from, and what it costs.</b> A cap is a constant of
 * the deployment and a schema bound a constant of the schema, so generated code
 * builds its {@code Bound}s once, into {@code static final} fields, and passes them
 * per call:
 *
 * <pre>{@code
 * private static final Bound CAP_DYN_ARRAY_COUNT = Bound.receiver(MAX_DYN_ARRAY_COUNT);
 * private static final Bound TAGS_COUNT = Bound.schema(8);
 *
 * // schema `count: 8` -- compared inside, INVALID_MSG at index 8 and above
 * Seq.placeElem(m.tags, id, "", s, TAGS_COUNT);
 * // schema declares no count -- compared inside, LIMIT_EXCEEDED at the cap
 * Seq.placeElem(m.notes, id, "", s, CAP_DYN_ARRAY_COUNT);
 * }</pre>
 *
 * <p>Nothing is allocated per call or per message on that path, and every constant
 * is a {@code static final} object with {@code final} fields, which the JIT folds
 * into the comparison it guards.
 *
 * <p>Instances are immutable and shareable.
 */
public final class Bound {

    /** {@link #SCHEMA_BOUNDED}: a statement with no number. */
    private static final byte STATED = 0;
    /** {@link #schema(long)}: the schema's bound, INVALID_MSG past it. */
    private static final byte SCHEMA = 1;
    /** {@link #receiver(long)}: the deployment's cap, LIMIT_EXCEEDED past it. */
    private static final byte RECEIVER = 2;

    /**
     * The number, or {@code -1} for {@link #SCHEMA_BOUNDED}. Only the two factories
     * produce a non-negative value, and both refuse everything below {@code 1}, so a
     * non-negative {@code max} here is always a number a caller deliberately stated.
     *
     * <p>The {@code -1} is also what keeps the index comparison a single compare:
     * every index is {@code >= -1}, so {@link #SCHEMA_BOUNDED} always reaches the
     * out-of-line rejection, which refuses it as {@link SofabError#ARGUMENT}.
     */
    private final long max;

    /** Which rule {@link #max} states: {@link #STATED}, {@link #SCHEMA} or {@link #RECEIVER}. */
    private final byte kind;

    /**
     * The schema bounds this {@code string} or {@code blob} and the caller has
     * already enforced its {@code maxlen} at the same header, as
     * {@link Sofab#invalid} (MESSAGE_SPEC §7.1), so §6.2.1 forbids a receiver cap
     * on it and there is no second number for {@link PayloadAcc} to compare.
     *
     * <p><b>It is not "unlimited", and it is not "unset".</b> It is a statement
     * about which rule applies, made by the layer that knows the schema. Passing it
     * for a field the schema leaves <em>unbounded</em> lets the sender decide how
     * much this process holds; that is a defect in the <b>call</b>, not a mode this
     * library offers.
     *
     * <p><b>It bounds no array index.</b> An index has a reservation to ride, and
     * the reservation compares the schema {@code count} itself when handed
     * {@link #schema(long)}; every {@link Seq} call refuses this value as
     * {@link SofabError#ARGUMENT} rather than growing a list uncompared.
     */
    public static final Bound SCHEMA_BOUNDED = new Bound(-1L, STATED);

    private Bound(long max, byte kind) {
        this.max = max;
        this.kind = kind;
    }

    /**
     * The bound the schema declares for this field — an array's {@code count}, or a
     * {@code string}/{@code blob}'s {@code maxlen} — compared by the call it is
     * handed to. A value past it is {@link SofabError#INVALID_MSG} (MESSAGE_SPEC
     * §7.1), never {@link SofabError#LIMIT_EXCEEDED}: the bytes contradict the
     * schema, and no receiver configuration makes them decode.
     *
     * <p>A {@code count} is a <b>capacity</b>, not a length: the destination starts
     * empty and the wire carries the length, so an element index {@code id} breaches
     * it when {@code id >= count}. A {@code maxlen} bounds a byte length, which
     * breaches it when {@code length > maxlen}.
     *
     * <p>The number is the schema's, and §6.2.1's exclusivity is the caller's to
     * honour by handing over exactly one bound per field: where the schema declares
     * this, no receiver cap applies.
     *
     * @param n the schema's {@code count} or {@code maxlen}, at least {@code 1} —
     *          what the schema validator admits
     * @return the bound to hand to the call that guards the field
     * @throws IllegalArgumentException if {@code n} is below {@code 1}; it refines
     *                                  {@link SofabError#ARGUMENT} (§6.3). {@code 0}
     *                                  is an unassigned field and a negative is the
     *                                  retired sentinel, neither of them a schema
     *                                  bound
     */
    public static Bound schema(long n) {
        if (n < 1L) {
            throw new IllegalArgumentException(
                    "sofab: " + SofabError.ARGUMENT + " (schema bound " + n
                            + " is not a count or maxlen: a schema admits 1 and above)");
        }
        return new Bound(n, SCHEMA);
    }

    /**
     * The receiver cap this deployment configured for a field the schema leaves
     * unbounded — one of the three {@code max_dyn_*} limits of §6.2.1. A value at or
     * below the cap passes; above it the call raises
     * {@link SofabError#LIMIT_EXCEEDED}, never a clamp.
     *
     * <p>The number is the <b>caller's</b>: §6.2.1 puts the value with generated
     * code, which knows the schema and the target, and this library neither supplies
     * one nor remembers one.
     *
     * <p><b>A cap must be at least 1</b>, and the two values below that are refused
     * rather than interpreted, because both are how a cap gets <em>forgotten</em>
     * rather than chosen:
     *
     * <ul>
     *   <li>{@code 0} is what Java writes into an unassigned {@code long} or
     *       {@code int} field, so accepting it would let an unconfigured constant
     *       silently become a policy that refuses every non-empty value;
     *   <li>a negative was the retired sentinel for "the schema bounds this", and is
     *       the bit pattern this type exists to stop meaning two things.
     * </ul>
     *
     * <p>A caller whose policy really is "accept nothing here" says so by rejecting
     * the field itself; that is not a cap.
     *
     * @param max the configured limit — a byte length for a {@code string} or
     *            {@code blob}, an element count for an array — at least {@code 1}
     * @return the bound to hand to the call that guards the field
     * @throws IllegalArgumentException if {@code max} is below {@code 1}; it refines
     *                                  {@link SofabError#ARGUMENT} (§6.3), and is
     *                                  raised where the value is stated rather than
     *                                  on the decode path
     */
    public static Bound receiver(long max) {
        if (max < 1L) {
            throw new IllegalArgumentException(
                    "sofab: " + SofabError.ARGUMENT + " (receiver cap " + max
                            + " is not a limit: a cap is at least 1, and "
                            + (max == 0L ? "0 is an unassigned field, not a policy"
                                         : "a negative is not \"the schema bounds this\" — "
                                                 + "pass Bound.schema(n) for that")
                            + ")");
        }
        return new Bound(max, RECEIVER);
    }

    /**
     * Whether a byte {@code length} breaches this bound — {@code false} for
     * {@link #SCHEMA_BOUNDED}, which states that the caller's own schema check
     * governs the field.
     *
     * @param length the announced payload length at the header
     * @return true if a number is stated and {@code length} is above it
     */
    boolean exceededBy(long length) {
        return kind != STATED && length > max;
    }

    /**
     * The rejection for a {@code length} {@link #exceededBy} refused, out of line
     * so the comparison that guards it stays small on the decode path: INVALID_MSG
     * for the schema's {@code maxlen}, LIMIT_EXCEEDED for the receiver's cap.
     *
     * @param noun   {@code "string length"} or {@code "blob length"}
     * @param length the announced length
     * @return the exception to throw
     */
    UncheckedIOException rejectLength(String noun, long length) {
        if (kind == SCHEMA) {
            return Sofab.invalid(noun + " " + length + " above schema maxlen " + max);
        }
        return Sofab.limitExceeded(noun + " " + length + " above configured limit " + max);
    }

    /**
     * The rejection for an element {@code index} that reached the out-of-line path
     * of {@link Seq#checkIndex} — {@code index >= max}, which every index meets for
     * {@link #SCHEMA_BOUNDED}.
     *
     * <ul>
     *   <li>{@link #schema(long)}: INVALID_MSG. An index at or past the schema
     *       {@code count} makes an array longer than the schema's capacity
     *       (MESSAGE_SPEC §7.1).
     *   <li>{@link #receiver(long)}: LIMIT_EXCEEDED. An element at index {@code cap}
     *       makes a list of {@code cap + 1}, one more than the receiver said it would
     *       hold (§6.2.1). Rejected, never clamped — placing it at {@code cap - 1}
     *       instead would be data corruption.
     *   <li>{@link #SCHEMA_BOUNDED}: ARGUMENT. It carries no count, so the call has
     *       nothing to compare; silence would grow the list uncompared.
     * </ul>
     *
     * @param index the wire's element index
     * @return the exception to throw
     */
    UncheckedIOException rejectIndex(long index) {
        switch (kind) {
            case SCHEMA:
                return Sofab.invalid("array element index " + index + " above schema capacity " + max);
            case RECEIVER:
                return Sofab.limitExceeded("array element index " + index + " above configured limit " + max);
            default:
                return Sofab.argument("Bound.SCHEMA_BOUNDED carries no count and cannot bound an "
                        + "array index: pass Bound.schema(count) where the schema bounds the "
                        + "array, or Bound.receiver(n) where it does not");
        }
    }

    /**
     * The stated number — what {@link Seq#checkIndex} compares an index against,
     * and what a diagnostic names. Negative for {@link #SCHEMA_BOUNDED}.
     *
     * @return the number
     */
    long cap() {
        return max;
    }

    /**
     * The bound that must have been stated, refusing a {@code null} rather than
     * reading it as <em>no cap</em> (§6.2.1: a codec "MUST NOT read an omitted
     * argument as unlimited").
     *
     * @param bound the caller's bound
     * @param which what this call bounds, for the diagnostic
     * @return {@code bound}
     */
    static Bound required(Bound bound, String which) {
        if (bound == null) {
            throw Sofab.argument("no bound stated for " + which
                    + ": pass Bound.schema(n) where the schema bounds this field, or "
                    + "Bound.receiver(n) with the configured limit where it does not");
        }
        return bound;
    }

    /**
     * A short description naming which rule this states, for diagnostics.
     *
     * @return {@code "Bound.SCHEMA_BOUNDED"}, {@code "Bound.schema(n)"} or
     *         {@code "Bound.receiver(n)"}
     */
    @Override
    public String toString() {
        switch (kind) {
            case SCHEMA:
                return "Bound.schema(" + max + ")";
            case RECEIVER:
                return "Bound.receiver(" + max + ")";
            default:
                return "Bound.SCHEMA_BOUNDED";
        }
    }
}
