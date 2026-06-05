package dev.nobash.domain.result;

import io.micronaut.core.annotation.Introspected;
import io.micronaut.serde.annotation.Serdeable;

/**
 * TEST-level counts only (ADR-0007). A {@link ContainerFinding} is NOT a test — it lives in
 * {@code findings}, never in these counts. Counts derive from {@code <testcase>} elements,
 * NEVER from the {@code <testsuite tests=>} header (ADR-0007 rule 5).
 *
 * <p>Deliberately NOT the source of {@code ok()}: deriving success from
 * {@code failed==0 && errored==0} would false-green a container-only run (the G5 trap). See
 * {@link NormalizedRun#ok()}.</p>
 *
 * @param total   the count of all {@code <testcase>} elements
 * @param passed  the count of passing tests
 * @param failed  the count of {@code <failure>} tests
 * @param errored the count of {@code <error>} tests
 * @param skipped the count of {@code <skipped>} tests
 */
@Serdeable
@Introspected
public record Summary(int total, int passed, int failed, int errored, int skipped) {
}
