package dev.nobash.application.verb.git;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitDiffEntry;
import dev.nobash.domain.git.GitDiffParser;
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
 * The {@code git_diff} use-case (PRD-002, issue #27). Returns an inline diff summary
 * ({@code gitDiff[]}) with one {@link GitDiffEntry} per changed file ({@code path},
 * {@code added}, {@code deleted}, {@code status}); the full patch text is stashed behind a
 * {@link Handle} in the run-cache and is retrievable via {@code get_log(handle)} — large patches
 * never flood the envelope.
 *
 * <p>Three git invocations are used, all with identical scope ({@code HEAD}) so the summary and
 * the patch describe exactly the same set of changes:</p>
 * <ol>
 *   <li>{@code git diff HEAD --numstat} — added/deleted counts per file.</li>
 *   <li>{@code git diff HEAD --name-status} — status letter + canonical path per file.</li>
 *   <li>{@code git diff HEAD} — the full patch (stashed behind the handle).</li>
 * </ol>
 *
 * <p>Using {@code HEAD} as the diff scope covers both staged and unstaged changes relative to the
 * last commit, which is the most useful scope for an agent doing a pre-commit or post-edit
 * inspection. An empty working tree (nothing changed since HEAD) returns an empty {@code gitDiff[]}
 * with a handle pointing at empty output.</p>
 *
 * <p>Guards mirror {@link GitLogUseCase}: {@code INVALID_PATH} → {@code TOOL_NOT_INSTALLED}.
 * No {@code NO_MANAGER_DETECTED} guard (git is ecosystem-agnostic). No {@code ModuleLock}
 * (read-only verb, exempt by ADR-0005). Non-zero exit from any invocation → {@code NOT_A_GIT_REPOSITORY}
 * (the most likely cause is the path not being inside a git working tree; there is no ref to
 * resolve, so {@code COMMIT_NOT_FOUND} does not apply).</p>
 */
@Singleton
public class GitDiffUseCase {

    private static final String VERB = "git_diff";
    private static final String MANAGER = "git";

    private final CommandExecutorPort executor;
    private final RawOutputStash stash;
    private final GitDiffParser parser = new GitDiffParser();

    public GitDiffUseCase(@Named("git") CommandExecutorPort executor, RawOutputStash stash) {
        this.executor = executor;
        this.stash = stash;
    }

    /**
     * Run {@code git_diff} for the repository at {@code path}.
     *
     * @param path    the repository directory; absent/blank → {@code INVALID_PATH}
     * @param timeout optional timeout in seconds; clamped to the git policy cap
     * @return the result envelope with the inline diff summary and a patch handle
     */
    public Envelope run(String path, Integer timeout) {
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

        // NO NO_MANAGER_DETECTED guard — git is ecosystem-agnostic.

        // Guard 2 — TOOL_NOT_INSTALLED
        if (!executor.isManagerInstalled()) {
            return Envelope.operationalError(VERB, ErrorCode.TOOL_NOT_INSTALLED,
                    "The '" + MANAGER + "' tool is not installed on PATH.",
                    "Install " + MANAGER + " and ensure it is on the system PATH.");
        }

        // NO ModuleLock — git read-only verbs are lock-exempt (ADR-0005).

        return runDiff(dir, timeout);
    }

    private Envelope runDiff(Path dir, Integer timeout) {
        int timeoutSeconds = GitTimeoutPolicy.clamp(timeout);

        // Invocation 1: numstat — added/deleted counts per file.
        List<String> numstatArgv = List.of("git", "diff", "HEAD", "--numstat");
        ExecSpec numstatSpec = new ExecSpec(numstatArgv, dir.toString(), timeoutSeconds);
        ExecResult numstatResult = executor.execute(numstatSpec);

        if (numstatResult.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git diff (numstat) exceeded its timeout and was killed.",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }
        if (numstatResult.exitCode() != 0) {
            return Envelope.operationalError(VERB, ErrorCode.NOT_A_GIT_REPOSITORY,
                    "Path is not inside a git repository (git exited with code "
                            + numstatResult.exitCode() + ").",
                    "Run git_diff from a directory that is inside a checked-out git repository.");
        }

        // Invocation 2: name-status — status letter + canonical path per file.
        List<String> nameStatusArgv = List.of("git", "diff", "HEAD", "--name-status");
        ExecSpec nameStatusSpec = new ExecSpec(nameStatusArgv, dir.toString(), timeoutSeconds);
        ExecResult nameStatusResult = executor.execute(nameStatusSpec);

        if (nameStatusResult.timedOut()) {
            return Envelope.operationalError(VERB, ErrorCode.TIMEOUT,
                    "git diff (name-status) exceeded its timeout and was killed.",
                    "Raise `timeout` (up to the cap) or check for a pathological repository state.");
        }
        if (nameStatusResult.exitCode() != 0) {
            return Envelope.operationalError(VERB, ErrorCode.NOT_A_GIT_REPOSITORY,
                    "Path is not inside a git repository (git exited with code "
                            + nameStatusResult.exitCode() + ").",
                    "Run git_diff from a directory that is inside a checked-out git repository.");
        }

        // Invocation 3: full patch (stash behind handle; partial is still useful).
        List<String> patchArgv = List.of("git", "diff", "HEAD");
        ExecSpec patchSpec = new ExecSpec(patchArgv, dir.toString(), timeoutSeconds);
        ExecResult patchResult = executor.execute(patchSpec);

        // Stash the full patch regardless of exit code (partial output is still useful).
        String patchOutput = (patchResult.stdout() == null ? "" : patchResult.stdout());
        Handle handle = stash.stash(patchOutput);

        List<GitDiffEntry> entries = parser.parse(numstatResult.stdout(), nameStatusResult.stdout());
        return Envelope.gitDiff(VERB, entries, handle);
    }
}
