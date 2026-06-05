package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.Summary;
import dev.nobash.domain.result.TestFinding;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The three Envelope shapes (D30, DESIGN §6): the counts-only <b>success</b>, the
 * <b>test-failure</b> carrying top-level {@code failures[]}, and the <b>operational-error</b>
 * (with an optional {@link Handle}). Pure unit assertions on the factories; the serde wire shape
 * of {@code failures[]} (the {@code "kind"} discriminator) is proven in {@link EnvelopeSerdeTest}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EnvelopeTest {

    @Test
    void success_is_counts_only_ok_true_and_surfaces_no_report() {
        Summary summary = new Summary(3, 3, 0, 0, 0);

        Envelope env = Envelope.success("run_tests", "mvn", summary, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.verb()).isEqualTo("run_tests");
        assertThat(env.manager()).isEqualTo("mvn");
        assertThat(env.summary()).isEqualTo(summary);
        // Counts-only: no failures surfaced, no error.
        assertThat(env.failures()).isNull();
        assertThat(env.error()).isNull();
    }

    @Test
    void test_failure_is_ok_false_and_carries_the_findings_as_top_level_failures() {
        Summary summary = new Summary(2, 1, 1, 0, 0);
        List<Finding> findings = List.of(
                new TestFinding("some.Suite", "addFails", List.of(), Outcome.FAILED, "failure",
                        "boom", null, "stack"));

        Envelope env = Envelope.testFailure("run_tests", "mvn", summary, findings, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.verb()).isEqualTo("run_tests");
        assertThat(env.manager()).isEqualTo("mvn");
        assertThat(env.summary()).isEqualTo(summary);
        assertThat(env.failures()).isEqualTo(findings);
        assertThat(env.error()).isNull();
    }

    @Test
    void a_container_only_run_is_a_test_failure_envelope_carrying_the_container_finding() {
        Summary summary = new Summary(0, 0, 0, 0, 0);
        List<Finding> findings = List.of(
                new ContainerFinding(ContainerScope.SUITE, "some.Suite", Outcome.ERRORED, "error",
                        "setup failed", null, "stack"));

        Envelope env = Envelope.testFailure("run_tests", "mvn", summary, findings, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.failures()).singleElement().isInstanceOf(ContainerFinding.class);
    }

    @Test
    void operational_error_with_a_handle_carries_both_the_error_and_the_handle() {
        Handle handle = new Handle("run-123");

        Envelope env = Envelope.operationalError("run_tests", ErrorCode.REPORT_NOT_PRODUCED,
                "No report was produced (compile failure).", "Run build to see the compiler errors.",
                handle);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.REPORT_NOT_PRODUCED);
        assertThat(env.handle()).isEqualTo(handle);
        assertThat(env.failures()).isNull();
        assertThat(env.summary()).isNull();
    }

    @Test
    void the_existing_three_arg_operational_error_factory_still_returns_a_handleless_error() {
        Envelope env = Envelope.operationalError("run_tests", ErrorCode.INVALID_PATH,
                "No path.", "Pass a path.");

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
        assertThat(env.handle()).isNull();
    }
}
