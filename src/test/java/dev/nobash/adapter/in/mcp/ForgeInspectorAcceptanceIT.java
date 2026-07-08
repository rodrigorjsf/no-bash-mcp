package dev.nobash.adapter.in.mcp;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP Inspector {@code --cli} acceptance test for the {@code pr_checks} tool (PRD-6 S1, #99) — the
 * verb driven through the REAL packaged server process end-to-end.
 *
 * <p>This is a <b>Failsafe integration test</b> ({@code *IT.java}), bound to the
 * {@code integration-test}/{@code verify} phase. It is intentionally NOT a Surefire unit test and MUST
 * NOT run under {@code mvn test}. Prerequisites (MCP Inspector via {@code npx}, {@code jq}, a packaged
 * jar) are CI-gated; a {@code @BeforeAll} probe {@code assumeTrue}-skips the whole class when any is
 * absent — <b>SKIPPED, never FAILED</b>.</p>
 *
 * <h3>What is proved</h3>
 * <p>The server, configured with an external forge allowlist ({@code MICRONAUT_CONFIG_FILES}) pointing
 * at a local WireMock forge stub, resolves the {@code repo} override against the allowlist, fetches and
 * folds check-runs + a red Commit Status, and returns {@code ok=false} with a populated
 * {@code prChecks[]} — asserted via {@code jq} on the envelope read off the real process's stdout. The
 * server is spawned with {@code MICRONAUT_ENVIRONMENTS=test} so the {@code http://} stub URL is
 * accepted (the test-profile-only affordance, D69).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ForgeInspectorAcceptanceIT {

    static final String INSPECTOR_VERSION = "@modelcontextprotocol/inspector@0.14.1";
    private static final String BUILD_DIR_PROP = "project.build.directory";
    private static final int INSPECTOR_TIMEOUT_SECONDS = 120;

    private static final String OWNER = "octo";
    private static final String REPO = "hello";
    private static final String REF = "deadbeefcafe";

    private static Path packaged_jar;
    private static Path configFile;
    private static WireMockServer forge;

    @BeforeAll
    static void probe_prerequisites_start_stub_and_resolve_jar() throws Exception {
        // 1. npx on PATH (required to run the pinned MCP Inspector).
        Assumptions.assumeTrue(isOnPath("npx"),
                "SKIPPED: 'npx' is not on PATH — MCP Inspector cannot be run");
        // 2. jq on PATH (required for JSON extraction assertions).
        Assumptions.assumeTrue(isOnPath("jq"),
                "SKIPPED: 'jq' is not on PATH — JSON assertions cannot run");
        // 3. Inspector resolvable.
        Assumptions.assumeTrue(probeInspector(),
                "SKIPPED: MCP Inspector " + INSPECTOR_VERSION + " is not resolvable via npx");
        // 4. Packaged jar + dependency dir exist.
        String buildDir = System.getProperty(BUILD_DIR_PROP);
        Assumptions.assumeTrue(buildDir != null && !buildDir.isBlank(),
                "SKIPPED: system property '" + BUILD_DIR_PROP + "' is not set");
        Path jarPath = Paths.get(buildDir, "no-bash-mcp-0.1.0-SNAPSHOT.jar");
        Assumptions.assumeTrue(Files.isRegularFile(jarPath),
                "SKIPPED: packaged jar not found at " + jarPath);
        Assumptions.assumeTrue(Files.isDirectory(Paths.get(buildDir, "dependency")),
                "SKIPPED: target/dependency/ not found");
        packaged_jar = jarPath;

        // Start the WireMock forge stub and stub a mixed (pass + red-status) fold for the ref.
        forge = new WireMockServer(options().dynamicPort());
        forge.start();
        String base = "/repos/" + OWNER + "/" + REPO + "/commits/" + REF;
        forge.stubFor(get(urlEqualTo(base + "/check-runs"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":1,\"check_runs\":["
                                + "{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"success\","
                                + "\"details_url\":\"https://x/actions/runs/1/job/11\"}]}")));
        forge.stubFor(get(urlEqualTo(base + "/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"failure\",\"statuses\":["
                                + "{\"context\":\"ci/legacy\",\"state\":\"failure\","
                                + "\"target_url\":\"https://x/build/9\"}]}")));

        // External forge allowlist pointing at the http:// stub (accepted only under the test profile).
        configFile = Files.createTempFile("forge-it-config", ".yml");
        Files.writeString(configFile, """
                forge:
                  instances:
                    - base-url: http://127.0.0.1:%d
                """.formatted(forge.port()));
    }

    @AfterAll
    static void stop_stub() {
        if (forge != null) {
            forge.stop();
        }
    }

    @Test
    void inspector_cli_pr_checks_folds_a_red_status_and_returns_ok_false() throws Exception {
        String envelopeJson = callPrChecksViaInspector();

        String ok = jqExtract(envelopeJson, ".ok");
        assertThat(ok.strip())
                .as("a red commit status must flip ok=false")
                .isEqualTo("false");

        String checksLen = jqExtract(envelopeJson, ".prChecks | length");
        assertThat(Integer.parseInt(checksLen.strip()))
                .as("prChecks[] must fold the check-run and the commit status")
                .isGreaterThanOrEqualTo(2);

        String failuresLen = jqExtract(envelopeJson, ".failures | length");
        assertThat(Integer.parseInt(failuresLen.strip()))
                .as("the red status is a ContainerFinding in failures[]")
                .isGreaterThanOrEqualTo(1);
    }

    // ---- helpers ----

    private static String callPrChecksViaInspector() throws Exception {
        List<String> cmd = new ArrayList<>(List.of(
                "npx", "--yes", INSPECTOR_VERSION, "--cli",
                "java", "-jar", packaged_jar.toString(),
                "--method", "tools/call",
                "--tool-name", "pr_checks",
                "--tool-arg", "repo=" + OWNER + "/" + REPO,
                "--tool-arg", "ref=" + REF));

        Path outFile = Files.createTempFile("forge-inspector-out", ".json");
        Path errFile = Files.createTempFile("forge-inspector-err", ".log");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        // The spawned server reads the forge allowlist from MICRONAUT_CONFIG_FILES; the test profile
        // enables the http:// stub affordance (D69).
        pb.environment().put("MICRONAUT_CONFIG_FILES", configFile.toString());
        pb.environment().put("MICRONAUT_ENVIRONMENTS", "test");
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
                .as("Inspector output must contain a valid pr_checks envelope.\n"
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

        Path jqOut = Files.createTempFile("jq-forge-envelope-out", ".json");
        Path jqErr = Files.createTempFile("jq-forge-envelope-err", ".txt");

        ProcessBuilder pb = new ProcessBuilder("jq", "-rc", filter, outFile.toString());
        pb.redirectOutput(jqOut.toFile());
        pb.redirectError(jqErr.toFile());

        Process proc = pb.start();
        boolean exited = proc.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out extracting envelope.\n--- inspector stdout ---\n" + stdout);
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
        Path jsonFile = Files.createTempFile("jq-forge-input", ".json");
        Files.writeString(jsonFile, json);

        Path outFile = Files.createTempFile("jq-forge-out", ".txt");
        Path errFile = Files.createTempFile("jq-forge-err", ".txt");

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
            throw new AssertionError("jq failed for filter '" + filter + "'\nInput: " + json
                    + "\nError: " + Files.readString(errFile, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static boolean isOnPath(String command) {
        try {
            Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true).start();
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
                    .redirectErrorStream(true).start();
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
