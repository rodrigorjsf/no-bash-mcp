package dev.nobash.application.verb.forge;

import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.forge.PrChecksSummary;
import dev.nobash.domain.forge.PrView;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.port.out.ForgePort;
import dev.nobash.domain.port.out.ForgePrRequest;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PrViewUseCase} (PRD-6 S2, #100): the one-call metadata envelope, the
 * {@code repo} override, and the SAME {@code FORGE_*} error mapping as {@code pr_checks}. All ports
 * are stubbed — no HTTP, no real git.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PrViewUseCaseTest {

    private static final ForgeInstance GITHUB =
            new ForgeInstance("https://api.github.com", null, "MY_TOKEN_ENV");
    private static final String ORIGIN_SSH = "git@github.com:octo-org/hello-world.git";
    private static final PrView SAMPLE_VIEW = new PrView(
            "open", true, false, "feature-x", "deadbeef", "main",
            "approved", new PrChecksSummary(2, 0, true));

    private static CommandExecutorPort git(String originUrl) {
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

    private static ForgePort forgeReturning(PrView view) {
        return new StubForgePort() {
            @Override
            public PrView fetchPrView(ForgePrRequest request) {
                return view;
            }
        };
    }

    private static ForgePort forgeThrowing(ForgeAccessException ex) {
        return new StubForgePort() {
            @Override
            public PrView fetchPrView(ForgePrRequest request) {
                throw ex;
            }
        };
    }

    /** A ForgePort stub whose non-pr_view methods explode — pr_view must never touch them. */
    private abstract static class StubForgePort implements ForgePort {
        @Override
        public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
            throw new AssertionError("not part of pr_view");
        }

        @Override
        public String fetchJobLog(ForgeLogRequest request) {
            throw new AssertionError("not part of pr_view");
        }

        @Override
        public String fetchPrDiff(ForgePrRequest request) {
            throw new AssertionError("not part of pr_view");
        }
    }

    private static PrViewUseCase useCase(CommandExecutorPort git, ForgeInstancePort allow, ForgePort forge) {
        return new PrViewUseCase(git, allow, forge);
    }

    // ---- happy path ----

    @Test
    void returns_the_one_call_pr_view_envelope(@TempDir Path dir) {
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeReturning(SAMPLE_VIEW)).run(dir.toString(), "42", null, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.manager()).isNull();
        assertThat(env.untrusted()).isTrue();
        assertThat(env.prView().state()).isEqualTo("open");
        assertThat(env.prView().mergeable()).isTrue();
        assertThat(env.prView().reviewStatus()).isEqualTo("approved");
        assertThat(env.prView().checksSummary()).isEqualTo(new PrChecksSummary(2, 0, true));
    }

    // ---- pr validation ----

    @Test
    void a_missing_pr_fails_clear(@TempDir Path dir) {
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeReturning(SAMPLE_VIEW)).run(dir.toString(), null, null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_ORIGIN_UNRESOLVED);
    }

    @Test
    void a_non_numeric_pr_fails_clear(@TempDir Path dir) {
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeReturning(SAMPLE_VIEW)).run(dir.toString(), "not-a-number", null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_ORIGIN_UNRESOLVED);
    }

    // ---- error mapping (SAME FORGE_* surface as pr_checks) ----

    @Test
    void an_un_allowlisted_host_fails_clear(@TempDir Path dir) {
        Envelope env = useCase(git("git@evil.example:x/y.git"), allowlist(GITHUB, "github.com"),
                forgeReturning(SAMPLE_VIEW)).run(dir.toString(), "1", null, null);

        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_HOST_NOT_ALLOWLISTED);
    }

    @Test
    void a_rate_limit_maps_to_a_structured_error(@TempDir Path dir) {
        ForgeAccessException rateLimited = new ForgeAccessException(ErrorCode.FORGE_RATE_LIMITED,
                "Forge rate limit reached (HTTP 403); Retry-After=60.", "Wait then retry.", "60");
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeThrowing(rateLimited)).run(dir.toString(), "1", null, null);

        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_RATE_LIMITED);
    }

    // ---- repo override ----

    @Test
    void the_repo_override_is_honored_against_the_primary_instance() {
        ForgePrRequest[] captured = new ForgePrRequest[1];
        ForgePort capturing = new StubForgePort() {
            @Override
            public PrView fetchPrView(ForgePrRequest request) {
                captured[0] = request;
                return SAMPLE_VIEW;
            }
        };

        Envelope env = useCase(git(null), allowlist(GITHUB, "github.com"), capturing)
                .run(null, "7", "upstream-org/upstream-repo", null);

        assertThat(env.ok()).isTrue();
        assertThat(captured[0].owner()).isEqualTo("upstream-org");
        assertThat(captured[0].repo()).isEqualTo("upstream-repo");
        assertThat(captured[0].pr()).isEqualTo(7L);
    }
}
