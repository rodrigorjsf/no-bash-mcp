package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * The normalized test outcome (ADR-0007, axis 2). Frozen, closed enum. The raw,
 * format-specific status is retained per finding ({@code Finding.rawStatus()}) so
 * normalization is never lossy.
 *
 * <p>{@code FAILED} = the test/suite ran and an assertion failed. {@code ERRORED} = it
 * could not run or terminated abnormally (a compile/build failure, an {@code init()}
 * panic, or a setup {@code @BeforeAll} error). Both drive {@code ok()==false}; the
 * discriminator is for agent triage, not for the gate (ADR-0007 rule 2).</p>
 */
@Serdeable
@Introspected
public enum Outcome {
    PASSED, FAILED, ERRORED, SKIPPED
}
