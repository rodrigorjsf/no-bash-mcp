package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.build.RunBuildUseCase;
import dev.nobash.application.verb.install.InstallUseCase;
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
 * is via compile-time DI. This slice exposes two verbs:
 * <ul>
 *   <li>{@code run_tests} — delegates to {@link RunTestsUseCase}</li>
 *   <li>{@code build} — delegates to {@link RunBuildUseCase} (ADR-0009)</li>
 * </ul>
 * Both return the result {@link Envelope} as structured content over the JSON-RPC/STDIO channel.
 * Also exposes the {@code install} verb, which delegates to {@link InstallUseCase} (PRD-3, slice 3).
 */
@Singleton
public class BuildTools {

    private final RunTestsUseCase runTests;
    private final RunBuildUseCase runBuild;
    private final InstallUseCase installUseCase;

    public BuildTools(RunTestsUseCase runTests, RunBuildUseCase runBuild, InstallUseCase installUseCase) {
        this.runTests = runTests;
        this.runBuild = runBuild;
        this.installUseCase = installUseCase;
    }

    /**
     * Run a project's tests via the detected manager: validate + guards, then launch the trusted
     * system {@code mvn} into a fresh per-run reports directory, normalize the Surefire report,
     * and return the result envelope with the positive-evidence failure floor.
     *
     * <p>The optional structured target selector ({@code targetKind} + {@code target}) narrows the
     * run to a specific class or method. The MCP translates the validated pair into a controlled
     * {@code -Dtest=<value>} — never passed as an agent free-flag (the allowlist drops any
     * {@code -Dtest=} in {@code flags}). An invalid selector returns {@code INVALID_TARGET} before
     * any process is launched. Absent/null → full-suite run (no selector).</p>
     *
     * @param path        the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param flags       agent-supplied flags, vetted against the per-operation allowlist
     * @param timeout     optional timeout in seconds; clamped to the policy cap
     * @param targetKind  optional target kind: {@code CLASS} or {@code METHOD}; absent → full suite
     * @param target      the test identity value matching the kind ({@code FooTest} / {@code FooTest#bar});
     *                    absent → full suite
     * @return the result envelope (success, test-failure, or operational-error)
     */
    @Tool(name = "run_tests", description = "Run a project's tests via the detected manager and "
            + "return a structured result envelope. Optionally narrow to a class or method via "
            + "targetKind + target.")
    public Envelope run_tests(
            @ToolArg(name = "path", description = "Path to the project directory") @Nullable String path,
            @ToolArg(name = "flags", description = "Optional manager flags (allowlisted)") @Nullable List<String> flags,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout,
            @ToolArg(name = "targetKind", description = "Optional target kind: CLASS or METHOD") @Nullable String targetKind,
            @ToolArg(name = "target", description = "Optional test identity: ClassName or ClassName#methodName") @Nullable String target) {
        return runTests.run(path, flags == null ? List.of() : flags, timeout, targetKind, target);
    }

    /**
     * Compile the project via the detected manager ({@code mvn test-compile}) and return a
     * structured result envelope. On a compile failure, the compiler diagnostics are parsed into
     * {@code CompileDiagnostic{file, line, col, severity, message}} and returned in
     * {@code diagnostics[]}. A successful build returns a minimal counts payload
     * ({@code errors:0, warnings:N}) with no {@code diagnostics[]} noise (ADR-0009).
     *
     * <p>The full compiler output is retained behind the {@code handle} for {@code get_log}.
     * {@code mvn} absent → operational error {@code TOOL_NOT_INSTALLED}.</p>
     *
     * @param path    the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the policy cap
     * @return the result envelope (build-success, compile-failure, or operational-error)
     */
    @Tool(name = "build", description = "Compile a project via the detected manager and return a "
            + "structured result envelope. Compile failures surface structured diagnostics[]. "
            + "A successful build returns a minimal counts payload with no diagnostics noise.")
    public Envelope build(
            @ToolArg(name = "path", description = "Path to the project directory") @Nullable String path,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return runBuild.run(path, timeout);
    }

    /**
     * Install Node.js dependencies via {@code npm install} and return a structured result envelope.
     * A successful install returns a minimal counts payload ({@code manager:"npm",
     * installSummary:{added:N, removed:M, changed:K}}) with no {@code failures[]} noise.
     *
     * <p>The MCP injects {@code --no-audit --no-fund} as controlled server flags. Agent-supplied
     * flags are vetted by an empty-seed allowlist — all are dropped in v1. Lifecycle hooks run
     * (no {@code --ignore-scripts}). Failed install → operational error {@code INSTALL_FAILED}
     * with npm's output retained behind the {@code handle} for {@code get_log}. {@code npm}
     * absent → {@code TOOL_NOT_INSTALLED}; no {@code package.json} → {@code NO_MANAGER_DETECTED}.</p>
     *
     * @param path    the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param flags   agent-supplied flags, vetted against the per-operation allowlist (empty seed)
     * @param timeout optional timeout in seconds; clamped to the policy cap
     * @return the result envelope (install-success or operational-error)
     */
    @Tool(name = "install", description = "Install Node.js dependencies via npm install and return a "
            + "structured result envelope. A successful install returns a minimal counts payload. "
            + "Failed install surfaces INSTALL_FAILED with npm output behind the handle.")
    public Envelope install(
            @ToolArg(name = "path", description = "Path to the Node.js project directory") @Nullable String path,
            @ToolArg(name = "flags", description = "Optional npm flags (allowlisted)") @Nullable List<String> flags,
            @ToolArg(name = "timeout", description = "Optional timeout in seconds") @Nullable Integer timeout) {
        return installUseCase.run(path, flags == null ? List.of() : flags, timeout);
    }
}
