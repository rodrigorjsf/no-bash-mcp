package s1;

import java.nio.file.Path;
import java.util.List;

/**
 * Spike s1 driver — FALSIFICATION, not confirmation. Feeds the verbatim prototype
 * normalizer UNSEEN real reports and asserts the schema/dedup hold. Exits non-zero
 * if any assertion fails, so "it folded" is a captured falsifiable claim.
 */
public final class Main {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        Path reports = Path.of("reports");

        section("A) Go multi-package — dedup heuristic at scale (UNSEEN report)");
        NormalizedRun multi = new GoTestJsonNormalizer().normalize(reports.resolve("go-multipkg.json"));
        printRun(multi);
        // Dedup must collapse Go's 7 raw "failed" (incl. 3 redundant parents) to 3 leaf
        // TestFindings + 1 PACKAGE ContainerFinding (initbroken init panic, no owner).
        check("multipkg: 10 tests passed", multi.summary().passed() == 10);
        check("multipkg: 3 leaf tests failed (parents suppressed)", multi.summary().failed() == 3);
        check("multipkg: 0 skipped", multi.summary().skipped() == 0);
        check("multipkg: exactly 4 findings (3 Test + 1 Container)", multi.findings().size() == 4);
        long containers = multi.findings().stream().filter(f -> f instanceof ContainerFinding).count();
        check("multipkg: exactly 1 ContainerFinding", containers == 1);
        ContainerFinding initContainer = (ContainerFinding) multi.findings().stream()
                .filter(f -> f instanceof ContainerFinding).findFirst().orElseThrow();
        check("multipkg: container scope=PACKAGE", initContainer.scope() == ContainerScope.PACKAGE);
        check("multipkg: container is initbroken", initContainer.container().endsWith("initbroken"));
        check("multipkg: ok() is FALSE (container-aware)", !multi.ok());
        // Deep path: TestReverseDeep/unicode/fails -> name=fails, path=[TestReverseDeep, unicode]
        TestFinding deep = multi.findings().stream()
                .filter(f -> f instanceof TestFinding tf && tf.name().equals("fails"))
                .map(f -> (TestFinding) f).findFirst().orElse(null);
        check("multipkg: deep leaf 'fails' present", deep != null);
        if (deep != null) {
            check("multipkg: deep path = [TestReverseDeep, unicode]",
                    deep.path().equals(List.of("TestReverseDeep", "unicode")));
            check("multipkg: deep leaf has file:line source", deep.source() != null && deep.source().line() != null);
        }
        // Prefix-collision guard: TestAdd (passes) must NOT be suppressed by TestAddTable's child.
        check("multipkg: TestMul kept as leaf TestFinding (sibling of TestAddTable)",
                multi.findings().stream().anyMatch(f -> f instanceof TestFinding tf && tf.name().equals("TestMul")));

        section("B) Go compile-error — non-JSON-on-stdout claim + signal preservation (UNSEEN report)");
        NormalizedRun ce = new GoTestJsonNormalizer().normalize(reports.resolve("go-compile-error.json"));
        printRun(ce);
        check("compile-error: ok() is FALSE (no false-green)", !ce.ok());
        check("compile-error: surfaces >=1 finding", !ce.findings().isEmpty());
        // The actionable compiler detail ("undefined: Heigth", buggy_test.go:9) lives in
        // build-output events keyed by ImportPath. Does ANY finding preserve it?
        boolean detailSurvives = ce.findings().stream().anyMatch(f ->
                (f.message() != null && f.message().contains("undefined"))
                || (f.detail() != null && f.detail().contains("undefined")));
        boolean srcSurvives = ce.findings().stream().anyMatch(f -> f.source() != null);
        System.out.println("  >> [verbatim] compiler-error text preserved in a finding? " + detailSurvives);
        System.out.println("  >> [verbatim] file:line preserved in a finding?          " + srcSurvives);
        check("compile-error: verbatim normalizer LOSES the compiler detail (documented gap)", !detailSurvives);

        section("B2) Go compile-error — REMEDY (V2 folds build-output/build-fail events)");
        NormalizedRun ce2 = new GoTestJsonNormalizerV2().normalize(
                java.nio.file.Files.readString(reports.resolve("go-compile-error.json")));
        printRun(ce2);
        check("compile-error V2: ok() is FALSE", !ce2.ok());
        check("compile-error V2: rawStatus = build-fail (compile error, not a test FAIL)",
                ce2.findings().stream().anyMatch(f -> "build-fail".equals(f.rawStatus())));
        check("compile-error V2: outcome ERRORED", ce2.findings().stream().anyMatch(f -> f.outcome() == Outcome.ERRORED));
        check("compile-error V2: compiler detail now PRESERVED (undefined symbol)",
                ce2.findings().stream().anyMatch(f -> f.detail() != null && f.detail().contains("undefined: Heigth")));
        check("compile-error V2: file:line now PRESERVED (buggy_test.go)",
                ce2.findings().stream().anyMatch(f -> f.source() != null
                        && f.source().file().contains("buggy_test.go") && f.source().line() != null));

        section("C) Container-only run — the G5 false-green keystone guard (frozen ADR test case)");
        NormalizedRun containerOnly = new NormalizedRun("synthetic",
                new Summary(3, 3, 0, 0, 0), // ALL 3 tests passed by count...
                List.of(new ContainerFinding(ContainerScope.SUITE, "DbSetup",
                        Outcome.ERRORED, "error", "@BeforeAll connection refused",
                        new SourceRef("DbSetupTest.java", 21), "java.net.ConnectException...")));
        printRun(containerOnly);
        check("container-only: summary shows 0 failed/errored tests", containerOnly.summary().failed() == 0
                && containerOnly.summary().errored() == 0);
        check("container-only: ok() is STILL FALSE (must not false-green)", !containerOnly.ok());

        section("D) JUnit @Nested + @ParameterizedTest + @DisplayName — identity at scale (UNSEEN report)");
        NormalizedRun junit = new JUnitXmlNormalizer().normalize(Path.of("reports", "junit-param"));
        printRun(junit);
        // Surefire wrote tests=0 in the outer file and tests=12 in the nested file — the
        // <testsuite> header is unreliable. Counting per-<testcase> must still yield 12.
        check("junit: counts 12 testcases (NOT the misleading testsuite headers)", junit.summary().total() == 12);
        check("junit: 7 passed", junit.summary().passed() == 7);
        check("junit: 5 failed", junit.summary().failed() == 5);
        check("junit: ok() is FALSE", !junit.ok());
        // Parametrized index lands in name; outer class in suite; path[] stays empty.
        check("junit: param index preserved in name (isEven(int)[3])",
                junit.findings().stream().anyMatch(f -> f instanceof TestFinding tf
                        && tf.name().equals("isEven(int)[3]") && tf.suite().equals("spike.ParamNestedTests")));
        // @Nested class lands in suite via $-join; identity not lost.
        check("junit: nested class preserved in suite ($WhenNegative)",
                junit.findings().stream().anyMatch(f -> f instanceof TestFinding tf
                        && tf.suite().equals("spike.ParamNestedTests$WhenNegative") && tf.name().equals("failsDirectly")));
        // Deepest: parametrized test inside a @Nested class.
        check("junit: nested+parametrized identity (singleChar(String)[3] in $WhenNegative)",
                junit.findings().stream().anyMatch(f -> f instanceof TestFinding tf
                        && tf.name().equals("singleChar(String)[3]")
                        && tf.suite().equals("spike.ParamNestedTests$WhenNegative")));
        check("junit: file:line parsed from project frame (not JUnit internals)",
                junit.findings().stream().anyMatch(f -> f.source() != null
                        && f.source().file().equals("ParamNestedTests.java") && f.source().line() != null));

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL HARD ASSERTIONS PASSED");
        } else {
            System.out.println(failures + " HARD ASSERTION(S) FAILED");
            System.exit(1);
        }
    }

    private static void section(String t) { System.out.println("\n=== " + t + " ==="); }

    private static void check(String label, boolean cond) {
        System.out.println((cond ? "  [PASS] " : "  [FAIL] ") + label);
        if (!cond) failures++;
    }

    private static void printRun(NormalizedRun r) {
        System.out.printf("  tool=%s ok=%s summary{total=%d passed=%d failed=%d errored=%d skipped=%d} findings=%d%n",
                r.tool(), r.ok(), r.summary().total(), r.summary().passed(), r.summary().failed(),
                r.summary().errored(), r.summary().skipped(), r.findings().size());
        for (Finding f : r.findings()) {
            if (f instanceof TestFinding tf) {
                System.out.printf("    TEST  suite=%s path=%s name=%s src=%s msg=%s%n",
                        tf.suite(), tf.path(), tf.name(), src(tf.source()), tf.message());
            } else if (f instanceof ContainerFinding cf) {
                System.out.printf("    CONT  [%s] %s src=%s msg=%s%n",
                        cf.scope(), cf.container(), src(cf.source()), cf.message());
            }
        }
    }

    private static String src(SourceRef s) {
        return s == null ? "null" : (s.file() + ":" + s.line());
    }
}
