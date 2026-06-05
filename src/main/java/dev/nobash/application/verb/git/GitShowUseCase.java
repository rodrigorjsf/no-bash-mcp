package dev.nobash.application.verb.git;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitCommitDetail;
import dev.nobash.domain.git.GitShowParser;
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
 * The {@code git_show} use-case (PRD-002, issue #26). Returns a commit's full metadata and
 * body; the commit diff is stashed behind a {@link Handle} in the run-cache and is retrievable
 * via {@code get_log(handle)} — large patches never flood the envelope.
 *
 * <p>Two git invocations are used to unambiguously separate body from diff:</p>
 * <ol>
 *   <li>{@code git show -s --format=<FORMAT> <ref>} — metadata + body only (the {@code -s}
 *       flag suppresses the diff entirely).</li>
 *   <li>{@code git show --format= <ref>} — diff only (empty format string means no header,
 *       so the output is pure diff). The diff is stashed behind a handle.</li>
 * </ol>
 *
 * <p>Guards mirror {@link GitStatusUseCase}: {@code INVALID_PATH} →
 * {@code TOOL_NOT_INSTALLED}. No {@code NO_MANAGER_DETECTED}, no {@code ModuleLock}.
 * An unknown or bad ref causes git to exit non-zero → {@code COMMIT_NOT_FOUND}.</p>
 */
@Singleton
public class GitShowUseCase {

    private static final String VERB = "git_show";
    private static final String MANAGER = "git";

    private final CommandExecutorPort executor;
    private final RawOutputStash stash;
    private final GitShowParser parser = new GitShowParser();

    public GitShowUseCase(@Named("git") CommandExecutorPort executor, RawOutputStash stash) {
        this.executor = executor;
        this.stash = stash;
    }

    /**
     * Run {@code git_show} for the commit at {@code ref} in the repository at {@code path}.
     *
     * @param path    the repository directory; absent/blank → {@code INVALID_PATH}
     * @param ref     the commit reference (SHA, tag, symbolic ref); absent/blank → {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope with commit detail; the diff is behind a handle
     */
    public Envelope run(String path, String ref, Integer timeout) {
        // Guard 1 — INVALID_PATH (path)
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

        // Guard 1b — INVALID_PATH (ref)
        if (ref == null || ref.isBlank()) {
            return Envelope.operationalError(VERB, ErrorCode.INVALID_PATH,
                    "No commit reference was provided.",
                    "Pass a commit SHA, tag, or symbolic ref (e.g. HEAD).");
        }

        // NO NO_MANAGER_DETECTED guard — git is ecosystem-agnostic.

        // Guard 2 — TOOL_NOT_INSTALLED
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' tool is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // NO ModuleLock — git read-only verbs are lock-exempt (ADR-0005).

        return runShow(dir, ref, timeout);
    }

    private Envelope runShow(Path dir, String ref, Integer timeout) {
        int timeoutSeconds = GitTimeoutPolicy.clamp(timeout);

        // Invocation 1: metadata + body only (-s suppresses the diff).
        List<String> metaArgv = List.of(
                "git", "show", "-s",
                "--format=" + GitShowParser.FORMAT,
                ref);
        ExecSpec metaSpec = new ExecSpec(metaArgv, dir.toString(), timeoutSeconds);
        ExecResult metaResult = executor.execute(metaSpec);

        if (metaResult.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git show (metadata) exceeded its timeout and was killed.",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }

        // Non-zero exit floor: bad ref or not a git repo.
        if (metaResult.exitCode() != 0) {
            // If the path itself is not a git repo vs a bad ref — we report COMMIT_NOT_FOUND
            // for any non-zero exit here since we already validated the directory exists.
            return Envelope.operationalError(VERB, ErrorCode.COMMIT_NOT_FOUND,
                    "Commit reference '" + ref + "' does not resolve in this repository "
                            + "(git exited with code " + metaResult.exitCode() + ").",
                    "Pass a valid commit SHA, tag, or symbolic ref visible in git_log output.");
        }

        GitCommitDetail detail = parser.parse(metaResult.stdout());

        // Invocation 2: diff only (empty format suppresses the metadata header).
        List<String> diffArgv = List.of(
                "git", "show", "--format=", ref);
        ExecSpec diffSpec = new ExecSpec(diffArgv, dir.toString(), timeoutSeconds);
        ExecResult diffResult = executor.execute(diffSpec);

        // Stash the diff output behind a handle regardless of diff exit code (partial is useful).
        String diffOutput = (diffResult.stdout() == null ? "" : diffResult.stdout());
        Handle handle = stash.stash(diffOutput);

        return Envelope.gitShow(VERB, detail, handle);
    }
}
