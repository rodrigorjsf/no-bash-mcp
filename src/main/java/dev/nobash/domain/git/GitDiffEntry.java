package dev.nobash.domain.git;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A single file entry in a {@code git_diff} result (PRD-002, issue #27). Parsed from the combined
 * output of {@code git diff --numstat} (added/deleted line counts) and
 * {@code git diff --name-status} (change status letter).
 *
 * <p>PURE domain record — IO-free, carrying only the parsed data. It is placed in
 * {@code domain.git} and is subject to the {@code the_git_domain_is_io_free} ArchUnit rule.</p>
 *
 * @param path     the working-tree path of the changed file (untrusted, repo-derived; P9-neutralized
 *                 before envelope assembly); for renames this is the NEW path
 * @param added    number of added lines; null for binary files (numstat outputs {@code -})
 * @param deleted  number of deleted lines; null for binary files
 * @param status   the single-letter status code from {@code git diff --name-status}: {@code M}
 *                 (modified), {@code A} (added), {@code D} (deleted), {@code R} (renamed),
 *                 {@code C} (copied), {@code T} (type changed), {@code U} (unmerged),
 *                 or {@code X} (unknown)
 */
@Serdeable
@Introspected
public record GitDiffEntry(
        @Nullable String path,
        @Nullable Integer added,
        @Nullable Integer deleted,
        @Nullable String status) {
}
