package dev.nobash.adapter.out.ecosystem.go;

import dev.nobash.adapter.out.ecosystem.ManagerPathResolver;
import dev.nobash.application.verb.tests.ReportPlan;
import dev.nobash.application.verb.tests.RunInterpretation;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.result.ContainerFinding;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.NormalizedRun;
import dev.nobash.domain.result.Outcome;
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
 * The Go {@link GoEcosystemAdapter} unit: the ecosystem-specific VALUES it returns to the
 * use-case (ADR-0011, PRD-3 slice 2). Marker detection, the launcher name, the installed-check
 * routing (the generic resolver, NOT the mvn-hardcoded port), the full-suite argv, and the
 * always-normalized interpretation (a Go build failure folds INTO the graph, never report-absence).
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GoEcosystemAdapterTest {

    /** A port that explodes on isManagerInstalled — proves the adapter never routes the Go check there. */
    private static final class ExplodingInstalledPort implements CommandExecutorPort {
        @Override
        public boolean isManagerInstalled() {
            throw new AssertionError("Go must check `go` via the resolver, NOT the mvn-hardcoded port");
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

    private static GoEcosystemAdapter adapter(CommandExecutorPort port, ManagerPathResolver resolver) {
        return new GoEcosystemAdapter(port, resolver);
    }

    @Nested
    class detection_and_identity {

        @Test
        void detects_iff_a_go_mod_regular_file_exists_at_the_dir(@TempDir Path dir) throws Exception {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            assertThat(go.detects(dir)).as("no go.mod → not detected").isFalse();

            Files.writeString(dir.resolve("go.mod"), "module example.com/x\n");
            assertThat(go.detects(dir)).as("go.mod present → detected").isTrue();
        }

        @Test
        void a_pom_xml_alone_is_not_a_go_project(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("pom.xml"), "<project/>");
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            assertThat(go.detects(dir)).isFalse();
        }

        @Test
        void the_manager_binary_is_go_and_the_marker_is_go_mod() {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            assertThat(go.managerBinary()).isEqualTo("go");
            assertThat(go.markerDescription()).isEqualTo("go.mod");
        }
    }

    @Nested
    class installed_check_uses_the_resolver_not_the_mvn_port {

        @Test
        void isInstalled_is_true_when_the_resolver_finds_go_on_path() {
            // The port explodes if its mvn-hardcoded isManagerInstalled() is consulted — a passing
            // test is direct evidence the adapter routed the check through the resolver for `go`.
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            assertThat(go.isInstalled()).isTrue();
        }

        @Test
        void isInstalled_is_false_when_the_resolver_does_not_find_go_on_path() {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", false));

            assertThat(go.isInstalled()).isFalse();
        }

        @Test
        void isInstalled_keys_on_go_specifically_not_some_other_manager() {
            // A resolver that only knows `mvn` must NOT make the Go adapter report installed.
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("mvn", true));

            assertThat(go.isInstalled()).as("the Go check must ask for `go`, not `mvn`").isFalse();
        }
    }

    @Nested
    class exec_plan {

        @Test
        void buildExec_is_a_full_suite_go_test_json_argv_in_the_working_dir(@TempDir Path dir) {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            ReportPlan plan = go.buildExec(List.of(), 600, dir, null);
            ExecSpec spec = plan.spec();

            assertThat(spec.argv()).containsExactly("go", "test", "-json", "./...");
            assertThat(spec.workingDir()).isEqualTo(dir.toString());
            assertThat(spec.timeoutSeconds()).isEqualTo(600);
        }

        @Test
        void buildExec_ignores_agent_flags_no_go_flag_policy(@TempDir Path dir) {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));

            // Even if vetted flags were somehow non-empty, Go v1 injects none of them.
            ReportPlan plan = go.buildExec(List.of("-race", "-count=2"), 600, dir, null);

            assertThat(plan.spec().argv()).containsExactly("go", "test", "-json", "./...");
        }
    }

    @Nested
    class interpretation_always_normalizes {

        @Test
        void a_passing_run_normalizes_to_a_green_run_never_report_absent(@TempDir Path dir) throws Exception {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));
            ReportPlan plan = go.buildExec(List.of(), 600, dir, null);
            ExecResult result = new ExecResult(0, read("fixtures/go/go-all-passed.ndjson"), "", false);

            RunInterpretation interp = go.interpret(result, plan);

            assertThat(interp.isReportAbsent()).as("Go never returns report-absent").isFalse();
            NormalizedRun run = interp.run();
            assertThat(run.summary().passed()).isEqualTo(2);
            assertThat(run.ok()).isTrue();
        }

        @Test
        void a_build_fail_run_folds_into_a_container_finding_PACKAGE_ERRORED_never_report_absent(
                @TempDir Path dir) throws Exception {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));
            ReportPlan plan = go.buildExec(List.of(), 600, dir, null);
            // A real non-compiling package: process exit 1, NDJSON build-fail events on stdout.
            ExecResult result = new ExecResult(1, read("fixtures/go/go-build-fail-src.ndjson"), "", false);

            RunInterpretation interp = go.interpret(result, plan);

            assertThat(interp.isReportAbsent())
                    .as("a Go build failure folds INTO the graph, it is NOT a report-absence")
                    .isFalse();
            NormalizedRun run = interp.run();
            assertThat(run.findings())
                    .singleElement()
                    .isInstanceOfSatisfying(ContainerFinding.class, cf -> {
                        assertThat(cf.scope()).isEqualTo(ContainerScope.PACKAGE);
                        assertThat(cf.outcome()).isEqualTo(Outcome.ERRORED);
                    });
            assertThat(run.ok()).as("a build-fail run is NOT ok (the floor's G5 keystone)").isFalse();
        }

        @Test
        void a_null_stdout_normalizes_to_an_empty_run_never_throws(@TempDir Path dir) {
            GoEcosystemAdapter go = adapter(new ExplodingInstalledPort(), resolverFor("go", true));
            ReportPlan plan = go.buildExec(List.of(), 600, dir, null);

            RunInterpretation interp = go.interpret(new ExecResult(0, null, null, false), plan);

            assertThat(interp.isReportAbsent()).isFalse();
            assertThat(interp.run().summary().total()).isZero();
        }
    }

    private static String read(String resource) throws Exception {
        try (InputStream in = GoEcosystemAdapterTest.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing test fixture on classpath: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
