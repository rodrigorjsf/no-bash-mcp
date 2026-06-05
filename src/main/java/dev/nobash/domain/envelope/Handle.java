package dev.nobash.domain.envelope;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * An opaque token (CONTEXT.md "Handle") returned with a verb result that lets the agent retrieve
 * the full, unsummarized output of that run later, without re-running it. A pure value type — it
 * carries only the id; it performs NO I/O and owns no cache (the run-cache itself, with eviction/
 * TTL/byte-cap and the {@code get_log} verb, is a later slice, issue #5).
 *
 * <p>This slice uses it for exactly one purpose: when a compile failure produces no Surefire
 * report ({@code REPORT_NOT_PRODUCED}, D25), the raw compiler output is stashed behind a Handle
 * and the Handle is carried on the Envelope so the agent can fetch the diagnostics.</p>
 *
 * @param id the opaque identifier the run-cache is keyed by; never null
 */
@Serdeable
@Introspected
public record Handle(String id) {
}
