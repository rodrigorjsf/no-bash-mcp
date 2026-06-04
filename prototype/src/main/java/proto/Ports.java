package proto;

import java.util.List;
import java.util.Map;

/**
 * The two outbound driven ports from ADR-0006, plus their format-agnostic data
 * types. Discriminator for bet #2: CommandExecutorPort must stay completely
 * ignorant of tests, reports, and formats — it only runs a process and returns
 * raw process output. ALL JUnit/jest/go knowledge lives in the normalizers and
 * the ecosystem layer ABOVE this port. If a method here ever has to name a
 * report format, the boundary has leaked.
 */
final class Ports {
    private Ports() {}
}

/** What to run. Pure process intent: no notion of "tests" or "reports". */
record ExecSpec(
        String cwd,
        List<String> command,   // e.g. ["mvn","-q","test"] — already-resolved tokens
        long timeoutMillis,
        Map<String, String> env
) {}

/** Raw process outcome. No tests, no failures, no formats — just OS-level facts. */
record ExecResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut
) {}

/**
 * Outbound port: ecosystem/process execution (ProcessBuilder in prod). The verb
 * layer injects the reporter flag into {@link ExecSpec#command} and knows where
 * the report lands; the port just executes. Format-agnostic by construction.
 */
interface CommandExecutorPort {
    ExecResult execute(ExecSpec spec);
}

/** A forge CI check, as the forge reports it — pre-normalization. */
record RawCheck(String name, String rawStatus, String rawConclusion, String detailsUrl) {}

/**
 * Outbound port: read-only forge inspection over HTTP (the JDK-client adapter in
 * prod). Returns forge-native data; normalization into the common envelope
 * happens above. Per-instance base URL + read-scoped token are adapter config,
 * never arguments here (forge-security-model.md).
 */
interface ForgePort {
    List<RawCheck> prChecks(String ref);
}
