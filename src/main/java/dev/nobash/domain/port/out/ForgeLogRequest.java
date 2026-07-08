package dev.nobash.domain.port.out;

/**
 * The carrier of a forge job-log fetch across the outbound {@code ForgePort} (PRD-6 S1, #99). Built
 * by the {@code pr_checks} use-case for each FAILING check-run and registered behind a {@code get_log}
 * handle so the log is fetched LAZILY — through the 302-to-signed-blob flow — only when the agent
 * drills in. Carries the allowlisted instance facts plus the {@code jobId} whose log is requested.
 *
 * @param baseUrl   the forge REST base URL (allowlisted)
 * @param apiPrefix optional REST path prefix (GHES seam); null when absent
 * @param tokenEnv  optional env-var NAME holding the read-scoped token; null → tokenless
 * @param owner     the repository owner
 * @param repo      the repository name
 * @param jobId     the GitHub Actions job id whose log to fetch
 */
public record ForgeLogRequest(String baseUrl, String apiPrefix, String tokenEnv,
                              String owner, String repo, long jobId) {
}
