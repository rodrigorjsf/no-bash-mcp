package dev.nobash.application.policy;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Set;

/**
 * The per-operation flag allowlist for the {@code install} verb / npm (PRD-3, slice 3,
 * security-model.md, ADR-0008). Fail-closed: only the explicit seed survives; every other
 * agent-supplied flag is <strong>silently dropped</strong> and never reaches npm.
 *
 * <p>The seed is intentionally <strong>empty</strong> for this tracer slice — no agent-supplied
 * npm flag is admitted in v1. The MCP injects {@code --no-audit} and {@code --no-fund} directly
 * as controlled, server-owned flags (not agent flags, not vetted here). Future slices may grow
 * the seed (e.g. {@code --prefer-offline}) via an explicit PRD decision.</p>
 *
 * <p>This is an explicit allowlist (membership test), never a denylist: an unknown flag is
 * dropped by default, so a newly-invented npm flag is fail-closed automatically.</p>
 */
@Singleton
public class InstallFlagPolicy {

    /** The exact set of admitted flag tokens — empty seed for this tracer slice. */
    private static final Set<String> SEED = Set.of();

    /**
     * @param requested the raw, agent-supplied flags (untrusted)
     * @return only the seed-allowlisted flags; an empty list because the seed is empty
     */
    public List<String> filter(List<String> requested) {
        return requested.stream()
                        .filter(SEED::contains)
                        .toList();
    }
}
