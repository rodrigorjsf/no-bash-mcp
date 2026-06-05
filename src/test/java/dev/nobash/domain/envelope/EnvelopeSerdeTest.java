package dev.nobash.domain.envelope;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.git.GitStatus;
import dev.nobash.domain.git.GitStatusEntry;
import dev.nobash.domain.result.BuildSummary;
import dev.nobash.domain.result.CompileDiagnostic;
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
 * Also proves the build-verb shapes (ADR-0009): {@code buildFailure} with {@code diagnostics[]}
 * and {@code buildSuccess} with {@code buildSummary}.
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

    // ---- build verb serde (ADR-0009) ----

    @Test
    void a_build_failure_envelope_serializes_diagnostics_with_severity_and_no_failures() throws Exception {
        List<CompileDiagnostic> diagnostics = List.of(
                new CompileDiagnostic("/src/Foo.java", 10, 5, "ERROR", "cannot find symbol"),
                new CompileDiagnostic("/src/Bar.java", 20, 1, "WARNING", "unchecked cast"));
        BuildSummary buildSummary = new BuildSummary(1, 1);
        Envelope env = Envelope.buildFailure("build", "mvn", buildSummary, diagnostics, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"ok\":false");
        assertThat(json).contains("\"untrusted\":true");
        assertThat(json).contains("\"diagnostics\"");
        assertThat(json).contains("\"severity\":\"ERROR\"");
        assertThat(json).contains("\"severity\":\"WARNING\"");
        assertThat(json).contains("\"buildSummary\"");
        // failures must be absent (build compile-failure uses diagnostics[], not failures[])
        assertThat(json).doesNotContain("\"failures\"");
        assertThat(json).doesNotContain("\"kind\"");
    }

    @Test
    void a_build_success_envelope_is_counts_only_with_no_diagnostics() throws Exception {
        BuildSummary buildSummary = new BuildSummary(0, 3);
        Envelope env = Envelope.buildSuccess("build", "mvn", buildSummary, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("\"buildSummary\"");
        assertThat(json).contains("\"errors\":0");
        assertThat(json).contains("\"warnings\":3");
        // Counts-only: no diagnostics, no failures
        assertThat(json).doesNotContain("\"diagnostics\"");
        assertThat(json).doesNotContain("\"failures\"");
    }

    @Test
    void build_failure_diagnostic_message_is_p9_neutralized() throws Exception {
        // ANSI CSI escape sequence: ESC (U+001B) + "[31m" is standard red-foreground code.
        // The OutboundNeutralizer must strip the ESC byte and its bracket sequence (CSI pattern).
        char esc = (char) 0x1B;
        String ansiMessage = esc + "[31mcannot find symbol" + esc + "[0m";
        List<CompileDiagnostic> diagnostics = List.of(
                new CompileDiagnostic("/src/Foo.java", 5, 1, "ERROR", ansiMessage));
        BuildSummary buildSummary = new BuildSummary(1, 0);
        Envelope env = Envelope.buildFailure("build", "mvn", buildSummary, diagnostics, null);

        String json = mapper.writeValueAsString(env);

        // Visible text must survive neutralization
        assertThat(json).contains("cannot find symbol");
        // The raw ESC byte must be absent from the serialized output
        assertThat(json).doesNotContain(String.valueOf(esc));
    }

    // ---- git_status verb serde (PRD-002) ----

    @Test
    void a_git_status_envelope_serializes_the_git_status_shape_with_null_manager() throws Exception {
        GitStatus status = new GitStatus("main", false, "origin/main", 1, 0,
                List.of(GitStatusEntry.of("src/staged.txt", "M.")),
                List.of(GitStatusEntry.of("src/unstaged.txt", ".M")),
                List.of(GitStatusEntry.of("untracked.txt", "?")));
        Envelope env = Envelope.gitStatus("git_status", status, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"ok\":true");
        assertThat(json).contains("\"gitStatus\"");
        assertThat(json).contains("\"branch\":\"main\"");
        assertThat(json).contains("\"upstream\":\"origin/main\"");
        assertThat(json).contains("\"ahead\":1");
        assertThat(json).contains("\"staged\"");
        assertThat(json).contains("\"unstaged\"");
        assertThat(json).contains("\"untracked\"");
        // manager is null for git verbs → omitted by serde (null-omission).
        assertThat(json).doesNotContain("\"manager\"");
        // git_status carries no test/build payloads.
        assertThat(json).doesNotContain("\"failures\"");
        assertThat(json).doesNotContain("\"diagnostics\"");
        assertThat(json).doesNotContain("\"summary\"");
        assertThat(json).doesNotContain("\"buildSummary\"");
    }

    @Test
    void a_git_status_envelope_is_marked_untrusted_and_neutralizes_repo_derived_paths()
            throws Exception {
        // A branch name and a path carrying an ANSI escape — both repo-derived and must be
        // neutralized; the envelope must be flagged untrusted.
        char esc = (char) 0x1B;
        String ansiBranch = esc + "[31mmain" + esc + "[0m";
        String ansiPath = esc + "[32mevil.txt" + esc + "[0m";
        GitStatus status = new GitStatus(ansiBranch, false, null, null, null,
                List.of(),
                List.of(),
                List.of(GitStatusEntry.of(ansiPath, "?")));
        Envelope env = Envelope.gitStatus("git_status", status, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"untrusted\":true");
        // Visible text survives neutralization; the raw ESC byte is stripped.
        assertThat(json).contains("main");
        assertThat(json).contains("evil.txt");
        assertThat(json).doesNotContain(String.valueOf(esc));
    }

    @Test
    void a_clean_git_status_envelope_serializes_null_ahead_behind_and_upstream_as_null()
            throws Exception {
        // No-upstream repo: upstream/ahead/behind are null. Nested-record null fields are
        // serialized explicitly as null by micronaut-serde (the top-level Envelope omits its own
        // nulls via @JsonSchema, but the nested GitStatus emits null members) — the agent sees an
        // unambiguous null rather than a misleading default, which is the contract that matters.
        GitStatus status = new GitStatus("feature", false, null, null, null,
                List.of(), List.of(), List.of());
        Envelope env = Envelope.gitStatus("git_status", status, null);

        String json = mapper.writeValueAsString(env);

        assertThat(json).contains("\"branch\":\"feature\"");
        assertThat(json).contains("\"upstream\":null");
        assertThat(json).contains("\"ahead\":null");
        assertThat(json).contains("\"behind\":null");
        // The empty buckets serialize as empty arrays (stable shape), never null.
        assertThat(json).contains("\"staged\":[]");
        assertThat(json).contains("\"unstaged\":[]");
        assertThat(json).contains("\"untracked\":[]");
    }
}
