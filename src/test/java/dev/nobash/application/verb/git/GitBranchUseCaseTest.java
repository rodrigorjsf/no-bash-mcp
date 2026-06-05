package dev.nobash.application.verb.git;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitBranchEntry;
import dev.nobash.domain.git.GitBranchParser;
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
 * Unit tests for {@link GitBranchUseCase} (PRD-002, issue #28).
 *
 * <p>Mirrors the pattern from {@link GitStatusUseCaseTest}: guard tests use the
 * {@code ExplodingExecutorSpy} pattern; execution tests use a stub port returning canned
 * branch output; a lock-exemption test proves two concurrent {@code git_branch} calls BOTH
 * succeed (no {@code RESOURCE_BUSY}, ADR-0005).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitBranchUseCaseTest {

    private static final char FS = GitBranchParser.FIELD_SEP;

    private static final String ONE_BRANCH_OUTPUT =
            "main" + FS + "*" + FS + "origin/main" + FS + "";

    private static final String TWO_BRANCH_OUTPUT =
            "feature" + FS + " " + FS + "" + FS + "" + "\n"
            + "main" + FS + "*" + FS + "origin/main" + FS + "ahead 1";

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

    private static GitBranchUseCase useCaseWith(CommandExecutorPort port) {
        return new GitBranchUseCase(port);
    }

    private static GitBranchUseCase useCaseWithResult(ExecResult result) {
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
        void a_directory_without_any_manager_marker_does_NOT_return_NO_MANAGER_DETECTED(
                @TempDir Path dir) {
            ExecResult result = new ExecResult(0, ONE_BRANCH_OUTPUT, "", false);
            Envelope env = useCaseWithResult(result).run(dir.toString(), null);

            // Reaches exec and returns branches — no NO_MANAGER_DETECTED in the git path at all.
            assertThat(env.ok()).isTrue();
            assertThat(env.gitBranch()).isNotNull();
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
        void single_branch_returns_ok_true_with_gitBranch_and_null_manager(@TempDir Path dir) {
            ExecResult result = new ExecResult(0, ONE_BRANCH_OUTPUT, "", false);

            Envelope env = useCaseWithResult(result).run(dir.toString(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.verb()).isEqualTo("git_branch");
            assertThat(env.manager()).as("manager is null for git verbs").isNull();
            assertThat(env.gitBranch()).isNotNull().hasSize(1);
            GitBranchEntry entry = env.gitBranch().get(0);
            assertThat(entry.name()).isEqualTo("main");
            assertThat(entry.current()).isTrue();
            assertThat(entry.upstream()).isEqualTo("origin/main");
            assertThat(entry.ahead()).isEqualTo(0);
            assertThat(entry.behind()).isEqualTo(0);
        }

        @Test
        void two_branches_returns_both_entries(@TempDir Path dir) {
            ExecResult result = new ExecResult(0, TWO_BRANCH_OUTPUT, "", false);

            Envelope env = useCaseWithResult(result).run(dir.toString(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.gitBranch()).hasSize(2);
            List<String> names = env.gitBranch().stream()
                    .map(GitBranchEntry::name)
                    .toList();
            assertThat(names).contains("feature", "main");
        }

        @Test
        void git_branch_envelope_is_marked_untrusted(@TempDir Path dir) {
            ExecResult result = new ExecResult(0, ONE_BRANCH_OUTPUT, "", false);

            Envelope env = useCaseWithResult(result).run(dir.toString(), null);

            assertThat(env.untrusted())
                    .as("git_branch carries repo-derived branch names")
                    .isTrue();
        }

        @Test
        void non_zero_exit_returns_NOT_A_GIT_REPOSITORY(@TempDir Path dir) {
            ExecResult notRepo = new ExecResult(128, "",
                    "fatal: not a git repository\n", false);

            Envelope env = useCaseWithResult(notRepo).run(dir.toString(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.NOT_A_GIT_REPOSITORY);
            assertThat(env.gitBranch()).as("no false-clean branch list on a non-repo").isNull();
        }

        @Test
        void a_timeout_returns_TIMEOUT(@TempDir Path dir) {
            ExecResult timedOut = new ExecResult(-1, "partial", "", true);

            Envelope env = useCaseWithResult(timedOut).run(dir.toString(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.TIMEOUT);
        }

        @Test
        void the_argv_contains_git_branch_with_the_format_flag(@TempDir Path dir) {
            ExecSpec[] captured = new ExecSpec[1];
            CommandExecutorPort capturing = new CommandExecutorPort() {
                @Override
                public boolean isManagerInstalled() {
                    return true;
                }

                @Override
                public ExecResult execute(ExecSpec spec) {
                    captured[0] = spec;
                    return new ExecResult(0, ONE_BRANCH_OUTPUT, "", false);
                }
            };

            useCaseWith(capturing).run(dir.toString(), null);

            assertThat(captured[0].argv()).hasSize(3);
            assertThat(captured[0].argv().get(0)).isEqualTo("git");
            assertThat(captured[0].argv().get(1)).isEqualTo("branch");
            assertThat(captured[0].argv().get(2)).startsWith("--format=");
            assertThat(captured[0].workingDir()).isEqualTo(dir.toString());
        }
    }

    // ============================================================================================
    // Lock-exemption (ADR-0005): git read-only verbs do NOT acquire the module lock.
    // ============================================================================================

    @Nested
    class git_verbs_are_lock_exempt {

        @Test
        void two_concurrent_git_branch_calls_on_the_same_module_both_succeed_no_resource_busy(
                @TempDir Path dir) throws Exception {
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
                    return new ExecResult(0, ONE_BRANCH_OUTPUT, "", false);
                }
            };
            GitBranchUseCase useCase = useCaseWith(barrierBoth);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<Envelope> a = pool.submit(() -> useCase.run(dir.toString(), null));
                Future<Envelope> b = pool.submit(() -> useCase.run(dir.toString(), null));

                assertThat(bothEntered.await(10, TimeUnit.SECONDS))
                        .as("both concurrent git_branch calls must reach execute() — no lock")
                        .isTrue();
                release.countDown();

                Envelope envA = a.get(10, TimeUnit.SECONDS);
                Envelope envB = b.get(10, TimeUnit.SECONDS);
                assertThat(envA.ok()).isTrue();
                assertThat(envB.ok()).isTrue();
                assertThat(envA.error()).isNull();
                assertThat(envB.error()).isNull();
            } finally {
                release.countDown();
                pool.shutdownNow();
            }
        }
    }
}
