package dev.nobash.bootstrap;

import java.nio.file.Path;

/**
 * The outcome of a {@link HarnessConfigWriter} run — the two files it wrote plus the assertable
 * <b>remove-Bash suggestion</b> (decision-log D15, {@code bootstrap-and-deployment.md} responsibility
 * 3). The suggestion is a returned value (not a side effect) so an acceptance test can assert the
 * skill surfaces it.
 *
 * <p>The suggestion is advisory text — the writer does NOT itself remove the Bash permission, and it
 * deliberately does NOT emit a wholesale {@code Bash} deny: forward-progress git still runs via Bash
 * in the transitional window (D35), so denying Bash entirely would contradict the design. Only the
 * specific dangerous-git commands are denied (via {@link DangerousGitDenyList}); removing Bash is a
 * human decision the skill recommends once MCP coverage is sufficient.</p>
 *
 * @param mcpConfigPath      the {@code .mcp.json} the writer produced (registers the MCP server)
 * @param settingsPath       the {@code .claude/settings.json} the writer produced/merged (carries
 *                           {@code permissions.deny})
 * @param removeBashSuggestion the human-facing advisory to remove the Bash permission once the MCP
 *                           verbs cover the dev loop; never null/blank
 */
public record HarnessConfigResult(
        Path mcpConfigPath,
        Path settingsPath,
        String removeBashSuggestion) {
}
