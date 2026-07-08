package dev.nobash.adapter.out.forge;

import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The forge inspection allowlist (PRD-6 P0, issue #98) — the product's FIRST operator-provisioned
 * config surface. Binds the nested {@code forge.instances[]} list from external configuration the
 * operator authors and points at via {@code MICRONAUT_CONFIG_FILES} (D62 sub-decision 3); the harness
 * {@code env} block supplies that path. Each entry is an {@link Instance} — {@code {baseUrl, apiPrefix?,
 * tokenEnv?}} — with kebab-case keys ({@code base-url}/{@code api-prefix}/{@code token-env}) accepted
 * via Micronaut relaxed binding.
 *
 * <p><b>Binding mechanism.</b> The list is bound with {@code @EachProperty(list = true)} — the
 * documented Micronaut idiom for an <i>ordered list of configuration beans</i>. (A raw
 * {@code List<record>} property on a parent {@code @ConfigurationProperties} does NOT bind the nested
 * complex elements — empirically it yields an empty list; #98 red proof.) One {@link Instance} bean is
 * generated per configured entry; this aggregator collects them, preserving order.</p>
 *
 * <p><b>Fail-closed default (D62).</b> With no {@code forge.instances} present, zero {@link Instance}
 * beans exist, so {@link #getInstances()} is <b>empty</b> — no instance means no forge access, never a
 * crash. This is the security-model default-deny (forge-security-model.md area 2): the allowlist is
 * human-authored, non-agent-mutable, and empty until an operator provisions it.</p>
 *
 * <p><b>Native binding is the crux this P0 exists to prove.</b> {@code @EachProperty} +
 * {@code @ConfigurationInject} generate the binding logic at COMPILE time (no runtime reflection on the
 * binder); the element introspection is auto-covered by the Micronaut annotation processors' native
 * metadata. The GraalVM binary must bind the SAME external yml the JVM does — asserted by
 * {@code NativeAcceptanceIT}'s forge legs in CI (the JVM gate is blind to native, #57).</p>
 *
 * <p><b>Scope (issue #98 hard boundary).</b> This is a pure, faithful binder: no verb, no HTTP, no
 * {@code @Client}, no TLS logic. In particular the D62 rejection of {@code http://} base URLs (TLS
 * always in production) is <b>deferred to #99+</b> with the transport layer — AC1 requires only that
 * the fields bind, and the issue explicitly scopes out TLS logic.</p>
 */
@Singleton
public class ForgeConfiguration {

    private final List<Instance> instances;

    /**
     * @param instances the {@code @EachProperty} instance beans, in configured order; empty when none
     *                  is provisioned (fail-closed). Copied defensively for an immutable, non-null view.
     */
    public ForgeConfiguration(List<Instance> instances) {
        this.instances = instances == null ? List.of() : List.copyOf(instances);
    }

    /**
     * The bound, non-null forge instance allowlist; empty when none is configured (fail-closed).
     *
     * @return the ordered instances, never {@code null}
     */
    public List<Instance> getInstances() {
        return instances;
    }

    /**
     * One allowlisted forge instance, bound from a {@code forge.instances[]} list element.
     * {@code baseUrl} is required; {@code apiPrefix} and {@code tokenEnv} are optional (nullable).
     * {@code tokenEnv} is the NAME of the environment variable that HOLDS the read-scoped token —
     * never the token value itself; the value is resolved by-reference at request time by a later
     * slice and is never logged or emitted (forge-security-model.md area 1).
     */
    @EachProperty(value = "forge.instances", list = true)
    public static class Instance {

        private final String baseUrl;
        private final String apiPrefix;
        private final String tokenEnv;

        /**
         * @param baseUrl   the forge base URL (e.g. {@code https://api.github.com}); required
         * @param apiPrefix optional REST API path prefix (GHES seam, e.g. {@code /api/v3}); null when absent
         * @param tokenEnv  optional env-var NAME holding the read-scoped token; null on a tokenless instance
         */
        @ConfigurationInject
        public Instance(
                String baseUrl,
                @Nullable String apiPrefix,
                @Nullable String tokenEnv) {
            this.baseUrl = baseUrl;
            this.apiPrefix = apiPrefix;
            this.tokenEnv = tokenEnv;
        }

        /** @return the forge base URL; required, never null once bound */
        public String baseUrl() {
            return baseUrl;
        }

        /** @return the optional REST API path prefix (GHES seam); null when absent */
        @Nullable
        public String apiPrefix() {
            return apiPrefix;
        }

        /** @return the optional env-var NAME holding the read-scoped token (never the value); null when absent */
        @Nullable
        public String tokenEnv() {
            return tokenEnv;
        }
    }
}
