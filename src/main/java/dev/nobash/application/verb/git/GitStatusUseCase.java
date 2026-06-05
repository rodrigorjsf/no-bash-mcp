package dev.nobash.application.verb.git;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitStatus;
import dev.nobash.domain.git.GitStatusParser;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import jakarta.inject.Named;
import jakarta.inject.Singleton;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

/**
 * The {@code git_status} use-case — the FOUNDATIONAL git verb (PRD-002, issue #24). It validates
 * the request, runs the programmatic guards <strong>before any process is launched</strong>
 * (DESIGN.md §9), then orchestrates the read-only git tracer: validate the path and tool, launch
 * the trusted system {@code git status --porcelain=v2 --branch} via the {@link CommandExecutorPort}
 * seam (the {@code @Named("git")} bean), parse git's STABLE machine format, and assemble the result
 * {@link Envelope} (manager null).
 *
 * <p><b>Guard order</b> is fixed and fail-closed: {@code INVALID_PATH} → {@code TOOL_NOT_INSTALLED}.
 * git verbs intentionally OMIT two guards the ecosystem verbs have:</p>
 * <ul>
 *   <li><b>No {@code NO_MANAGER_DETECTED}</b> — git is ecosystem-agnostic; there is no
 *       {@code pom.xml}-style manager marker to detect.</li>
 *   <li><b>No {@code RESOURCE_BUSY} module-lock</b> — git read-only verbs do not mutate the module
 *       and are exempt from the per-module lock (ADR-0005). Exemption is by OMISSION: this
 *       use-case simply never touches {@code ModuleLock}, so two concurrent {@code git_status}
 *       calls both succeed.</li>
 * </ul>
 *
 * <p><b>Output shapes:</b></p>
 * <ul>
 *   <li><b>status</b> — {@code ok=true}, {@code gitStatus} carries the normalized shape (branch,
 *       upstream, ahead/behind, staged/unstaged/untracked); {@code manager} null.</li>
 *   <li><b>operational-error</b> — {@code INVALID_PATH} (missing/not-a-directory),
 *       {@code TOOL_NOT_INSTALLED} (git absent), {@code TIMEOUT} (deadline exceeded), or
 *       {@code NOT_A_GIT_REPOSITORY} (path exists but is not inside a git working tree —
 *       {@code git} exits non-zero). The non-zero-exit floor mirrors {@code RunBuildUseCase}: a
 *       failed git invocation is NEVER parsed into a misleading "clean repo".</li>
 * </ul>
 */
@Singleton
public class GitStatusUseCase {

    private static final String VERB = "git_status";
    private static final String MANAGER = "git";
    private static final List<String> STATUS_ARGV =
            List.of("git", "status", "--porcelain=v2", "--branch");

    private final CommandExecutorPort executor;
    private final GitStatusParser parser = new GitStatusParser();

    public GitStatusUseCase(@Named("git") CommandExecutorPort executor) {
        this.executor = executor;
    }

    /**
     * Run {@code git_status} for the repository at {@code path}. Returns the result envelope:
     * the git-status shape on success, or an operational error.
     *
     * @param path    the repository directory; absent/blank fails closed to {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope
     */
    public Envelope run(String path, Integer timeout) {
        // Guard 1 — INVALID_PATH (null / missing / not-a-directory). No confinement claim.
        if (path == null || path.isBlank()) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "No path was provided.",
                    "Pass the path to an existing repository directory.");
        }
        final Path dir;
        try {
            dir = Path.of(path);
        } catch (InvalidPathException e) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' is not a valid path.",
                    "Pass the path to an existing repository directory.");
        }
        if (!Files.isDirectory(dir)) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "Path '" + path + "' does not exist or is not a directory.",
                    "Pass the path to an existing repository directory.");
        }

        // NO NO_MANAGER_DETECTED guard — git is ecosystem-agnostic (no pom.xml-style marker).

        // Guard 2 — TOOL_NOT_INSTALLED. Trusted system git on PATH only (ADR-0008). Short-circuits
        // BEFORE any execute() — no process is launched when git is absent (parity with build).
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' tool is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // NO ModuleLock — git read-only verbs are lock-exempt (ADR-0005); exemption by omission.

        return runStatus(dir, timeout);
    }

    private Envelope runStatus(Path dir, Integer timeout) {
        int timeoutSeconds = GitTimeoutPolicy.clamp(timeout);
        ExecSpec spec = new ExecSpec(STATUS_ARGV, dir.toString(), timeoutSeconds);

        ExecResult result = executor.execute(spec);

        // Timeout intercept — the deadline fired and the executor reaped the tree (issue #6).
        if (result.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git status exceeded its timeout and was killed (the process tree was reaped).",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }

        // Non-zero exit floor: a path that exists but is not a git working tree makes git exit
        // non-zero (typically 128). We must NOT parse empty/garbage stdout into a misleading
        // "clean repo" (false-green) — mirror RunBuildUseCase's exit-code floor.
        if (result.exitCode() != 0) {
            return Envelope.operationalError(VERB, ErrorCode.NOT_A_GIT_REPOSITORY,
                    "Path is not inside a git repository (git exited with code "
                            + result.exitCode() + ").",
                    "Run git_status from a directory that is inside a checked-out git repository.");
        }

        GitStatus status = parser.parse(result.stdout());
        return Envelope.gitStatus(VERB, status, null);
    }
}
