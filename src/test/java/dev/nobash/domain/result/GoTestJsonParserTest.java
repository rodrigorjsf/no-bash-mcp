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
 * The divergence matrix for {@link GoTestJsonParser} (PRD-3 slice 2, ADR-0007). Each fixture is a
 * captured (or grammar-authored) {@code go test -json} NDJSON stream (see
 * {@code fixtures/go/README.md} for provenance); the test reads its bytes and hands the in-memory
 * content to the pure, I/O-free parser — the Go analogue of {@code SurefireNormalizerTest}.
 *
 * <p>The matrix proves the frozen rules on the Go format: per-test counts (not a header); a
 * subtest failure attributed to the LEAF with the redundant parent/package suppressed; a Go
 * build failure folded INTO the graph as {@code ContainerFinding(PACKAGE, ERRORED)} (the run is
 * NOT ok — the floor's G5 keystone); and best-effort {@code file:line}.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoTestJsonParserTest {

    private static final GoTestJsonParser PARSER = new GoTestJsonParser();

    private static final String PASS_FAIL_SKIP = "fixtures/go/go-pass-fail-skip.ndjson";
    private static final String SUBTEST_FAIL = "fixtures/go/go-subtest-fail.ndjson";
    private static final String ALL_PASSED = "fixtures/go/go-all-passed.ndjson";
    private static final String BUILD_FAIL_SRC = "fixtures/go/go-build-fail-src.ndjson";
    private static final String BUILD_FAIL_TEST = "fixtures/go/go-build-fail-test.ndjson";
    private static final String BUILD_FAIL_MULTI = "fixtures/go/go-build-fail-multi.ndjson";

    @TestFactory
    @DisplayName("divergence matrix over go test -json fixtures")
    Stream<DynamicTest> divergence_matrix_over_go_test_json_fixtures() {
        return Stream.of(

                // ---- the Reporter (CONTEXT.md) name is "go-test", pinned like "surefire" ----
                DynamicTest.dynamicTest("the_reporter_tool_name_is_go_test", () -> {
                    NormalizedRun run = PARSER.parse(read(ALL_PASSED));
                    assertThat(run.tool()).isEqualTo("go-test");
                }),

                // ---- AC: pass/failing/skipped normalized into one schema; counts per-test ----
                DynamicTest.dynamicTest("pass_fail_skip_counts_derive_from_per_test_events", () -> {
                    NormalizedRun run = PARSER.parse(read(PASS_FAIL_SKIP));

                    // 1 passed, 1 failed, 1 skipped (the package-level fail is suppressed — a leaf
                    // already carries the failure, so it is NOT a no-owner container finding).
                    assertThat(run.summary()).isEqualTo(new Summary(3, 1, 1, 0, 1));

                    assertThat(run.findings())
                            .as("only the failing leaf surfaces a finding (pass/skip add none)")
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf -> {
                                assertThat(tf.suite()).isEqualTo("nbm/calc");
                                assertThat(tf.name()).isEqualTo("TestSubtract");
                                assertThat(tf.path()).isEmpty();
                                assertThat(tf.outcome()).isEqualTo(Outcome.FAILED);
                                assertThat(tf.rawStatus()).isEqualTo("fail");
                                // best-effort file:line from the calc_test.go:21 output line
                                assertThat(tf.source()).isNotNull();
                                assertThat(tf.source().file()).isEqualTo("calc_test.go");
                                assertThat(tf.source().line()).isEqualTo(21);
                            });
                    assertThat(run.ok()).as("a run with a failing test is not ok").isFalse();
                }),

                // ---- AC: a subtest failure is attributed to the LEAF; parent+package suppressed ----
                DynamicTest.dynamicTest("a_subtest_failure_is_attributed_to_the_leaf_redundant_parent_and_package_suppressed", () -> {
                    NormalizedRun run = PARSER.parse(read(SUBTEST_FAIL));

                    // 1 subtest passed (accepts_valid), 1 subtest failed (rejects_empty). The parent
                    // TestValidate `fail` and the package `fail` are BOTH suppressed — no double-count.
                    assertThat(run.summary()).isEqualTo(new Summary(2, 1, 1, 0, 0));

                    assertThat(run.findings())
                            .as("exactly ONE finding — the leaf; parent + package fail suppressed")
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf -> {
                                assertThat(tf.suite()).isEqualTo("nbm/table");
                                assertThat(tf.name()).as("the leaf subtest name, not the parent")
                                        .isEqualTo("rejects_empty");
                                assertThat(tf.path()).as("the parent folds into the flexible path")
                                        .containsExactly("TestValidate");
                                assertThat(tf.outcome()).isEqualTo(Outcome.FAILED);
                                assertThat(tf.source()).isNotNull();
                                assertThat(tf.source().file()).isEqualTo("validate_test.go");
                                assertThat(tf.source().line()).isEqualTo(42);
                            });
                    assertThat(run.ok()).isFalse();
                }),

                // ---- an all-green run → no findings, ok ----
                DynamicTest.dynamicTest("an_all_passed_run_has_clean_counts_no_findings_and_is_ok", () -> {
                    NormalizedRun run = PARSER.parse(read(ALL_PASSED));

                    assertThat(run.summary()).isEqualTo(new Summary(2, 2, 0, 0, 0));
                    assertThat(run.findings()).isEmpty();
                    assertThat(run.ok()).isTrue();
                }),

                // ---- AC (the G5 keystone) — a non-compiling Go package → ContainerFinding(PACKAGE, ERRORED) ----
                DynamicTest.dynamicTest("a_non_compiling_go_source_package_is_a_container_finding_PACKAGE_ERRORED_not_green", () -> {
                    NormalizedRun run = PARSER.parse(read(BUILD_FAIL_SRC));

                    // No test ran (zero counts), yet the run is NOT ok — the build failure folds
                    // INTO the graph as a no-owner PACKAGE finding (NOT a Maven-style report-absence).
                    assertThat(run.summary()).isEqualTo(new Summary(0, 0, 0, 0, 0));
                    assertThat(run.findings())
                            .singleElement()
                            .isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                                assertThat(cf.scope()).isEqualTo(ContainerScope.PACKAGE);
                                assertThat(cf.container()).isEqualTo("nbm/gofail/brokensrc");
                                assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                                assertThat(cf.rawStatus()).isEqualTo("build-fail");
                                // the compiler diagnostic is retained as the message
                                assertThat(cf.message()).contains("cannot use");
                                // best-effort file:line from brokensrc/brokensrc.go:5:9 (col dropped)
                                assertThat(cf.source()).isNotNull();
                                assertThat(cf.source().file()).isEqualTo("brokensrc/brokensrc.go");
                                assertThat(cf.source().line()).isEqualTo(5);
                            });
                    assertThat(run.ok())
                            .as("a build-fail package is NOT ok — the floor's G5 keystone")
                            .isFalse();
                }),

                // ---- a non-compiling TEST file → same fold, the test-file diagnostic ----
                DynamicTest.dynamicTest("a_non_compiling_go_test_file_is_a_container_finding_PACKAGE_ERRORED", () -> {
                    NormalizedRun run = PARSER.parse(read(BUILD_FAIL_TEST));

                    assertThat(run.findings())
                            .singleElement()
                            .isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                                assertThat(cf.scope()).isEqualTo(ContainerScope.PACKAGE);
                                assertThat(cf.container()).isEqualTo("nbm/gofail/brokentest");
                                assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                                assertThat(cf.message()).contains("undefined");
                                assertThat(cf.source().file()).isEqualTo("brokentest/brokentest_test.go");
                                assertThat(cf.source().line()).isEqualTo(7);
                            });
                    assertThat(run.ok()).isFalse();
                }),

                // ---- two packages fail to compile in one run → two distinct package findings ----
                DynamicTest.dynamicTest("two_packages_failing_to_compile_yield_two_distinct_PACKAGE_ERRORED_findings", () -> {
                    NormalizedRun run = PARSER.parse(read(BUILD_FAIL_MULTI));

                    assertThat(run.summary()).isEqualTo(new Summary(0, 0, 0, 0, 0));
                    assertThat(run.findings())
                            .hasSize(2)
                            .allSatisfy(f -> assertThat(f).isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                                assertThat(cf.scope()).isEqualTo(ContainerScope.PACKAGE);
                                assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                            }));
                    assertThat(run.findings())
                            .extracting(f -> ((ContainerFinding) f).container())
                            .containsExactlyInAnyOrder("nbm/gofail/brokensrc", "nbm/gofail/brokentest");
                    assertThat(run.ok()).isFalse();
                })
        );
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = GoTestJsonParserTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
