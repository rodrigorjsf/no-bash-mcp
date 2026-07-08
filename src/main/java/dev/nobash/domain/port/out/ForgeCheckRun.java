package dev.nobash.domain.port.out;

/**
 * One normalized CI check fetched from a forge (PRD-6 S1, #99), folding a GitHub Actions
 * <b>check-run</b> and a legacy <b>commit status</b> into a single shape so the use-case classifies
 * both uniformly. A raw, report-agnostic port record (mirrors {@link ExecResult}): the adapter
 * populates it from the REST JSON; the use-case owns the container-aware {@code ok()} fold.
 *
 * <p>Mapping: a check-run carries its GitHub {@code status}/{@code conclusion} and the {@code jobId}
 * parsed from {@code details_url} (used for the job-log 302 flow). A commit status is mapped to this
 * shape by state — {@code success}→({@code completed},{@code success}), {@code failure}/{@code
 * error}→({@code completed},{@code failure}/{@code error}), {@code pending}→({@code in_progress},
 * null) — with {@code jobId} null (statuses have no retrievable job log).</p>
 *
 * @param name       the check name (check-run {@code name} or status {@code context})
 * @param status     the run status ({@code completed} / {@code queued} / {@code in_progress})
 * @param conclusion the completed conclusion ({@code success}/{@code failure}/{@code neutral}/…);
 *                   null when the check has not concluded
 * @param jobId      the GitHub Actions job id for the log 302 flow; null for a commit status or an
 *                   unparseable {@code details_url}
 */
public record ForgeCheckRun(String name, String status, String conclusion, Long jobId) {
}
