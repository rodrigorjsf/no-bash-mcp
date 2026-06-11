package dev.nobash.adapter.out.ecosystem.node;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
import dev.nobash.application.verb.tests.ReportPlan;
import dev.nobash.application.verb.tests.RunInterpretation;
import dev.nobash.application.verb.tests.TestTarget;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.domain.result.Outcome;
import dev.nobash.domain.result.TestFinding;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Node {@link NodeEcosystemAdapter} unit (ADR-0011, PRD-3 slice 4): the ecosystem-specific
 * VALUES it returns to the use-case. D52 framework-aware detection (jest/vitest/mocha — NEVER a
 * bare {@code package.json}); the installed-check routing (the generic resolver for {@code npx},
 * NOT the mvn-hardcoded port); the full-suite {@code jest --json --outputFile=<fresh>
 * --testLocationInResults --no-install} argv; reading the report from the FRESH file (never
 * stdout); and the three preflight operational errors surfaced via the {@code reportAbsent} channel
 * with an INERT exec (no jest launched): {@code DEPS_NOT_INSTALLED}, {@code UNSUPPORTED_TEST_FRAMEWORK},
 * and {@code UNSUPPORTED_TARGET}.
 *
 * <p>The adapter mirrors {@code GoEcosystemAdapter}; the exploding-port proof is replicated to
 * verify the Node installed-check routes through {@link ManagerPathResolver}, never the
 * mvn-hardcoded {@code executor.isManagerInstalled()}.</p>
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NodeEcosystemAdapterTest {

    /** A port that explodes on isManagerInstalled — proves the adapter never routes the check there. */
    private static final class ExplodingInstalledPort implements CommandExecutorPort {
        @Override
        public boolean isManagerInstalled() {
            throw new AssertionError("Node must check npx via the resolver, NOT the mvn-hardcoded port");
        }

        @Override
        public ExecResult execute(ExecSpec spec) {
            throw new AssertionError("execute must not be reached in these value tests");
        }
    }

    /** A resolver whose answer for a given manager is fixed. */
    private static ManagerPathResolver resolverFor(String manager, boolean present) {
        return m -> m.equals(manager) && present;
    }

    private static NodeEcosystemAdapter adapter(CommandExecutorPort port, ManagerPathResolver resolver) {
        return new NodeEcosystemAdapter(port, resolver);
    }

    /** A jest project: package.json declaring jest in devDependencies + an installed jest binary. */
    private static void writeJestProject(Path dir, boolean installed) throws Exception {
        Files.writeString(dir.resolve("package.json"),
                "{\n  \"name\": \"x\",\n  \"devDependencies\": { \"jest\": \"^30.4.1\" }\n}\n");
        if (installed) {
            Path bin = dir.resolve("node_modules").resolve(".bin");
            Files.createDirectories(bin);
            Files.writeString(bin.resolve("jest"), "#!/usr/bin/env node\n");
        }
    }

    @Nested
    class detection_D52_is_framework_aware {

        @Test
        void a_package_json_declaring_jest_is_detected(@TempDir Path dir) throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            Files.writeString(dir.resolve("package.json"),
                    "{\"devDependencies\":{\"jest\":\"^30.4.1\"}}");

            assertThat(node.detects(dir)).isTrue();
        }

        @Test
        void a_jest_key_in_package_json_is_detected(@TempDir Path dir) throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            Files.writeString(dir.resolve("package.json"),
                    "{\"jest\":{\"testEnvironment\":\"node\"}}");

            assertThat(node.detects(dir)).isTrue();
        }

        @Test
        void a_jest_config_file_is_detected_even_without_a_package_json_dep(@TempDir Path dir) throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            Files.writeString(dir.resolve("package.json"), "{\"name\":\"x\"}");
            Files.writeString(dir.resolve("jest.config.js"), "module.exports = {};\n");

            assertThat(node.detects(dir)).isTrue();
        }

        @Test
        void vitest_and_mocha_are_DETECTED_so_they_can_be_rejected_not_NO_MANAGER_DETECTED(@TempDir Path dir)
                throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            Files.writeString(dir.resolve("package.json"), "{\"devDependencies\":{\"vitest\":\"^2\"}}");
            assertThat(node.detects(dir)).as("vitest is detected (then rejected in buildExec)").isTrue();

            Files.writeString(dir.resolve("package.json"), "{\"devDependencies\":{\"mocha\":\"^10\"}}");
            assertThat(node.detects(dir)).as("mocha is detected (then rejected in buildExec)").isTrue();
        }

        @Test
        void a_bare_tooling_only_package_json_is_NOT_detected_no_AMBIGUOUS_SCOPE_regression(@TempDir Path dir)
                throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            // prettier/eslint only — no recognized test framework. MUST NOT match (else a polyglot
            // root co-located with pom.xml/go.mod spuriously trips AMBIGUOUS_SCOPE).
            Files.writeString(dir.resolve("package.json"),
                    "{\"devDependencies\":{\"prettier\":\"^3\",\"eslint\":\"^9\"}}");

            assertThat(node.detects(dir)).isFalse();
        }

        @Test
        void a_tooling_only_package_json_beside_a_pom_or_go_mod_does_not_match(@TempDir Path dir)
                throws Exception {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            Files.writeString(dir.resolve("package.json"), "{\"devDependencies\":{\"eslint\":\"^9\"}}");
            Files.writeString(dir.resolve("pom.xml"), "<project/>");
            Files.writeString(dir.resolve("go.mod"), "module example.com/x\n");

            assertThat(node.detects(dir))
                    .as("a co-located tooling-only package.json must not make Node a second match")
                    .isFalse();
        }

        @Test
        void no_package_json_at_all_is_not_detected(@TempDir Path dir) {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            assertThat(node.detects(dir)).isFalse();
        }

        @Test
        void the_marker_description_names_jest() {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            assertThat(node.markerDescription()).contains("package.json");
        }
    }

    @Nested
    class installed_check_uses_the_resolver_not_the_mvn_port {

        @Test
        void isInstalled_is_true_when_the_resolver_finds_the_launcher_on_path() {
            // The port explodes if its mvn-hardcoded isManagerInstalled() is consulted — a passing
            // test is direct evidence the adapter routed the check through the resolver.
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            assertThat(node.isInstalled()).isTrue();
        }

        @Test
        void isInstalled_is_false_when_the_resolver_does_not_find_the_launcher() {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", false));
            assertThat(node.isInstalled()).isFalse();
        }

        @Test
        void isInstalled_keys_on_the_node_launcher_not_some_other_manager() {
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("mvn", true));
            assertThat(node.isInstalled()).as("the Node check must not be satisfied by mvn").isFalse();
        }
    }

    @Nested
    class exec_plan_drives_jest_with_injected_flags {

        @Test
        void buildExec_injects_json_outputFile_testLocationInResults_and_no_install(@TempDir Path dir)
                throws Exception {
            writeJestProject(dir, true);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);
            ExecSpec spec = plan.spec();

            assertThat(spec.argv()).contains("jest", "--json", "--testLocationInResults", "--no-install");
            // a fresh --outputFile=<path> is injected, and the report source is that same fresh file
            assertThat(spec.argv())
                    .anyMatch(a -> a.startsWith("--outputFile="))
                    .as("the report goes to a fresh file, never stdout");
            String outputFileArg = spec.argv().stream()
                    .filter(a -> a.startsWith("--outputFile=")).findFirst().orElseThrow();
            assertThat(outputFileArg).endsWith(plan.reportSource().toString());
            assertThat(spec.workingDir()).isEqualTo(dir.toString());
            assertThat(spec.timeoutSeconds()).isEqualTo(600);
            // no preflight short-circuit on a healthy jest project
            assertThat(plan.preflight()).isNull();
        }

        @Test
        void buildExec_allocates_a_fresh_output_file_per_run_report_freshness_D27(@TempDir Path dir)
                throws Exception {
            writeJestProject(dir, true);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            ReportPlan a = node.buildExec(List.of(), 600, dir, null);
            ReportPlan b = node.buildExec(List.of(), 600, dir, null);

            assertThat(a.reportSource()).isNotEqualTo(b.reportSource());
        }
    }

    @Nested
    class interpret_reads_the_fresh_file_and_folds_jest_json {

        @Test
        void an_assertion_failure_and_a_module_load_failure_fold_into_the_graph(@TempDir Path dir)
                throws Exception {
            writeJestProject(dir, true);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);

            // jest wrote its report to the fresh --outputFile (stdout stays empty). Simulate that.
            Files.writeString(plan.reportSource(), read("fixtures/jest/jest-loc.json"));
            ExecResult result = new ExecResult(1, "", "", false);

            RunInterpretation interp = node.interpret(result, plan);

            assertThat(interp.isReportAbsent()).as("a produced report is never report-absent").isFalse();
            NormalizedRun run = interp.run();
            assertThat(run.findings())
                    .anySatisfy(f -> assertThat(f).isInstanceOf(TestFinding.class))
                    .anySatisfy(f -> assertThat(f).isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                        assertThat(cf.scope()).isEqualTo(ContainerScope.FILE);
                        assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                    }));
            assertThat(run.ok()).isFalse();
        }

        @Test
        void an_all_passed_report_normalizes_to_a_green_run(@TempDir Path dir) throws Exception {
            writeJestProject(dir, true);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));
            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);

            Files.writeString(plan.reportSource(), read("fixtures/jest/jest-all-passed.json"));
            ExecResult result = new ExecResult(0, "", "", false);

            RunInterpretation interp = node.interpret(result, plan);

            assertThat(interp.isReportAbsent()).isFalse();
            assertThat(interp.run().summary().passed()).isEqualTo(2);
            assertThat(interp.run().ok()).isTrue();
        }
    }

    @Nested
    class preflight_operational_errors_via_reportAbsent_no_jest_launched {

        @Test
        void DEPS_NOT_INSTALLED_when_the_jest_binary_is_unresolvable_in_node_modules(@TempDir Path dir)
                throws Exception {
            // jest declared in package.json but node_modules/.bin/jest absent → never installed.
            writeJestProject(dir, false);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);

            // the planned exec is INERT (no jest launched) and a preflight decision is carried
            assertThat(plan.spec().argv()).doesNotContain("jest", "--json");
            assertThat(plan.preflight()).isNotNull();

            RunInterpretation interp = node.interpret(new ExecResult(0, "", "", false), plan);
            assertThat(interp.isReportAbsent()).isTrue();
            assertThat(interp.absence().code()).isEqualTo(ErrorCode.DEPS_NOT_INSTALLED);
            assertThat(interp.absence().hint().toLowerCase()).contains("install");
        }

        @Test
        void UNSUPPORTED_TEST_FRAMEWORK_for_vitest(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{\"devDependencies\":{\"vitest\":\"^2\"}}");
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);
            assertThat(plan.spec().argv()).doesNotContain("jest");
            assertThat(plan.preflight()).isNotNull();

            RunInterpretation interp = node.interpret(new ExecResult(0, "", "", false), plan);
            assertThat(interp.isReportAbsent()).isTrue();
            assertThat(interp.absence().code()).isEqualTo(ErrorCode.UNSUPPORTED_TEST_FRAMEWORK);
        }

        @Test
        void UNSUPPORTED_TEST_FRAMEWORK_for_mocha(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("package.json"), "{\"devDependencies\":{\"mocha\":\"^10\"}}");
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            ReportPlan plan = node.buildExec(List.of(), 600, dir, null);
            RunInterpretation interp = node.interpret(new ExecResult(0, "", "", false), plan);

            assertThat(interp.isReportAbsent()).isTrue();
            assertThat(interp.absence().code()).isEqualTo(ErrorCode.UNSUPPORTED_TEST_FRAMEWORK);
        }

        @Test
        void a_structured_target_on_node_is_an_honest_UNSUPPORTED_TARGET_full_suite_only(@TempDir Path dir)
                throws Exception {
            writeJestProject(dir, true);
            NodeEcosystemAdapter node = adapter(new ExplodingInstalledPort(), resolverFor("npx", true));

            TestTarget target = new TestTarget("CLASS", "Foo");
            ReportPlan plan = node.buildExec(List.of(), 600, dir, target);

            assertThat(plan.spec().argv()).as("no jest launched on an unsupported-target run")
                    .doesNotContain("jest");
            assertThat(plan.preflight()).isNotNull();

            RunInterpretation interp = node.interpret(new ExecResult(0, "", "", false), plan);
            assertThat(interp.isReportAbsent()).isTrue();
            assertThat(interp.absence().code()).isEqualTo(ErrorCode.UNSUPPORTED_TARGET);
            assertThat((interp.absence().message() + " " + interp.absence().hint()).toLowerCase())
                    .contains("node");
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = NodeEcosystemAdapterTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
