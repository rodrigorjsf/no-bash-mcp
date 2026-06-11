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
 * MCP Inspector {@code --cli} acceptance test for the Go {@code run_tests} adapter (PRD-3 slice 2),
 * driving a REAL Go project end-to-end through the packaged JVM server.
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} Maven phase — intentionally NOT a Surefire unit test, and
 * it MUST NOT run under {@code mvn test}. It NAMES a CI dependency the Maven unit gate does not
 * carry: a <b>Go toolchain</b> ({@code go} on PATH), in addition to the existing MCP-Inspector
 * prerequisites ({@code npx}, {@code jq}, a packaged jar).</p>
 *
 * <h3>Self-skipping</h3>
 * <p>A {@code @BeforeAll} probe calls {@link Assumptions#assumeTrue(boolean)} for each prerequisite
 * — {@code go}, {@code npx}, {@code jq}, the pinned Inspector, the packaged jar + {@code dependency/}.
 * If ANY is absent the entire class is <b>SKIPPED, never FAILED</b>, so it can never break a runner
 * that lacks the Go toolchain. (The implementer cannot run this leg — there is no Failsafe/Bash
 * access in-slice; it is authored to self-skip and run only in a Go-equipped CI.)</p>
 *
 * <h3>What is proved</h3>
 * <p>Inspector {@code --cli} + {@code run_tests} against a real Go project (a passing {@code TestAdd}
 * and a deliberately-failing {@code TestSubtract}) returns an {@code ok=false} envelope whose
 * {@code failures[]} carries a {@code kind="test"} finding — the Go NDJSON folded into the SAME
 * universal schema as Maven, asserted with {@code jq -e}.</p>
 *
 * @see InspectorAcceptanceIT the Maven sibling this mirrors (jar launch, jq extraction, probes)
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoInspectorAcceptanceIT {

    /** Pinned MCP Inspector version — kept in lockstep with {@link InspectorAcceptanceIT}. */
    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";

    /** System property name injected by the Failsafe plugin: the Maven build directory. */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /** Timeout for the inspector subprocess per invocation (seconds). */
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static Path packaged_jar;
    private static Path go_project_dir;

    @BeforeAll
    static void probe_prerequisites_and_resolve_jar(@TempDir Path tempFixtureRoot) throws Exception {
        // ---- prerequisite probes (self-skipping on absence) ----

        // 1. The Go toolchain — the CI dependency this leg names. Without `go` there is nothing to run.
        Assumptions.assumeTrue(
                isOnPath("go"),
                "SKIPPED: 'go' is not on PATH — the Go toolchain is required to run a real go test project");

        // 2. `npx` (to run the pinned MCP Inspector).
        Assumptions.assumeTrue(
                isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — MCP Inspector cannot be run");

        // 3. `jq` (for the JSON-extraction assertions).
        Assumptions.assumeTrue(
                isOnPath("jq"),
                "SKIPPED: 'jq' is not on PATH — JSON assertions cannot run");

        // 4. The pinned Inspector is resolvable via npx.
        Assumptions.assumeTrue(
                probeInspector(),
                "SKIPPED: MCP Inspector " + INSPECTOR_VERSION + " is not resolvable via npx (timed out)");

        // 5. Locate the packaged jar (Failsafe injects project.build.directory).
        String buildDir = System.getProperty(BUILD_DIR_PROP);
        Assumptions.assumeTrue(
                buildDir != null && !buildDir.isBlank(),
                "SKIPPED: system property '" + BUILD_DIR_PROP + "' is not set (run via mvn verify, not directly)");

        Path jarPath = Paths.get(buildDir, "no-bash-mcp-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(
                Files.isRegularFile(jarPath),
                "SKIPPED: packaged jar not found at " + jarPath + " — run 'mvn -DskipTests package' first");
        packaged_jar = jarPath;

        Path depsDir = Paths.get(buildDir, "dependency");
        Assumptions.assumeTrue(
                Files.isDirectory(depsDir),
                "SKIPPED: target/dependency/ not found — run 'mvn -DskipTests package' to copy-dependencies");

        // 6. Extract the real Go fixture project (go.mod + calc.go + calc_test.go) into the temp dir.
        go_project_dir = copyGoProject(tempFixtureRoot);
        Assumptions.assumeTrue(
                Files.isRegularFile(go_project_dir.resolve("go.mod")),
                "SKIPPED: go fixture go.mod not found");
        Assumptions.assumeTrue(
                Files.isRegularFile(go_project_dir.resolve("calc_test.go")),
                "SKIPPED: go fixture calc_test.go not found");
    }

    // ---- the Go failing-project acceptance leg → ok=false with a failures[].kind="test" finding ----

    @Test
    void inspector_cli_run_tests_on_a_real_go_project_returns_ok_false_with_a_test_failure_kind()
            throws Exception {
        String envelopeJson = callRunTestsViaInspector(go_project_dir.toString());

        // ok=false — TestSubtract fails on purpose.
        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("go failing-project case: ok must be false")
                .isEqualTo("false");

        // failures[] is non-empty.
        String failuresLength = jqExtract(envelopeJson, ".failures | length");
        assertThat(Integer.parseInt(failuresLength.strip()))
                .as("go failing-project case: failures[] must be non-empty")
                .isGreaterThan(0);

        // every failure carries the 'kind' discriminator (the SAME sealed-Finding shape as Maven),
        // and at least one is a "test" finding (the failing TestSubtract). jq -e sets exit code by
        // the result's truthiness — a non-true result fails the assertion below.
        assertJqTrue(envelopeJson, ".failures | map(.kind) | all(. != null)",
                "go: every failure must carry a non-null 'kind' discriminator");
        assertJqTrue(envelopeJson, ".failures | any(.kind == \"test\")",
                "go: at least one failure is a leaf test finding (TestSubtract)");

        // the manager is `go` — the SELECTED ecosystem is reported in the envelope.
        String manager = jqExtract(envelopeJson, ".manager");
        assertThat(manager.strip())
                .as("go: the envelope reports the selected manager")
                .isEqualTo("go");
    }

    // ---- helpers (mirror InspectorAcceptanceIT) ----

    private static String callRunTestsViaInspector(String projectPath) throws Exception {
        List<String> cmd = List.of(
                "npx", "--yes", INSPECTOR_VERSION, "--cli",
                "java", "-jar", packaged_jar.toString(),
                "--method", "tools/call",
                "--tool-name", "run_tests",
                "--tool-arg", "path=" + projectPath
        );

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);

        Path outFile = Files.createTempFile("go-inspector-out", ".json");
        Path errFile = Files.createTempFile("go-inspector-err", ".log");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(INSPECTOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("MCP Inspector timed out after " + INSPECTOR_TIMEOUT_SECONDS
                    + "s.\n--- stderr ---\n" + Files.readString(errFile));
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

        ProcessBuilder pb = new ProcessBuilder("jq", "-rc", filter, outFile.toString());
        pb.redirectErrorStream(false);

        Path jqOut = Files.createTempFile("go-jq-envelope-out", ".json");
        Path jqErr = Files.createTempFile("go-jq-envelope-err", ".txt");
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
        Path jsonFile = Files.createTempFile("go-jq-input", ".json");
        Files.writeString(jsonFile, json);

        ProcessBuilder pb = new ProcessBuilder("jq", "-r", filter, jsonFile.toString());
        pb.redirectErrorStream(false);

        Path outFile = Files.createTempFile("go-jq-out", ".txt");
        Path errFile = Files.createTempFile("go-jq-err", ".txt");
        pb.redirectOutput(outFile.toFile());
        pb.redirectError(errFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out processing: " + filter);
        }

        String result = Files.readString(outFile, StandardCharsets.UTF_8);
        if (proc.exitValue() != 0) {
            throw new AssertionError("jq failed with exit " + proc.exitValue()
                    + " for filter '" + filter + "'\nInput: " + json
                    + "\nError: " + Files.readString(errFile, StandardCharsets.UTF_8));
        }
        return result;
    }

    /** Assert a jq expression is truthy via {@code jq -e} (exit code reflects the result). */
    private static void assertJqTrue(String json, String expression, String description)
            throws Exception {
        Path jsonFile = Files.createTempFile("go-jqe-input", ".json");
        Files.writeString(jsonFile, json);

        ProcessBuilder pb = new ProcessBuilder("jq", "-e", expression, jsonFile.toString());
        pb.redirectErrorStream(true);
        Path outFile = Files.createTempFile("go-jqe-out", ".txt");
        pb.redirectOutput(outFile.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq -e timed out: " + expression);
        }
        // jq -e exits 0 when the last output value is neither false nor null.
        assertThat(proc.exitValue())
                .as(description + "\nJSON: " + json + "\njq output: " + Files.readString(outFile))
                .isZero();
    }

    /** Copy the real Go fixture project (go.mod + calc.go + calc_test.go) from the classpath. */
    private static Path copyGoProject(Path tempRoot) throws Exception {
        Path target = tempRoot.resolve("go-project");
        Files.createDirectories(target);
        for (String name : List.of("go.mod", "calc.go", "calc_test.go")) {
            String resource = "fixtures/go/project/" + name;
            try (InputStream is = GoInspectorAcceptanceIT.class.getClassLoader()
                    .getResourceAsStream(resource)) {
                if (is == null) {
                    throw new IllegalStateException("missing Go fixture resource: " + resource);
                }
                Files.copy(is, target.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    /** Whether a command resolves on PATH (probes {@code <cmd> version}/{@code --version}). */
    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "go" -> List.of("go", "version");
                case "npx" -> List.of("npx", "--version");
                case "jq" -> List.of("jq", "--version");
                default -> List.of(command, "--version");
            };
            Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Whether the pinned MCP Inspector resolves via npx (any termination within the timeout). */
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
