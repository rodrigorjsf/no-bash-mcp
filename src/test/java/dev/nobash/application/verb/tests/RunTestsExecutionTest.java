package dev.nobash.application.verb.tests;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.TestFinding;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The end-to-end execution tracer for {@code run_tests} (issue #4, AC1–AC10). The only outbound
 * seam — {@link CommandExecutorPort} — is a {@code @MockBean} whose Mockito {@link Answer} writes
 * the chosen fixture's XML into the MCP-injected reports directory <em>at call time</em> (or
 * leaves it empty, for {@code REPORT_NOT_PRODUCED}), exactly modelling a real Maven run that
 * produces (or fails to produce) Surefire reports in the directory the MCP controls.
 *
 * <p>{@code startApplication=false} so the STDIO loop does not hijack the test JVM
 * (DESIGN.md §9); {@code @MockBean} (NOT {@code @ExtendWith(MockitoExtension)}) so the mock is
 * injected through the real DI graph the verb runs under.</p>
 */
@MicronautTest(startApplication = false)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunTestsExecutionTest {

    @Inject
    RunTestsUseCase useCase;

    @Inject
    CommandExecutorPort executor;

    @Inject
    RawOutputStash stash;

    /** Captures the ExecSpec the use-case hands to the seam, so AC9/AC10 can inspect it. */
    private final AtomicReference<ExecSpec> capturedSpec = new AtomicReference<>();

    @MockBean(CommandExecutorPort.class)
    CommandExecutorPort executorMock() {
        CommandExecutorPort m = mock(CommandExecutorPort.class);
        Mockito.when(m.isManagerInstalled()).thenReturn(true);
        return m;
    }

    /** The MCP-injected reports directory is whatever value the use-case put in the ExecSpec. */
    private Path injectedReportsDir(ExecSpec spec) {
        String token = spec.argv().stream()
                .filter(a -> a.startsWith("-Dsurefire.reportsDirectory="))
                .findFirst().orElseThrow(() -> new AssertionError("no reportsDirectory was injected"));
        return Path.of(token.substring("-Dsurefire.reportsDirectory=".length()));
    }

    /** Stub execute() to write the given fixture bytes into the injected dir and return exit. */
    private void stubExec(int exitCode, String stderr, String... fixtureResources) {
        Mockito.when(executor.execute(Mockito.any())).thenAnswer((Answer<ExecResult>) inv -> {
            ExecSpec spec = inv.getArgument(0);
            capturedSpec.set(spec);
            Path dir = injectedReportsDir(spec);
            Files.createDirectories(dir);
            int i = 0;
            for (String resource : fixtureResources) {
                Files.write(dir.resolve("TEST-fixture-" + (i++) + ".xml"), readBytes(resource));
            }
            return new ExecResult(exitCode, "", stderr, false);
        });
    }

    /** Stub execute() to leave the injected dir EMPTY (compile failure) and return exit+stderr. */
    private void stubEmpty(int exitCode, String stderr) {
        Mockito.when(executor.execute(Mockito.any())).thenAnswer((Answer<ExecResult>) inv -> {
            ExecSpec spec = inv.getArgument(0);
            capturedSpec.set(spec);
            Files.createDirectories(injectedReportsDir(spec));
            return new ExecResult(exitCode, "", stderr, false);
        });
    }

    private Path mavenProject(Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        return dir;
    }

    // ---- AC1 — all-passing run with executedTests > 0 → counts-only ok=true ----
    @Test
    void all_passing_run_returns_counts_only_ok_true_envelope(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(0, "", "fixtures/maven/surefire-all-passed.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("run_tests");
        assertThat(env.manager()).isEqualTo("mvn");
        assertThat(env.summary().passed()).isGreaterThan(0);
        assertThat(env.failures()).as("a green run surfaces no report").isNull();
        assertThat(env.error()).isNull();
    }

    // ---- AC2 — failing run → failures[] with kind/identity/outcome/source ----
    @Test
    void failing_run_returns_failures_with_kind_identity_and_best_effort_source(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(1, "", "fixtures/maven/surefire-normal-error-failure-skipped.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.failures()).isNotEmpty();
        Finding addFails = env.failures().stream()
                .filter(f -> f instanceof TestFinding tf && tf.name().equals("addFailsAssertion"))
                .findFirst().orElseThrow();
        assertThat(addFails).isInstanceOfSatisfying(TestFinding.class, tf -> {
            assertThat(tf.suite()).isEqualTo("nobash.proto.NormalTests");
            assertThat(tf.name()).isEqualTo("addFailsAssertion");
            assertThat(tf.path()).as("the flexible identity path is present (empty, never null)").isEmpty();
            assertThat(tf.outcome()).isEqualTo(dev.nobash.domain.result.Outcome.FAILED);
            assertThat(tf.rawStatus()).isEqualTo("failure");
            assertThat(tf.source()).isNotNull();
            assertThat(tf.source().file()).isEqualTo("NormalTests.java");
            assertThat(tf.source().line()).isEqualTo(25);
        });
    }

    // ---- AC3 / fixture (b) — compile failure (empty fresh dir) → REPORT_NOT_PRODUCED ----
    @Test
    void compile_failure_with_empty_fresh_dir_returns_REPORT_NOT_PRODUCED_with_raw_output_behind_handle(
            @TempDir Path dir) throws Exception {
        mavenProject(dir);
        String compilerOutput = "[ERROR] /src/Foo.java:[7,3] cannot find symbol";
        stubEmpty(1, compilerOutput);

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.REPORT_NOT_PRODUCED);
        assertThat(env.error().hint().toLowerCase()).contains("build");
        assertThat(env.handle()).isNotNull();
        assertThat(stash.get(env.handle())).contains("cannot find symbol");
    }

    // ---- AC4 / fixture (c) — non-zero exit + all-PASSED findings → ok=false (D28 exit floor) ----
    @Test
    void non_zero_exit_with_all_passed_findings_is_not_ok(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(1, "", "fixtures/maven/surefire-all-passed.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).as("D28: a non-zero exit floors ok to false even with all-passed findings").isFalse();
    }

    // ---- AC5 / fixture (d) — fresh report, zero executed testcases, exit 0 → NO_TESTS_RUN ----
    @Test
    void fresh_report_with_zero_executed_testcases_and_exit_zero_returns_NO_TESTS_RUN(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(0, "", "fixtures/maven/surefire-paramnested-outer.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NO_TESTS_RUN);
    }

    // ---- AC6 / fixture (e) — all-SKIPPED fresh report + exit 0 → NO_TESTS_RUN ----
    @Test
    void all_skipped_report_with_exit_zero_returns_NO_TESTS_RUN(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(0, "", "fixtures/maven/surefire-all-skipped.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NO_TESTS_RUN);
    }

    // ---- AC7 / fixture (g) — non-zero exit + real FAILED finding → ok=false, once, counts unchanged ----
    @Test
    void non_zero_exit_with_a_real_failed_finding_is_not_ok_and_the_failure_appears_once(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(1, "", "fixtures/maven/surefire-normal-error-failure-skipped.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        // Summary.failed is the report's own count (2), untouched by the floor — the floor adds
        // only the boolean, never a synthetic finding.
        assertThat(env.summary().failed()).isEqualTo(2);
        long addFailsOccurrences = env.failures().stream()
                .filter(f -> f instanceof TestFinding tf && tf.name().equals("addFailsAssertion"))
                .count();
        assertThat(addFailsOccurrences).as("the failure appears exactly once").isEqualTo(1);
    }

    // ---- AC8 / fixture (a) — container-only run → ok=false test-failure envelope (NOT NO_TESTS_RUN) ----
    @Test
    void container_only_run_is_a_not_ok_test_failure_envelope_carrying_the_container_finding(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(1, "", "fixtures/maven/surefire-container-beforeall-error.xml");

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        // executedTests==0 here too, but run.ok()==false, so it must NOT be NO_TESTS_RUN — it is
        // a test-failure envelope carrying the container finding (the G5 keystone).
        assertThat(env.error()).isNull();
        assertThat(env.ok()).isFalse();
        assertThat(env.failures()).singleElement().isInstanceOf(ContainerFinding.class);
    }

    // ---- AC10 — the reportsDirectory token is MCP-injected and an agent flag cannot supply it ----
    @Test
    void the_reports_directory_is_mcp_injected_and_not_droppable_agent_input(@TempDir Path dir) throws Exception {
        mavenProject(dir);
        stubExec(0, "", "fixtures/maven/surefire-all-passed.xml");

        // The agent tries to smuggle its own reportsDirectory (and -DskipTests) via flags.
        useCase.run(dir.toString(),
                List.of("-Dsurefire.reportsDirectory=/tmp/agent-controlled", "-DskipTests"), null);

        ExecSpec spec = capturedSpec.get();
        List<String> reportDirTokens = spec.argv().stream()
                .filter(a -> a.startsWith("-Dsurefire.reportsDirectory=")).toList();
        // Exactly ONE reportsDirectory token, and it is NOT the agent's value (the agent flag was
        // dropped by the allowlist; the MCP appended its own fresh-tmp value).
        assertThat(reportDirTokens).hasSize(1);
        assertThat(reportDirTokens.get(0)).doesNotContain("/tmp/agent-controlled");
        assertThat(spec.argv()).doesNotContain("-DskipTests");
    }

    private static byte[] readBytes(String resource) {
        try (var in = RunTestsExecutionTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture on classpath: " + resource);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read fixture: " + resource, e);
        }
    }
}
