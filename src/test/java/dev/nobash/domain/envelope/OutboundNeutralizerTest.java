package dev.nobash.domain.envelope;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OutboundNeutralizer} (issue #7, P9 outbound neutralization).
 * The neutralizer is a PURE function: no I/O, no reflection, no Micronaut. Each test
 * verifies one structural property of the strip/cap contract.
 *
 * <p>Contract: strip control chars (C0 except tab/LF/CR, and C1), ANSI escape sequences
 * (CSI/OSC/lone ESC), and zero-width/bidi control code points; apply per-field length caps;
 * preserve tab, LF, CR (stack traces need them); null in to null out.</p>
 *
 * <p>All test input strings use Java String.valueOf((char) 0xXXXX) or explicit char casts
 * so the source is unambiguous regardless of editor rendering of invisible characters.</p>
 */
@SuppressWarnings("UnicodeEscape")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class OutboundNeutralizerTest {

    // Java char hex-cast constants — unambiguous, no unicode-escape processing needed.
    private static final char ESC  = (char) 0x1B;  // U+001B ESCAPE
    private static final char BEL  = (char) 0x07;  // U+0007 BELL
    private static final char ZWSP = (char) 0x200B; // U+200B ZERO WIDTH SPACE
    private static final char ZWNJ = (char) 0x200C; // U+200C ZERO WIDTH NON-JOINER
    private static final char ZWJ  = (char) 0x200D; // U+200D ZERO WIDTH JOINER
    private static final char BOM  = (char) 0xFEFF; // U+FEFF BOM
    private static final char WJ   = (char) 0x2060; // U+2060 WORD JOINER
    private static final char LRM  = (char) 0x200E; // U+200E LEFT-TO-RIGHT MARK
    private static final char RLM  = (char) 0x200F; // U+200F RIGHT-TO-LEFT MARK
    private static final char LRE  = (char) 0x202A; // U+202A LEFT-TO-RIGHT EMBEDDING
    private static final char RLO  = (char) 0x202E; // U+202E RIGHT-TO-LEFT OVERRIDE
    private static final char PDI  = (char) 0x2069; // U+2069 POP DIRECTIONAL ISOLATE

    @Nested
    class control_character_stripping {

        @Test
        void preserves_tab_lf_cr_as_needed_for_stack_traces() {
            String input = "line1\nline2\r\nindented\twith\ttabs";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo(input);
        }

        @Test
        void strips_bel_and_backspace_c0_chars() {
            String input = "before" + BEL + "after" + (char) 0x08 + "end";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafterend");
        }

        @Test
        void strips_c1_control_chars() {
            // C1 range: U+0080–U+009F — using U+0085 NEL and U+009F APC as representatives.
            String input = "before" + (char) 0x85 + "after" + (char) 0x9F + "end";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafterend");
        }

        @Test
        void strips_null_byte() {
            String input = "before" + (char) 0x00 + "after";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafter");
        }

        @Test
        void strips_del_u007f() {
            String input = "before" + (char) 0x7F + "after";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafter");
        }
    }

    @Nested
    class ansi_escape_sequence_stripping {

        @Test
        void strips_csi_sgr_sequences() {
            // CSI: ESC [ 31 m (SGR red), ESC [ 0 m (SGR reset)
            String input = ESC + "[31mERROR" + ESC + "[0m message";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("ERROR message");
        }

        @Test
        void strips_csi_cursor_movement_sequences() {
            // ESC [ 2 A = cursor up 2
            String input = "line1" + ESC + "[2Aline2";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("line1line2");
        }

        @Test
        void strips_osc_sequences_terminated_by_st() {
            // OSC: ESC ] text ST (ST = ESC \)
            String input = "before" + ESC + "]0;window title" + ESC + "\\after";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafter");
        }

        @Test
        void strips_osc_sequences_terminated_by_bel() {
            // OSC: ESC ] text BEL
            String input = "before" + ESC + "]0;title" + BEL + "after";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("beforeafter");
        }

        @Test
        void strips_lone_esc_not_part_of_a_csi_or_osc_sequence() {
            // ESC followed by a non-sequence char
            String input = "prefix" + ESC + "Xsuffix";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("prefixsuffix");
        }

        @Test
        void strips_lone_esc_at_end_of_string() {
            String input = "text" + ESC;
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("text");
        }
    }

    @Nested
    class zero_width_and_bidi_control_stripping {

        @Test
        void strips_zero_width_space_zwsp() {
            String input = "invis" + ZWSP + "ible";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("invisible");
        }

        @Test
        void strips_zero_width_non_joiner_and_joiner() {
            String input = "a" + ZWNJ + "b" + ZWJ + "c";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("abc");
        }

        @Test
        void strips_bom_and_word_joiner() {
            String input = BOM + "text" + WJ + "here";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("texthere");
        }

        @Test
        void strips_lrm_rlm_marks() {
            String input = "left" + LRM + "right" + RLM + "end";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("leftrightend");
        }

        @Test
        void strips_bidi_embedding_override_and_isolate_chars() {
            String input = "A" + LRE + "bidi" + RLO + "text" + PDI + "end";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo("Abiditext" + "end");
        }
    }

    @Nested
    class per_field_length_cap {

        @Test
        void truncates_to_cap_and_appends_ellipsis_marker() {
            String input = "a".repeat(100);
            String result = OutboundNeutralizer.neutralize(input, 10);
            assertThat(result).hasSize(10 + OutboundNeutralizer.TRUNCATION_MARKER.length());
            assertThat(result).endsWith(OutboundNeutralizer.TRUNCATION_MARKER);
        }

        @Test
        void does_not_truncate_strings_at_or_below_cap() {
            String input = "a".repeat(10);
            assertThat(OutboundNeutralizer.neutralize(input, 10)).isEqualTo(input);
        }

        @Test
        void cap_is_applied_after_stripping_so_stripped_sequences_do_not_consume_budget() {
            // ESC + "[31m" is 5 chars total; after stripping it is 0 chars.
            // "short" is 5 chars and must not be truncated at cap=100.
            String input = ESC + "[31mshort";
            assertThat(OutboundNeutralizer.neutralize(input, 100))
                    .isEqualTo("short");
        }
    }

    @Nested
    class null_and_edge_cases {

        @Test
        void returns_null_for_null_input() {
            assertThat(OutboundNeutralizer.neutralize(null, 1000)).isNull();
        }

        @Test
        void returns_empty_for_empty_input() {
            assertThat(OutboundNeutralizer.neutralize("", 1000)).isEqualTo("");
        }

        @Test
        void passes_through_clean_string_unchanged() {
            String input = "clean test failure message\nwith a stack trace";
            assertThat(OutboundNeutralizer.neutralize(input, 10_000))
                    .isEqualTo(input);
        }
    }

    @Nested
    class combined_attack_payload {

        @Test
        void strips_all_dangerous_sequences_from_a_crafted_injection_payload() {
            // A test name carrying ANSI + zero-width char + an embedded instruction payload.
            // After neutralization the dangerous sequences are gone but the visible text
            // (including the injection words) survives as plain typed data — the structural
            // guarantee, NOT heuristic keyword detection.
            String maliciousName =
                    ESC + "[31m"                      // CSI: ANSI red start
                    + ZWSP                            // U+200B: zero-width space (invisible framing)
                    + "ignore_previous_instructions"
                    + ESC + "[0m"                     // CSI: ANSI reset
                    + ZWNJ;                           // U+200C: ZWNJ (trailing invisible)

            String neutralized = OutboundNeutralizer.neutralize(maliciousName, 10_000);

            // Dangerous framing stripped.
            assertThat(neutralized).doesNotContain(String.valueOf(ESC));
            assertThat(neutralized).doesNotContain(String.valueOf(ZWSP));
            assertThat(neutralized).doesNotContain(String.valueOf(ZWNJ));
            // The visible injection text survives as a typed-field data value.
            assertThat(neutralized).isEqualTo("ignore_previous_instructions");
        }
    }
}
