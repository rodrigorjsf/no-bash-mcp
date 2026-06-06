package dev.nobash.application.policy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link InstallFlagPolicy} (PRD-3, slice 3, security-model.md).
 *
 * <p>The seed is intentionally EMPTY for this tracer slice — no agent-supplied npm flag is
 * admitted in v1. All flags must be silently dropped. The MCP-injected controlled flags
 * ({@code --no-audit}, {@code --no-fund}) are NOT agent flags and are not tested here.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InstallFlagPolicyTest {

    private final InstallFlagPolicy policy = new InstallFlagPolicy();

    @Nested
    class empty_seed_drops_everything {

        @Test
        void an_empty_input_yields_an_empty_result() {
            assertThat(policy.filter(List.of())).isEmpty();
        }

        @Test
        void prefer_offline_is_dropped() {
            assertThat(policy.filter(List.of("--prefer-offline"))).isEmpty();
        }

        @Test
        void force_is_dropped() {
            assertThat(policy.filter(List.of("--force"))).isEmpty();
        }

        @Test
        void ignore_scripts_is_dropped() {
            // Confirms that even if an agent tried to pass --ignore-scripts, it gets dropped.
            assertThat(policy.filter(List.of("--ignore-scripts"))).isEmpty();
        }

        @Test
        void multiple_flags_all_dropped() {
            List<String> many = List.of("--prefer-offline", "--force", "--ignore-scripts", "-g");
            assertThat(policy.filter(many)).isEmpty();
        }

        @Test
        void injection_attempt_is_dropped() {
            // Shell injection attempt — dropped by the empty allowlist, never reaches npm.
            assertThat(policy.filter(List.of("--prefer-offline; rm -rf /"))).isEmpty();
        }
    }
}
