package dev.nobash.adapter.out.forge;

import io.micronaut.context.ApplicationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PRD-6 S1 (#99) AC "Production config binding rejects {@code http://} base URLs (test profile only)"
 * (D69/D62(3)). Forces a NON-test environment via {@code deduceEnvironment(false).environments(
 * "production")} — otherwise JUnit auto-adds the {@code test} environment and the affordance would
 * apply — and asserts an {@code http://} forge instance aborts context startup. The external-config
 * path ({@code micronaut.config.files}) is the same one #98 proved for the nested-list binding.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ForgeInsecureBaseUrlRejectionTest {

    private static final String CONFIG_FILES_PROP = "micronaut.config.files";

    @AfterEach
    void clear_external_config_override() {
        System.clearProperty(CONFIG_FILES_PROP);
    }

    @Test
    void a_production_context_rejects_an_http_base_url() throws Exception {
        Path yml = Files.createTempFile("forge-insecure", ".yml");
        Files.writeString(yml, """
                forge:
                  instances:
                    - base-url: http://insecure.example
                """);
        System.setProperty(CONFIG_FILES_PROP, yml.toString());

        ApplicationContext ctx = ApplicationContext.builder()
                .deduceEnvironment(false)
                .environments("production")
                .build();
        try {
            assertThatThrownBy(ctx::start)
                    .as("an http:// forge base URL must abort startup outside the test profile")
                    .hasStackTraceContaining("Insecure forge base URL");
        } finally {
            try {
                ctx.stop();
            } catch (RuntimeException ignored) {
                // Already failed to start — nothing to stop cleanly.
            }
        }
    }

    @Test
    void a_production_context_accepts_an_https_base_url() throws Exception {
        Path yml = Files.createTempFile("forge-secure", ".yml");
        Files.writeString(yml, """
                forge:
                  instances:
                    - base-url: https://api.github.com
                      token-env: GITHUB_TOKEN
                """);
        System.setProperty(CONFIG_FILES_PROP, yml.toString());

        try (ApplicationContext ctx = ApplicationContext.builder()
                .deduceEnvironment(false)
                .environments("production")
                .build()
                .start()) {
            assertThat(ctx.isRunning())
                    .as("an https:// forge base URL starts cleanly in production")
                    .isTrue();
        }
    }
}
