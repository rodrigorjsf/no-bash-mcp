package dev.nobash.application.verb.git;

import dev.nobash.adapter.out.ecosystem.maven.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitCommitDetail;
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
 * End-to-end integration test for {@code git_show} (PRD-002, issue #26) against a REAL,
 * programmatically-scripted temp git repository built by {@link GitRepoFixture}.
 *
 * <p>This is a Surefire {@code *Test} (NOT a Failsafe {@code *IT}) so it rides the
 * {@code mvn test} gate, but it {@code assumeTrue(GitRepoFixture.gitAvailable())} so it
 * SELF-SKIPS on a git-less runner.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitShowIntegrationTest {

    @BeforeAll
    static void requireGit() {
        assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — the temp-git integration harness cannot run");
    }

    private static GitShowUseCase useCase() {
        return new GitShowUseCase(
                new GitCommandExecutor(new PathScanningManagerResolver()),
                new RawOutputStash());
    }

    @Test
    void git_show_HEAD_returns_the_latest_commit_metadata(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("seed commit");

        Envelope env = useCase().run(repo.dir().toString(), "HEAD", null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("git_show");
        assertThat(env.manager()).as("manager is null for git verbs").isNull();
        assertThat(env.untrusted()).isTrue();

        GitCommitDetail detail = env.gitShow();
        assertThat(detail).isNotNull();
        assertThat(detail.sha()).as("sha is 40-hex").hasSize(40);
        assertThat(detail.abbrev()).isNotNull();
        assertThat(detail.author()).isNotNull();
        assertThat(detail.dateIso()).isNotNull();
        assertThat(detail.subject()).isEqualTo("seed commit");
    }

    @Test
    void the_diff_is_stashed_behind_a_handle_and_is_non_null(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("add file");

        Envelope env = useCase().run(repo.dir().toString(), "HEAD", null);

        assertThat(env.ok()).isTrue();
        assertThat(env.handle()).as("diff handle must be present").isNotNull();
        assertThat(env.handle().id()).as("handle id must be non-blank").isNotBlank();
    }

    @Test
    void git_show_with_a_body_commit_captures_the_body(@TempDir Path tmp) {
        // Create a commit using the fixture's run() to pass a body via -m with multiple -m flags.
        // The GitRepoFixture.commit() uses -m for the subject. To add a body, we use run() directly
        // with the git command that allows multi-line messages.
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "v1\n").add();

        // Commit with a body using git commit -m "subject" -m "body paragraph"
        repo.run("-c", "user.email=test@no-bash-mcp.local", "-c", "user.name=no-bash-mcp test",
                "commit", "-m", "feat: big feature", "-m", "This is the body.\n\nCloses #42");

        Envelope env = useCase().run(repo.dir().toString(), "HEAD", null);

        assertThat(env.ok()).isTrue();
        GitCommitDetail detail = env.gitShow();
        assertThat(detail.subject()).isEqualTo("feat: big feature");
        assertThat(detail.body()).as("body should be captured").isNotNull();
        assertThat(detail.body()).contains("This is the body.");
    }

    @Test
    void git_show_with_abbreviated_sha_resolves_correctly(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("versioned commit");

        // Get the abbreviated SHA from git_log.
        GitLogUseCase logUseCase = new GitLogUseCase(new GitCommandExecutor(new PathScanningManagerResolver()));
        Envelope logEnv = logUseCase.run(repo.dir().toString(), 1, null);
        assertThat(logEnv.ok()).isTrue();
        String abbrev = logEnv.gitLog().get(0).abbrev();

        Envelope env = useCase().run(repo.dir().toString(), abbrev, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.gitShow().subject()).isEqualTo("versioned commit");
    }

    @Test
    void an_unknown_ref_returns_COMMIT_NOT_FOUND(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("f.txt", "x\n").add().commit("seed");

        Envelope env = useCase().run(repo.dir().toString(), "nonexistent-sha-deadbeef", null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.COMMIT_NOT_FOUND);
        assertThat(env.gitShow()).isNull();
    }

    @Test
    void a_path_that_is_a_directory_but_not_a_git_repo_returns_COMMIT_NOT_FOUND(
            @TempDir Path tmp) {
        Envelope env = useCase().run(tmp.toString(), "HEAD", null);

        // When the path is not a git repo, git exits non-zero → COMMIT_NOT_FOUND.
        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.COMMIT_NOT_FOUND);
    }

    @Test
    void blank_ref_returns_INVALID_PATH(@TempDir Path tmp) {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("f.txt", "x\n").add().commit("seed");

        Envelope env = useCase().run(repo.dir().toString(), "", null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
    }
}
