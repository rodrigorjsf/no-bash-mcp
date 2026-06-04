package dev.nobash.application.verb.getlog;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.runcache.RunRecord;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.TestFinding;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code get_log} use-case: both filters and miss cases (AC, issue #5).
 * Uses a fresh {@code new RawOutputStash()} to stay isolated from the DI singleton.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GetLogUseCaseTest {

    private static final String RAW = "stdout-content\nstderr-content";
    private static final String DETAIL = "java.lang.AssertionError\n\tat FooTest.java:42";

    private static final TestFinding FAILING_TEST = new TestFinding(
            "com.example.FooTest", "shouldFail", List.of(),
            Outcome.FAILED, "failure", "expected true", null, DETAIL);

    private static final TestFinding OTHER_TEST = new TestFinding(
            "com.example.BarTest", "otherFail", List.of(),
            Outcome.FAILED, "failure", "other msg", null, "other-detail");

    /** Returns a fresh use-case + the stored handle id. */
    private GetLogEntry stash(RunRecord record) {
        RawOutputStash cache = new RawOutputStash();
        Handle handle = cache.put(record);
        return new GetLogEntry(new GetLogUseCase(cache), handle.id());
    }

    private record GetLogEntry(GetLogUseCase useCase, String handleId) {}

    // ── No-filter path: return the full raw output ────────────────────────────

    @Nested
    class no_filter {

        @Test
        void returns_the_full_raw_output_when_filter_is_null() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST)));

            assertThat(e.useCase().get(e.handleId(), null)).isEqualTo(RAW);
        }

        @Test
        void returns_the_full_raw_output_when_filter_is_blank() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST)));

            assertThat(e.useCase().get(e.handleId(), "  ")).isEqualTo(RAW);
        }

        @Test
        void returns_null_for_unknown_handle_with_no_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = new GetLogUseCase(cache);

            assertThat(useCase.get("does-not-exist", null)).isNull();
        }

        @Test
        void returns_null_for_null_handle_id_with_no_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = new GetLogUseCase(cache);

            assertThat(useCase.get(null, null)).isNull();
        }
    }

    // ── Filter by test identity ───────────────────────────────────────────────

    @Nested
    class filter_by_test_identity {

        @Test
        void returns_detail_when_filter_matches_short_name() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST)));

            assertThat(e.useCase().get(e.handleId(), "shouldFail")).isEqualTo(DETAIL);
        }

        @Test
        void returns_detail_when_filter_matches_fully_qualified_name() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST)));

            assertThat(e.useCase().get(e.handleId(), "com.example.FooTest.shouldFail"))
                    .isEqualTo(DETAIL);
        }

        @Test
        void returns_the_correct_finding_when_multiple_findings_are_present() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST, OTHER_TEST)));

            assertThat(e.useCase().get(e.handleId(), "otherFail")).isEqualTo("other-detail");
        }

        @Test
        void returns_null_when_filter_does_not_match_any_finding() {
            GetLogEntry e = stash(new RunRecord(RAW, List.of(FAILING_TEST)));

            assertThat(e.useCase().get(e.handleId(), "nonExistentTest")).isNull();
        }

        @Test
        void returns_null_for_unknown_handle_with_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = new GetLogUseCase(cache);

            assertThat(useCase.get("does-not-exist", "shouldFail")).isNull();
        }

        @Test
        void container_findings_are_not_matched_by_test_identity_filter() {
            ContainerFinding container = new ContainerFinding(
                    ContainerScope.SUITE, "com.example.FooTest",
                    Outcome.ERRORED, "error", "setup failed", null, "container-detail");
            GetLogEntry e = stash(new RunRecord(RAW, List.of(container)));

            // A ContainerFinding is not a TestFinding — the filter should not match it.
            assertThat(e.useCase().get(e.handleId(), "com.example.FooTest")).isNull();
        }
    }

    // ── Eviction: evicted handle returns null ─────────────────────────────────

    @Nested
    class eviction {

        @Test
        void evicted_handle_returns_null_for_no_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = new GetLogUseCase(cache);

            // Fill to MAX_RUNS, capturing the first handle.
            Handle first = null;
            for (int i = 0; i < RawOutputStash.MAX_RUNS; i++) {
                Handle h = cache.put(new RunRecord("run-" + i, List.of()));
                if (i == 0) first = h;
            }
            // Add one more to push the first out.
            cache.put(new RunRecord("evicting", List.of()));

            assertThat(useCase.get(first.id(), null))
                    .as("evicted handle returns null (no re-run)")
                    .isNull();
        }

        @Test
        void evicted_handle_returns_null_for_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = new GetLogUseCase(cache);

            Handle first = null;
            for (int i = 0; i < RawOutputStash.MAX_RUNS; i++) {
                Handle h = cache.put(new RunRecord("run-" + i, List.of(FAILING_TEST)));
                if (i == 0) first = h;
            }
            cache.put(new RunRecord("evicting", List.of()));

            assertThat(useCase.get(first.id(), "shouldFail"))
                    .as("evicted handle returns null even with a filter")
                    .isNull();
        }
    }
}
