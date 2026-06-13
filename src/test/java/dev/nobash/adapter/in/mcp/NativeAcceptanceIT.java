package dev.nobash.adapter.in.mcp;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Native acceptance IT for the {@code --static-nolibc} linux-x64 binary (PRD-4 S1 / #59) — the
 * <b>integration-proof harness</b> the tracer lands. It drives the REAL native binary
 * ({@code target/no-bash-mcp}, a GraalVM CE 25 JDK-25 image) directly over STDIO JSON-RPC — NOT via
 * the MCP Inspector, NOT {@code java -jar} — and proves the three legs #58 de-risked plus the
 * polymorphic {@link dev.nobash.domain.result.ContainerFinding} branch, all over the native serde:
 *
 * <ol>
 *   <li><b>Maven leg</b> — {@code run_tests} spawns a real {@code mvn} on a failing fixture →
 *       {@code ok=false} with a {@code failures[].kind="test"} finding (report-dir fix, #64).</li>
 *   <li><b>Go test-failure leg</b> — {@code run_tests} spawns a real {@code go test} (a failing
 *       {@code TestSubtract}) → {@code ok=false}, {@code kind="test"}.</li>
 *   <li><b>Go build-failure leg</b> — {@code run_tests} on a package whose test does not compile →
 *       {@code ok=false} with a {@code kind="container"} finding (ContainerFinding(PACKAGE,ERRORED),
 *       ADR-0007 axis 5) — the polymorphic sibling #58 never exercised over native serde.</li>
 *   <li><b>npm leg</b> — {@code run_tests} spawns {@code npx jest --no-install} on a pre-installed
 *       jest project → {@code ok=false}, {@code kind="test"}, {@code manager="npx"}.</li>
 * </ol>
 *
 * <p>A fifth test asserts the §7 stdout-hygiene contract holds NATIVELY: every stdout line stays
 * JSON-RPC while {@code ProcessBuilder} forks a subprocess.</p>
 *
 * <h3>Fail-closed (the anti-false-green spine, G5/D28)</h3>
 * <p>Unlike the JVM Inspector ITs (which {@code assumeTrue}-self-skip on any absent prerequisite),
 * this IT is the native <b>release gate</b>: under the {@code native} Maven profile it runs with
 * {@code -Dnbm.native.required=true}, and then a MISSING binary or a missing per-leg toolchain is a
 * HARD FAILURE — never a silent skip. On the ordinary JVM build ({@code nbm.native.required=false},
 * no binary present) the whole class self-skips so it never breaks the JVM gate. The npm leg
 * additionally requires the pre-installed jest project (the {@code --no-install} invariant, D38)
 * supplied via {@value #JEST_PROJECT_PROP}; CI installs it out-of-band.</p>
 *
 * <h3>How to run</h3>
 * <pre>
 *   # Build the binary and run this gate in one lifecycle (the CI command):
 *   mvn -B -Dpackaging=native-image verify
 *   # Fast local loop against an already-built binary (skips the 2-min native rebuild):
 *   mvn -B -o -Dnbm.native.required=true -Dproject.build.directory=$PWD/target \
 *       -Dit.test=NativeAcceptanceIT test-compile failsafe:integration-test failsafe:verify
 * </pre>
 *
 * @see InspectorAcceptanceIT the JVM sibling whose jq-extraction helpers this mirrors
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NativeAcceptanceIT {

    /** System property naming the Maven build directory (Failsafe injects it). */
    private static final String BUILD_DIR_PROP = "project.build.directory";

    /**
     * Fail-closed switch. The {@code native} profile sets it {@code true} so an absent binary or
     * toolchain HARD-FAILS this release gate; the JVM build leaves it {@code false} so the class
     * self-skips. Read once in {@link #resolve_native_binary()}.
     */
    private static final String NATIVE_REQUIRED_PROP = "nbm.native.required";

    /**
     * System property naming a PRE-INSTALLED real jest project (with {@code node_modules}). CI
     * installs the committed {@code fixtures/jest/project} out-of-band ({@code npm install}) and
     * passes its path here — {@code run_tests} runs jest with {@code --no-install} and never fetches.
     */
    static final String JEST_PROJECT_PROP = "nbm.it.jest.project.dir";

    /**
     * The native image base name (pom {@code <imageName>}). GraalVM appends {@code .exe} on Windows,
     * so {@link #resolve_native_binary()} resolves the OS-specific filename.
     */
    private static final String BINARY_NAME = "no-bash-mcp";

    /**
     * On Windows the {@code mvn} and {@code npx} legs are SKIPPED, not run: their launchers are
     * {@code .cmd} shims that {@code ProcessBuilder} cannot spawn without a shell, which the ADR-0008
     * trusted-launcher posture forbids ({@code CreateProcess} appends only {@code .exe}, never
     * consults {@code PATHEXT}). This is a documented, fail-clear Windows limitation (#62/G13) — the
     * {@code go} legs (which use {@code go.exe}) plus spawn + serde + stdout purity still run and
     * must pass. The skip is explicit (a visible JUnit assumption), never a silent green.
     */
    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static Path native_binary;
    private static boolean native_required;

    @BeforeAll
    static void resolve_native_binary() {
        native_required = Boolean.getBoolean(NATIVE_REQUIRED_PROP);

        String buildDir = System.getProperty(BUILD_DIR_PROP);
        gate(buildDir != null && !buildDir.isBlank(),
                "system property '" + BUILD_DIR_PROP + "' is not set (run via mvn verify, not a bare goal)");

        String binaryName = IS_WINDOWS ? BINARY_NAME + ".exe" : BINARY_NAME;
        Path binary = Paths.get(buildDir, binaryName);
        gate(Files.isRegularFile(binary) && Files.isExecutable(binary),
                "native binary not found / not executable at " + binary
                        + " — build it with `mvn -Dpackaging=native-image package` before verify");
        native_binary = binary;
    }

    // ---- Leg 1: Maven → ok=false, kind="test" ------------------------------------------------

    @Test
    void native_run_tests_on_a_failing_maven_project_returns_ok_false_with_a_test_finding()
            throws Exception {
        Assumptions.assumeFalse(IS_WINDOWS,
                "SKIPPED on Windows: `mvn` is an `mvn.cmd` shim the native binary cannot spawn without a "
                        + "shell (ADR-0008 forbids one); documented fail-clear Windows limitation (#62/G13)");
        gate(isOnPath("mvn"), "'mvn' is not on PATH — the Maven leg cannot spawn a build");
        Path project = copyMavenFixture("failing", "FailingTest.java");

        String envelope = runTests(project, 300).envelope();

        assertEnvelopeOkIsFalse(envelope, "maven");
        assertFailuresNonEmptyWithKind(envelope, "test", "maven: a failing JUnit test is a leaf test finding");
        assertManager(envelope, "mvn");
    }

    // ---- Leg 2: Go test failure → ok=false, kind="test" --------------------------------------

    @Test
    void native_run_tests_on_a_failing_go_project_returns_ok_false_with_a_test_finding()
            throws Exception {
        gate(isOnPath("go"), "'go' is not on PATH — the Go leg cannot spawn `go test`");
        Path project = copyGoFixture("project");

        String envelope = runTests(project, 180).envelope();

        assertEnvelopeOkIsFalse(envelope, "go-test");
        assertFailuresNonEmptyWithKind(envelope, "test", "go: the failing TestSubtract is a leaf test finding");
        assertManager(envelope, "go");
    }

    // ---- Leg 3: Go build failure → ok=false, kind="container" (the polymorphic sibling) -------

    @Test
    void native_run_tests_on_a_go_build_failure_returns_ok_false_with_a_container_finding()
            throws Exception {
        gate(isOnPath("go"), "'go' is not on PATH — the Go leg cannot spawn `go test`");
        Path project = copyGoFixture("compile-error");

        String envelope = runTests(project, 180).envelope();

        assertEnvelopeOkIsFalse(envelope, "go-build");
        // A package that fails to BUILD has no test owner → ContainerFinding(PACKAGE,ERRORED),
        // kind="container" — the ADR-0007 axis-5 type #58 never round-tripped over native serde.
        assertFailuresNonEmptyWithKind(envelope, "container",
                "go: a build failure folds into a no-owner ContainerFinding, not a degenerate test");
        assertManager(envelope, "go");
    }

    // ---- Leg 4: npm/jest → ok=false, kind="test", manager="npx" ------------------------------

    @Test
    void native_run_tests_on_a_failing_jest_project_returns_ok_false_with_a_test_finding()
            throws Exception {
        Assumptions.assumeFalse(IS_WINDOWS,
                "SKIPPED on Windows: `npx` is an `npx.cmd` shim the native binary cannot spawn without a "
                        + "shell (ADR-0008 forbids one); documented fail-clear Windows limitation (#62/G13)");
        gate(isOnPath("node"), "'node' is not on PATH — the npm leg needs the Node toolchain");
        gate(isOnPath("npx"), "'npx' is not on PATH — jest is launched through npx (ADR-0008)");
        Path project = resolvePreinstalledJestProject();

        String envelope = runTests(project, 240).envelope();

        assertEnvelopeOkIsFalse(envelope, "jest");
        assertFailuresNonEmptyWithKind(envelope, "test", "jest: the deliberately-failing assertion is a leaf test finding");
        // The trusted PATH-resolved launcher jest is driven through (ADR-0008) — NOT "jest"/"go"/"mvn".
        assertManager(envelope, "npx");
    }

    // ---- Leg 5: stdout stays pure JSON-RPC while a subprocess forks (§7 hygiene, natively) -----

    @Test
    void native_binary_stdout_carries_only_jsonrpc_while_a_subprocess_runs() throws Exception {
        gate(isOnPath("go"), "'go' is not on PATH — need a real subprocess to exercise the channel");
        Path project = copyGoFixture("project");

        List<String> stdout = runTests(project, 180).stdoutLines();

        assertThat(stdout)
                .as("stdout must carry at least the initialize and run_tests responses")
                .isNotEmpty();
        List<String> offenders = new ArrayList<>();
        for (String line : stdout) {
            if (!looksLikeJsonRpc(line)) offenders.add(line);
        }
        assertThat(offenders)
                .as("every stdout line from the native binary must be a JSON-RPC message; offenders: %s", offenders)
                .isEmpty();
    }

    // ========================================================================================
    // Driving the native binary over STDIO (a Java port of #58's p0_run_tests_smoke.py).
    // ========================================================================================

    /** The captured result of one {@code run_tests} round-trip against the native binary. */
    private record NativeRun(String envelope, List<String> stdoutLines) {
    }

    /**
     * Start the native binary, perform the MCP handshake, send one {@code run_tests} for
     * {@code projectDir}, and return the extracted Envelope JSON plus every stdout line.
     */
    private static NativeRun runTests(Path projectDir, int runTestsTimeoutSeconds) throws Exception {
        List<String> stdoutLines = Collections.synchronizedList(new ArrayList<>());
        List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());

        Process proc = new ProcessBuilder(native_binary.toString()).start();
        Thread outPump = drain(proc.getInputStream(), stdoutLines);
        Thread errPump = drain(proc.getErrorStream(), stderrLines);

        try (BufferedWriter stdin = new BufferedWriter(
                new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8))) {
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                    + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"native-acceptance-it\",\"version\":\"0\"}}}");
            awaitResponseId(stdoutLines, 1, 20);
            send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                    + "\"name\":\"run_tests\",\"arguments\":{\"path\":\"" + projectDir.toString().replace("\\", "\\\\")
                    + "\",\"timeout\":" + runTestsTimeoutSeconds + "}}}");
            boolean got = awaitResponseId(stdoutLines, 2, runTestsTimeoutSeconds + 30);
            if (!got) {
                proc.destroyForcibly();
                throw new AssertionError("native run_tests (id=2) produced no response within "
                        + (runTestsTimeoutSeconds + 30) + "s\n--- stderr ---\n" + String.join("\n", stderrLines)
                        + "\n--- stdout ---\n" + String.join("\n", stdoutLines));
            }
        }

        if (!proc.waitFor(15, TimeUnit.SECONDS)) {
            proc.destroy();
            if (!proc.waitFor(5, TimeUnit.SECONDS)) proc.destroyForcibly();
        }
        outPump.join(2000);
        errPump.join(2000);

        List<String> snapshot = new ArrayList<>(stdoutLines);
        String envelope = extractEnvelope(snapshot, stderrLines);
        return new NativeRun(envelope, snapshot);
    }

    private static Thread drain(InputStream stream, List<String> sink) {
        Thread t = new Thread(() -> {
            // Read LINE BY LINE (not readAllBytes, which blocks until the process exits) so each
            // JSON-RPC frame lands in `sink` as it is emitted — awaitResponseId polls this list live.
            try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isBlank()) sink.add(line.strip());
                }
            } catch (IOException ignored) {
                // stream closed on process exit — the lines collected so far are what we assert on
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void send(BufferedWriter stdin, String json) throws IOException {
        stdin.write(json);
        stdin.write("\n");
        stdin.flush();
    }

    /** Poll the live stdout for a JSON-RPC response carrying the given id (a result or error). */
    private static boolean awaitResponseId(List<String> stdoutLines, int id, int timeoutSeconds)
            throws InterruptedException {
        String needle = "\"id\":" + id;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            synchronized (stdoutLines) {
                for (String line : stdoutLines) {
                    if (line.contains(needle) && (line.contains("\"result\"") || line.contains("\"error\""))) {
                        return true;
                    }
                }
            }
            Thread.sleep(100);
        }
        return false;
    }

    /**
     * Extract the Envelope JSON from the {@code id=2} run_tests response among the stdout lines,
     * using {@code jq} (the proven extraction the sibling Inspector ITs use). Handles the
     * {@code result.structuredContent} and {@code result.content[0].text} MCP shapes.
     */
    private static String extractEnvelope(List<String> stdoutLines, List<String> stderrLines) throws Exception {
        Path jsonl = Files.createTempFile("native-stdout", ".jsonl");
        Files.writeString(jsonl, String.join("\n", stdoutLines) + "\n");

        String filter = "select((.id // empty) == 2)"
                + " | (.result.structuredContent // (.result.content[0]?.text // empty | fromjson?) // empty)"
                + " | select(type == \"object\" and has(\"ok\"))";

        ProcessBuilder pb = new ProcessBuilder("jq", "-rc", "-f", jqProgram(filter).toString(), jsonl.toString());
        Path out = Files.createTempFile("native-jq-out", ".json");
        Path err = Files.createTempFile("native-jq-err", ".txt");
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process proc = pb.start();
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out extracting the envelope");
        }
        String result = Files.readString(out, StandardCharsets.UTF_8).strip();
        assertThat(result)
                .as("the run_tests (id=2) response must carry a structured Envelope.\n"
                        + "--- stdout ---\n%s\n--- stderr ---\n%s\n--- jq err ---\n%s",
                        String.join("\n", stdoutLines), String.join("\n", stderrLines), Files.readString(err))
                .isNotBlank();
        for (String line : result.split("\n")) {
            if (!line.isBlank()) return line.strip();
        }
        return result;
    }

    // ---- envelope assertions (jq, mirroring InspectorAcceptanceIT) ---------------------------

    private static void assertEnvelopeOkIsFalse(String envelope, String leg) throws Exception {
        assertThat(jq(envelope, ".ok").strip())
                .as(leg + ": ok must be false (the fixture carries a deliberate failure)")
                .isEqualTo("false");
    }

    private static void assertFailuresNonEmptyWithKind(String envelope, String kind, String why) throws Exception {
        assertThat(Integer.parseInt(jq(envelope, ".failures | length").strip()))
                .as(why + " — failures[] must be non-empty")
                .isGreaterThan(0);
        assertJqTrue(envelope, ".failures | map(.kind) | all(. != null)",
                "every failure must carry a non-null 'kind' discriminator (polymorphic Finding serde)");
        assertJqTrue(envelope, ".failures | any(.kind == \"" + kind + "\")", why);
    }

    private static void assertManager(String envelope, String expected) throws Exception {
        assertThat(jq(envelope, ".manager").strip())
                .as("the envelope must report the selected/launcher manager")
                .isEqualTo(expected);
    }

    /**
     * Write a jq program to a temp file so it can be passed via {@code -f <file>}, never as an inline
     * argv string. Embedded double-quotes in a jq program ({@code "object"}, {@code "ok"},
     * {@code .kind == "test"}) are STRIPPED by ProcessBuilder argument passing on Windows — they do
     * not survive Java → {@code CreateProcess} → {@code jq.exe}, so {@code select(type == "object")}
     * reaches jq as {@code select(type == object)} (a {@code object/0 is not defined} compile error).
     * Reading the program from a file is cross-platform-safe (proven by #62 on win32-x64).
     */
    private static Path jqProgram(String filter) throws IOException {
        Path program = Files.createTempFile("native-jq-prog", ".jq");
        Files.writeString(program, filter);
        return program;
    }

    private static String jq(String json, String filter) throws Exception {
        Path in = Files.createTempFile("native-jqin", ".json");
        Files.writeString(in, json);
        ProcessBuilder pb = new ProcessBuilder("jq", "-r", "-f", jqProgram(filter).toString(), in.toString());
        Path out = Files.createTempFile("native-jqout", ".txt");
        Path err = Files.createTempFile("native-jqerr", ".txt");
        pb.redirectOutput(out.toFile());
        pb.redirectError(err.toFile());
        Process proc = pb.start();
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("jq timed out: " + filter);
        }
        if (proc.exitValue() != 0) {
            throw new AssertionError("jq failed (" + proc.exitValue() + ") for '" + filter + "'\nInput: "
                    + json + "\nError: " + Files.readString(err));
        }
        return Files.readString(out, StandardCharsets.UTF_8);
    }

    private static void assertJqTrue(String json, String expression, String description) throws Exception {
        Path in = Files.createTempFile("native-jqein", ".json");
        Files.writeString(in, json);
        ProcessBuilder pb = new ProcessBuilder("jq", "-e", "-f", jqProgram(expression).toString(), in.toString());
        pb.redirectErrorStream(true);
        Path out = Files.createTempFile("native-jqeout", ".txt");
        pb.redirectOutput(out.toFile());
        Process proc = pb.start();
        if (!proc.waitFor(10, TimeUnit.SECONDS)) {
            proc.destroyForcibly();
            throw new AssertionError("jq -e timed out: " + expression);
        }
        assertThat(proc.exitValue())
                .as(description + "\nJSON: " + json + "\njq output: " + Files.readString(out))
                .isZero();
    }

    // ---- fixtures + probes -------------------------------------------------------------------

    /** Copy a Maven fixture ({@code pom.xml} + the one test class) from the classpath to a temp dir. */
    private static Path copyMavenFixture(String variant, String testFile) throws Exception {
        Path dir = Files.createTempDirectory("native-mvn-" + variant);
        copyResource("fixtures/it/" + variant + "/pom.xml", dir.resolve("pom.xml"));
        Path testDest = dir.resolve("src/test/java/dev/nobash/fixture/" + testFile);
        Files.createDirectories(testDest.getParent());
        copyResource("fixtures/it/" + variant + "/src/test/java/dev/nobash/fixture/" + testFile, testDest);
        return dir;
    }

    /** Copy a Go fixture ({@code go.mod} + {@code calc.go} + {@code calc_test.go}) to a temp dir. */
    private static Path copyGoFixture(String variant) throws Exception {
        Path dir = Files.createTempDirectory("native-go-" + variant);
        for (String name : List.of("go.mod", "calc.go", "calc_test.go")) {
            copyResource("fixtures/go/" + variant + "/" + name, dir.resolve(name));
        }
        return dir;
    }

    private static void copyResource(String resource, Path dest) throws Exception {
        try (InputStream is = NativeAcceptanceIT.class.getClassLoader().getResourceAsStream(resource)) {
            if (is == null) throw new IllegalStateException("missing fixture resource: " + resource);
            Files.createDirectories(dest.getParent());
            Files.copy(is, dest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Resolve and validate the pre-installed jest project (the {@code --no-install} invariant). */
    private static Path resolvePreinstalledJestProject() {
        String dir = System.getProperty(JEST_PROJECT_PROP);
        gate(dir != null && !dir.isBlank(),
                "system property '" + JEST_PROJECT_PROP + "' is not set — CI must install fixtures/jest/project "
                        + "(npm install) and point this at it");
        Path project = Paths.get(dir);
        gate(Files.isDirectory(project), "jest project dir '" + dir + "' is not a directory");
        Path jest = project.resolve("node_modules/.bin/jest");
        Path jestCmd = project.resolve("node_modules/.bin/jest.cmd");
        gate(Files.exists(jest) || Files.exists(jestCmd),
                "node_modules/.bin/jest not present under '" + dir + "' — install the jest project out-of-band "
                        + "(run_tests runs jest with --no-install and never fetches)");
        return project;
    }

    /**
     * The fail-closed gate. When the prerequisite holds, returns. Otherwise: HARD-FAIL under the
     * native release gate ({@code nbm.native.required=true}); self-SKIP on the ordinary JVM build.
     */
    private static void gate(boolean ok, String why) {
        if (ok) return;
        if (native_required) {
            fail("FAIL-CLOSED (native release gate, nbm.native.required=true): " + why);
        }
        Assumptions.assumeTrue(false, "SKIPPED (JVM build, nbm.native.required=false): " + why);
    }

    private static boolean isOnPath(String command) {
        try {
            List<String> probe = switch (command) {
                case "go" -> List.of("go", "version");
                default -> List.of(command, "--version");
            };
            Process p = new ProcessBuilder(probe).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }

    /** A line is JSON-RPC iff it is a JSON object carrying the {@code "jsonrpc":"2.0"} marker. */
    private static boolean looksLikeJsonRpc(String line) {
        String t = line.strip();
        return t.startsWith("{") && t.endsWith("}") && t.contains("\"jsonrpc\"") && t.contains("\"2.0\"");
    }
}
