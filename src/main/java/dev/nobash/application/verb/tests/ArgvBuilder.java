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

    /**
     * @param vettedFlags flags already filtered through the allowlist (never raw agent input)
     * @return an {@link ExecSpec} whose argv is {@code [mvn, -B, test, <vettedFlags...>]}
     */
    public ExecSpec buildTestArgv(List<String> vettedFlags) {
        List<String> argv = new ArrayList<>();
        argv.add(MANAGER);
        argv.addAll(BASE);
        argv.addAll(vettedFlags);
        return new ExecSpec(argv, null);
    }
}
