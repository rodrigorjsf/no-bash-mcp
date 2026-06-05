package dev.nobash.application.verb.git;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitCommit;
import dev.nobash.domain.git.GitLogParser;
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
 * The {@code git_log} use-case (PRD-002, issue #26). Returns a capped commit list from
 * {@code git log --format=<FORMAT> -n <limit>} parsed into {@link GitCommit} records.
 *
 * <p>Guards mirror {@link GitStatusUseCase} exactly: {@code INVALID_PATH} →
 * {@code TOOL_NOT_INSTALLED}. No {@code NO_MANAGER_DETECTED} guard (git is ecosystem-agnostic),
 * no {@code ModuleLock} (read-only verb, exempt by omission).</p>
 *
 * <p>The format uses {@code %x1f} (unit separator) to delimit fields and {@code %x1e} (record
 * separator) to terminate each record, so author names or subjects containing spaces parse
 * cleanly and locale-independently.</p>
 */
@Singleton
public class GitLogUseCase {

    private static final String VERB = "git_log";
    private static final String MANAGER = "git";

    /** Default cap: the last N commits returned when the caller does not specify a limit. */
    public static final int DEFAULT_LIMIT = 20;

    /** Hard ceiling on the returned commit count. */
    public static final int MAX_LIMIT = 100;

    private final CommandExecutorPort executor;
    private final GitLogParser parser = new GitLogParser();

    public GitLogUseCase(@Named("git") CommandExecutorPort executor) {
        this.executor = executor;
    }

    /**
     * Run {@code git_log} for the repository at {@code path}.
     *
     * @param path    the repository directory; absent/blank → {@code INVALID_PATH}
     * @param limit   optional limit on commit count; clamped to [{@code 1}, {@link #MAX_LIMIT}];
     *                null or non-positive uses {@link #DEFAULT_LIMIT}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope
     */
    public Envelope run(String path, Integer limit, Integer timeout) {
        // Guard 1 — INVALID_PATH
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

        // NO NO_MANAGER_DETECTED guard — git is ecosystem-agnostic.

        // Guard 2 — TOOL_NOT_INSTALLED
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' tool is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // NO ModuleLock — git read-only verbs are lock-exempt (ADR-0005).

        return runLog(dir, limit, timeout);
    }

    private Envelope runLog(Path dir, Integer limit, Integer timeout) {
        int clampedLimit = clampLimit(limit);
        int timeoutSeconds = GitTimeoutPolicy.clamp(timeout);

        List<String> argv = List.of(
                "git", "log",
                "--format=" + GitLogParser.FORMAT,
                "-n", String.valueOf(clampedLimit));
        ExecSpec spec = new ExecSpec(argv, dir.toString(), timeoutSeconds);

        ExecResult result = executor.execute(spec);

        if (result.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git log exceeded its timeout and was killed.",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }

        // Non-zero exit floor: a path that is not a git working tree makes git exit non-zero.
        if (result.exitCode() != 0) {
            return Envelope.operationalError(VERB, ErrorCode.NOT_A_GIT_REPOSITORY,
                    "Path is not inside a git repository (git exited with code "
                            + result.exitCode() + ").",
                    "Run git_log from a directory that is inside a checked-out git repository.");
        }

        List<GitCommit> commits = parser.parse(result.stdout());
        return Envelope.gitLog(VERB, commits);
    }

    static int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(requested, MAX_LIMIT);
    }
}
