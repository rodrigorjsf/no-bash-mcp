package dev.nobash.adapter.out.forge;

import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.port.out.ForgeInstancePort;
import jakarta.inject.Singleton;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Adapter that exposes the operator forge allowlist ({@link ForgeConfiguration}, #98) to the
 * application layer through the domain {@link ForgeInstancePort} (PRD-6 S1, #99). This keeps the
 * layered-architecture rule intact: the {@code pr_checks} use-case injects the domain port, never the
 * Micronaut config bean in the adapter layer. Each {@code ForgeConfiguration.Instance} is mapped onto
 * the pure {@link ForgeInstance} domain record.
 *
 * <p>Host matching honors the GitHub public-host equivalence: an origin host {@code H} matches an
 * instance whose baseUrl host is {@code H} <b>or</b> {@code api.H} (so a {@code github.com} origin
 * resolves the {@code https://api.github.com} instance). A GHES same-host instance matches directly.
 * The allowlist is the ONLY source of forge hosts (SSRF floor); nothing here is agent-supplied.</p>
 */
@Singleton
public class ForgeAllowlistProvider implements ForgeInstancePort {

    private final ForgeConfiguration configuration;

    public ForgeAllowlistProvider(ForgeConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public Optional<ForgeInstance> resolveForHost(String originHost) {
        if (originHost == null || originHost.isBlank()) {
            return Optional.empty();
        }
        String host = originHost.strip().toLowerCase();
        return instances().stream()
                .filter(instance -> hostMatches(host, instance))
                .findFirst()
                .map(ForgeAllowlistProvider::toDomain);
    }

    @Override
    public Optional<ForgeInstance> primary() {
        List<ForgeConfiguration.Instance> instances = instances();
        return instances.isEmpty()
                ? Optional.empty()
                : Optional.of(toDomain(instances.get(0)));
    }

    private List<ForgeConfiguration.Instance> instances() {
        return configuration.getInstances();
    }

    private static boolean hostMatches(String originHost, ForgeConfiguration.Instance instance) {
        String instanceHost = hostOf(instance.baseUrl());
        if (instanceHost == null) {
            return false;
        }
        return instanceHost.equals(originHost) || instanceHost.equals("api." + originHost);
    }

    private static ForgeInstance toDomain(ForgeConfiguration.Instance instance) {
        return new ForgeInstance(instance.baseUrl(), instance.apiPrefix(), instance.tokenEnv());
    }

    /** Extract the lower-cased host from a baseUrl defensively (a malformed URL yields null). */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(baseUrl.strip()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
