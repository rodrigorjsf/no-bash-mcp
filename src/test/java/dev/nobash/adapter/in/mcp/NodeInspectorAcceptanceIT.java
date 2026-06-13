package dev.nobash.adapter.in.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Inspector {@code --cli} acceptance test for the Node {@code run_tests} adapter (PRD-3 slice 4),
 * driving a REAL jest project end-to-end through the packaged JVM server.
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} Maven phase — intentionally NOT a Surefire unit test, and
 * it MUST NOT run under {@code mvn test}. It NAMES the CI dependencies the Maven unit gate does not
 * carry: a <b>Node toolchain</b> ({@code node} + {@code npx} on PATH), {@code jq}, the packaged jar,
 * AND — the Node-specific one — a <b>pre-installed real jest project</b> whose path is supplied via
 * the {@value #JEST_PROJECT_PROP} system property and whose {@code node_modules/.bin/jest} already
 * exists.</p>
 *
 * <h3>The {@code --no-install} invariant dictates a pre-installed project</h3>
 * <p>{@code run_tests} drives jest with {@code --no-install} (D38): it NEVER network-fetches a
 * missing framework — a missing {@code node_modules/.bin/jest} is a preflight
 * {@code DEPS_NOT_INSTALLED}, not an implicit download. So, unlike the Go IT (which copies a fixture
 * project and lets {@code go test} compile it on the fly with no pre-install step), this leg CANNOT
 * synthesize its project from committed sources: a jest run needs {@code node_modules} present
 * BEFORE the run. Committing {@code node_modules} is untenable, and running {@code npm install}
 * inside the test would add un-exercised network surface. The CI workflow therefore installs a real
 * jest project as a SEPARATE step and points this IT at it via {@value #JEST_PROJECT_PROP}; the
 * project must contain a deliberately-FAILING test (the jest analogue of the Go fixture's
 * intentionally-failing {@code TestSubtract}) so the envelope is {@code ok=false} with a
 * {@code failures[].kind=="test"} finding.</p>
 *
 * <h3>Self-skipping</h3>
 * <p>A {@code @BeforeAll} probe calls {@link Assumptions#assumeTrue(boolean)} for each prerequisite
 * — {@code node}, {@code npx}, {@code jq}, the pinned Inspector, the packaged jar + {@code dependency/},
 * and the supplied jest project dir (set, a directory, with {@code node_modules/.bin/jest} present).
 * If ANY is absent the entire class is <b>SKIPPED, never FAILED</b>, so it can never break a runner
 * that lacks the Node toolchain or the pre-installed project. (The implementer cannot run this leg —
 * there is no Failsafe/Bash access in-slice; it is authored to self-skip and run only in a
 * Node-equipped CI with a pre-installed jest project.)</p>
 *
 * <h3>What is proved</h3>
 * <p>Inspector {@code --cli} + {@code run_tests} against the real jest project returns an
 * {@code ok=false} envelope whose {@code failures[]} carries a {@code kind="test"} finding — the
 * jest JSON folded into the SAME universal schema as Maven and Go, asserted with {@code jq -e}. The
 * envelope's {@code manager} is {@code "npx"} (the trusted PATH-resolved launcher jest is driven
 * through, ADR-0008 — NOT {@code "jest"}, which is the Reporter, and NOT {@code "go"}/{@code "mvn"}).</p>
 *
 * @see InspectorAcceptanceIT the Maven sibling this mirrors (jar launch, jq extraction, probes)
 * @see GoInspectorAcceptanceIT the Go sibling whose structure this replicates
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NodeInspectorAcceptanceIT {

    /** Pinned MCP Inspector version — kept in lockstep with {@link InspectorAcceptanceIT}. */
    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";

    /** System property name injected by the Failsafe plugin: the Maven build directory. */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /**
     * System property naming a PRE-INSTALLED real jest project directory (with {@code node_modules}
     * already populated). The CI workflow installs a jest project carrying a deliberately-failing
     * test and passes its path here; absent → the leg self-skips. This is the Node analogue of the
     * Go IT's committed fixture project, except a jest project cannot be committed (its
     * {@code node_modules} must be installed out-of-band, since {@code run_tests} runs jest with
     * {@code --no-install}).
     */
    static final String JEST_PROJECT_PROP = "nbm.it.jest.project.dir";

    /** Timeout for the inspector subprocess per invocation (seconds). */
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static Path packaged_jar;
    private static Path jest_project_dir;

    @BeforeAll
    static void probe_prerequisites_and_resolve_jar() throws Exception {
        // ---- prerequisite probes (self-skipping on absence) ----

        // 1. The Node runtime — the CI dependency this leg names. Without `node` there is no jest.
        Assumptions.assumeTrue(
                isOnPath("node"),
                "SKIPPED: 'node' is not on PATH — the Node toolchain is required to run a real jest project");

        // 2. `npx` (the trusted launcher run_tests drives jest through, and that runs the Inspector).
        Assumptions.assumeTrue(
                isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — jest cannot be launched and MCP Inspector cannot be run");

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

        // 6. The pre-installed jest project (the --no-install invariant requires node_modules present).
        String jestProjectDir = System.getProperty(JEST_PROJECT_PROP);
        Assumptions.assumeTrue(
                jestProjectDir != null && !jestProjectDir.isBlank(),
                "SKIPPED: system property '" + JEST_PROJECT_PROP + "' is not set — point it at a "
                        + "pre-installed jest project (with node_modules) carrying a failing test");
        Path projectDir = Paths.get(jestProjectDir);
        Assumptions.assumeTrue(
                Files.isDirectory(projectDir),
                "SKIPPED: jest project dir '" + jestProjectDir + "' is not a directory");
        // node_modules/.bin/jest MUST already exist — run_tests runs with --no-install and never fetches.
        Path jestBin = projectDir.resolve("node_modules").resolve(".bin").resolve("jest");
        Path jestBinCmd = projectDir.resolve("node_modules").resolve(".bin").resolve("jest.cmd");
        Assumptions.assumeTrue(
                Files.exists(jestBin) || Files.exists(jestBinCmd),
                "SKIPPED: node_modules/.bin/jest not found under '" + jestProjectDir + "' — the jest "
                        + "project must be installed out-of-band (run_tests runs jest with --no-install)");
        jest_project_dir = projectDir;
    }

    // ---- the jest failing-project acceptance leg → ok=false with a failures[].kind="test" finding ----

    @Test
    void inspector_cli_run_tests_on_a_real_jest_project_returns_ok_false_with_a_test_failure_kind()
            throws Exception {
        String envelopeJson = callRunTestsViaInspector(jest_project_dir.toString());

        // ok=false — the jest project carries a deliberately-failing test.
        String okValue = jqExtract(envelopeJson, ".ok");
        assertThat(okValue.strip())
                .as("jest failing-project case: ok must be false")
                .isEqualTo("false");

        // failures[] is non-empty.
        String failuresLength = jqExtract(envelopeJson, ".failures | length");
        assertThat(Integer.parseInt(failuresLength.strip()))
                .as("jest failing-project case: failures[] must be non-empty")
                .isGreaterThan(0);

        // every failure carries the 'kind' discriminator (the SAME sealed-Finding shape as Maven/Go),
        // and at least one is a "test" finding (the failing assertion). jq -e sets exit code by the
        // result's truthiness — a non-true result fails the assertion below.
        assertJqTrue(envelopeJson, ".failures | map(.kind) | all(. != null)",
                "jest: every failure must carry a non-null 'kind' discriminator");
        assertJqTrue(envelopeJson, ".failures | any(.kind == \"test\")",
                "jest: at least one failure is a leaf test finding (the deliberately-failing assertion)");

        // the manager is `npx` — the trusted PATH-resolved launcher jest is driven through (ADR-0008),
        // which is what NodeEcosystemAdapter.managerBinary() reports into the envelope (NOT "jest").
        String manager = jqExtract(envelopeJson, ".manager");
        assertThat(manager.strip())
                .as("jest: the envelope reports the trusted launcher (npx) as the manager")
                .isEqualTo("npx");
    }

    // ---- helpers (mirror InspectorAcceptanceIT / GoInspectorAcceptanceIT) ----

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

        Path outFile = Files.createTempFile("node-inspector-out", ".json");
        Path errFile = Files.createTempFile("node-inspector-err", ".log");
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

        Path jqOut = Files.createTempFile("node-jq-envelope-out", ".json");
        Path jqErr = Files.createTempFile("node-jq-envelope-err", ".txt");
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
        Path jsonFile = Files.createTempFile("node-jq-input", ".json");
        Files.writeString(jsonFile, json);

        ProcessBuilder pb = new ProcessBuilder("jq", "-r", filter, jsonFile.toString());
        pb.redirectErrorStream(false);

        Path outFile = Files.createTempFile("node-jq-out", ".txt");
        Path errFile = Files.createTempFile("node-jq-err", ".txt");
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
        Path jsonFile = Files.createTempFile("node-jqe-input", ".json");
        Files.writeString(jsonFile, json);

        ProcessBuilder pb = new ProcessBuilder("jq", "-e", expression, jsonFile.toString());
        pb.redirectErrorStream(true);
        Path outFile = Files.createTempFile("node-jqe-out", ".txt");
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

    /** Whether a command resolves on PATH (probes {@code <cmd> --version}). */
    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "node" -> List.of("node", "--version");
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
