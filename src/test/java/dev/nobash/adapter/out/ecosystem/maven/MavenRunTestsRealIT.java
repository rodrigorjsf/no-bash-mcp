package dev.nobash.adapter.out.ecosystem.maven;

import dev.nobash.application.verb.tests.RunTestsUseCase;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.result.Outcome;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-{@code mvn} integration test for the Maven {@code run_tests} leg — the regression net the
 * stubbed {@code RunTestsExecutionTest} cannot provide. It wires the production
 * {@link RunTestsUseCase} with the REAL {@code CommandExecutorPort} (no {@code @MockBean}), copies
 * the committed passing fixture to a temp dir, and drives a genuine {@code mvn -B test}.
 *
 * <p>This is the test that exposes (and now guards against) the report-directory bug: a passing
 * Maven project MUST return {@code ok=true} with real findings, not {@code REPORT_NOT_PRODUCED}.
 * Surefire ignores the {@code -Dsurefire.reportsDirectory} user-property and always writes the
 * default {@code target/surefire-reports}; the adapter must read THAT directory.</p>
 *
 * <p>Gated as a Failsafe {@code *IT.java}: it self-skips when {@code mvn} is not on PATH, so the
 * inline unit gate is unaffected. In CI (mvn present) it is a hard gate.</p>
 */
@MicronautTest(startApplication = false)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class MavenRunTestsRealIT {

    @Inject
    RunTestsUseCase useCase;

    @Test
    void a_passing_maven_project_returns_ok_true_with_real_findings(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(isOnPath("mvn"), "SKIPPED: 'mvn' is not on PATH");

        Path project = copyDir(Path.of("src/test/resources/fixtures/it/passing"), tmp.resolve("passing"));

        Envelope env = useCase.run(project.toString(), List.of(), null);

        assertThat(env.ok())
                .as("a passing Maven project must return ok=true, not REPORT_NOT_PRODUCED")
                .isTrue();
        assertThat(env.verb()).isEqualTo("run_tests");
        assertThat(env.manager()).isEqualTo("mvn");
        assertThat(env.summary().passed())
                .as("the surefire report of a passing project must be read and normalized")
                .isGreaterThan(0);
        assertThat(env.error()).isNull();
    }

    @Test
    void a_failing_maven_project_returns_ok_false_with_failures(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(isOnPath("mvn"), "SKIPPED: 'mvn' is not on PATH");

        Path project = copyDir(Path.of("src/test/resources/fixtures/it/failing"), tmp.resolve("failing"));

        Envelope env = useCase.run(project.toString(), List.of(), null);

        assertThat(env.ok()).as("a failing Maven project must return ok=false").isFalse();
        assertThat(env.failures())
                .as("the failing test must surface as a normalized finding (not REPORT_NOT_PRODUCED)")
                .isNotEmpty();
        // Pin the actual mapping: the intentionally-failing test must surface as a FAILED finding,
        // not merely a non-null outcome (which every finding has by construction).
        assertThat(env.failures())
                .as("the intentionally-failing test must be normalized to outcome=FAILED")
                .anySatisfy(f -> assertThat(f.outcome()).isEqualTo(Outcome.FAILED));
    }

    private static boolean isOnPath(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static Path copyDir(Path src, Path dst) throws IOException {
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.toList()) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(p, target);
                }
            }
        }
        return dst;
    }
}
