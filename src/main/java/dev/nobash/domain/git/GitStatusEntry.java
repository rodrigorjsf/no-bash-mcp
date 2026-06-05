package dev.nobash.domain.git;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A single changed-path entry in a {@link GitStatus} (PRD-002, issue #24). Parsed from a
 * {@code git status --porcelain=v2} entry line and bucketed by the use-case into
 * {@code staged[]} / {@code unstaged[]} / {@code untracked[]}.
 *
 * <p>The {@code path} is the working-tree path git reports (untrusted, repo-derived — it can
 * carry ANSI/zero-width bytes, so the Envelope P9-neutralizes it before returning to the agent,
 * mirroring how compile-diagnostic paths are handled). The {@code code} is the locale-independent
 * porcelain-v2 status code that produced this entry:</p>
 * <ul>
 *   <li>For an ordinary ({@code 1}) or rename/copy ({@code 2}) line, it is the two-character
 *       {@code XY} field (e.g. {@code M.}, {@code .M}, {@code MM}, {@code A.}, {@code R.}). The
 *       parser records the FULL {@code XY} so a consumer can tell index-state (X) from
 *       worktree-state (Y); the use-case buckets on whichever half is non-{@code .}.</li>
 *   <li>For an untracked ({@code ?}) line, it is {@code "?"}.</li>
 * </ul>
 *
 * <p>{@code origPath} is populated ONLY for rename/copy ({@code 2}) entries — it carries the
 * original path git reports after the TAB; null for every other entry kind.</p>
 *
 * @param path     the working-tree path of the changed file (untrusted, repo-derived)
 * @param code     the porcelain-v2 status code that classified this entry (locale-independent)
 * @param origPath the pre-rename/copy original path; null unless this is a rename/copy entry
 */
@Serdeable
@Introspected
public record GitStatusEntry(
        @Nullable String path,
        @Nullable String code,
        @Nullable String origPath) {

    /**
     * Convenience factory for a plain (non-rename) entry — no original path.
     *
     * @param path the working-tree path
     * @param code the porcelain-v2 status code
     * @return a new entry with {@code origPath == null}
     */
    public static GitStatusEntry of(String path, String code) {
        return new GitStatusEntry(path, code, null);
    }
}
