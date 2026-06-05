package dev.nobash.application.verb.git;

import dev.nobash.adapter.out.ecosystem.maven.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitCommit;
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
 * End-to-end integration test for {@code git_log} (PRD-002, issue #26) against a REAL,
 * programmatically-scripted temp git repository built by {@link GitRepoFixture}.
 *
 * <p>This is a Surefire {@code *Test} (NOT a Failsafe {@code *IT}) so it rides the
 * {@code mvn test} gate, but it {@code assumeTrue(GitRepoFixture.gitAvailable())} so it
 * SELF-SKIPS on a git-less runner. It wires the production {@link GitLogUseCase} with the
 * production {@link GitCommandExecutor} over the real trusted-git seam.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitLogIntegrationTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — the temp-git integration harness cannot run");
    }

    private static GitLogUseCase useCase() {
        return new GitLogUseCase(new GitCommandExecutor(new PathScanningManagerResolver()));
    }

    @Test
    void a_repo_with_three_commits_returns_all_three_in_reverse_chronological_order(
            @TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("a.txt", "v1\n").add().commit("first commit")
                .writeFile("b.txt", "v2\n").add().commit("second commit")
                .writeFile("c.txt", "v3\n").add().commit("third commit");

        Envelope env = useCase().run(repo.dir().toString(), null, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_log");
        assertThat(env.manager()).as("manager is null for git verbs").isNull();
        assertThat(env.untrusted()).isTrue();

        List<GitCommit> commits = env.gitLog();
        assertThat(commits).hasSize(3);
        // Newest first.
        assertThat(commits.get(0).subject()).isEqualTo("third commit");
        assertThat(commits.get(1).subject()).isEqualTo("second commit");
        assertThat(commits.get(2).subject()).isEqualTo("first commit");
    }

    @Test
    void all_commit_fields_are_populated(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("seed commit");

        Envelope env = useCase().run(repo.dir().toString(), null, null);

        assertThat(env.ok()).isTrue();
        GitCommit commit = env.gitLog().get(0);
        assertThat(commit.sha()).as("sha is a 40-hex string").hasSize(40);
        assertThat(commit.abbrev()).as("abbrev is non-null").isNotNull();
        assertThat(commit.author()).as("author is non-null").isNotNull();
        assertThat(commit.dateIso()).as("dateIso is non-null").isNotNull();
        assertThat(commit.subject()).isEqualTo("seed commit");
    }

    @Test
    void limit_parameter_caps_the_returned_commit_count(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp);
        for (int i = 1; i <= 5; i++) {
            repo.writeFile("file" + i + ".txt", "v" + i + "\n").add().commit("commit " + i);
        }

        Envelope env = useCase().run(repo.dir().toString(), 2, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitLog()).hasSize(2);
        // The most recent 2 commits.
        assertThat(env.gitLog().get(0).subject()).isEqualTo("commit 5");
        assertThat(env.gitLog().get(1).subject()).isEqualTo("commit 4");
    }

    @Test
    void an_empty_repo_with_no_commits_returns_NOT_A_GIT_REPOSITORY(@TempDir Path tmp) {
        // git init but no commits: git log exits non-zero on a repo with no commits
        // (exit code 128 "does not have any commits yet"), which the exit-code floor
        // surfaces as NOT_A_GIT_REPOSITORY.
        GitRepoFixture repo = GitRepoFixture.init(tmp);

        Envelope env = useCase().run(repo.dir().toString(), null, null);

        // On a repo with no commits, git log exits non-zero → NOT_A_GIT_REPOSITORY.
        // An empty commit list with ok=true is not achievable via the current exit-code floor.
        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
    }

    @Test
    void a_path_that_is_a_directory_but_not_a_git_repo_returns_NOT_A_GIT_REPOSITORY(
            @TempDir Path tmp) {
        Envelope env = useCase().run(tmp.toString(), null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
        assertThat(env.gitLog()).isNull();
    }

    // ---- clamp unit tests (pure; exercise the default and MAX_LIMIT ceiling) ----

    @Test
    void clampLimit_null_or_non_positive_uses_the_default() {
        assertThat(GitLogUseCase.clampLimit(null)).isEqualTo(GitLogUseCase.DEFAULT_LIMIT);
        assertThat(GitLogUseCase.clampLimit(0)).isEqualTo(GitLogUseCase.DEFAULT_LIMIT);
        assertThat(GitLogUseCase.clampLimit(-5)).isEqualTo(GitLogUseCase.DEFAULT_LIMIT);
    }

    @Test
    void clampLimit_positive_within_range_is_passed_through() {
        assertThat(GitLogUseCase.clampLimit(2)).isEqualTo(2);
        assertThat(GitLogUseCase.clampLimit(GitLogUseCase.MAX_LIMIT)).isEqualTo(GitLogUseCase.MAX_LIMIT);
    }

    @Test
    void clampLimit_above_the_ceiling_is_capped_to_MAX_LIMIT() {
        assertThat(GitLogUseCase.clampLimit(1000)).isEqualTo(GitLogUseCase.MAX_LIMIT);
        assertThat(GitLogUseCase.clampLimit(GitLogUseCase.MAX_LIMIT + 1)).isEqualTo(GitLogUseCase.MAX_LIMIT);
    }
}
