package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The multi-ecosystem SELECTION the use-case owns (ADR-0011, PRD-3 slice 2). With more than one
 * {@link EcosystemAdapter} registered, {@link RunTestsUseCase} dispatches on
 * {@link EcosystemAdapter#detects(Path)}:
 * <ul>
 *   <li>0 match → {@code NO_MANAGER_DETECTED};</li>
 *   <li>exactly 1 → that adapter runs (the others are inert);</li>
 *   <li>≥2 → {@code AMBIGUOUS_SCOPE}, fail-closed, with a "pass the sub-project path" hint.</li>
 * </ul>
 *
 * <p>The drive is two marker-file fakes: a {@link MarkerAdapter} detects iff a chosen marker file
 * exists at the dir, so a polyglot temp dir (both markers present) makes exactly two adapters
 * detect. The fakes never reach execution unless selection resolves to exactly one — proven by an
 * {@link ExplodingExecutorSpy} that fails if the executor is consulted on the ambiguous/none
 * branches.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RunTestsSelectionTest {

    /** A port that explodes if consulted — proves selection returns before any execution. */
    private static final class ExplodingExecutorSpy implements CommandExecutorPort {
        @Override
        public boolean isManagerInstalled() {
            throw new AssertionError("executor port was consulted — selection should have returned first");
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            throw new AssertionError("executor was launched — selection should have returned first");
        }
    }

    /** A port whose execute returns a caller-chosen result; installed is always true. */
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
     * A fake {@link EcosystemAdapter} that detects iff its marker file is a regular file at the
     * dir — exactly how the real Maven/Go adapters key on {@code pom.xml}/{@code go.mod}. Its
     * {@code interpret} returns a fixed green run so a single-match selection proceeds cleanly.
     */
    private static final class MarkerAdapter implements EcosystemAdapter {
        private final String marker;
        private final String manager;

        MarkerAdapter(String marker, String manager) {
            this.marker = marker;
            this.manager = manager;
        }

        @Override
        public boolean detects(Path dir) {
            return Files.isRegularFile(dir.resolve(marker));
        }

        @Override
        public String managerBinary() {
            return manager;
        }

        @Override
        public String markerDescription() {
            return marker;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                                    @Nullable TestTarget target) {
            ExecSpec spec = new ExecSpec(List.of(manager, "test"), workingDir.toString(), timeoutSeconds);
            return new ReportPlan(spec, workingDir);
        }

        @Override
        public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
            TestFinding passed = new TestFinding(manager + "Suite", "alpha", List.of(),
                    Outcome.PASSED, "passed", null, null, null);
            NormalizedRun run = new NormalizedRun(manager + "-tool",
                    new Summary(1, 1, 0, 0, 0), List.of(passed));
            return RunInterpretation.normalized(run);
        }

        @Override
        public List<Finding> partialFindings(ReportPlan plan) {
            return List.of();
        }
    }

    private static RunTestsUseCase useCaseWith(CommandExecutorPort port, EcosystemAdapter... adapters) {
        return new RunTestsUseCase(port, List.of(adapters),
                new TestsFlagPolicy(), new RawOutputStash(), new ModuleLock());
    }

    // ---- ≥2 ecosystems match → AMBIGUOUS_SCOPE, fail-closed, with the sub-project hint ----
    @Test
    void two_ecosystems_matching_the_same_path_return_AMBIGUOUS_SCOPE_without_launching(@TempDir Path dir)
            throws Exception {
        // A polyglot root: both markers present, so BOTH adapters detect → ambiguous.
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("go.mod"), "module example.com/x\n");

        Envelope env = useCaseWith(new ExplodingExecutorSpy(),
                new MarkerAdapter("pom.xml", "mvn"),
                new MarkerAdapter("go.mod", "go"))
                .run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error()).isNotNull();
        assertThat(env.error().code()).isEqualTo(ErrorCode.AMBIGUOUS_SCOPE);
    }

    @Test
    void the_AMBIGUOUS_SCOPE_hint_tells_the_agent_to_pass_the_sub_project_path(@TempDir Path dir)
            throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project/>");
        Files.writeString(dir.resolve("go.mod"), "module example.com/x\n");

        Envelope env = useCaseWith(new ExplodingExecutorSpy(),
                new MarkerAdapter("pom.xml", "mvn"),
                new MarkerAdapter("go.mod", "go"))
                .run(dir.toString(), List.of(), null);

        String hint = env.error().hint().toLowerCase();
        assertThat(hint).contains("sub-project");
        // It names both matched markers so the agent knows what collided.
        String text = (env.error().message() + " " + env.error().hint());
        assertThat(text).contains("pom.xml");
        assertThat(text).contains("go.mod");
    }

    // ---- exactly 1 ecosystem matches → that adapter runs (the other is inert) ----
    @Test
    void exactly_one_matching_ecosystem_is_selected_and_runs(@TempDir Path dir) throws Exception {
        // Only go.mod present → the Maven adapter does not detect; Go is the sole match.
        Files.writeString(dir.resolve("go.mod"), "module example.com/x\n");

        Envelope env = useCaseWith(new FakePort(new ExecResult(0, "", "", false)),
                new MarkerAdapter("pom.xml", "mvn"),
                new MarkerAdapter("go.mod", "go"))
                .run(dir.toString(), List.of(), null);

        assertThat(env.ok()).as("the single matching ecosystem ran to a green result").isTrue();
        assertThat(env.manager()).as("the SELECTED adapter's manager is reported").isEqualTo("go");
    }

    // ---- 0 ecosystems match → NO_MANAGER_DETECTED, listing every known marker ----
    @Test
    void no_matching_ecosystem_returns_NO_MANAGER_DETECTED_listing_every_known_marker(@TempDir Path dir) {
        // Neither marker file exists → zero adapters detect.
        Envelope env = useCaseWith(new ExplodingExecutorSpy(),
                new MarkerAdapter("pom.xml", "mvn"),
                new MarkerAdapter("go.mod", "go"))
                .run(dir.toString(), List.of(), null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.NO_MANAGER_DETECTED);
        String text = env.error().message() + " " + env.error().hint();
        assertThat(text).contains("pom.xml");
        assertThat(text).contains("go.mod");
    }
}
