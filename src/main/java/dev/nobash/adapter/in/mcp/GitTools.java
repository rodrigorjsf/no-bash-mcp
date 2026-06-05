package dev.nobash.adapter.in.mcp;

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
 * <p>This slice exposes one verb:</p>
 * <ul>
 *   <li>{@code git_status} — delegates to {@link GitStatusUseCase}, returning the normalized
 *       git-status envelope.</li>
 * </ul>
 *
 * <p>Every git verb is read-only and is annotated {@code @Tool.ToolAnnotations(readOnlyHint = true)}
 * so MCP clients can surface the verb as a non-mutating inspection (CONTEXT.md, ADR-0005).</p>
 */
@Singleton
public class GitTools {

    private final GitStatusUseCase gitStatus;

    public GitTools(GitStatusUseCase gitStatus) {
        this.gitStatus = gitStatus;
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
}
