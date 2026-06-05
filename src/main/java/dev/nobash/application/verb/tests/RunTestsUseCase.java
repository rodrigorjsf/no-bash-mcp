package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.runcache.RunRecord;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.infra.concurrency.ModuleLock;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code run_tests} use-case (verb slice). It validates the request, runs the programmatic
 * security guards <strong>before any process is launched</strong> (DESIGN.md §9), then
 * orchestrates the full execution tracer. It is <strong>ecosystem-agnostic</strong> (ADR-0011):
 * the use-case owns the INVARIANT spine and delegates everything that VARIES per ecosystem to the
 * injected {@link EcosystemAdapter} (Maven is the sole adapter in this slice).
 *
 * <p>The INVARIANT spine the use-case keeps, single-source for every ecosystem: the pre-exec
 * guards, the per-module lock (ADR-0005/D22), the timeout intercept + process-tree kill (issue #6),
 * the anti-false-green failure floor (report freshness D27, the process-exit floor D28, the
 * positive-evidence {@code executedTests > 0} / {@code NO_TESTS_RUN} floor D29), and the run-cache
 * {@link Handle}/stash (D17). The adapter owns what varies: marker + launcher resolution, the
 * installed-check, the argv + reporter injection, the report source, normalization, and the
 * report-absence decision.</p>
 *
 * <p>Guard order is fixed and fail-closed: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} →
 * {@code TOOL_NOT_INSTALLED} → {@code INVALID_TARGET} → {@code RESOURCE_BUSY}. The adapter's
 * installed-check (which consults the executor seam) is reached ONLY once the earlier guards pass.</p>
 *
 * <p>After exec, the outcome is decided in this fixed precedence (the order is load-bearing — see
 * the container-only case below):</p>
 * <ol>
 *   <li><b>timeout</b> ⇒ stash any partial signal behind a {@link Handle} and return
 *       {@code TIMEOUT} — BEFORE the report-absence check, so an empty-on-timeout run is never
 *       mislabelled {@code REPORT_NOT_PRODUCED} (issue #6).</li>
 *   <li><b>report-absent</b> (e.g. Maven's empty fresh dir) ⇒ stash the adapter-supplied raw
 *       payload behind a {@link Handle} and return the adapter-supplied operational error
 *       (Maven: {@code REPORT_NOT_PRODUCED}, D25/D27).</li>
 *   <li>else normalize; {@code executedTests = passed + failed + errored} (excludes
 *       {@code SKIPPED}, D29). If {@code executedTests == 0} <em>and the run is otherwise green</em>
 *       ({@code run.ok()}) ⇒ {@code NO_TESTS_RUN} (D29). The {@code run.ok()} guard is essential: a
 *       container-only run (a {@code @BeforeAll} throw) also has {@code executedTests == 0} but is
 *       NOT ok, so it must fall through to a test-failure envelope carrying the container finding
 *       (AC8 / the G5 keystone), never {@code NO_TESTS_RUN}.</li>
 *   <li>else {@code ok = run.ok() && exitCode == 0 && !timedOut && executedTests > 0} (the
 *       application-layer floor; the frozen domain {@link NormalizedRun#ok()} stays findings-only).
 *       {@code ok} ⇒ counts-only success; otherwise a test-failure envelope carrying
 *       {@code run.findings()} as {@code failures[]}.</li>
 * </ol>
 */
@Singleton
public class RunTestsUseCase {

    private static final String VERB = "run_tests";

    private final CommandExecutorPort executor;
    private final EcosystemAdapter ecosystem;
    private final TestsFlagPolicy flagPolicy;
    private final RawOutputStash stash;
    private final ModuleLock moduleLock;

    public RunTestsUseCase(CommandExecutorPort executor, EcosystemAdapter ecosystem,
                           TestsFlagPolicy flagPolicy, RawOutputStash stash, ModuleLock moduleLock) {
        this.executor = executor;
        this.ecosystem = ecosystem;
        this.flagPolicy = flagPolicy;
        this.stash = stash;
        this.moduleLock = moduleLock;
    }

    /**
     * Full-suite run with no target selector (backward-compatible overload for existing callers).
     * Delegates to {@link #run(String, List, Integer, String, String)}.
     *
     * @param path    the project directory (optional at the wire; null fails closed)
     * @param flags   agent-supplied flags (untrusted; vetted by the allowlist)
     * @param timeout the agent's requested deadline in seconds; clamped (null/non-positive →
     *                default, never beyond the cap) and enforced by the executor (issue #6)
     * @return the result envelope (success, test-failure, or operational-error)
     */
    public Envelope run(String path, List<String> flags, Integer timeout) {
        return run(path, flags, timeout, null, null);
    }

    /**
     * Run with an optional structured target selector (issue #9, AC1–AC4). The agent supplies
     * the target as two typed values ({@code targetKind} / {@code target}); the MCP validates
     * them into a {@link TestTarget} (a pre-exec guard) and, when present, the adapter injects
     * {@code -Dtest=<value>} into the argv as a controlled value — exactly like the
     * report-directory injection.
     *
     * <p>Guard order: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} → {@code TOOL_NOT_INSTALLED}
     * → {@code INVALID_TARGET} → {@code RESOURCE_BUSY}. The target guard fires BEFORE the lock
     * is acquired, so a malformed target never blocks a concurrent run. The {@code RESOURCE_BUSY}
     * key is {@code realpath(moduleDir)} regardless of the target — different targets on the same
     * module still collide (D22, ADR-0005).</p>
     *
     * @param path        the project directory (optional at the wire; null fails closed)
     * @param flags       agent-supplied flags (untrusted; vetted by the allowlist)
     * @param timeout     the agent's requested deadline in seconds; clamped as before
     * @param targetKind  the agent-supplied kind ({@code CLASS} / {@code METHOD}); null → full suite
     * @param targetValue the agent-supplied test identity value; null → full suite
     * @return the result envelope (success, test-failure, or operational-error)
     */
    public Envelope run(String path, List<String> flags, Integer timeout,
                        @Nullable String targetKind, @Nullable String targetValue) {
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

        // Guard 2 — NO_MANAGER_DETECTED. The adapter owns marker detection (Maven: pom.xml).
        if (!ecosystem.detects(dir)) {
            String marker = ecosystem.markerDescription();
            return Envelope.operationalError(VERB, ErrorCode.NO_MANAGER_DETECTED,
                    "No supported manager was detected at '" + path + "' (looked for: " + marker + ").",
                    "Run run_tests from a directory that contains a " + marker + ".");
        }

        // Guard 3 — TOOL_NOT_INSTALLED. The adapter resolves the trusted system launcher on PATH
        // (ADR-0008); Maven delegates the check to the format-blind executor seam.
        if (!ecosystem.isInstalled()) {
            String manager = ecosystem.managerBinary();
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + manager + "' manager is not installed on PATH.",
                    "Install " + manager + " and ensure it is on the system PATH.");
        }

        // Preflight (DEPS_NOT_INSTALLED) is a Node concern (D21) and lands in slice 2; the Maven
        // adapter has no deps to check before exec (deps resolve on demand from ~/.m2). Omitted here.

        // Guard 4 — INVALID_TARGET. Validate the structured target selector BEFORE acquiring the
        // lock: a malformed target never blocks a concurrent run on the same module. The validation
        // is a pre-exec guard (no process launched on a malformed target, AC4). null/absent pair
        // passes cleanly → full-suite run (the guard is skipped).
        final TestTarget target;
        try {
            target = TestTarget.parse(targetKind, targetValue);
        } catch (TestTarget.MalformedTargetException e) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_TARGET,
                    e.getMessage(),
                    "Provide a valid targetKind (CLASS or METHOD) and target value. "
                            + "For CLASS: 'FooTest'. For METHOD: 'FooTest#testBar'.");
        }

        // Guard 5 — RESOURCE_BUSY. A mutating run holds a per-module lock keyed on the canonical
        // realpath(moduleDir) (alias paths collapse to one key). A second same-module run while the
        // first holds the lock fails fast — never blocks (ADR-0005, D22). A target selector does
        // NOT change the key; different targets on the same module still collide. Acquired AFTER
        // all pre-exec guards so a busy module is reported only for an otherwise-runnable request.
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
            return runLocked(dir, flags, timeout, target);
        } finally {
            try {
                moduleLock.release(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to release the module lock", e);
            }
        }
    }

    /**
     * The locked execution body: ask the adapter to build the plan (with the clamped timeout),
     * launch the trusted manager via the executor seam, then run the INVARIANT post-exec spine
     * over the adapter's interpretation. Runs only while this verb holds the module lock; the
     * caller's {@code finally} releases the lock on every exit path.
     *
     * @param target the validated structured target selector, or {@code null} for a full-suite run
     */
    private Envelope runLocked(Path dir, List<String> flags, Integer timeout,
                                @Nullable TestTarget target) {
        // Argv vetting drops every agent flag outside the allowlist; the adapter then injects the
        // MCP-controlled report-directory flag (and any -Dtest= selector) and allocates the fresh,
        // empty-before-exec report source (report freshness by construction, D27).
        List<String> vetted = flagPolicy.filter(flags == null ? List.of() : flags);
        // Clamp the agent's requested timeout: null/non-positive → default, > cap → cap (the agent
        // may raise it up to but not beyond the cap). The clamped value rides the ExecSpec.
        int timeoutSeconds = TimeoutPolicy.clamp(timeout);
        ReportPlan plan = ecosystem.buildExec(vetted, timeoutSeconds, dir, target);

        // Launch the planned spec through the format-blind executor seam — part of the invariant
        // spine (ADR-0011). The port is shared (build/git use it too) and stays format-blind: the
        // use-case holds the ExecSpec/ExecResult carriers, never a Maven type.
        ExecResult result = executor.execute(plan.spec());

        // Timeout intercept (issue #6) — BEFORE the report-absence check: a timeout killed before
        // any report is written leaves the source empty and would otherwise mislabel as
        // REPORT_NOT_PRODUCED. Surface TIMEOUT uniformly (empty-on-timeout and partial-on-timeout
        // both), retaining any partial signal behind the handle (op-error shape, ADR-0007 —
        // manager/summary/failures null).
        if (result.timedOut()) {
            String partial = (result.stdout() == null ? "" : result.stdout())
                    + (result.stderr() == null ? "" : result.stderr());
            Handle handle = stash.put(new RunRecord(partial, ecosystem.partialFindings(plan)));
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "The run exceeded its timeout and was killed (the process tree was reaped).",
                    "Raise `timeout` (up to the cap) or narrow the test scope; partial output is behind the handle.",
                    handle);
        }

        // Read the report source and interpret it (ecosystem-specific). A report-absent
        // interpretation carries everything the invariant op-error branch needs, so the use-case
        // keeps the stash/handle + envelope assembly with zero ecosystem literals (Maven: empty
        // fresh dir → REPORT_NOT_PRODUCED, D25/D27).
        RunInterpretation interpretation = ecosystem.interpret(result, plan);
        if (interpretation.isReportAbsent()) {
            RunInterpretation.ReportAbsence absence = interpretation.absence();
            Handle handle = stash.stash(absence.stashPayload());
            return Envelope.operationalError(VERB, absence.code(), absence.message(),
                    absence.hint(), handle);
        }

        NormalizedRun run = interpretation.run();
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
        String manager = ecosystem.managerBinary();
        if (ok) {
            return Envelope.success(VERB, manager, run.summary(), handle);
        }
        return Envelope.testFailure(VERB, manager, run.summary(), run.findings(), handle);
    }
}
