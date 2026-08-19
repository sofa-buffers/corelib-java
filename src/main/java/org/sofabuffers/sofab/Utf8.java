package org.sofabuffers.sofab;

import java.nio.charset.StandardCharsets;

/**
 * UTF-8 validation — and, on the decode side, materialization — of a raw byte
 * range, for both sides of a {@code string} field (CORELIB_PLAN §6.4).
 *
 * <p>{@link OStream#writeString} rejects an invalid {@code String} while
 * measuring it, but wherever a {@code string} is handled as <em>raw bytes</em>
 * this validator is what enforces the contract: on encode for the byte-container
 * entry point {@link OStream#writeFixlen} with {@link FixlenType#STRING}, and on
 * decode for bytes arriving from a peer, which must be validated <em>before</em>
 * they are handed to the consumer as a {@code String} — which is what
 * {@link #decode} does, in that order. Generated code needs this on every
 * materialized string, so it belongs here rather than being emitted into every
 * generated message class.
 *
 * <p>Validation is on the byte range, not on a constructed {@code String}:
 * {@code new String(bytes, UTF_8)} silently substitutes U+FFFD for malformed
 * input, so a check made after that conversion can never fail. Checking first is
 * what makes the rejection possible at all.
 */
public final class Utf8 {

    private Utf8() {}

    /**
     * Reports whether {@code b[i..end)} is well-formed UTF-8.
     *
     * <p>Accepts exactly the Unicode-scalar encoding and no more. Rejected:
     * overlong forms (including the {@code C0 80} "Modified UTF-8" NUL and the
     * {@code C1}-lead two-byte forms), surrogate code points {@code U+D800..DFFF}
     * encoded as three bytes, anything above {@code U+10FFFF}, a lead byte whose
     * continuation bytes are missing or out of range, and a bare continuation
     * byte.
     *
     * @param b   buffer
     * @param i   first byte of the range
     * @param end one past the last byte of the range
     * @return true when the range is valid UTF-8
     */
    public static boolean valid(byte[] b, int i, int end) {
        while (i < end) {
            int c = b[i] & 0xff;
            if (c < 0x80) { i++; continue; }
            int n, lo, hi;
            // The second byte's legal range depends on the lead: it is what
            // excludes the overlong forms (E0 A0.., F0 90..) and the surrogates
            // (ED 80..9F) without a separate code-point comparison.
            if (c < 0xC2) return false;                                              // bare continuation, or overlong 2-byte
            else if (c < 0xE0) { n = 1; lo = 0x80;                     hi = 0xBF; }
            else if (c < 0xF0) { n = 2; lo = (c == 0xE0) ? 0xA0 : 0x80; hi = (c == 0xED) ? 0x9F : 0xBF; }
            else if (c < 0xF5) { n = 3; lo = (c == 0xF0) ? 0x90 : 0x80; hi = (c == 0xF4) ? 0x8F : 0xBF; }
            else return false;                                                        // > U+10FFFF
            if (i + n >= end) return false;                                           // truncated sequence
            int cc = b[i + 1] & 0xff;
            if (cc < lo || cc > hi) return false;
            for (int k = 2; k <= n; k++) {
                cc = b[i + k] & 0xff;
                if (cc < 0x80 || cc > 0xBF) return false;
            }
            i += n + 1;
        }
        return true;
    }

    /**
     * Materialize {@code b[off, off + len)} as a {@code String}, rejecting a range
     * that is not well-formed UTF-8.
     *
     * <p>This is what a decoder does with a {@code string} payload once it is
     * complete, and it is two steps for a reason: {@link #valid} first, the
     * conversion second. {@code new String(bytes, UTF_8)} silently substitutes
     * U+FFFD for malformed input, so a check made on the result can never fail —
     * validating the bytes is what makes the rejection possible at all, and
     * MESSAGE_SPEC §7 requires the rejection rather than a repaired string.
     *
     * <p>The rejection is {@link Sofab#invalid}, i.e. an {@code INVALID_MSG}
     * {@link SofabException} wrapped so it can leave a {@link Visitor} callback,
     * which is where generated code calls this from.
     *
     * @param b   buffer
     * @param off first byte of the payload
     * @param len payload length in bytes
     * @return the decoded string
     * @throws java.io.UncheckedIOException wrapping an {@code INVALID_MSG}
     *                                      {@link SofabException} when the range is
     *                                      not valid UTF-8
     */
    public static String decode(byte[] b, int off, int len) {
        if (!valid(b, off, off + len)) {
            throw Sofab.invalid("string: invalid UTF-8");
        }
        return new String(b, off, len, StandardCharsets.UTF_8);
    }
}
