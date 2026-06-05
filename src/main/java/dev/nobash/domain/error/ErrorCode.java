package dev.nobash.domain.error;

/**
 * The enumerated operational-error codes this slice can return (CONTEXT.md "Operational
 * error"). The remaining catalog ({@code DEPS_NOT_INSTALLED}, {@code AMBIGUOUS_SCOPE}, …)
 * arrives with the slices that earn those codes.
 *
 * <p>An operational error is a failure of the <em>operation itself</em> — distinct from a
 * test failure — so the agent branches deterministically on {@code code}.</p>
 */
public enum ErrorCode {

    /** The path is missing or is not a directory. Makes NO workspace-confinement claim. */
    INVALID_PATH,

    /** No manager marker ({@code pom.xml}) was found at the path. */
    NO_MANAGER_DETECTED,

    /** The trusted system manager ({@code mvn}) is not installed on {@code PATH}. */
    TOOL_NOT_INSTALLED,

    /**
     * The build ran but produced NO Surefire report — a compile failure (D25, D27). The fresh
     * reports directory is empty after exec, so there is nothing to fold; the raw compiler
     * output is retained behind the {@code handle} and the hint points at the {@code build}
     * verb. NOT a container-style finding — there is no report to normalize.
     */
    REPORT_NOT_PRODUCED,

    /**
     * A fresh report exists but {@code executedTests == 0} — no test actually ran (an empty
     * leaf module, an all-{@code @Disabled} suite, or no test matched) and exit was 0 (D29).
     * The positive-evidence floor: distinct from {@link #REPORT_NOT_PRODUCED} (no report at
     * all) and from a real failure; closes the vacuous-green class.
     */
    NO_TESTS_RUN,

    /**
     * The run exceeded its {@code timeout} and the executor killed the whole process tree
     * (operational-model.md "Timeout", issue #6). The MCP never hangs; any partial output that
     * drained before the kill is retained behind the {@code handle}. Distinct from a test
     * failure — the operation itself was cut short, so {@code ok=false} regardless of partials.
     */
    TIMEOUT,

    /**
     * A second mutating verb targeted the SAME module while the first still held the per-module
     * lock (ADR-0005, D22). Fail-fast, never block: the agent is almost always double-issuing,
     * so the error surfaces the duplicate instead of silently serializing it. The {@code hint}
     * says the agent may retry once the first run frees the module.
     */
    RESOURCE_BUSY,

    /**
     * The structured target selector ({@code targetKind}/{@code target}) is malformed: the kind
     * is unknown, the value is blank, or the kind-specific format is violated (e.g. a METHOD
     * target missing the required {@code ClassName#methodName} separator). Type validation is a
     * pre-exec guard — NO process is launched for an invalid target (issue #9, AC4).
     */
    INVALID_TARGET
}
