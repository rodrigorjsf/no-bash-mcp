package dev.nobash.application.verb.getlog;

import dev.nobash.application.forge.ForgeLogHandleRegistry;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.runcache.RunRecord;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.error.ErrorCode;
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
        return new GetLogEntry(newUseCase(cache), handle.id());
    }

    private record GetLogEntry(GetLogUseCase useCase, String handleId) {}

    /** A use-case over the given stash with an empty forge registry and a forge port that explodes
     *  if consulted — the stash-path tests must never touch the forge. */
    private static GetLogUseCase newUseCase(RawOutputStash cache) {
        return new GetLogUseCase(cache, new ForgeLogHandleRegistry(), new ExplodingForgePort());
    }

    /** A forge port that fails the test if consulted (stash-path isolation). */
    private static final class ExplodingForgePort implements ForgePort {
        @Override
        public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
            throw new AssertionError("forge port must not be consulted on the stash path");
        }

        @Override
        public String fetchJobLog(ForgeLogRequest request) {
            throw new AssertionError("forge port must not be consulted on the stash path");
        }

        @Override
        public dev.nobash.domain.forge.PrView fetchPrView(dev.nobash.domain.port.out.ForgePrRequest request) {
            throw new AssertionError("forge port must not be consulted on the stash path");
        }

        @Override
        public String fetchPrDiff(dev.nobash.domain.port.out.ForgePrRequest request) {
            throw new AssertionError("forge port must not be consulted on the stash path");
        }
    }

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
            GetLogUseCase useCase = newUseCase(cache);

            assertThat(useCase.get("does-not-exist", null)).isNull();
        }

        @Test
        void returns_null_for_null_handle_id_with_no_filter() {
            RawOutputStash cache = new RawOutputStash();
            GetLogUseCase useCase = newUseCase(cache);

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
            GetLogUseCase useCase = newUseCase(cache);

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
            GetLogUseCase useCase = newUseCase(cache);

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
            GetLogUseCase useCase = newUseCase(cache);

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

    // ── Forge check handle: lazy job-log fetch via the 302 flow (PRD-6 S1, #99) ──

    @Nested
    class forge_check_handle {

        private static ForgePort logPortReturning(String log, long expectedJobId) {
            return new ForgePort() {
                @Override
                public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
                    throw new AssertionError("fetchChecks must not be called by get_log");
                }

                @Override
                public String fetchJobLog(ForgeLogRequest request) {
                    assertThat(request.jobId()).isEqualTo(expectedJobId);
                    return log;
                }

                @Override
                public dev.nobash.domain.forge.PrView fetchPrView(dev.nobash.domain.port.out.ForgePrRequest request) {
                    throw new AssertionError("fetchPrView must not be called by get_log");
                }

                @Override
                public String fetchPrDiff(dev.nobash.domain.port.out.ForgePrRequest request) {
                    throw new AssertionError("fetchPrDiff must not be called by get_log");
                }
            };
        }

        @Test
        void a_handle_not_in_the_stash_fetches_the_forge_job_log() {
            RawOutputStash cache = new RawOutputStash();
            ForgeLogHandleRegistry registry = new ForgeLogHandleRegistry();
            String handleId = registry.register(
                    new ForgeLogRequest("https://api.github.com", null, null, "octo", "hello", 42L));
            GetLogUseCase useCase =
                    new GetLogUseCase(cache, registry, logPortReturning("FORGE JOB LOG", 42L));

            assertThat(useCase.get(handleId, null)).isEqualTo("FORGE JOB LOG");
        }

        @Test
        void a_forge_fetch_failure_returns_a_diagnostic_string_not_a_lie() {
            RawOutputStash cache = new RawOutputStash();
            ForgeLogHandleRegistry registry = new ForgeLogHandleRegistry();
            String handleId = registry.register(
                    new ForgeLogRequest("https://api.github.com", null, null, "octo", "hello", 7L));
            ForgePort forge = new ForgePort() {
                @Override
                public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
                    throw new AssertionError();
                }

                @Override
                public String fetchJobLog(ForgeLogRequest request) {
                    throw new ForgeAccessException(ErrorCode.FORGE_RATE_LIMITED,
                            "rate limited", "wait", "60");
                }

                @Override
                public dev.nobash.domain.forge.PrView fetchPrView(dev.nobash.domain.port.out.ForgePrRequest request) {
                    throw new AssertionError();
                }

                @Override
                public String fetchPrDiff(dev.nobash.domain.port.out.ForgePrRequest request) {
                    throw new AssertionError();
                }
            };
            GetLogUseCase useCase = new GetLogUseCase(cache, registry, forge);

            assertThat(useCase.get(handleId, null))
                    .contains("could not fetch the forge job log");
        }

        @Test
        void an_unknown_handle_absent_from_both_stash_and_registry_returns_null() {
            GetLogUseCase useCase = newUseCase(new RawOutputStash());

            assertThat(useCase.get("forge-log-does-not-exist", null)).isNull();
        }
    }
}
