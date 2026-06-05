package dev.nobash.application.verb.build;

/**
 * Clamps the agent-supplied {@code timeout} for {@code build} (operational-model.md "Timeout").
 * Mirrors the pattern of {@link dev.nobash.application.verb.tests.TimeoutPolicy} for consistency.
 */
final class BuildTimeoutPolicy {

    /** The sane per-verb default applied when the agent supplies no timeout. */
    static final int DEFAULT_SECONDS = 300;

    /** The hard ceiling — the agent may raise the timeout up to, but never beyond, this. */
    static final int MAX_SECONDS = 900;

    private BuildTimeoutPolicy() {
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
