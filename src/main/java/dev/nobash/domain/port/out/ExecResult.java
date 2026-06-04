package dev.nobash.domain.port.out;

/**
 * The report-agnostic result of one outbound command execution (DESIGN.md §5). The port and
 * this carrier know NOTHING about tests, reports, or formats — they carry only the raw process
 * outcome. The verb use-case is what reads a report directory and normalizes; the seam stays
 * a thin, generic process boundary so {@code build}/{@code install}/{@code lint} reuse it.
 *
 * <p>{@code timedOut} is <strong>defined and read</strong> by this slice but always
 * {@code false} from the real executor — timeout enforcement (and the {@code TIMEOUT} code) is
 * a later slice (issue #6). The envelope's exit-code/timeout failure floor (D28) reads it now
 * so the wiring is in place before enforcement lands.</p>
 *
 * @param exitCode the process exit status ({@code 0} on success)
 * @param stdout   the full captured standard output
 * @param stderr   the full captured standard error
 * @param timedOut whether the run was killed for exceeding a deadline (always false this slice)
 */
public record ExecResult(int exitCode, String stdout, String stderr, boolean timedOut) {
}
