package dev.nobash.domain.port.out;

/**
 * Outbound driven port for ecosystem/process execution (DESIGN.md §5). A plain domain
 * interface with zero framework annotations; the ecosystem adapter in
 * {@code adapter/out/ecosystem/*} satisfies it at startup via compile-time DI.
 *
 * <p>Scope for this slice: the port exposes only the launcher-resolution capability the
 * {@code TOOL_NOT_INSTALLED} guard needs ({@link #isManagerInstalled()}). Real execution
 * ({@code execute(ExecSpec) -> ExecResult}) is deliberately NOT defined here — that is a
 * later slice. This slice's {@code run_tests} stops at the operational-error gate and never
 * launches a process.</p>
 */
public interface CommandExecutorPort {

    /**
     * @return {@code true} iff the trusted system manager ({@code mvn}) resolves on
     * {@code PATH}. Resolves the trusted system binary only — NEVER a repo wrapper
     * ({@code ./mvnw}), per ADR-0008.
     */
    boolean isManagerInstalled();
}
