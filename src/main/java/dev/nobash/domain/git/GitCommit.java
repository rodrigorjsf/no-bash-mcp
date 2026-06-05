package dev.nobash.domain.git;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A single entry in a {@code git_log} result (PRD-002, issue #26). Parsed from
 * {@code git log --format=...} output using an unambiguous delimiter scheme
 * ({@code %x1f} / {@code %x1e} unit/record separators).
 *
 * <p>This is a PURE domain record — IO-free, carrying only the parsed data.
 * It is placed in {@code domain.git} and is subject to the {@code the_git_domain_is_io_free}
 * ArchUnit rule.</p>
 *
 * @param sha      the full 40-hex commit SHA
 * @param abbrev   the abbreviated 7-char commit SHA
 * @param author   the author name (repo-derived; P9-neutralized before envelope assembly)
 * @param dateIso  the strict ISO 8601 author date (e.g. {@code 2024-01-15T10:23:00+00:00})
 * @param subject  the commit subject line (first line; repo-derived, P9-neutralized)
 */
@Serdeable
@Introspected
public record GitCommit(
        @Nullable String sha,
        @Nullable String abbrev,
        @Nullable String author,
        @Nullable String dateIso,
        @Nullable String subject) {
}
