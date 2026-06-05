package dev.nobash.application.verb.tests;

import io.micronaut.core.annotation.Nullable;

/**
 * A structured, type-validated test target for {@code run_tests} (issue #9, AC1–AC4). The agent
 * supplies two flat typed values ({@code targetKind} + {@code target}) via {@code @ToolArg} on the
 * MCP verb; the MCP translates them into a single controlled {@code -Dtest=<value>} injected into
 * the argv (same mechanism as the {@code -Dsurefire.reportsDirectory=} injection). The agent can
 * NEVER supply or drop {@code -Dtest=} as a free flag — it is absent from the allowlist seed.
 *
 * <p>Supported kinds (v1, maps to Surefire {@code -Dtest=} syntax):</p>
 * <ul>
 *   <li><b>CLASS</b> — fully or partially qualified class name, e.g. {@code com.example.FooTest}
 *       or just {@code FooTest}. Surefire: {@code -Dtest=FooTest}.</li>
 *   <li><b>METHOD</b> — {@code ClassName#methodName}, e.g. {@code FooTest#testBar}. Surefire:
 *       {@code -Dtest=FooTest#testBar}.</li>
 * </ul>
 *
 * <p><b>Explicitly NOT supported in v1:</b></p>
 * <ul>
 *   <li><b>FILE</b> — requires a file-to-class mapping that adds complexity without a concrete
 *       requirement.</li>
 *   <li><b>MODULE</b> — maps to Maven's {@code -pl}, a different flag entirely, not {@code -Dtest=}.
 *       Out of scope for this slice; CLASS/METHOD cover the concrete AC.</li>
 * </ul>
 *
 * <p>Validation is type-shape only (not injection-safety — argv-array construction already makes
 * shell metacharacters inert). Malformed means blank value, unknown kind, or METHOD without the
 * required {@code ClassName#methodName} format.</p>
 *
 * @param kind  the kind string as supplied by the agent ({@code CLASS} or {@code METHOD})
 * @param value the test identity value (non-blank, kind-specific format)
 */
public record TestTarget(String kind, String value) {

    /** The set of valid kind strings (v1). */
    public enum Kind {
        CLASS,
        METHOD
    }

    /**
     * Parse and validate an agent-supplied target pair into a typed {@link TestTarget}, returning
     * {@code null} when both kind and value are absent (full-suite run — the guard is skipped).
     *
     * @param kind  the agent-supplied kind string (nullable / blank → full-suite)
     * @param value the agent-supplied value (nullable / blank → full-suite)
     * @return a validated {@link TestTarget}, or {@code null} for full-suite (both absent)
     * @throws MalformedTargetException if either field is present but the pair is invalid
     */
    public static @Nullable TestTarget parse(@Nullable String kind, @Nullable String value)
            throws MalformedTargetException {
        boolean kindAbsent = kind == null || kind.isBlank();
        boolean valueAbsent = value == null || value.isBlank();

        // Both absent → full-suite, no target selector.
        if (kindAbsent && valueAbsent) {
            return null;
        }

        // One present without the other → malformed.
        if (kindAbsent) {
            throw new MalformedTargetException(
                    "A 'targetKind' must be specified when a 'target' value is provided. "
                            + "Supported kinds: CLASS, METHOD.");
        }
        if (valueAbsent) {
            throw new MalformedTargetException(
                    "A 'target' value must be specified when a 'targetKind' is provided.");
        }

        // Validate the kind.
        Kind resolvedKind;
        try {
            resolvedKind = Kind.valueOf(kind.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new MalformedTargetException(
                    "Unknown targetKind '" + kind + "'. Supported kinds: CLASS, METHOD.");
        }

        String trimmed = value.strip();

        // Kind-specific shape validation.
        switch (resolvedKind) {
            case CLASS -> {
                // Must be a non-blank identifier (no '#').
                if (trimmed.contains("#")) {
                    throw new MalformedTargetException(
                            "A CLASS target must not contain '#'. "
                                    + "Use targetKind=METHOD with 'ClassName#methodName' for a method target.");
                }
            }
            case METHOD -> {
                // Must be 'ClassName#methodName' — both parts non-blank.
                if (!trimmed.contains("#")) {
                    throw new MalformedTargetException(
                            "A METHOD target must be in 'ClassName#methodName' format "
                                    + "(e.g. FooTest#testBar). Got: '" + trimmed + "'.");
                }
                String[] parts = trimmed.split("#", 2);
                if (parts[0].isBlank() || parts[1].isBlank()) {
                    throw new MalformedTargetException(
                            "A METHOD target must have a non-blank class name and method name "
                                    + "on each side of '#'. Got: '" + trimmed + "'.");
                }
            }
        }

        return new TestTarget(resolvedKind.name(), trimmed);
    }

    /**
     * Translate this target into the Surefire {@code -Dtest=<value>} token, ready for injection
     * into the argv (never passed as an agent free-flag; always MCP-controlled). The value is the
     * exact Surefire {@code -Dtest=} syntax:
     * <ul>
     *   <li>{@code CLASS} → {@code -Dtest=ClassName}</li>
     *   <li>{@code METHOD} → {@code -Dtest=ClassName#methodName}</li>
     * </ul>
     *
     * @return the {@code -Dtest=<value>} token to inject into argv
     */
    public String toArgvToken() {
        return ArgvBuilder.TEST_SELECTOR_FLAG + value;
    }

    /**
     * Thrown when the agent-supplied {@code targetKind}/{@code target} pair is invalid. Caught
     * by the use-case pre-exec guard; the caught exception is converted to an
     * {@code INVALID_TARGET} operational-error envelope — NO process is launched.
     */
    public static final class MalformedTargetException extends Exception {
        public MalformedTargetException(String message) {
            super(message);
        }
    }
}
