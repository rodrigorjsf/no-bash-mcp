package dev.nobash.domain.git;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The normalized {@code git_status} result shape (PRD-002, issue #24), parsed from
 * {@code git status --porcelain=v2 --branch}. It is the FOUNDATIONAL git domain record — the
 * later git slices (git_log/show, git_diff, git_branch) carry their own shapes, but the parse
 * pattern and nullable-field discipline established here is the template.
 *
 * <p>Carried in the {@link dev.nobash.domain.envelope.Envelope} via the dedicated
 * {@code gitStatus} field (never reusing the frozen test {@code summary}/{@code failures} or the
 * build {@code diagnostics}, ADR-0007/ADR-0009). {@code manager} on a git envelope is null — git
 * verbs are ecosystem-agnostic and detect no package manager.</p>
 *
 * <h3>Nullable header fields (porcelain robustness)</h3>
 * <p>The branch header lines are NOT all always present, and the parser treats each as nullable
 * rather than throwing (porcelain-v2 reference, ADR-0005):</p>
 * <ul>
 *   <li>{@code branch} — the current branch name from {@code # branch.head}; {@code "(detached)"}
 *       on a detached HEAD. Null only if the header was entirely absent.</li>
 *   <li>{@code detached} — {@code true} when {@code # branch.head} reports {@code (detached)}.</li>
 *   <li>{@code upstream} — the tracking branch from {@code # branch.upstream}; <b>null</b> when
 *       there is no tracking branch (the header line is then absent entirely).</li>
 *   <li>{@code ahead} / {@code behind} — from {@code # branch.ab +A -B}; <b>both null</b> when
 *       there is no upstream (the {@code branch.ab} line is absent without a tracking branch).
 *       BOXED {@link Integer} so they serialize as {@code null}, never a misleading {@code 0}.</li>
 * </ul>
 *
 * <h3>Change buckets</h3>
 * <p>Entries are bucketed by their porcelain-v2 status code: the index (staged) half X of the
 * {@code XY} field drives {@code staged[]}, the worktree (unstaged) half Y drives
 * {@code unstaged[]}, and {@code ?} lines drive {@code untracked[]}. A file modified in BOTH the
 * index and the worktree ({@code MM}) appears in both {@code staged[]} and {@code unstaged[]}.
 * All three lists are non-null (empty when clean) for a stable agent-facing shape.</p>
 *
 * @param branch    the current branch name; {@code "(detached)"} when detached; null if absent
 * @param detached  whether HEAD is detached (no branch checked out)
 * @param upstream  the tracking branch ({@code remote/branch}); null when there is no upstream
 * @param ahead     commits ahead of upstream; null when there is no upstream (BOXED, nullable)
 * @param behind    commits behind upstream; null when there is no upstream (BOXED, nullable)
 * @param staged    paths with index (staged) changes; empty when none
 * @param unstaged  paths with worktree (unstaged) changes; empty when none
 * @param untracked untracked paths ({@code ?} lines); empty when none
 */
// Omit null header fields (branch/upstream/ahead/behind) from serialization so they are ABSENT
// rather than rendered as `null`. The generated MCP @JsonSchema outputSchema types these as
// integer/string (it does not express nullable for @Nullable components), so a rendered `null`
// fails the framework's structuredContent validation (isError). They are not `required`, so
// absence is valid and preserves the "no upstream" semantic (absent, never a misleading 0).
@JsonInclude(JsonInclude.Include.NON_NULL)
@Serdeable
@Introspected
public record GitStatus(
        @Nullable String branch,
        boolean detached,
        @Nullable String upstream,
        @Nullable Integer ahead,
        @Nullable Integer behind,
        List<GitStatusEntry> staged,
        List<GitStatusEntry> unstaged,
        List<GitStatusEntry> untracked) {

    /**
     * Canonical constructor copying the bucket lists defensively so the record is deeply
     * immutable. Null buckets are tolerated and normalized to empty lists for a stable shape.
     */
    public GitStatus {
        staged    = staged    == null ? List.of() : List.copyOf(staged);
        unstaged  = unstaged  == null ? List.of() : List.copyOf(unstaged);
        untracked = untracked == null ? List.of() : List.copyOf(untracked);
    }

    /** Whether the working tree is clean: no staged, unstaged, or untracked changes. */
    public boolean clean() {
        return staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty();
    }
}
