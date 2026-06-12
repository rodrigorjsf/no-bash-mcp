package dev.nobash.adapter.out.ecosystem.maven;

import dev.nobash.application.verb.tests.ArgvBuilder;
import dev.nobash.application.verb.tests.EcosystemAdapter;
import dev.nobash.application.verb.tests.ReportPlan;
import dev.nobash.application.verb.tests.RunInterpretation;
import dev.nobash.application.verb.tests.TestTarget;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.domain.result.SurefireNormalizer;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The Maven {@link EcosystemAdapter} (ADR-0011) — the SOLE implementation in this slice. It owns
 * everything that varies for the JVM/Maven ecosystem and was extracted verbatim, behaviour
 * preserving, from {@code RunTestsUseCase}:
 *
 * <ul>
 *   <li>marker detection ({@code pom.xml}) and the trusted launcher name ({@code mvn});</li>
 *   <li>the installed-check, delegated to the format-blind {@link CommandExecutorPort} seam (NOT
 *       to {@code ManagerPathResolver} — that would hit the real PATH and break the DI-wired
 *       execution/guard tests; the stubbed port's {@code isManagerInstalled()} must be honoured);</li>
 *   <li>report freshness by construction (D27): Surefire ignores {@code -Dsurefire.reportsDirectory}
 *       and, for a <em>single-module</em> project, writes the default
 *       {@code <module>/target/surefire-reports}, so the adapter wipes that directory before exec
 *       and reads it after; the {@code -Dtest=} selector is injected via {@link ArgvBuilder}.
 *       (Multi-module reactor roots are a known gap — submodule reports land under
 *       {@code <submodule>/target} and are not aggregated here;)</li>
 *   <li>reading that dir's {@code *.xml}, normalizing via {@link SurefireNormalizer}, and the
 *       empty-dir → {@code REPORT_NOT_PRODUCED} (D25/D27) report-absence decision.</li>
 * </ul>
 *
 * <p>The invariant orchestration — the lock, the timeout intercept + tree-kill, the D27/D28/D29
 * floor, and the run-cache stash/handle — stays single-source in the use-case (ADR-0011). This
 * adapter never injects {@code RawOutputStash} or {@code ModuleLock}; it returns VALUES the
 * use-case consumes.</p>
 */
@Singleton
public class MavenEcosystemAdapter implements EcosystemAdapter {

    private static final String MANAGER = "mvn";
    private static final String MANAGER_MARKER = "pom.xml";

    private final CommandExecutorPort executor;
    private final ArgvBuilder argvBuilder;
    private final SurefireNormalizer normalizer = new SurefireNormalizer();

    public MavenEcosystemAdapter(CommandExecutorPort executor, ArgvBuilder argvBuilder) {
        this.executor = executor;
        this.argvBuilder = argvBuilder;
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
        // Delegate to the format-blind port seam — it resolves the trusted system mvn on PATH
        // (ADR-0008), never a repo wrapper. Routing through the port (not ManagerPathResolver) is
        // load-bearing: the DI-wired tests stub the port's isManagerInstalled().
        return executor.isManagerInstalled();
    }

    @Override
    public ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                                @Nullable TestTarget target) {
        // Surefire ignores -Dsurefire.reportsDirectory (it is not a CLI-overridable user-property)
        // and, for a SINGLE-MODULE project, writes the default <module>/target/surefire-reports. Read
        // that directory, and guarantee report freshness (D27) by wiping it before exec so any XML
        // present afterwards is necessarily from THIS run. The agent cannot redirect it via flags —
        // its -Dsurefire.reportsDirectory / -D* are dropped by the allowlist; the path is derived
        // from the working directory, not agent input. This is NOT a sandbox (the working dir is
        // agent-supplied and `mvn test` already mutates target/ there — security-model.md); the wipe
        // refuses to follow a symlinked target/ or reports dir so it never deletes through an alias.
        // KNOWN GAP: multi-module reactor roots — submodule reports land under <submodule>/target,
        // which this single-dir read does not aggregate (a pure aggregator root yields
        // REPORT_NOT_PRODUCED). Any -Dtest= selector is injected by the MCP (never agent input).
        Path reportsDir = workingDir.resolve("target").resolve("surefire-reports");
        try {
            wipeForFreshness(workingDir, reportsDir);
        } catch (UncheckedIOException e) {
            // An undeletable reports dir (read-only dir/mount, or a file owned by another user from a
            // prior containerized run) must fail CLOSED with a structured operational error — never a
            // thrown exception (the Envelope contract). Short-circuit via an inert preflight, exactly
            // like the Node adapter's pre-exec conditions; interpret returns it verbatim.
            return inert(timeoutSeconds, workingDir, RunInterpretation.reportAbsent(
                    "", ErrorCode.REPORT_DIR_UNWRITABLE,
                    "Could not prepare a fresh Surefire reports directory at " + reportsDir
                            + " — an entry could not be deleted (a read-only directory/mount, or a "
                            + "file owned by another user).",
                    "Make " + reportsDir + " writable (or remove it), then re-run run_tests."));
        }
        ExecSpec spec = argvBuilder.buildTestArgv(vettedFlags, workingDir.toString(), timeoutSeconds, target);
        return new ReportPlan(spec, reportsDir);
    }

    @Override
    public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
        // A preflight decision (e.g. an unwritable reports dir) short-circuits — the inert no-op
        // already ran; return it verbatim so the use-case routes it through its operational-error
        // channel (zero ecosystem literals in the use-case).
        if (plan.preflight() != null) {
            return plan.preflight();
        }
        // Empty fresh dir after exec ⇒ no Surefire report ⇒ compile failure (D25/D27). Carry the
        // compiler stderr as the stash payload; the use-case keeps the stash/handle and assembles
        // the operational-error envelope (the run-cache stays invariant).
        List<String> reportXmls = readReportXmls(plan.reportSource());
        if (reportXmls.isEmpty()) {
            return RunInterpretation.reportAbsent(result.stderr(), ErrorCode.REPORT_NOT_PRODUCED,
                    "The build produced no test report (a compile failure is the usual cause).",
                    "Run `build` to see the compiler errors (retained behind the handle).");
        }
        NormalizedRun run = normalizer.normalizeAll(reportXmls);
        return RunInterpretation.normalized(run);
    }

    @Override
    public List<Finding> partialFindings(ReportPlan plan) {
        // Best-effort partial findings on a timeout: a Surefire run that wrote some report XML
        // before the kill leaves fresh PASSED/FAILED rows in the dir. A timeout that wrote nothing
        // yields an empty list.
        List<String> reportXmls = readReportXmls(plan.reportSource());
        if (reportXmls.isEmpty()) {
            return List.of();
        }
        return normalizer.normalizeAll(reportXmls).findings();
    }

    /**
     * Prepare a fresh Surefire reports directory (D27). Refuses to follow an agent-planted symlink:
     * if {@code target/} or {@code surefire-reports} is a symbolic link, the wipe is skipped rather
     * than deleting THROUGH the alias to a directory outside the module tree (the read path tolerates
     * a non-fresh dir, and {@code mvn} would write through the alias regardless — not-a-sandbox).
     * Otherwise the directory is wiped so any XML present after the run is necessarily from THIS run.
     */
    private static void wipeForFreshness(Path workingDir, Path reportsDir) {
        if (Files.isSymbolicLink(workingDir.resolve("target")) || Files.isSymbolicLink(reportsDir)) {
            return;
        }
        deleteRecursively(reportsDir);
    }

    /**
     * The inert preflight plan: a side-effect-free {@code mvn --version} no-op (mvn is present —
     * TOOL_NOT_INSTALLED already passed) paired with a {@code preflight} decision the use-case
     * surfaces as a structured operational error. Mirrors the Node adapter's preflight short-circuit.
     */
    private static ReportPlan inert(int timeoutSeconds, Path workingDir, RunInterpretation preflight) {
        ExecSpec noop = new ExecSpec(List.of(MANAGER, "--version"), workingDir.toString(), timeoutSeconds);
        return new ReportPlan(noop, workingDir, preflight);
    }

    /**
     * Recursively delete a directory tree, deepest-first. A non-existent directory is a no-op (the
     * first run on a clean checkout). {@link Files#walk} does not follow symbolic links, so a
     * symlinked entry is removed as a link, never descended. Throws {@link UncheckedIOException} on
     * any undeletable entry; {@link #buildExec} catches it and fails closed with an operational error.
     */
    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> entries = Files.walk(dir)) {
            entries.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to wipe a stale reports entry: " + p, e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to wipe the reports directory: " + dir, e);
        }
    }

    /** Read every {@code *.xml} in the fresh reports dir as in-memory content for the normalizer. */
    private static List<String> readReportXmls(Path reportsDir) {
        if (!Files.isDirectory(reportsDir)) {
            return List.of();
        }
        try (Stream<Path> entries = Files.list(reportsDir)) {
            List<String> xmls = new ArrayList<>();
            for (Path p : entries.filter(MavenEcosystemAdapter::isXml).sorted().toList()) {
                xmls.add(Files.readString(p, StandardCharsets.UTF_8));
            }
            return xmls;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the reports directory", e);
        }
    }

    private static boolean isXml(Path p) {
        return Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".xml");
    }
}
