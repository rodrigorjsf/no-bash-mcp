package dev.nobash.adapter.out.ecosystem.node;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
import dev.nobash.domain.port.out.ExecSpec;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link NpmCommandExecutor} (PRD-3, slice 3).
 *
 * <p>The critical property is that {@link NpmCommandExecutor#isManagerInstalled()} probes
 * {@code "npm"} on PATH — NOT {@code "mvn"}. This is the "Go correctness trap" (identified in
 * {@code GoEcosystemAdapter}: the {@code @Primary} Maven executor's
 * {@code isManagerInstalled()} is hardcoded to {@code mvn}; a multi-manager codebase MUST probe
 * each manager independently). {@link ManagerPathResolver} is a {@code @FunctionalInterface}
 * so the test injects a capturing lambda instead of a heavyweight stub.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NpmCommandExecutorTest {

    @Nested
    class manager_presence_probes_npm_not_mvn {

        @Test
        void is_manager_installed_passes_npm_to_the_resolver() {
            // Capture the manager name the resolver is called with.
            String[] captured = new String[1];
            NpmCommandExecutor exec = new NpmCommandExecutor(manager -> {
                captured[0] = manager;
                return true;
            });

            exec.isManagerInstalled();

            assertThat(captured[0])
                    .as("the npm executor must probe 'npm', not 'mvn'")
                    .isEqualTo("npm");
        }

        @Test
        void is_manager_installed_returns_true_when_npm_on_path() {
            NpmCommandExecutor exec = new NpmCommandExecutor(manager -> true);
            assertThat(exec.isManagerInstalled()).isTrue();
        }

        @Test
        void is_manager_installed_returns_false_when_npm_absent() {
            NpmCommandExecutor exec = new NpmCommandExecutor(manager -> false);
            assertThat(exec.isManagerInstalled()).isFalse();
        }
    }

    @Nested
    class execute_launches_the_argv_array_directly {

        @Test
        void to_process_builder_carries_the_exact_argv_tokens() {
            NpmCommandExecutor exec = new NpmCommandExecutor(manager -> true);
            List<String> argv = List.of("npm", "install", "--no-audit", "--no-fund");
            ExecSpec spec = new ExecSpec(argv, null);

            ProcessBuilder pb = exec.toProcessBuilder(spec);

            assertThat(pb.command())
                    .as("argv must be forwarded verbatim — no shell wrapping")
                    .containsExactly("npm", "install", "--no-audit", "--no-fund");
        }

        @Test
        void to_process_builder_sets_working_directory_when_present(@org.junit.jupiter.api.io.TempDir
                java.nio.file.Path dir) {
            NpmCommandExecutor exec = new NpmCommandExecutor(manager -> true);
            ExecSpec spec = new ExecSpec(List.of("npm", "install"), dir.toString());

            ProcessBuilder pb = exec.toProcessBuilder(spec);

            assertThat(pb.directory())
                    .as("working directory must be set on the ProcessBuilder")
                    .isEqualTo(dir.toFile());
        }
    }
}
