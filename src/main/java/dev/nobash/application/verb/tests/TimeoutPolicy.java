package dev.nobash.application.verb.tests;

/**
 * Clamps the agent-supplied {@code timeout} for {@code run_tests} (operational-model.md
 * "Timeout"). The agent may raise the deadline up to a <strong>max cap</strong> but never beyond
 * it — the cap is a non-sensitive tuning knob, so the agent cannot request an effectively
 * infinite run. A {@code null} (unspecified) timeout falls back to the per-verb default; a
 * non-positive value is treated as unspecified.
 *
 * <p>Plain constants (KISS — there is no {@code @ConfigurationProperties} precedent in the
 * codebase yet). The clamp is pure and side-effect-free so it unit-tests directly.</p>
 */
final class TimeoutPolicy {

    /** The sane per-verb default applied when the agent supplies no timeout. */
    static final int DEFAULT_SECONDS = 600;

    /** The hard ceiling — the agent may raise the timeout up to, but never beyond, this. */
    static final int MAX_SECONDS = 1800;

    private TimeoutPolicy() {
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
