package dev.nobash.domain.forge;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The folded CI-checks summary carried by a {@code pr_view} result (PRD-6 S2, #100): the same
 * container-aware fold {@code pr_checks} computes ({@link ForgeCheckClassifier}), reduced to counts so
 * the {@code pr_view} envelope stays a single, small, one-call read. All fields are server-computed
 * (counts + a boolean) — no repo-derived string, so no P9 neutralization is needed here.
 *
 * @param total   the total number of folded checks (check-runs + Commit Statuses)
 * @param failing the number of FAILING checks ({@link ForgeCheckClassifier#isFailing})
 * @param ok      the container-aware verdict ({@link ForgeCheckClassifier#ok}) — one red or
 *                still-running check flips it false
 */
@Serdeable
@Introspected
public record PrChecksSummary(int total, int failing, boolean ok) {
}
