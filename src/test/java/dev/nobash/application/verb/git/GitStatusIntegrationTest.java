package dev.nobash.application.verb.git;

import dev.nobash.adapter.out.ecosystem.maven.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitStatus;
import dev.nobash.domain.git.GitStatusEntry;
import dev.nobash.testsupport.git.GitRepoFixture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end integration test for {@code git_status} (PRD-002, issue #24) against a REAL,
 * programmatically-scripted temp git repository built by {@link GitRepoFixture} — the reusable
 * harness the later git slices inherit.
 *
 * <p>This is a Surefire {@code *Test} (NOT a Failsafe {@code *IT}) so it rides the {@code mvn test}
 * gate, but it {@code assumeTrue(GitRepoFixture.gitAvailable())} so it SELF-SKIPS on a git-less
 * runner rather than hard-failing. It wires the production {@link GitStatusUseCase} with the
 * production {@link GitCommandExecutor} over the real trusted-git seam — proving the full tracer:
 * launch real git, parse real porcelain v2, assemble the normalized envelope.</p>
 *
 * <p>Scope is deliberately the happy path (clean → dirty → branch): the ahead/behind/detached/
 * no-upstream/rename edges are covered exhaustively and precisely by the pure
 * {@code GitStatusParserTest} golden fixtures, with no remote setup needed.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitStatusIntegrationTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — the temp-git integration harness cannot run");
    }

    private static GitStatusUseCase useCase() {
        return new GitStatusUseCase(new GitCommandExecutor(new PathScanningManagerResolver()));
    }

    @Test
    void a_clean_committed_repo_reports_a_clean_status_on_the_main_branch(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("README.md", "# hello\n")
                .add()
                .commit("initial commit");

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_status");
        assertThat(env.manager()).as("manager is null for git verbs").isNull();
        GitStatus status = env.gitStatus();
        assertThat(status).isNotNull();
        assertThat(status.branch()).isEqualTo(GitRepoFixture.DEFAULT_BRANCH);
        assertThat(status.detached()).isFalse();
        // No remote → no upstream and no ahead/behind.
        assertThat(status.upstream()).isNull();
        assertThat(status.ahead()).isNull();
        assertThat(status.behind()).isNull();
        assertThat(status.clean()).as("a freshly-committed tree is clean").isTrue();
    }

    @Test
    void a_dirty_tree_reports_staged_unstaged_and_untracked_changes(@TempDir Path tmp) {
        // Seed TWO tracked files in a single commit so the working tree is clean to start.
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("staged.txt", "v1\n")
                .writeFile("unstaged.txt", "v1\n")
                .add()
                .commit("seed");

        // Create three distinct change classes WITHOUT any further commit (a later commit would
        // clear the staged state):
        //  - staged.txt modified AND staged          → staged (M.)
        repo.writeFile("staged.txt", "v2\n").add("staged.txt");
        //  - unstaged.txt modified but NOT staged    → unstaged (.M)
        repo.writeFile("unstaged.txt", "v2\n");
        //  - brand-new file, never added             → untracked (?)
        repo.writeFile("worktree-only.txt", "wt\n");

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        GitStatus status = env.gitStatus();
        assertThat(status.clean()).isFalse();

        // staged.txt is staged (M.).
        assertThat(status.staged()).extracting(GitStatusEntry::path).contains("staged.txt");
        // unstaged.txt is modified in the worktree but not staged (.M) → unstaged.
        assertThat(status.unstaged()).extracting(GitStatusEntry::path).contains("unstaged.txt");
        // worktree-only.txt is brand-new and unstaged → untracked.
        assertThat(status.untracked()).extracting(GitStatusEntry::path).contains("worktree-only.txt");
        // The envelope carrying repo-derived paths is flagged untrusted.
        assertThat(env.untrusted()).isTrue();
    }

    @Test
    void a_checked_out_branch_is_reported_as_the_current_branch(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "x\n")
                .add()
                .commit("seed")
                .checkoutNewBranch("feature/my-work");

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitStatus().branch()).isEqualTo("feature/my-work");
        assertThat(env.gitStatus().detached()).isFalse();
    }

    @Test
    void a_path_that_is_a_directory_but_not_a_git_repo_returns_NOT_A_GIT_REPOSITORY(@TempDir Path tmp) {
        // A real directory that was never `git init`-ed: git exits non-zero, and the exit-code
        // floor must surface NOT_A_GIT_REPOSITORY rather than a false-clean status.
        Envelope env = useCase().run(tmp.toString(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(env.gitStatus()).isNull();
    }

    // ---- lock-in: empty-but-initialized repo (unborn HEAD) is already correct → stay ok ----

    @Test
    void an_empty_repo_with_no_commits_returns_ok_true(@TempDir Path tmp) {
        // git_status uses `git status --porcelain=v2 --branch` which exits 0 even on an
        // unborn-HEAD repo (git reports "initial" branch state). This lock-in test pins
        // the already-correct behavior so it cannot silently regress.
        GitRepoFixture repo = GitRepoFixture.init(tmp);

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitStatus()).as("empty repo: gitStatus must be non-null").isNotNull();
    }
}
