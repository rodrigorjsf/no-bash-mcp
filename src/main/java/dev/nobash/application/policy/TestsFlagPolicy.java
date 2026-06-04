package dev.nobash.application.policy;

import jakarta.inject.Singleton;

import java.util.List;
import java.util.Set;

/**
 * The per-operation flag allowlist for {@code run_tests}/Maven (AC7, security-model.md,
 * ADR-0008). Fail-closed: only the explicit seed survives; every other agent-supplied flag
 * is <strong>silently dropped</strong> and never reaches the process.
 *
 * <p>The seed is intentionally tiny — {@code -o}/{@code --offline} and
 * {@code --fail-at-end}/{@code -fae}. It grows only per a concrete requirement. The three
 * forbidden categories stay forbidden by construction, because they are simply not on the
 * allowlist:</p>
 * <ul>
 *   <li><b>defeat-the-verb</b> — {@code -DskipTests}, {@code -Dmaven.test.skip} would
 *       false-green a clean run;</li>
 *   <li><b>arbitrary {@code -D}</b> — blanket system properties are never admitted (only
 *       individually-vetted keys would be, and none are vetted yet);</li>
 *   <li><b>stdout-verbosity</b> — {@code -X}, {@code -q} add noise; output is parsed from the
 *       report file, not stdout.</li>
 * </ul>
 *
 * <p>Test <b>selection</b> ({@code -Dtest=}, {@code -pl}) is also not a free flag — the
 * structured target selector (a later slice) injects the controlled value.</p>
 *
 * <p>This is an explicit allowlist (membership test), never a denylist: an unknown flag is
 * dropped by default, so a newly-invented Maven flag is fail-closed automatically.</p>
 */
@Singleton
public class TestsFlagPolicy {

    /** The exact set of admitted flag tokens — fail-closed membership, not a pattern. */
    private static final Set<String> SEED = Set.of(
            "-o", "--offline",
            "--fail-at-end", "-fae"
    );

    /**
     * @param requested the raw, agent-supplied flags (untrusted)
     * @return only the seed-allowlisted flags, in their original order; everything else dropped
     */
    public List<String> filter(List<String> requested) {
        return requested.stream()
                        .filter(SEED::contains)
                        .toList();
    }
}
