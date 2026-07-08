package dev.nobash.application.verb.forge;

import dev.nobash.application.forge.ForgeLogHandleRegistry;
import dev.nobash.application.runcache.RawOutputStash;
import dev.nobash.application.verb.getlog.GetLogUseCase;
import dev.nobash.domain.envelope.Envelope;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeInstance;
import dev.nobash.domain.port.out.CommandExecutorPort;
import dev.nobash.domain.port.out.ExecResult;
import dev.nobash.domain.port.out.ExecSpec;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeInstancePort;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.forge.PrView;
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
 * Unit tests for {@link PrDiffUseCase} (PRD-6 S2, #100): the handle-only envelope, the
 * {@code repo} override, the SAME {@code FORGE_*} error mapping as {@code pr_checks}/{@code pr_view},
 * and — the load-bearing assertion — that a LARGE diff routes through {@code handle} +
 * {@code get_log} NON-LOSSILY (byte-for-byte) via the SAME {@link RawOutputStash} instance
 * {@link GetLogUseCase} reads from. Inspector-CLI acceptance tests cannot prove this: each
 * invocation is a fresh JVM with an empty in-memory stash, so the round-trip must be proven here.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class PrDiffUseCaseTest {

    private static final ForgeInstance GITHUB =
            new ForgeInstance("https://api.github.com", null, "MY_TOKEN_ENV");
    private static final String ORIGIN_SSH = "git@github.com:octo-org/hello-world.git";

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

    private static ForgePort forgeReturning(String diff) {
        return new StubForgePort() {
            @Override
            public String fetchPrDiff(ForgePrRequest request) {
                return diff;
            }
        };
    }

    private static ForgePort forgeThrowing(ForgeAccessException ex) {
        return new StubForgePort() {
            @Override
            public String fetchPrDiff(ForgePrRequest request) {
                throw ex;
            }
        };
    }

    /** A ForgePort stub whose non-pr_diff methods explode — pr_diff must never touch them. */
    private static class StubForgePort implements ForgePort {
        @Override
        public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
            throw new AssertionError("not part of pr_diff");
        }

        @Override
        public String fetchJobLog(ForgeLogRequest request) {
            throw new AssertionError("not part of pr_diff");
        }

        @Override
        public PrView fetchPrView(ForgePrRequest request) {
            throw new AssertionError("not part of pr_diff");
        }

        @Override
        public String fetchPrDiff(ForgePrRequest request) {
            throw new AssertionError("not part of pr_diff");
        }
    }

    private static PrDiffUseCase useCase(CommandExecutorPort git, ForgeInstancePort allow,
                                         ForgePort forge, RawOutputStash stash) {
        return new PrDiffUseCase(git, allow, forge, stash);
    }

    // ---- happy path: handle-only envelope ----

    @Test
    void returns_a_handle_only_envelope(@TempDir Path dir) {
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeReturning("diff --git a/x b/x\n+y\n"), new RawOutputStash())
                .run(dir.toString(), "42", null, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.manager()).isNull();
        assertThat(env.untrusted()).isFalse();
        assertThat(env.handle()).isNotNull();
    }

    // ---- the load-bearing non-lossy round-trip ----

    @Test
    void a_large_diff_routes_through_handle_and_get_log_non_lossily() {
        StringBuilder large = new StringBuilder("diff --git a/big.txt b/big.txt\n");
        for (int i = 0; i < 20_000; i++) {
            large.append("+line ").append(i).append('\n');
        }
        String fullDiff = large.toString();

        RawOutputStash stash = new RawOutputStash();
        Envelope env = useCase(git(null), allowlist(GITHUB, "github.com"),
                forgeReturning(fullDiff), stash)
                .run(null, "42", "octo/hello", null);

        assertThat(env.ok()).isTrue();
        String handleId = env.handle().id();

        GetLogUseCase getLog = new GetLogUseCase(stash, new ForgeLogHandleRegistry(),
                new StubForgePort() {
                });
        String retrieved = getLog.get(handleId, null);

        assertThat(retrieved).as("get_log must return the FULL diff, byte-for-byte")
                .hasSize(fullDiff.length())
                .isEqualTo(fullDiff);
    }

    // ---- pr validation ----

    @Test
    void a_missing_pr_fails_clear(@TempDir Path dir) {
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeReturning("diff"), new RawOutputStash())
                .run(dir.toString(), null, null, null);

        assertThat(env.ok()).isFalse();
        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_ORIGIN_UNRESOLVED);
    }

    // ---- error mapping (SAME FORGE_* surface) ----

    @Test
    void a_private_repo_404_maps_to_resource_not_found(@TempDir Path dir) {
        ForgeAccessException notFound = new ForgeAccessException(ErrorCode.FORGE_RESOURCE_NOT_FOUND,
                "Forge returned HTTP 404 for the repository or ref.", "Provision a token.");
        Envelope env = useCase(git(ORIGIN_SSH), allowlist(GITHUB, "github.com"),
                forgeThrowing(notFound), new RawOutputStash())
                .run(dir.toString(), "42", null, null);

        assertThat(env.error().code()).isEqualTo(ErrorCode.FORGE_RESOURCE_NOT_FOUND);
    }

    // ---- repo override ----

    @Test
    void the_repo_override_is_honored_against_the_primary_instance() {
        ForgePrRequest[] captured = new ForgePrRequest[1];
        ForgePort capturing = new StubForgePort() {
            @Override
            public String fetchPrDiff(ForgePrRequest request) {
                captured[0] = request;
                return "diff";
            }
        };

        Envelope env = useCase(git(null), allowlist(GITHUB, "github.com"), capturing, new RawOutputStash())
                .run(null, "9", "upstream-org/upstream-repo", null);

        assertThat(env.ok()).isTrue();
        assertThat(captured[0].owner()).isEqualTo("upstream-org");
        assertThat(captured[0].repo()).isEqualTo("upstream-repo");
        assertThat(captured[0].pr()).isEqualTo(9L);
    }
}
