package dev.nobash.domain.forge;

import dev.nobash.domain.port.out.ForgeCheckRun;

import java.util.List;
import java.util.Set;

/**
 * The container-aware classification of forge CI checks — the anti-false-green keystone of
 * {@code pr_checks} (PRD-6 S1, #99; G5). A run is {@code ok} ONLY when EVERY check is
 * conclusively passing; a single failing OR still-incomplete check flips {@code ok=false}.
 *
 * <p>Three states, IO-free and testable in isolation:</p>
 * <ul>
 *   <li><b>passing</b> — {@code status == completed} AND {@code conclusion} ∈
 *       {{@code success}, {@code neutral}, {@code skipped}}.</li>
 *   <li><b>failing</b> — {@code status == completed} AND {@code conclusion} ∉ the passing set
 *       ({@code failure}/{@code cancelled}/{@code timed_out}/{@code action_required}/{@code stale},
 *       or a completed check with a null conclusion — treated as failing, never as ok).</li>
 *   <li><b>incomplete</b> — {@code status != completed} ({@code queued}/{@code in_progress}). NOT ok:
 *       reporting green while a check is still running is exactly the G5 trap. An incomplete check
 *       flips {@code ok=false} but is NOT a {@code ContainerFinding} (it is not a failure to triage —
 *       it simply has not concluded); it surfaces in the {@code prChecks[]} list.</li>
 * </ul>
 */
public final class ForgeCheckClassifier {

    /** GitHub conclusions that count as a clean pass. Everything else on a completed check fails. */
    private static final Set<String> PASSING_CONCLUSIONS = Set.of("success", "neutral", "skipped");

    private static final String COMPLETED = "completed";

    private ForgeCheckClassifier() {
        // Utility — no instances.
    }

    /** Whether the check has concluded ({@code status == completed}). */
    public static boolean isCompleted(ForgeCheckRun run) {
        return COMPLETED.equalsIgnoreCase(run.status());
    }

    /** Whether the check concluded with a clean pass ({@code success}/{@code neutral}/{@code skipped}). */
    public static boolean isPassing(ForgeCheckRun run) {
        return isCompleted(run)
                && run.conclusion() != null
                && PASSING_CONCLUSIONS.contains(run.conclusion().toLowerCase());
    }

    /** Whether the check concluded but NOT with a clean pass — a triage-worthy failure. */
    public static boolean isFailing(ForgeCheckRun run) {
        return isCompleted(run) && !isPassing(run);
    }

    /** Whether the check has not concluded yet ({@code queued}/{@code in_progress}) — not ok, not a finding. */
    public static boolean isIncomplete(ForgeCheckRun run) {
        return !isCompleted(run);
    }

    /**
     * The container-aware verdict: {@code true} ONLY when every check is passing. An empty list is
     * {@code ok=true} (a ref with no checks has nothing red); a single failing or incomplete check
     * makes it {@code false}.
     *
     * @param runs the folded check list (check-runs + commit statuses)
     * @return whether the whole run is ok
     */
    public static boolean ok(List<ForgeCheckRun> runs) {
        return runs.stream().allMatch(ForgeCheckClassifier::isPassing);
    }
}
