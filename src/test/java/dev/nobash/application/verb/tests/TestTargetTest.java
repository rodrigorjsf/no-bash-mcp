package dev.nobash.application.verb.tests;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Issue #9 AC4 — type validation rejects a malformed target BEFORE any process launches.
 * Covers: valid CLASS/METHOD targets, all known-invalid shapes, and the null/absent → full-suite
 * pass-through.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class TestTargetTest {

    @Nested
    class valid_targets_parse_cleanly {

        @Test
        void a_CLASS_target_parses_and_produces_dtest_token() throws Exception {
            TestTarget t = TestTarget.parse("CLASS", "FooTest");

            assertThat(t).isNotNull();
            assertThat(t.kind()).isEqualTo("CLASS");
            assertThat(t.value()).isEqualTo("FooTest");
            assertThat(t.toArgvToken()).isEqualTo("-Dtest=FooTest");
        }

        @Test
        void a_fully_qualified_CLASS_target_parses() throws Exception {
            TestTarget t = TestTarget.parse("CLASS", "com.example.FooTest");

            assertThat(t).isNotNull();
            assertThat(t.toArgvToken()).isEqualTo("-Dtest=com.example.FooTest");
        }

        @Test
        void a_METHOD_target_parses_and_produces_hash_form_token() throws Exception {
            TestTarget t = TestTarget.parse("METHOD", "FooTest#testBar");

            assertThat(t).isNotNull();
            assertThat(t.kind()).isEqualTo("METHOD");
            assertThat(t.value()).isEqualTo("FooTest#testBar");
            assertThat(t.toArgvToken()).isEqualTo("-Dtest=FooTest#testBar");
        }

        @Test
        void kind_is_case_insensitive() throws Exception {
            TestTarget lower = TestTarget.parse("class", "FooTest");
            TestTarget mixed = TestTarget.parse("Method", "FooTest#testBar");

            assertThat(lower).isNotNull();
            assertThat(lower.kind()).isEqualTo("CLASS");
            assertThat(mixed).isNotNull();
            assertThat(mixed.kind()).isEqualTo("METHOD");
        }

        @Test
        void trailing_and_leading_whitespace_is_stripped_from_the_value() throws Exception {
            TestTarget t = TestTarget.parse("CLASS", "  FooTest  ");

            assertThat(t).isNotNull();
            assertThat(t.value()).isEqualTo("FooTest");
        }
    }

    @Nested
    class null_and_absent_pair_produces_null_full_suite {

        @Test
        void both_null_returns_null_full_suite() throws Exception {
            assertThat(TestTarget.parse(null, null)).isNull();
        }

        @Test
        void both_blank_returns_null_full_suite() throws Exception {
            assertThat(TestTarget.parse("", "  ")).isNull();
        }
    }

    @Nested
    class malformed_targets_throw_before_any_process_is_launched {

        @Test
        void an_unknown_kind_throws() {
            assertThatThrownBy(() -> TestTarget.parse("FILE", "FooTest.java"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class)
                    .hasMessageContaining("FILE");
        }

        @Test
        void MODULE_kind_is_not_supported_in_v1() {
            assertThatThrownBy(() -> TestTarget.parse("MODULE", "my-module"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class)
                    .hasMessageContaining("MODULE");
        }

        @Test
        void a_CLASS_target_with_a_hash_throws() {
            assertThatThrownBy(() -> TestTarget.parse("CLASS", "FooTest#testBar"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class)
                    .hasMessageContaining("#");
        }

        @Test
        void a_METHOD_target_without_a_hash_throws() {
            assertThatThrownBy(() -> TestTarget.parse("METHOD", "FooTestNoHash"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class)
                    .hasMessageContaining("#");
        }

        @Test
        void a_METHOD_target_with_blank_class_part_throws() {
            assertThatThrownBy(() -> TestTarget.parse("METHOD", "#testBar"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }

        @Test
        void a_METHOD_target_with_blank_method_part_throws() {
            assertThatThrownBy(() -> TestTarget.parse("METHOD", "FooTest#"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }

        @Test
        void kind_present_with_null_value_throws() {
            assertThatThrownBy(() -> TestTarget.parse("CLASS", null))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }

        @Test
        void kind_present_with_blank_value_throws() {
            assertThatThrownBy(() -> TestTarget.parse("CLASS", "   "))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }

        @Test
        void value_present_with_null_kind_throws() {
            assertThatThrownBy(() -> TestTarget.parse(null, "FooTest"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }

        @Test
        void value_present_with_blank_kind_throws() {
            assertThatThrownBy(() -> TestTarget.parse("  ", "FooTest"))
                    .isInstanceOf(TestTarget.MalformedTargetException.class);
        }
    }
}
