package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A single compiler diagnostic entry produced by the {@code build} verb (ADR-0009).
 *
 * <p>Parsed from the maven-compiler-plugin's structured console line:
 * <pre>
 *   [ERROR] &lt;file&gt;:[&lt;line&gt;,&lt;col&gt;] &lt;message&gt;
 * </pre>
 *
 * <p>The coordinates ({@code line} and {@code col}) are locale-independent integers parsed from
 * the bracket-delimited numeric portion. The {@code message} is passed through verbatim as
 * untrusted content (P9 — the Envelope neutralizes it before returning to the agent).</p>
 *
 * <p>Distinct from the frozen test-result schema ({@link Finding} / {@link SourceRef}, ADR-0007):
 * compile diagnostics have no test identity, no {@link Outcome}, and carry a column that
 * {@link SourceRef} deliberately omits. Carried in the Envelope as a separate
 * {@code diagnostics[]} array, never in {@code failures[]}.</p>
 *
 * @param file     the source file path as reported by the compiler (untrusted, may be absolute)
 * @param line     the 1-based line number; null when the compiler omitted it
 * @param col      the 1-based column number; null when the compiler omitted it
 * @param severity the diagnostic severity: {@code ERROR} or {@code WARNING}
 * @param message  the compiler message text, passed through verbatim (untrusted, P9-neutralized)
 */
@Serdeable
@Introspected
public record CompileDiagnostic(
        @Nullable String file,
        @Nullable Integer line,
        @Nullable Integer col,
        String severity,
        String message) {

    /** Severity constant for compile errors. */
    public static final String SEVERITY_ERROR = "ERROR";

    /** Severity constant for compile warnings. */
    public static final String SEVERITY_WARNING = "WARNING";
}
