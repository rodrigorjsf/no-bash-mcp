package dev.nobash.testsupport.git;

import dev.nobash.adapter.out.ecosystem.maven.PathScanningManagerResolver;
import dev.nobash.adapter.out.git.GitCommandExecutor;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A reusable, programmatic temp {@code git init} integration-test harness (PRD-002, issue #24).
 * It scripts a real git repository in a {@code @TempDir} using the SAME trusted-{@code git}
 * outbound path the production code uses ({@link GitCommandExecutor} over {@link ExecSpec}), so
 * the harness exercises the exact launcher seam — never a separate {@code Runtime.exec}.
 *
 * <p>This helper is placed in {@code testsupport} (shared, not under any one verb-slice package)
 * so the later git slices — git_log/show (#26), git_diff (#27), git_branch (#28) — REUSE it to
 * build scripted repository state rather than re-implementing temp-git scripting each time.</p>
 *
 * <h3>Determinism (the classic temp-git flakes, pre-empted)</h3>
 * <ul>
 *   <li><b>Identity</b> — every {@code git commit} runs with local {@code user.email} /
 *       {@code user.name} set on the repo (via {@code git -c …}); the harness NEVER relies on a
 *       global git identity, which may be absent on a CI runner.</li>
 *   <li><b>Default branch name</b> — {@code git init -b main} forces the initial branch name
 *       rather than depending on the host's {@code init.defaultBranch} (main vs master varies).</li>
 *   <li><b>No network</b> — there is no remote; ahead/behind/upstream edges are covered by the
 *       pure golden parser fixtures, not here.</li>
 * </ul>
 *
 * <h3>Self-skipping</h3>
 * <p>{@link #gitAvailable()} lets a test {@code assumeTrue(...)} so the suite SELF-SKIPS when git
 * is absent — it rides the {@code mvn test} gate but never hard-fails a git-less runner.</p>
 */
public final class GitRepoFixture {

    /** Stable test identity injected into every commit (local config, never global). */
    private static final String USER_EMAIL = "test@no-bash-mcp.local";
    private static final String USER_NAME = "no-bash-mcp test";

    /** The forced initial branch name (deterministic across hosts). */
    public static final String DEFAULT_BRANCH = "main";

    private final Path repoDir;
    private final GitCommandExecutor git;

    private GitRepoFixture(Path repoDir, GitCommandExecutor git) {
        this.repoDir = repoDir;
        this.git = git;
    }

    /**
     * Initialize a fresh git repository at {@code repoDir} with a forced {@code main} branch, then
     * return the fixture handle. The git binary is resolved on the live PATH via the production
     * {@link GitCommandExecutor} (the same seam the use-case uses).
     *
     * @param repoDir an existing (empty) temp directory to initialize
     * @return the fixture handle for further scripting
     */
    public static GitRepoFixture init(Path repoDir) {
        GitCommandExecutor git = new GitCommandExecutor(new PathScanningManagerResolver());
        GitRepoFixture fixture = new GitRepoFixture(repoDir, git);
        fixture.run("init", "-b", DEFAULT_BRANCH);
        return fixture;
    }

    /** @return the repository directory (the working dir to pass to {@code git_status}). */
    public Path dir() {
        return repoDir;
    }

    /**
     * Write a file (creating parent directories) relative to the repo root, returning this fixture
     * for chaining.
     */
    public GitRepoFixture writeFile(String relativePath, String content) {
        Path target = repoDir.resolve(relativePath);
        try {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write fixture file " + relativePath, e);
        }
        return this;
    }

    /** Stage paths ({@code git add <paths...>}). With no args, stages everything ({@code .}). */
    public GitRepoFixture add(String... paths) {
        List<String> argv = new ArrayList<>(List.of("add"));
        if (paths.length == 0) {
            argv.add(".");
        } else {
            argv.addAll(List.of(paths));
        }
        run(argv.toArray(String[]::new));
        return this;
    }

    /**
     * Commit the staged tree with a message, injecting the local identity via {@code git -c …} so
     * the commit never depends on a global git config.
     */
    public GitRepoFixture commit(String message) {
        run("-c", "user.email=" + USER_EMAIL, "-c", "user.name=" + USER_NAME,
                "commit", "-m", message);
        return this;
    }

    /** Create AND check out a new branch ({@code git checkout -b <name>}). */
    public GitRepoFixture checkoutNewBranch(String name) {
        run("checkout", "-b", name);
        return this;
    }

    /**
     * Run an arbitrary git subcommand against the repo and return the raw result. Exposed so the
     * later git slices can script states this slice does not need (e.g. {@code git log},
     * {@code git tag}) through the same seam.
     *
     * @param args the git arguments AFTER {@code git} (e.g. {@code "status", "--porcelain=v2"})
     * @return the raw execution result (exit/stdout/stderr)
     */
    public ExecResult run(String... args) {
        List<String> argv = new ArrayList<>(args.length + 1);
        argv.add("git");
        argv.addAll(List.of(args));
        ExecResult result = git.execute(new ExecSpec(argv, repoDir.toString(), 60));
        if (result.exitCode() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args)
                    + " failed (exit " + result.exitCode() + "):\n" + result.stderr());
        }
        return result;
    }

    /**
     * Whether the trusted system {@code git} resolves on PATH. A test {@code assumeTrue(...)} on
     * this so the suite self-skips on a git-less runner.
     */
    public static boolean gitAvailable() {
        return new GitCommandExecutor(new PathScanningManagerResolver()).isManagerInstalled();
    }
}
