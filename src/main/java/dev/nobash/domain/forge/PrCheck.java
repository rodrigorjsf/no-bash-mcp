package dev.nobash.domain.forge;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * One entry in a {@code pr_checks} result (PRD-6 S1, #99): a single CI check folded from either a
 * GitHub Actions <b>check-run</b> or a legacy <b>commit status</b>, carrying the check {@code name},
 * its effective {@code conclusion}, and — for a FAILING check-run — an opaque {@code handle} the
 * agent passes to {@code get_log} to retrieve that job's log through the 302-to-signed-blob flow.
 *
 * <p>{@code conclusion} is the effective outcome string: a completed check-run's {@code conclusion}
 * ({@code success}/{@code failure}/{@code neutral}/…), or the {@code status} for a check that has
 * not concluded ({@code queued}/{@code in_progress}). {@code handle} is null for a passing check, an
 * incomplete check, or a commit status (statuses have no retrievable job log — the report-format
 * asymmetry, cf. D25).</p>
 *
 * <p>{@code name} is attacker-controllable (a repo names its checks), so it is P9-neutralized when
 * the envelope is built and the envelope is marked {@code untrusted=true}.</p>
 *
 * @param name       the check name (check-run name or status context); repo-derived, neutralized
 * @param conclusion the effective conclusion/status string
 * @param handle     the {@code get_log} handle id for a failing check-run's job log; null otherwise
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
@Introspected
public record PrCheck(String name, String conclusion, @Nullable String handle) {
}
