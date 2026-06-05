package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Compile-level counts for the {@code build} verb (ADR-0009). Distinct from the frozen
 * test-level {@link Summary} which carries test-execution counts ({@code total/passed/failed/
 * errored/skipped}). A successful build returns {@code {errors:0, warnings:N}}; a failed
 * build returns the actual counts parsed from the compiler output.
 *
 * <p>Carried in the Envelope as a separate {@code buildSummary} field, never reusing the
 * frozen {@link Summary} (ADR-0009).</p>
 *
 * @param errors   the count of compiler ERROR diagnostics (0 on a successful build)
 * @param warnings the count of compiler WARNING diagnostics (may be non-zero on a success)
 */
@Serdeable
@Introspected
public record BuildSummary(int errors, int warnings) {
}
