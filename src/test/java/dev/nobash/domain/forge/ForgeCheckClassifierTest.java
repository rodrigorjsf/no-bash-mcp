package dev.nobash.domain.forge;

import dev.nobash.domain.port.out.ForgeCheckRun;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ForgeCheckClassifier} — the container-aware {@code ok()} floor (PRD-6 S1,
 * #99; G5). The three cases the advisor flagged as the cardinal false-green risk are pinned here:
 * all-passing → ok; a failing check → not ok; an <b>incomplete</b> check → not ok.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ForgeCheckClassifierTest {

    private static ForgeCheckRun completed(String name, String conclusion) {
        return new ForgeCheckRun(name, "completed", conclusion, null);
    }

    private static ForgeCheckRun running(String name) {
        return new ForgeCheckRun(name, "in_progress", null, null);
    }

    @Test
    void an_all_passing_run_is_ok() {
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success"),
                completed("lint", "neutral"),
                completed("optional", "skipped"));

        assertThat(ForgeCheckClassifier.ok(runs)).isTrue();
    }

    @Test
    void a_single_failing_check_flips_ok_to_false() {
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success"),
                completed("test", "failure"));

        assertThat(ForgeCheckClassifier.ok(runs)).isFalse();
    }

    @Test
    void an_incomplete_check_flips_ok_to_false_never_a_false_green() {
        // Reporting green while a check is still running is exactly the G5 trap.
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success"),
                running("integration"));

        assertThat(ForgeCheckClassifier.ok(runs)).isFalse();
        assertThat(ForgeCheckClassifier.isIncomplete(runs.get(1))).isTrue();
        assertThat(ForgeCheckClassifier.isFailing(runs.get(1))).isFalse();
    }

    @Test
    void a_completed_check_with_a_null_conclusion_is_treated_as_failing() {
        ForgeCheckRun anomalous = completed("weird", null);

        assertThat(ForgeCheckClassifier.isFailing(anomalous)).isTrue();
        assertThat(ForgeCheckClassifier.isPassing(anomalous)).isFalse();
    }

    @Test
    void cancelled_and_timed_out_conclusions_are_failing() {
        assertThat(ForgeCheckClassifier.isFailing(completed("a", "cancelled"))).isTrue();
        assertThat(ForgeCheckClassifier.isFailing(completed("b", "timed_out"))).isTrue();
        assertThat(ForgeCheckClassifier.isFailing(completed("c", "action_required"))).isTrue();
    }

    @Test
    void an_empty_check_set_is_ok_nothing_red() {
        assertThat(ForgeCheckClassifier.ok(List.of())).isTrue();
    }

    @Test
    void classification_is_case_insensitive_on_status_and_conclusion() {
        assertThat(ForgeCheckClassifier.isPassing(new ForgeCheckRun("x", "COMPLETED", "SUCCESS", null)))
                .isTrue();
    }
}
