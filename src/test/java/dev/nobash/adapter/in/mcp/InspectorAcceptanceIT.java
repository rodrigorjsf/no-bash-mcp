package dev.nobash.adapter.in.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Inspector {@code --cli} acceptance test for the packaged JVM jar (issue #8).
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} Maven phase via {@code maven-failsafe-plugin}.
 * It is intentionally <b>NOT</b> a Surefire unit test and MUST NOT run under {@code mvn test}.
 * It has external/network prerequisites (MCP Inspector via {@code npx}, {@code jq}, a Java-25
 * system {@code mvn}, and a pre-packaged jar) that are CI-gated.</p>
 *
 * <h3>Self-skipping</h3>
 * <p>A {@code @BeforeAll} probe calls {@link Assumptions#assumeTrue(boolean)} for each
 * prerequisite. If any prerequisite is absent on the host — the pinned MCP Inspector cannot be
 * resolved via {@code npx}, {@code jq} is absent, or {@code mvn} is absent — the entire test
 * class is <b>SKIPPED, never FAILED</b>. This guarantees the test can never break a developer
 * machine or CI runner that lacks the tooling.</p>
 *
 * <h3>What is proved</h3>
 * <p>The three acceptance scenarios, in order:</p>
 * <ol>
 *   <li><b>Passing case</b> — Inspector {@code --cli} + {@code run_tests} on the
 *       {@code fixture-passing} project returns {@code ok=true} counts-only envelope.</li>
 *   <li><b>Failing case</b> — Inspector {@code --cli} + {@code run_tests} on the
 *       {@code fixture-failing} project returns {@code ok=false} with {@code failures[].kind}
 *       present (asserted via {@code jq}).</li>
 *   <li><b>Compile-fail case</b> — Inspector {@code --cli} + {@code run_tests} on the
 *       {@code compile-fail} project returns {@code code=REPORT_NOT_PRODUCED} in the
 *       operational-error envelope (asserted via {@code jq}).</li>
 * </ol>
 * <p>In addition, the clean-stdout channel is asserted: every stdout line from the packaged jar
 * is a valid JSON-RPC message; no banner, no log output leaked to the protocol channel.</p>
 *
 * <h3>Jar launch approach</h3>
 * <p>The packaged jar uses a MANIFEST.MF {@code Class-Path} that references the
 * {@code target/dependency/} directory (populated by {@code maven-dependency-plugin:copy-dependencies}
 * during the {@code prepare-package} phase). The IT therefore launches the server via
 * {@code java -jar no-bash-mcp-0.1.0-SNAPSHOT.jar} rather than {@code java -cp classpath Application}.
 * This tests the <b>actual packaged artifact</b> — not compiled classes from {@code target/classes}.</p>
 *
 * <h3>Prerequisites (pinned)</h3>
 * <ul>
 *   <li><b>Node / npm</b> — to run {@code npx}</li>
 *   <li><b>MCP Inspector {@value INSPECTOR_VERSION}</b> — pinned; not {@code @latest}</li>
 *   <li><b>{@code jq}</b> — for JSON extraction assertions</li>
 *   <li><b>System {@code mvn}</b> (Java 25) — to build the fixture projects</li>
 *   <li><b>Packaged jar + {@code target/dependency/}</b> — {@code mvn package -DskipTests} must
 *       have run before {@code mvn verify}; the jar is located via the {@code project.build.directory}
 *       system property injected by the Failsafe plugin configuration.</li>
 * </ul>
 *
 * <h3>How to run manually</h3>
 * <pre>
 *   mvn -DskipTests package          # build the jar + copy deps first
 *   mvn verify -DskipTests           # then run Failsafe ITs (skip Surefire)
 *   # or just:
 *   mvn verify                       # package + unit tests + integration-test + verify
 * </pre>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class InspectorAcceptanceIT {

    /**
     * Pinned MCP Inspector version used for these tests. Do NOT float to {@code @latest}.
     * Bump this deliberately when upgrading (update the CI workflow + this constant together).
     */
    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";

    /** System property name injected by the Failsafe plugin: the Maven build directory. */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /** Timeout for the inspector subprocess per invocation (seconds). */
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static Path packaged_jar;
    private static Path fixture_passing_dir;
    private static Path fixture_failing_dir;
    private static Path fixture_compile_fail_dir;

    @BeforeAll
    static void probe_prerequisites_and_resolve_jar(@TempDir Path tempFixtureRoot) throws Exception {
        // ---- prerequisite probes (self-skipping on absence) ----

        // 1. Check that `mvn` (the trusted system Maven, not ./mvnw) is on PATH.
        Assumptions.assumeTrue(
                isOnPath("mvn"),
                "SKIPPED: 'mvn' is not on PATH — fixture projects cannot be built");

        // 2. Check that `npx` is on PATH (required to run the pinned MCP Inspector).
        Assumptions.assumeTrue(
                isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — MCP Inspector cannot be run");

        // 3. Check that `jq` is on PATH (required for JSON extraction assertions).
        Assumptions.assumeTrue(
                isOnPath("jq"),
                "SKIPPED: 'jq' is not on PATH — JSON assertions cannot run");

        // 4. Probe the pinned Inspector version by running `npx --yes @.../inspector@0.14.1 --version`.
        // This confirms the version is resolvable from npm, including from a warm npm cache.
        // We only require that npx terminates within the timeout — not a specific exit code —
        // because Inspector 0.14.1 may not support --version as a stable flag.
        boolean inspectorResolvable = probeInspector();
        Assumptions.assumeTrue(
                inspectorResolvable,
                "SKIPPED: MCP Inspector " + INSPECTOR_VERSION + " is not resolvable via npx (timed out)");

        // 5. Locate the packaged jar. The Failsafe plugin injects 'project.build.directory'
        // via <systemPropertyVariables>.
        String buildDir = System.getProperty(BUILD_DIR_PROP);
        Assumptions.assumeTrue(
                buildDir != null && !buildDir.isBlank(),
                "SKIPPED: system property '" + BUILD_DIR_PROP + "' is not set (run via mvn verify, not directly)");

        Path jarPath = Paths.get(buildDir, "no-bash-mcp-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(
                Files.isRegularFile(jarPath),
                "SKIPPED: packaged jar not found at " + jarPath + " — run 'mvn -DskipTests package' first");
        packaged_jar = jarPath;

        // 6. Verify that target/dependency/ exists (populated by maven-dependency-plugin).
        // The jar MANIFEST.MF Class-Path references dependency/*.jar; without this dir,
        // java -jar exits immediately with NoClassDefFoundError.
        Path depsDir = Paths.get(buildDir, "dependency");
        Assumptions.assumeTrue(
                Files.isDirectory(depsDir),
                "SKIPPED: target/dependency/ not found — run 'mvn -DskipTests package' to copy-dependencies");

        // 7. Extract and prepare the fixture projects from classpath resources into the temp dir.
        fixture_passing_dir = copyFixture(tempFixtureRoot, "fixtures/it/passing");
        fixture_failing_dir = copyFixture(tempFixtureRoot, "fixtures/it/failing");
        fixture_compile_fail_dir = copyFixture(tempFixtureRoot, "fixtures/it/compile-fail");

        Assumptions.assumeTrue(
                Files.isRegularFile(fixture_passing_dir.resolve("pom.xml")),
                "SKIPPED: fixture/passing pom.xml not found");
        Assumptions.assumeTrue(
                Files.isRegularFile(fixture_failing_dir.resolve("pom.xml")),
                "SKIPPED: fixture/failing pom.xml not found");
        Assumptions.assumeTrue(
                Files.isRegularFile(fixture_compile_fail_dir.resolve("pom.xml")),
                "SKIPPED: fixture/compile-fail pom.xml not found");
    }

    // ---- AC1: passing case → ok=true, counts-only ----

    @Test
    void inspector_cli_run_tests_on_passing_project_returns_ok_true_counts_only_envelope()
            throws Exception {
        String envelopeJson = callRunTestsViaInspector(fixture_passing_dir.toString());

        // Assert ok=true via jq
        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("passing case: ok must be true")
                .isEqualTo("true");

        // Assert no failures[] (counts-only)
        String failuresValue = jqExtract(envelopeJson, ".failures");
        assertThat(failuresValue.strip())
                .as("passing case: failures must be null (counts-only)")
                .isEqualTo("null");

        // Assert no error
        String errorValue = jqExtract(envelopeJson, ".error");
        assertThat(errorValue.strip())
                .as("passing case: error must be null")
                .isEqualTo("null");
    }

    // ---- AC2: failing case → ok=false, failures[].kind present ----

    @Test
    void inspector_cli_run_tests_on_failing_project_returns_ok_false_with_failures_kind()
            throws Exception {
        String envelopeJson = callRunTestsViaInspector(fixture_failing_dir.toString());

        // Assert ok=false via jq
        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("failing case: ok must be false")
                .isEqualTo("false");

        // Assert failures[] is non-null and non-empty
        String failuresLength = jqExtract(envelopeJson, ".failures | length");
        int failuresCount = Integer.parseInt(failuresLength.strip());
        assertThat(failuresCount)
                .as("failing case: failures[] must be non-empty")
                .isGreaterThan(0);

        // Assert failures[].kind is present (the 'kind' discriminator from sealed Finding)
        assertJqExpression(envelopeJson, ".failures | map(.kind) | all(. != null)",
                "failing case: every failure must have a non-null 'kind' discriminator");
    }

    // ---- AC3: compile-fail case → REPORT_NOT_PRODUCED ----

    @Test
    void inspector_cli_run_tests_on_compile_fail_project_returns_REPORT_NOT_PRODUCED_code()
            throws Exception {
        String envelopeJson = callRunTestsViaInspector(fixture_compile_fail_dir.toString());

        // Assert ok=false
        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("compile-fail case: ok must be false")
                .isEqualTo("false");

        // Assert error.code = REPORT_NOT_PRODUCED
        // jq -r strips JSON string quotes, so the result is the bare string REPORT_NOT_PRODUCED
        String errorCode = jqExtract(envelopeJson, ".error.code");
        assertThat(errorCode.strip())
                .as("compile-fail case: error.code must be REPORT_NOT_PRODUCED")
                .isEqualTo("REPORT_NOT_PRODUCED");
    }

    // ---- AC4: clean stdout channel ----

    @Test
    void packaged_jar_stdout_carries_only_jsonrpc_no_banner_no_logs() throws Exception {
        // Drive the packaged jar directly over STDIO (the same channel the Inspector uses) and
        // assert every stdout line is JSON-RPC. This proves the channel is clean over the real
        // transport independently from the Inspector wrapper.
        //
        // The server is launched via 'java -jar <packaged_jar>' — using the actual artifact
        // (not target/classes) with the MANIFEST.MF Class-Path pointing to target/dependency/.
        Path outFile = Files.createTempFile("it-stdout", ".log");
        Path errFile = Files.createTempFile("it-stderr", ".log");

        String java = System.getProperty("java.home") + "/bin/java";

        ProcessBuilder pb = new ProcessBuilder(java, "-jar", packaged_jar.toString());
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());
        Process proc = pb.start();

        Thread.sleep(1500);
        if (!proc.isAlive()) {
            throw new AssertionError("Server exited before any frame was sent.\n--- stderr ---\n"
                    + Files.readString(errFile) + "\n--- stdout ---\n" + Files.readString(outFile));
        }

        try (OutputStream stdin = proc.getOutputStream();
             BufferedWriter writer = new BufferedWriter(
                     new OutputStreamWriter(stdin, StandardCharsets.UTF_8))) {
            sendFrame(writer, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                    + "\"params\":{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"it-clean-channel\",\"version\":\"0\"}}}");
            Thread.sleep(400);
            sendFrame(writer, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            Thread.sleep(200);
            sendFrame(writer, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            Thread.sleep(300);
        }

        boolean exited = proc.waitFor(20, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroy();
            proc.waitFor(5, TimeUnit.SECONDS);
            if (proc.isAlive()) proc.destroyForcibly();
        }

        List<String> stdoutLines = nonBlankLines(outFile);
        assertThat(stdoutLines)
                .as("stdout must carry at least the initialize and tools/list responses")
                .isNotEmpty();

        List<String> nonJsonRpc = new ArrayList<>();
        for (String line : stdoutLines) {
            if (!looksLikeJsonRpc(line)) {
                nonJsonRpc.add(line);
            }
        }
        assertThat(nonJsonRpc)
                .as("every stdout line from the packaged jar must be a JSON-RPC message; "
                        + "offenders: %s", nonJsonRpc)
                .isEmpty();
    }

    // ---- helpers ----

    /**
     * Launch the packaged jar via MCP Inspector {@code --cli}, call {@code run_tests} against the
     * given project path (pre-built fixture), and return the envelope JSON extracted from the
     * Inspector's output.
     *
     * <p>The Inspector {@code --cli} mode drives the server over real STDIO JSON-RPC and returns
     * the tool result. We extract the envelope from the Inspector's JSON output via {@code jq}
     * using a tolerant filter that handles both {@code structuredContent} wrapping and plain
     * text-content wrapping.</p>
     *
     * <p>The exact invocation is:
     * <pre>
     *   npx --yes @modelcontextprotocol/inspector@0.14.1 --cli \
     *     java -jar &lt;packaged_jar&gt; \
     *     --method tools/call --tool-name run_tests \
     *     --tool-arg path=&lt;fixtureDir&gt;
     * </pre></p>
     */
    private static String callRunTestsViaInspector(String fixtureProjectPath) throws Exception {
        List<String> cmd = List.of(
                "npx", "--yes", INSPECTOR_VERSION, "--cli",
                "java", "-jar", packaged_jar.toString(),
                "--method", "tools/call",
                "--tool-name", "run_tests",
                "--tool-arg", "path=" + fixtureProjectPath
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Path outFile = Files.createTempFile("inspector-out", ".json");
        Path errFile = Files.createTempFile("inspector-err", ".log");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(INSPECTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            String stderr = Files.readString(errFile);
            throw new AssertionError("MCP Inspector timed out after " + INSPECTOR_TIMEOUT_SECONDS
                    + "s.\n--- stderr ---\n" + stderr);
        }

        String stdout = Files.readString(outFile, StandardCharsets.UTF_8);
        String stderr = Files.readString(errFile, StandardCharsets.UTF_8);

        // Extract the Envelope JSON from the Inspector output using jq.
        // The Inspector --cli outputs the tool-call result as JSON, which may be:
        //   (a) {"result": {"structuredContent": {...envelope...}, "isError": false}}
        //   (b) {"result": {"content": [{"type": "text", "text": "{...envelope json...}"}]}}
        //   (c) the envelope directly ({"ok": ...})
        // We use a tolerant jq filter that handles all three shapes.
        // Assumption: Inspector stdout is valid JSON (one or more objects). If Inspector
        // interleaves non-JSON lines on stdout, the jq step will fail and the test fails.
        String envelopeJson = extractEnvelopeViaJq(outFile, stdout, stderr);

        assertThat(envelopeJson)
                .as("Inspector output must contain a valid envelope JSON.\n"
                        + "--- stdout ---\n%s\n--- stderr ---\n%s", stdout, stderr)
                .isNotBlank();

        return envelopeJson;
    }

    /**
     * Extract the Envelope JSON from the MCP Inspector {@code --cli} output file using {@code jq}.
     *
     * <p>Uses a tolerant jq filter that handles Inspector output in multiple known shapes:
     * the {@code result.structuredContent} wrapper, the {@code result.content[0].text} wrapper,
     * and a direct envelope object. Returns the envelope as a compact JSON string.</p>
     *
     * <p>Assumption: the Inspector writes valid JSON to stdout (possibly multi-line). If the
     * output contains non-JSON lines, jq will fail and the assertion in the caller will fire.</p>
     */
    private static String extractEnvelopeViaJq(Path outFile, String stdout, String stderr)
            throws Exception {
        // Tolerant filter:
        //   1. Try .result.structuredContent (MCP spec tools/call with structuredContent)
        //   2. Try .result.content[0].text parsed as JSON (text-only content)
        //   3. Try .structuredContent (top-level structuredContent without .result wrapper)
        //   4. Fall back to . (assume the whole output IS the envelope)
        // All four branches are guarded with // so jq never errors on missing keys.
        // Final guard: select(has("ok")) ensures we got an envelope, not a wrapper.
        String filter = "(.result.structuredContent"
                + " // (.result.content[0]?.text // empty | fromjson?)"
                + " // .structuredContent"
                + " // .) | select(type == \"object\" and has(\"ok\")) | .";

        ProcessBuilder pb = new ProcessBuilder("jq", "-rc", filter, outFile.toString());
        pb.redirectErrorStream(false);

        Path jqOut = Files.createTempFile("jq-envelope-out", ".json");
        Path jqErr = Files.createTempFile("jq-envelope-err", ".txt");
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
        String jqErrMsg = Files.readString(jqErr, StandardCharsets.UTF_8).strip();

        if (proc.exitValue() != 0 || result.isBlank()) {
            // jq failed or produced no output — return raw stdout for a better assertion message
            return stdout.strip();
        }
        // jq may output multiple matches on separate lines; take the first non-blank line.
        for (String line : result.split("\n")) {
            if (!line.isBlank()) return line.strip();
        }
        return stdout.strip();
    }

    /**
     * Run {@code jq -r FILTER} on the given JSON string and return the output.
     * Throws an {@code AssertionError} if jq fails.
     */
    private static String jqExtract(String json, String filter) throws Exception {
        Path jsonFile = Files.createTempFile("jq-input", ".json");
        Files.writeString(jsonFile, json);

        ProcessBuilder pb = new ProcessBuilder("jq", "-r", filter, jsonFile.toString());
        pb.redirectErrorStream(false);

        Path outFile = Files.createTempFile("jq-out", ".txt");
        Path errFile = Files.createTempFile("jq-err", ".txt");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out processing: " + filter);
        }

        String result = Files.readString(outFile, StandardCharsets.UTF_8);
        String err = Files.readString(errFile, StandardCharsets.UTF_8);

        if (proc.exitValue() != 0) {
            throw new AssertionError("jq failed with exit " + proc.exitValue()
                    + " for filter '" + filter + "'\nInput: " + json
                    + "\nError: " + err);
        }
        return result;
    }

    /**
     * Assert that a {@code jq} expression evaluates to {@code "true"}
     * over the given JSON input.
     */
    private static void assertJqExpression(String json, String expression, String description)
            throws Exception {
        String result = jqExtract(json, expression);
        assertThat(result.strip())
                .as(description + "\nJSON: " + json)
                .isEqualTo("true");
    }

    /**
     * Copy a fixture directory from classpath resources to a temp directory.
     * Recursively copies all files under the given classpath resource prefix.
     */
    private static Path copyFixture(Path tempRoot, String resourcePrefix) throws Exception {
        Path target = tempRoot.resolve(resourcePrefix.replace("fixtures/it/", ""));
        copyResourceDirectory(resourcePrefix, target);
        return target;
    }

    /**
     * Recursively copy classpath resources under the given prefix to a target directory.
     * This works by scanning the classpath URL for matching resources.
     */
    private static void copyResourceDirectory(String resourcePrefix, Path targetDir) throws Exception {
        // List known fixture paths by walking through the classpath resource tree.
        // We enumerate files by using getResourceAsStream on well-known paths.
        Files.createDirectories(targetDir);

        // The fixture structure is deterministic, so we enumerate the known files explicitly.
        // This avoids the need to enumerate classpath directories (which is JVM/classpath-loader specific).
        String[] knownFiles = resolveKnownFiles(resourcePrefix);
        for (String relativePath : knownFiles) {
            String fullResourcePath = resourcePrefix + "/" + relativePath;
            try (InputStream is = InspectorAcceptanceIT.class.getClassLoader()
                    .getResourceAsStream(fullResourcePath)) {
                if (is == null) continue;
                Path destFile = targetDir.resolve(relativePath);
                Files.createDirectories(destFile.getParent());
                Files.copy(is, destFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /** Return the known relative file paths for each fixture variant. */
    private static String[] resolveKnownFiles(String resourcePrefix) {
        if (resourcePrefix.contains("passing")) {
            return new String[]{
                    "pom.xml",
                    "src/test/java/dev/nobash/fixture/PassingTest.java"
            };
        } else if (resourcePrefix.contains("failing")) {
            return new String[]{
                    "pom.xml",
                    "src/test/java/dev/nobash/fixture/FailingTest.java"
            };
        } else if (resourcePrefix.contains("compile-fail")) {
            return new String[]{
                    "pom.xml",
                    "src/test/java/dev/nobash/fixture/CompileFailTest.java"
            };
        }
        return new String[0];
    }

    /**
     * Check whether a command is available on the system {@code PATH} by attempting to run it
     * with {@code --version} (or {@code -version} for java). Returns {@code true} if the
     * process launches without an error and exits within 5 seconds.
     */
    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "mvn" -> List.of("mvn", "--version");
                case "npx" -> List.of("npx", "--version");
                case "jq" -> List.of("jq", "--version");
                default -> List.of(command, "--version");
            };
            Process p = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            // Drain output to avoid pipe-buffer deadlock on a blocked process
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /**
     * Probe whether the pinned MCP Inspector version is resolvable via {@code npx}.
     * Runs {@code npx --yes INSPECTOR_VERSION --version} with a timeout.
     * Returns {@code true} if the process terminates within 60 seconds (regardless of exit code,
     * since Inspector 0.14.1 may not support {@code --version} as a stable flag).
     * Returns {@code false} only on timeout or if npx itself is not found.
     */
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

    private static void sendFrame(BufferedWriter writer, String json) throws IOException {
        writer.write(json);
        writer.write("\n");
        writer.flush();
    }

    private static List<String> nonBlankLines(Path file) throws IOException {
        List<String> result = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (!line.isBlank()) result.add(line.strip());
        }
        return result;
    }

    /** A line is JSON-RPC iff it is a JSON object carrying the {@code "jsonrpc":"2.0"} marker. */
    private static boolean looksLikeJsonRpc(String line) {
        String t = line.strip();
        return t.startsWith("{") && t.endsWith("}") && t.contains("\"jsonrpc\"") && t.contains("\"2.0\"");
    }
}
