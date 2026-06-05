package dev.nobash.adapter.in.mcp;

import dev.nobash.testsupport.git.GitRepoFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Inspector {@code --cli} acceptance test for the {@code git_status} tool (PRD-002, issue #24).
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} Maven phase via {@code maven-failsafe-plugin}. It is
 * intentionally <b>NOT</b> a Surefire unit test and MUST NOT run under {@code mvn test}. It has
 * external prerequisites (MCP Inspector via {@code npx}, {@code jq}, a system {@code git}, and a
 * pre-packaged jar) that are CI-gated.</p>
 *
 * <h3>Self-skipping</h3>
 * <p>A {@code @BeforeAll} probe calls {@link Assumptions#assumeTrue(boolean)} for each
 * prerequisite (including {@code git}). If any is absent on the host, the entire class is
 * <b>SKIPPED, never FAILED</b>.</p>
 *
 * <h3>What is proved</h3>
 * <ol>
 *   <li><b>Dirty repo</b> — Inspector {@code --cli} + {@code git_status} on a scripted temp repo
 *       with a staged + untracked change returns {@code ok=true} with {@code gitStatus.branch}
 *       present and {@code gitStatus.untracked[]} non-empty (asserted via {@code jq}).</li>
 *   <li><b>Non-repo</b> — Inspector {@code --cli} + {@code git_status} on a plain directory returns
 *       {@code ok=false} with {@code error.code=NOT_A_GIT_REPOSITORY} (asserted via {@code jq}).</li>
 * </ol>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GitInspectorAcceptanceIT {

    /** Pinned MCP Inspector version (same as the other inspector ITs). */
    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";

    /** System property injected by Failsafe: the Maven build directory. */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /** Timeout for the inspector subprocess per invocation (seconds). */
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static Path packaged_jar;

    @BeforeAll
    static void probe_prerequisites_and_resolve_jar() throws Exception {
        // 1. git on PATH (required to script the repo AND for the server to run git_status).
        Assumptions.assumeTrue(GitRepoFixture.gitAvailable(),
                "SKIPPED: 'git' is not on PATH — git_status cannot run");

        // 2. npx on PATH (required to run the pinned MCP Inspector).
        Assumptions.assumeTrue(isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — MCP Inspector cannot be run");

        // 3. jq on PATH (required for JSON extraction assertions).
        Assumptions.assumeTrue(isOnPath("jq"),
                "SKIPPED: 'jq' is not on PATH — JSON assertions cannot run");

        // 4. Inspector resolvable.
        Assumptions.assumeTrue(probeInspector(),
                "SKIPPED: MCP Inspector " + INSPECTOR_VERSION + " is not resolvable via npx");

        // 5. Packaged jar exists.
        String buildDir = System.getProperty(BUILD_DIR_PROP);
        Assumptions.assumeTrue(buildDir != null && !buildDir.isBlank(),
                "SKIPPED: system property '" + BUILD_DIR_PROP + "' is not set");

        Path jarPath = Paths.get(buildDir, "no-bash-mcp-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jarPath),
                "SKIPPED: packaged jar not found at " + jarPath);
        packaged_jar = jarPath;

        // 6. target/dependency/ exists (the thin-jar Class-Path references it).
        Path depsDir = Paths.get(buildDir, "dependency");
        Assumptions.assumeTrue(Files.isDirectory(depsDir),
                "SKIPPED: target/dependency/ not found");
    }

    // ---- AC: dirty repo → ok=true, branch present, untracked[] non-empty ----

    @Test
    void inspector_cli_git_status_on_a_dirty_repo_returns_ok_true_with_branch_and_untracked(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("committed.txt", "v1\n")
                .add()
                .commit("seed");
        // A staged change + an untracked file.
        repo.writeFile("committed.txt", "v2\n").add("committed.txt");
        repo.writeFile("untracked.txt", "u\n");

        String envelopeJson = callGitStatusViaInspector(repo.dir().toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("dirty repo: ok must be true").isEqualTo("true");

        String branch = jqExtract(envelopeJson, ".gitStatus.branch");
        assertThat(branch.strip())
                .as("dirty repo: the current branch must be reported")
                .isEqualTo(GitRepoFixture.DEFAULT_BRANCH);

        String untrackedLen = jqExtract(envelopeJson, ".gitStatus.untracked | length");
        assertThat(Integer.parseInt(untrackedLen.strip()))
                .as("dirty repo: untracked[] must be non-empty")
                .isGreaterThan(0);
    }

    // ---- AC: non-repo → ok=false, error.code=NOT_A_GIT_REPOSITORY ----

    @Test
    void inspector_cli_git_status_on_a_non_repo_returns_NOT_A_GIT_REPOSITORY(@TempDir Path plainDir)
            throws Exception {
        String envelopeJson = callGitStatusViaInspector(plainDir.toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("non-repo: ok must be false").isEqualTo("false");

        String errorCode = jqExtract(envelopeJson, ".error.code");
        assertThat(errorCode.strip())
                .as("non-repo: error.code must be NOT_A_GIT_REPOSITORY")
                .isEqualTo("NOT_A_GIT_REPOSITORY");
    }

    // ---- AC: git_log on a repo with commits → ok=true, gitLog[] non-empty ----

    @Test
    void inspector_cli_git_log_on_a_repo_with_commits_returns_ok_true_with_non_empty_list(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("a.txt", "v1\n").add().commit("first commit")
                .writeFile("b.txt", "v2\n").add().commit("second commit");

        String envelopeJson = callToolViaInspector("git_log", "path=" + repo.dir().toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("git_log: ok must be true").isEqualTo("true");

        String listLen = jqExtract(envelopeJson, ".gitLog | length");
        assertThat(Integer.parseInt(listLen.strip()))
                .as("git_log: gitLog[] must be non-empty")
                .isGreaterThan(0);
    }

    @Test
    void inspector_cli_git_log_with_limit_returns_at_most_that_many_commits(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("a.txt", "1\n").add().commit("c1")
                .writeFile("b.txt", "2\n").add().commit("c2")
                .writeFile("c.txt", "3\n").add().commit("c3");

        String envelopeJson = callToolViaInspector("git_log",
                "path=" + repo.dir().toString(), "limit=2");

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("git_log limit: ok must be true").isEqualTo("true");

        String listLen = jqExtract(envelopeJson, ".gitLog | length");
        assertThat(Integer.parseInt(listLen.strip()))
                .as("git_log limit: gitLog[] must have at most 2 entries")
                .isLessThanOrEqualTo(2);
    }

    // ---- AC: git_show on HEAD → ok=true, gitShow.subject present, handle present ----

    @Test
    void inspector_cli_git_show_HEAD_returns_ok_true_with_subject_and_handle(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("file.txt", "content\n").add().commit("seed commit");

        String envelopeJson = callToolViaInspector("git_show",
                "path=" + repo.dir().toString(), "ref=HEAD");

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("git_show HEAD: ok must be true").isEqualTo("true");

        String subject = jqExtract(envelopeJson, ".gitShow.subject");
        assertThat(subject.strip())
                .as("git_show HEAD: subject must be present")
                .isEqualTo("seed commit");

        String handleId = jqExtract(envelopeJson, ".handle.id");
        assertThat(handleId.strip())
                .as("git_show HEAD: handle.id must be present")
                .isNotBlank();
    }

    @Test
    void inspector_cli_git_show_unknown_ref_returns_COMMIT_NOT_FOUND(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("f.txt", "x\n").add().commit("seed");

        String envelopeJson = callToolViaInspector("git_show",
                "path=" + repo.dir().toString(), "ref=nonexistent-sha-deadbeef");

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("git_show bad ref: ok must be false").isEqualTo("false");

        String errorCode = jqExtract(envelopeJson, ".error.code");
        assertThat(errorCode.strip())
                .as("git_show bad ref: error.code must be COMMIT_NOT_FOUND")
                .isEqualTo("COMMIT_NOT_FOUND");
    }

    // ---- AC: git_diff on a dirty repo → ok=true, gitDiff[] non-empty, handle present ----

    @Test
    void inspector_cli_git_diff_on_a_dirty_repo_returns_ok_true_with_files_and_handle(
            @TempDir Path tmp) throws Exception {
        GitRepoFixture repo = GitRepoFixture.init(tmp)
                .writeFile("seed.txt", "original\n").add().commit("seed");
        // Stage a modification so git diff HEAD sees a change.
        repo.writeFile("seed.txt", "original\nchanged\n").add("seed.txt");

        String envelopeJson = callToolViaInspector("git_diff",
                "path=" + repo.dir().toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("dirty repo git_diff: ok must be true").isEqualTo("true");

        String filesLen = jqExtract(envelopeJson, ".gitDiff | length");
        assertThat(Integer.parseInt(filesLen.strip()))
                .as("dirty repo git_diff: gitDiff[] must be non-empty")
                .isGreaterThan(0);

        String handleId = jqExtract(envelopeJson, ".handle.id");
        assertThat(handleId.strip())
                .as("git_diff: handle.id must be present")
                .isNotBlank();
    }

    @Test
    void inspector_cli_git_diff_on_a_non_repo_returns_NOT_A_GIT_REPOSITORY(
            @TempDir Path plainDir) throws Exception {
        String envelopeJson = callToolViaInspector("git_diff",
                "path=" + plainDir.toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip()).as("non-repo git_diff: ok must be false").isEqualTo("false");

        String errorCode = jqExtract(envelopeJson, ".error.code");
        assertThat(errorCode.strip())
                .as("non-repo git_diff: error.code must be NOT_A_GIT_REPOSITORY")
                .isEqualTo("NOT_A_GIT_REPOSITORY");
    }

    // ---- helpers ----

    private static String callToolViaInspector(String toolName, String... toolArgs) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(
                "npx", "--yes", INSPECTOR_VERSION, "--cli",
                "java", "-jar", packaged_jar.toString(),
                "--method", "tools/call",
                "--tool-name", toolName));
        for (String arg : toolArgs) {
            cmd.add("--tool-arg");
            cmd.add(arg);
        }

        Path outFile = Files.createTempFile("git-inspector-out-" + toolName, ".json");
        Path errFile = Files.createTempFile("git-inspector-err-" + toolName, ".log");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(INSPECTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("MCP Inspector timed out after " + INSPECTOR_TIMEOUT_SECONDS
                    + "s for tool " + toolName + ".\n--- stderr ---\n" + Files.readString(errFile));
        }

        String stdout = Files.readString(outFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(errFile, StandardCharsets.UTF_8);

        String envelopeJson = extractEnvelopeViaJq(outFile, stdout, stderr);
        assertThat(envelopeJson)
                .as("Inspector output must contain a valid envelope JSON for tool " + toolName + ".\n"
                        + "--- stdout ---\n%s\n--- stderr ---\n%s", stdout, stderr)
                .isNotBlank();
        return envelopeJson;
    }

    private static String callGitStatusViaInspector(String repoPath) throws Exception {
        return callToolViaInspector("git_status", "path=" + repoPath);
    }

    private static String extractEnvelopeViaJq(Path outFile, String stdout, String stderr)
            throws Exception {
        String filter = "(.result.structuredContent"
                + " // (.result.content[0]?.text // empty | fromjson?)"
                + " // .structuredContent"
                + " // .) | select(type == \"object\" and has(\"ok\")) | .";

        Path jqOut = Files.createTempFile("jq-git-envelope-out", ".json");
        Path jqErr = Files.createTempFile("jq-git-envelope-err", ".txt");

        ProcessBuilder pb = new ProcessBuilder("jq", "-rc", filter, outFile.toString());
        pb.redirectOutput(jqOut.toFile());
        pb.redirectError(jqErr.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out extracting envelope from Inspector output.\n"
                    + "--- inspector stdout ---\n" + stdout);
        }

        String result = Files.readString(jqOut, StandardCharsets.UTF_8).strip();
        if (proc.exitValue() != 0 || result.isBlank()) {
            return stdout.strip();
        }
        for (String line : result.split("\n")) {
            if (!line.isBlank()) return line.strip();
        }
        return stdout.strip();
    }

    private static String jqExtract(String json, String filter) throws Exception {
        Path jsonFile = Files.createTempFile("jq-git-input", ".json");
        Files.writeString(jsonFile, json);

        Path outFile = Files.createTempFile("jq-git-out", ".txt");
        Path errFile = Files.createTempFile("jq-git-err", ".txt");

        ProcessBuilder pb = new ProcessBuilder("jq", "-r", filter, jsonFile.toString());
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out for filter: " + filter);
        }

        String result = Files.readString(outFile, StandardCharsets.UTF_8);
        if (proc.exitValue() != 0) {
            String err = Files.readString(errFile, StandardCharsets.UTF_8);
            throw new AssertionError("jq failed for filter '" + filter
                    + "'\nInput: " + json + "\nError: " + err);
        }
        return result;
    }

    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "npx" -> List.of("npx", "--version");
                case "jq" -> List.of("jq", "--version");
                default -> List.of(command, "--version");
            };
            Process p = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static boolean probeInspector() {
        try {
            Process p = new ProcessBuilder("npx", "--yes", INSPECTOR_VERSION, "--version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(60, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
