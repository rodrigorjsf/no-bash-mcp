package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.Summary;
import dev.nobash.domain.result.TestFinding;
import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC2 / D30 — the test-failure Envelope serializes {@code failures[]} with each element's
 * {@code "kind"} discriminator so the agent branches {@link TestFinding} vs
 * {@link ContainerFinding}; the success Envelope is counts-only (no {@code failures}, no report).
 * Proven over micronaut-serde on the JVM with the real {@link ObjectMapper}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class EnvelopeSerdeTest {

    private static ApplicationContext context;
    private static ObjectMapper mapper;

    @BeforeAll
    static void boot() {
        context = ApplicationContext.run();
        mapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void shutdown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void a_test_failure_envelope_serializes_failures_with_kind_discriminators() throws Exception {
        List<Finding> findings = List.of(
                new TestFinding("some.Suite", "addFails", List.of(), Outcome.FAILED, "failure",
                        "boom", null, "stack"),
                new ContainerFinding(ContainerScope.SUITE, "some.Suite", Outcome.ERRORED, "error",
                        "setup failed", null, "stack"));
        Envelope env = Envelope.testFailure("run_tests", "mvn", new Summary(1, 0, 1, 0, 0), findings, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"ok\":false");
        assertThat(json).contains("\"failures\"");
        assertThat(json).contains("\"kind\":\"test\"");
        assertThat(json).contains("\"kind\":\"container\"");
    }

    @Test
    void a_success_envelope_is_counts_only_with_no_failures_array() throws Exception {
        Envelope env = Envelope.success("run_tests", "mvn", new Summary(3, 3, 0, 0, 0), null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("\"summary\"");
        // Counts-only: the failures array is absent/null (no report surfaced on a green run).
        assertThat(json).doesNotContain("\"kind\"");
    }

    @Test
    void a_report_not_produced_envelope_carries_the_handle() throws Exception {
        Envelope env = Envelope.operationalError("run_tests", ErrorCode.REPORT_NOT_PRODUCED,
                "No report produced.", "Run build.", new Handle("run-xyz"));

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("REPORT_NOT_PRODUCED");
        assertThat(json).contains("\"handle\"");
        assertThat(json).contains("run-xyz");
    }
}
