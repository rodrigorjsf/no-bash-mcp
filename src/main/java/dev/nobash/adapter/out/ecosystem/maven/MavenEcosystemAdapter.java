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
 *   <li>fresh report-dir allocation (report freshness by construction, D27) + the
 *       {@code -Dsurefire.reportsDirectory=}/{@code -Dtest=} injection via {@link ArgvBuilder};</li>
 *   <li>reading the fresh dir's {@code *.xml}, normalizing via {@link SurefireNormalizer}, and the
 *       empty-fresh-dir → {@code REPORT_NOT_PRODUCED} (D25/D27) report-absence decision.</li>
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
    private static final String REPORTS_DIR_PREFIX = "no-bash-mcp-surefire-";

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
        // Allocate a unique, empty-before-exec reports directory so any XML in it is necessarily
        // from THIS run (report freshness by construction, D27). The reports-dir flag and any
        // -Dtest= selector are injected by the MCP (never agent input).
        Path freshReportsDir = allocateFreshReportsDir();
        ExecSpec spec = argvBuilder.buildTestArgv(vettedFlags, freshReportsDir.toString(),
                workingDir.toString(), timeoutSeconds, target);
        return new ReportPlan(spec, freshReportsDir);
    }

    @Override
    public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
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

    private static Path allocateFreshReportsDir() {
        try {
            return Files.createTempDirectory(REPORTS_DIR_PREFIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to allocate a fresh reports directory", e);
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
