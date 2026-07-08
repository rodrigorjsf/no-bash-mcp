package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.forge.PrChecksUseCase;
import dev.nobash.application.verb.forge.PrDiffUseCase;
import dev.nobash.application.verb.forge.PrViewUseCase;
import dev.nobash.domain.envelope.Envelope;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

/**
 * The inbound MCP adapter for the read-only forge verbs (DESIGN.md §4, PRD-6 S1/S2, #99/#100). The
 * {@code @Tool} bean IS the adapter — no inbound port interface; transport (STDIO) is configuration.
 * This slice exposes {@code pr_checks}, {@code pr_view}, and {@code pr_diff} on the same family bean.
 *
 * <p>All three verbs are read-only and annotated {@code @Tool.ToolAnnotations(readOnlyHint = true)}.
 * {@code pr_checks}' failing check carries a {@code handle} in {@code prChecks[]}; {@code pr_diff}'s
 * envelope carries a {@code handle} for its full diff text — both retrievable via
 * {@code get_log(handle)}.</p>
 */
@Singleton
public class ForgeTools {

    private final PrChecksUseCase prChecks;
    private final PrViewUseCase prView;
    private final PrDiffUseCase prDiff;

    public ForgeTools(PrChecksUseCase prChecks, PrViewUseCase prView, PrDiffUseCase prDiff) {
        this.prChecks = prChecks;
        this.prView = prView;
        this.prDiff = prDiff;
    }

    /**
     * Report a pull request / ref's CI checks — folding GitHub Actions check-runs AND legacy Commit
     * Statuses into one normalized list with a container-aware {@code ok} (one red or still-running
     * check ⇒ {@code ok=false}). Each failing check-run carries a {@code handle} retrievable via
     * {@code get_log(handle)} (the failed job's log). The repository is resolved from the workspace
     * {@code origin} remote (or the {@code repo} override) against the operator allowlist.
     *
     * <p>Host not allowlisted → {@code FORGE_HOST_NOT_ALLOWLISTED}; rate-limit →
     * {@code FORGE_RATE_LIMITED} (surfaces {@code Retry-After}, never auto-retries); private repo
     * without a token → {@code FORGE_RESOURCE_NOT_FOUND}; unresolvable origin/ref →
     * {@code FORGE_ORIGIN_UNRESOLVED}. This verb is read-only.</p>
     *
     * @param path    the repository checkout directory (resolves {@code origin} and {@code HEAD})
     * @param ref     optional commit ref; defaults to the current {@code HEAD}
     * @param repo    optional {@code owner/repo} override
     * @param timeout optional git-call timeout in seconds
     * @return the pr_checks envelope (per-check list + container-aware ok) or an operational error
     */
    @Tool(name = "pr_checks",
            description = "Report a PR/ref's CI checks — check-runs + commit statuses folded into a "
                    + "normalized list with a container-aware ok (one red or still-running check ⇒ "
                    + "ok=false). Failing checks carry a get_log(handle) to the failed job's log.",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope pr_checks(
            @ToolArg(name = "path", description = "Path to the repository checkout directory") @Nullable String path,
            @ToolArg(name = "ref", description = "Commit ref (branch/tag/SHA); defaults to HEAD") @Nullable String ref,
            @ToolArg(name = "repo", description = "Optional owner/repo override") @Nullable String repo,
            @ToolArg(name = "timeout", description = "Optional git-call timeout in seconds") @Nullable Integer timeout) {
        return prChecks.run(path, ref, repo, timeout);
    }

    /**
     * Report a pull request's metadata in ONE call: {@code state}, {@code mergeable}, {@code merged},
     * head/base refs, a folded review status, and a checks summary. The repository is resolved from
     * the workspace {@code origin} remote (or the {@code repo} override) against the operator
     * allowlist. {@code pr} is required — there is no branch-to-PR resolution in this slice.
     *
     * <p>Same structured {@code FORGE_*} error surface as {@code pr_checks} (host not allowlisted,
     * rate-limited, private-repo 404, unresolved target/{@code pr}). This verb is read-only.</p>
     *
     * @param path    the repository checkout directory (resolves {@code origin})
     * @param pr      the pull request number
     * @param repo    optional {@code owner/repo} override
     * @param timeout optional git-call timeout in seconds
     * @return the pr_view envelope (one-call PR metadata) or an operational error
     */
    @Tool(name = "pr_view",
            description = "Report a PR's metadata in one call: state, mergeable, merged, head/base "
                    + "refs, a folded review status, and a checks summary.",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope pr_view(
            @ToolArg(name = "path", description = "Path to the repository checkout directory") @Nullable String path,
            @ToolArg(name = "pr", description = "Pull request number") @Nullable String pr,
            @ToolArg(name = "repo", description = "Optional owner/repo override") @Nullable String repo,
            @ToolArg(name = "timeout", description = "Optional git-call timeout in seconds") @Nullable Integer timeout) {
        return prView.run(path, pr, repo, timeout);
    }

    /**
     * Fetch a pull request's unified diff. The full diff text is stashed behind a {@code handle}
     * retrievable via {@code get_log(handle)} — non-lossily, exactly like {@code git_diff}'s full
     * patch. The repository is resolved from the workspace {@code origin} remote (or the {@code repo}
     * override) against the operator allowlist. {@code pr} is required.
     *
     * <p>Same structured {@code FORGE_*} error surface as {@code pr_checks}/{@code pr_view}. This
     * verb is read-only.</p>
     *
     * @param path    the repository checkout directory (resolves {@code origin})
     * @param pr      the pull request number
     * @param repo    optional {@code owner/repo} override
     * @param timeout optional git-call timeout in seconds
     * @return the pr_diff envelope (handle-only) or an operational error
     */
    @Tool(name = "pr_diff",
            description = "Fetch a PR's unified diff. The full diff text is behind a "
                    + "get_log(handle) — non-lossy, same pattern as git_diff.",
            annotations = @Tool.ToolAnnotations(readOnlyHint = true))
    public Envelope pr_diff(
            @ToolArg(name = "path", description = "Path to the repository checkout directory") @Nullable String path,
            @ToolArg(name = "pr", description = "Pull request number") @Nullable String pr,
            @ToolArg(name = "repo", description = "Optional owner/repo override") @Nullable String repo,
            @ToolArg(name = "timeout", description = "Optional git-call timeout in seconds") @Nullable Integer timeout) {
        return prDiff.run(path, pr, repo, timeout);
    }
}
