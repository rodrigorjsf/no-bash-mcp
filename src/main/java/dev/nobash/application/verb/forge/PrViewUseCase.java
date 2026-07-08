package dev.nobash.application.verb.forge;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.forge.PrView;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.port.out.ForgePrRequest;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

/**
 * The {@code pr_view} use-case (PRD-6 S2, #100): resolve the target repository (workspace
 * {@code origin} or the explicit {@code repo} override) against the operator allowlist, validate the
 * required {@code pr} number, fetch the one-call PR metadata via {@link ForgePort#fetchPrView}, and
 * return the {@code pr_view} envelope. Mirrors {@link PrChecksUseCase}'s guard order and error
 * mapping (SAME {@code FORGE_*} error surface — no new unstructured failure path).
 *
 * <p><b>Fail-closed guard order, every gate BEFORE any I/O:</b></p>
 * <ol>
 *   <li>Resolve the target: {@code repo} override → the primary allowlisted instance; else parse
 *       {@code origin} (needs a valid workspace {@code path} + git on PATH) and match its host against
 *       the allowlist.</li>
 *   <li>Validate {@code pr}: required, must be a positive integer (reuses
 *       {@code FORGE_ORIGIN_UNRESOLVED} — the PR to inspect could not be determined; no branch-based
 *       PR resolution is built, D62(5)).</li>
 *   <li>Call the forge; a {@link ForgeAccessException} is mapped to a structured operational error.</li>
 * </ol>
 */
@Singleton
public class PrViewUseCase {

    private static final String VERB = "pr_view";
    private static final String GIT = "git";
    private static final int GIT_TIMEOUT_DEFAULT = 30;
    private static final int GIT_TIMEOUT_MAX = 120;

    private final CommandExecutorPort git;
    private final ForgeInstancePort allowlist;
    private final ForgePort forge;

    public PrViewUseCase(@Named(GIT) CommandExecutorPort git, ForgeInstancePort allowlist, ForgePort forge) {
        this.git = git;
        this.allowlist = allowlist;
        this.forge = forge;
    }

    /**
     * Run {@code pr_view} for the given PR of a repository resolved from the workspace or the override.
     *
     * @param path    the repository checkout directory (used to resolve {@code origin})
     * @param pr      the pull request number (required)
     * @param repo    optional {@code owner/repo} override (fork workflows / host aliases, D62(5))
     * @param timeout optional git-call timeout in seconds; clamped to [1, {@value GIT_TIMEOUT_MAX}]
     * @return the pr_view envelope, or a structured operational error
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
            PrView view = forge.fetchPrView(request);
            return Envelope.prView(VERB, view);
        } catch (ForgeAccessException e) {
            return Envelope.operationalError(VERB, e.code(), e.getMessage(), e.hint());
        }
    }
}
