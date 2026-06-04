package dev.nobash.application.verb.tests;

import dev.nobash.application.policy.TestsFlagPolicy;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code run_tests} use-case (verb slice). It validates the request and runs the
 * programmatic security guards <strong>before any process is launched</strong>
 * (DESIGN.md §9, security-tests-first). Guard order is fixed and fail-closed:
 *
 * <ol>
 *   <li>{@code INVALID_PATH} — path is null, missing, or not a directory. Makes NO
 *       workspace-confinement claim (path existence / directory-ness only).</li>
 *   <li>{@code NO_MANAGER_DETECTED} — the directory has no {@code pom.xml} marker; the
 *       message lists what was looked for.</li>
 *   <li>{@code TOOL_NOT_INSTALLED} — the trusted system {@code mvn} is absent from
 *       {@code PATH} (resolved via the port; never a repo wrapper, ADR-0008).</li>
 * </ol>
 *
 * <p>The executor port is consulted ONLY once the path and manager-marker guards pass — so
 * the early guards return without any outbound call, and this slice never launches
 * {@code mvn}. Argv construction (always an array) and flag vetting (the allowlist) are wired
 * but execution itself is a later slice; the flow stops at the operational-error gate.</p>
 */
@Singleton
public class RunTestsUseCase {

    private static final String VERB = "run_tests";
    private static final String MANAGER = "mvn";
    private static final String MANAGER_MARKER = "pom.xml";

    private final CommandExecutorPort executor;
    private final ArgvBuilder argvBuilder;
    private final TestsFlagPolicy flagPolicy;

    public RunTestsUseCase(CommandExecutorPort executor, ArgvBuilder argvBuilder, TestsFlagPolicy flagPolicy) {
        this.executor = executor;
        this.argvBuilder = argvBuilder;
        this.flagPolicy = flagPolicy;
    }

    /**
     * @param path    the project directory (optional at the wire; null fails closed)
     * @param flags   agent-supplied flags (untrusted; vetted by the allowlist)
     * @param timeout accepted but NOT enforced in this slice (enforcement is a later slice)
     * @return an operational-error envelope; this slice never returns a success envelope
     */
    public Envelope run(String path, List<String> flags, Integer timeout) {
        // Guard 1 — INVALID_PATH (null / missing / not-a-directory). No confinement claim.
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

        // Guard 2 — NO_MANAGER_DETECTED. List what was looked for (Maven-only this slice).
        if (!Files.isRegularFile(dir.resolve(MANAGER_MARKER))) {
            return Envelope.operationalError(VERB, ErrorCode.NO_MANAGER_DETECTED,
                    "No supported manager was detected at '" + path + "' (looked for: " + MANAGER_MARKER + ").",
                    "Run run_tests from a directory that contains a " + MANAGER_MARKER + ".");
        }

        // Guard 3 — TOOL_NOT_INSTALLED. Trusted system mvn on PATH only (ADR-0008).
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' manager is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // Past the operational-error gate: argv is always an array and flags are vetted.
        // Real execution is a later slice (this slice stops here).
        List<String> vetted = flagPolicy.filter(flags == null ? List.of() : flags);
        argvBuilder.buildTestArgv(vetted);
        throw new UnsupportedOperationException(
                "run_tests execution is not implemented in this slice (operational-error gate only)");
    }
}
