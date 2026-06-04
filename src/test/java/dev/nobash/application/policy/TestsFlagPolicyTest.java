package dev.nobash.application.policy;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC7 — the per-operation flag allowlist. Admits ONLY the {@code run_tests}/Maven seed
 * ({@code -o}/{@code --offline}, {@code --fail-at-end}/{@code -fae}); silently DROPS every
 * other flag (security-model.md: unknown flags never reach the process). The three forbidden
 * categories — defeat-the-verb, arbitrary {@code -D}, stdout-verbosity — plus selection flags
 * and raw injection strings are all dropped.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TestsFlagPolicyTest {

    private final TestsFlagPolicy policy = new TestsFlagPolicy();

    @Nested
    class admits_the_seed_allowlist {

        @ParameterizedTest
        @CsvFileSource(resources = "/security/good-flags.csv", numLinesToSkip = 1)
        void each_seed_flag_survives_filtering(String flag) {
            assertThat(policy.filter(List.of(flag))).containsExactly(flag);
        }

        @Test
        void keeps_seed_flags_in_order_and_admits_all_four_together() {
            List<String> seed = List.of("-o", "--offline", "--fail-at-end", "-fae");

            assertThat(policy.filter(seed)).containsExactlyElementsOf(seed);
        }
    }

    @Nested
    class silently_drops_everything_outside_the_seed {

        @ParameterizedTest
        @CsvFileSource(resources = "/security/bad-flags.csv", numLinesToSkip = 1)
        void each_forbidden_flag_is_dropped(String flag, String category) {
            assertThat(policy.filter(List.of(flag)))
                    .as("category: %s — flag %s must be dropped", category, flag)
                    .isEmpty();
        }

        @Test
        void drops_the_forbidden_flag_but_keeps_an_interleaved_seed_flag() {
            List<String> mixed = List.of("-o", "-DskipTests", "--fail-at-end", "-X");

            assertThat(policy.filter(mixed)).containsExactly("-o", "--fail-at-end");
        }

        @Test
        void an_empty_input_yields_an_empty_result() {
            assertThat(policy.filter(List.of())).isEmpty();
        }
    }
}
