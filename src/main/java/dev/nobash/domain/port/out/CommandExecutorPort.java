package dev.nobash.domain.port.out;

/**
 * Outbound driven port for ecosystem/process execution (DESIGN.md §5). A plain domain
 * interface with zero framework annotations; the ecosystem adapter in
 * {@code adapter/out/ecosystem/*} satisfies it at startup via compile-time DI.
 *
 * <p>It exposes the launcher-resolution capability the {@code TOOL_NOT_INSTALLED} guard needs
 * ({@link #isManagerInstalled()}) and the generic execution capability
 * ({@link #execute(ExecSpec)}). The port is <strong>report-agnostic</strong>: it returns the
 * raw {@link ExecResult} (exit/stdout/stderr/timedOut) and knows nothing of tests, reports, or
 * formats — reading and normalizing a report directory is the verb use-case's job.</p>
 */
public interface CommandExecutorPort {

    /**
     * @return {@code true} iff the trusted system manager ({@code mvn}) resolves on
     * {@code PATH}. Resolves the trusted system binary only — NEVER a repo wrapper
     * ({@code ./mvnw}), per ADR-0008.
     */
    boolean isManagerInstalled();

    /**
     * Run the given command and return its raw outcome. The implementation launches the
     * explicit {@code argv} array directly (NO {@code /bin/sh -c}); {@code argv[0]} is the
     * trusted system manager resolved on {@code PATH}, never a repo wrapper (ADR-0008).
     *
     * @param spec the argv array + working directory to run in
     * @return the raw, report-agnostic execution result
     */
    ExecResult execute(ExecSpec spec);
}
