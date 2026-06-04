package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.error.OperationalError;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.jsonschema.JsonSchema;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The common result shape every verb returns (CONTEXT.md "Envelope"). This slice builds
 * ONLY the operational-error shape — {@code ok=false} carrying an {@link OperationalError}.
 * The success and test-failure shapes (counts, normalized {@code failures[]}, {@code handle})
 * are later slices and are deliberately absent.
 *
 * <p>Pure domain, reflection-free ({@code @Serdeable @Introspected}) for STDIO serialization
 * without runtime reflection (DESIGN.md §7). Optional wire fields are reference types so they
 * emit {@code null} rather than a misleading default, satisfying serde's
 * fail-on-null-for-primitives on any read-back.</p>
 *
 * @param ok      whether the operation itself succeeded
 * @param verb    the logical operation invoked (e.g. {@code run_tests})
 * @param manager the detected manager for ecosystem verbs ({@code mvn}); null when none
 * @param error   the operational error when {@code ok=false}; null on success
 */
@Serdeable
@Introspected
@JsonSchema
public record Envelope(boolean ok,
                       String verb,
                       @Nullable String manager,
                       @Nullable OperationalError error) {

    /**
     * Build an operational-error envelope ({@code ok=false}) for a verb.
     */
    public static Envelope operationalError(String verb, ErrorCode code, String message, String hint) {
        return new Envelope(false, verb, null, new OperationalError(code, message, hint));
    }
}
