package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The granularity of a failure with no single test owner (ADR-0007, axis 5). A CLOSED
 * enum for v1 — by design, not oversight: unlike the sealed {@link Finding} (which carries
 * forward-compat for finding KINDS), adding a scope is an intentional breaking, ADR-gated
 * change. No captured report needs a fifth scope.
 *
 * <ul>
 *   <li>{@code SUITE} — a per-suite setup failure (e.g. a JUnit {@code @BeforeAll} throw).</li>
 *   <li>{@code FILE} — a module/file-load failure (e.g. a jest module-load error).</li>
 *   <li>{@code PACKAGE} — a package-level failure with no failing test under it (Go).</li>
 *   <li>{@code RUN} — a run-level failure (e.g. a failed forge CI check).</li>
 * </ul>
 */
@Serdeable
@Introspected
public enum ContainerScope {
    SUITE, FILE, PACKAGE, RUN
}
