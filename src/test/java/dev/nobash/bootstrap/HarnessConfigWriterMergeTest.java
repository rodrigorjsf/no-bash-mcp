package dev.nobash.bootstrap;

import io.micronaut.context.ApplicationContext;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance test for {@link HarnessConfigWriter} (AC8) — the writer runs against a throwaway config
 * dir that ALREADY holds foreign config, and the merged result must be the <b>union</b> that
 * clobbers nothing:
 *
 * <ul>
 *   <li>a foreign {@code mcpServers} entry survives, and {@code no-bash-mcp} is added beside it;</li>
 *   <li>a pre-existing {@code permissions.deny} entry survives, and the dangerous-git patterns are
 *       unioned in (no duplicates);</li>
 *   <li>an unrelated top-level settings key and an unrelated {@code permissions} sub-key survive.</li>
 * </ul>
 *
 * <p>Reading into generic maps (not typed records) is what guarantees the unknown keys survive.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HarnessConfigWriterMergeTest {

    private static final String SENTINEL_JAR = "/opt/no-bash-mcp/no-bash-mcp-0.1.0-SNAPSHOT.jar";

    private static ApplicationContext context;
    private static ObjectMapper mapper;

    @BeforeAll
    static void boot() {
        context = ApplicationContext.run();
        mapper = context.getBean(ObjectMapper.class);
    }

    @AfterAll
    static void shutdown() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void merging_into_an_existing_mcp_json_preserves_foreign_servers_and_adds_no_bash_mcp(
            @TempDir Path dir) throws IOException {
        // Pre-seed an existing .mcp.json with a foreign server registration.
        String existingMcp = """
                {
                  "mcpServers": {
                    "some-other-server": {
                      "command": "node",
                      "args": ["other.js"]
                    }
                  }
                }
                """;
        Files.writeString(dir.resolve(".mcp.json"), existingMcp, StandardCharsets.UTF_8);

        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        Map<String, Object> mcp = parse(Files.readString(result.mcpConfigPath()));
        Map<String, Object> servers = (Map<String, Object>) mcp.get("mcpServers");

        // The foreign server is preserved untouched.
        assertThat(servers).containsKey("some-other-server");
        Map<String, Object> foreign = (Map<String, Object>) servers.get("some-other-server");
        assertThat(foreign.get("command")).isEqualTo("node");
        assertThat(foreign.get("args")).isEqualTo(List.of("other.js"));

        // no-bash-mcp is added beside it with the java -jar launcher.
        assertThat(servers).containsKey("no-bash-mcp");
        Map<String, Object> ours = (Map<String, Object>) servers.get("no-bash-mcp");
        assertThat(ours.get("command")).isEqualTo("java");
        assertThat(ours.get("args")).isEqualTo(List.of("-jar", SENTINEL_JAR));
    }

    @Test
    @SuppressWarnings("unchecked")
    void merging_into_an_existing_settings_json_unions_deny_and_preserves_unrelated_keys(
            @TempDir Path dir) throws IOException {
        // Pre-seed .claude/settings.json with: an unrelated top-level key, an unrelated permissions
        // sub-key (allow), and a pre-existing deny entry.
        Path claudeDir = dir.resolve(".claude");
        Files.createDirectories(claudeDir);
        String existingSettings = """
                {
                  "model": "claude-opus-4",
                  "permissions": {
                    "allow": ["Read(*)"],
                    "deny": ["Bash(rm -rf:*)"]
                  }
                }
                """;
        Files.writeString(claudeDir.resolve("settings.json"), existingSettings, StandardCharsets.UTF_8);

        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        Map<String, Object> settings = parse(Files.readString(result.settingsPath()));

        // Unrelated top-level key survives.
        assertThat(settings.get("model")).isEqualTo("claude-opus-4");

        Map<String, Object> permissions = (Map<String, Object>) settings.get("permissions");
        // Unrelated permissions sub-key (allow) survives.
        assertThat(permissions.get("allow")).isEqualTo(List.of("Read(*)"));

        List<Object> deny = (List<Object>) permissions.get("deny");
        // Pre-existing deny entry survives.
        assertThat(deny).contains("Bash(rm -rf:*)");
        // Every dangerous-git pattern is unioned in.
        assertThat(deny).containsAll(DangerousGitDenyList.denyRules());
        // No duplicates introduced (the pre-existing entry appears exactly once).
        assertThat(deny.stream().filter("Bash(rm -rf:*)"::equals).count()).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void re_running_the_writer_is_idempotent_and_introduces_no_duplicate_deny_entries(
            @TempDir Path dir) throws IOException {
        HarnessConfigWriter writer = new HarnessConfigWriter(mapper);

        writer.write(dir, SENTINEL_JAR);
        HarnessConfigResult second = writer.write(dir, SENTINEL_JAR);

        Map<String, Object> settings = parse(Files.readString(second.settingsPath()));
        Map<String, Object> permissions = (Map<String, Object>) settings.get("permissions");
        List<Object> deny = (List<Object>) permissions.get("deny");

        // Exactly the deny-list set, no duplicates after a second run.
        assertThat(deny).containsExactlyInAnyOrderElementsOf(DangerousGitDenyList.denyRules());

        Map<String, Object> mcp = parse(Files.readString(second.mcpConfigPath()));
        Map<String, Object> servers = (Map<String, Object>) mcp.get("mcpServers");
        // Only the single no-bash-mcp registration (idempotent re-registration).
        assertThat(servers).containsOnlyKeys("no-bash-mcp");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws IOException {
        return mapper.readValue(json, Map.class);
    }
}
