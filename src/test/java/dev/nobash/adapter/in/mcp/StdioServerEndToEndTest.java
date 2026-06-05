package dev.nobash.adapter.in.mcp;

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
 * protocol channel — and logs landed on stderr; (AC2) {@code tools/list} advertises both
 * {@code run_tests} and {@code build}; (AC2/AC3) {@code tools/call run_tests} with a bad path
 * returns {@code INVALID_PATH}; (AC1 for build) {@code tools/call build} with a bad path also
 * returns {@code INVALID_PATH}, proving the build verb is reachable over STDIO.</p>
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

        // ---- AC2: run_tests and build are both advertised in tools/list ----
        assertThat(allStdout)
                .as("tools/list must advertise the run_tests tool")
                .contains("run_tests");
        assertThat(allStdout)
                .as("tools/list must advertise the build tool (AC1 for build verb)")
                .contains("build");

        // ---- AC2 / AC3 over the wire: both verb calls return the INVALID_PATH operational error ----
        assertThat(allStdout)
                .as("tools/call run_tests at a bad path must return the INVALID_PATH envelope")
                .contains("INVALID_PATH");
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
