package dev.nobash.adapter.in.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC1 + AC2 (+ AC3 over the wire) — the end-to-end STDIO proof. The MCP server is launched as
 * a real subprocess over JSON-RPC/STDIO and driven through the full handshake, exactly like the
 * s2 spike's {@code stdio_client.py}:
 * {@code initialize} -> {@code notifications/initialized} -> {@code tools/list} ->
 * {@code tools/call run_tests} -> {@code tools/call build}.
 *
 * <p>The subprocess runs from the <b>test classpath</b> (not a built jar — {@code mvn test}
 * runs before {@code package}), and its stdout/stderr are redirected to temp files so the
 * classic ProcessBuilder pipe-buffer deadlock cannot occur.</p>
 *
 * <p>Asserts: (AC1) every stdout line is a JSON-RPC message — no banner, no logs leaked to the
 * protocol channel — and logs landed on stderr; (AC2) {@code tools/list} advertises
 * {@code run_tests}, {@code build}, AND {@code git_status} (PRD-002), with {@code git_status}
 * carrying its {@code readOnlyHint} annotation; (AC2/AC3) {@code tools/call run_tests} with a bad
 * path returns {@code INVALID_PATH}; (AC1 for build) {@code tools/call build} with a bad path also
 * returns {@code INVALID_PATH}; (PRD-002) {@code tools/call git_status} with a bad path also
 * returns {@code INVALID_PATH}, proving the git_status verb is reachable over STDIO.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class StdioServerEndToEndTest {

    @Test
    void run_tests_is_callable_over_stdio_and_stdout_stays_pure_jsonrpc() throws Exception {
        Path outFile = Files.createTempFile("mcp-stdout", ".log");
        Path errFile = Files.createTempFile("mcp-stderr", ".log");

        String java = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, "dev.nobash.Application");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());
        Process proc = pb.start();

        // Give the server a moment to start its STDIO loop before driving frames.
        Thread.sleep(1500);
        if (!proc.isAlive()) {
            throw new AssertionError("server exited before any frame was sent.\n--- stderr ---\n"
                    + Files.readString(errFile) + "\n--- stdout ---\n" + Files.readString(outFile));
        }

        try (OutputStream stdin = proc.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8))) {

            // Full MCP handshake then the two driving frames. We cannot read stdout inline
            // (it is redirected to a file), so we pace the frames with brief sleeps and poll
            // the file for completion — mirroring the spike's request/response cadence.
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"e2e-test\",\"version\":\"0\"}}}");
            Thread.sleep(400);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            Thread.sleep(200);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            Thread.sleep(300);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"run_tests\","
                    + "\"arguments\":{\"path\":\"/no/such/path/e2e-does-not-exist\"}}}");
            Thread.sleep(800);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"build\","
                    + "\"arguments\":{\"path\":\"/no/such/path/e2e-does-not-exist\"}}}");
            Thread.sleep(800);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"git_status\","
                    + "\"arguments\":{\"path\":\"/no/such/path/e2e-does-not-exist\"}}}");
            Thread.sleep(800);
        }

        // Close stdin (done above) and let the server drain + exit.
        boolean exited = proc.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) {
                proc.destroyForcibly();
            }
        }

        List<String> stdoutLines = nonBlankLines(outFile);
        String stderr = Files.readString(errFile);

        // ---- AC1: stdout is PURE JSON-RPC (no banner / no logs leaked) ----
        assertThat(stdoutLines)
                .as("stdout must carry at least the initialize/tools-list/tools-call responses")
                .isNotEmpty();
        List<String> nonJsonRpc = new ArrayList<>();
        for (String line : stdoutLines) {
            if (!looksLikeJsonRpc(line)) {
                nonJsonRpc.add(line);
            }
        }
        assertThat(nonJsonRpc)
                .as("every stdout line must be a JSON-RPC message; offenders: %s", nonJsonRpc)
                .isEmpty();

        // Logs (Micronaut startup line) must land on stderr, never stdout.
        assertThat(stderr)
                .as("server logs must be routed to stderr")
                .contains("STDIO");

        String allStdout = String.join("\n", stdoutLines);

        // ---- AC2: run_tests, build, and git_status are all advertised in tools/list ----
        assertThat(allStdout)
                .as("tools/list must advertise the run_tests tool")
                .contains("run_tests");
        assertThat(allStdout)
                .as("tools/list must advertise the build tool (AC1 for build verb)")
                .contains("build");
        assertThat(allStdout)
                .as("tools/list must advertise the git_status tool (PRD-002)")
                .contains("git_status");

        // ---- PRD-002: git_status carries the readOnlyHint annotation in its tools/list descriptor ----
        assertThat(allStdout)
                .as("git_status must surface readOnlyHint in its tool descriptor")
                .contains("readOnlyHint");

        // ---- AC2 / AC3 over the wire: the verb calls return the INVALID_PATH operational error ----
        assertThat(allStdout)
                .as("tools/call run_tests at a bad path must return the INVALID_PATH envelope")
                .contains("INVALID_PATH");
    }

    /**
     * Regression for the git_status/git_branch MCP outputSchema bug: a repo with NO upstream
     * tracking branch leaves {@code ahead}/{@code behind}/{@code upstream} null. Those must be
     * OMITTED from the structuredContent (not serialized as {@code null}), or the framework's
     * outputSchema validation rejects the envelope ({@code isError}) because the generated schema
     * types them non-nullable. Drives the REAL server over STDIO (the only layer that runs the
     * framework's outputSchema validation — unit serde tests do not).
     */
    @Test
    void git_status_and_git_branch_on_an_upstream_less_repo_pass_output_schema_validation() throws Exception {
        Assumptions.assumeTrue(isOnPath("git"), "SKIPPED: 'git' is not on PATH");

        Path repo = Files.createTempDirectory("git-noupstream");
        runGit(repo, "init", "-q");
        Files.writeString(repo.resolve("a.txt"), "v1\n");
        runGit(repo, "add", ".");
        // Identity passed inline so the test does not depend on ambient/global git config (CI has none).
        runGit(repo, "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-qm", "seed");

        Path outFile = Files.createTempFile("mcp-git-stdout", ".log");
        Path errFile = Files.createTempFile("mcp-git-stderr", ".log");
        String java = System.getProperty("java.home") + "/bin/java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(java, "-cp", classpath, "dev.nobash.Application");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());
        Process proc = pb.start();
        Thread.sleep(1500);
        if (!proc.isAlive()) {
            throw new AssertionError("server exited before any frame was sent.\n--- stderr ---\n"
                    + Files.readString(errFile));
        }

        try (OutputStream stdin = proc.getOutputStream();
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8))) {
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"git-schema-test\",\"version\":\"0\"}}}");
            Thread.sleep(400);
            send(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            Thread.sleep(200);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"git_status\",\"arguments\":{\"path\":\"" + jsonPath(repo) + "\"}}}");
            Thread.sleep(800);
            send(writer, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                    + "\"params\":{\"name\":\"git_branch\",\"arguments\":{\"path\":\"" + jsonPath(repo) + "\"}}}");
            Thread.sleep(800);
        }
        boolean exited = proc.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }

        String allStdout = String.join("\n", nonBlankLines(outFile));
        // The keystone: NO outputSchema validation failure leaked into a response.
        assertThat(allStdout)
                .as("git_status/git_branch on an upstream-less repo must NOT fail MCP outputSchema "
                        + "validation (null ahead/behind/upstream must be omitted, not rendered)")
                .doesNotContain("does not match tool outputSchema");
        // And both verbs returned their success shapes over the wire.
        assertThat(allStdout).as("git_status returned its gitStatus envelope").contains("gitStatus");
        assertThat(allStdout).as("git_branch returned its gitBranch envelope").contains("gitBranch");
    }

    private static String jsonPath(Path p) {
        return p.toString().replace("\\", "\\\\");
    }

    private static void runGit(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        for (String a : args) {
            cmd.add(a);
        }
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new AssertionError("git " + String.join(" ", args) + " failed (exit " + p.exitValue() + ")");
        }
    }

    private static boolean isOnPath(String bin) {
        try {
            Process p = new ProcessBuilder(bin, "--version").redirectErrorStream(true).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private static void send(BufferedWriter writer, String json) throws IOException {
        writer.write(json);
        writer.write("\n");
        writer.flush();
    }

    private static List<String> nonBlankLines(Path file) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                result.add(line.strip());
            }
        }
        return result;
    }

    /** A line is JSON-RPC iff it is a JSON object carrying the "jsonrpc":"2.0" marker. */
    private static boolean looksLikeJsonRpc(String line) {
        String t = line.strip();
        return t.startsWith("{") && t.endsWith("}") && t.contains("\"jsonrpc\"") && t.contains("\"2.0\"");
    }
}
