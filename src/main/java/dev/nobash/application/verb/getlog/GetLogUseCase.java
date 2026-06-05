package dev.nobash.application.verb.getlog;

import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.runcache.RunRecord;
import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.result.Finding;
import dev.nobash.domain.result.TestFinding;
import jakarta.inject.Singleton;

/**
 * The {@code get_log} use-case: expand a retained run result **without re-running** (the
 * anti-lossy keystone, gotcha G5). Lives in its own verb slice ({@code application/verb/getlog})
 * so the slice-isolation ArchUnit rule keeps it from depending on {@code application/verb/tests}.
 *
 * <p>Two filters:</p>
 * <ul>
 *   <li><b>By test identity</b> ({@code filter} non-null): locate the first {@link TestFinding}
 *       whose {@code suite+"."+name} equals {@code filter} and return its {@code detail()} (the
 *       full stack trace). If no such finding is found returns {@code null}.</li>
 *   <li><b>No filter</b> ({@code filter} null): return the whole retained raw output (stdout +
 *       stderr) verbatim.</li>
 * </ul>
 *
 * <p>An unknown or evicted handle returns {@code null} for both paths — a clean miss, never an
 * exception.</p>
 */
@Singleton
public class GetLogUseCase {

    private final RawOutputStash cache;

    public GetLogUseCase(RawOutputStash cache) {
        this.cache = cache;
    }

    /**
     * Expand a retained run result.
     *
     * @param handleId  the opaque id from a {@link Handle} returned with a previous run result;
     *                  null or blank returns {@code null} (unknown handle)
     * @param filter    optional test identity ({@code "suite.name"} or just {@code "name"});
     *                  when null returns the full raw output
     * @return the requested slice, or {@code null} when the handle is unknown/evicted or the
     *         filtered finding is not present
     */
    public String get(String handleId, String filter) {
        if (handleId == null || handleId.isBlank()) return null;
        RunRecord record = cache.getRecord(new Handle(handleId));
        if (record == null) return null;

        if (filter == null || filter.isBlank()) {
            // No filter: return the whole raw output.
            return record.rawOutput();
        }

        // Filter by test identity: first TestFinding whose identity matches.
        return record.findings().stream()
                .filter(f -> f instanceof TestFinding tf && matchesIdentity(tf, filter))
                .map(Finding::detail)
                .findFirst()
                .orElse(null);
    }

    /**
     * A test identity matches when the filter equals the {@code name} alone OR equals
     * {@code suite + "." + name} (the fully-qualified form). This lets agents use either the
     * short display name or the fully-qualified form interchangeably.
     */
    private static boolean matchesIdentity(TestFinding tf, String filter) {
        return filter.equals(tf.name())
                || filter.equals(tf.suite() + "." + tf.name());
    }
}
