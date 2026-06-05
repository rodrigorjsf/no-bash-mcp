package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;

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
 * @param spec         the execution spec the use-case hands to the executor seam
 * @param reportSource the ecosystem-specific report source the adapter reads back (e.g. the fresh
 *                     Surefire reports dir for Maven)
 */
public record ReportPlan(ExecSpec spec, Path reportSource) {
}
