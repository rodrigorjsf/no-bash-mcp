package dev.nobash.application.verb.forge;

import dev.nobash.application.forge.ForgeLogHandleRegistry;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.forge.PrCheck;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.result.ContainerScope;
import dev.nobash.domain.result.ContainerFinding;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PrChecksUseCase} (PRD-6 S1, #99): the container-aware fold, per-check handles,
 * error mapping, the {@code repo} override, and the secret-in-envelope discipline. All ports are
 * stubbed — no HTTP, no real git.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PrChecksUseCaseTest {

    private static final ForgeInstance GITHUB =
            new ForgeInstance("https://api.github.com", null, "MY_TOKEN_ENV");
    private static final String ORIGIN_SSH = "git@github.com:octo-org/hello-world.git";

    // ---- stubs ----

    private static CommandExecutorPort git(String originUrl, String head) {
        return new CommandExecutorPort() {
            @Override
            public boolean isManagerInstalled() {
                return true;
            }

            @Override
            public ExecResult execute(ExecSpec spec) {
                if (spec.argv().contains("get-url")) {
                    return new ExecResult(originUrl == null ? 1 : 0,
                            originUrl == null ? "" : originUrl, "", false);
                }
                if (spec.argv().contains("rev-parse")) {
                    return new ExecResult(head == null ? 128 : 0,
                            head == null ? "" : head, "", false);
                }
                return new ExecResult(0, "", "", false);
            }
        };
    }

    private static ForgeInstancePort allowlist(ForgeInstance instance, String matchHost) {
        return new ForgeInstancePort() {
            @Override
            public Optional<ForgeInstance> resolveForHost(String originHost) {
                return instance != null && matchHost.equals(originHost)
                        ? Optional.of(instance) : Optional.empty();
            }

            @Override
            public Optional<ForgeInstance> primary() {
                return Optional.ofNullable(instance);
            }
        };
    }

    private static ForgePort forgeReturning(List<ForgeCheckRun> runs) {
        return new ForgePort() {
            @Override
            public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
                return runs;
            }

            @Override
            public String fetchJobLog(ForgeLogRequest request) {
                throw new AssertionError("fetchJobLog is not part of pr_checks");
            }
        };
    }

    private static ForgePort forgeThrowing(ForgeAccessException ex) {
        return new ForgePort() {
            @Override
            public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
                throw ex;
            }

            @Override
            public String fetchJobLog(ForgeLogRequest request) {
                throw new AssertionError();
            }
        };
    }

    private static ForgeCheckRun completed(String name, String conclusion, Long jobId) {
        return new ForgeCheckRun(name, "completed", conclusion, jobId);
    }

    private static PrChecksUseCase useCase(CommandExecutorPort git, ForgeInstancePort allow, ForgePort forge) {
        return new PrChecksUseCase(git, allow, forge, new ForgeLogHandleRegistry());
    }

    // ---- container-aware fold ----

    @Test
    void a_failing_check_on_page_2_flips_ok_false_and_carries_a_handle(@TempDir Path dir) {
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success", 11L),
                completed("e2e", "failure", 22L));
        Envelope env = useCase(git(ORIGIN_SSH, "abc123"), allowlist(GITHUB, "github.com"),
                forgeReturning(runs)).run(dir.toString(), "abc123", null, null);

        assertThat(env.ok()).as("a failing check flips ok=false").isFalse();
        assertThat(env.manager()).as("manager is null for forge verbs").isNull();
        assertThat(env.untrusted()).isTrue();
        assertThat(env.prChecks()).extracting(PrCheck::name).containsExactly("build", "e2e");

        PrCheck failing = env.prChecks().get(1);
        assertThat(failing.conclusion()).isEqualTo("failure");
        assertThat(failing.handle()).as("a failing check-run carries a get_log handle").isNotBlank();

        PrCheck passing = env.prChecks().get(0);
        assertThat(passing.handle()).as("a passing check carries no handle").isNull();

        // The failing check is a top-level ContainerFinding(RUN).
        assertThat(env.failures()).hasSize(1);
        assertThat(env.failures().get(0)).isInstanceOfSatisfying(ContainerFinding.class,
                cf -> assertThat(cf.scope()).isEqualTo(ContainerScope.RUN));
    }

    @Test
    void an_all_passing_run_returns_ok_true_with_no_failures(@TempDir Path dir) {
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success", 11L),
                completed("lint", "neutral", 12L));
        Envelope env = useCase(git(ORIGIN_SSH, "abc123"), allowlist(GITHUB, "github.com"),
                forgeReturning(runs)).run(dir.toString(), "abc123", null, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.failures()).isEmpty();
        assertThat(env.prChecks()).hasSize(2);
    }

    @Test
    void an_incomplete_check_flips_ok_false_but_is_not_a_finding(@TempDir Path dir) {
        List<ForgeCheckRun> runs = List.of(
                completed("build", "success", 11L),
                new ForgeCheckRun("integration", "in_progress", null, 33L));
        Envelope env = useCase(git(ORIGIN_SSH, "abc123"), allowlist(GITHUB, "github.com"),
                forgeReturning(runs)).run(dir.toString(), "abc123", null, null);

        assertThat(env.ok()).as("a still-running check must NOT report green (G5)").isFalse();
        // Incomplete checks appear in prChecks[] but are not triage findings.
        assertThat(env.prChecks()).extracting(PrCheck::name).contains("integration");
        assertThat(env.failures()).as("an incomplete check is not a failure finding").isEmpty();
    }

    // ---- error mapping ----

    @Test
    void an_un_allowlisted_host_fails_clear(@TempDir Path dir) {
        // origin host is evil.example; the allowlist only knows github.com → no match.
        Envelope env = useCase(git("git@evil.example:x/y.git", "abc"),
                allowlist(GITHUB, "github.com"), forgeReturning(List.of()))
                .run(dir.toString(), "abc", null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_HOST_NOT_ALLOWLISTED);
    }

    @Test
    void a_rate_limit_maps_to_a_structured_error_with_the_retry_after_hint(@TempDir Path dir) {
        ForgeAccessException rateLimited = new ForgeAccessException(ErrorCode.FORGE_RATE_LIMITED,
                "Forge rate limit reached (HTTP 403); Retry-After=60.", "Wait then retry.", "60");
        Envelope env = useCase(git(ORIGIN_SSH, "abc"), allowlist(GITHUB, "github.com"),
                forgeThrowing(rateLimited)).run(dir.toString(), "abc", null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_RATE_LIMITED);
        assertThat(env.error().message()).contains("Retry-After=60");
    }

    @Test
    void a_private_repo_404_maps_to_resource_not_found(@TempDir Path dir) {
        ForgeAccessException notFound = new ForgeAccessException(ErrorCode.FORGE_RESOURCE_NOT_FOUND,
                "Forge returned HTTP 404 for the repository or ref.", "Provision a token.");
        Envelope env = useCase(git(ORIGIN_SSH, "abc"), allowlist(GITHUB, "github.com"),
                forgeThrowing(notFound)).run(dir.toString(), "abc", null, null);

        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_RESOURCE_NOT_FOUND);
    }

    @Test
    void no_origin_remote_fails_origin_unresolved(@TempDir Path dir) {
        Envelope env = useCase(git(null, "abc"), allowlist(GITHUB, "github.com"),
                forgeReturning(List.of())).run(dir.toString(), "abc", null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_ORIGIN_UNRESOLVED);
    }

    @Test
    void a_missing_path_fails_invalid_path_when_origin_is_needed() {
        Envelope env = useCase(git(ORIGIN_SSH, "abc"), allowlist(GITHUB, "github.com"),
                forgeReturning(List.of())).run(null, null, null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.INVALID_PATH);
    }

    // ---- repo override ----

    @Test
    void the_repo_override_is_honored_against_the_primary_instance() {
        ForgeCheckRequest[] captured = new ForgeCheckRequest[1];
        ForgePort capturing = new ForgePort() {
            @Override
            public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
                captured[0] = request;
                return List.of(completed("build", "success", 1L));
            }

            @Override
            public String fetchJobLog(ForgeLogRequest request) {
                throw new AssertionError();
            }
        };

        // No path, no origin needed: repo + ref are supplied explicitly.
        Envelope env = useCase(git(null, null), allowlist(GITHUB, "github.com"), capturing)
                .run(null, "feature-ref", "upstream-org/upstream-repo", null);

        assertThat(env.ok()).isTrue();
        assertThat(captured[0].owner()).isEqualTo("upstream-org");
        assertThat(captured[0].repo()).isEqualTo("upstream-repo");
        assertThat(captured[0].ref()).isEqualTo("feature-ref");
    }

    @Test
    void a_repo_override_with_no_allowlisted_instance_fails_clear() {
        Envelope env = useCase(git(null, null), allowlist(null, "github.com"),
                forgeReturning(List.of())).run(null, "ref", "octo/hello", null);

        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_HOST_NOT_ALLOWLISTED);
    }

    // ---- secret discipline ----

    @Test
    void the_token_env_name_is_never_returned_in_the_envelope(@TempDir Path dir) throws Exception {
        List<ForgeCheckRun> runs = List.of(completed("build", "failure", 11L));
        Envelope env = useCase(git(ORIGIN_SSH, "abc"), allowlist(GITHUB, "github.com"),
                forgeReturning(runs)).run(dir.toString(), "abc", null, null);

        String json = ObjectMapper.getDefault().writeValueAsString(env);

        // The tokenEnv NAME (let alone any token value) must never leak into the envelope.
        assertThat(json).doesNotContain("MY_TOKEN_ENV");
    }
}
