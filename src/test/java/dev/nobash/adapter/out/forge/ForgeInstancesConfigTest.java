package dev.nobash.adapter.out.forge;

import dev.nobash.adapter.out.forge.ForgeConfiguration.Instance;
import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD-6 P0 (issue #98) AC1/AC3 — the highest JVM seam for the forge external-config binding. Boots a
 * REAL {@link ApplicationContext} with an external YAML supplied exactly as production does it — the
 * {@code micronaut.config.files} system property, the sysprop twin of the {@code MICRONAUT_CONFIG_FILES}
 * env var (same external-file load path) — and asserts the nested {@code forge.instances[]} list POJO
 * binds {@code {baseUrl, apiPrefix?, tokenEnv?}} with kebab-case keys via Micronaut relaxed binding. A
 * second case with NO external file proves the fail-closed default (no instance → empty list → no forge
 * access, D62).
 *
 * <p>This is only the JVM half of the P0 gate: the native half — proving the GraalVM binary binds the
 * SAME external file through reflective nested-list-POJO introspection (the failure class that bit
 * logback in native, D53/G15) — lives in {@code NativeAcceptanceIT}'s forge legs and is verified only in
 * CI. The JVM gate is structurally blind to native reachability (#57 precedent), so JVM-green here is
 * necessary but NOT sufficient for AC2.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ForgeInstancesConfigTest {

    /** The Micronaut external-config sysprop — the same load path as the {@code MICRONAUT_CONFIG_FILES} env var. */
    private static final String CONFIG_FILES_PROP = "micronaut.config.files";

    @AfterEach
    void clear_external_config_override() {
        System.clearProperty(CONFIG_FILES_PROP);
    }

    @Test
    void binds_the_nested_forge_instances_list_from_an_external_config_file() throws Exception {
        Path yml = Files.createTempFile("forge-instances", ".yml");
        Files.writeString(yml, """
                forge:
                  instances:
                    - base-url: https://api.github.com
                      api-prefix: /api/v3
                      token-env: GITHUB_TOKEN
                    - base-url: https://git.example.test
                """);
        System.setProperty(CONFIG_FILES_PROP, yml.toString());

        try (ApplicationContext ctx = ApplicationContext.run()) {
            List<Instance> instances = ctx.getBean(ForgeConfiguration.class).getInstances();

            assertThat(instances)
                    .as("both forge.instances[] entries must bind from the external yml")
                    .hasSize(2);

            // Look each entry up by its (required) baseUrl rather than by list index: the injected
            // ordering of @EachProperty(list=true) beans is a Micronaut internal detail, whereas AC1
            // (issue #98) requires only that each entry's fields bind — baseUrl required, apiPrefix?
            // and tokenEnv? optional-nullable. Asserting by baseUrl keeps this deterministic and
            // decoupled from binder ordering (which is not a functional contract of this slice).
            Instance full = instanceWithBaseUrl(instances, "https://api.github.com");
            assertThat(full.apiPrefix()).isEqualTo("/api/v3");
            assertThat(full.tokenEnv()).isEqualTo("GITHUB_TOKEN");

            // apiPrefix? and tokenEnv? are OPTIONAL — a minimal instance binds them as null, not blank.
            Instance minimal = instanceWithBaseUrl(instances, "https://git.example.test");
            assertThat(minimal.apiPrefix()).isNull();
            assertThat(minimal.tokenEnv()).isNull();
        }
    }

    /** Resolve a bound instance by its required baseUrl; fails the test if none bound with that URL. */
    private static Instance instanceWithBaseUrl(List<Instance> instances, String baseUrl) {
        return instances.stream()
                .filter(instance -> baseUrl.equals(instance.baseUrl()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no forge instance bound with base-url " + baseUrl));
    }

    @Test
    void no_external_config_file_yields_an_empty_fail_closed_instance_list() {
        System.clearProperty(CONFIG_FILES_PROP);

        try (ApplicationContext ctx = ApplicationContext.run()) {
            assertThat(ctx.getBean(ForgeConfiguration.class).getInstances())
                    .as("no forge.instances configured → empty list (fail-closed, no forge access — D62)")
                    .isEmpty();
        }
    }
}
