package dev.nobash.domain.port.out;

import dev.nobash.domain.forge.ForgeAccessException;

import java.util.List;

/**
 * Outbound driven port for read-only forge (GitHub REST) inspection (PRD-6 S1, #99, ADR-0003). A
 * plain domain interface with zero framework annotations; the adapter in
 * {@code adapter.out.forge.github} satisfies it via compile-time DI.
 *
 * <p>It is transport-focused and repo-agnostic beyond the request: the use-case has already
 * resolved and allowlist-validated the instance/host (SSRF floor) before calling. The port owns
 * exactly two REST mechanics:</p>
 * <ul>
 *   <li>{@link #fetchChecks(ForgeCheckRequest)} — GET the ref's check-runs paginated via
 *       {@code Link rel="next"} <b>to exhaustion</b>, GET the ref's Commit Statuses, and return the
 *       two folded into one normalized {@link ForgeCheckRun} list.</li>
 *   <li>{@link #fetchJobLog(ForgeLogRequest)} — GET the failed job's log, following the single
 *       302-to-signed-blob hop <b>manually</b> and stripping {@code Authorization} on any cross-origin
 *       hop (the token-leak floor).</li>
 * </ul>
 *
 * <p>Both throw {@link ForgeAccessException} (rate-limit, 404/private, unexpected) rather than
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
}
