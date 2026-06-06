package dev.nobash.domain.result;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InstallSummaryParser} (PRD-3, slice 3).
 *
 * <p>Covers all npm stdout shapes: added-only, added+removed+changed, no-op "up to date",
 * empty/null input, and multi-package grammar.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InstallSummaryParserTest {

    @Nested
    class nominal_npm_output_shapes {

        @Test
        void parses_added_removed_changed_from_full_npm_summary() {
            String npmOut = "added 12 packages, removed 3 packages, changed 1 package, "
                    + "and audited 42 packages in 2s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(12);
            assertThat(summary.removed()).isEqualTo(3);
            assertThat(summary.changed()).isEqualTo(1);
        }

        @Test
        void parses_added_only() {
            String npmOut = "added 5 packages in 1s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(5);
            assertThat(summary.removed()).isEqualTo(0);
            assertThat(summary.changed()).isEqualTo(0);
        }

        @Test
        void parses_added_and_audited_without_removed_or_changed() {
            String npmOut = "added 10 packages, and audited 30 packages in 3s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(10);
            assertThat(summary.removed()).isEqualTo(0);
            assertThat(summary.changed()).isEqualTo(0);
        }

        @Test
        void parses_up_to_date_as_all_zeros() {
            String npmOut = "up to date, audited 42 packages in 1s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(0);
            assertThat(summary.removed()).isEqualTo(0);
            assertThat(summary.changed()).isEqualTo(0);
        }

        @Test
        void parses_singular_package_grammar() {
            // npm uses "package" (singular) for count 1.
            String npmOut = "added 1 package, removed 1 package, changed 1 package in 2s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(1);
            assertThat(summary.removed()).isEqualTo(1);
            assertThat(summary.changed()).isEqualTo(1);
        }

        @Test
        void handles_large_counts() {
            String npmOut = "added 1234 packages, removed 567 packages, changed 89 packages in 30s";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(1234);
            assertThat(summary.removed()).isEqualTo(567);
            assertThat(summary.changed()).isEqualTo(89);
        }
    }

    @Nested
    class defensive_fallback_cases {

        @Test
        void null_input_returns_empty_summary() {
            InstallSummary summary = InstallSummaryParser.parse(null);

            assertThat(summary).isEqualTo(InstallSummary.EMPTY);
        }

        @Test
        void blank_input_returns_empty_summary() {
            InstallSummary summary = InstallSummaryParser.parse("   ");

            assertThat(summary).isEqualTo(InstallSummary.EMPTY);
        }

        @Test
        void unrecognized_output_returns_all_zeros() {
            // A completely unrecognized output format should yield EMPTY, not throw.
            String npmOut = "Some unexpected npm output format that is not parseable";

            InstallSummary summary = InstallSummaryParser.parse(npmOut);

            assertThat(summary.added()).isEqualTo(0);
            assertThat(summary.removed()).isEqualTo(0);
            assertThat(summary.changed()).isEqualTo(0);
        }

        @Test
        void empty_string_returns_empty_summary() {
            InstallSummary summary = InstallSummaryParser.parse("");

            assertThat(summary).isEqualTo(InstallSummary.EMPTY);
        }
    }
}
