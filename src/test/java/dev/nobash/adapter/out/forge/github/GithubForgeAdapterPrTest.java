package dev.nobash.adapter.out.forge.github;

import com.github.tomakehurst.wiremock.WireMockServer;
import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.forge.PrChecksSummary;
import dev.nobash.domain.forge.PrView;
import dev.nobash.domain.port.out.ForgePrRequest;
import io.micronaut.serde.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WireMock tests for {@link GithubForgeAdapter}'s {@code pr_view}/{@code pr_diff} methods (PRD-6 S2,
 * #100): the one-call metadata fold (state/mergeable/merged/head/base/review-status/checks-summary),
 * the {@code Accept: …diff} unified-diff fetch, and the SAME {@code FORGE_*} error surface as S1.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class GithubForgeAdapterPrTest {

    private static final String TOKEN = "secret-token-abc123";
    private static final String TOKEN_ENV = "GH_TOKEN";
    private static final String OWNER = "octo";
    private static final String REPO = "hello";
    private static final long PR = 42L;

    private static final UnaryOperator<String> TOKEN_RESOLVER =
            name -> TOKEN_ENV.equals(name) ? TOKEN : null;

    private WireMockServer forge;
    private GithubForgeAdapter adapter;

    @BeforeEach
    void start_server() {
        forge = new WireMockServer(options().dynamicPort());
        forge.start();
        adapter = new GithubForgeAdapter(ObjectMapper.getDefault(), TOKEN_RESOLVER);
    }

    @AfterEach
    void stop_server() {
        forge.stop();
    }

    private String forgeBase() {
        return "http://127.0.0.1:" + forge.port();
    }

    private ForgePrRequest prRequest() {
        return new ForgePrRequest(forgeBase(), null, TOKEN_ENV, OWNER, REPO, PR);
    }

    private String pullPath() {
        return "/repos/" + OWNER + "/" + REPO + "/pulls/" + PR;
    }

    // ---- pr_view ----

    @Test
    void folds_pr_metadata_reviews_and_a_checks_summary_into_one_call() {
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"open\",\"mergeable\":true,\"merged\":false,"
                                + "\"head\":{\"ref\":\"feature-x\",\"sha\":\"deadbeef\"},"
                                + "\"base\":{\"ref\":\"main\"}}")));
        forge.stubFor(get(urlEqualTo(pullPath() + "/reviews"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"state\":\"APPROVED\"}]")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/check-runs"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":1,\"check_runs\":["
                                + "{\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"failure\","
                                + "\"details_url\":\"https://x/actions/runs/1/job/11\"}]}")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"success\",\"statuses\":[]}")));

        PrView view = adapter.fetchPrView(prRequest());

        assertThat(view.state()).isEqualTo("open");
        assertThat(view.mergeable()).isTrue();
        assertThat(view.merged()).isFalse();
        assertThat(view.headRef()).isEqualTo("feature-x");
        assertThat(view.headSha()).isEqualTo("deadbeef");
        assertThat(view.baseRef()).isEqualTo("main");
        assertThat(view.reviewStatus()).isEqualTo("approved");
        assertThat(view.checksSummary()).isEqualTo(new PrChecksSummary(1, 1, false));
    }

    @Test
    void a_changes_requested_review_wins_the_fold() {
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"open\",\"mergeable\":null,\"merged\":false,"
                                + "\"head\":{\"ref\":\"feature-x\",\"sha\":\"deadbeef\"},"
                                + "\"base\":{\"ref\":\"main\"}}")));
        forge.stubFor(get(urlEqualTo(pullPath() + "/reviews"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[{\"state\":\"APPROVED\"},{\"state\":\"CHANGES_REQUESTED\"}]")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/check-runs"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"check_runs\":[]}")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"success\",\"statuses\":[]}")));

        PrView view = adapter.fetchPrView(prRequest());

        assertThat(view.mergeable()).as("null while GitHub computes it").isNull();
        assertThat(view.reviewStatus()).isEqualTo("changes_requested");
        assertThat(view.checksSummary()).isEqualTo(new PrChecksSummary(0, 0, true));
    }

    @Test
    void a_tokened_pr_view_call_sends_the_bearer_token() {
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"closed\",\"mergeable\":false,\"merged\":true,"
                                + "\"head\":{\"ref\":\"feature-x\",\"sha\":\"deadbeef\"},"
                                + "\"base\":{\"ref\":\"main\"}}")));
        forge.stubFor(get(urlEqualTo(pullPath() + "/reviews"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("[]")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/check-runs"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"total_count\":0,\"check_runs\":[]}")));
        forge.stubFor(get(urlEqualTo("/repos/" + OWNER + "/" + REPO + "/commits/deadbeef/status"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"state\":\"success\",\"statuses\":[]}")));

        PrView view = adapter.fetchPrView(prRequest());

        assertThat(view.merged()).isTrue();
        assertThat(view.reviewStatus()).isEqualTo("none");
        forge.verify(getRequestedFor(urlEqualTo(pullPath()))
                .withHeader("Authorization", equalTo("Bearer " + TOKEN)));
    }

    @Test
    void pr_view_maps_a_404_to_resource_not_found_the_same_as_pr_checks() {
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(404).withBody("{\"message\":\"Not Found\"}")));

        assertThatThrownBy(() -> adapter.fetchPrView(prRequest()))
                .isInstanceOfSatisfying(ForgeAccessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORGE_RESOURCE_NOT_FOUND));
    }

    // ---- pr_diff ----

    @Test
    void fetches_the_unified_diff_via_the_diff_accept_header() {
        String diffBody = "diff --git a/foo.txt b/foo.txt\n+hello\n";
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.v3.diff")
                        .withBody(diffBody)));

        String diff = adapter.fetchPrDiff(prRequest());

        assertThat(diff).isEqualTo(diffBody);
        forge.verify(getRequestedFor(urlEqualTo(pullPath()))
                .withHeader("Accept", equalTo("application/vnd.github.v3.diff")));
    }

    @Test
    void a_large_diff_is_returned_in_full_non_lossily() {
        StringBuilder large = new StringBuilder("diff --git a/big.txt b/big.txt\n");
        for (int i = 0; i < 20_000; i++) {
            large.append("+line ").append(i).append('\n');
        }
        String diffBody = large.toString();
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/vnd.github.v3.diff")
                        .withBody(diffBody)));

        String diff = adapter.fetchPrDiff(prRequest());

        assertThat(diff).hasSize(diffBody.length()).isEqualTo(diffBody);
    }

    @Test
    void pr_diff_rate_limit_surfaces_retry_after_and_never_auto_retries() {
        forge.stubFor(get(urlEqualTo(pullPath()))
                .willReturn(aResponse().withStatus(403)
                        .withHeader("Retry-After", "30")
                        .withHeader("X-RateLimit-Remaining", "0")
                        .withBody("{\"message\":\"API rate limit exceeded\"}")));

        assertThatThrownBy(() -> adapter.fetchPrDiff(prRequest()))
                .isInstanceOfSatisfying(ForgeAccessException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.FORGE_RATE_LIMITED);
                    assertThat(e.retryAfter()).isEqualTo("30");
                });
    }
}
