package dev.nobash.domain.git;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for {@link GitBranchParser} (PRD-002, issue #28), AC#3.
 *
 * <p>Tests use golden {@code --format=} fixtures (the exact byte-level output from real git) to
 * verify the parser's field splitting, current-branch detection, upstream parsing, and
 * ahead/behind extraction without any process or I/O.</p>
 *
 * <p>The unit separator (ASCII 31) is embedded as {@code FIELD_SEP} to mirror the format string
 * exactly.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitBranchParserTest {

    private static final char FS = GitBranchParser.FIELD_SEP;

    /** Build a single fixture line: name | HEAD-marker | upstream | track. */
    private static String line(String name, String head, String upstream, String track) {
        return name + FS + head + FS + upstream + FS + track;
    }

    private final GitBranchParser parser = new GitBranchParser();

    // ============================================================================================
    // Empty / null input
    // ============================================================================================

    @Nested
    class empty_and_null_input {

        @Test
        void null_input_returns_empty_list() {
            List<GitBranchEntry> result = parser.parse(null);
            assertThat(result).isEmpty();
        }

        @Test
        void blank_input_returns_empty_list() {
            List<GitBranchEntry> result = parser.parse("   ");
            assertThat(result).isEmpty();
        }

        @Test
        void empty_string_returns_empty_list() {
            List<GitBranchEntry> result = parser.parse("");
            assertThat(result).isEmpty();
        }
    }

    // ============================================================================================
    // Single branch — no upstream (local-only branch)
    // ============================================================================================

    @Nested
    class single_branch_no_upstream {

        @Test
        void current_branch_main_no_upstream() {
            // git emits: "main" + FS + "*" + FS + "" + FS + ""
            String output = line("main", "*", "", "");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.name()).isEqualTo("main");
            assertThat(entry.current()).isTrue();
            assertThat(entry.upstream()).as("no upstream → null").isNull();
            assertThat(entry.ahead()).as("no upstream → ahead null").isNull();
            assertThat(entry.behind()).as("no upstream → behind null").isNull();
        }

        @Test
        void non_current_branch_feature_no_upstream() {
            String output = line("feature/my-work", " ", "", "");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.name()).isEqualTo("feature/my-work");
            assertThat(entry.current()).isFalse();
            assertThat(entry.upstream()).isNull();
            assertThat(entry.ahead()).isNull();
            assertThat(entry.behind()).isNull();
        }
    }

    // ============================================================================================
    // Branch with upstream — various track states
    // ============================================================================================

    @Nested
    class branch_with_upstream {

        @Test
        void up_to_date_branch_has_zero_ahead_zero_behind() {
            // upstream:track,nobracket → empty string when up to date
            String output = line("main", "*", "origin/main", "");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.name()).isEqualTo("main");
            assertThat(entry.current()).isTrue();
            assertThat(entry.upstream()).isEqualTo("origin/main");
            assertThat(entry.ahead()).as("up-to-date → ahead=0").isEqualTo(0);
            assertThat(entry.behind()).as("up-to-date → behind=0").isEqualTo(0);
        }

        @Test
        void branch_ahead_of_upstream() {
            // upstream:track,nobracket → "ahead 3"
            String output = line("main", "*", "origin/main", "ahead 3");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.upstream()).isEqualTo("origin/main");
            assertThat(entry.ahead()).isEqualTo(3);
            assertThat(entry.behind()).isEqualTo(0);
        }

        @Test
        void branch_behind_upstream() {
            // upstream:track,nobracket → "behind 2"
            String output = line("main", "*", "origin/main", "behind 2");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.upstream()).isEqualTo("origin/main");
            assertThat(entry.ahead()).isEqualTo(0);
            assertThat(entry.behind()).isEqualTo(2);
        }

        @Test
        void branch_diverged_from_upstream() {
            // upstream:track,nobracket → "ahead 2, behind 1"
            String output = line("main", "*", "origin/main", "ahead 2, behind 1");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.upstream()).isEqualTo("origin/main");
            assertThat(entry.ahead()).isEqualTo(2);
            assertThat(entry.behind()).isEqualTo(1);
        }

        @Test
        void upstream_gone_produces_null_upstream_and_null_counts() {
            // upstream:track,nobracket → "gone" when the remote tracking branch was deleted
            String output = line("feature", " ", "origin/feature", "gone");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(1);
            GitBranchEntry entry = entries.get(0);
            assertThat(entry.upstream()).as("gone upstream → null upstream").isNull();
            assertThat(entry.ahead()).as("gone upstream → ahead null").isNull();
            assertThat(entry.behind()).as("gone upstream → behind null").isNull();
        }
    }

    // ============================================================================================
    // Multiple branches — list ordering preserved
    // ============================================================================================

    @Nested
    class multiple_branches {

        @Test
        void two_branches_correct_order_and_current_flag() {
            String output = line("feature/a", " ", "", "") + "\n"
                    + line("main", "*", "origin/main", "ahead 1");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(2);

            GitBranchEntry feature = entries.get(0);
            assertThat(feature.name()).isEqualTo("feature/a");
            assertThat(feature.current()).isFalse();
            assertThat(feature.upstream()).isNull();

            GitBranchEntry main = entries.get(1);
            assertThat(main.name()).isEqualTo("main");
            assertThat(main.current()).isTrue();
            assertThat(main.upstream()).isEqualTo("origin/main");
            assertThat(main.ahead()).isEqualTo(1);
            assertThat(main.behind()).isEqualTo(0);
        }

        @Test
        void blank_lines_between_entries_are_skipped() {
            String output = line("a", "*", "", "") + "\n\n"
                    + line("b", " ", "", "") + "\n";

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).name()).isEqualTo("a");
            assertThat(entries.get(1).name()).isEqualTo("b");
        }
    }

    // ============================================================================================
    // Robustness — malformed / edge-case lines
    // ============================================================================================

    @Nested
    class robustness {

        @Test
        void line_with_fewer_than_two_fields_is_skipped() {
            // Only a name field, no FIELD_SEP at all.
            String output = "bare-branch-name\n"
                    + line("valid", "*", "", "");

            List<GitBranchEntry> entries = parser.parse(output);

            // The malformed line is skipped; only the valid line is parsed.
            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).name()).isEqualTo("valid");
        }

        @Test
        void mixed_windows_and_unix_line_endings_are_both_accepted() {
            String output = line("main", "*", "", "") + "\r\n"
                    + line("dev", " ", "", "");

            List<GitBranchEntry> entries = parser.parse(output);

            assertThat(entries).hasSize(2);
        }
    }
}
