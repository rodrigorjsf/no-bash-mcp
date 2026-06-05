package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.git.GitDiffUseCase;
import dev.nobash.application.verb.git.GitLogUseCase;
import dev.nobash.application.verb.git.GitShowUseCase;
import dev.nobash.application.verb.git.GitStatusUseCase;
import dev.nobash.domain.envelope.Envelope;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

/**
 * The inbound MCP adapter for the read-only git verbs (DESIGN.md §4). The {@code @Tool} bean IS
 * the adapter — there is no inbound port interface; transport (STDIO) is configuration. Discovery
 * is via compile-time DI. This is the FOUNDATIONAL git bean (PRD-002, issue #24); the later git
 * slices (git_log/show, git_diff, git_branch) add their verbs to this same family bean.
 *
 * <p>This slice exposes four verbs:</p>
 * <ul>
 *   <li>{@code git_status} — delegates to {@link GitStatusUseCase}, returning the normalized
 *       git-status envelope.</li>
 *   <li>{@code git_log} — delegates to {@link GitLogUseCase}, returning a capped commit list
 *       (sha, abbrev, author, dateIso, subject) parsed from {@code git log --format=}.</li>
 *   <li>{@code git_show} — delegates to {@link GitShowUseCase}, returning the commit metadata
 *       and body; the diff is retrievable via {@code get_log(handle)}.</li>
 *   <li>{@code git_diff} — delegates to {@link GitDiffUseCase}, returning an inline diff file
 *       summary ({@code gitDiff[]}); the full patch is retrievable via {@code get_log(handle)}.</li>
 * </ul>
 *
 * <p>Every git verb is read-only and is annotated {@code @Tool.ToolAnnotations(readOnlyHint = true)}
 * so MCP clients can surface the verb as a non-mutating inspection (CONTEXT.md, ADR-0005).</p>
 */
@Singleton
public class GitTools {

    private final GitStatusUseCase gitStatus;
    private final GitLogUseCase gitLog;
    private final GitShowUseCase gitShow;
    private final GitDiffUseCase gitDiff;

    public GitTools(GitStatusUseCase gitStatus, GitLogUseCase gitLog, GitShowUseCase gitShow,
                    GitDiffUseCase gitDiff) {
        this.gitStatus = gitStatus;
        this.gitLog = gitLog;
        this.gitShow = gitShow;
        this.gitDiff = gitDiff;
    }

    /**
     * Report the working-tree status of a git repository — branch, upstream, ahead/behind, and the
     * staged / unstaged / untracked changes — as a normalized envelope. Parsed from git's stable
     * machine format ({@code git status --porcelain=v2 --branch}), never scraped from human stdout.
     *
     * <p>{@code git} absent → operational error {@code TOOL_NOT_INSTALLED}; a path that is not a
     * git working tree → {@code NOT_A_GIT_REPOSITORY}. This verb is read-only: it never mutates the
     * repository and is exempt from the per-module concurrency lock (ADR-0005).</p>
     *
     * @param path    the repository directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope (git-status or operational-error)
     */
    @Tool(name = "git_status",
            description = "Report a git repository's working-tree status — branch, upstream, "
                    + "ahead/behind, and staged/unstaged/untracked changes — as a normalized "
                    + "envelope parsed from git's porcelain v2 machine format.",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope git_status(
            @ToolArg(name = "path", description = "Path to the git repository directory") @Nullable String path,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return gitStatus.run(path, timeout);
    }

    /**
     * Return a capped commit list (sha, short, author, dateIso, subject) from the repository's
     * log, newest first. Defaults to the last {@value GitLogUseCase#DEFAULT_LIMIT} commits;
     * {@code limit} may raise this up to {@value GitLogUseCase#MAX_LIMIT}.
     *
     * <p>{@code git} absent → {@code TOOL_NOT_INSTALLED}; path not a git repo →
     * {@code NOT_A_GIT_REPOSITORY}. Read-only; lock-exempt.</p>
     *
     * @param path    the repository directory
     * @param limit   optional max commit count; clamped to [{@code 1}, {@value GitLogUseCase#MAX_LIMIT}]
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the git-log envelope with {@code gitLog[]} or an operational error
     */
    @Tool(name = "git_log",
            description = "Return a capped commit list (sha, short, author, dateIso, subject) "
                    + "from the repository log, newest first.",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope git_log(
            @ToolArg(name = "path", description = "Path to the git repository directory") @Nullable String path,
            @ToolArg(name = "limit", description = "Max number of commits to return (default "
                    + GitLogUseCase.DEFAULT_LIMIT + ", max " + GitLogUseCase.MAX_LIMIT + ")") @Nullable Integer limit,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return gitLog.run(path, limit, timeout);
    }

    /**
     * Return a commit's full metadata and body; the commit diff is stashed behind a
     * {@code handle} and is retrievable via {@code get_log(handle)} — large patches never flood
     * the envelope.
     *
     * <p>{@code git} absent → {@code TOOL_NOT_INSTALLED}; unknown ref → {@code COMMIT_NOT_FOUND};
     * path not a git repo → {@code COMMIT_NOT_FOUND}. Read-only; lock-exempt.</p>
     *
     * @param path    the repository directory
     * @param ref     the commit reference (full or abbreviated SHA, tag, symbolic ref such as HEAD)
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the git-show envelope with {@code gitShow} detail and a diff {@code handle}
     */
    @Tool(name = "git_show",
            description = "Return a commit's full metadata and body; the diff is retrievable "
                    + "via get_log(handle).",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope git_show(
            @ToolArg(name = "path", description = "Path to the git repository directory") @Nullable String path,
            @ToolArg(name = "ref", description = "Commit reference (SHA, tag, HEAD, …)") @Nullable String ref,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return gitShow.run(path, ref, timeout);
    }

    /**
     * Return a structured diff summary ({@code gitDiff[]}) for all changes between the current
     * working tree (staged + unstaged) and the last commit ({@code HEAD}). Each entry carries
     * the file {@code path}, the number of {@code added} and {@code deleted} lines (null for
     * binary files), and the change {@code status} letter ({@code M}, {@code A}, {@code D},
     * {@code R}, {@code C}, …). The full patch text is stashed behind a {@code handle} and is
     * retrievable via {@code get_log(handle)} — large patches never flood the envelope.
     *
     * <p>{@code git} absent → {@code TOOL_NOT_INSTALLED}; path not a git repo →
     * {@code NOT_A_GIT_REPOSITORY}. Read-only; lock-exempt.</p>
     *
     * @param path    the repository directory
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the git-diff envelope with {@code gitDiff[]} and a patch {@code handle}
     */
    @Tool(name = "git_diff",
            description = "Return a structured diff summary (gitDiff[]) for all changes between "
                    + "the working tree (staged + unstaged) and HEAD. The full patch is "
                    + "retrievable via get_log(handle).",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope git_diff(
            @ToolArg(name = "path", description = "Path to the git repository directory") @Nullable String path,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return gitDiff.run(path, timeout);
    }
}
