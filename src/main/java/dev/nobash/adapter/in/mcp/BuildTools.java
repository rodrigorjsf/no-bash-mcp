package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.tests.RunTestsUseCase;
import dev.nobash.domain.envelope.Envelope;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

import java.util.List;

/**
 * The inbound MCP adapter for build/test verbs (DESIGN.md §4). The {@code @Tool} bean IS the
 * adapter — there is no inbound port interface; transport (STDIO) is configuration. Discovery
 * is via compile-time DI. This slice exposes a single verb, {@code run_tests}, which delegates
 * to {@link RunTestsUseCase} and returns the operational-error {@link Envelope} as structured
 * content over the JSON-RPC/STDIO channel.
 */
@Singleton
public class BuildTools {

    private final RunTestsUseCase runTests;

    public BuildTools(RunTestsUseCase runTests) {
        this.runTests = runTests;
    }

    /**
     * Run a project's tests via the detected manager. In this slice the verb stops at the
     * operational-error gate (input validation + security guards) and never launches a process.
     *
     * @param path    the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param flags   agent-supplied flags, vetted against the per-operation allowlist
     * @param timeout accepted but not enforced in this slice
     * @return the result envelope (operational-error shape in this slice)
     */
    @Tool(name = "run_tests", description = "Run a project's tests via the detected manager and "
            + "return a structured result envelope.")
    public Envelope run_tests(
            @ToolArg(name = "path", description = "Path to the project directory") @Nullable String path,
            @ToolArg(name = "flags", description = "Optional manager flags (allowlisted)") @Nullable List<String> flags,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return runTests.run(path, flags == null ? List.of() : flags, timeout);
    }
}
