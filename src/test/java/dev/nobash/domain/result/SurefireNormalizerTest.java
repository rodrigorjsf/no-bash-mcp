package dev.nobash.domain.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The divergence matrix for {@link SurefireNormalizer} (issue #3, ADR-0007). Each golden
 * fixture is a REAL captured Surefire report (see {@code fixtures/maven/README.md} for
 * provenance); the test reads its bytes and hands the in-memory content to the pure,
 * I/O-free normalizer.
 *
 * <p>The matrix proves the frozen counting/identity rules: counts come from
 * {@code <testcase>} not the header (AC3, rule 5); {@code <error>} → ERRORED and
 * {@code <failure>} → FAILED (AC4, rule 2); and the container-only run is NOT ok though its
 * test counts are clean (AC5, rule 1 — the G5 keystone).</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SurefireNormalizerTest {

    private static final SurefireNormalizer NORMALIZER = new SurefireNormalizer();

    private static final String CONTAINER = "fixtures/maven/surefire-container-beforeall-error.xml";
    private static final String NORMAL = "fixtures/maven/surefire-normal-error-failure-skipped.xml";
    private static final String PARAM_OUTER = "fixtures/maven/surefire-paramnested-outer.xml";
    private static final String PARAM_NESTED = "fixtures/maven/surefire-paramnested-whennegative.xml";

    /**
     * AC2 — the divergence matrix as a {@code @TestFactory} stream of dynamic tests over the
     * golden fixtures. Each case asserts one frozen rule.
     */
    @TestFactory
    @DisplayName("divergence matrix over golden Surefire fixtures")
    Stream<DynamicTest> divergence_matrix_over_golden_surefire_fixtures() {
        return Stream.of(

                // AC5 / rule 1 — the G5 keystone. A container-only run (a real @BeforeAll
                // throw) has ZERO test-level findings and all-zero TEST counts, yet is NOT ok
                // because its only finding is a ContainerFinding(SUITE, ERRORED). Deriving ok
                // from counts would false-green it. This is the single most load-bearing
                // assertion in the slice. (Committed RED before ok() was findings-based.)
                DynamicTest.dynamicTest("container_only_run_is_not_ok_though_test_counts_are_clean", () -> {
                    NormalizedRun run = NORMALIZER.normalize(read(CONTAINER));

                    assertThat(run.summary().failed()).isZero();
                    assertThat(run.summary().errored()).isZero();
                    assertThat(run.summary().total()).isZero();
                    assertThat(run.findings()).singleElement().isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                        assertThat(cf.scope()).isEqualTo(ContainerScope.SUITE);
                        assertThat(cf.container()).isEqualTo("nobash.proto.SetupBrokenTests");
                        assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                        assertThat(cf.rawStatus()).isEqualTo("error");
                    });
                    assertThat(run.ok()).as("container-only run must NOT be ok (G5)").isFalse();
                }),

                // AC4 / rule 2 — <error> → ERRORED, <failure> → FAILED, proven on the real
                // NormalTests report. Counts come from the six <testcase> elements.
                DynamicTest.dynamicTest("error_maps_to_errored_and_failure_maps_to_failed", () -> {
                    NormalizedRun run = NORMALIZER.normalize(read(NORMAL));

                    // The Reporter (CONTEXT.md) is "surefire" — NOT the spike's wrong
                    // "maven-surefire". Slice #4 wraps this normalizer expecting that value.
                    assertThat(run.tool()).isEqualTo("surefire");

                    assertThat(run.summary())
                            .isEqualTo(new Summary(6, 2, 2, 1, 1));

                    assertThat(run.findings()).filteredOn(f -> f instanceof TestFinding tf
                                    && tf.name().equals("throwsError"))
                            .singleElement()
                            .satisfies(f -> {
                                assertThat(f.outcome()).isEqualTo(Outcome.ERRORED);
                                assertThat(f.rawStatus()).isEqualTo("error");
                            });

                    assertThat(run.findings()).filteredOn(f -> f instanceof TestFinding tf
                                    && tf.name().equals("addFailsAssertion"))
                            .singleElement()
                            .satisfies(f -> {
                                assertThat(f.outcome()).isEqualTo(Outcome.FAILED);
                                assertThat(f.rawStatus()).isEqualTo("failure");
                            });

                    assertThat(run.ok()).as("a run with a failing test is not ok").isFalse();
                }),

                // AC3 / rule 5 — the <testsuite tests=> header is NOT trusted. The outer file
                // header says tests="0" with ZERO <testcase> elements; the real cases live in
                // the $WhenNegative sibling. Folding both, identity (suite) comes from each
                // <testcase classname=>, NOT the file's <testsuite name=>. A header-truster
                // would stamp every case in the $WhenNegative file with the suite
                // "spike.ParamNestedTests$WhenNegative" — but isEven(int)[3] carries
                // classname="spike.ParamNestedTests". Attribution, not count, is the proof.
                DynamicTest.dynamicTest("testcase_classname_not_the_testsuite_header_drives_identity", () -> {
                    NormalizedRun run = NORMALIZER.normalizeAll(List.of(read(PARAM_OUTER), read(PARAM_NESTED)));

                    // 12 real testcases, 5 failing — the header on the outer file (tests="0")
                    // would have lost all of them.
                    assertThat(run.summary().total()).isEqualTo(12);
                    assertThat(run.summary().failed()).isEqualTo(5);

                    // A parametrized case physically written into the $WhenNegative file but
                    // carrying classname="spike.ParamNestedTests" keeps THAT suite identity.
                    assertThat(run.findings()).filteredOn(f -> f instanceof TestFinding tf
                                    && tf.name().equals("isEven(int)[3]"))
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf ->
                                    assertThat(tf.suite()).isEqualTo("spike.ParamNestedTests"));

                    // And a genuinely-nested case keeps the nested-class suite.
                    assertThat(run.findings()).filteredOn(f -> f instanceof TestFinding tf
                                    && tf.name().equals("failsDirectly"))
                            .singleElement()
                            .isInstanceOfSatisfying(TestFinding.class, tf ->
                                    assertThat(tf.suite()).isEqualTo("spike.ParamNestedTests$WhenNegative"));

                    assertThat(run.ok()).isFalse();
                })
        );
    }

    /**
     * AC1 / AC6 — the carried ADR-0007 G5 guard. This is the ONE hand-built artifact the
     * slice permits, **carried verbatim from ADR-0007** ("the G5 container-only guard (case
     * C) is asserted against a hand-built {@code ContainerFinding}"). It asserts the
     * container-aware {@code ok()} invariant DIRECTLY on a synthetic record, independent of
     * any XML parse: a run whose only finding is a {@code ContainerFinding(ERRORED)} with
     * all-zero TEST counts is NOT ok.
     */
    @Test
    void carried_adr_0007_hand_built_container_finding_makes_a_clean_count_run_not_ok() {
        ContainerFinding handBuilt = new ContainerFinding(
                ContainerScope.SUITE, "some.Suite", Outcome.ERRORED, "error",
                "setup failed", null, "carried from ADR-0007 G5 case C");

        NormalizedRun run = new NormalizedRun(
                "surefire", new Summary(0, 0, 0, 0, 0), List.of(handBuilt));

        assertThat(run.summary().failed()).isZero();
        assertThat(run.summary().errored()).isZero();
        assertThat(run.ok())
                .as("ok() is container-aware: a ContainerFinding(ERRORED) with clean counts is NOT ok")
                .isFalse();
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = SurefireNormalizerTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
