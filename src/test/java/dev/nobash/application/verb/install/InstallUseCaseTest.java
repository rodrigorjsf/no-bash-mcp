package dev.nobash.application.verb.install;

import dev.nobash.application.policy.InstallFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InstallUseCase} (PRD-3, slice 3).
 *
 * <p>Guard tests use the {@code ExplodingExecutorSpy} pattern: a port that explodes on
 * {@code isManagerInstalled()} proves the early guards never reach execution.</p>
 *
 * <p>Execution tests use a {@code StubExecutor} with canned output to exercise the success
 * and failure shapes without requiring a real npm.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InstallUseCaseTest {

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
        private ExecSpec capturedSpec;

        private StubExecutor(ExecResult result) {
            this.result = result;
        }

        @Override
        public boolean isManagerInstalled() {
            return true;
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            this.capturedSpec = spec;
            return result;
        }
    }

    private InstallUseCase useCaseWith(CommandExecutorPort port) {
        return new InstallUseCase(port, new InstallFlagPolicy(), new RawOutputStash());
    }

    private InstallUseCase useCaseWithResult(ExecResult result) {
        return useCaseWith(new StubExecutor(result));
    }

    // ============================================================================================
    // Guard tests (structural proof: a passing guard assertion against ExplodingExecutorSpy means
    // the verb returned before consulting the executor).
    // ============================================================================================

    @Nested
    class invalid_path_guard {

        @Test
        void null_path_fails_closed_to_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(null, List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void blank_path_fails_closed_to_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run("   ", List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void non_existent_path_returns_INVALID_PATH() {
            Envelope env = useCaseWith(new ExplodingExecutorSpy())
                    .run("/no/such/path/" + System.nanoTime(), List.of(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }

        @Test
        void a_file_not_a_directory_returns_INVALID_PATH(@TempDir Path dir) throws Exception {
            Path file = dir.resolve("package.json");
            Files.writeString(file, "{}");

            // The path points at a file, not a directory.
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(file.toString(), List.of(), null);

            assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        }
    }

    @Nested
    class no_manager_detected_guard {

        @Test
        void directory_without_package_json_returns_NO_MANAGER_DETECTED(@TempDir Path dir) {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.NO_MANAGER_DETECTED);
        }

        @Test
        void error_message_lists_what_was_looked_for(@TempDir Path dir) {
            Envelope env = useCaseWith(new ExplodingExecutorSpy()).run(dir.toString(), List.of(), null);

            String text = env.error().message() + " " + env.error().hint();
            assertThat(text).contains("package.json");
        }
    }

    @Nested
    class tool_not_installed_guard {

        @Test
        void npm_absent_returns_TOOL_NOT_INSTALLED(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");

            Envelope env = useCaseWith(new ManagerPresence(false)).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error().code()).isEqualTo(ErrorCode.TOOL_NOT_INSTALLED);
        }

        @Test
        void error_message_names_npm(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");

            Envelope env = useCaseWith(new ManagerPresence(false)).run(dir.toString(), List.of(), null);

            String text = env.error().message() + " " + env.error().hint();
            assertThat(text).contains("npm");
        }
    }

    // ============================================================================================
    // Execution path tests (stub executor returns canned output).
    // ============================================================================================

    @Nested
    class execution_paths {

        @Test
        void successful_install_returns_ok_true_with_manager_npm_and_no_failures(@TempDir Path dir)
                throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0,
                    "added 12 packages, and audited 42 packages in 2s\n", "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.verb()).isEqualTo("install");
            assertThat(env.manager()).isEqualTo("npm");
            assertThat(env.failures()).as("no failures[] on install success").isNull();
            assertThat(env.diagnostics()).as("no diagnostics[] on install").isNull();
            assertThat(env.error()).as("no error on success").isNull();
        }

        @Test
        void successful_install_returns_install_summary_with_parsed_counts(@TempDir Path dir)
                throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            String npmOut = "added 10 packages, removed 2 packages, changed 1 package, "
                    + "and audited 50 packages in 3s\n";
            ExecResult success = new ExecResult(0, npmOut, "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.installSummary()).isNotNull();
            assertThat(env.installSummary().added()).isEqualTo(10);
            assertThat(env.installSummary().removed()).isEqualTo(2);
            assertThat(env.installSummary().changed()).isEqualTo(1);
        }

        @Test
        void successful_install_with_only_added_parses_correctly(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0,
                    "added 5 packages in 1s\n", "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.installSummary().added()).isEqualTo(5);
            assertThat(env.installSummary().removed()).isEqualTo(0);
            assertThat(env.installSummary().changed()).isEqualTo(0);
        }

        @Test
        void noop_install_up_to_date_returns_empty_summary(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0,
                    "up to date, audited 42 packages in 1s\n", "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isTrue();
            assertThat(env.installSummary()).isNotNull();
            assertThat(env.installSummary().added()).isEqualTo(0);
            assertThat(env.installSummary().removed()).isEqualTo(0);
            assertThat(env.installSummary().changed()).isEqualTo(0);
        }

        @Test
        void failed_install_returns_INSTALL_FAILED_with_handle(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult fail = new ExecResult(1, "",
                    "npm ERR! code E404\nnpm ERR! 404 Not Found\n", false);

            Envelope env = useCaseWithResult(fail).run(dir.toString(), List.of(), null);

            assertThat(env.ok()).isFalse();
            assertThat(env.error()).isNotNull();
            assertThat(env.error().code()).isEqualTo(ErrorCode.INSTALL_FAILED);
            assertThat(env.handle()).as("INSTALL_FAILED carries a handle for get_log drill-down").isNotNull();
            assertThat(env.handle().id()).isNotBlank();
        }

        @Test
        void failed_install_error_message_mentions_exit_code(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult fail = new ExecResult(1, "", "npm ERR! 404\n", false);

            Envelope env = useCaseWithResult(fail).run(dir.toString(), List.of(), null);

            assertThat(env.error().message()).contains("1");
        }

        @Test
        void successful_install_carries_a_handle_for_get_log(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0, "added 1 package in 1s\n", "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.handle()).as("success also carries a handle for get_log").isNotNull();
        }

        @Test
        void install_is_not_marked_untrusted_on_success(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0, "added 3 packages in 1s\n", "", false);

            Envelope env = useCaseWithResult(success).run(dir.toString(), List.of(), null);

            assertThat(env.untrusted())
                    .as("install success has server-authored content only")
                    .isFalse();
        }
    }

    // ============================================================================================
    // argv construction tests — verify controlled flags are injected, agent flags are vetted.
    // ============================================================================================

    @Nested
    class argv_construction {

        @Test
        void argv_contains_npm_install_and_controlled_flags(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0, "added 0 packages\n", "", false);
            StubExecutor stub = new StubExecutor(success);
            InstallUseCase useCase = new InstallUseCase(stub, new InstallFlagPolicy(), new RawOutputStash());

            useCase.run(dir.toString(), List.of(), null);

            assertThat(stub.capturedSpec).isNotNull();
            assertThat(stub.capturedSpec.argv()).containsExactly("npm", "install", "--no-audit", "--no-fund");
        }

        @Test
        void agent_supplied_flags_are_dropped_by_empty_allowlist(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0, "added 0 packages\n", "", false);
            StubExecutor stub = new StubExecutor(success);
            InstallUseCase useCase = new InstallUseCase(stub, new InstallFlagPolicy(), new RawOutputStash());

            // All agent flags dropped: --prefer-offline is NOT in the empty seed.
            useCase.run(dir.toString(), List.of("--prefer-offline", "--force"), null);

            assertThat(stub.capturedSpec.argv())
                    .as("agent flags must be dropped by the empty-seed allowlist")
                    .containsExactly("npm", "install", "--no-audit", "--no-fund");
        }

        @Test
        void no_ignore_scripts_flag_is_injected(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{}");
            ExecResult success = new ExecResult(0, "added 0 packages\n", "", false);
            StubExecutor stub = new StubExecutor(success);
            InstallUseCase useCase = new InstallUseCase(stub, new InstallFlagPolicy(), new RawOutputStash());

            useCase.run(dir.toString(), List.of(), null);

            assertThat(stub.capturedSpec.argv())
                    .as("lifecycle hooks must run — --ignore-scripts must NOT be present")
                    .doesNotContain("--ignore-scripts");
        }
    }
}
