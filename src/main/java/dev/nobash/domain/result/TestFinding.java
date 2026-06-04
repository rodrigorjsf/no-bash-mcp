package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * A failure or error owned by a single test (ADR-0007, axes 1 &amp; 7). Identity is a
 * FLEXIBLE PATH ({@code suite} + {@code name} + {@code path[]}), never a fixed classname:
 * the parametrized index folds into {@code name} ({@code isEven(int)[3]}) and the nested
 * class into {@code suite} ({@code Outer$Inner}); {@code path} may be empty (ADR-0007 rule 5).
 *
 * @param suite     the owning suite identity (Surefire {@code <testcase classname=>})
 * @param name      the test identity, parametrized index included
 * @param path      the flexible identity path; may be empty, never null
 * @param outcome   the normalized outcome
 * @param rawStatus the retained raw status (e.g. {@code "failure"}, {@code "error"})
 * @param message   a best-effort message; null when none
 * @param source    a best-effort source location; null when unparseable
 * @param detail    the full retained detail (stack trace); null when none
 */
@Serdeable
@Introspected
public record TestFinding(String suite,
                          String name,
                          List<String> path,
                          Outcome outcome,
                          String rawStatus,
                          @Nullable String message,
                          @Nullable SourceRef source,
                          @Nullable String detail) implements Finding {
}
