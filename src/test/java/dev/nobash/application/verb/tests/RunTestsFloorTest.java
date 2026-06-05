package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.Summary;
import dev.nobash.domain.result.TestFinding;
import dev.nobash.infra.concurrency.ModuleLock;
import io.micronaut.core.annotation.Nullable;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC6 — the anti-false-green failure floor (D27/D28/D29) is single-source in
 * {@link RunTestsUseCase} and is exercised here through the NEW seam — a hand-written
 * {@link EcosystemAdapter} — entirely independent of Maven. This proves the floor lives in the
 * ecosystem-agnostic use-case, not in any adapter: a future Node/Go adapter cannot reopen a
 * false-green hole because it never touches the floor (ADR-0011).
 *
 * <p>The drive is two fakes: a {@link FakePort} returning a caller-chosen {@link ExecResult}
 * (exit code / timedOut), and a {@link FakeEcosystemAdapter} whose {@code interpret} returns a
 * caller-chosen {@link NormalizedRun} (ignoring the result). The four floor cases are then pure
 * use-case behaviour over the returned run.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunTestsFloorTest {

    /** A port whose {@code execute} returns a caller-chosen result; installed is always true. */
    private static final class FakePort implements CommandExecutorPort {
        private final ExecResult result;

        FakePort(ExecResult result) {
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

    /**
     * A non-Maven {@link EcosystemAdapter} fake: it detects + installs trivially, builds a trivial
     * plan, and returns a caller-chosen {@link NormalizedRun} from {@code interpret} (or a
     * report-absence signal). The floor under test never inspects the plan's report source — only
     * the use-case's D27/D28/D29 decision over the returned run is exercised.
     */
    private static final class FakeEcosystemAdapter implements EcosystemAdapter {
        private final RunInterpretation interpretation;

        FakeEcosystemAdapter(RunInterpretation interpretation) {
            this.interpretation = interpretation;
        }

        @Override
        public boolean detects(Path dir) {
            return true;
        }

        @Override
        public String managerBinary() {
            return "fake";
        }

        @Override
        public String markerDescription() {
            return "fake.marker";
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                                    @Nullable TestTarget target) {
            ExecSpec spec = new ExecSpec(List.of("fake", "test"), workingDir.toString(), timeoutSeconds);
            return new ReportPlan(spec, workingDir);
        }

        @Override
        public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
            return interpretation;
        }

        @Override
        public List<Finding> partialFindings(ReportPlan plan) {
            return List.of();
        }
    }

    private static RunTestsUseCase useCaseWith(ExecResult result, RunInterpretation interpretation) {
        // Single-adapter list (the fake) so selection always resolves to one — these tests encode
        // the D27/D28/D29 anti-false-green floor in the use-case, never AMBIGUOUS_SCOPE.
        return new RunTestsUseCase(new FakePort(result),
                java.util.List.of(new FakeEcosystemAdapter(interpretation)),
                new TestsFlagPolicy(), new RawOutputStash(), new ModuleLock());
    }

    private static NormalizedRun run(Summary summary, List<Finding> findings) {
        return new NormalizedRun("fake-tool", summary, findings);
    }

    private static TestFinding passed(String name) {
        return new TestFinding("FakeSuite", name, List.of(), Outcome.PASSED, "passed", null, null, null);
    }

    private static ContainerFinding containerError() {
        return new ContainerFinding(ContainerScope.SUITE, "FakeSuite", Outcome.ERRORED, "error",
                "container setup blew up", null, null);
    }

    // ---- D29 — executedTests==0 AND run.ok() → NO_TESTS_RUN (positive-evidence floor) ----
    @Test
    void zero_executed_tests_with_an_otherwise_green_run_is_NO_TESTS_RUN(@TempDir Path dir) {
        // No findings at all → run.ok()==true, executedTests==0.
        NormalizedRun green = run(new Summary(0, 0, 0, 0, 0), List.of());
        RunTestsUseCase useCase = useCaseWith(new ExecResult(0, "", "", false),
                RunInterpretation.normalized(green));

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error()).isNotNull();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NO_TESTS_RUN);
    }

    // ---- D28 — non-zero exit floors ok to false even with all-passed findings ----
    @Test
    void a_non_zero_exit_with_all_passed_findings_floors_ok_to_false(@TempDir Path dir) {
        NormalizedRun allPassed = run(new Summary(1, 1, 0, 0, 0), List.of(passed("alpha")));
        RunTestsUseCase useCase = useCaseWith(new ExecResult(1, "", "", false),
                RunInterpretation.normalized(allPassed));

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).as("D28: a non-zero exit floors ok to false even with all-passed findings")
                .isFalse();
        // It is a test-failure envelope (not an operational error) carrying the run's findings.
        assertThat(env.error()).isNull();
        assertThat(env.failures()).isNotEmpty();
    }

    // ---- the G5 keystone — container-only run (executedTests==0 && !ok) → testFailure NOT NO_TESTS_RUN ----
    @Test
    void a_container_only_run_is_a_test_failure_not_NO_TESTS_RUN(@TempDir Path dir) {
        // A ContainerFinding(ERRORED) makes run.ok()==false, yet executedTests==0 (a container is
        // not a test). It must fall through to a test-failure envelope carrying the container
        // finding — never NO_TESTS_RUN (the vacuous-green trap the floor exists to close).
        NormalizedRun containerOnly = run(new Summary(0, 0, 0, 0, 0), List.of(containerError()));
        RunTestsUseCase useCase = useCaseWith(new ExecResult(1, "", "", false),
                RunInterpretation.normalized(containerOnly));

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.error()).as("a container-only run is NOT an operational error").isNull();
        assertThat(env.ok()).isFalse();
        assertThat(env.failures()).singleElement().isInstanceOf(ContainerFinding.class);
    }

    // ---- success only when ALL four floor conditions hold (ok && exit0 && !timedOut && executed>0) ----
    @Test
    void success_only_when_green_exit_zero_not_timed_out_and_at_least_one_test_executed(@TempDir Path dir) {
        NormalizedRun green = run(new Summary(1, 1, 0, 0, 0), List.of(passed("alpha")));
        RunTestsUseCase useCase = useCaseWith(new ExecResult(0, "", "", false),
                RunInterpretation.normalized(green));

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isTrue();
        assertThat(env.manager()).isEqualTo("fake");
        assertThat(env.summary().passed()).isEqualTo(1);
        assertThat(env.failures()).as("a green run surfaces no report").isNull();
        assertThat(env.error()).isNull();
        assertThat(env.handle()).isNotNull();
    }

    // ---- the timeout intercept (D28-first) runs over the seam, before the report-absence check ----
    @Test
    void a_timed_out_run_is_a_TIMEOUT_operational_error_regardless_of_the_interpretation(@TempDir Path dir) {
        // Even a fully-green interpretation must not green a timed-out run: the intercept fires
        // BEFORE interpret is consulted for the outcome decision, flooring ok to false (D28).
        NormalizedRun green = run(new Summary(1, 1, 0, 0, 0), List.of(passed("alpha")));
        RunTestsUseCase useCase = useCaseWith(new ExecResult(-1, "partial out\n", "", true),
                RunInterpretation.normalized(green));

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error()).isNotNull();
        assertThat(env.error().code()).isEqualTo(ErrorCode.TIMEOUT);
        assertThat(env.summary()).as("a TIMEOUT op-error carries no summary").isNull();
        assertThat(env.handle()).as("the partial signal is behind the handle").isNotNull();
    }

    // ---- the report-absence signal flows through the invariant op-error branch unchanged ----
    @Test
    void a_report_absent_interpretation_becomes_the_adapter_supplied_operational_error(@TempDir Path dir) {
        // The adapter signals report-absence with its own code/message/hint; the use-case keeps the
        // stash/handle + envelope assembly with zero ecosystem literals.
        RunInterpretation absent = RunInterpretation.reportAbsent("raw compiler stderr",
                ErrorCode.REPORT_NOT_PRODUCED, "no report", "run build");
        RunTestsUseCase useCase = useCaseWith(new ExecResult(1, "", "raw compiler stderr", false), absent);

        Envelope env = useCase.run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error()).isNotNull();
        assertThat(env.error().code()).isEqualTo(ErrorCode.REPORT_NOT_PRODUCED);
        assertThat(env.handle()).isNotNull();
    }
}
