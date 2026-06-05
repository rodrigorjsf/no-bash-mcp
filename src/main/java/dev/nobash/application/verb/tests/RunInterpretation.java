package dev.nobash.application.verb.tests;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.result.NormalizedRun;
import io.micronaut.core.annotation.Nullable;

/**
 * The value an {@link EcosystemAdapter#interpret} returns (ADR-0011). It expresses the
 * report-absence asymmetry the schema map records WITHOUT pulling the stash/handle or the envelope
 * assembly into the adapter — those stay invariant in {@link RunTestsUseCase}.
 *
 * <p>It has exactly two shapes:</p>
 * <ul>
 *   <li><b>normalized</b> — a report was produced and folded into a {@link NormalizedRun}; the
 *       use-case applies the D28/D29 floor over it and assembles the success / test-failure
 *       envelope.</li>
 *   <li><b>report-absent</b> — no report to fold (Maven's empty fresh dir → an operational
 *       {@code REPORT_NOT_PRODUCED}, D25/D27). It carries everything the use-case's invariant
 *       op-error branch needs to stay byte-identical: the raw {@code stashPayload} to retain behind
 *       the {@link dev.nobash.domain.envelope.Handle}, the {@link ErrorCode}, the {@code message},
 *       and the {@code hint}. The use-case does the {@code stash} + {@code operationalError}
 *       assembly with zero ecosystem-specific literals.</li>
 * </ul>
 *
 * <p>A {@code normalized} interpretation has a non-null {@link #run()} and a null
 * {@link #absence()}; a {@code report-absent} interpretation is the reverse. Use the factories,
 * never the canonical constructor, and branch on {@link #isReportAbsent()}.</p>
 *
 * @param run     the normalized run when a report was produced; null on report-absence
 * @param absence the report-absence signal when no report was produced; null otherwise
 */
public record RunInterpretation(@Nullable NormalizedRun run, @Nullable ReportAbsence absence) {

    /**
     * A report-absence signal: everything the use-case's invariant operational-error branch needs.
     *
     * @param stashPayload the raw output to retain behind the handle (e.g. the compiler stderr)
     * @param code         the operational error code (e.g. {@code REPORT_NOT_PRODUCED})
     * @param message      the human-readable message
     * @param hint         the actionable hint
     */
    public record ReportAbsence(String stashPayload, ErrorCode code, String message, String hint) {
    }

    /** A normalized interpretation: a report was produced and folded. */
    public static RunInterpretation normalized(NormalizedRun run) {
        return new RunInterpretation(run, null);
    }

    /** A report-absence interpretation: no report to fold (an operational error). */
    public static RunInterpretation reportAbsent(String stashPayload, ErrorCode code, String message,
                                                 String hint) {
        return new RunInterpretation(null, new ReportAbsence(stashPayload, code, message, hint));
    }

    /** @return {@code true} iff this is a report-absence interpretation. */
    public boolean isReportAbsent() {
        return absence != null;
    }
}
