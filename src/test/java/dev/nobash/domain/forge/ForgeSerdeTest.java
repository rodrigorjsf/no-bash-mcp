package dev.nobash.domain.forge;

import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Serde guard for the forge carriers (PRD-6, #102 regression): a {@code @Nullable} field on a
 * NESTED forge record MUST be OMITTED from the JSON when null, not emitted as {@code "field":null}.
 *
 * <p>The MCP tool {@code outputSchema} generated from {@link dev.nobash.domain.envelope.Envelope}
 * renders a {@code @Nullable String}/{@code Boolean} as a plain {@code "type":"string"}/{@code "boolean"}
 * (it never emits a nullable union — nullable properties are simply absent from {@code required}). So a
 * present-but-null value fails the SDK's output-schema validation ({@code null found, string expected}),
 * and micronaut-mcp then replaces the whole result with an {@code isError:true} text — the structured
 * envelope (and its {@code ok} flag) is dropped. {@link PrCheck#handle()} (null for passing/incomplete
 * checks and commit statuses) and {@link PrView#mergeable()} (null while GitHub computes mergeability)
 * hit this exactly. The fix is {@code @JsonInclude(NON_NULL)} on the record, matching {@code GitStatus}
 * / {@code GitBranchEntry}; this test locks it in at the fast surefire gate (the acceptance IT that
 * first caught it runs only in CI). Proven over the real micronaut-serde {@link ObjectMapper}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ForgeSerdeTest {

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
    void pr_check_omits_a_null_handle_so_the_output_schema_stays_satisfied() throws Exception {
        String json = mapper.writeValueAsString(new PrCheck("build", "success", null));

        assertThat(json)
                .as("a null handle must be OMITTED, never emitted as \"handle\":null")
                .doesNotContain("handle")
                .contains("\"name\":\"build\"")
                .contains("\"conclusion\":\"success\"");
    }

    @Test
    void pr_check_still_carries_a_present_handle() throws Exception {
        String json = mapper.writeValueAsString(new PrCheck("build", "failure", "h-42"));

        assertThat(json).contains("\"handle\":\"h-42\"");
    }

    @Test
    void pr_view_omits_a_null_mergeable_while_github_is_still_computing_it() throws Exception {
        String json = mapper.writeValueAsString(new PrView(
                "open", null, false, "feature-x", "deadbeef", "main", "none",
                new PrChecksSummary(0, 0, true)));

        assertThat(json)
                .as("a null mergeable must be OMITTED, never emitted as \"mergeable\":null")
                .doesNotContain("mergeable")
                .contains("\"state\":\"open\"");
    }
}
