package dev.nobash.adapter.out.ecosystem.node;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
import dev.nobash.application.verb.tests.EcosystemAdapter;
import dev.nobash.application.verb.tests.ReportPlan;
import dev.nobash.application.verb.tests.RunInterpretation;
import dev.nobash.application.verb.tests.TestTarget;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.JestJsonParser;
import dev.nobash.domain.result.NormalizedRun;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The Node {@link EcosystemAdapter} (ADR-0011, PRD-3 slice 4) — drives jest's {@code run_tests} via
 * the trusted {@code npx} launcher. Like the Maven and Go adapters it owns everything that VARIES
 * for the Node ecosystem and returns VALUES the use-case consumes; the invariant orchestration —
 * the lock, the timeout intercept + tree-kill, the D27/D28/D29 floor, and the run-cache stash/handle
 * — stays single-source in {@code RunTestsUseCase}. It never injects {@code RawOutputStash} or
 * {@code ModuleLock}.
 *
 * <ul>
 *   <li><b>Detection (D52)</b> — a {@code package.json} declaring a RECOGNIZED test framework
 *       ({@code jest}/{@code vitest}/{@code mocha}) in {@code dependencies}/{@code devDependencies},
 *       OR a top-level {@code "jest"} config key, OR a {@code jest.config.{js,ts,cjs,mjs,json}} file.
 *       A BARE / tooling-only {@code package.json} (prettier/eslint, no test framework) does NOT
 *       match — so a polyglot root co-located with a {@code pom.xml}/{@code go.mod} never spuriously
 *       trips {@code AMBIGUOUS_SCOPE}. vitest/mocha DO detect (so the directory is unambiguously a
 *       Node test project) and are then rejected in {@link #buildExec} with
 *       {@code UNSUPPORTED_TEST_FRAMEWORK} — jest is the only framework RUN in v1.</li>
 *   <li><b>Installed-check</b> — delegated to the generic {@link ManagerPathResolver} for
 *       {@code npx} on PATH (ADR-0008), NOT {@code executor.isManagerInstalled()} (hardcoded to
 *       {@code mvn}). The launcher is {@code npx} consistently across {@link #managerBinary},
 *       {@link #isInstalled}, and the inert preflight no-op.</li>
 *   <li><b>Exec</b> — a full-suite {@code npx jest --json --outputFile=<fresh>
 *       --testLocationInResults --no-install}. The report goes to a FRESH per-run {@code --outputFile}
 *       (report freshness by construction, D27; the report file IS the report source, never stdout —
 *       with {@code --outputFile} jest's stdout is empty). {@code --no-install} is MANDATORY: jest is
 *       resolved from {@code node_modules/.bin} (a preflight check), NEVER network-fetched
 *       (D21/D38).</li>
 *   <li><b>Preflight (the 3 pre-result conditions)</b> — the seam has no preflight hook and the
 *       use-case always runs {@code buildExec → execute → interpret}. So {@link #buildExec} performs
 *       the framework / target / dependency checks and, on any failure, builds an INERT no-op spec
 *       ({@code npx --version} — guaranteed present since {@code TOOL_NOT_INSTALLED} already passed,
 *       sub-second, no network) and stashes the {@code reportAbsent} decision on the
 *       {@link ReportPlan#preflight()}. {@link #interpret} returns it verbatim, routing through the
 *       use-case's existing {@code isReportAbsent()} → {@code operationalError} channel with zero
 *       use-case edits. The conditions: vitest/mocha → {@code UNSUPPORTED_TEST_FRAMEWORK}; a
 *       structured target → {@code UNSUPPORTED_TARGET} (full-suite only on Node); jest unresolvable
 *       in {@code node_modules} → {@code DEPS_NOT_INSTALLED} (hint: run {@code install}).</li>
 *   <li><b>Interpret</b> — reads the FRESH {@code --outputFile} and folds the jest JSON into a
 *       {@link NormalizedRun} via the pure {@link JestJsonParser}. An assertion failure → a
 *       {@code TestFinding}; a module-load failure → a {@code ContainerFinding(FILE, ERRORED)} that
 *       makes {@code run.ok()==false} with {@code executedTests==0}, which the use-case floor routes
 *       to a test-failure envelope, never {@code NO_TESTS_RUN} (the G5 keystone).</li>
 * </ul>
 */
@Singleton
public class NodeEcosystemAdapter implements EcosystemAdapter {

    /** The trusted PATH-resolved launcher (ADR-0008); jest is driven through it, never a wrapper. */
    private static final String LAUNCHER = "npx";

    /** The framework binary jest is RUN as (the Reporter is "jest", CONTEXT.md). */
    private static final String JEST = "jest";

    private static final String MARKER_DESCRIPTION = "package.json (jest)";

    /** A fresh, empty-before-exec report file prefix (report freshness by construction, D27). */
    private static final String REPORT_FILE_PREFIX = "no-bash-mcp-jest-";
    private static final String REPORT_FILE_SUFFIX = ".json";

    /** The frameworks D52 detection RECOGNIZES; only {@link #JEST} is RUN (others → rejected). */
    private static final List<String> RECOGNIZED_FRAMEWORKS = List.of("jest", "vitest", "mocha");

    /** {@code jest.config.{js,ts,cjs,mjs,json}} — any of these is a jest project even sans dep. */
    private static final List<String> JEST_CONFIG_FILES = List.of(
            "jest.config.js", "jest.config.ts", "jest.config.cjs", "jest.config.mjs", "jest.config.json");

    private final CommandExecutorPort executor;
    private final ManagerPathResolver resolver;
    private final JestJsonParser parser = new JestJsonParser();

    /**
     * @param executor the format-blind executor seam (@Primary → the Maven {@code CommandExecutor},
     *                 which launches {@code argv[0]} verbatim — so it runs {@code npx} unchanged).
     *                 Mirrors {@code GoEcosystemAdapter}: the adapter never executes through this
     *                 field itself (the use-case's own {@code @Primary} executor runs
     *                 {@code plan.spec()}); it is carried only to keep the constructor symmetric with
     *                 the other adapters. The {@code @Named("npm")} qualifier is deliberately NOT
     *                 used — it is unnecessary (no execute/installed-check goes through here) and a
     *                 qualified param would break every mocked {@code CommandExecutorPort} context.
     * @param resolver the generic PATH resolver (used for the {@code npx}-on-PATH installed-check;
     *                 the port's own installed-check is hardcoded to {@code mvn} and unusable here)
     */
    public NodeEcosystemAdapter(CommandExecutorPort executor, ManagerPathResolver resolver) {
        this.executor = executor;
        this.resolver = resolver;
    }

    @Override
    public boolean detects(Path dir) {
        // D52: a jest.config.* file is a jest project even without a package.json dep.
        for (String cfg : JEST_CONFIG_FILES) {
            if (Files.isRegularFile(dir.resolve(cfg))) {
                return true;
            }
        }
        Path packageJson = dir.resolve("package.json");
        if (!Files.isRegularFile(packageJson)) {
            return false;
        }
        String json = readQuietly(packageJson);
        if (json == null) {
            return false;
        }
        // A recognized framework declared as a dependency, OR a top-level "jest" config key. A bare
        // / tooling-only package.json (no recognized framework) returns false — no AMBIGUOUS_SCOPE
        // regression for a polyglot root.
        return declaredFramework(json) != null || hasTopLevelJestKey(json);
    }

    @Override
    public String managerBinary() {
        return LAUNCHER;
    }

    @Override
    public String markerDescription() {
        return MARKER_DESCRIPTION;
    }

    @Override
    public boolean isInstalled() {
        // The generic resolver, NOT executor.isManagerInstalled() (which is hardcoded to mvn).
        return resolver.resolvesOnPath(LAUNCHER);
    }

    @Override
    public ReportPlan buildExec(List<String> vettedFlags, int timeoutSeconds, Path workingDir,
                                @Nullable TestTarget target) {
        // Preflight (no jest launched on any failure): build an INERT no-op spec and stash the
        // reportAbsent decision; interpret returns it verbatim through the use-case's existing
        // operational-error channel. `npx --version` is guaranteed present (TOOL_NOT_INSTALLED has
        // already passed), sub-second, and never network-fetches.

        // (1) Framework check (D52). A recognized-but-unsupported framework (vitest/mocha) → reject.
        String framework = resolveFramework(workingDir);
        if (framework != null && !JEST.equals(framework)) {
            return inert(timeoutSeconds, workingDir, RunInterpretation.reportAbsent(
                    "", ErrorCode.UNSUPPORTED_TEST_FRAMEWORK,
                    "Detected the '" + framework + "' test framework, which run_tests does not run "
                            + "(jest is the only supported Node framework in v1).",
                    "Use jest for this project, or run run_tests against a jest-based sub-project."));
        }

        // (2) Structured-target check. Node run_tests is full-suite only in v1 — a well-formed
        // CLASS/METHOD target is honestly unsupported here (NOT a malformed INVALID_TARGET).
        if (target != null) {
            return inert(timeoutSeconds, workingDir, RunInterpretation.reportAbsent(
                    "", ErrorCode.UNSUPPORTED_TARGET,
                    "Target selection is not yet supported for node — run_tests runs the full jest "
                            + "suite only (full-suite only).",
                    "Re-run run_tests without a targetKind/target to run the full jest suite."));
        }

        // (3) Dependency preflight. jest must be resolvable in node_modules/.bin — NEVER fetched.
        if (!jestBinaryResolvable(workingDir)) {
            return inert(timeoutSeconds, workingDir, RunInterpretation.reportAbsent(
                    "", ErrorCode.DEPS_NOT_INSTALLED,
                    "The 'jest' binary was not found in node_modules — the project's dependencies are "
                            + "not installed (run_tests never network-fetches a test framework).",
                    "Run `install` to install the project's dependencies, then re-run run_tests."));
        }

        // The healthy path: a fresh --outputFile so any report content is necessarily THIS run's
        // (D27). The report source is that same fresh file; interpret reads it, never stdout.
        Path reportFile = allocateFreshReportFile();
        List<String> argv = List.of(LAUNCHER, JEST, "--json", "--outputFile=" + reportFile,
                "--testLocationInResults", "--no-install");
        // vettedFlags are intentionally ignored — Node v1 injects no agent flags (YAGNI), exactly
        // like Go. The reporter flags above are MCP-controlled, never agent input.
        ExecSpec spec = new ExecSpec(argv, workingDir.toString(), timeoutSeconds);
        return new ReportPlan(spec, reportFile);
    }

    @Override
    public RunInterpretation interpret(ExecResult result, ReportPlan plan) {
        // A preflight decision short-circuits — the inert no-op already ran; return it verbatim so
        // the use-case routes it through isReportAbsent() → operationalError (zero ecosystem
        // literals in the use-case).
        if (plan.preflight() != null) {
            return plan.preflight();
        }
        // Read the FRESH --outputFile (jest's stdout is empty with --outputFile) and fold it.
        String report = readQuietly(plan.reportSource());
        NormalizedRun run = parser.parse(report == null ? "" : report);
        return RunInterpretation.normalized(run);
    }

    @Override
    public List<Finding> partialFindings(ReportPlan plan) {
        // Best-effort partial findings on a timeout: jest may have flushed a (partial) report to the
        // fresh --outputFile before the kill. A preflight no-op or an unwritten file yields empty.
        if (plan.preflight() != null) {
            return List.of();
        }
        String report = readQuietly(plan.reportSource());
        if (report == null || report.isBlank()) {
            return List.of();
        }
        return parser.parse(report).findings();
    }

    /**
     * Build an INERT {@link ReportPlan}: a no-op {@code npx --version} spec (guaranteed present,
     * sub-second, no network) plus the carried preflight {@link RunInterpretation}. No jest is
     * launched; {@link #interpret} returns the preflight decision verbatim.
     */
    private static ReportPlan inert(int timeoutSeconds, Path workingDir, RunInterpretation preflight) {
        ExecSpec noop = new ExecSpec(List.of(LAUNCHER, "--version"), workingDir.toString(), timeoutSeconds);
        // The report source is unused on the preflight path (interpret short-circuits); carry the
        // working dir to satisfy the ReportPlan contract.
        return new ReportPlan(noop, workingDir, preflight);
    }

    /** The recognized framework declared for the project at {@code dir}, or null when none. */
    private @Nullable String resolveFramework(Path dir) {
        for (String cfg : JEST_CONFIG_FILES) {
            if (Files.isRegularFile(dir.resolve(cfg))) {
                return JEST;
            }
        }
        String json = readQuietly(dir.resolve("package.json"));
        if (json == null) {
            return null;
        }
        if (hasTopLevelJestKey(json)) {
            return JEST;
        }
        return declaredFramework(json);
    }

    /**
     * The FIRST recognized framework declared in the {@code dependencies}/{@code devDependencies}
     * objects of a {@code package.json}, or null when none is declared. jest is preferred when more
     * than one is present (jest is the only one RUN; a jest+vitest project is treated as jest).
     */
    private static @Nullable String declaredFramework(String json) {
        String deps = objectSpan(json, "dependencies") + "\n" + objectSpan(json, "devDependencies");
        String found = null;
        for (String fw : RECOGNIZED_FRAMEWORKS) {
            if (dependencyKeyPresent(deps, fw)) {
                if (JEST.equals(fw)) {
                    return JEST;   // jest wins outright
                }
                if (found == null) {
                    found = fw;
                }
            }
        }
        return found;
    }

    /** Whether {@code "<dep>"} appears as a KEY (followed by {@code :}) in a dependencies span. */
    private static boolean dependencyKeyPresent(String depsSpan, String dep) {
        return Pattern.compile("\"" + Pattern.quote(dep) + "\"\\s*:").matcher(depsSpan).find();
    }

    /** Whether the package.json carries a top-level {@code "jest"} config key (inline jest config). */
    private static boolean hasTopLevelJestKey(String json) {
        // A "jest": key at the top level of the package.json object. Conservative: matches the key
        // followed by ':' and an object/string value — not a "jest" inside a dependency version.
        return Pattern.compile("\"jest\"\\s*:\\s*[\\{\"]").matcher(json).find();
    }

    /**
     * The raw substring spanning the {@code "<key>": { … }} object value (braces included), or an
     * empty string when the key is absent / not an object. A tiny brace-balanced, escape-aware
     * scan — enough to bound the dependency-name search to the dependencies object so a framework
     * name appearing elsewhere (a script, a comment-like string) is never mistaken for a dep.
     */
    private static String objectSpan(String json, String key) {
        String needle = "\"" + key + "\"";
        int k = json.indexOf(needle);
        if (k < 0) {
            return "";
        }
        int colon = json.indexOf(':', k + needle.length());
        if (colon < 0) {
            return "";
        }
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) {
            p++;
        }
        if (p >= json.length() || json.charAt(p) != '{') {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        for (int i = p; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return json.substring(p, i + 1);
                }
            }
        }
        return json.substring(p);
    }

    /** Whether {@code node_modules/.bin/jest} (or its {@code .cmd} sibling) exists at {@code dir}. */
    private static boolean jestBinaryResolvable(Path dir) {
        Path bin = dir.resolve("node_modules").resolve(".bin");
        return Files.exists(bin.resolve(JEST)) || Files.exists(bin.resolve(JEST + ".cmd"));
    }

    private static Path allocateFreshReportFile() {
        try {
            return Files.createTempFile(REPORT_FILE_PREFIX, REPORT_FILE_SUFFIX);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to allocate a fresh jest report file", e);
        }
    }

    /** Read a file's content as UTF-8, or null when it is absent / unreadable (best-effort). */
    private static @Nullable String readQuietly(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }
}
