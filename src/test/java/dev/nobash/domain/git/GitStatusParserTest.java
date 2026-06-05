package dev.nobash.domain.git;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, I/O-free golden-fixture tests for {@link GitStatusParser} (PRD-002, issue #24).
 *
 * <p>Each test reads a pre-baked {@code git status --porcelain=v2 --branch} text fixture from the
 * classpath (in-memory), hands it to the pure parser, and asserts the normalized {@link GitStatus}.
 * No File, no Path, no process — exactly mirroring {@code CompileDiagnosticParserTest}. The matrix
 * exhaustively covers the porcelain-robustness edges (clean, dirty, no-upstream, detached,
 * ahead/behind, rename, initial-commit, unmerged) so the temp-git IT only needs the happy path.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitStatusParserTest {

    private static final GitStatusParser PARSER = new GitStatusParser();

    // ---- golden fixtures ----

    @Test
    void clean_repo_with_upstream_has_no_changes_and_zero_ahead_behind() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-clean.txt"));

        assertThat(status.branch()).isEqualTo("main");
        assertThat(status.detached()).isFalse();
        assertThat(status.upstream()).isEqualTo("origin/main");
        assertThat(status.ahead()).isEqualTo(0);
        assertThat(status.behind()).isEqualTo(0);
        assertThat(status.staged()).isEmpty();
        assertThat(status.unstaged()).isEmpty();
        assertThat(status.untracked()).isEmpty();
        assertThat(status.clean()).isTrue();
    }

    @Test
    void dirty_repo_buckets_staged_unstaged_and_untracked_correctly() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-dirty.txt"));

        // Staged: M. (staged-only), MM (both), A. (added staged) → 3 entries.
        assertThat(status.staged()).extracting(GitStatusEntry::path)
                .containsExactlyInAnyOrder("src/staged-only.txt", "src/both-sides.txt", "src/added staged.txt");
        // Unstaged: .M (unstaged-only), MM (both) → 2 entries.
        assertThat(status.unstaged()).extracting(GitStatusEntry::path)
                .containsExactlyInAnyOrder("src/unstaged-only.txt", "src/both-sides.txt");
        // Untracked: two ? lines, one with a space in the path.
        assertThat(status.untracked()).extracting(GitStatusEntry::path)
                .containsExactlyInAnyOrder("untracked-one.txt", "src/untracked two.txt");
        assertThat(status.clean()).isFalse();
    }

    @Test
    void a_path_with_spaces_survives_intact() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-dirty.txt"));

        // The ordinary entry "src/added staged.txt" has a space in its path — porcelain v2 does
        // not quote the path, so the parser must take the whole line tail.
        assertThat(status.staged()).extracting(GitStatusEntry::path)
                .contains("src/added staged.txt");
        assertThat(status.untracked()).extracting(GitStatusEntry::path)
                .contains("src/untracked two.txt");
    }

    @Test
    void no_upstream_leaves_upstream_and_ahead_behind_null_never_throws() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-no-upstream.txt"));

        assertThat(status.branch()).isEqualTo("feature-no-tracking");
        assertThat(status.detached()).isFalse();
        // The #1 porcelain bug: assuming branch.upstream / branch.ab are always present.
        assertThat(status.upstream()).as("no tracking branch → no branch.upstream header").isNull();
        assertThat(status.ahead()).as("no upstream → no branch.ab header → ahead null").isNull();
        assertThat(status.behind()).as("no upstream → no branch.ab header → behind null").isNull();
        assertThat(status.unstaged()).extracting(GitStatusEntry::path).containsExactly("README.md");
    }

    @Test
    void detached_head_sets_detached_true_and_branch_marker() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-detached.txt"));

        assertThat(status.detached()).isTrue();
        assertThat(status.branch()).isEqualTo("(detached)");
        assertThat(status.upstream()).isNull();
        assertThat(status.ahead()).isNull();
        assertThat(status.behind()).isNull();
        assertThat(status.clean()).isTrue();
    }

    @Test
    void ahead_behind_are_parsed_as_signed_integers() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-ahead-behind.txt"));

        assertThat(status.ahead()).isEqualTo(3);
        assertThat(status.behind()).isEqualTo(2);
        assertThat(status.upstream()).isEqualTo("origin/main");
    }

    @Test
    void rename_entry_records_new_path_and_orig_path() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-rename.txt"));

        // R. → staged (index half is R), worktree half is . (no unstaged).
        assertThat(status.staged()).hasSize(1);
        GitStatusEntry renamed = status.staged().get(0);
        assertThat(renamed.path()).isEqualTo("new-name.txt");
        assertThat(renamed.origPath()).isEqualTo("old-name.txt");
        assertThat(renamed.code()).isEqualTo("R.");
        assertThat(status.unstaged()).isEmpty();
    }

    @Test
    void initial_commit_repo_has_null_ahead_behind_and_untracked_files() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-initial.txt"));

        assertThat(status.branch()).isEqualTo("main");
        assertThat(status.upstream()).isNull();
        assertThat(status.ahead()).isNull();
        assertThat(status.behind()).isNull();
        assertThat(status.untracked()).extracting(GitStatusEntry::path).containsExactly("first-file.txt");
    }

    @Test
    void unmerged_lines_are_tolerated_without_throwing_or_misfiling() {
        GitStatus status = PARSER.parse(read("fixtures/git/status-unmerged.txt"));

        // The 'u' unmerged line is tolerated (this slice has no unmerged bucket) — it must NOT
        // crash the parser and must NOT be misfiled into staged/unstaged/untracked.
        assertThat(status.staged()).isEmpty();
        assertThat(status.unstaged()).isEmpty();
        assertThat(status.untracked()).extracting(GitStatusEntry::path).containsExactly("still-untracked.txt");
        assertThat(status.ahead()).isEqualTo(1);
        assertThat(status.behind()).isEqualTo(1);
    }

    // ---- null / blank safety ----

    @Test
    void null_input_yields_all_null_headers_and_empty_buckets() {
        GitStatus status = PARSER.parse(null);

        assertThat(status.branch()).isNull();
        assertThat(status.upstream()).isNull();
        assertThat(status.ahead()).isNull();
        assertThat(status.behind()).isNull();
        assertThat(status.staged()).isEmpty();
        assertThat(status.unstaged()).isEmpty();
        assertThat(status.untracked()).isEmpty();
        assertThat(status.clean()).isTrue();
    }

    @Test
    void blank_input_yields_empty_status() {
        GitStatus status = PARSER.parse("   \n  ");

        assertThat(status.branch()).isNull();
        assertThat(status.clean()).isTrue();
    }

    // ---- individual line-shape assertions ----

    @Test
    void a_both_sides_modified_file_appears_in_staged_and_unstaged() {
        String mm = "1 MM N... 100644 100644 100644 "
                + "1111111111111111111111111111111111111111 "
                + "2222222222222222222222222222222222222222 file.txt\n";
        GitStatus status = PARSER.parse("# branch.head main\n" + mm);

        assertThat(status.staged()).extracting(GitStatusEntry::path).containsExactly("file.txt");
        assertThat(status.unstaged()).extracting(GitStatusEntry::path).containsExactly("file.txt");
    }

    @Test
    void staged_only_modification_does_not_appear_in_unstaged() {
        String staged = "1 M. N... 100644 100644 100644 "
                + "1111111111111111111111111111111111111111 "
                + "2222222222222222222222222222222222222222 only-staged.txt\n";
        GitStatus status = PARSER.parse("# branch.head main\n" + staged);

        assertThat(status.staged()).extracting(GitStatusEntry::path).containsExactly("only-staged.txt");
        assertThat(status.unstaged()).isEmpty();
    }

    @Test
    void deletion_codes_are_bucketed_like_modifications() {
        // D. = staged deletion; .D = unstaged deletion.
        String deletions = "1 D. N... 100644 000000 100644 "
                + "1111111111111111111111111111111111111111 "
                + "0000000000000000000000000000000000000000 deleted-staged.txt\n"
                + "1 .D N... 100644 100644 000000 "
                + "2222222222222222222222222222222222222222 "
                + "2222222222222222222222222222222222222222 deleted-unstaged.txt\n";
        GitStatus status = PARSER.parse("# branch.head main\n" + deletions);

        assertThat(status.staged()).extracting(GitStatusEntry::path).containsExactly("deleted-staged.txt");
        assertThat(status.unstaged()).extracting(GitStatusEntry::path).containsExactly("deleted-unstaged.txt");
    }

    @Test
    void malformed_entry_line_is_skipped_not_fatal() {
        // A truncated ordinary line with too few fields must not throw, just be skipped.
        GitStatus status = PARSER.parse("# branch.head main\n1 M. short\n? real-untracked.txt\n");

        assertThat(status.staged()).isEmpty();
        assertThat(status.untracked()).extracting(GitStatusEntry::path).containsExactly("real-untracked.txt");
    }

    // ---- helper ----

    private static String read(String resourcePath) {
        try (InputStream is = GitStatusParserTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read fixture: " + resourcePath, e);
        }
    }
}
