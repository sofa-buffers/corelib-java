/*
 * SofaBuffers Java - which rule bounds one field: the schema's, or the
 * receiver's configured cap.
 *
 * SPDX-License-Identifier: MIT
 */
package org.sofabuffers.sofab;

/**
 * Which of the two rules of CORELIB_PLAN §6.2.1 bounds <b>this</b> field: the
 * <b>schema's</b> {@code count}/{@code maxlen}, or a <b>receiver</b> cap the
 * deployment configured.
 *
 * <p>The two are mutually exclusive — §6.2.1: receiver limits "<b>MUST NOT</b> be
 * applied to a field the schema already bounds. There the schema bound governs and
 * its violation is {@code INVALID}" — and they are the only two answers. This type
 * exists so that a call site has to give one of them, and so that <em>saying which</em>
 * is not the same act as <em>choosing a number</em>:
 *
 * <ul>
 *   <li>{@link #SCHEMA_BOUNDED} — the schema declares the ceiling. The caller has
 *       already enforced it at this same header, as {@link Sofab#invalid}
 *       (MESSAGE_SPEC §7.1), so there is no second number to compare against here.
 *   <li>{@link #receiver(long)} — the schema declares none, and this is the
 *       deployment's {@code max_dyn_string_len} / {@code max_dyn_blob_len} /
 *       {@code max_dyn_array_count} for the field. A breach is
 *       {@link SofabError#LIMIT_EXCEEDED}.
 * </ul>
 *
 * <p><b>Why a type and not a number.</b> These two answers used to share one
 * {@code long} parameter, with a negative sentinel for the first — so a caller who
 * had <em>forgotten</em> to state a cap and a caller asserting <em>the schema bounds
 * this field</em> handed over the identical bit pattern, and the forgotten one
 * decoded uncapped and unreported. One sentinel cannot carry two opposite meanings.
 * Here the schema answer is a distinct object carrying no number at all, and
 * <b>every numeric value that reaches a cap comparison is a receiver cap</b>,
 * because {@link #receiver(long)} is the only way to make one.
 *
 * <p><b>Both "forgot" values are diagnosed, not obeyed.</b> Java writes an
 * unassigned {@code long} field as {@code 0} and the retired sentinel was
 * {@code -1}; {@link #receiver(long)} refuses both with {@link SofabError#ARGUMENT},
 * and a {@code null} reference reaching one of the calls is refused the same way
 * rather than read as <em>no cap</em>. §6.2.1 admits "no unset state and no
 * unlimited mode", and a codec "<b>MUST NOT</b> read an omitted argument as
 * <em>unlimited</em>".
 *
 * <p><b>This library still holds no limit.</b> A {@code Bound} is built by the
 * caller out of the caller's number, used for one comparison and not retained;
 * nothing here defaults one, falls back to one, or clamps to one.
 * {@link Sofab#ARRAY_MAX} and {@link Sofab#ID_MAX} are <em>format</em> ceilings,
 * whose breach is {@link SofabError#INVALID_MSG}, and §6.2.1 forbids presenting one
 * as a receiver cap — so there is deliberately no {@code Bound} that means "the
 * format ceiling".
 *
 * <p><b>Where the value comes from, and what it costs.</b> A cap is a constant of
 * the deployment, so generated code builds its {@code Bound}s once, into
 * {@code static final} fields, and passes them per call:
 *
 * <pre>{@code
 * private static final Bound STRING_CAP = Bound.receiver(MAX_DYN_STRING_LEN);
 *
 * public void string(int id, int total, int offset, byte[] data, int co, int cl) {
 *     switch (id) {
 *         case 1 -> { // schema: maxlen: 64 -- the caller's own INVALID check
 *             if (total > 64) { throw Sofab.invalid("note: string length above maxlen 64"); }
 *             note = acc.string(total, offset, data, co, cl, Bound.SCHEMA_BOUNDED);
 *         }
 *         case 2 -> // schema declares no maxlen
 *             free = acc.string(total, offset, data, co, cl, STRING_CAP);
 *         default -> { }  // an id this message does not read: never capped
 *     }
 * }
 * }</pre>
 *
 * <p>Nothing is allocated per call or per message on that path, and both constants
 * are {@code static final} objects with a {@code final} field, which the JIT folds
 * into the comparison it guards.
 *
 * <p>Instances are immutable and shareable.
 */
public final class Bound {

    /**
     * The receiver cap, or {@code -1} for {@link #SCHEMA_BOUNDED}. Only
     * {@link #receiver(long)} can produce a non-negative value, and it refuses
     * everything below {@code 1}, so a non-negative {@code cap} here is always a
     * number a caller deliberately configured.
     */
    private final long cap;

    /**
     * The schema bounds this field, so §6.2.1 forbids a receiver cap on it: the
     * {@code count} or {@code maxlen} governs and a breach is the caller's
     * {@link Sofab#invalid} (MESSAGE_SPEC §7.1).
     *
     * <p><b>It is not "unlimited", and it is not "unset".</b> It is a statement
     * about which rule applies, made by the layer that knows the schema. Passing it
     * for a field the schema leaves <em>unbounded</em> lets the sender decide how
     * much this process holds; that is a defect in the <b>call</b>, not a mode this
     * library offers.
     *
     * <p><b>It carries no number</b>, because this library does not apply the schema
     * bound — the caller does, at the same header, one line before the call. Handing
     * the {@code maxlen} over as well would put a second implementation of
     * MESSAGE_SPEC §7.1 here, which §6.2.1's "one implementation, wherever it runs"
     * forbids just as it forbids two implementations of the cap.
     */
    public static final Bound SCHEMA_BOUNDED = new Bound(-1L);

    private Bound(long cap) {
        this.cap = cap;
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
     *   <li>a negative was the retired sentinel for {@link #SCHEMA_BOUNDED}, and is
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
                                                 + "pass Bound.SCHEMA_BOUNDED for that")
                            + ")");
        }
        return new Bound(max);
    }

    /**
     * Whether {@code value} breaches this bound — {@code false} for
     * {@link #SCHEMA_BOUNDED}, which states that the caller's own schema check
     * governs the field.
     *
     * @param value the announced length or the element index at the header
     * @return true if a receiver cap is stated and {@code value} is above it
     */
    boolean exceededBy(long value) {
        return cap >= 0L && value > cap;
    }

    /**
     * Whether {@code index} breaches this bound as an element <b>index</b>: an
     * element at index {@code cap} makes an array of {@code cap + 1}, so the
     * comparison is {@code >=} rather than {@code >}.
     *
     * @param index the wire's element index
     * @return true if a receiver cap is stated and {@code index} is at or past it
     */
    boolean exceededByIndex(long index) {
        return cap >= 0L && index >= cap;
    }

    /**
     * The configured limit, for the rejection message. Negative for
     * {@link #SCHEMA_BOUNDED}, which never rejects.
     *
     * @return the cap
     */
    long cap() {
        return cap;
    }

    /**
     * The bound that must have been stated, refusing a {@code null} rather than
     * reading it as <em>no cap</em> (§6.2.1: a codec "MUST NOT read an omitted
     * argument as unlimited").
     *
     * @param bound the caller's bound
     * @param which the {@code max_dyn_*} limit this call guards, for the diagnostic
     * @return {@code bound}
     */
    static Bound required(Bound bound, String which) {
        if (bound == null) {
            throw Sofab.argument("no bound stated for " + which
                    + ": pass Bound.receiver(n) with the configured limit, or "
                    + "Bound.SCHEMA_BOUNDED where the schema bounds this field");
        }
        return bound;
    }

    /**
     * A short description naming which rule this states, for diagnostics.
     *
     * @return {@code "Bound.SCHEMA_BOUNDED"} or {@code "Bound.receiver(n)"}
     */
    @Override
    public String toString() {
        return cap < 0L ? "Bound.SCHEMA_BOUNDED" : "Bound.receiver(" + cap + ")";
    }
}
