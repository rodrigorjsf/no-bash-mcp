package dev.nobash.application.verb.tests;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code run_tests} use-case: validation + programmatic security guards run in a fixed
 * order BEFORE any process is launched (AC3/AC4/AC5, DESIGN.md §9 security-tests-first).
 * Guard order: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} → {@code TOOL_NOT_INSTALLED}.
 *
 * <p>"No process launched" (AC3) is proven structurally: the manager-installed check is the
 * adapter's only outbound call here, and a guard that fires before it MUST NOT consult the
 * port. {@link ExplodingExecutorSpy} fails the test if {@code isManagerInstalled()} is ever
 * called — so a passing {@code INVALID_PATH}/{@code NO_MANAGER_DETECTED} assertion against the
 * spy is direct evidence the verb returned without reaching the executor.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunTestsUseCaseTest {

    /** A port that explodes if consulted — proves the early guards never reach execution. */
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

    /** A port whose manager presence is fixed; execute is never reached by these guard tests. */
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
            throw new AssertionError("execute must not be reached past the TOOL_NOT_INSTALLED guard");
        }
    }

    private RunTestsUseCase useCaseWith(CommandExecutorPort port) {
        return new RunTestsUseCase(port, new ArgvBuilder(),
                new dev.nobash.application.policy.TestsFlagPolicy(),
                new dev.nobash.application.runcache.RawOutputStash(),
                new dev.nobash.infra.concurrency.ModuleLock());
    }

    @Nested
    class invalid_path_guard_AC3 {

        @Test
        void a_non_existent_path_returns_INVALID_PATH_without_consulting_the_executor() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy())
                    .run("/no/such/path/" + System.nanoTime(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.verb()).isEqualTo("run_tests");
            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void a_path_that_is_a_file_not_a_directory_returns_INVALID_PATH(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("a-file.txt");
            java.nio.file.Files.writeString(file, "x");

            Envelope env = useCaseWith(new ExplodingExecutorSpy())
                    .run(file.toString(), List.of(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void a_null_path_fails_closed_to_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(null, List.of(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void a_malformed_path_string_fails_closed_to_INVALID_PATH_not_an_exception() {
            // A NUL byte makes Path.of throw InvalidPathException — untrusted input must still
            // yield a clean envelope, never a leaked exception. The NUL is written as the Java
            // escape \u0000 (NOT a raw control byte) so the source stays valid text while still
            // exercising the InvalidPathException catch branch specifically.
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run("bad\u0000path", List.of(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void the_INVALID_PATH_message_makes_no_workspace_confinement_claim() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy())
                    .run("/no/such/path/" + System.nanoTime(), List.of(), null);

            String text = (env.error().message() + " " + env.error().hint()).toLowerCase();
            // It is only about existence / directory-ness — never a confinement/workspace claim.
            assertThat(text).doesNotContain("workspace", "confine", "outside", "sandbox", "escape");
            assertThat(text).containsAnyOf("director", "exist");
        }
    }

    @Nested
    class no_manager_detected_guard_AC4 {

        @Test
        void a_directory_without_a_pom_returns_NO_MANAGER_DETECTED_without_consulting_the_executor(@TempDir Path dir) {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.NO_MANAGER_DETECTED);
        }

        @Test
        void the_NO_MANAGER_DETECTED_message_lists_what_was_looked_for(@TempDir Path dir) {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(dir.toString(), List.of(), null);

            String text = env.error().message() + " " + env.error().hint();
            assertThat(text).contains("pom.xml");
        }
    }

    @Nested
    class tool_not_installed_guard_AC5 {

        @Test
        void a_valid_maven_project_with_mvn_absent_returns_TOOL_NOT_INSTALLED(@TempDir Path dir) throws Exception {
            java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project/>");
            CommandExecutorPort mvnAbsent = new ManagerPresence(false);

            Envelope env = useCaseWith(mvnAbsent).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.TOOL_NOT_INSTALLED);
        }

        @Test
        void the_TOOL_NOT_INSTALLED_message_names_the_manager_and_hints_installation(@TempDir Path dir) throws Exception {
            java.nio.file.Files.writeString(dir.resolve("pom.xml"), "<project/>");

            Envelope env = useCaseWith(new ManagerPresence(false)).run(dir.toString(), List.of(), null);

            String text = env.error().message() + " " + env.error().hint();
            assertThat(text).contains("mvn");
        }
    }
}
