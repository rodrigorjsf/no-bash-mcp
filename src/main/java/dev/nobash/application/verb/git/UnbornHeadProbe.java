package dev.nobash.application.verb.git;

import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;

import java.nio.file.Path;
import java.util.List;

/**
 * Discriminates an <em>empty-but-initialized</em> repository (unborn HEAD, no commits yet) from
 * a path that is genuinely not inside a git working tree (issue #38, D36).
 *
 * <p>Used ONLY on the non-zero-exit error path of {@code git_log} and {@code git_diff}, which
 * both hit the unborn HEAD condition and previously returned the misleading
 * {@code NOT_A_GIT_REPOSITORY} error. {@code git_status} and {@code git_branch} already exit 0
 * on an empty repo and are NOT affected.</p>
 *
 * <h3>Two-probe discriminator</h3>
 * <ol>
 *   <li>{@code git rev-parse --is-inside-work-tree} — exits 0 and prints {@code true} when the
 *       working directory is inside a git work tree (including an empty/unborn-HEAD repo). A path
 *       that is NOT a git repository fails this probe (non-zero exit). Short-circuit: if the path
 *       is not inside a work tree at all, skip the second probe and return {@code false} (keeps
 *       {@code NOT_A_GIT_REPOSITORY}).</li>
 *   <li>{@code git rev-parse --verify --quiet HEAD} — exits 0 only when HEAD resolves to a commit
 *       (i.e. at least one commit exists). On an unborn-HEAD repo this exits non-zero. If the
 *       first probe passed AND this probe fails, the repo is empty-but-initialized → return
 *       {@code true} (caller must return ok-empty).</li>
 * </ol>
 *
 * <p>The probes run with the same timeout as the originating command. No exec is issued on the
 * happy path (the probes are entered only after the primary command exits non-zero).</p>
 */
final class UnbornHeadProbe {

    private UnbornHeadProbe() {
        // utility class — no instances
    }

    /**
     * Returns {@code true} if {@code dir} is inside a git working tree but has no commits yet
     * (unborn HEAD). Returns {@code false} if the path is genuinely not a git repository or if
     * HEAD resolves to an existing commit (which should not happen on the error path, but is
     * handled defensively).
     *
     * @param executor       the git command executor (same instance the calling use-case holds)
     * @param dir            the directory that produced the non-zero exit from the primary command
     * @param timeoutSeconds the timeout to apply to each probe invocation
     * @return {@code true} iff the directory is an empty-but-initialized git repository
     */
    static boolean isEmptyButInitialized(CommandExecutorPort executor, Path dir, int timeoutSeconds) {
        // Probe 1: is this path inside a git work tree at all?
        ExecSpec insideWorkTreeSpec = new ExecSpec(
                List.of("git", "rev-parse", "--is-inside-work-tree"),
                dir.toString(),
                timeoutSeconds);
        ExecResult insideWorkTreeResult = executor.execute(insideWorkTreeSpec);

        if (insideWorkTreeResult.timedOut() || insideWorkTreeResult.exitCode() != 0) {
            // Not inside a git work tree — genuinely not a repository.
            return false;
        }

        // Probe 2: does HEAD resolve to a commit?
        ExecSpec headVerifySpec = new ExecSpec(
                List.of("git", "rev-parse", "--verify", "--quiet", "HEAD"),
                dir.toString(),
                timeoutSeconds);
        ExecResult headVerifyResult = executor.execute(headVerifySpec);

        if (headVerifyResult.timedOut()) {
            // Treat a timeout on the probe as "unknown" — keep NOT_A_GIT_REPOSITORY.
            return false;
        }

        // Inside a work tree AND HEAD does NOT resolve → empty-but-initialized.
        return headVerifyResult.exitCode() != 0;
    }
}
