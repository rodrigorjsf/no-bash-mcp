package dev.nobash.application.verb.git;

import dev.nobash.adapter.out.ecosystem.maven.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitBranchEntry;
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
 * End-to-end integration test for {@code git_branch} (PRD-002, issue #28), AC#4, against a REAL,
 * programmatically-scripted temp git repository built by {@link GitRepoFixture}.
 *
 * <p>This is a Surefire {@code *Test} (NOT a Failsafe {@code *IT}) so it rides the
 * {@code mvn test} gate, but it {@code assumeTrue(GitRepoFixture.gitAvailable())} so it
 * SELF-SKIPS on a git-less runner.</p>
 *
 * <p>Verifies the full tracer: launch real git, parse real {@code --format=} output,
 * assemble the normalized envelope. Covers:</p>
 * <ul>
 *   <li>Multiple branches with a configured upstream (local tracking branch set via
 *       {@code --set-upstream-to}) — proves ahead/behind are parsed correctly.</li>
 *   <li>Current-branch detection.</li>
 *   <li>Non-repo returns {@code NOT_A_GIT_REPOSITORY}.</li>
 * </ul>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitBranchIntegrationTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — the temp-git integration harness cannot run");
    }

    private static GitBranchUseCase useCase() {
        return new GitBranchUseCase(new GitCommandExecutor(new PathScanningManagerResolver()));
    }

    @Test
    void single_branch_repo_returns_main_as_current_branch(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("README.md", "# hello\n")
                .add()
                .commit("initial commit");

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_branch");
        assertThat(env.manager()).as("manager is null for git verbs").isNull();
        assertThat(env.untrusted()).isTrue();

        List<GitBranchEntry> branches = env.gitBranch();
        assertThat(branches).isNotNull().isNotEmpty();

        GitBranchEntry main = branches.stream()
                .filter(b -> GitRepoFixture.DEFAULT_BRANCH.equals(b.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Expected branch '" + GitRepoFixture.DEFAULT_BRANCH + "' in gitBranch[]"));

        assertThat(main.current()).as("main is the current branch").isTrue();
        assertThat(main.upstream()).as("no upstream configured → null").isNull();
        assertThat(main.ahead()).as("no upstream → ahead null").isNull();
        assertThat(main.behind()).as("no upstream → behind null").isNull();
    }

    @Test
    void multiple_branches_all_appear_and_current_flag_is_set_correctly(@TempDir Path tmp) {
        // Create main with a commit, then create a feature branch and check it out.
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "v1\n")
                .add()
                .commit("initial commit")
                .checkoutNewBranch("feature/my-work");

        // We are now on feature/my-work; list branches from there.
        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        List<GitBranchEntry> branches = env.gitBranch();
        assertThat(branches).hasSizeGreaterThanOrEqualTo(2);

        // feature/my-work is current.
        GitBranchEntry feature = branches.stream()
                .filter(b -> "feature/my-work".equals(b.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected feature/my-work in gitBranch[]"));
        assertThat(feature.current()).as("feature/my-work is the current branch").isTrue();

        // main is NOT current.
        GitBranchEntry main = branches.stream()
                .filter(b -> GitRepoFixture.DEFAULT_BRANCH.equals(b.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected main in gitBranch[]"));
        assertThat(main.current()).as("main is NOT the current branch").isFalse();
    }

    @Test
    void branch_with_upstream_reports_ahead_behind_counts(@TempDir Path tmp) {
        // Create main branch, then create feature branching from it.
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("base.txt", "base\n")
                .add()
                .commit("base commit")
                .checkoutNewBranch("feature");

        // Add a commit on feature.
        repo.writeFile("feature.txt", "new\n").add().commit("feature commit");

        // Set up a local upstream: feature tracks main (local tracking, no remote needed).
        // This makes %(upstream:short) = "main" and %(upstream:track) = "ahead 1".
        repo.run("branch", "--set-upstream-to=" + GitRepoFixture.DEFAULT_BRANCH, "feature");

        Envelope env = useCase().run(repo.dir().toString(), null);

        assertThat(env.ok()).isTrue();
        GitBranchEntry feature = env.gitBranch().stream()
                .filter(b -> "feature".equals(b.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected 'feature' in gitBranch[]"));

        assertThat(feature.upstream())
                .as("upstream should be 'main' (local tracking)")
                .isEqualTo(GitRepoFixture.DEFAULT_BRANCH);
        assertThat(feature.ahead())
                .as("feature has 1 commit ahead of main")
                .isEqualTo(1);
        assertThat(feature.behind())
                .as("feature is not behind main")
                .isEqualTo(0);
    }

    @Test
    void a_path_that_is_not_a_git_repo_returns_NOT_A_GIT_REPOSITORY(@TempDir Path tmp) {
        Envelope env = useCase().run(tmp.toString(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(env.gitBranch()).isNull();
    }
}
