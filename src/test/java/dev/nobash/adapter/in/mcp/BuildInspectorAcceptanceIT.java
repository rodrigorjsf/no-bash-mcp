package dev.nobash.adapter.in.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Inspector {@code --cli} acceptance test for the {@code build} tool (issue #23, ADR-0009).
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} Maven phase via {@code maven-failsafe-plugin}.
 * It is intentionally <b>NOT</b> a Surefire unit test and MUST NOT run under {@code mvn test}.
 * It has external/network prerequisites (MCP Inspector via {@code npx}, {@code jq}, a Java-25
 * system {@code mvn}, and a pre-packaged jar) that are CI-gated.</p>
 *
 * <h3>Self-skipping</h3>
 * <p>A {@code @BeforeAll} probe calls {@link Assumptions#assumeTrue(boolean)} for each
 * prerequisite. If any prerequisite is absent on the host, the entire test class is
 * <b>SKIPPED, never FAILED</b>.</p>
 *
 * <h3>What is proved</h3>
 * <ol>
 *   <li><b>Compile-fail case</b> — Inspector {@code --cli} + {@code build} on the
 *       {@code fixture-compile-fail} project returns {@code ok=false} with
 *       {@code diagnostics[]} non-empty and each entry having {@code severity="ERROR"}
 *       (asserted via {@code jq}).</li>
 *   <li><b>Passing case</b> — Inspector {@code --cli} + {@code build} on the
 *       {@code fixture-passing} project returns {@code ok=true} with
 *       {@code buildSummary.errors=0} (asserted via {@code jq}).</li>
 * </ol>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class BuildInspectorAcceptanceIT {

    /** Pinned MCP Inspector version (same as InspectorAcceptanceIT). */
    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";

    /** System property injected by Failsafe: the Maven build directory. */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /** Timeout for the inspector subprocess per invocation (seconds). */
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static Path packaged_jar;
    private static Path fixture_passing_dir;
    private static Path fixture_compile_fail_dir;

    @BeforeAll
    static void probe_prerequisites_and_resolve_jar(@TempDir Path tempFixtureRoot) throws Exception {
        // 1. mvn on PATH
        Assumptions.assumeTrue(isOnPath("mvn"),
                "SKIPPED: 'mvn' is not on PATH");

        // 2. npx on PATH
        Assumptions.assumeTrue(isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — MCP Inspector cannot be run");

        // 3. jq on PATH
        Assumptions.assumeTrue(isOnPath("jq"),
                "SKIPPED: 'jq' is not on PATH — JSON assertions cannot run");

        // 4. Inspector resolvable
        boolean inspectorResolvable = probeInspector();
        Assumptions.assumeTrue(inspectorResolvable,
                "SKIPPED: MCP Inspector " + INSPECTOR_VERSION + " is not resolvable via npx");

        // 5. Packaged jar exists
        String buildDir = System.getProperty(BUILD_DIR_PROP);
        Assumptions.assumeTrue(buildDir != null && !buildDir.isBlank(),
                "SKIPPED: system property '" + BUILD_DIR_PROP + "' is not set");

        Path jarPath = Paths.get(buildDir, "no-bash-mcp-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jarPath),
                "SKIPPED: packaged jar not found at " + jarPath);
        packaged_jar = jarPath;

        // 6. target/dependency/ exists
        Path depsDir = Paths.get(buildDir, "dependency");
        Assumptions.assumeTrue(Files.isDirectory(depsDir),
                "SKIPPED: target/dependency/ not found");

        // 7. Copy fixture projects to temp dir
        fixture_passing_dir     = copyFixture(tempFixtureRoot, "fixtures/it/passing");
        fixture_compile_fail_dir = copyFixture(tempFixtureRoot, "fixtures/it/compile-fail");

        Assumptions.assumeTrue(Files.isRegularFile(fixture_passing_dir.resolve("pom.xml")),
                "SKIPPED: fixture/passing pom.xml not found");
        Assumptions.assumeTrue(Files.isRegularFile(fixture_compile_fail_dir.resolve("pom.xml")),
                "SKIPPED: fixture/compile-fail pom.xml not found");
    }

    // ---- AC: compile-fail → ok=false, diagnostics[] non-empty with ERROR entries ----

    @Test
    void inspector_cli_build_on_compile_fail_project_returns_ok_false_with_error_diagnostics()
            throws Exception {
        String envelopeJson = callBuildViaInspector(fixture_compile_fail_dir.toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("compile-fail case: ok must be false")
                .isEqualTo("false");

        // diagnostics[] must be non-null and non-empty
        String diagLength = jqExtract(envelopeJson, ".diagnostics | length");
        int diagCount = Integer.parseInt(diagLength.strip());
        assertThat(diagCount)
                .as("compile-fail case: diagnostics[] must be non-empty")
                .isGreaterThan(0);

        // At least one diagnostic must have severity=ERROR
        assertJqExpression(envelopeJson,
                ".diagnostics | map(.severity) | any(. == \"ERROR\")",
                "compile-fail case: at least one diagnostic must have severity ERROR");

        // No test failures[] (compile errors must not bleed into failures[])
        String failuresValue = jqExtract(envelopeJson, ".failures");
        assertThat(failuresValue.strip())
                .as("compile-fail case: failures[] must be null (compile errors are in diagnostics[])")
                .isEqualTo("null");
    }

    // ---- AC: passing build → ok=true, buildSummary.errors=0 ----

    @Test
    void inspector_cli_build_on_passing_project_returns_ok_true_with_zero_errors()
            throws Exception {
        String envelopeJson = callBuildViaInspector(fixture_passing_dir.toString());

        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("passing case: ok must be true")
                .isEqualTo("true");

        String errorsValue = jqExtract(envelopeJson, ".buildSummary.errors");
        assertThat(errorsValue.strip())
                .as("passing case: buildSummary.errors must be 0")
                .isEqualTo("0");

        // No diagnostics[] on success
        String diagValue = jqExtract(envelopeJson, ".diagnostics");
        assertThat(diagValue.strip())
                .as("passing case: diagnostics[] must be null")
                .isEqualTo("null");
    }

    // ---- helpers ----

    private static String callBuildViaInspector(String fixtureProjectPath) throws Exception {
        List<String> cmd = List.of(
                "npx", "--yes", INSPECTOR_VERSION, "--cli",
                "java", "-jar", packaged_jar.toString(),
                "--method", "tools/call",
                "--tool-name", "build",
                "--tool-arg", "path=" + fixtureProjectPath
        );

        Path outFile = Files.createTempFile("build-inspector-out", ".json");
        Path errFile = Files.createTempFile("build-inspector-err", ".log");

        ProcessBuilder pb = new ProcessBuilder(cmd);
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

        String envelopeJson = extractEnvelopeViaJq(outFile, stdout, stderr);

        assertThat(envelopeJson)
                .as("Inspector output must contain a valid envelope JSON.\n"
                        + "--- stdout ---\n%s\n--- stderr ---\n%s", stdout, stderr)
                .isNotBlank();

        return envelopeJson;
    }

    private static String extractEnvelopeViaJq(Path outFile, String stdout, String stderr)
            throws Exception {
        String filter = "(.result.structuredContent"
                + " // (.result.content[0]?.text // empty | fromjson?)"
                + " // .structuredContent"
                + " // .) | select(type == \"object\" and has(\"ok\")) | .";

        Path jqOut = Files.createTempFile("jq-build-envelope-out", ".json");
        Path jqErr = Files.createTempFile("jq-build-envelope-err", ".txt");

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
        Path jsonFile = Files.createTempFile("jq-build-input", ".json");
        Files.writeString(jsonFile, json);

        Path outFile = Files.createTempFile("jq-build-out", ".txt");
        Path errFile = Files.createTempFile("jq-build-err", ".txt");

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

    private static void assertJqExpression(String json, String expression, String description)
            throws Exception {
        String result = jqExtract(json, expression);
        assertThat(result.strip())
                .as(description + "\nJSON: " + json)
                .isEqualTo("true");
    }

    private static Path copyFixture(Path tempRoot, String resourcePrefix) throws Exception {
        Path target = tempRoot.resolve(resourcePrefix.replace("fixtures/it/", ""));
        copyResourceDirectory(resourcePrefix, target);
        return target;
    }

    private static void copyResourceDirectory(String resourcePrefix, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        String[] knownFiles = resolveKnownFiles(resourcePrefix);
        for (String relativePath : knownFiles) {
            String fullResourcePath = resourcePrefix + "/" + relativePath;
            try (InputStream is = BuildInspectorAcceptanceIT.class.getClassLoader()
                    .getResourceAsStream(fullResourcePath)) {
                if (is == null) continue;
                Path destFile = targetDir.resolve(relativePath);
                Files.createDirectories(destFile.getParent());
                Files.copy(is, destFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String[] resolveKnownFiles(String resourcePrefix) {
        if (resourcePrefix.contains("passing")) {
            return new String[]{
                    "pom.xml",
                    "src/test/java/dev/nobash/fixture/PassingTest.java"
            };
        } else if (resourcePrefix.contains("compile-fail")) {
            return new String[]{
                    "pom.xml",
                    "src/test/java/dev/nobash/fixture/CompileFailTest.java"
            };
        }
        return new String[0];
    }

    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "mvn" -> List.of("mvn", "--version");
                case "npx" -> List.of("npx", "--version");
                case "jq"  -> List.of("jq", "--version");
                default    -> List.of(command, "--version");
            };
            Process p = new ProcessBuilder(probe)
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
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
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
}
