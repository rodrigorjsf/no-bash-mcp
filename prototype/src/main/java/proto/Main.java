package proto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Throwaway runner for the universal-schema prototype. Not a keystroke TUI: the
 * schema fold is a transformation, not a state machine, so this prints all three
 * REAL normalized runs side by side, then exercises the two outbound ports once.
 *
 * One command:  cd prototype && mvn -q compile exec:java
 *
 * It answers two questions:
 *   Bet #1 — do three dissimilar real reports fold into ONE struct, with the
 *            no-test-owner failure as a first-class ContainerFinding (not a fake
 *            empty test)?
 *   Bet #2 — does CommandExecutorPort stay format-agnostic (process result only),
 *            with all format knowledge above it in the verb + normalizer?
 */
public final class Main {

    static final String B = "[1m", D = "[2m", R = "[0m",
            GRN = "[32m", RED = "[31m", YEL = "[33m", CYN = "[36m";

    public static void main(String[] args) throws Exception {
        Path reports = Path.of("reports");

        var runs = new ArrayList<NormalizedRun>();
        runs.add(new JUnitXmlNormalizer().normalize(reports));
        NormalizedRun jest = loadJest(reports.resolve("jest.json"));
        if (jest != null) runs.add(jest);
        runs.add(new GoTestJsonNormalizer().normalize(reports.resolve("go-test.json")));

        System.out.println(B + "═══ BET #1: real reports → ONE schema ═══" + R + "\n");
        runs.forEach(Main::printRun);
        if (jest == null)
            System.out.println(YEL + "  (jest report not yet available — local install pending; "
                    + "fold shown on JUnit+Go, both genuinely dissimilar and both hit axis 5)" + R + "\n");

        System.out.println(B + "═══ BET #1 discriminator: the no-test-owner failure (axis 5) ═══" + R);
        System.out.println(D + "Each must be a ContainerFinding — NOT a TestFinding with an empty name." + R);
        for (var run : runs)
            run.findings().stream()
                    .filter(f -> f instanceof ContainerFinding)
                    .forEach(f -> System.out.println("  " + run.tool() + ":  " + render(f)));
        System.out.println();

        System.out.println(B + "═══ BET #2: dispatch through the outbound ports ═══" + R);
        System.out.println(D + "CommandExecutorPort returns only {exitCode, stdout, stderr, timedOut} —"
                + " zero format knowledge. The verb + normalizer hold all of it." + R + "\n");

        var cache = new RunCache();
        var runTests = new RunTestsVerb(new StubExecutor(), cache);

        Envelope e1 = runTests.run("fixtures/junit-gen", Manager.MAVEN);
        Envelope e2 = runTests.run("fixtures/go-gen", Manager.GO);
        printEnvelope(e1);
        printEnvelope(e2);

        // get_log drill-down: signal (the envelope) stayed tight; noise (raw
        // stacktrace) is retrieved on demand by handle, never re-run.
        System.out.println(B + "get_log(" + e1.handle() + ", failure=0)" + R + " — drill into retained detail:");
        System.out.println(D + indent(cache.getLog(e1.handle(), 0)) + R + "\n");

        var prChecks = new PrChecksVerb(new StubForge());
        printEnvelope(prChecks.run("refs/pull/42/head"));

        // The G5 false-green guard: a run whose ONLY failure is a no-test-owner
        // container (all TESTS passed by count) must STILL be not-ok. Proven on a
        // synthetic container-only run — the case the three fixtures happened to mask.
        System.out.println(B + "═══ False-green guard (container-only run) ═══" + R);
        var containerOnly = new NormalizedRun("synthetic",
                new Summary(3, 3, 0, 0, 0), // 3 tests, all "passed" by count
                List.of(new ContainerFinding(ContainerScope.PACKAGE, "broken/pkg",
                        Outcome.FAILED, "fail", "init panic", null, "")));
        System.out.printf("%scounts say pass=3/3, but findings hold a PACKAGE failure → ok() = %s%s%s (must be false)%n%n",
                D, containerOnly.ok() ? RED + "true — BUG" : GRN + "false — correct", R, "");

        System.out.println(B + "═══ VERDICT ═══" + R);
        System.out.println("See prototype/NOTES.md for the written conclusion that promotes ADR-0006.");
    }

    /** Loads the jest run if its report exists and is non-empty; else null so the
     *  fold can still be validated on JUnit+Go while the local jest install runs. */
    static NormalizedRun loadJest(Path p) {
        try {
            if (!Files.exists(p) || Files.size(p) == 0) return null;
            return new JestJsonNormalizer().normalize(p);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- rendering -------------------------------------------------------

    static void printRun(NormalizedRun run) {
        var s = run.summary();
        String dot = run.ok() ? GRN + "ok" + R : RED + "FAIL" + R; // container-aware
        long containers = run.findings().stream().filter(f -> f instanceof ContainerFinding).count();
        System.out.printf("%s%-16s%s [%s]  %stests: total=%d pass=%d fail=%d err=%d skip=%d  +%d container%s%s%n",
                B, run.tool(), R, dot, D, s.total(), s.passed(), s.failed(), s.errored(), s.skipped(),
                containers, containers == 1 ? "" : "s", R);
        for (Finding f : run.findings()) System.out.println("    " + render(f));
        System.out.println();
    }

    static String render(Finding f) {
        return switch (f) {
            case TestFinding t -> {
                String nest = t.path().isEmpty() ? "" : D + String.join(" › ", t.path()) + " › " + R;
                yield String.format("%sTEST%s %s%s › %s%s%s  %s  %s%s",
                        CYN, R, D, t.suite(), R, nest, B + t.name() + R,
                        outcome(t.outcome()), src(t.source()), msg(t.message()));
            }
            case ContainerFinding c -> String.format("%sCONT%s %s[%s]%s %s%s%s  %s  %s%s",
                    YEL, R, D, c.scope(), R, B, c.container(), R,
                    outcome(c.outcome()), src(c.source()), msg(c.message()));
        };
    }

    static String outcome(Outcome o) {
        String c = switch (o) { case FAILED -> RED; case ERRORED -> YEL; case SKIPPED -> D; case PASSED -> GRN; };
        return c + o + R;
    }
    static String src(SourceRef s) { return s == null ? D + "(no src)" + R : D + s.file() + ":" + s.line() + R; }
    static String msg(String m) { return m == null ? "" : "  " + m; }
    static String indent(String s) { return s == null ? "(none)" : s.strip().replace("\n", "\n  "); }

    static void printEnvelope(Envelope e) {
        String head = e.ok() ? GRN + "ok" + R : (e.code() != null ? YEL + e.code() + R : RED + "FAIL" + R);
        String mgr = e.manager() == null ? "" : "manager=" + e.manager() + " "; // null for git/forge verbs
        System.out.printf("%s%-10s%s [%s] %s%s%s%s  handle=%s%n",
                B, e.verb(), R, head, mgr, D, e.summary(), R, e.handle());
        for (Finding f : e.failures()) System.out.println("    " + render(f));
        if (e.hint() != null) System.out.println("    " + D + "hint: " + e.hint() + R);
        System.out.println();
    }
}

// ---- envelope + run cache (common output contract) -----------------------

/** The common envelope (operational-model.md). Three shapes: success
 *  (failures empty, code null), test-failure (failures populated), operational
 *  error (code + hint). */
record Envelope(boolean ok, String verb, String manager, String summary, String handle,
                List<Finding> failures, String code, String hint) {}

/** Transient handle -> retained detail. Stand-in for the session-scoped run
 *  cache; get_log expands a slice without re-running. */
final class RunCache {
    private final Map<String, NormalizedRun> runs = new LinkedHashMap<>();
    private int seq = 0;

    String put(NormalizedRun run) {
        String h = "run-" + (++seq);
        runs.put(h, run);
        return h;
    }

    String getLog(String handle, int failureIndex) {
        NormalizedRun run = runs.get(handle);
        if (run == null) return null;
        var fails = run.findings();
        return failureIndex < fails.size() ? fails.get(failureIndex).detail() : null;
    }
}

// ---- application verbs (dispatch) ----------------------------------------

enum Manager { MAVEN, GO, JEST }

/**
 * run_tests dispatch: typed input -> build ExecSpec (inject reporter flag) ->
 * CommandExecutorPort.execute -> resolve report SOURCE -> normalize -> envelope.
 *
 * KEY BOUNDARY FINDING: the report source differs by ecosystem — Maven writes a
 * FILE (target/surefire-reports/), go test -json writes to STDOUT. The port
 * exposes BOTH (ExecResult.stdout + a file the process wrote) uniformly; THIS
 * layer decides which to read. The port never names a format. Bet #2 holds.
 */
final class RunTestsVerb {
    private final CommandExecutorPort exec;
    private final RunCache cache;

    RunTestsVerb(CommandExecutorPort exec, RunCache cache) {
        this.exec = exec;
        this.cache = cache;
    }

    Envelope run(String path, Manager mgr) {
        ExecSpec spec = buildSpec(path, mgr);   // verb knowledge: which manager + reporter flag
        ExecResult result = exec.execute(spec); // PORT: format-agnostic process execution
        if (result.timedOut())
            return new Envelope(false, "run_tests", mgr.name(), "timed out", null, List.of(), "TIMEOUT",
                    "raise timeout or narrow the target");

        NormalizedRun run;
        try {
            run = normalize(mgr, path, result);  // verb + normalizer hold ALL format knowledge
        } catch (Exception ex) {
            return new Envelope(false, "run_tests", mgr.name(), "no report produced", null, List.of(),
                    "REPORT_NOT_PRODUCED", "check the manager actually ran the tests");
        }

        var s = run.summary();
        String handle = cache.put(run);
        if (run.ok())   // container-aware: a container-only failure is NOT a success
            return new Envelope(true, "run_tests", mgr.name(),
                    "passed " + s.passed() + "/" + s.total(), handle, List.of(), null, null);

        List<Finding> failures = run.findings().stream().filter(f -> f.outcome() != Outcome.SKIPPED).toList();
        long containers = failures.stream().filter(f -> f instanceof ContainerFinding).count();
        long testFails = failures.size() - containers;
        String summary = testFails + " test failure(s)"
                + (containers > 0 ? " + " + containers + " container failure(s)" : "")
                + " of " + s.total() + " tests";
        return new Envelope(false, "run_tests", mgr.name(), summary, handle, failures, null, null);
    }

    private static ExecSpec buildSpec(String path, Manager mgr) {
        // The verb injects the reporter — never stdout scraping (tool-catalog.md).
        List<String> cmd = switch (mgr) {
            case MAVEN -> List.of("mvn", "-q", "test"); // surefire XML is default
            case GO    -> List.of("go", "test", "-json", "./...");
            case JEST  -> List.of("jest", "--json");
        };
        return new ExecSpec(path, cmd, 120_000, Map.of());
    }

    /** Resolves the report SOURCE per ecosystem (file vs stdout) and normalizes.
     *  This is where the file-vs-stdout divergence is genuinely handled — and it is
     *  EXERCISED, not asserted: Go parses {@code result.stdout()} (the bytes the
     *  port captured), Maven reads the file the process wrote. The port stays
     *  format-blind either way. */
    private static NormalizedRun normalize(Manager mgr, String path, ExecResult result) throws Exception {
        return switch (mgr) {
            case MAVEN -> new JUnitXmlNormalizer().normalize(Path.of("reports")); // report = file
            case GO    -> new GoTestJsonNormalizer().normalize(result.stdout());   // report = STDOUT
            case JEST  -> new JestJsonNormalizer().normalize(Path.of("reports/jest.json"));
        };
    }
}

/** pr_checks dispatch: ForgePort -> normalize CI checks into the SAME envelope. */
final class PrChecksVerb {
    private final ForgePort forge;

    PrChecksVerb(ForgePort forge) { this.forge = forge; }

    Envelope run(String ref) {
        List<RawCheck> checks = forge.prChecks(ref);
        long failed = checks.stream().filter(c -> c.rawConclusion().equals("failure")).count();
        long pending = checks.stream().filter(c -> c.rawConclusion().isEmpty()).count();
        String summary = checks.size() + " checks, " + failed + " failing, " + pending + " pending";
        // Forge failures map onto the same Finding shape (ContainerFinding: a CI
        // check has no single test owner) — the envelope is genuinely common.
        List<Finding> failures = checks.stream()
                .filter(c -> c.rawConclusion().equals("failure"))
                .map(c -> (Finding) new ContainerFinding(ContainerScope.RUN, c.name(),
                        Outcome.FAILED, "failure", "CI check failed", null, c.detailsUrl()))
                .toList();
        // manager is null for forge verbs: a forge is NOT a manager (CONTEXT.md).
        return new Envelope(failed == 0, "pr_checks", null, summary, "checks-1", failures, null,
                failed == 0 ? null : "drill the failing check log via get_log");
    }
}

// ---- stub adapters (in-memory; no real subprocess / HTTP) ----------------

/** Stub CommandExecutorPort. Represents "the process ran" without re-running the
 *  real tools (they already produced the reports on disk). Crucially it returns
 *  ONLY process-level facts — it returns raw bytes, it does not parse or know any
 *  format. It mirrors reality: a `go test -json` process writes its report to
 *  STDOUT, while `mvn`/`jest` write it to a file (left on disk for the verb). */
final class StubExecutor implements CommandExecutorPort {
    @Override
    public ExecResult execute(ExecSpec spec) {
        String stdout = "";
        if (spec.command().contains("go")) { // go test -json reports on stdout
            try {
                stdout = Files.readString(Path.of("reports/go-test.json"));
            } catch (Exception ignore) { /* stub: leave empty */ }
        }
        return new ExecResult(1, stdout, "", false); // exit 1: tests failed; format-blind
    }
}

/** Stub ForgePort with canned GitHub-style checks. */
final class StubForge implements ForgePort {
    @Override
    public List<RawCheck> prChecks(String ref) {
        List<RawCheck> checks = new ArrayList<>();
        checks.add(new RawCheck("build", "completed", "success", "https://ci/build/1"));
        checks.add(new RawCheck("unit-tests", "completed", "failure", "https://ci/unit/2"));
        checks.add(new RawCheck("lint", "in_progress", "", "https://ci/lint/3"));
        return checks;
    }
}
