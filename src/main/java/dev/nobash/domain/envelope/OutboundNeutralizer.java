package dev.nobash.domain.envelope;

import io.micronaut.core.annotation.Nullable;

/**
 * Structural prompt-injection defense for outbound repo-derived content (issue #7, P9).
 *
 * <p>The MCP returns repo-derived strings (test names, assertion messages, paths, stderr) into
 * the agent's context — a confused-deputy surface. A malicious or compromised repo could craft
 * content to carry an injection payload framed with invisible/control sequences. This class
 * strips that framing structurally; the security model explicitly rejects heuristic
 * keyword-detection (it would redact real error signal, see {@code security-model.md}).</p>
 *
 * <p>What is stripped:</p>
 * <ul>
 *   <li><b>C0 control chars</b> (U+0000–U+001F) EXCEPT {@code \t} (U+0009), {@code \n}
 *       (U+000A), and {@code \r} (U+000D) — stack traces and messages need whitespace;
 *       BEL, BS, ESC, and DEL (U+007F) are stripped.</li>
 *   <li><b>C1 control chars</b> (U+0080–U+009F).</li>
 *   <li><b>ANSI escape sequences</b> — CSI ({@code ESC[...}), OSC ({@code ESC]...} terminated by
 *       ST or BEL), and any lone remaining ESC (U+001B).</li>
 *   <li><b>Zero-width and bidi control code points</b> — U+200B–200D (ZWSP, ZWNJ, ZWJ),
 *       U+FEFF (BOM/ZWNBSP), U+2060 (word joiner), U+200E/200F (LRM/RLM),
 *       U+202A–202E (bidi embedding/override), U+2066–U+2069 (bidi isolate/pop).</li>
 * </ul>
 *
 * <p>What is <b>kept</b>: {@code \t}, {@code \n}, {@code \r} (stack traces / messages); all
 * printable ASCII and Unicode text including the visible words of an injection payload — they
 * land as data inside a typed JSON field, marked {@code untrusted} in the envelope.</p>
 *
 * <p>Per-field length caps are applied <em>after</em> stripping so the cap is on clean content.
 * Strings that exceed the cap are truncated and a {@link #TRUNCATION_MARKER} is appended.
 * The full content is still reachable via {@code get_log}.</p>
 *
 * <p>This class is PURE: no I/O, no state, no Micronaut, no reflection — safe in the domain
 * layer (ArchUnit domain-purity rule). All char matching uses explicit numeric hex-cast
 * constants rather than unicode escape sequences in source.</p>
 */
public final class OutboundNeutralizer {

    // ESC = U+001B ESCAPE; BEL = U+0007 BELL.
    // Expressed as (char) cast of hex literals — avoids the Java unicode escape scanner
    // which is a textual substitution that happens before tokenisation.
    private static final char ESC = (char) 0x1B;  // ESCAPE (U+001B)
    private static final char BEL = (char) 0x07;  // BELL   (U+0007)

    /** Appended to a string that was truncated to its field cap. */
    public static final String TRUNCATION_MARKER = "…[truncated]"; // U+2026 HORIZONTAL ELLIPSIS

    // Per-field caps — generous enough not to touch any normal-length test output.
    // Full content is always reachable via get_log.
    /** Maximum characters for a test name ({@link dev.nobash.domain.result.TestFinding#name()}). */
    public static final int TEST_NAME_CAP = 512;
    /** Maximum characters for a container identity
     * ({@link dev.nobash.domain.result.ContainerFinding#container()}). */
    public static final int CONTAINER_CAP = 512;
    /** Maximum characters for a suite name ({@link dev.nobash.domain.result.TestFinding#suite()}). */
    public static final int SUITE_CAP = 512;
    /** Maximum characters for a message field ({@link dev.nobash.domain.result.Finding#message()}). */
    public static final int MESSAGE_CAP = 2_000;
    /** Maximum characters for a detail field ({@link dev.nobash.domain.result.Finding#detail()}).
     * Stacks can be long; the tighter squeeze lives behind get_log. */
    public static final int DETAIL_CAP = 10_000;
    /** Maximum characters for a source file path ({@link dev.nobash.domain.result.SourceRef#file()}). */
    public static final int SOURCE_FILE_CAP = 512;

    private OutboundNeutralizer() {
        // Utility class — no instances.
    }

    /**
     * Strip all dangerous sequences from {@code input} and cap the result to {@code cap}
     * characters. Returns {@code null} iff {@code input} is {@code null} (null-preserving so
     * nullable record fields stay null rather than becoming empty strings).
     *
     * @param input the raw repo-derived string; may be null
     * @param cap   maximum length of the returned string before the truncation marker
     * @return the neutralized string, or {@code null} if {@code input} was {@code null}
     */
    @Nullable
    public static String neutralize(@Nullable String input, int cap) {
        if (input == null) {
            return null;
        }
        String stripped = stripDangerous(input);
        return applyLengthCap(stripped, cap);
    }

    // ---- private helpers ----

    /**
     * Single-pass strip: iterates over all chars once and builds the output without the
     * dangerous code points. All char matching uses explicit numeric ranges.
     */
    private static String stripDangerous(String s) {
        int n = s.length();
        StringBuilder sb = null;   // null until we encounter the first char to drop
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);

            // ---- ANSI escape sequences (begin with ESC U+001B) ----
            if (c == ESC) {
                sb = ensureBuilder(sb, s, i);
                i++;
                if (i < n) {
                    char next = s.charAt(i);
                    if (next == '[') {
                        // CSI: ESC '[' <parameter/intermediate bytes>* <final byte>
                        // Final byte = 0x40–0x7E.
                        i++;
                        while (i < n && !isCsiFinalByte(s.charAt(i))) {
                            i++;
                        }
                        if (i < n) i++; // consume the final byte
                    } else if (next == ']') {
                        // OSC: ESC ']' <text> terminated by BEL or ST (ESC '\')
                        i++;
                        while (i < n) {
                            char oc = s.charAt(i);
                            if (oc == BEL) {
                                i++;
                                break;
                            }
                            if (oc == ESC && i + 1 < n && s.charAt(i + 1) == '\\') {
                                i += 2;
                                break;
                            }
                            i++;
                        }
                    } else {
                        // Lone ESC followed by any other char — skip the following char too.
                        i++;
                    }
                }
                // ESC at very end of string — just dropped, loop ends naturally.
                continue;
            }

            // ---- C0 control chars (except \t/\n/\r), DEL, C1 range ----
            if (isStrippedControl(c)) {
                sb = ensureBuilder(sb, s, i);
                i++;
                continue;
            }

            // ---- Zero-width and bidi control code points ----
            if (isZeroWidthOrBidi(c)) {
                sb = ensureBuilder(sb, s, i);
                i++;
                continue;
            }

            // ---- Normal character — keep it ----
            if (sb != null) {
                sb.append(c);
            }
            i++;
        }
        return sb != null ? sb.toString() : s;
    }

    /**
     * Returns {@code true} for C0 control chars that must be removed (all C0 except \t\n\r),
     * DEL (U+007F), and C1 control chars (U+0080–U+009F).
     *
     * <p>ESC (U+001B) satisfies this check but is handled by the ANSI-sequence branch
     * <em>before</em> this check is reached, so it is never double-processed.</p>
     */
    private static boolean isStrippedControl(char c) {
        if (c == '\t' || c == '\n' || c == '\r') {
            return false;  // preserve — needed for stack traces and multi-line messages
        }
        return (c <= 0x1F)                        // C0 (includes NUL, BEL, BS, ESC, …)
                || c == 0x7F                      // DEL
                || (c >= 0x0080 && c <= 0x009F);  // C1
    }

    /**
     * Returns {@code true} for zero-width and bidi control code points.
     * All comparisons use numeric literals so source encoding is irrelevant.
     *
     * <pre>
     *   0x200B  ZERO WIDTH SPACE
     *   0x200C  ZERO WIDTH NON-JOINER
     *   0x200D  ZERO WIDTH JOINER
     *   0xFEFF  ZERO WIDTH NO-BREAK SPACE (BOM)
     *   0x2060  WORD JOINER
     *   0x200E  LEFT-TO-RIGHT MARK
     *   0x200F  RIGHT-TO-LEFT MARK
     *   0x202A–0x202E  bidi embedding / override
     *   0x2066–0x2069  bidi isolate / pop
     * </pre>
     */
    private static boolean isZeroWidthOrBidi(char c) {
        return c == 0x200B                            // ZERO WIDTH SPACE
                || c == 0x200C                        // ZERO WIDTH NON-JOINER
                || c == 0x200D                        // ZERO WIDTH JOINER
                || c == 0xFEFF                        // BOM / ZERO WIDTH NO-BREAK SPACE
                || c == 0x2060                        // WORD JOINER
                || c == 0x200E                        // LEFT-TO-RIGHT MARK
                || c == 0x200F                        // RIGHT-TO-LEFT MARK
                || (c >= 0x202A && c <= 0x202E)       // bidi embedding / override
                || (c >= 0x2066 && c <= 0x2069);      // bidi isolate / pop
    }

    /**
     * CSI final byte: in the range 0x40–0x7E (@ through ~, excludes DEL at 0x7F).
     * See ECMA-48 S5.4 "Control sequences".
     */
    private static boolean isCsiFinalByte(char c) {
        return c >= 0x40 && c <= 0x7E;
    }

    /** Lazily initialise the output builder, copying the already-clean prefix [0, upTo). */
    private static StringBuilder ensureBuilder(@Nullable StringBuilder sb, String s, int upTo) {
        if (sb == null) {
            sb = new StringBuilder(s.length());
            sb.append(s, 0, upTo);
        }
        return sb;
    }

    private static String applyLengthCap(String s, int cap) {
        if (s.length() <= cap) {
            return s;
        }
        return s.substring(0, cap) + TRUNCATION_MARKER;
    }
}
