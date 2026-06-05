package dev.nobash.application.verb.tests;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The timeout clamp (issue #6, operational-model.md "Timeout"): the agent may raise {@code timeout}
 * up to a max cap but NEVER beyond it; an unspecified (null / non-positive) value falls back to the
 * per-verb default. Pure, side-effect-free — asserted directly on {@link TimeoutPolicy#clamp}.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TimeoutPolicyTest {

    @Test
    void a_null_timeout_falls_back_to_the_default() {
        assertThat(TimeoutPolicy.clamp(null)).isEqualTo(TimeoutPolicy.DEFAULT_SECONDS);
    }

    @Test
    void a_zero_or_negative_timeout_is_treated_as_unspecified_and_defaults() {
        assertThat(TimeoutPolicy.clamp(0)).isEqualTo(TimeoutPolicy.DEFAULT_SECONDS);
        assertThat(TimeoutPolicy.clamp(-30)).isEqualTo(TimeoutPolicy.DEFAULT_SECONDS);
    }

    @Test
    void an_in_range_timeout_is_passed_through_unchanged() {
        int requested = TimeoutPolicy.DEFAULT_SECONDS + 7;
        assertThat(TimeoutPolicy.clamp(requested)).isEqualTo(requested);
    }

    @Test
    void the_agent_may_raise_the_timeout_right_up_to_the_cap() {
        assertThat(TimeoutPolicy.clamp(TimeoutPolicy.MAX_SECONDS)).isEqualTo(TimeoutPolicy.MAX_SECONDS);
    }

    @Test
    void a_timeout_beyond_the_cap_is_clamped_down_to_the_cap_never_beyond() {
        assertThat(TimeoutPolicy.clamp(TimeoutPolicy.MAX_SECONDS + 1)).isEqualTo(TimeoutPolicy.MAX_SECONDS);
        assertThat(TimeoutPolicy.clamp(Integer.MAX_VALUE)).isEqualTo(TimeoutPolicy.MAX_SECONDS);
    }

    @Test
    void the_default_is_within_the_cap() {
        // A sanity invariant: the default must itself be a legal (≤ cap) deadline.
        assertThat(TimeoutPolicy.DEFAULT_SECONDS).isLessThanOrEqualTo(TimeoutPolicy.MAX_SECONDS);
    }
}
