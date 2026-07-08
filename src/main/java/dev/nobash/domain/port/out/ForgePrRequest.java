package dev.nobash.domain.port.out;

/**
 * The carrier of a forge pull-request fetch across the outbound {@code ForgePort} (PRD-6 S2, #100),
 * used by both {@code pr_view} ({@link ForgePort#fetchPrView}) and {@code pr_diff}
 * ({@link ForgePort#fetchPrDiff}). Holds the allowlisted instance's connection facts ({@code baseUrl},
 * optional {@code apiPrefix} GHES seam, optional {@code tokenEnv} env-var NAME) plus the target
 * {@code owner}/{@code repo}/{@code pr} number. The host is already allowlist-validated by the
 * use-case before this request is built (SSRF floor), mirroring {@link ForgeCheckRequest}.
 *
 * <p>{@code tokenEnv} is the env-var NAME, never the value; the adapter resolves it by-reference at
 * request time and never logs or returns it (forge-security-model.md area 1).</p>
 *
 * @param baseUrl   the forge REST base URL (allowlisted); e.g. {@code https://api.github.com}
 * @param apiPrefix optional REST path prefix (GHES seam, e.g. {@code /api/v3}); null when absent
 * @param tokenEnv  optional env-var NAME holding the read-scoped token; null → tokenless
 * @param owner     the repository owner
 * @param repo      the repository name
 * @param pr        the pull request number
 */
public record ForgePrRequest(String baseUrl, String apiPrefix, String tokenEnv,
                             String owner, String repo, long pr) {
}
