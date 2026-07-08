package dev.nobash.domain.port.out;

import dev.nobash.domain.forge.ForgeInstance;

import java.util.Optional;

/**
 * Outbound driven port exposing the operator forge allowlist (#98) to the application layer as pure
 * {@link ForgeInstance} domain records (PRD-6 S1, #99). The adapter in {@code adapter.out.forge}
 * wraps the Micronaut {@code ForgeConfiguration} config bean and implements this port, so the
 * use-case never touches an adapter type (layered-architecture rule) while the host allowlist stays
 * operator-authored and non-agent-mutable.
 *
 * <p>Host matching honors the GitHub public-host equivalence: an origin host {@code H} matches an
 * instance whose baseUrl host is {@code H} <b>or</b> {@code api.H} (so {@code github.com} resolves the
 * {@code https://api.github.com} instance); a GHES same-host instance matches directly.</p>
 */
public interface ForgeInstancePort {

    /**
     * Resolve the allowlisted instance for a git origin host, applying the {@code api.} equivalence.
     *
     * @param originHost the host parsed from the {@code origin} remote (e.g. {@code github.com})
     * @return the matching instance, or empty when the host is not allowlisted
     */
    Optional<ForgeInstance> resolveForHost(String originHost);

    /**
     * The primary (first configured) allowlisted instance, used to resolve a bare {@code owner/repo}
     * override slug against the single GitHub instance (D62(5); multi-instance disambiguation for a
     * slug override is out of the tracer's scope).
     *
     * @return the first allowlisted instance, or empty when none is configured (fail-closed)
     */
    Optional<ForgeInstance> primary();
}
