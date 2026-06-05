package dev.nobash.domain.git;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, I/O-free golden-fixture tests for {@link GitDiffParser} (PRD-002, issue #27).
 *
 * <p>Each test provides pre-baked {@code git diff --numstat} and {@code git diff --name-status}
 * output strings and asserts the normalized {@link GitDiffEntry} list. No File, no Path, no
 * process — subject to the {@code the_git_domain_is_io_free} ArchUnit rule.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitDiffParserTest {

    private static final GitDiffParser PARSER = new GitDiffParser();

    // ---- golden fixtures ----

    @Test
    void a_single_modified_file_produces_one_entry() {
        String numstat = "5\t2\tsrc/main/Foo.java\n";
        String nameStatus = "M\tsrc/main/Foo.java\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        GitDiffEntry e = entries.get(0);
        assertThat(e.path()).isEqualTo("src/main/Foo.java");
        assertThat(e.added()).isEqualTo(5);
        assertThat(e.deleted()).isEqualTo(2);
        assertThat(e.status()).isEqualTo("M");
    }

    @Test
    void a_new_file_has_status_A_and_zero_deleted() {
        String numstat = "10\t0\tnewfile.txt\n";
        String nameStatus = "A\tnewfile.txt\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        GitDiffEntry e = entries.get(0);
        assertThat(e.path()).isEqualTo("newfile.txt");
        assertThat(e.added()).isEqualTo(10);
        assertThat(e.deleted()).isEqualTo(0);
        assertThat(e.status()).isEqualTo("A");
    }

    @Test
    void a_deleted_file_has_status_D_and_zero_added() {
        String numstat = "0\t7\told.txt\n";
        String nameStatus = "D\told.txt\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        GitDiffEntry e = entries.get(0);
        assertThat(e.path()).isEqualTo("old.txt");
        assertThat(e.added()).isEqualTo(0);
        assertThat(e.deleted()).isEqualTo(7);
        assertThat(e.status()).isEqualTo("D");
    }

    @Test
    void a_renamed_file_uses_the_new_path_from_name_status() {
        // numstat for renames emits: added\tdeleted\t{old => new} or similar — we ignore the
        // numstat path; the new path comes from the name-status second TAB field.
        String numstat = "3\t3\t{old => new}.txt\n";
        String nameStatus = "R100\told.txt\tnew.txt\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        GitDiffEntry e = entries.get(0);
        // Path from name-status: the new path (after the second TAB).
        assertThat(e.path()).isEqualTo("new.txt");
        assertThat(e.added()).isEqualTo(3);
        assertThat(e.deleted()).isEqualTo(3);
        assertThat(e.status()).isEqualTo("R");
    }

    @Test
    void a_binary_file_has_null_added_and_deleted() {
        String numstat = "-\t-\timage.png\n";
        String nameStatus = "M\timage.png\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        GitDiffEntry e = entries.get(0);
        assertThat(e.path()).isEqualTo("image.png");
        assertThat(e.added()).as("binary file added must be null").isNull();
        assertThat(e.deleted()).as("binary file deleted must be null").isNull();
        assertThat(e.status()).isEqualTo("M");
    }

    @Test
    void three_files_produce_three_entries_in_order() {
        String numstat = "1\t0\ta.txt\n" +
                "0\t5\tb.txt\n" +
                "3\t3\tc.txt\n";
        String nameStatus = "A\ta.txt\n" +
                "D\tb.txt\n" +
                "M\tc.txt\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).path()).isEqualTo("a.txt");
        assertThat(entries.get(0).status()).isEqualTo("A");
        assertThat(entries.get(1).path()).isEqualTo("b.txt");
        assertThat(entries.get(1).status()).isEqualTo("D");
        assertThat(entries.get(2).path()).isEqualTo("c.txt");
        assertThat(entries.get(2).status()).isEqualTo("M");
    }

    @Test
    void a_path_containing_spaces_parses_correctly() {
        String numstat = "2\t1\tsrc/my module/Foo.java\n";
        String nameStatus = "M\tsrc/my module/Foo.java\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path()).isEqualTo("src/my module/Foo.java");
    }

    @Test
    void null_numstat_input_returns_empty_list() {
        List<GitDiffEntry> entries = PARSER.parse(null, "M\tfile.txt\n");
        assertThat(entries).isEmpty();
    }

    @Test
    void null_name_status_input_returns_empty_list() {
        List<GitDiffEntry> entries = PARSER.parse("1\t0\tfile.txt\n", null);
        assertThat(entries).isEmpty();
    }

    @Test
    void both_null_inputs_return_empty_list() {
        List<GitDiffEntry> entries = PARSER.parse(null, null);
        assertThat(entries).isEmpty();
    }

    @Test
    void blank_inputs_return_empty_list() {
        List<GitDiffEntry> entries = PARSER.parse("   \n  ", "  ");
        assertThat(entries).isEmpty();
    }

    @Test
    void a_type_changed_file_has_status_T() {
        String numstat = "0\t0\tsymlink\n";
        String nameStatus = "T\tsymlink\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).status()).isEqualTo("T");
    }

    @Test
    void a_copied_file_uses_the_new_path_from_name_status() {
        String numstat = "5\t0\toriginal.txt\n";
        String nameStatus = "C80\toriginal.txt\tcopy.txt\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).path()).isEqualTo("copy.txt");
        assertThat(entries.get(0).status()).isEqualTo("C");
    }

    @Test
    void trailing_newlines_do_not_produce_spurious_empty_entries() {
        String numstat = "1\t1\tfoo.java\n\n";
        String nameStatus = "M\tfoo.java\n\n";

        List<GitDiffEntry> entries = PARSER.parse(numstat, nameStatus);

        assertThat(entries).hasSize(1);
    }
}
