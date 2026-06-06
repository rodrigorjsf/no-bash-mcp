package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;
import io.micronaut.core.annotation.Nullable;

import java.nio.file.Path;

/**
 * The execution plan an {@link EcosystemAdapter} hands back from {@link EcosystemAdapter#buildExec}
 * (ADR-0011). It pairs the {@link ExecSpec} the use-case feeds to the executor with the
 * ecosystem-specific report source the same adapter reads back in
 * {@link EcosystemAdapter#interpret} / {@link EcosystemAdapter#partialFindings}.
 *
 * <p>For Maven the {@code reportSource} is the fresh, empty-before-exec Surefire reports directory
 * (report freshness by construction, D27); the use-case never inspects it — only the adapter reads
 * it. The use-case treats the plan as opaque except for {@link #spec()}, which it executes.</p>
 *
 * <p><b>Preflight short-circuit (PRD-3, slice 4).</b> The {@link EcosystemAdapter} seam has no
 * dedicated preflight or target-rejection hook, yet {@link RunTestsUseCase} ALWAYS runs
 * {@code buildExec → executor.execute(spec) → interpret} unconditionally. When an adapter detects a
 * pre-exec condition it must surface as an operational error WITHOUT actually launching the test
 * framework — the Node adapter's {@code DEPS_NOT_INSTALLED} / {@code UNSUPPORTED_TEST_FRAMEWORK} /
 * {@code UNSUPPORTED_TARGET} cases — it builds an INERT no-op {@link ExecSpec} (e.g.
 * {@code npx --version}) and stashes the precondition decision here as a non-null {@code preflight}
 * {@link RunInterpretation}. {@link EcosystemAdapter#interpret} short-circuits on it (returns it
 * verbatim, after the inert exec has run), so the use-case's existing
 * {@link RunInterpretation#isReportAbsent()} → {@code operationalError} channel fires with zero
 * use-case edits and zero new interface methods. Maven and Go always pass {@code null} (the 2-arg
 * constructor), so their behaviour and the {@link RunTestsUseCase} spine stay byte-identical.</p>
 *
 * @param spec         the execution spec the use-case hands to the executor seam
 * @param reportSource the ecosystem-specific report source the adapter reads back (e.g. the fresh
 *                     Surefire reports dir for Maven)
 * @param preflight    a pre-exec decision (a {@link RunInterpretation}, typically a report-absence)
 *                     the adapter's {@code interpret} must return verbatim instead of reading the
 *                     report; {@code null} for the normal "run and read the report" path
 */
public record ReportPlan(ExecSpec spec, Path reportSource, @Nullable RunInterpretation preflight) {

    /**
     * The normal plan: run the spec and read the report source back. Back-compatible with every
     * existing adapter (Maven, Go) and test fake — they never carry a preflight decision.
     *
     * @param spec         the execution spec the use-case executes
     * @param reportSource the report source the adapter reads back
     */
    public ReportPlan(ExecSpec spec, Path reportSource) {
        this(spec, reportSource, null);
    }
}
