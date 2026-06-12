package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code run_tests}/Maven invocation as an explicit argv ARRAY (AC6,
 * security-model.md). The manager binary is always {@code argv[0]}; the base verb tokens
 * follow; vetted flags are appended as-is. Every appended token is a literal argv element —
 * it is never concatenated into a shell string, never split on a separator, and no shell
 * interpreter is ever prepended. Shell metacharacters in a token are therefore inert.
 *
 * <p>Flag vetting is the caller's responsibility (see {@code application/policy}); this
 * builder only guarantees the array shape.</p>
 */
@Singleton
public class ArgvBuilder {

    private static final String MANAGER = "mvn";
    private static final List<String> BASE = List.of("-B", "test");

    /**
     * The MCP-controlled test-selector flag prefix (issue #9). Never an agent free-flag — the
     * agent supplies a structured {@link TestTarget} via typed {@code @ToolArg}s; the MCP
     * translates the validated target into this token and injects it. The agent's own
     * {@code -Dtest=…} in the {@code flags} parameter is dropped by the allowlist before
     * it ever reaches here.
     */
    public static final String TEST_SELECTOR_FLAG = "-Dtest=";

    /**
     * @param vettedFlags flags already filtered through the allowlist (never raw agent input)
     * @return an {@link ExecSpec} whose argv is {@code [mvn, -B, test, <vettedFlags...>]}
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags) {
        return new ExecSpec(baseArgv(vettedFlags), null);
    }

    /**
     * Build the full execution spec for a run in the given module working directory.
     *
     * <p>Report freshness (D27) is NOT achieved by a {@code -Dsurefire.reportsDirectory} flag —
     * that is not a Surefire user-property and is silently ignored (Surefire always writes the
     * default {@code <module>/target/surefire-reports}). Freshness is the Maven adapter's job: it
     * reads that default directory after wiping it pre-exec. The agent's own
     * {@code -Dsurefire.reportsDirectory} / {@code -D*} flags never reach here regardless — the
     * allowlist drops them, so the agent cannot redirect or smuggle a reports directory.</p>
     *
     * @param vettedFlags    flags already filtered through the allowlist (never raw agent input)
     * @param workingDir     the module directory the manager runs in
     * @param timeoutSeconds the already-clamped hard deadline the executor enforces (issue #6)
     * @return the {@link ExecSpec} to hand to the executor seam
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags, String workingDir, int timeoutSeconds) {
        return buildTestArgv(vettedFlags, workingDir, timeoutSeconds, null);
    }

    /**
     * Build the full execution spec for a targeted run, injecting the MCP-controlled
     * {@code -Dtest=<value>} flag (issue #9) when a target is present. The target is an
     * MCP-injected controlled value — never an agent free-flag (the agent's own {@code -Dtest=}
     * is dropped by the allowlist before it reaches here).
     *
     * @param vettedFlags    flags already filtered through the allowlist (never raw agent input)
     * @param workingDir     the module directory the manager runs in
     * @param timeoutSeconds the already-clamped hard deadline the executor enforces (issue #6)
     * @param target         an optional, already-validated structured target; {@code null} → full suite
     * @return the {@link ExecSpec} to hand to the executor seam
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags, String workingDir, int timeoutSeconds,
                                  @Nullable TestTarget target) {
        List<String> argv = baseArgv(vettedFlags);
        if (target != null) {
            argv.add(target.toArgvToken());
        }
        return new ExecSpec(argv, workingDir, timeoutSeconds);
    }

    private static List<String> baseArgv(List<String> vettedFlags) {
        List<String> argv = new ArrayList<>();
        argv.add(MANAGER);
        argv.addAll(BASE);
        argv.addAll(vettedFlags);
        return argv;
    }
}
