package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

/**
 * A best-effort, derived, nullable source location (ADR-0007, axis 3). Both members are
 * optional: a report may carry no parseable {@code file:line}. {@code line} is a BOXED
 * {@link Integer} (never primitive {@code int}) so it can be {@code null} and serde 3.0
 * emits {@code null} rather than a misleading {@code 0} default.
 *
 * @param file the source file the failure was attributed to; null when unknown
 * @param line the 1-based line within {@code file}; null when unknown (BOXED, nullable)
 */
@Serdeable
@Introspected
public record SourceRef(@Nullable String file, @Nullable Integer line) {
}
