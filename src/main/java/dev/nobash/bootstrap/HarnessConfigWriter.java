package dev.nobash.bootstrap;

import io.micronaut.serde.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Claude Code <b>harness-config writer</b> for the bootstrap skill (decision-log D15/D16,
 * {@code docs/design/bootstrap-and-deployment.md}). Given the current on-disk config state and the
 * jar path, it produces two declarative permission documents:
 *
 * <ol>
 *   <li>{@code .mcp.json} — registers the {@code no-bash-mcp} MCP server under {@code mcpServers}
 *       (launcher {@code java -jar <jar>}). Carries ONLY {@code mcpServers}.</li>
 *   <li>{@code .claude/settings.json} — carries the transitional dangerous-git deny-list as a
 *       {@code permissions.deny} array (from {@link DangerousGitDenyList}). A <b>declarative</b>
 *       permission-config entry, NOT a {@code PreToolUse} bash hook (D16).</li>
 * </ol>
 *
 * <p>The two concerns live in two distinct files: {@code permissions.deny} is NOT nested inside
 * {@code .mcp.json} (which carries only server registration). This is the Claude Code shape the
 * README documents.</p>
 *
 * <h3>Merge, not clobber</h3>
 * <p>Both files are read (if present) into generic {@code LinkedHashMap} trees and merged
 * <b>additively</b>: a foreign {@code mcpServers} entry, a pre-existing {@code permissions.deny}
 * entry, and any unrelated top-level key are all <b>preserved</b>; the {@code no-bash-mcp}
 * registration and the dangerous-git deny patterns are unioned in (deduped). Reading into generic
 * maps — rather than typed records — is what guarantees unknown keys survive the round-trip.</p>
 *
 * <h3>Determinism</h3>
 * <p>Insertion-ordered maps ({@code LinkedHashMap}) plus a stable build order give byte-stable
 * output for the golden-file tests; the jar path is an <b>injected input</b> (a parameter), never
 * computed from the environment, so the produced {@code .mcp.json} is identical on every machine.</p>
 *
 * <p>Placement: top-level {@code dev.nobash.bootstrap}, outside the Domain/Application/Adapter
 * triad (DESIGN §3/§8 — a separate deliverable, implements neither outbound port). It depends only
 * on micronaut-serde, the JDK, and {@link DangerousGitDenyList}; it imports nothing from the triad.</p>
 */
public final class HarnessConfigWriter {

    /** The MCP server id registered under {@code mcpServers} (matches {@code application.yml}). */
    public static final String SERVER_ID = "no-bash-mcp";

    /** The launcher command — {@code java -jar <jar>} over STDIO is the only supported launcher. */
    static final String LAUNCHER_COMMAND = "java";

    private static final String MCP_FILE = ".mcp.json";
    private static final String SETTINGS_DIR = ".claude";
    private static final String SETTINGS_FILE = "settings.json";

    private static final String KEY_MCP_SERVERS = "mcpServers";
    private static final String KEY_PERMISSIONS = "permissions";
    private static final String KEY_DENY = "deny";

    private static final String REMOVE_BASH_SUGGESTION =
            "Once the no-bash-mcp verbs cover your dev loop, remove the agent's Bash permission "
            + "from your harness config — keeping Bash enabled alongside the MCP defeats the purpose. "
            + "Until native mutating-git verbs ship, the transitional permissions.deny entries block "
            + "only the destructive git operations, so forward-progress git still runs via Bash.";

    private final ObjectMapper objectMapper;

    /**
     * @param objectMapper the micronaut-serde {@link ObjectMapper} (the ONLY JSON facility — no
     *                     other JSON library is on the classpath, DESIGN §7)
     */
    public HarnessConfigWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Write (merging into any existing config) the {@code .mcp.json} and {@code .claude/settings.json}
     * under {@code configDir}, and return the result carrying both paths and the remove-Bash
     * suggestion.
     *
     * @param configDir the harness config directory (created if absent)
     * @param jarPath   the absolute path to the packaged server jar (an injected input, so the
     *                  produced {@code .mcp.json} is machine-stable)
     * @return the two produced paths plus the assertable remove-Bash suggestion
     * @throws IOException if a file cannot be read or written
     */
    public HarnessConfigResult write(Path configDir, String jarPath) throws IOException {
        Files.createDirectories(configDir);

        Path mcpConfigPath = configDir.resolve(MCP_FILE);
        Map<String, Object> mcpTree = readTree(mcpConfigPath);
        mergeMcpRegistration(mcpTree, jarPath);
        writeTree(mcpConfigPath, mcpTree);

        Path settingsDir = configDir.resolve(SETTINGS_DIR);
        Files.createDirectories(settingsDir);
        Path settingsPath = settingsDir.resolve(SETTINGS_FILE);
        Map<String, Object> settingsTree = readTree(settingsPath);
        mergeDenyList(settingsTree);
        writeTree(settingsPath, settingsTree);

        return new HarnessConfigResult(mcpConfigPath, settingsPath, REMOVE_BASH_SUGGESTION);
    }

    // ---- .mcp.json merge ----

    /**
     * Union the {@code no-bash-mcp} server registration into the {@code mcpServers} map, preserving
     * any foreign server entries. The {@code no-bash-mcp} entry is set/overwritten (idempotent
     * re-registration with the current jar path); other servers are untouched.
     */
    private void mergeMcpRegistration(Map<String, Object> tree, String jarPath) {
        Map<String, Object> servers = childMap(tree, KEY_MCP_SERVERS);

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("command", LAUNCHER_COMMAND);
        entry.put("args", List.of("-jar", jarPath));
        servers.put(SERVER_ID, entry);
    }

    // ---- settings.json permissions.deny merge ----

    /**
     * Union the dangerous-git deny patterns into {@code permissions.deny}, preserving any
     * pre-existing entries and not duplicating ones already present. Every other settings key
     * (and every other {@code permissions} sub-key) is left untouched.
     */
    private void mergeDenyList(Map<String, Object> tree) {
        Map<String, Object> permissions = childMap(tree, KEY_PERMISSIONS);
        List<Object> deny = childList(permissions, KEY_DENY);

        for (String rule : DangerousGitDenyList.denyRules()) {
            if (!deny.contains(rule)) {
                deny.add(rule);
            }
        }
    }

    // ---- generic tree helpers (preserve unknown keys) ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> readTree(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return new LinkedHashMap<>();
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        if (json.isBlank()) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
        // Copy into a mutable, insertion-ordered map so we control emission order deterministically.
        return new LinkedHashMap<>(parsed);
    }

    private void writeTree(Path file, Map<String, Object> tree) throws IOException {
        String json = objectMapper.writeValueAsString(tree);
        Files.writeString(file, json, StandardCharsets.UTF_8);
    }

    /**
     * Fetch (or create) a child object map under {@code key}, preserving any existing entries. The
     * existing child is rehomed into a mutable {@code LinkedHashMap} so subsequent puts are stable
     * and order-controlled.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> childMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        Map<String, Object> child = existing instanceof Map<?, ?> m
                ? new LinkedHashMap<>((Map<String, Object>) m)
                : new LinkedHashMap<>();
        parent.put(key, child);
        return child;
    }

    /**
     * Fetch (or create) a child array list under {@code key}, preserving any existing elements.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> childList(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        List<Object> child = existing instanceof List<?> l
                ? new ArrayList<>((List<Object>) l)
                : new ArrayList<>();
        parent.put(key, child);
        return child;
    }
}
