package dev.nobash.domain.git;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The full metadata and body of a single commit, as returned by {@code git_show} (PRD-002,
 * issue #26). The diff is NOT embedded here — it is stashed behind a {@link dev.nobash.domain.envelope.Handle}
 * in the run-cache and is retrievable via {@code get_log(handle)}. This keeps large patches
 * from flooding the envelope.
 *
 * <p>PURE domain record; IO-free; subject to the {@code the_git_domain_is_io_free} ArchUnit rule.</p>
 *
 * @param sha      the full 40-hex commit SHA
 * @param abbrev   the abbreviated 7-char commit SHA
 * @param author   the author name (repo-derived; P9-neutralized before envelope assembly)
 * @param dateIso  the strict ISO 8601 author date
 * @param subject  the commit subject line (repo-derived; P9-neutralized)
 * @param body     the commit message body (everything after the subject; may be blank or null;
 *                 repo-derived; P9-neutralized)
 */
@Serdeable
@Introspected
public record GitCommitDetail(
        @Nullable String sha,
        @Nullable String abbrev,
        @Nullable String author,
        @Nullable String dateIso,
        @Nullable String subject,
        @Nullable String body) {
}
