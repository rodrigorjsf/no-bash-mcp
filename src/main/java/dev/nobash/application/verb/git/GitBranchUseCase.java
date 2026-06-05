package dev.nobash.application.verb.git;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitBranchEntry;
import dev.nobash.domain.git.GitBranchParser;
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
 * The {@code git_branch} use-case (PRD-002, issue #28). Returns a normalized list of
 * {@link GitBranchEntry} records (name, current, upstream, ahead, behind) from
 * {@code git branch --format=<FORMAT>}, one entry per local branch.
 *
 * <p>Guards mirror {@link GitStatusUseCase}: {@code INVALID_PATH} →
 * {@code TOOL_NOT_INSTALLED}. No {@code NO_MANAGER_DETECTED} guard (git is ecosystem-agnostic),
 * no {@code ModuleLock} (read-only verb, exempt by ADR-0005).</p>
 *
 * <p>The format uses ASCII 31 (unit separator) as the field delimiter within each line so branch
 * names and upstream refs containing any printable character parse cleanly and
 * locale-independently. There is no stash/handle: {@code git branch} output is bounded (one
 * line per local branch) and never large enough to warrant pagination.</p>
 */
@Singleton
public class GitBranchUseCase {

    private static final String VERB = "git_branch";
    private static final String MANAGER = "git";

    private final CommandExecutorPort executor;
    private final GitBranchParser parser = new GitBranchParser();

    public GitBranchUseCase(@Named("git") CommandExecutorPort executor) {
        this.executor = executor;
    }

    /**
     * Run {@code git_branch} for the repository at {@code path}.
     *
     * @param path    the repository directory; absent/blank → {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope with the normalized branch list, or an operational error
     */
    public Envelope run(String path, Integer timeout) {
        // Guard 1 — INVALID_PATH (null / missing / not-a-directory).
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

        // Guard 2 — TOOL_NOT_INSTALLED.
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' tool is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // NO ModuleLock — git read-only verbs are lock-exempt (ADR-0005); exemption by omission.

        return runBranch(dir, timeout);
    }

    private Envelope runBranch(Path dir, Integer timeout) {
        int timeoutSeconds = GitTimeoutPolicy.clamp(timeout);

        List<String> argv = List.of("git", "branch", "--format=" + GitBranchParser.FORMAT);
        ExecSpec spec = new ExecSpec(argv, dir.toString(), timeoutSeconds);

        ExecResult result = executor.execute(spec);

        if (result.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git branch exceeded its timeout and was killed (the process tree was reaped).",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }

        // Non-zero exit floor: a path that is not a git working tree makes git exit non-zero.
        if (result.exitCode() != 0) {
            return Envelope.operationalError(VERB, ErrorCode.NOT_A_GIT_REPOSITORY,
                    "Path is not inside a git repository (git exited with code "
                            + result.exitCode() + ").",
                    "Run git_branch from a directory that is inside a checked-out git repository.");
        }

        List<GitBranchEntry> entries = parser.parse(result.stdout());
        return Envelope.gitBranch(VERB, entries);
    }
}
