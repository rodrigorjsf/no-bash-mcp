package dev.nobash.application.verb.build;

import dev.nobash.domain.port.out.ExecSpec;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * Builds the {@code build}/Maven invocation as an explicit argv ARRAY (security-model.md).
 * The manager binary is always {@code argv[0]}. Uses {@code mvn -B test-compile} to compile
 * both main and test sources (ADR-0009), so errors in test sources are caught — exactly as
 * the existing {@code compile-fail} fixture requires.
 *
 * <p>No agent-supplied flags are accepted for the build verb in this slice (YAGNI — the
 * allowlist is a future concern; build is a simpler verb than test).</p>
 */
@Singleton
public class BuildArgvBuilder {

    private static final String MANAGER = "mvn";

    /** Build the {@code mvn -B test-compile} execution spec for the given project directory. */
    public ExecSpec buildArgv(String workingDir, int timeoutSeconds) {
        return new ExecSpec(List.of(MANAGER, "-B", "test-compile"), workingDir, timeoutSeconds);
    }
}
