package dev.nobash.domain.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The divergence matrix for {@link JestJsonParser} (PRD-3 slice 4, ADR-0007). Each fixture is a
 * captured (or grammar-authored) {@code jest --json} report (see {@code fixtures/jest/README.md}
 * for provenance); the test reads its bytes and hands the in-memory content to the pure, I/O-free
 * parser — the jest analogue of {@code GoTestJsonParserTest}.
 *
 * <p>The matrix proves the frozen rules on the jest format: per-assertion counts (not the
 * top-level {@code numPassedTests}/{@code numFailedTests} header); an assertion failure attributed
 * to a {@code TestFinding} with best-effort {@code file:line} from
 * {@code assertionResults[].location} (only when present — {@code jest-all.json} carries
 * {@code location:null} and must not NPE); and a jest module-load failure folded INTO the graph as
 * {@code ContainerFinding(FILE, ERRORED)} keyed on the EMPTY {@code assertionResults} discriminator
 * — never {@code testExecError} (NULL in jest 30). A module-load-only run is {@code executedTests
 * == 0} yet NOT ok (the floor's G5 keystone).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class JestJsonParserTest {

    private static final JestJsonParser PARSER = new JestJsonParser();

    private static final String LOC = "fixtures/jest/jest-loc.json";
    private static final String ALL = "fixtures/jest/jest-all.json";
    private static final String ALL_PASSED = "fixtures/jest/jest-all-passed.json";
    private static final String MODULE_LOAD_FAIL = "fixtures/jest/jest-module-load-fail.json";

    @TestFactory
    @DisplayName("divergence matrix over jest --json fixtures")
    Stream<DynamicTest> divergence_matrix_over_jest_json_fixtures() {
        return Stream.of(

                // ---- the Reporter (CONTEXT.md) name is "jest", pinned like "surefire"/"go-test" ----
                DynamicTest.dynamicTest("the_reporter_tool_name_is_jest", () -> {
                    NormalizedRun run = PARSER.parse(read(ALL_PASSED));
                    assertThat(run.tool()).isEqualTo("jest");
                }),

                // ---- AC: an assertion failure → TestFinding + best-effort file:line (location present) ----
                DynamicTest.dynamicTest("an_assertion_failure_is_a_test_finding_with_best_effort_file_line", () -> {
                    NormalizedRun run = PARSER.parse(read(LOC));

                    // 1 passed (pass.test.js), 1 failed (fail.test.js). The module-load suite contributes
                    // ZERO test counts — it is a container finding, not a test (ADR-0007 counts-from-elements).
                    assertThat(run.summary()).isEqualTo(new Summary(2, 1, 1, 0, 0));

                    assertThat(run.findings())
                            .filteredOn(f -> f instanceof TestFinding)
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf -> {
                                assertThat(tf.suite()).as("suite is the test-file basename")
                                        .isEqualTo("fail.test.js");
                                assertThat(tf.name()).isEqualTo("wrong expectation");
                                assertThat(tf.path()).as("no describe nesting → empty path").isEmpty();
                                assertThat(tf.outcome()).isEqualTo(Outcome.FAILED);
                                assertThat(tf.rawStatus()).isEqualTo("failed");
                                assertThat(tf.message()).contains("Object.is equality");
                                // best-effort file:line — file = name basename, line = location.line
                                assertThat(tf.source()).isNotNull();
                                assertThat(tf.source().file()).isEqualTo("fail.test.js");
                                assertThat(tf.source().line()).isEqualTo(2);
                            });
                    assertThat(run.ok()).as("a run with a failing test/suite is not ok").isFalse();
                }),

                // ---- AC (the G5 keystone) — a module-load failure → ContainerFinding(FILE, ERRORED) ----
                DynamicTest.dynamicTest("a_module_load_failure_is_a_container_finding_FILE_ERRORED_keyed_on_empty_assertionResults", () -> {
                    NormalizedRun run = PARSER.parse(read(LOC));

                    assertThat(run.findings())
                            .filteredOn(f -> f instanceof ContainerFinding)
                            .singleElement()
                            .isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                                assertThat(cf.scope()).isEqualTo(ContainerScope.FILE);
                                assertThat(cf.container()).isEqualTo("loadfail.test.js");
                                assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                                assertThat(cf.rawStatus()).isEqualTo("failed");
                                // the file-level '● Test suite failed to run …' message is carried
                                assertThat(cf.message()).contains("module load boom");
                                assertThat(cf.detail()).contains("Test suite failed to run");
                            });
                }),

                // ---- the no-owner discriminator is EMPTY assertionResults, NEVER testExecError ----
                DynamicTest.dynamicTest("the_module_load_owner_is_keyed_on_empty_assertionResults_not_testExecError", () -> {
                    // jest-module-load-fail.json carries NO testExecError field at all (NULL in jest 30);
                    // a parser that keyed on testExecError would emit NO container finding and false-green.
                    String json = read(MODULE_LOAD_FAIL);
                    assertThat(json).as("the fixture deliberately omits testExecError").doesNotContain("testExecError");

                    NormalizedRun run = PARSER.parse(json);

                    assertThat(run.findings())
                            .singleElement()
                            .isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                                assertThat(cf.scope()).isEqualTo(ContainerScope.FILE);
                                assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                            });
                }),

                // ---- the G5 keystone — a module-load-ONLY run is executedTests==0 yet NOT ok ----
                DynamicTest.dynamicTest("a_module_load_only_run_has_zero_executed_tests_but_is_not_ok", () -> {
                    NormalizedRun run = PARSER.parse(read(MODULE_LOAD_FAIL));

                    // No test ran (zero counts), yet the run is NOT ok — the module-load failure folds
                    // INTO the graph as a no-owner FILE finding. The use-case floor routes this to a
                    // test-failure envelope, NOT NO_TESTS_RUN (the run is not ok), the G5 keystone.
                    assertThat(run.summary()).isEqualTo(new Summary(0, 0, 0, 0, 0));
                    assertThat(run.findings()).hasSize(1);
                    assertThat(run.ok())
                            .as("a module-load-only run is NOT ok — the floor's G5 keystone")
                            .isFalse();
                }),

                // ---- location:null (no --testLocationInResults) must not NPE; file:line is best-effort ----
                DynamicTest.dynamicTest("a_null_location_does_not_npe_and_yields_no_line", () -> {
                    NormalizedRun run = PARSER.parse(read(ALL));

                    assertThat(run.summary()).isEqualTo(new Summary(2, 1, 1, 0, 0));
                    assertThat(run.findings())
                            .filteredOn(f -> f instanceof TestFinding)
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf -> {
                                assertThat(tf.suite()).isEqualTo("fail.test.js");
                                assertThat(tf.outcome()).isEqualTo(Outcome.FAILED);
                                // location was null → no derived line (the flag is load-bearing)
                                assertThat(tf.source() == null || tf.source().line() == null)
                                        .as("location:null → no derived line, never an NPE")
                                        .isTrue();
                            });
                    assertThat(run.ok()).isFalse();
                }),

                // ---- an all-green run → clean counts, no findings, ok; ancestorTitles fold into path ----
                DynamicTest.dynamicTest("an_all_passed_run_has_clean_counts_no_findings_and_is_ok", () -> {
                    NormalizedRun run = PARSER.parse(read(ALL_PASSED));

                    assertThat(run.summary()).isEqualTo(new Summary(2, 2, 0, 0, 0));
                    assertThat(run.findings()).isEmpty();
                    assertThat(run.ok()).isTrue();
                }),

                // ---- a null/blank report normalizes to an empty run, never throws ----
                DynamicTest.dynamicTest("a_null_report_normalizes_to_an_empty_run_never_throws", () -> {
                    NormalizedRun run = PARSER.parse(null);

                    assertThat(run.summary()).isEqualTo(new Summary(0, 0, 0, 0, 0));
                    assertThat(run.findings()).isEmpty();
                    assertThat(run.ok()).isTrue();
                })
        );
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = JestJsonParserTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
