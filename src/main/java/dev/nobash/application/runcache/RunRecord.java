package dev.nobash.application.runcache;

import dev.nobash.domain.result.Finding;

import java.util.List;

/**
 * The full, retained result of a single run, kept in the session-scoped run cache.
 *
 * <p>Separates signal (findings, already on the Envelope) from noise (raw output), so the
 * Envelope stays tight while {@code get_log} expands exactly the slice the agent requests.</p>
 *
 * @param rawOutput the combined raw output (stdout + stderr) of the run; never null
 * @param findings  the normalized findings from the run; may be empty, never null
 */
public record RunRecord(String rawOutput, List<Finding> findings) {

    public RunRecord {
        rawOutput = rawOutput == null ? "" : rawOutput;
        findings = findings == null ? List.of() : List.copyOf(findings);
    }
}
