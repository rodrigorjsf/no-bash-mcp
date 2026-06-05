package dev.nobash.application.verb.git;

/**
 * Clamps the agent-supplied {@code timeout} for the read-only git verbs (operational-model.md
 * "Timeout"). Mirrors {@code BuildTimeoutPolicy} / {@code TimeoutPolicy}. git status is a fast,
 * local operation, so the default and ceiling are tighter than the build/test verbs.
 */
final class GitTimeoutPolicy {

    /** The sane per-verb default applied when the agent supplies no timeout. */
    static final int DEFAULT_SECONDS = 30;

    /** The hard ceiling — the agent may raise the timeout up to, but never beyond, this. */
    static final int MAX_SECONDS = 120;

    private GitTimeoutPolicy() {
    }

    /**
     * @param requested the agent-supplied timeout in seconds; {@code null} or non-positive means
     *                  "unspecified" → the default applies
     * @return the clamped deadline: default when unspecified, else {@code min(requested, cap)}
     */
    static int clamp(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_SECONDS;
        }
        return Math.min(requested, MAX_SECONDS);
    }
}
