package dev.nobash.domain.result;

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
 * Slice #4 obligation (issue #4, D30) — the polymorphic {@link Finding} graph must serialize to
 * the agent with a {@code "kind"} discriminator so the agent branches {@link TestFinding} vs
 * {@link ContainerFinding}. ADR-0007 / DESIGN §7 mandate
 * {@code @JsonTypeInfo(use=NAME, property="kind")} + {@code @JsonSubTypes}; micronaut-serde 3.0.0
 * honors only a SUBSET of Jackson annotations, so this test PROVES the import actually works on
 * the JVM (serialize AND deserialize — {@code defaultImpl}/{@code fail-on-null} bite on read-back).
 *
 * <p>This is the gate for the test-failure Envelope shape: {@code failures[]} carries
 * {@code Finding}s, and the agent can only act on them if {@code "kind":"test"}/{@code "container"}
 * is literally present in the JSON.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class FindingSerdeTest {

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
    void a_test_finding_serializes_with_a_kind_test_discriminator() throws Exception {
        Finding finding = new TestFinding("some.Suite", "addPasses", List.of(),
                Outcome.FAILED, "failure", "expected <5> but was <4>",
                new SourceRef("Some.java", 25), "stack trace");

        String json = mapper.writeValueAsString(finding);

        assertThat(json).contains("\"kind\":\"test\"");
        assertThat(json).contains("\"suite\":\"some.Suite\"");
    }

    @Test
    void a_container_finding_serializes_with_a_kind_container_discriminator() throws Exception {
        Finding finding = new ContainerFinding(ContainerScope.SUITE, "some.Suite",
                Outcome.ERRORED, "error", "setup failed", null, "stack trace");

        String json = mapper.writeValueAsString(finding);

        assertThat(json).contains("\"kind\":\"container\"");
        assertThat(json).contains("\"container\":\"some.Suite\"");
    }

    @Test
    void a_failures_list_round_trips_each_kind_back_to_its_concrete_type() throws Exception {
        List<Finding> failures = List.of(
                new TestFinding("some.Suite", "addFails", List.of(), Outcome.FAILED, "failure",
                        "boom", new SourceRef("Some.java", 25), "stack"),
                new ContainerFinding(ContainerScope.SUITE, "some.Suite", Outcome.ERRORED, "error",
                        "setup failed", null, "stack"));

        String json = mapper.writeValueAsString(failures);
        assertThat(json).contains("\"kind\":\"test\"").contains("\"kind\":\"container\"");

        List<Finding> back = mapper.readValue(json, io.micronaut.core.type.Argument.listOf(Finding.class));

        assertThat(back).hasSize(2);
        assertThat(back.get(0)).isInstanceOf(TestFinding.class);
        assertThat(back.get(1)).isInstanceOf(ContainerFinding.class);
    }
}
