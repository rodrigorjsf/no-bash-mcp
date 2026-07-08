package dev.nobash.domain.forge;

/**
 * One resolved forge instance from the operator allowlist (#98), as the application layer sees it —
 * a pure domain record, decoupled from the Micronaut {@code ForgeConfiguration.Instance} adapter
 * bean (the layered-architecture rule forbids the application layer touching an adapter type). The
 * adapter maps its config bean onto this record behind {@code ForgeInstancePort}.
 *
 * <p>{@code tokenEnv} is the NAME of the environment variable that holds the read-scoped token —
 * never the token value. Resolution ({@code System.getenv}) happens by-reference at request time in
 * the outbound adapter; the value is never logged, returned, or accepted from agent input
 * (forge-security-model.md area 1).</p>
 *
 * @param baseUrl   the forge REST base URL (e.g. {@code https://api.github.com}); required
 * @param apiPrefix optional REST API path prefix (GHES seam, e.g. {@code /api/v3}); null when absent
 * @param tokenEnv  optional env-var NAME holding the read-scoped token; null on a tokenless instance
 */
public record ForgeInstance(String baseUrl, String apiPrefix, String tokenEnv) {
}
