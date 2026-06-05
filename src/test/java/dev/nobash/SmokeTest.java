package dev.nobash;

import dev.nobash.adapter.in.mcp.BuildTools;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import io.micronaut.context.annotation.Property;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DI wiring smoke test. Proves the bean graph assembles under Micronaut compile-time DI —
 * the {@code @Tool} {@link BuildTools} bean, its {@code RunTestsUseCase}, the
 * {@link CommandExecutorPort} adapter, the argv builder and the flag policy — and that the
 * wired graph enforces the first security guard ({@code INVALID_PATH}) end-to-end through the
 * tool bean. {@code startApplication=false} so the STDIO loop does NOT start and hijack the
 * test JVM's stdin/stdout (DESIGN.md §9).
 */
@MicronautTest(startApplication = false)
@Property(name = "micronaut.mcp.server.transport", value = "STDIO")
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class SmokeTest {

    @Inject
    BuildTools buildTools;

    @Inject
    CommandExecutorPort commandExecutorPort;

    @Test
    void the_tool_bean_graph_wires_via_compile_time_di() {
        assertThat(buildTools).isNotNull();
        assertThat(commandExecutorPort).isNotNull();
    }

    @Test
    void the_wired_run_tests_tool_enforces_the_invalid_path_guard() {
        Envelope env = buildTools.run_tests("/no/such/path/wiring-smoke", List.of(), null, null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
    }
}
