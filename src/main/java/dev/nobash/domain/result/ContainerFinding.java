package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A failure NOT attributable to any single test (ADR-0007, axis 5). Carries NO test name —
 * a setup failure with no single test owner (a JUnit {@code @BeforeAll} throw, a Go
 * {@code init()} panic, a jest module-load failure, or a failed forge CI check) is a
 * first-class finding, never a degenerate empty-named {@link TestFinding} (ADR-0007 rule 1).
 *
 * <p>This is the keystone of the container-aware {@code ok()}: a run whose only failure is a
 * {@code ContainerFinding} has clean TEST counts yet is NOT ok — the G5 false-green trap the
 * whole project exists to avoid.</p>
 *
 * @param scope     the granularity of the no-owner failure
 * @param container the container identity (e.g. the suite classname)
 * @param outcome   the normalized outcome
 * @param rawStatus the retained raw status (e.g. {@code "error"})
 * @param message   a best-effort message; null when none
 * @param source    a best-effort source location; null when unparseable
 * @param detail    the full retained detail (stack trace); null when none
 */
@Serdeable
@Introspected
public record ContainerFinding(ContainerScope scope,
                               String container,
                               Outcome outcome,
                               String rawStatus,
                               @Nullable String message,
                               @Nullable SourceRef source,
                               @Nullable String detail) implements Finding {
}
