package dev.nobash.domain.port.out;

import java.util.List;

/**
 * The carrier of a command invocation across the outbound execution port. {@code argv} is
 * an explicit, ordered token array — the security keystone (security-model.md, ADR-0008):
 * the manager binary is {@code argv[0]} and every other token is a literal element, NEVER a
 * shell string and NEVER passed to {@code /bin/sh -c}. {@code workingDir} is the resolved
 * path the manager runs in.
 *
 * <p>{@code timeoutSeconds} is the hard deadline the executor enforces (issue #6): on expiry
 * the executor kills the whole process tree and returns a {@code timedOut=true}
 * {@link ExecResult}. It is the already-clamped value (default → cap, never beyond the cap);
 * clamping is the verb use-case's job, never this carrier's.</p>
 *
 * @param argv           the explicit argv token array ({@code argv[0]} is the trusted manager)
 * @param workingDir     the resolved directory the manager runs in (null when unset)
 * @param timeoutSeconds the enforced hard deadline in seconds (already clamped to the cap)
 */
public record ExecSpec(List<String> argv, String workingDir, int timeoutSeconds) {

    public ExecSpec {
        argv = List.copyOf(argv);
    }

    /**
     * Backward-compatible convenience constructor for callers (and unit specs) that do not
     * carry an explicit deadline. Defaults {@code timeoutSeconds} to {@link #DEFAULT_TIMEOUT_SECONDS}.
     */
    public ExecSpec(List<String> argv, String workingDir) {
        this(argv, workingDir, DEFAULT_TIMEOUT_SECONDS);
    }

    /** A sane per-run default for specs built without an explicit deadline. */
    public static final int DEFAULT_TIMEOUT_SECONDS = 600;
}
