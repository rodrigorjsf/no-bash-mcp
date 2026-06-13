package dev.nobash.application.verb.install;

/**
 * Clamps the agent-supplied {@code timeout} for {@code install} (operational-model.md "Timeout").
 * Mirrors the pattern of {@link dev.nobash.application.verb.build.BuildTimeoutPolicy} for
 * consistency. npm install can be network-bound so the default is generous.
 */
final class InstallTimeoutPolicy {

    /** The sane per-verb default applied when the agent supplies no timeout. */
    static final int DEFAULT_SECONDS = 300;

    /** The hard ceiling — the agent may raise the timeout up to, but never beyond, this. */
    static final int MAX_SECONDS = 900;

    private InstallTimeoutPolicy() {
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
