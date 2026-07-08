package dev.nobash.domain.port.out;

import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.PrView;

import java.util.List;

/**
 * Outbound driven port for read-only forge (GitHub REST) inspection (PRD-6 S1/S2, #99/#100, ADR-0003).
 * A plain domain interface with zero framework annotations; the adapter in
 * {@code adapter.out.forge.github} satisfies it via compile-time DI.
 *
 * <p>It is transport-focused and repo-agnostic beyond the request: the use-case has already
 * resolved and allowlist-validated the instance/host (SSRF floor) before calling. The port owns
 * these REST mechanics:</p>
 * <ul>
 *   <li>{@link #fetchChecks(ForgeCheckRequest)} — GET the ref's check-runs paginated via
 *       {@code Link rel="next"} <b>to exhaustion</b>, GET the ref's Commit Statuses, and return the
 *       two folded into one normalized {@link ForgeCheckRun} list.</li>
 *   <li>{@link #fetchJobLog(ForgeLogRequest)} — GET the failed job's log, following the single
 *       302-to-signed-blob hop <b>manually</b> and stripping {@code Authorization} on any cross-origin
 *       hop (the token-leak floor).</li>
 *   <li>{@link #fetchPrView(ForgePrRequest)} — GET the PR resource + its reviews + the head-sha
 *       checks fold (S2, #100), returning the one-call {@link PrView} metadata carrier.</li>
 *   <li>{@link #fetchPrDiff(ForgePrRequest)} — GET the PR resource with
 *       {@code Accept: application/vnd.github.v3.diff} and return the raw unified diff text (S2,
 *       #100); the use-case stashes it behind a {@code get_log} handle, exactly like {@code git_diff}.</li>
 * </ul>
 *
 * <p>All four throw {@link ForgeAccessException} (rate-limit, 404/private, unexpected) rather than
 * returning a misleading empty result; the use-case's single catch maps it to a structured
 * operational error. The token is resolved by-reference from {@code tokenEnv} and never logged or
 * returned.</p>
 */
public interface ForgePort {

    /**
     * Fetch and fold the ref's CI checks (check-runs paginated to exhaustion + Commit Statuses).
     *
     * @param request the allowlisted instance facts + owner/repo/ref
     * @return the normalized, folded check list (may be empty when the ref has no checks)
     * @throws ForgeAccessException on rate-limit, 404/private, or an unexpected transport failure
     */
    List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request);

    /**
     * Fetch a failed job's log through the 302-to-signed-blob flow, stripping {@code Authorization}
     * on the cross-origin hop.
     *
     * @param request the allowlisted instance facts + owner/repo + job id
     * @return the log text
     * @throws ForgeAccessException on rate-limit, 404, or an unexpected transport failure
     */
    String fetchJobLog(ForgeLogRequest request);

    /**
     * Fetch a pull request's one-call metadata: state, mergeability, merged, head/base refs, a
     * folded review status, and a checks summary (S2, #100).
     *
     * @param request the allowlisted instance facts + owner/repo/pr number
     * @return the normalized PR view carrier
     * @throws ForgeAccessException on rate-limit, 404/private, or an unexpected transport failure
     */
    PrView fetchPrView(ForgePrRequest request);

    /**
     * Fetch a pull request's unified diff text via {@code Accept: application/vnd.github.v3.diff}
     * (S2, #100).
     *
     * @param request the allowlisted instance facts + owner/repo/pr number
     * @return the raw unified diff text (never truncated; the caller stashes it behind a handle)
     * @throws ForgeAccessException on rate-limit, 404/private, or an unexpected transport failure
     */
    String fetchPrDiff(ForgePrRequest request);
}
