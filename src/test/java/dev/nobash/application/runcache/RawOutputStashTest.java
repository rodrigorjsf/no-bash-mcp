package dev.nobash.application.runcache;

import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.TestFinding;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * In-memory run cache: both {@code get_log} filters and the eviction bound (AC, issue #5).
 * Uses a fresh {@code new RawOutputStash()} in every test — never the DI singleton — so
 * Micronaut context state from other tests cannot pollute the eviction count.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class RawOutputStashTest {

    // ── Backward-compat slice-4 API ──────────────────────────────────────────

    @Test
    void stash_and_get_return_the_raw_output() {
        RawOutputStash cache = new RawOutputStash();
        String raw = "compiler error: cannot find symbol";

        Handle handle = cache.stash(raw);

        assertThat(cache.get(handle)).isEqualTo(raw);
    }

    @Test
    void get_returns_null_for_unknown_handle() {
        RawOutputStash cache = new RawOutputStash();
        assertThat(cache.get(new Handle("does-not-exist"))).isNull();
    }

    @Test
    void get_returns_null_for_null_handle() {
        RawOutputStash cache = new RawOutputStash();
        assertThat(cache.get(null)).isNull();
    }

    // ── Full RunRecord API ───────────────────────────────────────────────────

    @Test
    void put_and_getRecord_return_the_stored_record() {
        RawOutputStash cache = new RawOutputStash();
        RunRecord record = new RunRecord("raw output text", List.of());

        Handle handle = cache.put(record);

        assertThat(cache.getRecord(handle)).isEqualTo(record);
    }

    @Test
    void getRecord_returns_null_for_unknown_handle() {
        RawOutputStash cache = new RawOutputStash();
        assertThat(cache.getRecord(new Handle("missing"))).isNull();
    }

    // ── Eviction bound ───────────────────────────────────────────────────────

    @Test
    void the_oldest_entry_is_evicted_when_max_runs_is_exceeded() {
        RawOutputStash cache = new RawOutputStash();

        // Fill exactly MAX_RUNS entries; the first one will be evicted on the next put.
        Handle oldest = null;
        for (int i = 0; i < RawOutputStash.MAX_RUNS; i++) {
            Handle h = cache.stash("run-" + i);
            if (i == 0) oldest = h;
        }
        assertThat(cache.getRecord(oldest)).as("oldest is still present at the bound").isNotNull();

        // One more entry pushes the oldest out.
        cache.stash("run-extra");

        assertThat(cache.getRecord(oldest)).as("oldest entry is evicted past the bound").isNull();
        assertThat(cache.size()).isEqualTo(RawOutputStash.MAX_RUNS);
    }

    @Test
    void a_handle_within_the_last_N_is_still_valid() {
        RawOutputStash cache = new RawOutputStash();

        // Add MAX_RUNS - 1 entries, then add one more.  The second handle is always within range.
        Handle second = null;
        for (int i = 0; i < RawOutputStash.MAX_RUNS; i++) {
            Handle h = cache.stash("run-" + i);
            if (i == 1) second = h;
        }
        // second is at index 1 (not the oldest), should still be valid after filling to MAX.
        assertThat(cache.getRecord(second)).isNotNull();
    }

    // ── get_log filter: by test identity ─────────────────────────────────────

    @Test
    void getRecord_findings_contain_the_stored_test_finding() {
        RawOutputStash cache = new RawOutputStash();
        TestFinding finding = new TestFinding(
                "com.example.FooTest", "shouldFail", List.of(),
                Outcome.FAILED, "failure",
                "expected true but was false", null,
                "java.lang.AssertionError: expected true\n\tat FooTest.java:42");
        RunRecord record = new RunRecord("raw stderr", List.of(finding));

        Handle handle = cache.put(record);
        RunRecord retrieved = cache.getRecord(handle);

        assertThat(retrieved.findings()).singleElement().satisfies(f -> {
            assertThat(f).isInstanceOf(TestFinding.class);
            TestFinding tf = (TestFinding) f;
            assertThat(tf.suite()).isEqualTo("com.example.FooTest");
            assertThat(tf.name()).isEqualTo("shouldFail");
            assertThat(tf.detail()).contains("AssertionError");
        });
    }

    // ── Null handle to put ───────────────────────────────────────────────────

    @Test
    void put_null_record_stores_an_empty_record_gracefully() {
        RawOutputStash cache = new RawOutputStash();
        Handle handle = cache.put(null);
        assertThat(handle).isNotNull();
        assertThat(cache.getRecord(handle)).isNotNull();
        assertThat(cache.getRecord(handle).rawOutput()).isEmpty();
    }
}
