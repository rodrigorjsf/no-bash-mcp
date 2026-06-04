import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spike s3 — forge read-only seams, on the PRODUCTION transport family.
 *
 * Run (token passed BY REFERENCE via env, never inline, never a file):
 *   GITHUB_TOKEN=$(gh auth token) java SpikeForge.java
 *
 * Uses ONLY java.net.http.HttpClient (the JDK client — same family as the chosen
 * production micronaut-http-client-jdk; DESIGN.md §7). No third-party deps: a tiny
 * JSON reader is inlined because JSON parsing is NOT the seam under test (production
 * uses serde). What IS under test: secret-by-reference, URL construction (SaaS vs the
 * GHES /api/v3 prefix), redirect-following WITHOUT leaking the token to the signed
 * blob store, and folding CI checks into the common ContainerFinding(RUN) envelope.
 *
 * Target (real, public): cli/cli — PR #13412; commit 2503dcfd has a real failed check
 * (govulncheck) whose Actions job log is retrievable. One coherent PR family.
 */
public class SpikeForge {

    // ---- the configurable, non-agent-mutable instance allowlist (ADR-0004) ----
    record ForgeInstance(String name, String baseUrl, String apiPrefix, String tokenEnvVar) {}

    // SaaS GitHub: api.github.com, NO path prefix.
    static final ForgeInstance GITHUB_SAAS =
            new ForgeInstance("github.com", "https://api.github.com", "", "GITHUB_TOKEN");
    // GHES (self-hosted): host swap is NOT enough — REST lives under /api/v3.
    static final ForgeInstance GHES_EXAMPLE =
            new ForgeInstance("ghe.example.com", "https://ghe.example.com", "/api/v3", "GHE_TOKEN");

    static final String OWNER = "cli", REPO = "cli";
    static final int PR = 13412;
    static final String REF = "2503dcfd79e372bfade8f41d05a2fbae57da1e26";

    static int failures = 0;
    static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)   // we follow 302 MANUALLY (token-leak control)
            .connectTimeout(Duration.ofSeconds(15)).build();

    public static void main(String[] args) throws Exception {
        // ---- secret-by-reference: resolve from env, never inline / never print ----
        String token = System.getenv(GITHUB_SAAS.tokenEnvVar());
        check("token resolved BY REFERENCE from $" + GITHUB_SAAS.tokenEnvVar() + " (never inline)",
                token != null && !token.isBlank());
        System.out.println("  token present, length=" + (token == null ? 0 : token.length())
                + " (value never printed/logged/returned)");

        // ===========================================================================
        section("URL construction seam — SaaS vs GHES (the self-hosted de-risk, ON PAPER)");
        // GHES is NOT a bare host swap: REST gets an /api/v3 prefix; GraphQL differs too.
        String saasRest = restUrl(GITHUB_SAAS, "/repos/" + OWNER + "/" + REPO + "/pulls/" + PR);
        String ghesRest = restUrl(GHES_EXAMPLE, "/repos/" + OWNER + "/" + REPO + "/pulls/" + PR);
        System.out.println("  SaaS REST : " + saasRest);
        System.out.println("  GHES REST : " + ghesRest);
        System.out.println("  SaaS GQL  : " + graphqlUrl(GITHUB_SAAS));
        System.out.println("  GHES GQL  : " + graphqlUrl(GHES_EXAMPLE));
        check("GHES REST seam builds the /api/v3 prefix (not just a host swap)",
                ghesRest.equals("https://ghe.example.com/api/v3/repos/cli/cli/pulls/13412"));
        check("SaaS REST has NO /api/v3 prefix", saasRest.equals("https://api.github.com/repos/cli/cli/pulls/13412"));
        check("GHES GraphQL is /api/graphql (SaaS is /graphql under api.github.com)",
                graphqlUrl(GHES_EXAMPLE).equals("https://ghe.example.com/api/graphql"));

        // ===========================================================================
        section("pr_view (#" + PR + ") — GET, JSON, normalize");
        var prJson = (Map<?, ?>) Json.parse(get(GITHUB_SAAS, token,
                "/repos/" + OWNER + "/" + REPO + "/pulls/" + PR, "application/vnd.github+json").body());
        String state = (String) prJson.get("state");
        boolean merged = Boolean.TRUE.equals(prJson.get("merged"));
        System.out.printf("  PR #%d  state=%s merged=%s  title=%s  +%s/-%s  files=%s%n",
                PR, state, merged, prJson.get("title"), num(prJson, "additions"),
                num(prJson, "deletions"), num(prJson, "changed_files"));
        check("pr_view: PR is closed+merged (real data folded)", "closed".equals(state) && merged);

        // ===========================================================================
        section("pr_diff (#" + PR + ") — same endpoint, Accept: diff media type");
        String diff = get(GITHUB_SAAS, token, "/repos/" + OWNER + "/" + REPO + "/pulls/" + PR,
                "application/vnd.github.diff").body();
        long diffFiles = diff.lines().filter(l -> l.startsWith("diff --git ")).count();
        System.out.println("  diff bytes=" + diff.length() + "  files touched (diff --git)=" + diffFiles);
        System.out.println("  first diff line: " + diff.lines().findFirst().orElse(""));
        check("pr_diff: Accept media type yields a unified diff (not JSON)", diff.startsWith("diff --git"));

        // ===========================================================================
        section("pr_checks (ref " + REF.substring(0, 8) + ") — fold CI checks into the COMMON envelope");
        var checksJson = (Map<?, ?>) Json.parse(get(GITHUB_SAAS, token,
                "/repos/" + OWNER + "/" + REPO + "/commits/" + REF + "/check-runs",
                "application/vnd.github+json").body());
        List<?> runs = (List<?>) checksJson.get("check_runs");
        // The forge-side envelope: a non-success check has no single test owner -> ContainerFinding(RUN).
        List<Map<String, Object>> findings = new ArrayList<>();
        String failedJobHandle = null;
        for (Object o : runs) {
            Map<?, ?> cr = (Map<?, ?>) o;
            String name = (String) cr.get("name");
            String conclusion = String.valueOf(cr.get("conclusion"));
            if (!"success".equals(conclusion) && !"neutral".equals(conclusion) && !"skipped".equals(conclusion)) {
                findings.add(containerRun(name, conclusion, (String) cr.get("details_url")));
                if (failedJobHandle == null) failedJobHandle = jobIdFromDetailsUrl((String) cr.get("details_url"));
            }
        }
        boolean ok = findings.isEmpty();  // container-aware: a failed CHECK (no test owner) fails the run
        System.out.printf("  envelope: { ok=%s, verb=pr_checks, manager=null, total_checks=%d, failures=%d }%n",
                ok, runs.size(), findings.size());
        for (var f : findings) System.out.printf("    CONT [RUN] %s  rawStatus=%s  handle=%s%n",
                f.get("container"), f.get("rawStatus"), f.get("handle"));
        check("pr_checks: ok() is FALSE because a CI check failed (ContainerFinding(RUN), manager=null)", !ok);
        check("pr_checks: exactly 1 failing check folded (govulncheck)",
                findings.size() == 1 && "govulncheck".equals(findings.get(0).get("container")));
        check("pr_checks: a drill-down handle (job id) was resolved from the check", failedJobHandle != null);

        // ===========================================================================
        section("get_log(handle=" + failedJobHandle + ") — drill-down WITHOUT re-running; 302 sans token-leak");
        String logSlice = getJobLog(GITHUB_SAAS, token, failedJobHandle);
        long logLines = logSlice.lines().count();
        System.out.println("  job-log bytes=" + logSlice.length() + " lines=" + logLines);
        String firstErr = logSlice.lines().filter(l -> l.toLowerCase().contains("vuln")
                || l.contains("Error") || l.contains("FAIL")).findFirst().orElse(logSlice.lines().findFirst().orElse(""));
        System.out.println("  log signal line: " + trim(firstErr, 140));
        check("get_log: failed-job log retrieved via handle (non-empty slice)", logSlice.length() > 0);

        System.out.println();
        if (failures == 0) System.out.println("ALL HARD ASSERTIONS PASSED");
        else { System.out.println(failures + " HARD ASSERTION(S) FAILED"); System.exit(1); }
    }

    // ---- URL seam (the self-hosted de-risk) ----
    static String restUrl(ForgeInstance fi, String path) { return fi.baseUrl() + fi.apiPrefix() + path; }
    static String graphqlUrl(ForgeInstance fi) {
        // SaaS: https://api.github.com/graphql ; GHES: https://<host>/api/graphql
        return fi.apiPrefix().isEmpty() ? fi.baseUrl() + "/graphql" : fi.baseUrl() + "/api/graphql";
    }

    static HttpResponse<String> get(ForgeInstance fi, String token, String path, String accept) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(restUrl(fi, path)))
                .header("Authorization", "Bearer " + token)          // token sent ONLY to the forge host
                .header("Accept", accept)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "no-bash-mcp-spike")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + res.statusCode() + " for " + path);
        return res;
    }

    /** /actions/jobs/{id}/logs returns 302 to a signed blob URL. Follow it MANUALLY and
     *  do NOT forward the Authorization header — the signed URL is pre-authenticated and
     *  leaking the token to the blob store is both wrong and rejected. */
    static String getJobLog(ForgeInstance fi, String token, String jobId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(
                        URI.create(restUrl(fi, "/repos/" + OWNER + "/" + REPO + "/actions/jobs/" + jobId + "/logs")))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "no-bash-mcp-spike")
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> res = CLIENT.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() == 302) {
            String location = res.headers().firstValue("location").orElseThrow();
            boolean sameHost = URI.create(location).getHost().equals(URI.create(fi.baseUrl()).getHost());
            check("get_log: 302 redirects to a DIFFERENT (signed blob) host", !sameHost);
            HttpRequest blob = HttpRequest.newBuilder(URI.create(location))   // NO Authorization header
                    .header("User-Agent", "no-bash-mcp-spike")
                    .timeout(Duration.ofSeconds(20)).GET().build();
            HttpResponse<String> blobRes = CLIENT.send(blob, HttpResponse.BodyHandlers.ofString());
            check("get_log: signed-blob fetch succeeds WITHOUT forwarding the token", blobRes.statusCode() == 200);
            assertNoAuthHeader(blob.headers());
            return blobRes.body();
        }
        return res.body();
    }

    static void assertNoAuthHeader(HttpHeaders h) {
        check("get_log: NO Authorization header on the signed-blob request (no token leak)",
                h.firstValue("authorization").isEmpty());
    }

    static Map<String, Object> containerRun(String name, String conclusion, String detailsUrl) {
        var m = new LinkedHashMap<String, Object>();
        m.put("scope", "RUN");
        m.put("container", name);
        m.put("outcome", "FAILED");
        m.put("rawStatus", conclusion);          // raw forge conclusion retained (axis 2)
        m.put("handle", jobIdFromDetailsUrl(detailsUrl));
        return m;
    }

    /** details_url: https://github.com/cli/cli/actions/runs/{runId}/job/{jobId} -> jobId.
     *  (For Actions check-runs the check-run id == the job id, but parsing the URL is the
     *  general handle resolution.) */
    static String jobIdFromDetailsUrl(String url) {
        if (url == null) return null;
        int i = url.indexOf("/job/");
        return i < 0 ? null : url.substring(i + 5).replaceAll("[^0-9].*$", "");
    }

    // ---- helpers ----
    static void section(String t) { System.out.println("\n=== " + t + " ==="); }
    static void check(String label, boolean cond) {
        System.out.println((cond ? "  [PASS] " : "  [FAIL] ") + label);
        if (!cond) failures++;
    }
    static Object num(Map<?, ?> m, String k) { return m.get(k); }
    static String trim(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "…"; }

    // =========================================================================
    /** Minimal, correct JSON reader (objects/arrays/strings/numbers/bool/null).
     *  Inlined so the spike has ZERO third-party deps — JSON is not the seam under test. */
    static final class Json {
        private final String s; private int i;
        private Json(String s) { this.s = s; }
        static Object parse(String s) { Json j = new Json(s); j.ws(); Object v = j.value(); return v; }
        private Object value() {
            ws();
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't', 'f' -> bool();
                case 'n' -> nul();
                default -> number();
            };
        }
        private Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>(); i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = string(); ws(); i++; /* : */ Object v = value(); m.put(k, v); ws();
                char c = s.charAt(i++); if (c == '}') return m; /* else ',' */
            }
        }
        private List<Object> array() {
            List<Object> a = new ArrayList<>(); i++; ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.add(value()); ws(); char c = s.charAt(i++); if (c == ']') return a; /* else ',' */
            }
        }
        private String string() {
            StringBuilder b = new StringBuilder(); i++; /* opening " */
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"' -> b.append('"'); case '\\' -> b.append('\\'); case '/' -> b.append('/');
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            return n.contains(".") || n.contains("e") || n.contains("E") ? Double.parseDouble(n) : Long.parseLong(n);
        }
        private Boolean bool() {
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; } i += 5; return Boolean.FALSE;
        }
        private Object nul() { i += 4; return null; }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    }
}
