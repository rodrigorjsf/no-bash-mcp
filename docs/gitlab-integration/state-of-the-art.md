# State of the Art — Recommended Architecture for a Go-Native GitLab Integration Tool

The synthesis and recommendation document for the **future Go tool** (`cursor-bastion`'s GitLab surface). It distills the verified research in this corpus (`glab.md`, `gitlab-api.md`, `gitlab-mcp.md`, `comparison.md`) into one opinionated, forward-looking architecture: which surfaces to wrap, how to wrap them in idiomatic Go, and how to expose them to LLM agents without burning the context window.

> **Audience priority.** **Self-hosted GitLab (Self-Managed and Dedicated) is the primary deployment target throughout.** `gitlab.com` is the secondary case. Every design decision below assumes a configurable base URL, a private CA, an admin who can tune rate limits, and a tier that may be Free/CE.

> **Accuracy note.** Claims marked *confirmed* in the verify bundles are stated plainly. Claims that were *corrected* or are *unverified/flagged* are hedged inline. Version numbers, tier gating, and feature-maturity labels move fast. Treat anything tagged "as of 2026-05-29; reconfirm against your instance/version" as a snapshot, not a contract. **Researched 2026-05-29; version-specific details should be reconfirmed.**

---

## 1. Design Goals

Four goals, in priority order. They are the lens for every later decision.

1. **`gh`-parity ergonomics.** `gh` (GitHub CLI) is the gold standard. The bar to clear: automatic TTY detection (rich human output on a terminal, tab-delimited machine output when piped, *without a flag*); a `--json field,field` + `--jq expr` + `--template` triad with a built-in jq engine (no external `jq` binary); a universal `api` escape hatch that handles both REST and GraphQL with `--paginate`, typed field injection, and response caching; a stable exit-code contract (`0` success, `1` general, `2` cancel, `4` auth-required); and per-host config that survives multiple instances. `glab` already mirrors much of `gh`'s *command surface* but has confirmed gaps versus `gh`: **no `--json field,field` sparse projection** (it returns the full object via `--output json`), **no `--template`**, and **no extension system** (open issue `gitlab-org/cli#1053`, unimplemented). Those gaps are exactly what a new tool should not inherit.

2. **Self-hosted first.** A configurable base URL is non-negotiable. REST lives at `https://<host>/api/v4/`, GraphQL at `https://<host>/api/graphql` — the `/api/v4` and `/api/graphql` prefixes are mandatory and non-configurable; only the host is variable. The tool must trust private CAs via a cert pool (never `InsecureSkipVerify`), honor proxy env vars, handle admin-tunable (often *disabled-by-default*) rate limits, and degrade gracefully on Free/CE where Premium/Ultimate features 403.

3. **Agent-native.** The tool is consumed by Cursor agents as much as by humans. That means deterministic, non-interactive invocation (no TTY prompt can ever hang the loop), structured JSON output, structured *errors*, and a small, predictable surface the model can reason about.

4. **Token-efficient.** Every byte returned to an agent costs context budget; every tool schema injected costs budget *before the first turn*. The architecture must minimize both — field projection over fat payloads, a tiny typed-command set plus one escape hatch over dozens of MCP tools.

---

## 2. The Five Surfaces — What Exists to Wrap

| Surface | Transport | Schema overhead | Field selection | Self-hosted Free/CE | Best for |
|---|---|---|---|---|---|
| **go-gitlab SDK** (`client-go/v2`) | In-process HTTP | None | Client-side only | ✅ any tier | Typed, programmatic REST access |
| **REST API v4** (raw HTTP) | HTTP | None | ❌ no sparse fieldsets | ✅ any tier | Universal coverage, write ops |
| **GraphQL** (`/api/graphql`) | HTTP POST | None | ✅ native | ✅ any tier | Token-minimal multi-field reads |
| **`glab` CLI** (`os/exec`) | Subprocess | None (fetched on call) | ❌ (`--output json` = full object) | ✅ any tier | Interactive/prototype only |
| **MCP servers** (official + community) | stdio / HTTP | **High** (every tool schema in context) | Varies | Official: ❌ Premium+Duo; community: ✅ | Exposing *to* agents |

**Key correction from research:** the canonical Go SDK is `gitlab.com/gitlab-org/api/client-go` — the maintained successor to the deprecated `github.com/xanzy/go-gitlab` (deprecated at v0.115.0, 2024-12-10). But the current major version is **v2** (`gitlab.com/gitlab-org/api/client-go/v2`, ~v2.36.0 as of 2026-05-28). The original research pinned v1.46.0; **use the `/v2` import path** for new code. (Corrected during verification; reconfirm the exact patch version against pkg.go.dev.)

---

## 3. Recommended Architecture — A Hybrid

No single surface wins on all four goals. The recommendation is a **layered hybrid**, with the SDK as the spine and a clear escape hatch for everything it does not cover.

```
┌─────────────────────────────────────────────────────────┐
│  cobra command layer  (typed commands + `api` escape)    │
├─────────────────────────────────────────────────────────┤
│  output layer  (JSON / jq / template / TTY-aware human)  │
├─────────────────────────────────────────────────────────┤
│  transport routing                                       │
│    ├── go-gitlab/v2 SDK   → typed REST (default path)    │
│    ├── raw GraphQL client → token-minimal reads          │
│    └── raw REST (http)    → endpoints SDK lacks          │
├─────────────────────────────────────────────────────────┤
│  client factory  (base URL, CA pool, proxy, auth, retry) │
└─────────────────────────────────────────────────────────┘
                  ▲                          ▲
        in-process (humans + scripts)   thin MCP facade (agents)
```

### 3.1 SDK vs. `os/exec` over `glab` vs. raw HTTP

**Decision: SDK-first, raw HTTP for gaps, never `os/exec` over `glab` in the product path.**

- **`go-gitlab/v2` SDK is the default.** It is typed, synchronous, ~2–5× faster than spawning a subprocess (no shell startup), ships gomock-based test doubles, and gives compile-time safety. `NewClient(token, ...opts)` plus `WithBaseURL` and `WithHTTPClient` cover the self-hosted essentials.

- **Raw HTTP (via the same `*http.Client`) for SDK gaps.** Some endpoints lag the SDK; keyset pagination is not universal; the SDK bundles **no GraphQL client**. Reuse the configured transport and auth for these.

- ❌ **Do not shell out to `glab`** in the shipped tool. It couples the binary to `glab`'s installation, version, and CLI-driven JSON schema (not a stable API contract). Valid only for throwaway prototypes or one-off scripts where `glab` is already deployed.

```go
// ❌ Don't — fragile: requires glab binary, schema not stable across versions
out, err := exec.Command("glab", "mr", "list", "--output", "json").Output()
var mrs []map[string]any
json.Unmarshal(out, &mrs) // schema is CLI-output-driven, not a contract
```

```go
// ✅ Do — typed, dependency-controlled, testable
mrs, _, err := client.MergeRequests.ListProjectMergeRequests(projectID, opts)
```

### 3.2 REST vs. GraphQL — per operation

GitLab REST v4 **does not support sparse fieldsets** — it always returns the full object (~80 fields for a project, 40+ for an issue). GraphQL returns *only* requested fields and can join sub-resources in one round-trip. This is the single biggest token lever.

| Operation class | Surface | Rationale |
|---|---|---|
| Single-field / scalar reads (e.g. pipeline status) | GraphQL | Fewest tokens; field projection |
| Multi-resource reads (MR + pipeline + threads) | GraphQL | One round-trip replaces 3+ REST calls |
| Large enumeration (all projects/issues) | REST + keyset | Stable cursor; SDK `Scan2` iterator |
| Writes / mutations (create issue, MR) | REST (SDK) | Broadest coverage; simplest auth; `read_api` ≠ enough — needs `api` |
| Label change history (`resource_label_events`) | REST | **GraphQL gap** — not exposed; confirmed |
| Repository file CRUD, wiki attachments | REST | GraphQL gaps |
| New Epic features (assignees, health status) | GraphQL Work Items | REST/`createEpic` deprecated; Work Items GA in 18.1 |

**GraphQL caveats to honor:**
- Complexity cap: **200 unauthenticated / 250 authenticated** (the research initially listed only 250; both confirmed). Query `queryComplexity { score limit }` alongside real data during development.
- 10,000-character query limit; 100 nodes per page; 30s timeout.
- **GraphQL rate-limit headers (`RateLimit-Remaining`, `Retry-After`) are not reliably returned** (open issue `#352409`) — implement exponential backoff on `429`, not header-based throttling.
- Global IDs (`gid://gitlab/Issue/27039960`) are opaque — never parse them.
- `workItemTypeId` for an Epic is **not stable across instances/namespaces** — query `WorkItemTypes` at runtime; do not hardcode `gid://gitlab/WorkItems::Type/0`. (Corrected during verification.)
- For GraphQL auth, the docs confirm `Authorization: Bearer`; whether `PRIVATE-TOKEN` works at `/api/graphql` is **unverified** — prefer Bearer (as of 2026-05-29; reconfirm against your instance).

### 3.3 Whether to expose an MCP facade for agents

**Decision: expose a *thin, optional* MCP facade — not a 1:1 mapping of the CLI.** See §6. The hard data: MCP schema overhead is paid on *every* completion turn before any task content. Benchmarks show CLI vs. MCP cost ratios of 4–32× for equivalent tasks (one task: 1,365 tokens CLI vs. 44,026 MCP), and tool-overload degrades accuracy (95% with a ~4-tool/~1,200-token set vs. 71% with a 46-tool/~42,000-token set). The official GitLab MCP server exposes **15 tools** (the research said 14; corrected) and requires Premium/Ultimate + Duo + OAuth DCR (no PAT — open issue `#586184`). Community servers range from 9 to 1,000+ tools.

---

## 4. Go Implementation Patterns

### 4.1 The client factory — base URL, CA, proxy, auth

This is the heart of self-hosted support. One composable factory handles the four cross-cutting concerns.

```go
// ✅ Do — private CA on top of the system pool, proxy-aware, no InsecureSkipVerify
import (
    "crypto/tls"
    "crypto/x509"
    "net/http"
    "os"

    gitlab "gitlab.com/gitlab-org/api/client-go/v2" // NOTE: /v2 import path
)

func newGitLabClient(baseURL, token, caCertPath string) (*gitlab.Client, error) {
    rootCAs, _ := x509.SystemCertPool()
    if rootCAs == nil { // SystemCertPool can return nil/err on some Windows Go builds
        rootCAs = x509.NewCertPool()
    }
    if caCertPath != "" {
        pem, err := os.ReadFile(caCertPath)
        if err != nil {
            return nil, err
        }
        rootCAs.AppendCertsFromPEM(pem) // augment, never replace, the system pool
    }
    httpClient := &http.Client{
        Transport: &http.Transport{
            Proxy: http.ProxyFromEnvironment, // honours HTTP_PROXY/HTTPS_PROXY/NO_PROXY
            TLSClientConfig: &tls.Config{
                RootCAs: rootCAs,
                // InsecureSkipVerify defaults to false — never set it true
            },
        },
    }
    return gitlab.NewClient(token,
        gitlab.WithBaseURL(baseURL), // root URL only; SDK appends /api/v4/ itself
        gitlab.WithHTTPClient(httpClient),
    )
}
```

```go
// ❌ Don't — disables all cert verification; trivial MITM; SAST flags this as critical
httpClient := &http.Client{Transport: &http.Transport{
    TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
}}
```

**Base-URL pitfall (confirmed):** pass the *root* URL to `WithBaseURL` (`https://gitlab.internal`). The SDK appends `/api/v4/`. Passing the full `/api/v4/` path doubles it (`/api/v4/api/v4/...`) — a common mistake when copying old xanzy docs.

**Proxy:** `http.ProxyFromEnvironment` reads `HTTP_PROXY`/`HTTPS_PROXY`/`NO_PROXY` (and lowercase). Loopback is never proxied. Lives in `net/http`, not the `x/net` implementation package.

### 4.2 cobra structure

- **Root persistent flags:** `--hostname`, `--output (json|ndjson|text)`, `--jq`, `--template`. Bind `--hostname` to viper with a default of `gitlab.com` (but expect override).
- **Resolution order for host** (mirror `glab`): explicit `--hostname` > `GITLAB_HOST`/`GL_HOST` env > `hosts.yml` entry > git-remote detection > `gitlab.com` fallback.
- **Token resolution order** (mirror `glab`/`gh`): `GITLAB_TOKEN` env > per-host config token > `CI_JOB_TOKEN` (only when CI-autologin is enabled). Treat `GITLAB_ACCESS_TOKEN`/`OAUTH_TOKEN` as low-confidence aliases — only `GITLAB_TOKEN` is documented in the current `glab` README. (Corrected during verification.)
- **Subcommand groups:** start with the high-value typed set (§6.2) plus `api`. Resist sprawl.

### 4.3 Auth & config

Mirror `gh`'s two-file layout, keyed by hostname so multiple self-hosted instances coexist:

```yaml
# ~/.config/<tool>/hosts.yml  (respect XDG_CONFIG_HOME; allow <TOOL>_CONFIG_DIR override)
hosts:
  gitlab.com:
    token: glpat-xxxx
    api_protocol: https
  gitlab.internal.corp:
    token: glpat-yyyy
    api_protocol: https
    ca_cert_path: /etc/ssl/certs/corp-ca.pem   # per-host private CA
```

```go
// ✅ Do — env var takes precedence (matches gh GH_TOKEN / glab GITLAB_TOKEN)
func TokenForHost(cfg *Config, hostname string) string {
    if t := os.Getenv("GITLAB_TOKEN"); t != "" {
        return t
    }
    if h, ok := cfg.Hosts[hostname]; ok {
        return h.Token
    }
    return ""
}
```

**Token facts to bake in:**
- Auth headers: `PRIVATE-TOKEN: <token>` (GitLab-specific, always works) or `Authorization: Bearer <token>` (OAuth-compatible, friendlier to proxies). CI job tokens use `JOB-TOKEN:` and reach only a restricted endpoint set — never route general API calls through a `CI_JOB_TOKEN`.
- Scope minimization is a hard capability boundary: issue `read_api` for read-only agents, `api` only for writers. The docs list ~17 PAT scopes (the research listed 9); document at least `api`, `read_api`, `read_repository`/`write_repository`, `read_user`, `sudo` (admin), `admin_mode` (self-managed *and* Dedicated — the research said self-managed-only; corrected).
- Since GitLab 16.0, new tokens require an expiry (max 365 days; 400 with a feature flag in 17.6+). OAuth access tokens expire in **2 hours** and need refresh — PATs are simpler for long-lived automation.
- Never put a token in an LLM prompt or a rules file. Provider logs are forever. Pass tokens only via env or a secrets vault.

### 4.4 Pagination

Prefer keyset for large collections — it is O(1) per page at the DB layer, stable when data shifts mid-iteration, and avoids the `x-total` header suppression above 10,000 records and the default 50,000-offset ceiling.

```go
// ✅ Do — keyset via the Scan2 iterator (range-over-func, Go 1.22+)
opts := &gitlab.ListProjectsOptions{ListOptions: gitlab.ListOptions{
    Pagination: "keyset", OrderBy: "id", Sort: "asc", PerPage: 100,
}}
for project := range gitlab.Must(gitlab.Scan2(func(p gitlab.PaginationOptionFunc) ([]*gitlab.Project, *gitlab.Response, error) {
    return client.Projects.ListProjects(opts, p)
})) {
    fmt.Println(project.PathWithNamespace)
}
```

```go
// ❌ Don't — offset pagination on large sets: full-index scans, shifting results,
// and errors past the configured max offset
for page := 1; ; page++ {
    opts := &gitlab.ListProjectsOptions{ListOptions: gitlab.ListOptions{Page: page, PerPage: 100}}
    projects, resp, _ := client.Projects.ListProjects(opts)
    _ = projects
    if resp.NextPage == 0 { break }
}
```

> *Flagged:* `WithKeysetPaginationParameters` and the `Scan2`/`ScanAndCollect` helpers were confirmed in v1 examples; their exact presence/signature in `/v2` should be reconfirmed against the v2 pkg.go.dev page before relying on them (as of 2026-05-29). Keyset is also not universal across endpoints — guard with an offset fallback.

### 4.5 JSON + template output to match `gh`

Three-tier output, TTY-aware, replicating `gh`'s contract:

```go
// ✅ Do — explicit JSON mode; machine-friendly plain text when piped; rich human on TTY
func render(result any, jsonFlag bool, jqExpr string) error {
    switch {
    case jsonFlag:
        enc := json.NewEncoder(os.Stdout)
        enc.SetIndent("", "  ")
        return enc.Encode(result) // apply embedded jq (gojq/itchyny) if jqExpr != ""
    case !isatty.IsTerminal(os.Stdout.Fd()):
        return renderTabDelimited(result) // no color, no truncation
    default:
        return renderTable(result) // color + aligned columns
    }
}
```

**Embed the jq engine** (`itchyny/gojq`) and a Go-template renderer so `--jq`/`--template` need no external `jq` binary — this is a confirmed `gh` advantage and a `glab` gap. **Crucially, add the `--json field,field` sparse projection `glab` lacks**: since REST returns full objects, project to the requested fields *client-side* before serializing. This is the headline parity win.

```bash
# ✅ Do — agent asks for exactly the fields it needs (client-side projection)
<tool> mr list --json iid,title,state,web_url
```

```bash
# ❌ Don't — dump the full object and let the model sift 40+ fields (80–90% wasted tokens)
<tool> mr list --output json   # glab-style full-object dump
```

### 4.6 Errors, exit codes, and non-interactive safety

- **Stable exit codes** (adopt `gh`'s): `0` success, `1` general, `2` cancel, `4` auth-required. Agents branch reliably on these.
- **Structured errors in JSON mode:** emit `{"error":"message","status":404}` rather than human prose. `glab` does *not* do this yet (tracked under the agent-friendliness umbrella `gitlab-org/cli#8177`) — it is a differentiator.
- **Never hang on a prompt.** Provide a global no-prompt switch and honor `GLAB_NO_PROMPT`-style semantics. (Note: `glab` deprecated `NO_PROMPT` for `GLAB_NO_PROMPT` in v2.0.0 — pick the prefixed form.)
- **`429` handling:** parse `Retry-After` and `RateLimit-Reset` (REST returns the full header set incl. `RateLimit-Name`, `RateLimit-Observed`). On self-hosted the window may be 3,600s, not 60 — never assume. Read `RateLimit-Remaining` and back off *proactively* below a threshold.

### 4.7 Testing

```go
// ✅ Do — httptest server pointed at via WithBaseURL; zero-backoff so tests don't sleep
func setup(t *testing.T) (*http.ServeMux, *gitlab.Client) {
    t.Helper()
    mux := http.NewServeMux()
    srv := httptest.NewServer(mux)
    t.Cleanup(srv.Close)
    client, err := gitlab.NewClient("",
        gitlab.WithBaseURL(srv.URL),
        gitlab.WithCustomBackoff(func(_, _ time.Duration, _ int, _ *http.Response) time.Duration { return 0 }),
    )
    if err != nil { t.Fatalf("NewClient: %v", err) }
    return mux, client
}
```

The SDK also ships a gomock-based `testing` sub-package (`.../client-go/v2/testing`, path inferred — reconfirm). If the project uses testify/mock, prefer the `httptest` approach above to avoid mixing mock frameworks.

---

## 5. Agent-Native Interaction Model — Minimizing Tokens

The governing principle, borrowed from GitHub's own agentic-workflow measurements: **the cheapest LLM call is the one you don't make, and the cheapest tool is the one whose schema isn't in context.** GitHub reported 19–62% effective token reductions across five workflows by pre-fetching deterministic data via CLI *before* the agent loop and removing unused MCP tool registrations (8–12 KB saved per tool eliminated). Output tokens are weighted ~4× cache-read input in their Effective-Tokens metric, so constraining the model's *output* (structured mutations) matters most.

Concrete model for this tool:

1. **One `api`-style escape hatch + a few typed commands — not dozens of MCP tools.** This is the central agent-native decision. A 15-tool focused set sits in the sweet spot; a 156- or 870-tool catalog injects 20k–490k tokens of schema before turn one.

2. **Field projection by default for agents.** `--json iid,title,state` over full objects cuts payloads 80–90%. For multi-field reads, route through GraphQL.

3. **NDJSON for streaming.** `--output ndjson` (one object per line) lets agents process incrementally and lets `jq` stream without buffering the whole array — ideal for `--paginate`d bulk reads.

4. **Pre-fetch deterministic data outside the loop.** If an agent always needs pipeline status at start, run the read in a setup step, write to a workspace file, and let the agent read the file — zero tokens spent on the fetch.

5. **`--limit N` with explicit truncation.** Cap large enumerations internally (`ScanAndCollectN`) and emit `"truncated": true` — never silently drop results.

6. **Structured errors + exit codes** so the agent reacts programmatically instead of re-reading prose.

```bash
# ✅ Do — deterministic, structured, minimal-field agent invocation
export GITLAB_HOST=https://gitlab.corp.example.com
export GITLAB_TOKEN=glpat-xxxx
<tool> issue list --label needs-triage --json iid,title,web_url --limit 50
<tool> api graphql -f query='query { project(fullPath:"g/r"){ pipelines(first:1){ nodes{ status } } } }'
```

```bash
# ❌ Don't — interactive, full-object, unbounded; can hang the agent and blow the budget
<tool> issue list                # may launch a TUI / wait on a prompt → agent hangs
<tool> mr list --output json     # full objects, every field, every page
```

---

## 6. Whether/How to Expose an MCP Facade

### 6.1 Position

Ship MCP as an **optional, thin facade**, off the default path. Most Cursor-agent value comes from the CLI being callable as a Bash tool with structured JSON — that has *zero* persistent schema cost and is 7–32× cheaper than MCP for read-heavy work. Expose MCP only where the *structured argument schema* genuinely helps the model fill a mutation correctly (create issue/MR), and even then keep the tool count tiny.

| Option | Tools | Approx. schema tokens | When |
|---|---|---|---|
| CLI as Bash tool (recommended default) | 0 | 0 | All read-heavy + most agent work |
| Thin custom MCP facade | ~10–15 | ~3–6k (est., unverified) | Write-accuracy-critical flows |
| Official GitLab MCP (`/api/v4/mcp`) | 15 | proportional, ~3–6k (est.) | Premium/Ultimate + Duo only |
| Community (zereight 156, jmrplens 870+) | many | 20k–490k | Avoid unless dynamic-mode |

### 6.2 If you build a facade — the high-value typed set

Mirror the official server's restraint (≈15 tools), grouped by the aggregated-tool pattern (one `manage_pipeline`-style tool dispatching list/create/cancel/retry beats five separate tools):

`get_issue` · `create_issue` · `get_merge_request` · `create_merge_request` · `get_mr_diffs` · `get_mr_pipelines` · `get_pipeline_jobs` · `manage_pipeline` · `search` · `create_note` · plus a single `api` passthrough for the long tail.

### 6.3 Guardrails (from community-server incidents)

- **Bind loopback by default.** A widely-used community server (zereight 2.0.0–2.0.20) defaulted `HOST=0.0.0.0`, exposing the embedded PAT to the LAN (AIKIDO-2025-11000). In PAT mode, bind `127.0.0.1` and refuse non-loopback (yoda-digital's model). For network exposure, require per-connection OAuth Bearer — hold no static token.
- **Read-only enforcement at the *token* layer**, not just by hiding tools. Hiding write tools at registration is defeatable by a regression; a `read_api`-only token is the true boundary.
- **Tool-name prefixing** when multiple instances are connected, so the model can't route a write to the wrong environment (official server added `X-Gitlab-Mcp-Server-Tool-Name-Prefix`, 32-char cap, in 18.11).

---

## 7. `gh` Feature-Parity Roadmap

Ordered by leverage. The first three close the confirmed `glab`-vs-`gh` gaps and define the tool's reason to exist.

| # | Capability | `gh` | `glab` | Priority | Notes |
|---|---|---|---|---|---|
| 1 | `--json field,field` sparse projection | ✅ | ❌ | **P0** | Biggest token win; project client-side (REST has no sparse fieldsets) |
| 2 | Built-in jq (`--jq`, no external binary) | ✅ | partial (`--jq` exists, no field select) | **P0** | Embed `gojq` |
| 3 | `--template` (Go templates + `tablerow`/`tablerender`/`timeago`) | ✅ | ❌ | **P0** | Columnar human output |
| 4 | TTY auto-detection (color/truncation off when piped) | ✅ | partial | **P0** | `isatty`; no flag needed |
| 5 | `api` escape hatch: REST **and** GraphQL, `--paginate`, typed `-F`/string `-f` | ✅ | partial | **P0** | GraphQL `--paginate` needs `$endCursor` + `pageInfo` |
| 6 | Stable exit codes (0/1/2/4) | ✅ | unclear | **P1** | Agent branching |
| 7 | Structured JSON errors | ⚠️ | ❌ | **P1** | Differentiator; `glab#8177` |
| 8 | Multi-host config (`hosts.yml`, per-host CA) | ✅ | ✅ | **P1** | Self-hosted essential |
| 9 | `--cache <dur>` on `api` reads | ✅ | ❌ | **P2** | Cuts repeat fetches in agent loops |
| 10 | `--slurp` (collect pages into one array) | ✅ | ❌ | **P2** | Convenience |
| 11 | Extension system | ✅ | ❌ (`#1053`) | **P3** | Large effort; defer |
| 12 | Thin MCP facade (~15 tools) | n/a | experimental `glab mcp serve` | **P2** | Optional; §6 |

> *Flagged:* the `glab api` "lacks `--jq`/`--template`/`--slurp`/`--cache`" comparison was *unverified* in one bundle and *partially contradicted* in another (`glab api` does have `--jq`; it lacks the gh-style `--json` field whitelist and `--template`). Reconfirm `glab api`'s current flag surface against `docs.gitlab.com/cli/api/` before finalizing scope (as of 2026-05-29).

---

## 8. Open Risks & Things to Reconfirm

These are the items most likely to be stale or instance-specific. Reconfirm before implementation.

1. **SDK major version & helper surface.** Use `client-go/v2`; the patch version (~v2.36.0) and the presence/signature of `Scan2`/`ScanAndCollect`/`WithKeysetPaginationParameters` and the `v2/testing` sub-package path in v2 are **flagged** — verify on pkg.go.dev. Any v1→v2 breaking changes were not confirmed from a primary source.

2. **GraphQL `PRIVATE-TOKEN`.** Current docs show only `Authorization: Bearer` for `/api/graphql`. Whether `PRIVATE-TOKEN` is accepted there is **unverified** — default to Bearer.

3. **Tier/offering gates drift.** Confirmed corrections: `admin_mode` is Self-Managed **and** Dedicated (not SM-only); project access tokens on **gitlab.com Free are unavailable** (Premium+), but available on Self-Managed Free; the official MCP server is Premium/Ultimate + Duo (one search hinted at Free-via-credits in 18.10 — **unconfirmed**, recheck); service accounts are EE (incl. Free EE up to 100) but **not** CE/FOSS. Gate feature paths on `isSelfHosted` and tier, and handle 403 with a clear upgrade hint.

4. **Token-cost estimates.** The 15-tool official-server schema overhead (~3–6k tokens) is **extrapolated, not measured**. Measure against a running instance before publishing agent guidance. Community-server figures (zereight 156 tools, jmrplens 870/1,031, dynamic-mode 20,488 tokens, `TOOL_SURFACE=meta` value — *not* `meta-tools`) carry per-repo corrections; treat as directional.

5. **`glab mcp serve` maturity.** One bundle calls it **experimental** ("not ready for production"), another **beta since 18.6** — likely a conflation of `glab mcp serve` (CLI, experimental) with the server-side `/api/v4/mcp` (beta). Do not depend on either as a stable contract; both are pre-GA.

6. **Offset-pagination failure mode.** "500 on exceeding max offset" is **unverified** (docs confirm a max-offset limit exists but not the exact status code). Guard with keyset; don't assume the error shape.

7. **Reverse-proxy `%2F` decoding (self-hosted).** Apache behind GitLab needs `AllowEncodedSlashes NoDecode` + `nocanon` or namespaced-path lookups 404 silently. NGINX usually handles it. A frequent, hard-to-diagnose self-hosted failure — surface a helpful error.

8. **Rate limits disabled by default on self-hosted.** Do not assume any limit exists; equally, do not assume gitlab.com's limits. Read headers at runtime and adapt.

9. **`x509.SystemCertPool()` on Windows.** Can return nil/error on some Go builds — the nil-pool fallback in §4.1 is mandatory; exact affected versions unidentified.

10. **Community MCP server identifiers churn.** Env-var names differ per server (`GITLAB_URL` vs `GITLAB_API_URL`; `GITLAB_READ_ONLY` vs `GITLAB_READ_ONLY_MODE`), npm package names, default ports (zereight `3002`, yoda-digital `3000`), and tool counts were all corrected during verification. If integrating any, read that server's current README — do not trust cross-references.

---

## Sources

Primary documentation and verified references this synthesis relies on:

- https://pkg.go.dev/gitlab.com/gitlab-org/api/client-go/v2
- https://gitlab.com/gitlab-org/api/client-go
- https://github.com/xanzy/go-gitlab/issues/2060
- https://docs.gitlab.com/api/rest/
- https://docs.gitlab.com/api/rest/authentication/
- https://docs.gitlab.com/api/rest/troubleshooting/
- https://docs.gitlab.com/api/graphql/
- https://docs.gitlab.com/api/graphql/getting_started/
- https://docs.gitlab.com/api/graphql/epic_work_items_api_migration_guide/
- https://docs.gitlab.com/development/api_graphql_styleguide/
- https://docs.gitlab.com/development/graphql_guide/pagination/
- https://docs.gitlab.com/user/profile/personal_access_tokens/
- https://docs.gitlab.com/security/tokens/
- https://docs.gitlab.com/security/rate_limits/
- https://docs.gitlab.com/administration/settings/user_and_ip_rate_limits/
- https://docs.gitlab.com/administration/instance_limits/
- https://docs.gitlab.com/administration/settings/sign_in_restrictions/
- https://docs.gitlab.com/administration/settings/account_and_limit_settings/
- https://docs.gitlab.com/topics/offline/quick_start_guide/
- https://docs.gitlab.com/cli/
- https://docs.gitlab.com/cli/auth/login/
- https://docs.gitlab.com/cli/api/
- https://docs.gitlab.com/cli/config/
- https://docs.gitlab.com/cli/mcp/serve/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_tools/
- https://docs.gitlab.com/user/gitlab_duo/semantic_code_search/
- https://about.gitlab.com/blog/give-your-ai-agent-direct-structured-gitlab-access-with-glab-cli/
- https://cli.github.com/manual/gh_api
- https://cli.github.com/manual/gh_help_formatting
- https://cli.github.com/manual/gh_help_exit-codes
- https://github.com/cli/go-gh
- https://github.blog/ai-and-ml/github-copilot/improving-token-efficiency-in-github-agentic-workflows/
- https://github.blog/engineering/engineering-principles/scripting-with-github-cli/
- https://www.scalekit.com/blog/mcp-vs-cli-use
- https://dev.to/nebulagg/mcp-tool-overload-why-more-tools-make-your-agent-worse-5a49
- https://github.com/zereight/gitlab-mcp
- https://github.com/jmrplens/gitlab-mcp-server
- https://github.com/yoda-digital/mcp-gitlab-server
- https://github.com/ttpears/gitlab-mcp
- https://intel.aikido.dev/cve/AIKIDO-2025-11000
- https://forfuncsake.github.io/post/2017/08/trust-extra-ca-cert-in-go-app/
- https://pkg.go.dev/net/http#ProxyFromEnvironment
- https://pkg.go.dev/crypto/x509#SystemCertPool
- https://gitlab.com/gitlab-org/gitlab/-/issues/352409
- https://gitlab.com/gitlab-org/gitlab/-/issues/586184
- https://gitlab.com/gitlab-org/cli/-/issues/1053
- https://gitlab.com/gitlab-org/cli/-/work_items/8177

*Researched 2026-05-29; version-specific details should be reconfirmed.*
