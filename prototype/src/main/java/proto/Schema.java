package proto;

import java.util.List;

/**
 * The universal normalized test-result schema — the project's riskiest bet
 * (schema-divergence-map.md). Validated by folding three REAL, dissimilar
 * reports (Surefire JUnit-XML, jest --json, go test -json) into this ONE graph.
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
 * Best-effort, derived, nullable source location (axis 3). Parsed from a
 * stacktrace/output in TWO of three formats and sometimes absent in the third,
 * so both fields are nullable and the whole ref is nullable on a Finding.
 * {@code line} is BOXED — serde 3.0 needs boxed nullables, not primitives.
 */
record SourceRef(String file, Integer line) {}

/**
 * A normalized finding. SEALED so the no-test-owner case (axis 5) is a
 * first-class type, never a degenerate test with empty fields. In production
 * this maps to serde {@code @JsonTypeInfo(use=NAME, property="kind",
 * defaultImpl=...)} for forward-compatible polymorphic JSON the agent can branch
 * on by shape rather than by null-checking a name field.
 */
sealed interface Finding permits TestFinding, ContainerFinding {
    Outcome outcome();
    String rawStatus();   // retained raw status (axis 2): "failure" "error" "fail" "failed" ...
    String message();     // best-effort human message (axis 4) — nullable
    SourceRef source();   // best-effort file:line (axis 3) — nullable
    String detail();      // raw slice (stacktrace/output) for get_log drill-down — noise, capped in prod
}

/**
 * A failure/error owned by a single test (axes 1, 7). Identity is a FLEXIBLE
 * PATH (suite + name + path[]), never a fixed classname.
 */
record TestFinding(
        String suite,         // top container: classname / file / package
        String name,          // leaf test name
        List<String> path,    // intermediate nesting: ancestorTitles / subtests / param index (may be empty)
        Outcome outcome,
        String rawStatus,
        String message,
        SourceRef source,
        String detail
) implements Finding {}

/** Granularity of a failure with no single test owner (axis 5). */
enum ContainerScope { SUITE, FILE, PACKAGE, RUN }

/**
 * A failure NOT attributable to any single test (axis 5): suite setup
 * (@BeforeAll throws), file setup (beforeAll throws), or package init/build
 * (init panic / build failure). First-class: it carries NO test name.
 */
record ContainerFinding(
        ContainerScope scope,
        String container,     // the suite / file / package identifier
        Outcome outcome,
        String rawStatus,
        String message,
        SourceRef source,
        String detail
) implements Finding {}

/** TEST-level counts only. A no-test-owner ContainerFinding is NOT a test, so it
 *  is deliberately absent from these counts and lives in {@link NormalizedRun#findings}. */
record Summary(int total, int passed, int failed, int errored, int skipped) {}

/** The unified result every ecosystem folds into. {@code tool} retains provenance. */
record NormalizedRun(String tool, Summary summary, List<Finding> findings) {
    /** Container-aware success. Derived from FINDINGS, never from test counts:
     *  a run whose only failure is a no-test-owner ContainerFinding (a Go init
     *  panic, a jest module-load failure, a JUnit @BeforeAll error) is still NOT
     *  ok. Deriving from counts would false-green it — the G5 lossy-summary trap
     *  the whole project exists to avoid. */
    boolean ok() {
        return findings.stream().noneMatch(f -> f.outcome() == Outcome.FAILED || f.outcome() == Outcome.ERRORED);
    }
}
