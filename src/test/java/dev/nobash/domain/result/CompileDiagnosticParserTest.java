package dev.nobash.domain.result;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure, I/O-free golden-fixture tests for {@link CompileDiagnosticParser} (ADR-0009, issue #23).
 *
 * <p>Each test reads a pre-baked compiler-output text fixture from the classpath (in-memory),
 * hands it to the pure parser, and asserts the parsed {@link CompileDiagnostic} entries.
 * No File, no Path, no process — exactly mirroring {@code SurefireNormalizerTest}.</p>
 *
 * <h3>Fixtures</h3>
 * <ul>
 *   <li>{@code compiler-diagnostics-errors-and-warnings.txt} — real maven-compiler-plugin output
 *       shape with 2 coordinate-bearing ERROR diagnostics and 2 WARNING diagnostics plus Maven
 *       noise lines ({@code [ERROR] BUILD FAILURE}, {@code [ERROR] COMPILATION ERROR :},
 *       {@code [INFO] 3 errors} — Maven's own tally, which is NOT a parseable diagnostic).</li>
 *   <li>{@code compiler-clean-build.txt} — successful build output with no diagnostics.</li>
 *   <li>{@code compiler-noise-only.txt} — only Maven noise {@code [ERROR]} lines,
 *       no file:[line,col] shape → zero diagnostics parsed.</li>
 * </ul>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class CompileDiagnosticParserTest {

    private static final CompileDiagnosticParser PARSER = new CompileDiagnosticParser();

    private static final String ERRORS_AND_WARNINGS =
            "fixtures/maven/compiler-diagnostics-errors-and-warnings.txt";
    private static final String CLEAN_BUILD =
            "fixtures/maven/compiler-clean-build.txt";
    private static final String NOISE_ONLY =
            "fixtures/maven/compiler-noise-only.txt";

    // ---- golden-fixture matrix ----

    @TestFactory
    @DisplayName("golden-fixture matrix for CompileDiagnosticParser")
    Stream<DynamicTest> golden_fixture_matrix() {
        return Stream.of(

                // A real maven-compiler-plugin output with mixed errors and warnings.
                // Noise lines ([ERROR] BUILD FAILURE, [ERROR] COMPILATION ERROR :, etc.)
                // must be filtered; only [ERROR]/<file>:[line,col] and [WARNING]/<file>:[line,col]
                // shapes count.
                DynamicTest.dynamicTest("errors_and_warnings_fixture_produces_correct_diagnostics", () -> {
                    String output = read(ERRORS_AND_WARNINGS);
                    List<CompileDiagnostic> diagnostics = PARSER.parse(output);

                    // 2 ERROR + 2 WARNING = 4 total (noise lines like [ERROR] BUILD FAILURE
                    // and [ERROR] COMPILATION ERROR : do not carry [line,col] and are filtered out)
                    assertThat(diagnostics).hasSize(4);

                    // Severity counts
                    long errors   = diagnostics.stream().filter(d -> "ERROR".equals(d.severity())).count();
                    long warnings = diagnostics.stream().filter(d -> "WARNING".equals(d.severity())).count();
                    assertThat(errors).isEqualTo(2);
                    assertThat(warnings).isEqualTo(2);

                    // Spot-check the first ERROR diagnostic
                    CompileDiagnostic firstError = diagnostics.stream()
                            .filter(d -> "ERROR".equals(d.severity()))
                            .findFirst().orElseThrow();
                    assertThat(firstError.file())
                            .contains("FooTest.java");
                    assertThat(firstError.line()).isEqualTo(15);
                    assertThat(firstError.col()).isEqualTo(9);
                    assertThat(firstError.message()).contains("cannot find symbol");

                    // Spot-check a WARNING diagnostic
                    CompileDiagnostic warning = diagnostics.stream()
                            .filter(d -> "WARNING".equals(d.severity()))
                            .findFirst().orElseThrow();
                    assertThat(warning.file()).contains("Foo.java");
                    assertThat(warning.line()).isEqualTo(10);
                    assertThat(warning.col()).isEqualTo(5);
                    assertThat(warning.message()).contains("deprecation warning");
                }),

                // A clean build output (BUILD SUCCESS, nothing with [line,col]) → zero diagnostics.
                DynamicTest.dynamicTest("clean_build_output_produces_no_diagnostics", () -> {
                    String output = read(CLEAN_BUILD);
                    List<CompileDiagnostic> diagnostics = PARSER.parse(output);

                    assertThat(diagnostics).isEmpty();
                }),

                // Maven noise-only [ERROR] lines (BUILD FAILURE, COMPILATION ERROR, etc.)
                // without the file:[line,col] shape → zero diagnostics parsed.
                DynamicTest.dynamicTest("maven_noise_lines_without_file_coords_produce_no_diagnostics", () -> {
                    String output = read(NOISE_ONLY);
                    List<CompileDiagnostic> diagnostics = PARSER.parse(output);

                    assertThat(diagnostics).isEmpty();
                })
        );
    }

    // ---- null/blank safety ----

    @Test
    void null_input_produces_empty_list() {
        assertThat(PARSER.parse(null)).isEmpty();
    }

    @Test
    void blank_input_produces_empty_list() {
        assertThat(PARSER.parse("   \n  ")).isEmpty();
    }

    // ---- individual line-shape assertions ----

    @Test
    void single_error_line_is_parsed_correctly() {
        String line = "[ERROR] /src/main/java/com/example/Foo.java:[42,13] cannot find symbol";
        List<CompileDiagnostic> result = PARSER.parse(line);

        assertThat(result).hasSize(1);
        CompileDiagnostic d = result.get(0);
        assertThat(d.severity()).isEqualTo("ERROR");
        assertThat(d.file()).isEqualTo("/src/main/java/com/example/Foo.java");
        assertThat(d.line()).isEqualTo(42);
        assertThat(d.col()).isEqualTo(13);
        assertThat(d.message()).isEqualTo("cannot find symbol");
    }

    @Test
    void single_warning_line_is_parsed_correctly() {
        String line = "[WARNING] /src/Bar.java:[7,1] unchecked or unsafe operations";
        List<CompileDiagnostic> result = PARSER.parse(line);

        assertThat(result).hasSize(1);
        CompileDiagnostic d = result.get(0);
        assertThat(d.severity()).isEqualTo("WARNING");
        assertThat(d.file()).isEqualTo("/src/Bar.java");
        assertThat(d.line()).isEqualTo(7);
        assertThat(d.col()).isEqualTo(1);
        assertThat(d.message()).isEqualTo("unchecked or unsafe operations");
    }

    @Test
    void noise_error_line_without_file_coords_is_not_parsed() {
        // Maven emits these — must not be matched by the diagnostic pattern.
        List<String> noiseLines = List.of(
                "[ERROR] BUILD FAILURE",
                "[ERROR] -> [Help 1]",
                "[ERROR] Failed to execute goal",
                "[ERROR] COMPILATION ERROR :",
                "[ERROR]"
        );
        for (String noise : noiseLines) {
            assertThat(PARSER.parse(noise))
                    .as("noise line '%s' must not produce any diagnostic", noise)
                    .isEmpty();
        }
    }

    @Test
    void message_is_passed_through_verbatim_including_special_chars() {
        // The message text is untrusted and passed through as-is (P9 neutralizes at Envelope level).
        String line = "[ERROR] /src/Foo.java:[1,1] symbol:   class UndefinedClass\n  location: package dev.example";
        // Only the first line is a diagnostic; the continuation line is not a diagnostic shape.
        List<CompileDiagnostic> result = PARSER.parse(line);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).message()).isEqualTo("symbol:   class UndefinedClass");
    }

    @Test
    void coordinates_are_parsed_as_integers_regardless_of_surrounding_text() {
        // Locale-independence: line and col are pure ASCII digit sequences in the [line,col] bracket.
        String line = "[ERROR] C:\\Users\\user\\project\\src\\main\\java\\Foo.java:[123,456] incompatible types";
        List<CompileDiagnostic> result = PARSER.parse(line);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).line()).isEqualTo(123);
        assertThat(result.get(0).col()).isEqualTo(456);
    }

    // ---- helper ----

    private static String read(String resourcePath) {
        try (InputStream is = CompileDiagnosticParserTest.class.getClassLoader()
                .getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IllegalStateException("Fixture not found on classpath: " + resourcePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read fixture: " + resourcePath, e);
        }
    }
}
