package dev.nobash.application.verb.forge;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.port.out.ForgePrRequest;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * The {@code pr_diff} use-case (PRD-6 S2, #100): resolve the target repository, validate the
 * required {@code pr} number, fetch the PR's raw unified diff via {@link ForgePort#fetchPrDiff}, and
 * stash it behind a {@code get_log} handle — exactly mirroring how {@code git_diff} stashes its full
 * patch in {@link RawOutputStash} (NOT the lazy {@code ForgeLogHandleRegistry}, which is reserved for
 * job-log fetches that must stay lazy through the 302 flow; a PR diff is already fully materialized by
 * the single {@code Accept: …diff} GET, so it is stashed eagerly like any other raw output).
 *
 * <p>Guards + error mapping mirror {@link PrViewUseCase} (SAME {@code FORGE_*} surface).</p>
 */
@Singleton
public class PrDiffUseCase {

    private static final String VERB = "pr_diff";
    private static final String GIT = "git";
    private static final int GIT_TIMEOUT_DEFAULT = 30;
    private static final int GIT_TIMEOUT_MAX = 120;

    private final CommandExecutorPort git;
    private final ForgeInstancePort allowlist;
    private final ForgePort forge;
    private final RawOutputStash stash;

    public PrDiffUseCase(@Named(GIT) CommandExecutorPort git, ForgeInstancePort allowlist,
                         ForgePort forge, RawOutputStash stash) {
        this.git = git;
        this.allowlist = allowlist;
        this.forge = forge;
        this.stash = stash;
    }

    /**
     * Run {@code pr_diff} for the given PR of a repository resolved from the workspace or the override.
     *
     * @param path    the repository checkout directory (used to resolve {@code origin})
     * @param pr      the pull request number (required)
     * @param repo    optional {@code owner/repo} override (fork workflows / host aliases, D62(5))
     * @param timeout optional git-call timeout in seconds; clamped to [1, {@value GIT_TIMEOUT_MAX}]
     * @return the pr_diff envelope (handle-only), or a structured operational error
     */
    public Envelope run(String path, String pr, String repo, Integer timeout) {
        int gitTimeout = ForgeTargetResolver.clampTimeout(timeout, GIT_TIMEOUT_DEFAULT, GIT_TIMEOUT_MAX);

        ForgeTargetResolver.Resolved resolved =
                ForgeTargetResolver.resolveTarget(VERB, git, allowlist, path, repo, gitTimeout);
        if (resolved.error() != null) {
            return resolved.error();
        }

        long[] prNumber = new long[1];
        Envelope prError = ForgeTargetResolver.validatePr(VERB, pr, prNumber);
        if (prError != null) {
            return prError;
        }

        ForgeInstance instance = resolved.instance();
        ForgePrRequest request = new ForgePrRequest(
                instance.baseUrl(), instance.apiPrefix(), instance.tokenEnv(),
                resolved.owner(), resolved.repo(), prNumber[0]);

        try {
            String diff = forge.fetchPrDiff(request);
            Handle handle = stash.stash(diff == null ? "" : diff);
            return Envelope.prDiff(VERB, handle);
        } catch (ForgeAccessException e) {
            return Envelope.operationalError(VERB, e.code(), e.getMessage(), e.hint());
        }
    }
}
