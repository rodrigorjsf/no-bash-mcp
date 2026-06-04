package s1;

import java.util.List;

/**
 * The universal normalized test-result schema — COPIED VERBATIM from prototype/
 * (the artifact under test). The spike feeds it UNSEEN real reports to falsify it.
 *
 * Honors the five "safe to assert now" invariants:
 *  1. Identity is a flexible path (suite + name + path[]), never a fixed classname.
 *  2. file:line + diff are best-effort, derived, nullable — never guaranteed.
 *  3. A failure with no single test owner is FIRST-CLASS (ContainerFinding).
 *  4. Outcome is a normalized enum AND the raw status is retained.
 *  5. Expected-vs-actual is not reliably structurable -> message + best-effort only.
 */
final class Schema {
    private Schema() {}
}

/** Normalized outcome (axis 2). The raw status is retained per finding. */
enum Outcome { PASSED, FAILED, ERRORED, SKIPPED }

/**
 * Best-effort, derived, nullable source location (axis 3). {@code line} is BOXED —
 * serde 3.0 needs boxed nullables, not primitives.
 */
record SourceRef(String file, Integer line) {}

/**
 * A normalized finding. SEALED so the no-test-owner case (axis 5) is a first-class
 * type, never a degenerate test with empty fields.
 */
sealed interface Finding permits TestFinding, ContainerFinding {
    Outcome outcome();
    String rawStatus();
    String message();
    SourceRef source();
    String detail();
}

/** A failure/error owned by a single test (axes 1, 7). Identity is a FLEXIBLE PATH. */
record TestFinding(
        String suite,
        String name,
        List<String> path,
        Outcome outcome,
        String rawStatus,
        String message,
        SourceRef source,
        String detail
) implements Finding {}

/** Granularity of a failure with no single test owner (axis 5). */
enum ContainerScope { SUITE, FILE, PACKAGE, RUN }

/** A failure NOT attributable to any single test (axis 5). Carries NO test name. */
record ContainerFinding(
        ContainerScope scope,
        String container,
        Outcome outcome,
        String rawStatus,
        String message,
        SourceRef source,
        String detail
) implements Finding {}

/** TEST-level counts only. A ContainerFinding is NOT a test — it lives in findings. */
record Summary(int total, int passed, int failed, int errored, int skipped) {}

/** The unified result every ecosystem folds into. */
record NormalizedRun(String tool, Summary summary, List<Finding> findings) {
    /** Container-aware success — derived from FINDINGS, never from test counts.
     *  A run whose only failure is a no-test-owner ContainerFinding is still NOT ok
     *  (the G5 false-green trap the whole project exists to avoid). */
    boolean ok() {
        return findings.stream().noneMatch(f -> f.outcome() == Outcome.FAILED || f.outcome() == Outcome.ERRORED);
    }
}
