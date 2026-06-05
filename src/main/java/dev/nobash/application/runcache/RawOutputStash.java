package dev.nobash.application.runcache;

import dev.nobash.domain.envelope.Handle;
import dev.nobash.domain.result.Finding;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Session-scoped, in-memory run cache. Every completed run (success, test-failure, or
 * compile-failure) is retained here, indexed by an opaque {@link Handle}. The full
 * {@link RunRecord} is kept (raw output + normalized findings) so {@code get_log} can
 * expand exactly the slice the agent requests without re-running.
 *
 * <p>Retention policy: last {@link #MAX_RUNS} runs (insertion order); the oldest entry is
 * evicted when the bound is exceeded. TTL, byte-cap eviction, and disk spill are deferred to
 * the operational-hardening PRD (issue #5 explicit scope).</p>
 *
 * <p>The class also preserves the slice-4 API ({@link #stash(String)} / {@link #get(Handle)})
 * so existing callers at the {@code REPORT_NOT_PRODUCED} path continue to work without
 * change.</p>
 *
 * <p>Thread-safety: the store is a {@code synchronized} {@link LinkedHashMap} with
 * {@code removeEldestEntry} — appropriate for a single-session STDIO singleton with sequential
 * verb dispatch.</p>
 */
@Singleton
public class RawOutputStash {

    /** The maximum number of runs retained. Oldest-inserted entry is evicted past this bound. */
    public static final int MAX_RUNS = 10;

    private final Map<String, RunRecord> store;

    public RawOutputStash() {
        this.store = new LinkedHashMap<>(MAX_RUNS * 2, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, RunRecord> eldest) {
                return size() > MAX_RUNS;
            }
        };
    }

    // ---- slice-5 run-cache API -----------------------------------------------

    /**
     * Retain a full {@link RunRecord} and return the {@link Handle} that points at it.
     *
     * @param record the full run record (raw output + findings); never null
     * @return a fresh handle whose id keys the stored record
     */
    public synchronized Handle put(RunRecord record) {
        String id = UUID.randomUUID().toString();
        store.put(id, record == null ? new RunRecord("", List.of()) : record);
        return new Handle(id);
    }

    /**
     * Retrieve the full {@link RunRecord} for a previously stored handle.
     *
     * @param handle a handle returned by {@link #put(RunRecord)} or {@link #stash(String)}
     * @return the retained record, or {@code null} if the handle is unknown or evicted
     */
    public synchronized RunRecord getRecord(Handle handle) {
        if (handle == null) return null;
        return store.get(handle.id());
    }

    // ---- slice-4 compile-failure API (preserved for backward compatibility) ----

    /**
     * Stash raw output (e.g. compiler stderr for {@code REPORT_NOT_PRODUCED}) and return the
     * {@link Handle} that points at it. Delegates to {@link #put(RunRecord)} with an empty
     * findings list so the record is subject to normal eviction.
     *
     * @param rawOutput the raw output to retain; null is treated as empty
     * @return a fresh handle
     */
    public Handle stash(String rawOutput) {
        return put(new RunRecord(rawOutput, List.of()));
    }

    /**
     * Retrieve the raw output previously stored via {@link #stash(String)}.
     *
     * @param handle a handle returned by {@link #stash(String)} or {@link #put(RunRecord)}
     * @return the stashed raw output, or {@code null} if the handle is unknown or evicted
     */
    public synchronized String get(Handle handle) {
        RunRecord record = getRecord(handle);
        return record == null ? null : record.rawOutput();
    }

    /**
     * Returns the number of entries currently in the cache. Exposed for testing only.
     */
    synchronized int size() {
        return store.size();
    }
}
