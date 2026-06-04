package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;
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

    /** The MCP-controlled report-freshness flag prefix (D27). Never an agent free-flag. */
    public static final String REPORTS_DIR_FLAG = "-Dsurefire.reportsDirectory=";

    /**
     * @param vettedFlags flags already filtered through the allowlist (never raw agent input)
     * @return an {@link ExecSpec} whose argv is {@code [mvn, -B, test, <vettedFlags...>]}
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags) {
        return new ExecSpec(baseArgv(vettedFlags), null);
    }

    /**
     * Build the full execution spec for a run, injecting the MCP-controlled
     * {@code -Dsurefire.reportsDirectory=<freshReportsDir>} flag (D27) and the module working
     * directory. The reports-dir flag is appended by the MCP <em>after</em> the vetted flags —
     * it is an MCP-injected value, not agent input (the agent's own
     * {@code -Dsurefire.reportsDirectory} / {@code -D*} flags are dropped by the allowlist before
     * they ever reach here), so report freshness is guaranteed by construction (AC10).
     *
     * @param vettedFlags     flags already filtered through the allowlist (never raw agent input)
     * @param freshReportsDir the unique, empty-before-exec reports directory (MCP-controlled)
     * @param workingDir      the module directory the manager runs in
     * @return the {@link ExecSpec} to hand to the executor seam
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags, String freshReportsDir, String workingDir) {
        List<String> argv = baseArgv(vettedFlags);
        argv.add(REPORTS_DIR_FLAG + freshReportsDir);
        return new ExecSpec(argv, workingDir);
    }

    private static List<String> baseArgv(List<String> vettedFlags) {
        List<String> argv = new ArrayList<>();
        argv.add(MANAGER);
        argv.addAll(BASE);
        argv.addAll(vettedFlags);
        return argv;
    }
}
