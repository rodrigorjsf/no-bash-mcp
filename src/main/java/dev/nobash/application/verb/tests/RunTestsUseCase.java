package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.runcache.RunRecord;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.domain.result.SurefireNormalizer;
import dev.nobash.infra.concurrency.ModuleLock;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * The {@code run_tests} use-case (verb slice). It validates the request, runs the programmatic
 * security guards <strong>before any process is launched</strong> (DESIGN.md §9), then
 * orchestrates the full execution tracer: resolve the module, allocate a fresh per-run reports
 * directory, inject it into the {@link ExecSpec} (report freshness by construction, D27), launch
 * the trusted system {@code mvn} via the {@link CommandExecutorPort} seam, read that fresh dir,
 * normalize the Surefire reports, and assemble the result {@link Envelope} with the
 * positive-evidence failure floor (D28/D29).
 *
 * <p>Guard order is fixed and fail-closed: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} →
 * {@code TOOL_NOT_INSTALLED}. The executor seam is consulted ONLY once those guards pass.</p>
 *
 * <p>After exec, the outcome is decided in this fixed precedence (the order is load-bearing — see
 * the container-only case below):</p>
 * <ol>
 *   <li><b>empty fresh dir</b> ⇒ compile failure (no report produced): stash the compiler stderr
 *       behind a {@link Handle} and return {@code REPORT_NOT_PRODUCED} (D25/D27).</li>
 *   <li>else normalize every {@code *.xml}; {@code executedTests = passed + failed + errored}
 *       (excludes {@code SKIPPED}, D29). If {@code executedTests == 0} <em>and the run is otherwise
 *       green</em> ({@code run.ok()}) ⇒ {@code NO_TESTS_RUN} (D29). The {@code run.ok()} guard is
 *       essential: a container-only run (a {@code @BeforeAll} throw) also has
 *       {@code executedTests == 0} but is NOT ok, so it must fall through to a test-failure
 *       envelope carrying the container finding (AC8 / the G5 keystone), never {@code NO_TESTS_RUN}.</li>
 *   <li>else {@code ok = run.ok() && exitCode == 0 && !timedOut && executedTests > 0} (the
 *       application-layer floor; the frozen domain {@link NormalizedRun#ok()} stays findings-only).
 *       {@code ok} ⇒ counts-only success; otherwise a test-failure envelope carrying
 *       {@code run.findings()} as {@code failures[]}.</li>
 * </ol>
 */
@Singleton
public class RunTestsUseCase {

    private static final String VERB = "run_tests";
    private static final String MANAGER = "mvn";
    private static final String MANAGER_MARKER = "pom.xml";
    private static final String REPORTS_DIR_PREFIX = "no-bash-mcp-surefire-";

    private final CommandExecutorPort executor;
    private final ArgvBuilder argvBuilder;
    private final TestsFlagPolicy flagPolicy;
    private final RawOutputStash stash;
    private final ModuleLock moduleLock;
    private final SurefireNormalizer normalizer = new SurefireNormalizer();

    public RunTestsUseCase(CommandExecutorPort executor, ArgvBuilder argvBuilder,
                           TestsFlagPolicy flagPolicy, RawOutputStash stash, ModuleLock moduleLock) {
        this.executor = executor;
        this.argvBuilder = argvBuilder;
        this.flagPolicy = flagPolicy;
        this.stash = stash;
        this.moduleLock = moduleLock;
    }

    /**
     * @param path    the project directory (optional at the wire; null fails closed)
     * @param flags   agent-supplied flags (untrusted; vetted by the allowlist)
     * @param timeout the agent's requested deadline in seconds; clamped (null/non-positive →
     *                default, never beyond the cap) and enforced by the executor (issue #6)
     * @return the result envelope (success, test-failure, or operational-error)
     */
    public Envelope run(String path, List<String> flags, Integer timeout) {
        // Guard 1 — INVALID_PATH (null / missing / not-a-directory). No confinement claim.
        if (path == null || path.isBlank()) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "No path was provided.",
                    "Pass the path to an existing project directory.");
        }
        final Path dir;
        try {
            dir = Path.of(path);
        } catch (InvalidPathException e) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' is not a valid path.",
                    "Pass the path to an existing project directory.");
        }
        if (!Files.isDirectory(dir)) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' does not exist or is not a directory.",
                    "Pass the path to an existing project directory.");
        }

        // Guard 2 — NO_MANAGER_DETECTED. List what was looked for (Maven-only this slice).
        if (!Files.isRegularFile(dir.resolve(MANAGER_MARKER))) {
            return Envelope.operationalError(VERB, ErrorCode.NO_MANAGER_DETECTED,
                    "No supported manager was detected at '" + path + "' (looked for: " + MANAGER_MARKER + ").",
                    "Run run_tests from a directory that contains a " + MANAGER_MARKER + ".");
        }

        // Guard 3 — TOOL_NOT_INSTALLED. Trusted system mvn on PATH only (ADR-0008).
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' manager is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // Preflight (DEPS_NOT_INSTALLED) is a Maven no-op (D21): deps resolve on demand from
        // ~/.m2 during the build, so there is nothing to check before exec. Pass through.

        // Guard 4 — RESOURCE_BUSY. A mutating run holds a per-module lock keyed on the canonical
        // realpath(moduleDir) (alias paths collapse to one key). A second same-module run while the
        // first holds the lock fails fast — never blocks (ADR-0005, D22). A target selector does
        // NOT change the key; different modules proceed concurrently. Acquired AFTER the three
        // guards so a busy module is reported only for an otherwise-runnable request.
        boolean acquired;
        try {
            acquired = moduleLock.tryAcquire(dir);
        } catch (IOException e) {
            // The directory guard already passed; a realpath failure here is genuinely exceptional.
            throw new UncheckedIOException("Failed to resolve the module's real path for locking", e);
        }
        if (!acquired) {
            return Envelope.operationalError(VERB, ErrorCode.RESOURCE_BUSY,
                    "Another " + VERB + " is already running on this module.",
                    "Wait for the in-flight run to finish, then retry — concurrent same-module runs fail fast.");
        }

        // The lock is released on EVERY exit path (success, test-failure, TIMEOUT, REPORT_NOT_PRODUCED,
        // NO_TESTS_RUN, and any thrown exception) by the finally below.
        try {
            return runLocked(dir, flags, timeout);
        } finally {
            try {
                moduleLock.release(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to release the module lock", e);
            }
        }
    }

    /**
     * The locked execution body: build the spec (with the clamped timeout), launch the trusted
     * manager, and assemble the result envelope. Runs only while this verb holds the module lock;
     * the caller's {@code finally} releases the lock on every exit path.
     */
    private Envelope runLocked(Path dir, List<String> flags, Integer timeout) {
        // Allocate a unique, empty-before-exec reports directory so any XML in it is necessarily
        // from THIS run (report freshness by construction, D27). Argv vetting drops every agent
        // flag outside the allowlist; the reportsDirectory flag is then injected by the MCP.
        Path freshReportsDir = allocateFreshReportsDir();
        List<String> vetted = flagPolicy.filter(flags == null ? List.of() : flags);
        // Clamp the agent's requested timeout: null/non-positive → default, > cap → cap (the agent
        // may raise it up to but not beyond the cap). The clamped value rides the ExecSpec.
        int timeoutSeconds = TimeoutPolicy.clamp(timeout);
        ExecSpec spec = argvBuilder.buildTestArgv(vetted, freshReportsDir.toString(), dir.toString(),
                timeoutSeconds);

        ExecResult result = executor.execute(spec);

        // Timeout intercept (issue #6) — BEFORE the empty-dir check: a timeout killed before any
        // report is written leaves the dir empty and would otherwise mislabel as REPORT_NOT_PRODUCED.
        // Surface TIMEOUT uniformly (empty-on-timeout and partial-on-timeout both), retaining any
        // partial signal behind the handle (op-error shape, ADR-0007 — manager/summary/failures null).
        if (result.timedOut()) {
            String partial = (result.stdout() == null ? "" : result.stdout())
                    + (result.stderr() == null ? "" : result.stderr());
            Handle handle = stash.put(new RunRecord(partial, partialFindings(freshReportsDir)));
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "The run exceeded its timeout and was killed (the process tree was reaped).",
                    "Raise `timeout` (up to the cap) or narrow the test scope; partial output is behind the handle.",
                    handle);
        }

        // Empty fresh dir after exec ⇒ no Surefire report ⇒ compile failure (D25/D27).
        List<String> reportXmls = readReportXmls(freshReportsDir);
        if (reportXmls.isEmpty()) {
            Handle handle = stash.stash(result.stderr());
            return Envelope.operationalError(VERB, ErrorCode.REPORT_NOT_PRODUCED,
                    "The build produced no test report (a compile failure is the usual cause).",
                    "Run `build` to see the compiler errors (retained behind the handle).",
                    handle);
        }

        NormalizedRun run = normalizer.normalizeAll(reportXmls);
        int executedTests = run.summary().passed() + run.summary().failed() + run.summary().errored();

        // Positive-evidence floor (D29): a fresh-but-empty run greens vacuously. Only when the run
        // is OTHERWISE green (run.ok()) is a zero-executed run NO_TESTS_RUN — a container-only run
        // is executedTests==0 yet NOT ok, so it falls through to a test-failure envelope (AC8).
        if (executedTests == 0 && run.ok()) {
            return Envelope.operationalError(VERB, ErrorCode.NO_TESTS_RUN,
                    "A test report was produced but no test executed (0 tests ran).",
                    "Check the test selection, an all-@Disabled suite, or an empty module.");
        }

        // The application-layer failure floor (D28/D29). The frozen NormalizedRun.ok() stays
        // findings-only; exit/timedOut/executedTests are folded in ONLY here.
        boolean ok = run.ok() && result.exitCode() == 0 && !result.timedOut() && executedTests > 0;
        // Stash the full run record (raw output + findings) so get_log can drill in without re-run.
        String rawOutput = (result.stdout() == null ? "" : result.stdout())
                + (result.stderr() == null ? "" : result.stderr());
        Handle handle = stash.put(new RunRecord(rawOutput, run.findings()));
        if (ok) {
            return Envelope.success(VERB, MANAGER, run.summary(), handle);
        }
        return Envelope.testFailure(VERB, MANAGER, run.summary(), run.findings(), handle);
    }

    /**
     * Best-effort partial findings on a timeout: a Surefire run that wrote some report XML before
     * the kill leaves fresh PASSED/FAILED rows in the dir. Normalizing them lets {@code get_log}
     * drill into the partial signal. A timeout that wrote nothing yields an empty list.
     */
    private List<dev.nobash.domain.result.Finding> partialFindings(Path freshReportsDir) {
        List<String> reportXmls = readReportXmls(freshReportsDir);
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
            for (Path p : entries.filter(RunTestsUseCase::isXml).sorted().toList()) {
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
