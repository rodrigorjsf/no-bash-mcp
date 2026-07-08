package dev.nobash.adapter.out.forge.github;

import dev.nobash.domain.error.ErrorCode;
import dev.nobash.domain.forge.ForgeAccessException;
import dev.nobash.domain.port.out.ForgeCheckRequest;
import dev.nobash.domain.port.out.ForgeCheckRun;
import dev.nobash.domain.port.out.ForgeLogRequest;
import dev.nobash.domain.port.out.ForgePort;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.json.tree.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * The outbound GitHub REST adapter satisfying {@link ForgePort} (PRD-6 S1, #99). Uses the raw JDK
 * {@code java.net.http.HttpClient} with {@code followRedirects(NEVER)} — a deliberate DESIGN §7
 * deviation (D69) chosen because the declarative {@code @Client} cannot express the manual,
 * no-{@code Authorization}-forward 302 control the security floor requires. No new runtime dependency
 * (the JDK client is built-in; §7 itself notes {@code @Client} ergonomics sit over this client).
 *
 * <p><b>Secret discipline (forge-security-model.md area 1).</b> The read-scoped token is resolved
 * by-reference from the instance's {@code tokenEnv} env-var NAME at request time; it is attached only
 * as {@code Authorization: Bearer <token>} on requests to the ALLOWLISTED forge host and is NEVER
 * logged, returned, or accepted from agent input. A tokenless instance issues unauthenticated GETs.</p>
 *
 * <p><b>302 token-leak floor.</b> The job-log GET 302s to a signed blob on a DIFFERENT host; this
 * adapter reads the {@code Location} manually and issues the second GET stripping {@code Authorization}
 * whenever the redirect target authority (scheme+host+port) differs from the original — the RFC-9110
 * cross-origin default.</p>
 *
 * <p>Pagination follows {@code Link rel="next"} to exhaustion; Commit Statuses are folded into the
 * same {@link ForgeCheckRun} list so the use-case's container-aware {@code ok()} sees both.</p>
 */
@Singleton
public class GithubForgeAdapter implements ForgePort {

    private static final String ACCEPT = "application/vnd.github+json";
    private static final String API_VERSION = "2022-11-28";
    private static final String USER_AGENT = "no-bash-mcp";

    private final JsonMapper jsonMapper;
    private final UnaryOperator<String> envResolver;
    private final HttpClient httpClient;

    @Inject
    public GithubForgeAdapter(JsonMapper jsonMapper) {
        this(jsonMapper, System::getenv);
    }

    /**
     * Package/test constructor injecting the env-var resolver so the token can be controlled without
     * mutating the JVM environment. The HTTP client is always the production client (redirects OFF).
     */
    public GithubForgeAdapter(JsonMapper jsonMapper, UnaryOperator<String> envResolver) {
        this.jsonMapper = jsonMapper;
        this.envResolver = envResolver;
        this.httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public List<ForgeCheckRun> fetchChecks(ForgeCheckRequest request) {
        String token = resolveToken(request.tokenEnv());
        String apiBase = apiBase(request.baseUrl(), request.apiPrefix());
        String repoPath = "/repos/" + request.owner() + "/" + request.repo();

        List<ForgeCheckRun> runs = new ArrayList<>();

        // Check-runs: paginate via Link rel="next" to exhaustion.
        URI url = URI.create(apiBase + repoPath + "/commits/" + request.ref() + "/check-runs");
        while (url != null) {
            HttpResponse<String> resp = get(url, token);
            requireOk(resp);
            parseCheckRuns(resp.body(), runs);
            url = nextLink(resp).orElse(null);
        }

        // Commit Statuses: folded into the same list (also Link-paginated defensively).
        URI statusUrl = URI.create(apiBase + repoPath + "/commits/" + request.ref() + "/status");
        while (statusUrl != null) {
            HttpResponse<String> resp = get(statusUrl, token);
            requireOk(resp);
            parseStatuses(resp.body(), runs);
            statusUrl = nextLink(resp).orElse(null);
        }

        return runs;
    }

    @Override
    public String fetchJobLog(ForgeLogRequest request) {
        String token = resolveToken(request.tokenEnv());
        String apiBase = apiBase(request.baseUrl(), request.apiPrefix());
        URI uri = URI.create(apiBase + "/repos/" + request.owner() + "/" + request.repo()
                + "/actions/jobs/" + request.jobId() + "/logs");

        HttpResponse<String> resp = get(uri, token);
        int sc = resp.statusCode();
        if (isRedirect(sc)) {
            String location = resp.headers().firstValue("Location")
                    .orElseThrow(() -> new ForgeAccessException(ErrorCode.FORGE_REQUEST_FAILED,
                            "Forge job-log redirect carried no Location header.",
                            "Retry later or verify the job id."));
            URI target = uri.resolve(location);
            // Cross-origin token-leak floor: strip Authorization when the authority changes.
            String hopToken = sameAuthority(uri, target) ? token : null;
            HttpResponse<String> blob = get(target, hopToken);
            requireOk(blob);
            return blob.body();
        }
        requireOk(resp);
        return resp.body();
    }

    // ---- HTTP ----

    private HttpResponse<String> get(URI uri, @Nullable String token) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .GET()
                .header("Accept", ACCEPT)
                .header("X-GitHub-Api-Version", API_VERSION)
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(30));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ForgeAccessException(ErrorCode.FORGE_REQUEST_FAILED,
                    "Forge request to " + uri.getHost() + " failed: " + e.getMessage(),
                    "Retry later or verify the forge host is reachable.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ForgeAccessException(ErrorCode.FORGE_REQUEST_FAILED,
                    "Forge request was interrupted.",
                    "Retry the request.");
        }
    }

    /** Fail closed on any non-2xx, mapping to the specific FORGE_* code (D65/D66/D68). */
    private void requireOk(HttpResponse<String> resp) {
        int sc = resp.statusCode();
        if (sc >= 200 && sc < 300) {
            return;
        }
        if (isRateLimited(resp)) {
            String retryAfter = resp.headers().firstValue("Retry-After")
                    .orElseGet(() -> resp.headers().firstValue("X-RateLimit-Reset").orElse(null));
            throw new ForgeAccessException(ErrorCode.FORGE_RATE_LIMITED,
                    "Forge rate limit reached (HTTP " + sc + ")"
                            + (retryAfter != null ? "; Retry-After=" + retryAfter : "") + ".",
                    "Wait for the Retry-After interval before retrying, or provision a token for a "
                            + "higher budget. The MCP never auto-retries a rate-limited call.",
                    retryAfter);
        }
        if (sc == 404 || sc == 401) {
            throw new ForgeAccessException(ErrorCode.FORGE_RESOURCE_NOT_FOUND,
                    "Forge returned HTTP " + sc + " for the repository or ref.",
                    "If the repository is private, provision a read-scoped token (tokenEnv) for the "
                            + "allowlisted instance.");
        }
        throw new ForgeAccessException(ErrorCode.FORGE_REQUEST_FAILED,
                "Forge request failed with HTTP " + sc + ".",
                "Retry later or verify the forge is reachable and the ref exists.");
    }

    private static boolean isRateLimited(HttpResponse<String> resp) {
        int sc = resp.statusCode();
        if (sc == 429) {
            return true;
        }
        if (sc == 403) {
            boolean remainingZero = "0".equals(
                    resp.headers().firstValue("X-RateLimit-Remaining").orElse(null));
            return remainingZero || resp.headers().firstValue("Retry-After").isPresent();
        }
        return false;
    }

    private static boolean isRedirect(int sc) {
        return sc == 301 || sc == 302 || sc == 303 || sc == 307 || sc == 308;
    }

    private static boolean sameAuthority(URI a, URI b) {
        return equalsIgnoreCase(a.getScheme(), b.getScheme())
                && equalsIgnoreCase(a.getHost(), b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static boolean equalsIgnoreCase(String a, String b) {
        return a == null ? b == null : a.equalsIgnoreCase(b);
    }

    private Optional<URI> nextLink(HttpResponse<String> resp) {
        return resp.headers().firstValue("Link").flatMap(GithubForgeAdapter::parseNextLink);
    }

    /** Extract the {@code rel="next"} URL from an RFC-8288 {@code Link} header, if present. */
    static Optional<URI> parseNextLink(String linkHeader) {
        for (String part : linkHeader.split(",")) {
            if (part.contains("rel=\"next\"")) {
                int lt = part.indexOf('<');
                int gt = part.indexOf('>', lt + 1);
                if (lt >= 0 && gt > lt) {
                    try {
                        return Optional.of(URI.create(part.substring(lt + 1, gt).trim()));
                    } catch (RuntimeException e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ---- token ----

    @Nullable
    private String resolveToken(@Nullable String tokenEnv) {
        if (tokenEnv == null || tokenEnv.isBlank()) {
            return null;
        }
        String value = envResolver.apply(tokenEnv);
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String apiBase(String baseUrl, @Nullable String apiPrefix) {
        String base = stripTrailingSlashes(baseUrl.strip());
        if (apiPrefix != null && !apiPrefix.isBlank()) {
            String prefix = apiPrefix.strip();
            if (!prefix.startsWith("/")) {
                prefix = "/" + prefix;
            }
            base = base + stripTrailingSlashes(prefix);
        }
        return base;
    }

    private static String stripTrailingSlashes(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    // ---- JSON ----

    private void parseCheckRuns(String body, List<ForgeCheckRun> out) {
        JsonNode root = readTree(body);
        JsonNode checkRuns = root == null ? null : root.get("check_runs");
        if (checkRuns == null || !checkRuns.isArray()) {
            return;
        }
        for (JsonNode cr : checkRuns.values()) {
            String name = str(cr, "name");
            String status = str(cr, "status");
            String conclusion = str(cr, "conclusion");
            Long jobId = parseJobId(str(cr, "details_url"));
            out.add(new ForgeCheckRun(name, status, conclusion, jobId));
        }
    }

    private void parseStatuses(String body, List<ForgeCheckRun> out) {
        JsonNode root = readTree(body);
        JsonNode statuses = root == null ? null : root.get("statuses");
        if (statuses == null || !statuses.isArray()) {
            return;
        }
        for (JsonNode st : statuses.values()) {
            String name = str(st, "context");
            String state = str(st, "state");
            out.add(mapStatus(name, state));
        }
    }

    /** Map a Commit Status state onto the unified check shape (jobId null — statuses have no log). */
    private static ForgeCheckRun mapStatus(String name, String state) {
        String s = state == null ? "" : state.toLowerCase();
        return switch (s) {
            case "success" -> new ForgeCheckRun(name, "completed", "success", null);
            case "pending" -> new ForgeCheckRun(name, "in_progress", null, null);
            case "failure" -> new ForgeCheckRun(name, "completed", "failure", null);
            case "error" -> new ForgeCheckRun(name, "completed", "error", null);
            // An unknown state is treated as a completed non-passing check (fails closed).
            default -> new ForgeCheckRun(name, "completed", state, null);
        };
    }

    @Nullable
    private JsonNode readTree(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return jsonMapper.readValue(body, JsonNode.class);
        } catch (IOException e) {
            throw new ForgeAccessException(ErrorCode.FORGE_REQUEST_FAILED,
                    "Forge returned a body that could not be parsed as JSON.",
                    "Retry later or verify the forge endpoint.");
        }
    }

    @Nullable
    private static String str(@Nullable JsonNode obj, String field) {
        if (obj == null) {
            return null;
        }
        JsonNode node = obj.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return node.coerceStringValue();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Parse the trailing job id from a check-run {@code details_url} (.../job/{jobId}). */
    @Nullable
    static Long parseJobId(@Nullable String detailsUrl) {
        if (detailsUrl == null) {
            return null;
        }
        int idx = detailsUrl.indexOf("/job/");
        if (idx < 0) {
            return null;
        }
        String tail = detailsUrl.substring(idx + "/job/".length());
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
