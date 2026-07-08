package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.forge.PrChecksUseCase;
import dev.nobash.domain.envelope.Envelope;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

/**
 * The inbound MCP adapter for the read-only forge verbs (DESIGN.md §4, PRD-6 S1, #99). The
 * {@code @Tool} bean IS the adapter — no inbound port interface; transport (STDIO) is configuration.
 * This slice exposes {@code pr_checks}; the sibling forge verbs ({@code pr_view}/{@code pr_diff}, #100)
 * add themselves to this same family bean.
 *
 * <p>{@code pr_checks} is read-only and annotated {@code @Tool.ToolAnnotations(readOnlyHint = true)}.
 * A failing check carries a {@code handle} in {@code prChecks[]} that the agent passes to
 * {@code get_log(handle)} to retrieve that job's log through the 302 flow.</p>
 */
@Singleton
public class ForgeTools {

    private final PrChecksUseCase prChecks;

    public ForgeTools(PrChecksUseCase prChecks) {
        this.prChecks = prChecks;
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
}
