package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecResult;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;
import java.util.List;

/**
 * The per-ecosystem strategy seam (ADR-0011). It captures exactly what <em>varies</em> per
 * ecosystem; {@link RunTestsUseCase} owns what is <em>invariant</em> — the pre-exec guards, the
 * per-module lock, the timeout intercept + process-tree kill, the anti-false-green failure floor
 * (D27/D28/D29), and the run-cache {@code handle}/stash. The use-case stays ecosystem-agnostic:
 * it holds zero manager-specific constants or types and delegates every ecosystem-specific
 * decision through this interface.
 *
 * <p><b>Scope (PRD-3, slice 1).</b> Maven is the SOLE implementation in this slice — a pure,
 * behaviour-preserving extraction. There is no {@code AMBIGUOUS_SCOPE} branch, no multi-ecosystem
 * dispatch, and no Node/Go adapter yet; those are downstream slices. With a single adapter the
 * use-case need not branch on {@link #detects(Path)}.</p>
 *
 * <p>The seam lives in the application layer (beside the use-case) — NOT the domain — because
 * {@link #buildExec} references {@link TestTarget}, which is an application type; placing it in
 * {@code domain} would violate the {@code domain → application} purity rule (ArchUnit).</p>
 */
public interface EcosystemAdapter {

    /**
     * Whether this ecosystem's marker (and, in later slices, its test signal) is present at the
     * project directory — the basis for the {@code NO_MANAGER_DETECTED} guard. Maven returns
     * {@code true} iff a {@code pom.xml} regular file exists at {@code dir}.
     *
     * @param dir the project directory (already validated as an existing directory)
     * @return {@code true} iff this ecosystem is detected at {@code dir}
     */
    boolean detects(Path dir);

    /**
     * The trusted, PATH-resolved manager binary name (ADR-0008) — {@code mvn} for Maven. Used in
     * the success/test-failure envelope's {@code manager} field and in the
     * {@code TOOL_NOT_INSTALLED} message.
     *
     * @return the manager binary name
     */
    String managerBinary();

    /**
     * A human-readable description of this ecosystem's detection marker (e.g. {@code pom.xml}),
     * used in the {@code NO_MANAGER_DETECTED} message and hint.
     *
     * @return the marker description
     */
    String markerDescription();

    /**
     * Whether the trusted system manager resolves on PATH — the basis for the
     * {@code TOOL_NOT_INSTALLED} guard. Maven delegates to the format-blind
     * {@link dev.nobash.domain.port.out.CommandExecutorPort#isManagerInstalled()} seam (it resolves
     * the trusted system {@code mvn} on PATH, never a repo wrapper, ADR-0008).
     *
     * @return {@code true} iff the manager is installed on PATH
     */
    boolean isInstalled();

    /**
     * Build the execution plan for a run: allocate a fresh, empty-before-exec report target (report
     * freshness by construction, D27), assemble the {@link dev.nobash.domain.port.out.ExecSpec}
     * (argv + working dir + clamped timeout) with any MCP-controlled flag injection, and carry the
     * report source so {@link #interpret} and {@link #partialFindings} can read it back.
     *
     * @param vettedFlags    flags already filtered through the allowlist (never raw agent input)
     * @param timeoutSeconds the already-clamped hard deadline the executor enforces (issue #6)
     * @param workingDir     the module directory the manager runs in
     * @param target         the validated structured target selector, or {@code null} for full suite
     * @return the report plan (the spec to execute + the report source to read back)
     */
    ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                         @Nullable TestTarget target);

    /**
     * Read this ecosystem's report source and fold it into a normalized result — OR signal a
     * report-absence operational error (e.g. Maven's empty fresh dir → {@code REPORT_NOT_PRODUCED},
     * D25/D27). {@code interpret} returns a VALUE only; the use-case keeps the stash/handle and the
     * operational-error envelope assembly so the run-cache stays invariant.
     *
     * @param result the raw, report-agnostic execution result (used for the stash payload on absence)
     * @param plan   the report plan returned by {@link #buildExec} (carries the report source)
     * @return the normalized run, or a report-absence signal
     */
    RunInterpretation interpret(ExecResult result, ReportPlan plan);

    /**
     * Best-effort partial findings on a timeout: the ecosystem-specific read + normalize the
     * timeout intercept needs (it runs BEFORE the report-absence check). Maven re-reads the fresh
     * dir; an empty dir yields an empty list.
     *
     * @param plan the report plan returned by {@link #buildExec}
     * @return the partial findings (possibly empty, never null)
     */
    List<dev.nobash.domain.result.Finding> partialFindings(ReportPlan plan);
}
