package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * Counts parsed from {@code npm install} stdout for the {@code install} verb (PRD-3, slice 3).
 * Distinct from both {@link Summary} (test-level counts) and {@link BuildSummary} (compile-level
 * counts). npm prints e.g. {@code "added 12 packages, removed 3 packages, changed 1 package"} —
 * this record captures those counts defensively (zero when not present in the output).
 *
 * <p>Carried in the Envelope as a separate {@code installSummary} field — never reusing the
 * frozen test or build summaries.</p>
 *
 * @param added   the number of packages npm added (0 when not mentioned in output)
 * @param removed the number of packages npm removed (0 when not mentioned in output)
 * @param changed the number of packages npm changed (0 when not mentioned in output)
 */
@Serdeable
@Introspected
public record InstallSummary(int added, int removed, int changed) {

    /** The zero-counts sentinel used when npm output cannot be parsed defensively. */
    public static final InstallSummary EMPTY = new InstallSummary(0, 0, 0);
}
