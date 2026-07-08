package dev.nobash.adapter.out.forge.github;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.ForgeCheckClassifier;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeLogRequest;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock tests for {@link GithubForgeAdapter} (PRD-6 S1, #99): pagination to exhaustion, the
 * Commit-Status fold, the four-condition 302 token-leak control, rate-limit + 404 fail-clear, and the
 * token header discipline. Drives the REAL production JDK {@code HttpClient} against deterministic,
 * offline local HTTP servers.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GithubForgeAdapterTest {

    private static final String TOKEN = "secret-token-abc123";
    private static final String TOKEN_ENV = "GH_TOKEN";
    private static final String OWNER = "octo";
    private static final String REPO = "hello";
    private static final String REF = "deadbeef";

    /** The env resolver maps the configured env-var NAME to the secret — no real env mutation. */
    private static final UnaryOperator<String> TOKEN_RESOLVER =
            name -> TOKEN_ENV.equals(name) ? TOKEN : null;

    private WireMockServer forge;
    private WireMockServer blob;
    private GithubForgeAdapter adapter;

    @BeforeEach
    void start_servers() {
        forge = new WireMockServer(options().dynamicPort());
        forge.start();
        blob = new WireMockServer(options().dynamicPort());
        blob.start();
        adapter = new GithubForgeAdapter(ObjectMapper.getDefault(), TOKEN_RESOLVER);
    }

    @AfterEach
    void stop_servers() {
        forge.stop();
        blob.stop();
    }

    /** The forge base URL as the adapter sees it — host 127.0.0.1 (distinct from the blob's localhost). */
    private String forgeBase() {
        return "http://127.0.0.1:" + forge.port();
    }

    private ForgeCheckRequest checkRequest(String tokenEnv) {
        return new ForgeCheckRequest(forgeBase(), null, tokenEnv, OWNER, REPO, REF);
    }

    private String checkRunsPath() {
        return "/repos/" + OWNER + "/" + REPO + "/commits/" + REF + "/check-runs";
    }

    private String statusPath() {
        return "/repos/" + OWNER + "/" + REPO + "/commits/" + REF + "/status";
    }

    private void stubEmptyStatuses() {
        forge.stubFor(get(urlEqualTo(statusPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"success\",\"statuses\":[]}")));
    }

    // ---- pagination ----

    @Test
    void paginates_check_runs_via_link_next_to_exhaustion() {
        String page2Url = forgeBase() + checkRunsPath() + "?page=2";
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Link", "<" + page2Url + ">; rel=\"next\"")
                        .withBody("{\"total_count\":2,\"check_runs\":["
                                + "{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"success\","
                                + "\"details_url\":\"https://x/actions/runs/1/job/11\"}]}")));
        forge.stubFor(get(urlEqualTo(checkRunsPath() + "?page=2"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":2,\"check_runs\":["
                                + "{\"name\":\"e2e\",\"status\":\"completed\",\"conclusion\":\"failure\","
                                + "\"details_url\":\"https://x/actions/runs/1/job/22\"}]}")));
        stubEmptyStatuses();

        List<ForgeCheckRun> runs = adapter.fetchChecks(checkRequest(TOKEN_ENV));

        assertThat(runs).extracting(ForgeCheckRun::name).containsExactly("build", "e2e");
        // The failing check on page 2 flips the container-aware verdict.
        assertThat(ForgeCheckClassifier.ok(runs)).isFalse();
        // The failing check-run's job id is parsed from details_url for the log handle.
        ForgeCheckRun e2e = runs.get(1);
        assertThat(e2e.jobId()).isEqualTo(22L);
    }

    // ---- commit status fold ----

    @Test
    void folds_commit_statuses_into_the_check_list() {
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":1,\"check_runs\":["
                                + "{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"success\","
                                + "\"details_url\":\"https://x/actions/runs/1/job/11\"}]}")));
        forge.stubFor(get(urlEqualTo(statusPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"failure\",\"statuses\":["
                                + "{\"context\":\"ci/legacy\",\"state\":\"failure\","
                                + "\"target_url\":\"https://x/build/9\"}]}")));

        List<ForgeCheckRun> runs = adapter.fetchChecks(checkRequest(TOKEN_ENV));

        assertThat(runs).extracting(ForgeCheckRun::name).containsExactly("build", "ci/legacy");
        // A red Commit Status flips ok=false even though the check-run passed.
        assertThat(ForgeCheckClassifier.ok(runs)).isFalse();
        // A commit status has no job id (no retrievable job log).
        assertThat(runs.get(1).jobId()).isNull();
    }

    // ---- the four-condition 302 token-leak control ----

    @Test
    void job_log_302_flow_strips_authorization_on_the_cross_host_hop() {
        // (1) production HTTP client (the adapter's own client), (2) a 302 to a DIFFERENT host
        // (forge=127.0.0.1 → blob=localhost), (3) token PRESENT, (4) assert no Authorization on the
        // cross-host hop (the blob server records the second request's headers).
        String jobLogPath = "/repos/" + OWNER + "/" + REPO + "/actions/jobs/99/logs";
        String blobUrl = "http://localhost:" + blob.port() + "/signed-blob/xyz";

        forge.stubFor(get(urlEqualTo(jobLogPath))
                .willReturn(aResponse().withStatus(302).withHeader("Location", blobUrl)));
        blob.stubFor(get(urlEqualTo("/signed-blob/xyz"))
                .willReturn(aResponse().withStatus(200).withBody("JOB LOG CONTENT")));

        ForgeLogRequest request =
                new ForgeLogRequest(forgeBase(), null, TOKEN_ENV, OWNER, REPO, 99L);

        String log = adapter.fetchJobLog(request);

        assertThat(log).isEqualTo("JOB LOG CONTENT");
        // The first hop (forge host, token PRESENT) carries the Bearer token.
        forge.verify(getRequestedFor(urlEqualTo(jobLogPath))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
        // The cross-host hop (blob host) carries NO Authorization — the token was NOT forwarded.
        blob.verify(getRequestedFor(urlEqualTo("/signed-blob/xyz"))
                .withHeader("Authorization", absent()));
    }

    // ---- rate limit ----

    @Test
    void rate_limit_surfaces_retry_after_and_never_auto_retries() {
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Retry-After", "60")
                        .withHeader("X-RateLimit-Remaining", "0")
                        .withBody("{\"message\":\"API rate limit exceeded\"}")));

        assertThatThrownBy(() -> adapter.fetchChecks(checkRequest(TOKEN_ENV)))
                .isInstanceOfSatisfying(ForgeAccessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FORGE_RATE_LIMITED);
                    assertThat(e.retryAfter()).isEqualTo("60");
                });
        // Never auto-retried: exactly one request was issued.
        forge.verify(1, getRequestedFor(urlPathEqualTo(checkRunsPath())));
    }

    // ---- private repo 404 without token ----

    @Test
    void private_repo_404_without_token_fails_clear_not_an_empty_set() {
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(404)
                        .withBody("{\"message\":\"Not Found\"}")));

        assertThatThrownBy(() -> adapter.fetchChecks(checkRequest(null)))
                .isInstanceOfSatisfying(ForgeAccessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORGE_RESOURCE_NOT_FOUND));
    }

    // ---- token header discipline ----

    @Test
    void a_tokened_instance_sends_the_bearer_token_to_the_forge_host() {
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"check_runs\":[]}")));
        stubEmptyStatuses();

        adapter.fetchChecks(checkRequest(TOKEN_ENV));

        forge.verify(getRequestedFor(urlEqualTo(checkRunsPath()))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void a_tokenless_instance_issues_unauthenticated_gets() {
        forge.stubFor(get(urlEqualTo(checkRunsPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"check_runs\":[]}")));
        stubEmptyStatuses();

        adapter.fetchChecks(checkRequest(null));

        forge.verify(getRequestedFor(urlEqualTo(checkRunsPath()))
                .withHeader("Authorization", absent()));
    }
}
