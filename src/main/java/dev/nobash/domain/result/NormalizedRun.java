package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

import java.util.List;

/**
 * The unified result every ecosystem folds into (ADR-0007). {@code tool} is the Reporter
 * (CONTEXT.md) — {@code "surefire"} for Maven, the one place the codebase keeps the word
 * "tool" and it means the Reporter, never the Manager.
 *
 * @param tool    the Reporter that produced the parsed report (e.g. {@code "surefire"})
 * @param summary the TEST-level counts
 * @param findings the normalized findings (test- and container-level)
 */
@Serdeable
@Introspected
public record NormalizedRun(String tool, Summary summary, List<Finding> findings) {

    /**
     * Container-aware success — derived from FINDINGS, never from test counts (ADR-0007
     * rule 1). A run whose only failure is a no-test-owner {@link ContainerFinding} (a
     * JUnit {@code @BeforeAll} error, a Go {@code init()} panic, a jest module-load failure,
     * or a failed forge CI check) has clean TEST counts yet is NOT ok — the G5 false-green
     * trap the whole project exists to avoid. Deriving {@code ok} from
     * {@code summary.failed()==0 && summary.errored()==0} would false-green it.
     */
    public boolean ok() {
        return findings.stream()
                .noneMatch(f -> f.outcome() == Outcome.FAILED || f.outcome() == Outcome.ERRORED);
    }
}
