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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Golden-file test for {@link HarnessConfigWriter} (AC2, AC4, AC6). It runs the writer against a
 * throwaway empty config dir with a FIXED sentinel jar path, then asserts the produced
 * {@code .mcp.json} and {@code .claude/settings.json} match committed golden fixtures.
 *
 * <p>Comparison is <b>semantic</b> (parse both sides to maps, compare trees) so it is immune to
 * whitespace/indent/key-order churn. The jar path is the fixed sentinel
 * {@code /opt/no-bash-mcp/no-bash-mcp-0.1.0-SNAPSHOT.jar}, so the golden is machine-stable.</p>
 *
 * <p>Two files, two golden assertions: {@code .mcp.json} carries ONLY {@code mcpServers};
 * {@code permissions.deny} lives in {@code .claude/settings.json}.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class HarnessConfigWriterGoldenTest {

    /** Fixed sentinel jar path — keeps the golden byte-stable across machines. */
    static final String SENTINEL_JAR = "/opt/no-bash-mcp/no-bash-mcp-0.1.0-SNAPSHOT.jar";

    private static final String GOLDEN_MCP = "fixtures/bootstrap/golden-mcp.json";
    private static final String GOLDEN_SETTINGS = "fixtures/bootstrap/golden-settings.json";

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
    void the_produced_mcp_json_matches_the_golden_and_registers_only_no_bash_mcp(@TempDir Path dir)
            throws IOException {
        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        Map<String, Object> produced = parse(Files.readString(result.mcpConfigPath()));
        Map<String, Object> golden = parse(readResource(GOLDEN_MCP));

        assertThat(produced).isEqualTo(golden);
        // .mcp.json carries ONLY mcpServers — no permissions, no deny nested here.
        assertThat(produced).containsOnlyKeys("mcpServers");
    }

    @Test
    void the_produced_settings_json_matches_the_golden_permissions_deny(@TempDir Path dir)
            throws IOException {
        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        Map<String, Object> produced = parse(Files.readString(result.settingsPath()));
        Map<String, Object> golden = parse(readResource(GOLDEN_SETTINGS));

        assertThat(produced).isEqualTo(golden);
        // permissions.deny lives HERE (the settings file), not in .mcp.json.
        assertThat(produced).containsKey("permissions");
    }

    @Test
    void the_writer_places_the_two_files_at_the_documented_paths(@TempDir Path dir)
            throws IOException {
        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        assertThat(result.mcpConfigPath()).isEqualTo(dir.resolve(".mcp.json"));
        assertThat(result.settingsPath()).isEqualTo(dir.resolve(".claude").resolve("settings.json"));
        assertThat(Files.isRegularFile(result.mcpConfigPath())).isTrue();
        assertThat(Files.isRegularFile(result.settingsPath())).isTrue();
    }

    @Test
    void the_result_surfaces_an_assertable_remove_bash_suggestion(@TempDir Path dir)
            throws IOException {
        HarnessConfigResult result = new HarnessConfigWriter(mapper).write(dir, SENTINEL_JAR);

        // AC5 — the remove-Bash suggestion is a returned value an acceptance test can assert.
        assertThat(result.removeBashSuggestion())
                .isNotBlank()
                .containsIgnoringCase("remove")
                .containsIgnoringCase("Bash permission");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(String json) throws IOException {
        return mapper.readValue(json, Map.class);
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = HarnessConfigWriterGoldenTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("golden resource not found on the test classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
