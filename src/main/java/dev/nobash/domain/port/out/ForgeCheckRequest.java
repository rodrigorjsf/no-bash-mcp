package dev.nobash.domain.port.out;

/**
 * The carrier of a forge check-fetch across the outbound {@code ForgePort} (PRD-6 S1, #99). It holds
 * the allowlisted instance's connection facts ({@code baseUrl}, optional {@code apiPrefix} GHES seam,
 * optional {@code tokenEnv} env-var NAME) plus the target {@code owner}/{@code repo}/{@code ref}. The
 * host is already allowlist-validated by the use-case before this request is built (SSRF floor).
 *
 * <p>{@code tokenEnv} is the env-var NAME, never the value; the adapter resolves it by-reference at
 * request time and never logs or returns it (forge-security-model.md area 1). {@code tokenEnv} null
 * → an unauthenticated (tokenless) request.</p>
 *
 * @param baseUrl   the forge REST base URL (allowlisted); e.g. {@code https://api.github.com}
 * @param apiPrefix optional REST path prefix (GHES seam, e.g. {@code /api/v3}); null when absent
 * @param tokenEnv  optional env-var NAME holding the read-scoped token; null → tokenless
 * @param owner     the repository owner
 * @param repo      the repository name
 * @param ref       the commit ref (SHA/branch/tag) whose checks are fetched
 */
public record ForgeCheckRequest(String baseUrl, String apiPrefix, String tokenEnv,
                                String owner, String repo, String ref) {
}
