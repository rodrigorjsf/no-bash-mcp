package dev.nobash.domain.git;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, I/O-free golden-fixture tests for {@link GitLogParser} (PRD-002, issue #26).
 *
 * <p>Each test constructs a pre-baked {@code git log --format=} text fixture using the
 * unit/record separator scheme expected by the parser, then asserts the normalized
 * {@link GitCommit} list. No File, no Path, no process — mirroring {@code GitStatusParserTest}.</p>
 *
 * <p>The format string is {@code %H%x1f%h%x1f%an%x1f%aI%x1f%s%x1e}, where {@code %x1f}
 * (ASCII 31, unit separator) delimits fields and {@code %x1e} (ASCII 30, record separator)
 * terminates each record.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitLogParserTest {

    private static final char US = (char) 0x1F; // unit separator
    private static final char RS = (char) 0x1E; // record separator

    private static final GitLogParser PARSER = new GitLogParser();

    // ---- golden fixtures ----

    @Test
    void three_commits_parse_to_three_records_in_order() {
        String fixture = sha(1) + US + "abc1234" + US + "Alice Smith" + US + "2024-03-15T10:23:00+00:00" + US + "feat: add widget" + RS
                + sha(2) + US + "def2345" + US + "Bob Jones" + US + "2024-03-14T09:15:00+00:00" + US + "fix: null pointer" + RS
                + sha(3) + US + "ghi3456" + US + "Carol Davis" + US + "2024-03-13T08:00:00+00:00" + US + "chore: deps" + RS;

        List<GitCommit> commits = PARSER.parse(fixture);

        assertThat(commits).hasSize(3);

        GitCommit first = commits.get(0);
        assertThat(first.sha()).isEqualTo(sha(1));
        assertThat(first.abbrev()).isEqualTo("abc1234");
        assertThat(first.author()).isEqualTo("Alice Smith");
        assertThat(first.dateIso()).isEqualTo("2024-03-15T10:23:00+00:00");
        assertThat(first.subject()).isEqualTo("feat: add widget");

        GitCommit second = commits.get(1);
        assertThat(second.sha()).isEqualTo(sha(2));
        assertThat(second.author()).isEqualTo("Bob Jones");
        assertThat(second.subject()).isEqualTo("fix: null pointer");

        GitCommit third = commits.get(2);
        assertThat(third.sha()).isEqualTo(sha(3));
        assertThat(third.author()).isEqualTo("Carol Davis");
    }

    @Test
    void subject_containing_spaces_parses_correctly() {
        String fixture = sha(1) + US + "abc1234" + US + "Author Name" + US + "2024-01-01T00:00:00+00:00"
                + US + "fix: correct spaces in path /src/foo bar.txt" + RS;

        List<GitCommit> commits = PARSER.parse(fixture);

        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).subject()).isEqualTo("fix: correct spaces in path /src/foo bar.txt");
    }

    @Test
    void author_name_containing_spaces_parses_correctly() {
        String fixture = sha(1) + US + "abc1234" + US + "Jean Paul Sartre" + US + "2024-01-01T00:00:00+00:00"
                + US + "feat: existential refactor" + RS;

        List<GitCommit> commits = PARSER.parse(fixture);

        assertThat(commits).hasSize(1);
        assertThat(commits.get(0).author()).isEqualTo("Jean Paul Sartre");
    }

    @Test
    void empty_repo_with_no_commits_returns_empty_list() {
        // git log on a repo with no commits produces empty output.
        List<GitCommit> commits = PARSER.parse("");

        assertThat(commits).isEmpty();
    }

    @Test
    void null_input_returns_empty_list() {
        List<GitCommit> commits = PARSER.parse(null);

        assertThat(commits).isEmpty();
    }

    @Test
    void blank_input_returns_empty_list() {
        List<GitCommit> commits = PARSER.parse("   \n  ");

        assertThat(commits).isEmpty();
    }

    @Test
    void single_commit_parses_all_five_fields() {
        String sha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        String fixture = sha + US + "a1b2c3d" + US + "Developer" + US + "2025-06-05T12:00:00+02:00"
                + US + "docs: update README" + RS;

        List<GitCommit> commits = PARSER.parse(fixture);

        assertThat(commits).hasSize(1);
        GitCommit commit = commits.get(0);
        assertThat(commit.sha()).isEqualTo(sha);
        assertThat(commit.abbrev()).isEqualTo("a1b2c3d");
        assertThat(commit.author()).isEqualTo("Developer");
        assertThat(commit.dateIso()).isEqualTo("2025-06-05T12:00:00+02:00");
        assertThat(commit.subject()).isEqualTo("docs: update README");
    }

    @Test
    void trailing_newline_after_record_separator_does_not_produce_spurious_empty_entry() {
        String fixture = sha(1) + US + "abc1234" + US + "Author" + US + "2024-01-01T00:00:00+00:00"
                + US + "subject" + RS + "\n";

        List<GitCommit> commits = PARSER.parse(fixture);

        // The trailing newline after the RS must not produce a spurious empty entry.
        assertThat(commits).hasSize(1);
    }

    @Test
    void limit_of_one_commit_returns_exactly_one() {
        // Only one record in output — the caller passes -n 1 to git.
        String fixture = sha(1) + US + "abc1234" + US + "Author" + US + "2024-01-01T00:00:00+00:00"
                + US + "only commit" + RS;

        List<GitCommit> commits = PARSER.parse(fixture);

        assertThat(commits).hasSize(1);
    }

    // ---- helper ----

    /**
     * Generate a deterministic 40-hex SHA string for test records. Uses a repeated digit pair
     * so different records have distinct SHAs without actual git hashes.
     */
    private static String sha(int n) {
        String hex = String.format("%02x", n & 0xFF);
        return hex.repeat(20);
    }
}
