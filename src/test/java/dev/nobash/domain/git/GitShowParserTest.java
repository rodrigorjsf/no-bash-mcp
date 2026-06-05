package dev.nobash.domain.git;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, I/O-free golden-fixture tests for {@link GitShowParser} (PRD-002, issue #26).
 *
 * <p>Each test constructs a pre-baked {@code git show -s --format=} text fixture using the
 * unit separator scheme and asserts the normalized {@link GitCommitDetail}.
 * No File, no Path, no process — mirroring {@code GitStatusParserTest}.</p>
 *
 * <p>The format string is {@code %H%x1f%h%x1f%an%x1f%aI%x1f%s%x1f%b}, where {@code %x1f}
 * (ASCII 31, unit separator) delimits all fields including the body (which is the last field
 * and may be multi-line).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitShowParserTest {

    private static final char US = (char) 0x1F; // unit separator

    private static final GitShowParser PARSER = new GitShowParser();

    // ---- golden fixtures ----

    @Test
    void commit_with_no_body_parses_all_metadata_fields_and_null_body() {
        String sha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        String fixture = sha + US + "a1b2c3d" + US + "Alice Smith" + US
                + "2024-03-15T10:23:00+00:00" + US + "feat: add widget" + US + "";

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.sha()).isEqualTo(sha);
        assertThat(detail.abbrev()).isEqualTo("a1b2c3d");
        assertThat(detail.author()).isEqualTo("Alice Smith");
        assertThat(detail.dateIso()).isEqualTo("2024-03-15T10:23:00+00:00");
        assertThat(detail.subject()).isEqualTo("feat: add widget");
        assertThat(detail.body()).as("no body → null").isNull();
    }

    @Test
    void commit_with_multi_line_body_captures_the_full_body() {
        String sha = "b1b2c3d4e5f6b1b2c3d4e5f6b1b2c3d4e5f6b1b2";
        String body = "This is the first line of the body.\n\nSecond paragraph here.";
        String fixture = sha + US + "b1b2c3d" + US + "Bob Jones" + US
                + "2024-03-14T09:15:00+00:00" + US + "fix: null pointer" + US + body;

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.sha()).isEqualTo(sha);
        assertThat(detail.subject()).isEqualTo("fix: null pointer");
        assertThat(detail.body()).isEqualTo(body);
    }

    @Test
    void author_name_with_spaces_parses_correctly() {
        String sha = "c1c2c3d4e5f6c1c2c3d4e5f6c1c2c3d4e5f6c1c2";
        String fixture = sha + US + "c1c2c3d" + US + "Jean Paul Sartre" + US
                + "2024-01-01T00:00:00+00:00" + US + "feat: existential refactor" + US + "";

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.author()).isEqualTo("Jean Paul Sartre");
    }

    @Test
    void subject_with_colon_and_spaces_parses_correctly() {
        String sha = "d1d2d3d4e5f6d1d2d3d4e5f6d1d2d3d4e5f6d1d2";
        String fixture = sha + US + "d1d2d3d" + US + "Dev" + US + "2024-01-01T00:00:00+00:00"
                + US + "fix: correct path /src/foo bar.txt in parser" + US + "";

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.subject()).isEqualTo("fix: correct path /src/foo bar.txt in parser");
    }

    @Test
    void null_input_returns_all_null_fields() {
        GitCommitDetail detail = PARSER.parse(null);

        assertThat(detail).isNotNull();
        assertThat(detail.sha()).isNull();
        assertThat(detail.abbrev()).isNull();
        assertThat(detail.author()).isNull();
        assertThat(detail.dateIso()).isNull();
        assertThat(detail.subject()).isNull();
        assertThat(detail.body()).isNull();
    }

    @Test
    void blank_input_returns_all_null_fields() {
        GitCommitDetail detail = PARSER.parse("   ");

        assertThat(detail).isNotNull();
        assertThat(detail.sha()).isNull();
    }

    @Test
    void body_with_only_whitespace_is_null_after_trimming() {
        String sha = "e1e2e3d4e5f6e1e2e3d4e5f6e1e2e3d4e5f6e1e2";
        // body field contains only whitespace — trimming makes it null.
        String fixture = sha + US + "e1e2e3d" + US + "Author" + US
                + "2024-01-01T00:00:00+00:00" + US + "chore: deps" + US + "   \n  ";

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.body()).as("blank body → null").isNull();
    }

    @Test
    void body_containing_diff_like_lines_is_captured_verbatim() {
        // Ensures the parser does not confuse body content with diff markers.
        String sha = "f1f2f3d4e5f6f1f2f3d4e5f6f1f2f3d4e5f6f1f2";
        String body = "Closes #42\n\n+++ b/src/main/Foo.java\n--- a/src/main/Foo.java";
        String fixture = sha + US + "f1f2f3d" + US + "Author" + US
                + "2024-01-01T00:00:00+00:00" + US + "fix: the thing" + US + body;

        GitCommitDetail detail = PARSER.parse(fixture);

        assertThat(detail.body()).isEqualTo(body);
    }
}
