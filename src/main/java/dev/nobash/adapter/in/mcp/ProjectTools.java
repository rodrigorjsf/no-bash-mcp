package dev.nobash.adapter.in.mcp;

import dev.nobash.application.verb.getlog.GetLogUseCase;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.mcp.annotations.Tool;
import io.micronaut.mcp.annotations.ToolArg;
import jakarta.inject.Singleton;

/**
 * The inbound MCP adapter for project-level read verbs (DESIGN.md §4). This slice exposes
 * {@code get_log}, the drill-down verb that expands a retained run result without re-running
 * (the anti-lossy keystone, G5).
 */
@Singleton
public class ProjectTools {

    private final GetLogUseCase getLog;

    public ProjectTools(GetLogUseCase getLog) {
        this.getLog = getLog;
    }

    /**
     * Expand a retained run result without re-running. Two filters:
     * <ul>
     *   <li>With {@code filter} — returns the full detail/stack trace for the matching
     *       failing test (identity = {@code "suite.name"} or just {@code "name"}).</li>
     *   <li>Without {@code filter} — returns the whole retained raw output (stdout + stderr).</li>
     * </ul>
     *
     * @param handle the opaque handle id returned with a previous run result
     * @param filter optional test identity to drill into; omit to get the full raw output
     * @return the requested slice, or {@code null} when the handle is unknown/evicted or the
     *         filtered finding is absent
     */
    @Tool(name = "get_log",
            description = "Expand a retained run result without re-running. "
                    + "With filter: returns the full detail/stack trace for the matching failing test. "
                    + "Without filter: returns the whole retained raw output.")
    public String get_log(
            @ToolArg(name = "handle", description = "Opaque handle id from a previous run result") String handle,
            @ToolArg(name = "filter", description = "Optional test identity (suite.name or name)") @Nullable String filter) {
        return getLog.get(handle, filter);
    }
}
