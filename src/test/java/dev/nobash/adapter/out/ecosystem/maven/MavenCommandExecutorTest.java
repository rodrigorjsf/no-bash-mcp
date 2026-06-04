package dev.nobash.adapter.out.ecosystem.maven;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

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
}
