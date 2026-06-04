package dev.nobash.adapter.out.ecosystem.maven;

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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AC5 (adapter level) — the Maven adapter resolves the <em>trusted system</em> {@code mvn}
 * on a PATH and reports its presence. Because the real test-JVM PATH cannot be mutated, the
 * adapter takes an injectable {@link ManagerPathResolver} seam: a unit drives it against a
 * controlled PATH with {@code mvn} absent (and present) without touching the host environment.
 *
 * <p>The trusted-resolution rule (ADR-0008): resolve the system binary on PATH, NEVER a repo
 * wrapper ({@code ./mvnw}). The resolver only ever searches the configured PATH directories.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MavenCommandExecutorTest {

    @Nested
    class manager_presence_over_a_controlled_path {

        @Test
        void reports_absent_when_mvn_is_not_on_the_controlled_path(@TempDir Path emptyDir) {
            // A PATH with no mvn executable anywhere.
            ManagerPathResolver resolver = new PathScanningManagerResolver(emptyDir.toString());
            MavenCommandExecutor executor = new MavenCommandExecutor(resolver);

            assertThat(executor.isManagerInstalled()).isFalse();
        }

        @Test
        void reports_present_when_an_executable_mvn_is_on_the_controlled_path(@TempDir Path binDir) throws Exception {
            Path mvn = binDir.resolve("mvn");
            Files.writeString(mvn, "#!/bin/sh\n");
            mvn.toFile().setExecutable(true);

            ManagerPathResolver resolver = new PathScanningManagerResolver(binDir.toString());
            MavenCommandExecutor executor = new MavenCommandExecutor(resolver);

            assertThat(executor.isManagerInstalled()).isTrue();
        }

        @Test
        void a_repo_wrapper_in_the_working_dir_is_never_treated_as_the_manager(@TempDir Path repo) throws Exception {
            // ./mvnw exists in the repo but is NOT on PATH — it must not count (ADR-0008).
            Path wrapper = repo.resolve("mvnw");
            Files.writeString(wrapper, "#!/bin/sh\n");
            wrapper.toFile().setExecutable(true);

            // Controlled PATH points elsewhere (an empty sibling), so only PATH is consulted.
            Path emptyBin = repo.resolve("empty-bin");
            Files.createDirectory(emptyBin);
            ManagerPathResolver resolver = new PathScanningManagerResolver(emptyBin.toString());
            MavenCommandExecutor executor = new MavenCommandExecutor(resolver);

            assertThat(executor.isManagerInstalled()).isFalse();
        }
    }

    @Nested
    class execute_launches_the_argv_array_directly {

        private MavenCommandExecutor anyExecutor() {
            // Resolution seam is irrelevant to execute(); a present resolver keeps DI shape.
            return new MavenCommandExecutor(manager -> true);
        }

        @Test
        void the_process_command_is_the_argv_array_starting_with_the_system_mvn_never_mvnw() {
            // AC9 — the launched command is EXACTLY the argv array; argv[0] is the trusted
            // system "mvn" name (resolved on PATH by the OS), never "mvnw" / "./mvnw" (ADR-0008).
            // Proven on the ProcessBuilder the adapter constructs — no spawn needed for this rule.
            ExecSpec spec = new ExecSpec(List.of("mvn", "-B", "test"), null);

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.command()).containsExactly("mvn", "-B", "test");
            assertThat(pb.command().get(0)).isEqualTo("mvn");
            assertThat(pb.command()).noneMatch(token -> token.contains("mvnw"));
        }

        @Test
        void no_shell_interpreter_is_prepended_to_the_launched_command() {
            // The argv is launched directly — no /bin/sh -c wrapping (security-model.md).
            ExecSpec spec = new ExecSpec(List.of("mvn", "-B", "test", "; rm -rf /"), null);

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.command()).doesNotContain("/bin/sh", "-c", "sh", "bash", "cmd");
            // The injection token survives as one inert element, never split into a new command.
            assertThat(pb.command()).contains("; rm -rf /");
        }

        @Test
        void the_working_directory_is_set_when_present() {
            ExecSpec spec = new ExecSpec(List.of("mvn", "-B", "test"), "/tmp/some-module");

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.directory()).isNotNull();
            assertThat(pb.directory().getPath()).isEqualTo("/tmp/some-module");
        }

        @Test
        void a_real_spawn_captures_exit_code_stdout_and_stderr_with_timed_out_false(@TempDir Path bin) throws Exception {
            // A POSIX-only smoke of a REAL ProcessBuilder spawn (the host build runs on Linux);
            // skipped on Windows where a "#!/bin/sh" script is not directly executable.
            assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                    "POSIX shell script fixture — Windows uses a .cmd/.bat shim path");
            // A fake "mvn" that writes to both streams and exits non-zero, proving the adapter
            // captures all three channels from a REAL ProcessBuilder spawn. argv[0] is the
            // absolute path of the fake so resolution is deterministic in the test sandbox.
            Path fakeMvn = bin.resolve("mvn");
            Files.writeString(fakeMvn, "#!/bin/sh\n"
                    + "echo 'out line'\n"
                    + "echo 'err line' 1>&2\n"
                    + "exit 7\n");
            fakeMvn.toFile().setExecutable(true);

            ExecSpec spec = new ExecSpec(List.of(fakeMvn.toString(), "-B", "test"), bin.toString());

            ExecResult result = anyExecutor().execute(spec);

            assertThat(result.exitCode()).isEqualTo(7);
            assertThat(result.stdout()).contains("out line");
            assertThat(result.stderr()).contains("err line");
            // timedOut is define-and-read only in this slice — the real executor hardcodes false.
            assertThat(result.timedOut()).isFalse();
        }
    }
}
