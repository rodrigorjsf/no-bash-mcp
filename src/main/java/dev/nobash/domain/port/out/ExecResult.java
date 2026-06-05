package dev.nobash.domain.port.out;

/**
 * The report-agnostic result of one outbound command execution (DESIGN.md §5). The port and
 * this carrier know NOTHING about tests, reports, or formats — they carry only the raw process
 * outcome. The verb use-case is what reads a report directory and normalizes; the seam stays
 * a thin, generic process boundary so {@code build}/{@code install}/{@code lint} reuse it.
 *
 * <p>{@code timedOut} is set {@code true} by the executor when it killed the process tree for
 * exceeding the {@code ExecSpec.timeoutSeconds} deadline (issue #6). On a timeout the
 * {@code stdout}/{@code stderr} carry whatever partial output drained before the tree was reaped;
 * the verb use-case maps {@code timedOut} to a {@code TIMEOUT} operational-error envelope and the
 * application failure floor (D28) also floors {@code ok} to false on it.</p>
 *
 * @param exitCode the process exit status ({@code 0} on success; undefined on a timeout kill)
 * @param stdout   the full captured standard output (partial on a timeout)
 * @param stderr   the full captured standard error (partial on a timeout)
 * @param timedOut whether the run was killed for exceeding its deadline (tree-killed)
 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
}
