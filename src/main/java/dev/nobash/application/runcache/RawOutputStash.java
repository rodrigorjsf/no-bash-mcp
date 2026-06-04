package dev.nobash.application.runcache;

import dev.nobash.domain.envelope.Handle;
import jakarta.inject.Singleton;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * The SMALLEST in-memory store that lets a verb put raw output behind a {@link Handle} and carry
 * the Handle on the Envelope. This slice uses it for exactly one case: stashing the raw compiler
 * output of a compile failure ({@code REPORT_NOT_PRODUCED}, D25) so the agent can fetch it.
 *
 * <p>Deliberately minimal (issue #4 scope boundary vs issue #5): NO eviction, NO TTL, NO byte
 * cap, and NO {@code get_log} retrieval verb — those, and the full session-scoped run-cache, are
 * issue #5. It is a {@code @Singleton} so a single store backs the whole process; keyed by an
 * opaque random id so handles are unguessable and collision-free.</p>
 */
@Singleton
public class RawOutputStash {

    private final ConcurrentMap<String, String> store = new ConcurrentHashMap<>();

    /**
     * Stash raw output and return the {@link Handle} that points at it.
     *
     * @param rawOutput the full unsummarized output to retain (e.g. compiler stderr)
     * @return a fresh handle whose id keys the stored output
     */
    public Handle stash(String rawOutput) {
        String id = UUID.randomUUID().toString();
        store.put(id, rawOutput == null ? "" : rawOutput);
        return new Handle(id);
    }

    /**
     * @param handle a handle previously returned by {@link #stash(String)}
     * @return the stashed raw output, or {@code null} if the handle is unknown
     */
    public String get(Handle handle) {
        return handle == null ? null : store.get(handle.id());
    }
}
