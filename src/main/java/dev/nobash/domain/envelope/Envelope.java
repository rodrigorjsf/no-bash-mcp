package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.error.OperationalError;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.Summary;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.jsonschema.JsonSchema;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The common result shape every verb returns (CONTEXT.md "Envelope"). It has three shapes,
 * distinguished by which optional fields are present (D30, DESIGN §6):
 *
 * <ul>
 *   <li><b>success</b> — {@code ok=true}, counts-only ({@code summary} present, NO {@code failures},
 *       NO report surfaced). Built ONLY when tests actually executed.</li>
 *   <li><b>test-failure</b> — {@code ok=false}, {@code summary} + top-level {@code failures[]} (each
 *       a {@link Finding} serialized with its frozen {@code "kind"} discriminator so the agent
 *       branches {@code TestFinding} vs {@code ContainerFinding}).</li>
 *   <li><b>operational-error</b> — {@code ok=false}, an {@link OperationalError} ({@code code} +
 *       {@code message} + {@code hint}); a {@link Handle} is attached when raw output was stashed
 *       (e.g. {@code REPORT_NOT_PRODUCED} carries the compiler output behind the handle).</li>
 * </ul>
 *
 * <p>Pure domain, reflection-free ({@code @Serdeable @Introspected}) for STDIO serialization
 * without runtime reflection (DESIGN.md §7). Optional wire fields are reference types so they
 * emit {@code null} rather than a misleading default.</p>
 *
 * @param ok       whether the operation itself succeeded (the application-layer failure floor)
 * @param verb     the logical operation invoked (e.g. {@code run_tests})
 * @param manager  the detected manager for ecosystem verbs ({@code mvn}); null when none
 * @param summary  the TEST-level counts; present on success / test-failure, null on op-error
 * @param failures the normalized findings (test- and container-level); present only on test-failure
 * @param error    the operational error when this is the op-error shape; null otherwise
 * @param handle   a token to retrieve stashed raw output later; null when nothing was stashed
 */
@Serdeable
@Introspected
@JsonSchema
public record Envelope(boolean ok,
                       String verb,
                       @Nullable String manager,
                       @Nullable Summary summary,
                       @Nullable List<Finding> failures,
                       @Nullable OperationalError error,
                       @Nullable Handle handle) {

    /**
     * Build a counts-only success envelope ({@code ok=true}). Surfaces NO report — a green run
     * gives the agent the counts and nothing to triage (token efficiency, CONTEXT.md "Noise").
     */
    public static Envelope success(String verb, String manager, Summary summary, @Nullable Handle handle) {
        return new Envelope(true, verb, manager, summary, null, null, handle);
    }

    /**
     * Build a test-failure envelope ({@code ok=false}) carrying the normalized {@code failures[]}.
     * The failure floor adds only the boolean — it NEVER injects a synthetic finding, so each
     * finding appears exactly once and {@code summary} counts are unchanged (D28/D30).
     */
    public static Envelope testFailure(String verb, String manager, Summary summary,
                                       List<Finding> failures, @Nullable Handle handle) {
        return new Envelope(false, verb, manager, summary, List.copyOf(failures), null, handle);
    }

    /**
     * Build an operational-error envelope ({@code ok=false}) for a verb, with no stashed output.
     */
    public static Envelope operationalError(String verb, ErrorCode code, String message, String hint) {
        return operationalError(verb, code, message, hint, null);
    }

    /**
     * Build an operational-error envelope ({@code ok=false}) carrying a {@link Handle} to the
     * stashed raw output (e.g. the compiler diagnostics behind {@code REPORT_NOT_PRODUCED}).
     */
    public static Envelope operationalError(String verb, ErrorCode code, String message, String hint,
                                            @Nullable Handle handle) {
        return new Envelope(false, verb, null, null, null, new OperationalError(code, message, hint), handle);
    }
}
