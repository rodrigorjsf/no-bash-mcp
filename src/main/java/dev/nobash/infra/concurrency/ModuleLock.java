package dev.nobash.infra.concurrency;

import jakarta.inject.Singleton;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A fail-fast, per-module mutual-exclusion guard for mutating verbs (ADR-0005, D22). A second
 * mutating run on the <em>same module</em> while the first holds the key must fail fast with
 * {@code RESOURCE_BUSY}, NEVER block — blocking interacts badly with the caller's {@code timeout}
 * and hides duplicate work (operational-model.md "Concurrency").
 *
 * <p>The key is {@code moduleDir.toRealPath()} — the canonical, symlink-resolved path — so two
 * alias paths (a symlink and its target, {@code ./a/../a}, …) collapse to the SAME key and a
 * concurrent run via an alias still collides. {@code toAbsolutePath()} would NOT collapse a
 * symlink and could let aliased duplicates run concurrently.</p>
 *
 * <p>Lives in {@code infra/}, NOT {@code domain/}: it touches {@code java.nio.file} (forbidden in
 * the pure domain) and is invoked from the application verb. The primitive is a lock-free
 * {@link ConcurrentHashMap}-backed key set: {@link #tryAcquire} is a single atomic {@code add}
 * that returns {@code false} when the key is already held — there is no waiting, ever.</p>
 */
@Singleton
public class ModuleLock {

    private final Set<String> held = ConcurrentHashMap.newKeySet();

    /**
     * Try to acquire the lock for a module, fail-fast. Resolves {@code moduleDir} to its canonical
     * real path (following symlinks) and atomically claims that key.
     *
     * @param moduleDir the module directory to lock (already validated as an existing directory)
     * @return {@code true} iff the key was free and is now held by the caller; {@code false} if
     * another run already holds it (the caller must return {@code RESOURCE_BUSY} and NOT block)
     * @throws IOException if the real path cannot be resolved (the caller has already passed the
     *                     INVALID_PATH directory guard, so this is not expected on the happy path)
     */
    public boolean tryAcquire(Path moduleDir) throws IOException {
        return held.add(key(moduleDir));
    }

    /**
     * Release a previously acquired lock. Idempotent and safe on every exit path (success,
     * TIMEOUT, thrown exception) — the caller releases in a {@code finally}.
     *
     * @param moduleDir the same module directory passed to {@link #tryAcquire}
     * @throws IOException if the real path cannot be resolved
     */
    public void release(Path moduleDir) throws IOException {
        held.remove(key(moduleDir));
    }

    /**
     * Whether the canonical key for a module is currently held. Observability for tests — the
     * production path uses only {@link #tryAcquire}/{@link #release}.
     *
     * @param moduleDir the module directory to probe
     * @return {@code true} iff the canonical realpath key is currently held
     * @throws IOException if the real path cannot be resolved
     */
    public boolean isHeldFor(Path moduleDir) throws IOException {
        return held.contains(key(moduleDir));
    }

    private static String key(Path moduleDir) throws IOException {
        return moduleDir.toRealPath().toString();
    }
}
