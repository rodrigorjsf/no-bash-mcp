package dev.nobash.application.verb.git;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitStatusEntry;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link GitStatusUseCase} (PRD-002, issue #24).
 *
 * <p>Guard tests use the {@code ExplodingExecutorSpy} pattern from {@code RunBuildUseCaseTest}: a
 * port that explodes when consulted proves the early guards return before exec. Execution tests
 * use a stub port returning canned porcelain output. A positive lock-exemption test proves two
 * concurrent {@code git_status} calls BOTH succeed (no {@code RESOURCE_BUSY}, ADR-0005).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitStatusUseCaseTest {

    private static final String CLEAN_PORCELAIN =
            "# branch.oid a1b2c3d4\n"
            + "# branch.head main\n"
            + "# branch.upstream origin/main\n"
            + "# branch.ab +0 -0\n";

    private static final String DIRTY_PORCELAIN =
            "# branch.oid a1b2c3d4\n"
            + "# branch.head main\n"
            + "# branch.upstream origin/main\n"
            + "# branch.ab +1 -0\n"
            + "1 M. N... 100644 100644 100644 1111 2222 staged.txt\n"
            + "1 .M N... 100644 100644 100644 3333 3333 unstaged.txt\n"
            + "? untracked.txt\n";

    /** Explodes if the executor port is consulted — proves early guards fire before exec. */
    private static final class ExplodingExecutorSpy implements CommandExecutorPort {
        @Override
        public boolean isManagerInstalled() {
            throw new AssertionError("executor port was consulted — a guard should have returned first");
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            throw new AssertionError("executor was launched — a guard should have returned first");
        }
    }

    /** A port with fixed manager presence; execute is never reached by guard tests. */
    private static final class ManagerPresence implements CommandExecutorPort {
        private final boolean installed;

        private ManagerPresence(boolean installed) {
            this.installed = installed;
        }

        @Override
        public boolean isManagerInstalled() {
            return installed;
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            throw new AssertionError("execute must not be reached past TOOL_NOT_INSTALLED guard");
        }
    }

    /** A port that returns a canned ExecResult on execute(). */
    private static final class StubExecutor implements CommandExecutorPort {
        private final ExecResult result;

        private StubExecutor(ExecResult result) {
            this.result = result;
        }

        @Override
        public boolean isManagerInstalled() {
            return true;
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            return result;
        }
    }

    private static GitStatusUseCase useCaseWith(CommandExecutorPort port) {
        return new GitStatusUseCase(port);
    }

    private static GitStatusUseCase useCaseWithResult(ExecResult result) {
        return useCaseWith(new StubExecutor(result));
    }

    // ============================================================================================
    // Guard tests
    // ============================================================================================

    @Nested
    class invalid_path_guard {

        @Test
        void null_path_fails_closed_to_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(null, null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void blank_path_fails_closed_to_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run("   ", null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void non_existent_path_returns_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy())
                    .run("/no/such/path/" + System.nanoTime(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void a_file_not_a_directory_returns_INVALID_PATH(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("file.txt");
            Files.writeString(file, "x");

            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(file.toString(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }
    }

    @Nested
    class no_manager_detected_guard_is_omitted_for_git {

        @Test
        void a_directory_without_any_manager_marker_does_NOT_return_NO_MANAGER_DETECTED(@TempDir Path dir) {
            // git is ecosystem-agnostic — an empty dir (no pom.xml) must reach the TOOL guard, not
            // fail closed to NO_MANAGER_DETECTED. With git "present" the guard passes and exec runs.
            ExecResult clean = new ExecResult(0, CLEAN_PORCELAIN, "", false);
            Envelope env = useCaseWithResult(clean).run(dir.toString(), null);

            // Reaches exec and returns a status — no NO_MANAGER_DETECTED in the git path at all.
            assertThat(env.ok()).isTrue();
            assertThat(env.gitStatus()).isNotNull();
        }
    }

    @Nested
    class tool_not_installed_guard {

        @Test
        void git_absent_returns_TOOL_NOT_INSTALLED(@TempDir Path dir) {
            Envelope env = useCaseWith(new ManagerPresence(false)).run(dir.toString(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.TOOL_NOT_INSTALLED);
        }

        @Test
        void error_message_names_the_tool(@TempDir Path dir) {
            Envelope env = useCaseWith(new ManagerPresence(false)).run(dir.toString(), null);

            String text = env.error().message() + " " + env.error().hint();
            assertThat(text).contains("git");
        }
    }

    // ============================================================================================
    // Execution path tests
    // ============================================================================================

    @Nested
    class execution_paths {

        @Test
        void clean_repo_returns_ok_true_with_git_status_and_null_manager(@TempDir Path dir) {
            ExecResult clean = new ExecResult(0, CLEAN_PORCELAIN, "", false);

            Envelope env = useCaseWithResult(clean).run(dir.toString(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.verb()).isEqualTo("git_status");
            assertThat(env.manager()).as("manager is null for git verbs").isNull();
            assertThat(env.gitStatus()).isNotNull();
            assertThat(env.gitStatus().branch()).isEqualTo("main");
            assertThat(env.gitStatus().upstream()).isEqualTo("origin/main");
            assertThat(env.gitStatus().ahead()).isEqualTo(0);
            assertThat(env.gitStatus().clean()).isTrue();
        }

        @Test
        void dirty_repo_surfaces_staged_unstaged_and_untracked(@TempDir Path dir) {
            ExecResult dirty = new ExecResult(0, DIRTY_PORCELAIN, "", false);

            Envelope env = useCaseWithResult(dirty).run(dir.toString(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.gitStatus().staged()).extracting(GitStatusEntry::path)
                    .containsExactly("staged.txt");
            assertThat(env.gitStatus().unstaged()).extracting(GitStatusEntry::path)
                    .containsExactly("unstaged.txt");
            assertThat(env.gitStatus().untracked()).extracting(GitStatusEntry::path)
                    .containsExactly("untracked.txt");
        }

        @Test
        void git_status_envelope_is_marked_untrusted(@TempDir Path dir) {
            // Branch names and paths are repo-derived; the envelope must be flagged untrusted.
            ExecResult dirty = new ExecResult(0, DIRTY_PORCELAIN, "", false);

            Envelope env = useCaseWithResult(dirty).run(dir.toString(), null);

            assertThat(env.untrusted())
                    .as("git_status carries repo-derived branch names and paths")
                    .isTrue();
        }

        @Test
        void non_zero_exit_returns_NOT_A_GIT_REPOSITORY_never_a_false_clean(@TempDir Path dir) {
            // git exits 128 with empty stdout on a non-repo path. The exit-code floor must surface
            // NOT_A_GIT_REPOSITORY rather than parsing empty stdout into a misleading clean repo.
            ExecResult notRepo = new ExecResult(128, "",
                    "fatal: not a git repository (or any of the parent directories): .git\n", false);

            Envelope env = useCaseWithResult(notRepo).run(dir.toString(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
            assertThat(env.gitStatus()).as("no false-clean status on a non-repo").isNull();
        }

        @Test
        void a_timeout_returns_TIMEOUT(@TempDir Path dir) {
            ExecResult timedOut = new ExecResult(-1, "partial", "", true);

            Envelope env = useCaseWithResult(timedOut).run(dir.toString(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.TIMEOUT);
        }

        @Test
        void the_argv_is_git_status_porcelain_v2_branch_with_the_repo_as_working_dir(@TempDir Path dir) {
            // Capture the spec the use-case builds — proves the porcelain-parse argv (AC3) and that
            // the working dir is the repo path.
            ExecSpec[] captured = new ExecSpec[1];
            CommandExecutorPort capturing = new CommandExecutorPort() {
                @Override
                public boolean isManagerInstalled() {
                    return true;
                }

                @Override
                public ExecResult execute(ExecSpec spec) {
                    captured[0] = spec;
                    return new ExecResult(0, CLEAN_PORCELAIN, "", false);
                }
            };

            useCaseWith(capturing).run(dir.toString(), null);

            assertThat(captured[0].argv())
                    .containsExactly("git", "status", "--porcelain=v2", "--branch");
            assertThat(captured[0].workingDir()).isEqualTo(dir.toString());
        }
    }

    // ============================================================================================
    // Lock-exemption (ADR-0005): git read-only verbs do NOT acquire the module lock.
    // ============================================================================================

    @Nested
    class git_verbs_are_lock_exempt {

        @Test
        void two_concurrent_git_status_calls_on_the_same_module_both_succeed_no_resource_busy(
                @TempDir Path dir) throws Exception {
            // A two-latch barrier: BOTH calls must be inside execute() at the same time and BOTH
            // must succeed. A mutating verb would RESOURCE_BUSY the second; git_status must not,
            // because it never acquires the ModuleLock (exemption by omission).
            CountDownLatch bothEntered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);

            CommandExecutorPort barrierBoth = new CommandExecutorPort() {
                @Override
                public boolean isManagerInstalled() {
                    return true;
                }

                @Override
                public ExecResult execute(ExecSpec spec) {
                    bothEntered.countDown();
                    try {
                        if (!bothEntered.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("both calls did not reach execute() concurrently");
                        }
                        release.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("interrupted at the barrier", e);
                    }
                    return new ExecResult(0, CLEAN_PORCELAIN, "", false);
                }
            };
            GitStatusUseCase useCase = useCaseWith(barrierBoth);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Envelope> a = pool.submit(() -> useCase.run(dir.toString(), null));
                Future<Envelope> b = pool.submit(() -> useCase.run(dir.toString(), null));

                // Both must arrive concurrently — the concurrency proof. A module lock would
                // serialize them and one would fail fast before the other entered.
                assertThat(bothEntered.await(10, TimeUnit.SECONDS))
                        .as("both concurrent git_status calls must reach execute() — no lock")
                        .isTrue();
                release.countDown();

                Envelope envA = a.get(10, TimeUnit.SECONDS);
                Envelope envB = b.get(10, TimeUnit.SECONDS);
                assertThat(envA.ok()).isTrue();
                assertThat(envB.ok()).isTrue();
                // Neither is RESOURCE_BUSY.
                assertThat(envA.error()).isNull();
                assertThat(envB.error()).isNull();
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }
}
