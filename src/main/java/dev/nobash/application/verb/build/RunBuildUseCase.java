package dev.nobash.application.verb.build;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.BuildSummary;
import dev.nobash.domain.result.CompileDiagnostic;
import dev.nobash.domain.result.CompileDiagnosticParser;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code build} use-case (verb slice). It validates the request, runs the programmatic
 * security guards <strong>before any process is launched</strong> (DESIGN.md §9), then
 * orchestrates the compilation tracer: validate the path and guards, launch the trusted system
 * {@code mvn test-compile} via the {@link CommandExecutorPort} seam, parse compiler diagnostics,
 * and assemble the result {@link Envelope} (ADR-0009).
 *
 * <p>Guard order is fixed and fail-closed: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} →
 * {@code TOOL_NOT_INSTALLED}. The executor seam is consulted ONLY once those guards pass.</p>
 *
 * <p>Output shapes (ADR-0009):</p>
 * <ul>
 *   <li><b>success</b> — {@code ok=true}, {@code buildSummary:{errors:0, warnings:N}}, no
 *       {@code diagnostics[]}; zero compiler errors.</li>
 *   <li><b>compile-failure</b> — {@code ok=false}, {@code diagnostics[]} with parsed
 *       {@code CompileDiagnostic} entries (ERROR and WARNING), {@code buildSummary} with counts;
 *       the full raw compiler output is retained behind a {@link Handle} for {@code get_log}.</li>
 * </ul>
 *
 * <p>The compile diagnostics are distinct from the frozen test-result schema (ADR-0007): they
 * carry no test identity, no {@link dev.nobash.domain.result.Outcome}, and carry a column that
 * {@link dev.nobash.domain.result.SourceRef} deliberately omits. Compile errors NEVER appear in
 * {@code failures[]}.</p>
 *
 * <p>Uses {@code mvn test-compile} (not plain {@code compile}) so that errors in test sources
 * are also caught — the existing {@code compile-fail} fixture has its error in a test source
 * (D33, ADR-0009).</p>
 */
@Singleton
public class RunBuildUseCase {

    private static final String VERB = "build";
    private static final String MANAGER = "mvn";
    private static final String MANAGER_MARKER = "pom.xml";

    private final CommandExecutorPort executor;
    private final BuildArgvBuilder argvBuilder;
    private final RawOutputStash stash;
    private final CompileDiagnosticParser parser = new CompileDiagnosticParser();

    public RunBuildUseCase(CommandExecutorPort executor, BuildArgvBuilder argvBuilder,
                           RawOutputStash stash) {
        this.executor = executor;
        this.argvBuilder = argvBuilder;
        this.stash = stash;
    }

    /**
     * Run the build (compile) for the project at {@code path}. Returns the result envelope:
     * success (counts-only), compile-failure (with {@code diagnostics[]}), or operational-error.
     *
     * @param path    the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the policy cap
     * @return the result envelope
     */
    public Envelope run(String path, Integer timeout) {
        // Guard 1 — INVALID_PATH (null / missing / not-a-directory).
        if (path == null || path.isBlank()) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "No path was provided.",
                    "Pass the path to an existing project directory.");
        }
        final Path dir;
        try {
            dir = Path.of(path);
        } catch (InvalidPathException e) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' is not a valid path.",
                    "Pass the path to an existing project directory.");
        }
        if (!Files.isDirectory(dir)) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' does not exist or is not a directory.",
                    "Pass the path to an existing project directory.");
        }

        // Guard 2 — NO_MANAGER_DETECTED. Maven-only in this slice.
        if (!Files.isRegularFile(dir.resolve(MANAGER_MARKER))) {
            return Envelope.operationalError(VERB, ErrorCode.NO_MANAGER_DETECTED,
                    "No supported manager was detected at '" + path + "' (looked for: " + MANAGER_MARKER + ").",
                    "Run build from a directory that contains a " + MANAGER_MARKER + ".");
        }

        // Guard 3 — TOOL_NOT_INSTALLED. Trusted system mvn on PATH only (ADR-0008).
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' manager is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        return runBuild(dir, timeout);
    }

    private Envelope runBuild(Path dir, Integer timeout) {
        int timeoutSeconds = BuildTimeoutPolicy.clamp(timeout);
        ExecSpec spec = argvBuilder.buildArgv(dir.toString(), timeoutSeconds);

        ExecResult result = executor.execute(spec);

        // Combine stdout + stderr — maven-compiler-plugin emits diagnostics on stdout.
        String combined = (result.stdout() == null ? "" : result.stdout())
                + (result.stderr() == null ? "" : result.stderr());

        // Stash the full raw output behind a handle (always, so get_log works without re-run).
        Handle handle = stash.stash(combined);

        List<CompileDiagnostic> diagnostics = parser.parse(combined);

        long errorCount   = diagnostics.stream().filter(d -> CompileDiagnostic.SEVERITY_ERROR.equals(d.severity())).count();
        long warningCount = diagnostics.stream().filter(d -> CompileDiagnostic.SEVERITY_WARNING.equals(d.severity())).count();
        BuildSummary buildSummary = new BuildSummary((int) errorCount, (int) warningCount);

        // A compile failure: non-zero exit code OR any ERROR diagnostic was parsed.
        // The exit-code floor (D28 analogue) ensures we never green-wash a failed build.
        boolean hasErrors = result.exitCode() != 0 || errorCount > 0;

        if (!hasErrors) {
            // Success: zero compile errors. Warnings are allowed.
            return Envelope.buildSuccess(VERB, MANAGER, buildSummary, handle);
        }

        // Compile failure: return diagnostics[] with all ERROR and WARNING entries.
        return Envelope.buildFailure(VERB, MANAGER, buildSummary, diagnostics, handle);
    }
}
