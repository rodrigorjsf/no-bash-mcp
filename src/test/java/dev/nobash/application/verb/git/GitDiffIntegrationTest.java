package dev.nobash.application.verb.git;

import dev.nobash.adapter.out.ecosystem.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitDiffEntry;
import dev.nobash.testsupport.git.GitRepoFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test for {@code git_diff} (PRD-002, issue #27) against a REAL,
 * programmatically-scripted temp git repository built by {@link GitRepoFixture}.
 *
 * <p>This is a Surefire {@code *Test} (NOT a Failsafe {@code *IT}) so it rides the
 * {@code mvn test} gate, but it {@code assumeTrue(GitRepoFixture.gitAvailable())} so it
 * SELF-SKIPS on a git-less runner.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitDiffIntegrationTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — the temp-git integration harness cannot run");
    }

    private static RawOutputStash sharedStash() {
        return new RawOutputStash();
    }

    private static GitDiffUseCase useCase(RawOutputStash stash) {
        return new GitDiffUseCase(
                new GitCommandExecutor(new PathScanningManagerResolver()),
                stash);
    }

    @Test
    void git_diff_on_a_clean_tree_returns_ok_true_with_empty_files_list(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("seed commit");

        Envelope env = useCase(sharedStash()).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_diff");
        assertThat(env.manager()).as("manager is null for git verbs").isNull();
        assertThat(env.untrusted()).isTrue();
        assertThat(env.gitDiff()).as("clean tree: gitDiff[] must be empty but not null").isNotNull().isEmpty();
        assertThat(env.handle()).as("handle must be present even on an empty diff").isNotNull();
    }

    @Test
    void git_diff_on_a_dirty_tree_with_staged_change_returns_the_modified_file(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "line1\nline2\n").add().commit("seed");
        // Stage a modification.
        repo.writeFile("file.txt", "line1\nline2\nline3\n").add("file.txt");

        Envelope env = useCase(sharedStash()).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitDiff()).as("staged change: gitDiff[] must be non-empty").isNotNull().isNotEmpty();
        GitDiffEntry entry = env.gitDiff().stream()
                .filter(e -> "file.txt".equals(e.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected file.txt in gitDiff[]"));
        assertThat(entry.status()).isEqualTo("M");
        assertThat(entry.added()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void git_diff_on_a_dirty_tree_with_unstaged_change_returns_the_modified_file(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "v1\n").add().commit("seed");
        // Unstaged modification (do not add).
        repo.writeFile("file.txt", "v1\nv2\n");

        Envelope env = useCase(sharedStash()).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitDiff()).as("unstaged change: gitDiff[] must be non-empty").isNotNull().isNotEmpty();
        GitDiffEntry entry = env.gitDiff().stream()
                .filter(e -> "file.txt".equals(e.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected file.txt in gitDiff[]"));
        assertThat(entry.status()).isEqualTo("M");
    }

    @Test
    void the_full_patch_is_stashed_behind_a_handle_and_is_retrievable_via_stash(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("patch.txt", "original\n").add().commit("seed");
        repo.writeFile("patch.txt", "original\nchanged\n").add("patch.txt");

        RawOutputStash stash = new RawOutputStash();
        GitDiffUseCase uc = useCase(stash);

        Envelope env = uc.run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.handle()).as("diff handle must be present").isNotNull();
        assertThat(env.handle().id()).as("handle id must be non-blank").isNotBlank();

        // AC #2: the patch is retrievable via the stash without re-running.
        String patch = stash.get(env.handle());
        assertThat(patch)
                .as("stashed patch must contain the unified diff marker")
                .contains("diff --git");
    }

    @Test
    void a_newly_added_file_appears_with_status_A_in_the_diff(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("seed.txt", "seed\n").add().commit("seed");
        // Stage a new file.
        repo.writeFile("new.txt", "new content\n").add("new.txt");

        Envelope env = useCase(sharedStash()).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        GitDiffEntry entry = env.gitDiff().stream()
                .filter(e -> "new.txt".equals(e.path()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected new.txt in gitDiff[]"));
        assertThat(entry.status()).isEqualTo("A");
        assertThat(entry.added()).isGreaterThanOrEqualTo(1);
        assertThat(entry.deleted()).isEqualTo(0);
    }

    @Test
    void git_diff_on_an_empty_repo_with_no_commits_returns_ok_with_empty_files_list(
            @TempDir Path tmp) {
        // git init but no commits: git diff HEAD exits 128 because HEAD does not resolve.
        // An unborn-HEAD repo IS a valid git repository — it simply has no commits yet.
        // The unborn-HEAD discriminator detects this case and returns ok=true with an empty
        // gitDiff[] and a handle (consistent with the clean-tree contract).
        GitRepoFixture repo = GitRepoFixture.init(tmp);

        RawOutputStash stash = new RawOutputStash();
        Envelope env = useCase(stash).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_diff");
        assertThat(env.gitDiff())
                .as("empty repo: gitDiff[] must be empty but not null")
                .isNotNull().isEmpty();
        assertThat(env.handle()).as("handle must be present even on an empty-repo diff").isNotNull();
    }

    @Test
    void git_diff_on_a_non_repo_directory_returns_NOT_A_GIT_REPOSITORY(@TempDir Path tmp) {
        Envelope env = useCase(sharedStash()).run(tmp.toString(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(env.gitDiff()).isNull();
    }

    @Test
    void blank_path_returns_INVALID_PATH(@TempDir Path tmp) {
        Envelope env = useCase(sharedStash()).run("", null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
    }

    @Test
    void null_path_returns_INVALID_PATH() {
        Envelope env = useCase(sharedStash()).run(null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
    }

    @Test
    void multiple_changed_files_all_appear_in_the_files_list(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("a.txt", "a\n").add()
                .writeFile("b.txt", "b\n").add()
                .commit("seed");
        // Modify both.
        repo.writeFile("a.txt", "a\nmore\n").add("a.txt");
        repo.writeFile("b.txt", "b\nalso\n").add("b.txt");

        Envelope env = useCase(sharedStash()).run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        List<GitDiffEntry> entries = env.gitDiff();
        assertThat(entries).as("both modified files must appear").hasSize(2);
        List<String> paths = entries.stream().map(GitDiffEntry::path).toList();
        assertThat(paths).contains("a.txt", "b.txt");
    }
}
