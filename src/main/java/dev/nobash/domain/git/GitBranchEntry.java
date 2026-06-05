package dev.nobash.domain.git;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A single branch entry in a {@code git_branch} result (PRD-002, issue #28). Parsed from
 * {@code git branch --format=<FORMAT>} output, one record per branch.
 *
 * <p>PURE domain record — IO-free, carrying only the parsed data. It is placed in
 * {@code domain.git} and is subject to the {@code the_git_domain_is_io_free} ArchUnit rule.</p>
 *
 * <h3>Nullable field convention</h3>
 * <p>{@code upstream}, {@code ahead}, and {@code behind} are all null when the branch has no
 * configured tracking upstream — mirroring the nullable-field discipline from
 * {@link GitStatus}. When an upstream IS configured but the counts are zero (up to date),
 * {@code ahead} and {@code behind} are {@code 0}, not null. This preserves the distinction
 * between "no upstream" and "upstream, up-to-date".</p>
 *
 * @param name     the short branch name (e.g. {@code main}, {@code feature/my-work})
 * @param current  {@code true} when this is the currently checked-out branch (HEAD)
 * @param upstream the tracking upstream ref in short form (e.g. {@code origin/main}); null
 *                 when there is no configured upstream
 * @param ahead    commits this branch is ahead of its upstream; null when there is no upstream
 * @param behind   commits this branch is behind its upstream; null when there is no upstream
 */
@Serdeable
@Introspected
public record GitBranchEntry(
        @Nullable String name,
        boolean current,
        @Nullable String upstream,
        @Nullable Integer ahead,
        @Nullable Integer behind) {
}
