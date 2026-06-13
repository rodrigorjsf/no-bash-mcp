package dev.nobash.application.verb.install;

import dev.nobash.application.policy.InstallFlagPolicy;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.InstallSummary;
import dev.nobash.domain.result.InstallSummaryParser;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code install} use-case (verb slice). It validates the request, runs the programmatic
 * security guards <strong>before any process is launched</strong> (DESIGN.md §9), then
 * runs {@code npm install} and assembles the result {@link Envelope} (PRD-3, slice 3).
 *
 * <p>Guard order is fixed and fail-closed: {@code INVALID_PATH} → {@code NO_MANAGER_DETECTED} →
 * {@code TOOL_NOT_INSTALLED}. The executor seam is consulted ONLY once those guards pass.</p>
 *
 * <p>Output shapes:</p>
 * <ul>
 *   <li><b>success</b> — {@code ok=true}, {@code manager:"npm"}, {@code installSummary} with
 *       npm's added/removed/changed counts parsed from stdout; NO {@code failures[]}.</li>
 *   <li><b>install-failure</b> — {@code INSTALL_FAILED} operational error; npm's stderr is
 *       retained behind a {@link Handle} for {@code get_log} drill-down.</li>
 * </ul>
 *
 * <p><b>D51:</b> {@code install} is Node/npm-only. It is a STANDALONE use-case — NOT routed
 * through {@code EcosystemAdapter}. Maven install is vacuous; Go fetches modules on demand.</p>
 *
 * <p><b>Security:</b> npm lifecycle hooks run (no {@code --ignore-scripts}) — project-authored
 * hooks are sanctioned, not agent-composed. The MCP injects {@code --no-audit --no-fund} as
 * controlled server flags. Agent-supplied flags are vetted by {@link InstallFlagPolicy}
 * (empty seed → all agent flags dropped).</p>
 *
 * <p>The executor is the {@code @Named("npm")} {@link CommandExecutorPort} — the
 * {@code NpmCommandExecutor} in {@code adapter.out.ecosystem.node}. Its
 * {@code isManagerInstalled()} probes {@code npm} on PATH via the generic
 * {@link dev.nobash.adapter.out.ecosystem.ManagerPathResolver} seam. Its {@code execute(spec)}
 * runs the argv array verbatim via {@code ProcessBuilder} (no shell wrapping, per ADR-0008).</p>
 */
@Singleton
public class InstallUseCase {

    private static final String VERB = "install";
    private static final String MANAGER = "npm";
    private static final String MANAGER_MARKER = "package.json";

    /** MCP-injected controlled flags — NOT agent-supplied, never vetted through the allowlist. */
    private static final List<String> CONTROLLED_FLAGS = List.of("--no-audit", "--no-fund");

    private final CommandExecutorPort executor;
    private final InstallFlagPolicy flagPolicy;
    private final RawOutputStash stash;

    public InstallUseCase(@Named("npm") CommandExecutorPort executor,
                          InstallFlagPolicy flagPolicy,
                          RawOutputStash stash) {
        this.executor = executor;
        this.flagPolicy = flagPolicy;
        this.stash = stash;
    }

    /**
     * Run {@code npm install} for the project at {@code path}. Returns the result envelope:
     * install-success (minimal counts), or operational-error.
     *
     * @param path    the project directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param flags   agent-supplied flags, vetted against the (empty) per-operation allowlist
     * @param timeout optional timeout in seconds; clamped to the policy cap
     * @return the result envelope
     */
    public Envelope run(String path, List<String> flags, Integer timeout) {
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

        // Guard 2 — NO_MANAGER_DETECTED (no package.json at the path).
        if (!Files.isRegularFile(dir.resolve(MANAGER_MARKER))) {
            return Envelope.operationalError(VERB, ErrorCode.NO_MANAGER_DETECTED,
                    "No supported manager was detected at '" + path + "' (looked for: " + MANAGER_MARKER + ").",
                    "Run install from a directory that contains a " + MANAGER_MARKER + ".");
        }

        // Guard 3 — TOOL_NOT_INSTALLED. npm's isManagerInstalled() probes npm on PATH.
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' manager is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        return runInstall(dir, flags == null ? List.of() : flags, timeout);
    }

    private Envelope runInstall(Path dir, List<String> agentFlags, Integer timeout) {
        int timeoutSeconds = InstallTimeoutPolicy.clamp(timeout);

        // Vet agent-supplied flags through the empty-seed allowlist (all dropped in v1).
        List<String> vettedFlags = flagPolicy.filter(agentFlags);

        // Build argv: [npm, install, --no-audit, --no-fund, ...vettedFlags]
        List<String> argv = new ArrayList<>();
        argv.add(MANAGER);
        argv.add("install");
        argv.addAll(CONTROLLED_FLAGS);
        argv.addAll(vettedFlags);

        ExecSpec spec = new ExecSpec(argv, dir.toString(), timeoutSeconds);
        ExecResult result = executor.execute(spec);

        // Stash stdout+stderr behind a handle — get_log works for both outcomes.
        String combined = (result.stdout() == null ? "" : result.stdout())
                + (result.stderr() == null ? "" : result.stderr());
        Handle handle = stash.stash(combined);

        // Failure: non-zero exit or timed-out → INSTALL_FAILED; retain output behind handle.
        if (result.exitCode() != 0 || result.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.INSTALL_FAILED,
                    "npm install failed (exit code " + result.exitCode() + ").",
                    "Check the log for details via get_log with handle '" + handle.id() + "'.",
                    handle);
        }

        // Success: parse the npm summary from stdout, return minimal envelope.
        InstallSummary installSummary = InstallSummaryParser.parse(result.stdout());
        return Envelope.installSuccess(VERB, MANAGER, installSummary, handle);
    }
}
