package dev.nobash.domain.error;

/**
 * The enumerated operational-error codes this slice can return (CONTEXT.md "Operational
 * error"). The full catalog ({@code TIMEOUT}, {@code RESOURCE_BUSY},
 * {@code DEPS_NOT_INSTALLED}, …) arrives with the slices that earn those codes.
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
    NO_TESTS_RUN
}
