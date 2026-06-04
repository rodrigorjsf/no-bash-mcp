package dev.nobash.domain.error;

/**
 * The enumerated operational-error codes this slice can return (CONTEXT.md "Operational
 * error"). Deliberately limited to the three the thin STDIO tracer reaches at the
 * pre-execution gate; the full catalog ({@code TIMEOUT}, {@code RESOURCE_BUSY},
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
    TOOL_NOT_INSTALLED
}
