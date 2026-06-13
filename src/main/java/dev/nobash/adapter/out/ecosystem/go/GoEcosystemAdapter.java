package dev.nobash.adapter.out.ecosystem.go;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
import dev.nobash.application.verb.tests.EcosystemAdapter;
import dev.nobash.application.verb.tests.ReportPlan;
import dev.nobash.application.verb.tests.RunInterpretation;
import dev.nobash.application.verb.tests.TestTarget;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.GoTestJsonParser;
import dev.nobash.domain.result.NormalizedRun;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The Go {@link EcosystemAdapter} (ADR-0011, PRD-3 slice 2). It owns everything that varies for the
 * Go ecosystem; the invariant orchestration — the lock, the timeout intercept + tree-kill, the
 * D27/D28/D29 floor, and the run-cache stash/handle — stays single-source in
 * {@code RunTestsUseCase}. Like the Maven adapter, it returns VALUES the use-case consumes and
 * never injects {@code RawOutputStash} or {@code ModuleLock}.
 *
 * <ul>
 *   <li><b>Marker</b> — a {@code go.mod} regular file at the project dir; the launcher name is
 *       {@code go}.</li>
 *   <li><b>Installed-check</b> — delegated to the generic {@link ManagerPathResolver} for
 *       {@code go} on PATH (ADR-0008). NOT the {@link CommandExecutorPort#isManagerInstalled()}
 *       seam: that check is hardcoded to {@code mvn} and would wrongly report Go's presence. This
 *       is the one Go correctness trap — a stubbed port would otherwise pass tests while being
 *       wrong in production.</li>
 *   <li><b>Exec</b> — a full-suite {@code go test -json ./...} {@link ExecSpec}, launched through
 *       the format-blind {@link CommandExecutorPort} ({@code argv[0]="go"} verbatim, no shell). Go
 *       needs no agent flags for v1, so {@code vettedFlags} are ignored (no Go flag policy — YAGNI)
 *       and full-suite is the only mode (the structured {@code target} is Maven-specific and not
 *       wired for Go in this slice).</li>
 *   <li><b>Interpret</b> — Go writes its report to <em>stdout</em>, so the report source is the
 *       captured {@code stdout}; {@link GoTestJsonParser} folds the NDJSON into a
 *       {@link NormalizedRun}. {@code interpret} ALWAYS returns {@code normalized(run)} — a Go
 *       build failure folds INTO the graph as a {@code ContainerFinding(PACKAGE, ERRORED)}, so
 *       there is no Maven-style report-absence. A build-fail run is {@code executedTests==0 &&
 *       !run.ok()}, which the use-case's floor routes to a test-failure envelope (the G5 keystone),
 *       never {@code NO_TESTS_RUN}.</li>
 * </ul>
 */
@Singleton
public class GoEcosystemAdapter implements EcosystemAdapter {

    private static final String MANAGER = "go";
    private static final String MANAGER_MARKER = "go.mod";

    private final CommandExecutorPort executor;
    private final ManagerPathResolver resolver;
    private final GoTestJsonParser parser = new GoTestJsonParser();

    /**
     * @param executor the format-blind executor seam (@Primary → the Maven {@code CommandExecutor},
     *                 which launches {@code argv[0]} verbatim — so it runs {@code go} unchanged)
     * @param resolver the generic PATH resolver (used for the {@code go}-on-PATH installed-check;
     *                 the port's own installed-check is hardcoded to {@code mvn} and unusable here)
     */
    public GoEcosystemAdapter(CommandExecutorPort executor, ManagerPathResolver resolver) {
        this.executor = executor;
        this.resolver = resolver;
    }

    @Override
    public boolean detects(Path dir) {
        return Files.isRegularFile(dir.resolve(MANAGER_MARKER));
    }

    @Override
    public String managerBinary() {
        return MANAGER;
    }

    @Override
    public String markerDescription() {
        return MANAGER_MARKER;
    }

    @Override
    public boolean isInstalled() {
        // The generic resolver, NOT executor.isManagerInstalled() (which is hardcoded to mvn).
        return resolver.resolvesOnPath(MANAGER);
    }

    @Override
    public ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                                @Nullable TestTarget target) {
        // Full-suite: `go test -json ./...`. vettedFlags and target are intentionally ignored — Go
        // needs no agent flags and no per-test selector in this slice (YAGNI). argv[0]="go" is the
        // trusted system launcher resolved on PATH by the OS (ADR-0008), never a wrapper.
        ExecSpec spec = new ExecSpec(List.of(MANAGER, "test", "-json", "./..."),
                workingDir.toString(), timeoutSeconds);
        // Go writes its report to stdout; the report source is the working dir (unused by interpret,
        // which reads result.stdout()) — carried only to satisfy the ReportPlan contract.
        return new ReportPlan(spec, workingDir);
    }

    @Override
    public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
        // ALWAYS normalized — Go folds a build failure into the graph (ContainerFinding(PACKAGE,
        // ERRORED)), never a report-absence. The use-case's D28/D29 floor does the rest.
        NormalizedRun run = parser.parse(result.stdout() == null ? "" : result.stdout());
        return RunInterpretation.normalized(run);
    }

    @Override
    public List<Finding> partialFindings(ReportPlan plan) {
        // Go writes NDJSON to stdout, not to the plan's report source, so on a timeout the partial
        // signal lives in the (port-captured) stdout the use-case already stashes — there is no
        // separate report directory to re-read. Best-effort empty here.
        return List.of();
    }
}
