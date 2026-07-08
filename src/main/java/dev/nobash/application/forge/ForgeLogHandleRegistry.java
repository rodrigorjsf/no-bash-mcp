package dev.nobash.application.forge;

import dev.nobash.domain.port.out.ForgeLogRequest;
import jakarta.inject.Singleton;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Session-scoped registry mapping an opaque handle id → the {@link ForgeLogRequest} needed to fetch a
 * failing check-run's job log LAZILY through the 302 flow (PRD-6 S1, #99). {@code pr_checks} registers
 * one entry per failing check-run and puts the id on the {@code PrCheck.handle}; {@code get_log}
 * resolves it and drives {@code ForgePort.fetchJobLog} only when the agent drills in.
 *
 * <p><b>Package placement is deliberate.</b> It lives in {@code application.forge} — NOT under
 * {@code application.verb.*} — so both the {@code verb.forge} (pr_checks) and {@code verb.getlog}
 * (get_log) slices may depend on it without creating a forbidden verb-slice→sibling-verb-slice edge
 * (the ArchUnit {@code no_verb_slice_depends_on_another_verb_slice} rule).</p>
 *
 * <p>Bounded to the last {@link #MAX_HANDLES} registrations (insertion order, oldest evicted),
 * mirroring {@code RawOutputStash}; a single-session STDIO singleton with sequential dispatch.</p>
 */
@Singleton
public class ForgeLogHandleRegistry {

    /** The maximum number of forge log handles retained; oldest-inserted is evicted past this bound. */
    public static final int MAX_HANDLES = 50;

    private final Map<String, ForgeLogRequest> store;

    public ForgeLogHandleRegistry() {
        this.store = new LinkedHashMap<>(MAX_HANDLES * 2, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ForgeLogRequest> eldest) {
                return size() > MAX_HANDLES;
            }
        };
    }

    /**
     * Register a forge log request and return the fresh handle id that keys it.
     *
     * @param request the job-log fetch descriptor; never null
     * @return the opaque handle id to place on the {@code PrCheck.handle}
     */
    public synchronized String register(ForgeLogRequest request) {
        String id = "forge-log-" + UUID.randomUUID();
        store.put(id, request);
        return id;
    }

    /**
     * Resolve a handle id to its forge log request.
     *
     * @param handleId the opaque id; may be null
     * @return the registered request, or {@code null} when the handle is unknown or evicted
     */
    public synchronized ForgeLogRequest resolve(String handleId) {
        if (handleId == null) {
            return null;
        }
        return store.get(handleId);
    }
}
