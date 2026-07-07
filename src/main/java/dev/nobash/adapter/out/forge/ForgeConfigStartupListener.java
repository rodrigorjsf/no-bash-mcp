package dev.nobash.adapter.out.forge;

import dev.nobash.adapter.out.forge.ForgeConfiguration.Instance;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The forge external-config OBSERVABILITY signal (PRD-6 P0, issue #98) — and the anti-false-green
 * spine of the native gate. With no forge verb in scope, the native binary would otherwise give NO
 * positive evidence that {@link ForgeConfiguration} actually bound the nested {@code forge.instances[]}
 * list: a silent empty list (binding failed) would be indistinguishable from a deliberate fail-closed
 * empty list (nothing configured). This {@code @Singleton} startup listener closes that gap by emitting
 * ONE deterministic diagnostic line at startup that {@code NativeAcceptanceIT} asserts on.
 *
 * <p><b>Two hard constraints (issue #98 / forge-security-model.md):</b></p>
 * <ol>
 *   <li><b>stderr ONLY.</b> The line is logged via SLF4J → logback, which routes every log to
 *       {@code System.err} (logback.xml, DESIGN.md §7). A stray stdout line would corrupt the pure
 *       JSON-RPC STDIO channel and break the stdout-purity tests. Never {@code System.out}.</li>
 *   <li><b>Never the token VALUE.</b> Only the env-var NAME ({@link Instance#tokenEnv()}, itself
 *       operator-authored config) is logged — the token is never resolved here (no
 *       {@code System.getenv}), never logged, never emitted (area 1).</li>
 * </ol>
 *
 * <p>The line is a POSITIVE signal in both directions: {@code bound N instance(s)} for N&gt;=1 proves
 * binding populated the list; {@code bound 0 instance(s)} proves the fail-closed default was reached by
 * a running, correctly-bound binary — not by a startup crash before any log. Host parsing is defensive
 * so a malformed {@code baseUrl} degrades gracefully rather than throwing during startup.</p>
 */
@Singleton
public class ForgeConfigStartupListener implements ApplicationEventListener<StartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(ForgeConfigStartupListener.class);

    private final ForgeConfiguration configuration;

    public ForgeConfigStartupListener(ForgeConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        List<Instance> instances = configuration.getInstances();

        String hosts = instances.stream()
                .map(instance -> hostOf(instance.baseUrl()))
                .collect(Collectors.joining(","));
        // Only the env-var NAMES — never resolved, never the token value.
        String tokenEnvs = instances.stream()
                .map(Instance::tokenEnv)
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(","));

        LOG.info("forge-config: bound {} instance(s) [hosts={}] [tokenEnvs={}]{}",
                instances.size(),
                hosts,
                tokenEnvs,
                instances.isEmpty() ? " - fail-closed, no forge access" : "");
    }

    /**
     * Extract the host of a base URL for the diagnostic, defensively: a null/blank/malformed value must
     * never throw during startup. Falls back to the trimmed raw value when no host can be parsed. Only
     * host/URL text (operator config) is ever surfaced — never a secret.
     */
    private static String hostOf(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "?";
        }
        String trimmed = baseUrl.strip();
        try {
            String host = URI.create(trimmed).getHost();
            return host == null || host.isBlank() ? trimmed : host;
        } catch (RuntimeException e) {
            return trimmed;
        }
    }
}
