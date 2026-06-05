package dev.nobash.adapter.out.git;

import dev.nobash.adapter.out.ecosystem.maven.ManagerPathResolver;
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
 * Unit tests for {@link GitCommandExecutor} (PRD-002, issue #24), mirroring
 * {@code MavenCommandExecutorTest}. The git adapter resolves the <em>trusted system</em>
 * {@code git} via the shared {@link ManagerPathResolver} seam (reused from the Maven package) and
 * launches the explicit argv array directly — never through a shell, never a repo wrapper
 * (ADR-0008).
 *
 * <p>The actual PATH-scanning logic ({@code PathScanningManagerResolver}) is proven exhaustively
 * by {@code MavenCommandExecutorTest} (same shared resolver); here the presence tests use the
 * public {@link ManagerPathResolver} functional seam to prove the git adapter delegates resolution
 * for the {@code "git"} manager name.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitCommandExecutorTest {

    @Nested
    class manager_presence_via_the_resolver_seam {

        @Test
        void reports_absent_when_the_resolver_does_not_find_git() {
            // A resolver that resolves nothing — git is absent.
            ManagerPathResolver resolver = manager -> false;
            GitCommandExecutor executor = new GitCommandExecutor(resolver);

            assertThat(executor.isManagerInstalled()).isFalse();
        }

        @Test
        void reports_present_when_the_resolver_finds_git() {
            // A resolver that resolves ONLY "git" — proves the adapter queries the right name.
            ManagerPathResolver resolver = "git"::equals;
            GitCommandExecutor executor = new GitCommandExecutor(resolver);

            assertThat(executor.isManagerInstalled()).isTrue();
        }
    }

    @Nested
    class execute_launches_the_argv_array_directly {

        private GitCommandExecutor anyExecutor() {
            return new GitCommandExecutor(manager -> true);
        }

        @Test
        void the_process_command_is_the_argv_array_starting_with_the_system_git() {
            ExecSpec spec = new ExecSpec(List.of("git", "status", "--porcelain=v2", "--branch"), null);

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.command()).containsExactly("git", "status", "--porcelain=v2", "--branch");
            assertThat(pb.command().get(0)).isEqualTo("git");
        }

        @Test
        void no_shell_interpreter_is_prepended_to_the_launched_command() {
            ExecSpec spec = new ExecSpec(List.of("git", "status", "; rm -rf /"), null);

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.command()).doesNotContain("/bin/sh", "-c", "sh", "bash", "cmd");
            // The injection token survives as one inert element, never split into a new command.
            assertThat(pb.command()).contains("; rm -rf /");
        }

        @Test
        void the_working_directory_is_set_when_present() {
            ExecSpec spec = new ExecSpec(List.of("git", "status"), "/tmp/some-repo");

            ProcessBuilder pb = anyExecutor().toProcessBuilder(spec);

            assertThat(pb.directory()).isNotNull();
            assertThat(pb.directory().getPath()).isEqualTo("/tmp/some-repo");
        }

        @Test
        void a_real_spawn_captures_exit_code_stdout_and_stderr_with_timed_out_false(@TempDir Path bin)
                throws Exception {
            assumeTrue(!System.getProperty("os.name", "").toLowerCase().contains("win"),
                    "POSIX shell script fixture — Windows uses a .cmd/.bat shim path");
            Path fakeGit = bin.resolve("git");
            Files.writeString(fakeGit, "#!/bin/sh\n"
                    + "echo 'on branch main'\n"
                    + "echo 'err line' 1>&2\n"
                    + "exit 3\n");
            fakeGit.toFile().setExecutable(true);

            ExecSpec spec = new ExecSpec(List.of(fakeGit.toString(), "status"), bin.toString());

            ExecResult result = anyExecutor().execute(spec);

            assertThat(result.exitCode()).isEqualTo(3);
            assertThat(result.stdout()).contains("on branch main");
            assertThat(result.stderr()).contains("err line");
            assertThat(result.timedOut()).isFalse();
        }
    }
}
