package dev.nobash.domain.error;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * An operational error (CONTEXT.md): an enumerated {@code code} plus a human-readable
 * {@code message} and an actionable {@code hint}. Pure domain, reflection-free
 * ({@code @Serdeable @Introspected}) so it serializes to the agent over STDIO without
 * runtime reflection (DESIGN.md §7).
 */
@Serdeable
@Introspected
public record OperationalError(ErrorCode code, String message, String hint) {
}
