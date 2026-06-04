# Configuring Tooling Against Self-Hosted GitLab

An end-to-end, implementation-grade runbook for pointing `glab`, the REST API, the GraphQL API, and MCP servers at a **GitLab Self-Managed** (a.k.a. self-hosted) instance. **Self-hosted is the priority audience throughout; `gitlab.com` (SaaS) is the secondary case.**

> **Accuracy note.** Facts below are tagged where the underlying research was *corrected* or *unverified* during fact-checking. Version numbers, tier gating, feature-maturity labels (experiment vs. beta), and exact CLI flag names move fast. Treat anything marked "as of 2026-05-29; reconfirm against your instance/version" as a snapshot, not a contract. Researched 2026-05-29; version-specific details should be reconfirmed.

> **The one rule that governs everything below.** Never hardcode `https://gitlab.com`. Store one base URL — `https://<host>` with **no trailing slash** — and derive every surface from it:
>
> | Surface | Path appended to `https://<host>` |
> |---|---|
> | REST API v4 | `/api/v4/` |
> | GraphQL | `/api/graphql` |
> | GraphiQL explorer (browser) | `/-/graphql-explorer` |
> | Official MCP server | `/api/v4/mcp` |
> | OAuth authorize / token | `/oauth/authorize`, `/oauth/token` |
>
> The `/api/v4` prefix is mandatory and non-configurable. There is no `/api/v3/` — v3 was removed in GitLab 9.0 (confirmed).

---

## 1. Base-URL Conventions Per Surface

The single most common self-hosted failure is a malformed base URL. Each tool consumes the host differently, and several have a known doubled-scheme footgun.

### 1.1 `glab` — `GITLAB_HOST` / `GL_HOST` / `--hostname`

`glab` resolves the target host in this order (highest to lowest precedence):

1. explicit `--hostname` flag (on `glab auth login`, `glab api`, etc.)
2. `GITLAB_HOST` environment variable
3. `GL_HOST` environment variable (alias)
4. `host` key in `~/.config/glab-cli/config.yml`
5. Git remote URL auto-detection (when inside a repo)
6. fallback to `https://gitlab.com`

**Critical, version-dependent footgun (confirmed).** When `GITLAB_HOST` is used as an *environment variable*, set it to the **bare hostname WITHOUT the scheme**. `glab` prepends the protocol internally (from `api_protocol`); including `https://` produces a doubled-scheme URL (`https://https://...`).

```bash
# ✅ Do: bare hostname in the env var; protocol set separately
export GITLAB_HOST=gitlab.example.com
glab config set api_protocol https --host gitlab.example.com
glab mr list   # resolves to https://gitlab.example.com/api/v4/...
```

```bash
# ❌ Don't: include the scheme in the GITLAB_HOST env var
export GITLAB_HOST=https://gitlab.example.com
glab mr list
# Error: GET https://https://gitlab.example.com/api/v4/... (doubled scheme)
```

> **Caveat (as of 2026-05-29; reconfirm against your instance/version).** Some `glab` documentation and the `--hostname` flag accept a full URL form, and behavior has varied across versions (the bare-hostname requirement traces to the archived `profclems/glab` issue #592). The safe, portable choice is **bare hostname for the env var, and let `api_protocol` carry the scheme**. Confirm with `glab auth status` after setup.

A separate **API host** exists for instances where the API is served from a different address than the Git remote (e.g. an internal load balancer or `api.gitlab.example.com`):

```bash
# ✅ Do: split API host from Git remote host when they differ
glab auth login \
  --hostname gitlab.example.com \
  --api-host api.gitlab.example.com \
  --api-protocol https \
  --git-protocol ssh
```

> **Unverified (as of 2026-05-29).** A `GITLAB_API_HOST` env var (distinct from the `api_host` config key) was referenced in secondary sources but not confirmed on the official docs. Prefer the `--api-host` flag or the `api_host` config key; reconfirm before relying on the env var.

### 1.2 REST API — `/api/v4/`

```bash
GITLAB_HOST="https://gitlab.example.com"   # full URL WITH scheme is correct for raw curl
TOKEN="glpat-xxxxxxxxxxxxxxxxxxxx"

# ✅ Do: REST v4 base path
curl --silent --header "PRIVATE-TOKEN: $TOKEN" \
  "${GITLAB_HOST}/api/v4/projects?membership=true&per_page=20"

# ✅ Do: cheap instance/version probe (gate feature detection on this)
curl --header "PRIVATE-TOKEN: $TOKEN" "${GITLAB_HOST}/api/v4/version"
# -> {"version":"18.1.0","revision":"abc123"}
```

```bash
# ❌ Don't: any of these
curl ... "https://gitlab.com/api/v4/projects"            # wrong server (SaaS, not your instance)
curl ... "https://gitlab.example.com/projects"           # 404 — missing /api/v4
curl ... "https://gitlab.example.com/api/v3/projects"    # 404 — v3 removed in GitLab 9.0
curl ... "https://gitlab.example.com/api/v4/api/v4/..."  # doubled prefix (common when a tool already appends it)
```

> **Note for raw `curl` vs. Go SDK.** Raw `curl` takes the **full URL with scheme** (`https://...`). The Go SDK (`gitlab.com/gitlab-org/api/client-go`, see `gitlab-api.md`) wants the **root URL without `/api/v4/`** passed to `WithBaseURL` — it appends `/api/v4/` itself. Passing the full API path to `WithBaseURL` yields `/api/v4/api/v4/...`.

### 1.3 GraphQL — `/api/graphql`

Single endpoint, same auth headers as REST.

```bash
# ✅ Do
curl --silent --request POST \
  --header "Content-Type: application/json" \
  --header "Authorization: Bearer $TOKEN" \
  --data '{"query":"{ currentUser { username } }"}' \
  "${GITLAB_HOST}/api/graphql"
```

The browser GraphiQL explorer is at `${GITLAB_HOST}/-/graphql-explorer`.

> **GraphQL auth caveat (corrected during verification).** Current GraphQL docs show only `Authorization: Bearer <token>`. Whether the GitLab-specific `PRIVATE-TOKEN` header is accepted at `/api/graphql` is **unverified** for current versions — prefer `Authorization: Bearer` for GraphQL.

### 1.4 Official MCP server — `/api/v4/mcp`

The first-party MCP server lives inside the GitLab application itself at `${GITLAB_HOST}/api/v4/mcp`. See §7 for the full picture (it is OAuth-only, Premium/Ultimate, and requires GitLab Duo). The URL is simply the host with `/api/v4/mcp` appended.

### 1.5 Community MCP servers — `GITLAB_API_URL` vs. `GITLAB_URL` (these are NOT interchangeable)

Community servers split into two naming camps. **Verify the exact variable for the specific package** — passing the wrong one silently falls back to `gitlab.com`.

| Variable | Form | Servers that use it |
|---|---|---|
| `GITLAB_API_URL` | full REST path: `https://<host>/api/v4` | `zereight/gitlab-mcp`, `yoda-digital/mcp-gitlab-server`, `mcpland/gitlab-mcp`, `@modelcontextprotocol/server-gitlab` |
| `GITLAB_URL` | base URL: `https://<host>` (no `/api/v4`) | `ttpears/gitlab-mcp`, `jmrplens/gitlab-mcp-server` |

> **Unverified (as of 2026-05-29).** The exact env-var names per community package were flagged unverified in fact-checking; reconfirm against each repo's README before wiring in. The `GITLAB_API_URL` (full `/api/v4`) vs. `GITLAB_URL` (bare base) distinction is the load-bearing detail.

---

## 2. Creating & Scoping Tokens

### 2.1 Token types and their prefixes

| Type | Prefix | Header | Where created | Notes |
|---|---|---|---|---|
| Personal access token (PAT) | `glpat-` | `PRIVATE-TOKEN` or `Authorization: Bearer` | User > Settings > Access Tokens | The default for automation |
| Project access token | `glpat-` | `PRIVATE-TOKEN` | Project > Settings > Access Tokens | Bot user, cannot escape the project |
| Group access token | `glpat-` | `PRIVATE-TOKEN` | Group > Settings > Access Tokens | Bot user scoped to the group |
| Impersonation token | `glpat-` | `PRIVATE-TOKEN` | Admin API only (`POST /users/:id/impersonation_tokens`) | **Self-Managed & Dedicated only**, not SaaS |
| OAuth 2.0 token | (opaque) | `Authorization: Bearer` only | OAuth app flow | 2-hour lifetime (see §2.5) |
| CI job token | `glcbt-` | `JOB-TOKEN` | Injected as `$CI_JOB_TOKEN` in CI | Short-lived, restricted endpoint set |
| Deploy token | `gldt-` | (registry/package URLs only) | — | **Not** usable with the general REST API |
| Runner auth token | `glrt-` / `glrtr-` | — | Runner registration | `glrtr-` is the registration-workflow variant |

> **Header equivalence.** PATs, project/group/impersonation tokens all accept either `PRIVATE-TOKEN: <token>` or `Authorization: Bearer <token>`. OAuth tokens accept `Authorization: Bearer` only. CI job tokens use `JOB-TOKEN: $CI_JOB_TOKEN`.

### 2.2 PAT scopes (self-managed, all tiers unless noted)

`api` (full read/write), `read_api`, `read_user`, `read_repository`, `write_repository`, `read_registry`, `write_registry`, `read_virtual_registry`, `write_virtual_registry`, `create_runner`, `manage_runner`, `ai_features`, `k8s_proxy`, `self_rotate`, `read_service_ping` (admin), `sudo` (admin), and `admin_mode` (**Self-Managed and Dedicated only**, required when Admin Mode is enabled — see §2.4). OAuth tokens additionally support `openid`, `profile`, `email`.

**Effective access is the intersection of the token scope and the user's role.** An `api`-scoped token held by a Reporter cannot perform Maintainer actions.

```bash
# ✅ Do: minimum scope for the job (hard capability boundary)
#   read-only agent           -> read_api  (+ read_repository if cloning)
#   code-push agent           -> write_repository + read_api
#   full automation           -> api
```

```bash
# ❌ Don't: hand every agent an `api`-scoped, never-expiring token
#   A leaked `api` token can rewrite repos, delete pipelines, and read every
#   project the user can see. Scope down and set an expiry.
```

> **Token efficiency / agent note.** `read_api` is a hard write-prevention boundary — an LLM agent given a `read_api` token *cannot* mutate, regardless of prompt injection. Project access tokens are even tighter (they cannot escape the project boundary). Prefer the narrowest scope that lets the agent complete its task.

### 2.3 Project & group access tokens

Available on **all tiers including CE on self-managed and Dedicated** (confirmed correction — on `gitlab.com` Free tier they require Premium/Ultimate). They create a non-licensed bot user. You **cannot** use a project access token to create another project access token via the API — that creation call requires a PAT.

### 2.4 Admin Mode and the `admin_mode` scope

Admin Mode is a **Self-Managed and Dedicated** feature (corrected: not "self-managed only" — it is also on Dedicated; it is **not** on `gitlab.com`). When enabled, administrators do not have admin privileges by default; admin API calls require a PAT that carries the `admin_mode` scope **in addition to** `api`.

```bash
# ✅ Do: token with BOTH api and admin_mode scopes when Admin Mode is on
export ADMIN_TOKEN="glpat-..."   # scopes: api + admin_mode
curl --header "PRIVATE-TOKEN: $ADMIN_TOKEN" \
  "https://gitlab.example.com/api/v4/admin/users"
```

```bash
# ❌ Don't: assume an `api`-scoped admin token "just works"
#   With Admin Mode enabled, the token looks valid but admin endpoints return
#   403 until admin_mode scope is present. This is a silent, confusing failure.
```

Enable/disable via **Admin area > Settings > General > Sign-in restrictions**, or via the Application Settings API (`admin_mode=true|false`).

### 2.5 Token expiry (self-managed)

- Since **GitLab 16.0**, the UI no longer lets regular users create non-expiring PATs (confirmed).
- The **admin-level enforcement setting** that re-enables/disables this was introduced in **17.3** (this version split is a documented inconsistency — verify against your target version).
- Default and maximum lifetime is **365 days**; **400 days** with the `buffered_token_expiration_limit` feature flag in **17.6+** (the flag is disabled by default and **not** available on Dedicated).
- **Forcing a maximum lifetime (1–365 days) is an Ultimate-only feature.** CE/Free/Premium cannot administratively cap token lifetime below the default.
- Admins can disable expiry enforcement under **Admin > Settings > General > Account and limit**.
- An expired token returns **401**; since **17.2** `api_json.log` records `meta.auth_fail_reason: token_expired`.

### 2.6 OAuth applications & flows

Self-managed instances act as their own OAuth authorization server. Three supported flows:

1. **Authorization Code with PKCE** (recommended; `code_challenge_method=S256`; no client secret on public clients).
2. **Authorization Code** (standard; requires `client_secret`).
3. **Device Authorization Grant** (GA in **17.9**; `POST /oauth/authorize_device` then poll `/oauth/token`). `glab auth login --device` uses this and **requires GitLab 17.9+**.

Implicit and ROPC flows are **omitted per the OAuth 2.1 draft** (not "deprecated" in GitLab's wording — do not build on them).

**OAuth access tokens are valid for exactly 7,200 seconds (2 hours), non-configurable.** Any tool caching an OAuth token must implement refresh-token rotation. **For long-lived automation, a PAT with an appropriate expiry is simpler and more reliable than OAuth.**

### 2.7 Sudo (admin impersonation)

```bash
# ✅ Do: impersonate a user with an admin token carrying the `sudo` scope
curl --header "PRIVATE-TOKEN: $ADMIN_TOKEN" \
     --header "Sudo: target-username" \
     "https://gitlab.example.com/api/v4/user"
# Returns the target user's view, not the admin's.
```

`sudo` requires the token holder to be an **instance admin** AND the token to carry the `sudo` scope. A non-admin or missing-scope token returns `403 Forbidden - Must be admin to use sudo`; a non-existent sudo target returns `404`. Prefer `sudo` over minting impersonation tokens for short-lived scripting — it avoids token proliferation.

### 2.8 Token introspection for setup scripts

```bash
# ✅ Do: validate a supplied token's type/scopes/expiry before using it (self-managed)
curl --request POST --header "PRIVATE-TOKEN: $ADMIN_TOKEN" \
     --header "Content-Type: application/json" \
     --data '{"token":"glpat-suppliedtoken..."}' \
     "https://gitlab.example.com/api/v4/admin/token"
```

The Admin Token Information API lets an automated setup flow confirm a token has the correct scopes before attempting operations — useful for failing fast with a clear error instead of a confusing 403 mid-run.

---

## 3. TLS With a Custom / Internal CA — The Right Way

Self-hosted instances frequently sit behind internal PKI or self-signed certificates. **The correct fix is always to trust the CA, never to disable verification.** Go-based tools (`glab`, the Go SDK) honor the OS trust store plus `SSL_CERT_FILE` / `SSL_CERT_DIR`.

### 3.1 Trust the CA — in order of durability

```bash
# ✅ Option A (most durable): trust at the OS level — every Go binary, git, curl benefit
sudo cp internal-ca.crt /usr/local/share/ca-certificates/
sudo update-ca-certificates
# On RHEL/Fedora:
#   sudo cp internal-ca.pem /etc/pki/ca-trust/source/anchors/ && sudo update-ca-trust
```

```bash
# ✅ Option B (per-tool, any glab version): SSL_CERT_FILE env var
#    Go's crypto/tls reads SSL_CERT_FILE and SSL_CERT_DIR in addition to the OS store.
export SSL_CERT_FILE=/etc/ssl/certs/internal-ca.pem
glab auth login --hostname gitlab.example.com
```

```bash
# ✅ Option C (glab-scoped, persisted): glab config ca_cert
#    NOTE the flag is --host (NOT --hostname) on `glab config set`.
glab config set ca_cert /etc/ssl/certs/internal-ca.pem --host gitlab.example.com
```

> **Version caveat (as of 2026-05-29; reconfirm).** The `glab config set ca_cert` key is documented as existing; the specific "v1.58.0+" version floor came from a secondary source (MR reference) and was **not** confirmed against the official changelog. If `ca_cert` is unrecognized on your `glab`, fall back to Option A or B. The flag for per-host scoping on `glab config set` is `--host` / `-h`, **not** `--hostname` (corrected).

```bash
# ✅ Git operations against an internal-CA instance
git config --global http.sslCAInfo ~/.ssl/gitlab.crt
# or, scoped to one host:
git config --global http."https://gitlab.example.com/".sslCAInfo /etc/ssl/certs/internal-ca.pem
```

```bash
# ✅ The GitLab server itself (Omnibus): trust additional CAs server-side
sudo cp internal-ca.pem /etc/gitlab/trusted-certs/
sudo gitlab-ctl reconfigure
```

### 3.2 Go SDK (`RootCAs`) — for a custom CLI built on `client-go`

```go
// ✅ Do: augment the system pool with the internal CA, inject via WithHTTPClient
rootCAs, _ := x509.SystemCertPool()
if rootCAs == nil {
    rootCAs = x509.NewCertPool() // Windows can return nil — always guard
}
if pem, err := os.ReadFile(caCertPath); err == nil {
    rootCAs.AppendCertsFromPEM(pem)
}
httpClient := &http.Client{
    Transport: &http.Transport{
        Proxy:           http.ProxyFromEnvironment,           // see §4
        TLSClientConfig: &tls.Config{RootCAs: rootCAs},       // InsecureSkipVerify stays false
    },
}
client, _ := gitlab.NewClient(token,
    gitlab.WithBaseURL("https://gitlab.example.com"),         // root URL, no /api/v4
    gitlab.WithHTTPClient(httpClient),
)
```

### 3.3 Anti-pattern: disabling verification — DO NOT DO THIS

```bash
# ❌ NEVER in production: disables ALL certificate verification
export GIT_SSL_NO_VERIFY=true
# ❌ In .gitlab-ci.yml:
variables:
  GIT_SSL_NO_VERIFY: "true"
```

```bash
# ❌ NEVER: the curl equivalent
curl --insecure --header "PRIVATE-TOKEN: $TOKEN" "https://gitlab.example.com/api/v4/version"

# ❌ NEVER for Node-based community MCP servers
export NODE_TLS_REJECT_UNAUTHORIZED=0
```

```go
// ❌ NEVER in Go: SAST tools (e.g. Datadog static analysis) flag this as critical
TLSClientConfig: &tls.Config{InsecureSkipVerify: true}
```

**Why this is wrong, not just frowned upon.** Disabling verification exposes every token and repository credential to trivial man-in-the-middle interception — the attacker presents any certificate and your tool trusts it. GitLab's own docs label `GIT_SSL_NO_VERIFY` a temporary measure and a security risk. The correct fix is §3.1: trust the CA.

> **Development-only escape hatch, with eyes open.** Some Go community servers expose `GITLAB_SKIP_TLS_VERIFY=true` (e.g. `jmrplens/gitlab-mcp-server`). Treat it identically to the above — acceptable only on a throwaway dev instance, never where a real token is in play.

---

## 4. HTTP(S) Proxy & `no_proxy`

Go's `net/http` (and therefore `glab` and the Go SDK) reads standard proxy env vars via `http.ProxyFromEnvironment`: `HTTP_PROXY`, `HTTPS_PROXY`, `NO_PROXY` (and lowercase variants). **There is no dedicated `glab config set http_proxy` key in production `glab`** (the feature request, archived `profclems/glab` #765, was never merged) — set the env vars in the shell.

```bash
# ✅ Do: standard proxy env vars; exclude internal hosts from proxying
export HTTPS_PROXY=http://corp-proxy.internal:3128
export HTTP_PROXY=http://corp-proxy.internal:3128
export NO_PROXY=gitlab.example.com,registry.example.com,.internal,localhost,127.0.0.1
glab mr list
```

```bash
# ❌ Don't: proxy traffic to the internal GitLab through an external proxy
export HTTPS_PROXY=http://corp-proxy.internal:3128
# (no NO_PROXY) -> requests to gitlab.example.com are bounced through the proxy,
# often breaking internal TLS/routing and adding latency. List internal hosts in NO_PROXY.
```

- **Loopback addresses are never proxied** regardless of env vars (Go behavior).
- **GitLab server** outbound proxy lives in `/etc/gitlab/gitlab.rb` under `gitlab_rails['env']`.
- **GitLab Runner** (for CI-resident agents): set both case variants in `/etc/gitlab-runner/config.toml` `[[runners]]` and a `NO_PROXY` excluding the instance + registry. GitLab tooling honors both `HTTP_PROXY` and `http_proxy` with no single canonical case — set both.
- **Community MCP servers**: `zereight` and `mcpland` honor `HTTP_PROXY`/`HTTPS_PROXY`; `mcpland` additionally offers `GITLAB_CLOUDFLARE_BYPASS=true` for WAF-fronted instances.

To combine proxy + custom CA in a Go transport, set both `Proxy: http.ProxyFromEnvironment` and `TLSClientConfig.RootCAs` on the same `*http.Transport` (see §3.2).

---

## 5. CE vs. EE / Premium / Ultimate — Feature Gating & Version Differences

GitLab ships a single unified codebase: the EE binary behaves as CE when no license is active. **Endpoints and fields that require a paid tier return `403` or are silently excluded on an unlicensed/CE instance.** Always probe `/api/v4/version` and guard newer features against `404`.

### 5.1 Gates that matter for CLI / API / agent tooling

| Capability | Availability on self-managed | Notes |
|---|---|---|
| REST/GraphQL core | All tiers incl. CE | The bread and butter |
| Project & group access tokens | **All tiers incl. CE** | (On `gitlab.com` Free: needs Premium/Ultimate) |
| Disabling PAT expiry enforcement | All tiers | Admin setting |
| **Maximum PAT lifetime enforcement** | **Ultimate only** | CE/Free/Premium cannot cap lifetime |
| **Service accounts** | **EE only** (incl. Free EE up to 100); **CE cannot create them** | Corrected: Free *EE* gained them GA in 18.11; CE/FOSS still cannot |
| `admin_mode` PAT scope | **Self-Managed & Dedicated** | Not on `gitlab.com` |
| Impersonation tokens | **Self-Managed & Dedicated** | Not on `gitlab.com` |
| **Official MCP server** (`/api/v4/mcp`) | **Premium/Ultimate + GitLab Duo, GitLab 18.6+ (beta)** | See §7 |
| `semantic_code_search` MCP tool | Premium/Ultimate + vector store + AI Gateway | Needs ES 8.0+/OpenSearch 2.0+/pgvector |
| GitLab Duo CLI (`glab duo`) | Premium/Ultimate, GitLab 18.11+ | Beta |
| Job-token query param for artifact download | Premium/Ultimate (on `gitlab.com`) | Header auth works on all |

### 5.2 Version floors worth remembering

- `glab` minimum supported instance: **GitLab 16.0**.
- OAuth device flow: **17.9+**.
- Official MCP server: experiment in **18.3**, beta in **18.6**, still beta as of **18.11 (May 2026)** — **not GA**.
- API v4 follows semantic versioning; backward-incompatible changes are reserved for major GitLab releases.
- **Maintenance policy:** only the current minor gets bug fixes and N-2 minors get security fixes. Instances older than ~3 months may lack endpoints documented in current docs — guard with version checks.

```bash
# ✅ Do: gate a feature call on the instance version
ver=$(curl -s --header "PRIVATE-TOKEN: $TOKEN" "${GITLAB_HOST}/api/v4/version" | jq -r .version)
# branch on $ver before calling 18.x-only endpoints; handle 404 as "not on this version"
```

---

## 6. Air-Gapped / Offline Self-Managed

Air-gapped instances have no path to `gitlab.com`, so any tool that phones home will hang or fail on startup.

```bash
# ✅ Do: full offline glab bootstrap
# 1. Stop glab from blocking on a gitlab.com update check
glab config set check_update false --global

# 2. Suppress outbound telemetry
export GLAB_SEND_TELEMETRY=false

# 3. Trust the internal CA (if TLS is in use)
export SSL_CERT_FILE=/etc/pki/ca-trust/internal-ca.pem

# 4. Point at the internal instance (bare hostname for the env var)
export GITLAB_HOST=gitlab.internal.corp

# 5. Authenticate with a PAT — no browser/OAuth/device flow is reachable offline
glab auth login --hostname gitlab.internal.corp --token "$GITLAB_TOKEN" --git-protocol ssh
```

```bash
# ❌ Don't: rely on OAuth web flow or device flow in an air-gapped network
glab auth login --hostname gitlab.internal.corp --web      # needs external connectivity
glab auth login --hostname gitlab.internal.corp --device   # device flow also needs reachability
# PAT login (--token / GITLAB_TOKEN) is the ONLY viable offline auth method.
```

Server-side and ecosystem notes for air-gapped operation:

- Disable **Version Check**, **Service Ping**, **Runner version management**, and external **NTP** access in the Admin area; set up a **local package mirror** (or transfer via physical media).
- `EXTERNAL_URL` should be a **stable hostname**, not an IP.
- Distribute the internal CA to the Docker registry client, GitLab Runner, and all git clients.
- **MCP:** the official server uses OAuth Dynamic Client Registration, which needs reachability for the auth dance; air-gapped/PAT-only environments should use a **community MCP server** with a PAT (`GITLAB_API_URL`/`GITLAB_URL` + `GITLAB_TOKEN`) or `glab mcp serve` (PAT-authenticated stdio, experimental). The Go-static-binary community servers (e.g. `jmrplens/gitlab-mcp-server`) avoid even a Node runtime dependency — useful in locked-down images.

---

## 7. MCP Servers Against Self-Hosted

Two distinct families. **Do not conflate them.**

### 7.1 Official server — `${GITLAB_HOST}/api/v4/mcp`

- Built into GitLab. **Experiment in 18.3, beta in 18.6, still beta as of 18.11** (not GA).
- Requires **Premium or Ultimate**, **GitLab Duo enabled**, and (on self-managed/Dedicated) **beta/experimental features turned on**: Admin icon > **Settings > GitLab Duo > Change configuration**, check *"Use experiment and beta GitLab Duo features"* AND *GitLab Duo enabled for this instance*. **Missing either => the `/api/v4/mcp` endpoint returns 404** (the most common self-hosted MCP failure).
- **Auth is OAuth 2.0 Dynamic Client Registration (RFC 7591) only. No PAT support** as of 18.11 (open issue #586184). Dynamically-registered apps get only the restricted `mcp` scope.
- Exposes **15 tools** (corrected from 14): `get_mcp_server_version`, `create_issue`, `get_issue`, `create_merge_request`, `get_merge_request`, `get_merge_request_commits`, `get_merge_request_diffs`, `get_merge_request_pipelines`, `get_pipeline_jobs`, `manage_pipeline`, `create_workitem_note`, `get_workitem_notes`, `search`, `search_labels`, `semantic_code_search`.

```json
// ✅ Do: HTTP transport (recommended — no Node, no mcp-remote shim, lowest latency)
{
  "mcpServers": {
    "gitlab": {
      "type": "http",
      "url": "https://gitlab.example.com/api/v4/mcp",
      "headers": { "X-Gitlab-Mcp-Server-Tool-Name-Prefix": "gl_" }
    }
  }
}
```

The `X-Gitlab-Mcp-Server-Tool-Name-Prefix` header (added **18.11**, truncated to 32 chars) disambiguates tool names when wiring multiple instances.

```json
// ❌ Don't: two GitLab MCP servers with no prefix — tool names collide
{
  "mcpServers": {
    "gitlab-prod":    { "type": "http", "url": "https://gitlab.example.com/api/v4/mcp" },
    "gitlab-staging": { "type": "http", "url": "https://staging.gitlab.example.com/api/v4/mcp" }
  }
}
// Both expose identical create_issue / get_merge_request names; the model cannot
// tell which instance to target and may route a write to the wrong environment.
// Set distinct prefixes ("prod_", "staging_") via the header (needs 18.11+).
```

```json
// ❌ Don't: try to PAT-auth the official endpoint — it ignores Bearer <glpat-...>
{
  "mcpServers": {
    "gitlab": {
      "type": "http",
      "url": "https://gitlab.example.com/api/v4/mcp",
      "headers": { "Authorization": "Bearer glpat-xxxxxxxxxxxxxxxxxxxx" }
    }
  }
}
// PAT auth is not implemented (issue #586184). Use a community server or glab mcp serve for PAT/M2M.
```

> **`mcp-remote` scope bug (issue #585699 / #566965).** stdio clients (e.g. Claude Desktop) that proxy through `mcp-remote >= 0.1.27` may hit *"The requested scope is invalid, unknown, or malformed"* because the client injects `&scope=mcp`. Workaround: pass `--static-oauth-client-metadata '{"scope": "mcp"}'`, or clear `~/.mcp-auth/mcp-remote*` for stale-cache false positives. This affects self-managed identically to SaaS.

### 7.2 Community servers & `glab mcp serve` — the PAT-friendly path

For **CE / Free / air-gapped / PAT-only / M2M** scenarios, the official server is unusable (no PAT, Premium-gated). Use a community server or `glab mcp serve`.

```json
// ✅ Do: community server (any tier, PAT-based) — note GITLAB_API_URL is the FULL /api/v4 path
{
  "mcpServers": {
    "gitlab": {
      "command": "npx",
      "args": ["-y", "@zereight/mcp-gitlab"],
      "env": {
        "GITLAB_PERSONAL_ACCESS_TOKEN": "glpat-xxxxxxxxxxxxxxxxxxxx",
        "GITLAB_API_URL": "https://gitlab.example.com/api/v4",
        "GITLAB_READ_ONLY_MODE": "true",
        "USE_PIPELINE": "true"
      }
    }
  }
}
```

```json
// ✅ Do: glab mcp serve (stdio, PAT via glab's existing auth) — experimental
{
  "mcpServers": {
    "glab": { "type": "stdio", "command": "glab", "args": ["mcp", "serve"] }
  }
}
```

> **`glab mcp serve` caveats.** It is **experimental** (per the `glab` docs it remains experimental even where the official server reached beta — versions differ; reconfirm). Its tool set is smaller and it relies on `glab`'s existing auth — run `glab auth login` first rather than assuming `GITLAB_TOKEN`/`GITLAB_HOST` are read by the subcommand (that env-var support for the `mcp serve` subcommand specifically is **unverified**).

```bash
# ❌ Don't: bind a PAT-mode community server to a non-loopback address
docker run -d -e GITLAB_PERSONAL_ACCESS_TOKEN=glpat-... -e HOST=0.0.0.0 \
  -e STREAMABLE_HTTP=true -p 3000:3000 zereight050/gitlab-mcp
# A single long-lived PAT becomes reachable from the whole network.
# zereight 2.0.0-2.0.20 defaulted HOST=0.0.0.0 (AIKIDO-2025-11000, fixed in 2.0.21).
# For network-exposed deployments use OAuth mode (the server holds no static token),
# or keep HOST=127.0.0.1 in PAT mode. (yoda-digital refuses to start in this config.)
```

```text
# ❌ Don't: ever put a token in prompt text, CLAUDE.md, or a rules file.
# LLM providers log inference requests. A token in a prompt = a token in logs.
# Tokens belong ONLY in the MCP server's env block or a secrets vault.
```

> **Agent token-efficiency note.** Every MCP tool's name + description + JSON schema is injected into the model's context before the first turn — GitLab's own dev guidelines warn this increases input token count, slows responses, and reduces accuracy. The official server's 15 tools are compact; community servers can expose 80–1000+ tools. Use feature flags (`USE_PIPELINE`, `USE_MILESTONE`, `USE_GITLAB_WIKI` default *off* in `zereight`), `GITLAB_READ_ONLY_MODE=true` (drops all write-tool schemas), or progressive-disclosure modes (`jmrplens` `TOOL_SURFACE=dynamic` exposes 2 discovery tools instead of 866+) to keep context cost down.

---

## 8. Pagination, Rate Limiting & Agent Efficiency (self-hosted specifics)

These cross-cut every surface and differ from `gitlab.com`.

### 8.1 Rate limiting

- **Disabled by default on self-managed** (unlike `gitlab.com`'s fixed limits). Admins configure under **Admin > Settings > Network > User and IP rate limits**; defaults when enabled are 7,200/hr authenticated, 3,600/hr unauthenticated. **Do not assume any limit exists unless configured.**
- `429 Too Many Requests` carries `Retry-After`. All responses carry `RateLimit-Limit`, `RateLimit-Name`, `RateLimit-Observed`, `RateLimit-Remaining`, `RateLimit-Reset`; 429s add `RateLimit-ResetTime`.
- Admins can run a **dry-run** mode (log throttle hits without blocking) and can ban an IP with `403` after repeated `401`s.

```text
# ✅ Do: read Retry-After on 429 (self-managed windows can be 3,600s, not 60s);
#        self-throttle when RateLimit-Remaining is low instead of hitting 429 blind.
# ❌ Don't: use fixed exponential backoff that ignores Retry-After.
```

### 8.2 Pagination

- Prefer **keyset pagination** (`?pagination=keyset&order_by=id&sort=asc&per_page=100`) for large collections — it is O(1) per page and survives data changes mid-iteration.
- Offset pagination **omits `x-total`/`x-total-pages`/`rel=last` above 10,000 records** — code that treats an absent header as zero will silently stop after page 1.
- Self-managed offset limit defaults to **50,000** (configurable via Rails console `Plan.default.actual_limits.update!(offset_pagination_limit: N)`; `0` disables).

### 8.3 Agent token efficiency

- **GraphQL field selection** is the primary token-reduction lever — request exactly the fields needed instead of REST's full objects (a 5-field GraphQL query over 20 MRs can use 5–10x fewer tokens than the REST equivalent). REST v4 has **no** server-side field selection; filter client-side (e.g. `glab api ... | jq`).
- `glab api --paginate --output ndjson` streams one object per line — friendlier for incremental agent parsing.
- Scope tokens minimally (§2.2); prefer webhooks/`glab ci watch` over polling pipelines in a loop.

---

## 9. Troubleshooting Table

| Symptom | Likely cause | Fix |
|---|---|---|
| **401 Unauthorized** | Expired token, or wrong token type for the header | Check expiry (since 16.0 tokens expire; `api_json.log` shows `meta.auth_fail_reason: token_expired` in 17.2+). Use `PRIVATE-TOKEN` for PATs, `Authorization: Bearer` for OAuth, `JOB-TOKEN` for CI tokens. |
| **403 Forbidden** on admin endpoint | **Admin Mode enabled** but token lacks `admin_mode` scope | Mint a PAT with `api` **+** `admin_mode` (§2.4). |
| **403 "Must be admin to use sudo"** | `sudo` used by non-admin, or token missing `sudo` scope | Token holder must be an instance admin AND token must carry `sudo` scope. |
| **403** on a feature that "should work" | Tier gating (CE/Free hitting a Premium/Ultimate endpoint) | Check tier (§5). Probe `/api/v4/version`; the feature may not exist on your tier. |
| **404 Not Found** on a valid resource | Missing `/api/v4` prefix, used `/api/v3/`, or hit `gitlab.com` instead of your host | Use `https://<host>/api/v4/...` (§1.2). v3 was removed in 9.0. |
| **404** on private resource | Token can't see it (GitLab returns 404, not 403, for unauthorized private resources) | Confirm the token's user has access; check scope intersection with role. |
| **404** with URL-encoded slashes (`%2F`) behind Apache | Reverse proxy decoded `%2F` before forwarding | Add `AllowEncodedSlashes NoDecode` to the VirtualHost and `nocanon` to ProxyPass. NGINX usually handles this. Also check HTTP↔HTTPS protocol mismatch between proxy and GitLab. |
| **404** on `/api/v4/mcp` | GitLab Duo and/or beta features not enabled, or tier < Premium | Admin > Settings > GitLab Duo: enable Duo **and** "Use experiment and beta GitLab Duo features" (§7.1). Requires Premium/Ultimate, GitLab 18.6+. |
| **x509: certificate signed by unknown authority** | Internal CA not trusted | Trust the CA (§3.1): OS store, `SSL_CERT_FILE`, `glab config ca_cert`, or `git http.sslCAInfo`. **Do not** disable verification. |
| **x509: certificate is valid for X, not Y** | Hostname mismatch (cert SAN doesn't cover the host you're calling) | Use the host the cert was issued for, or reissue the cert with the correct SAN. Not fixable by trusting a CA. |
| **Doubled scheme** `https://https://...` | `GITLAB_HOST` env var set with a scheme | Set `GITLAB_HOST=gitlab.example.com` (bare), control scheme via `api_protocol` (§1.1). |
| **Doubled path** `/api/v4/api/v4/...` | Passed full API path where the tool appends `/api/v4` | Go SDK `WithBaseURL` and many MCP servers want the **root** URL; `GITLAB_API_URL` wants `/api/v4`, `GITLAB_URL` wants bare base (§1.5). |
| **Hang on startup / timeout** reaching `gitlab.com` | `glab` update check or telemetry in an air-gapped/proxied network | `glab config set check_update false --global`; `GLAB_SEND_TELEMETRY=false`; set `NO_PROXY` (§4, §6). |
| **429 Too Many Requests** | Rate limit (only if admin enabled it on self-managed) | Honor `Retry-After`; self-throttle on `RateLimit-Remaining` (§8.1). |
| **Proxy errors / connection refused** to internal host | Internal GitLab routed through an external proxy | Add the instance + registry to `NO_PROXY` (§4). |
| **MCP "scope is invalid"** | `mcp-remote >= 0.1.27` injecting `&scope=mcp` | `--static-oauth-client-metadata '{"scope":"mcp"}'`; clear `~/.mcp-auth/mcp-remote*` (§7.1). |
| **MCP tool-name collision / wrong instance targeted** | Two GitLab MCP servers without prefixes | Set `X-Gitlab-Mcp-Server-Tool-Name-Prefix` (18.11+) per server (§7.1). |

---

## Sources

- https://docs.gitlab.com/api/rest/
- https://docs.gitlab.com/api/rest/authentication/
- https://docs.gitlab.com/api/rest/troubleshooting/
- https://docs.gitlab.com/api/graphql/
- https://docs.gitlab.com/api/graphql/getting_started/
- https://docs.gitlab.com/api/oauth2/
- https://docs.gitlab.com/integration/oauth_provider/
- https://docs.gitlab.com/api/user_tokens/
- https://docs.gitlab.com/api/admin/token/
- https://docs.gitlab.com/api/project_access_tokens/
- https://docs.gitlab.com/api/job_artifacts/
- https://docs.gitlab.com/security/tokens/
- https://docs.gitlab.com/security/tokens/token_troubleshooting/
- https://docs.gitlab.com/user/profile/personal_access_tokens/
- https://docs.gitlab.com/user/project/settings/project_access_tokens/
- https://docs.gitlab.com/user/profile/service_accounts/
- https://docs.gitlab.com/cli/
- https://docs.gitlab.com/cli/auth/login/
- https://docs.gitlab.com/cli/config/
- https://docs.gitlab.com/cli/config/set/
- https://docs.gitlab.com/cli/api/
- https://docs.gitlab.com/cli/mcp/serve/
- https://docs.gitlab.com/omnibus/settings/ssl/ssl_troubleshooting/
- https://docs.gitlab.com/runner/configuration/tls-self-signed/
- https://docs.gitlab.com/runner/configuration/proxy/
- https://docs.gitlab.com/administration/settings/account_and_limit_settings/
- https://docs.gitlab.com/administration/settings/sign_in_restrictions/
- https://docs.gitlab.com/administration/settings/user_and_ip_rate_limits/
- https://docs.gitlab.com/administration/instance_limits/
- https://docs.gitlab.com/topics/offline/quick_start_guide/
- https://docs.gitlab.com/policy/maintenance/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_tools/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_troubleshooting/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_clients/
- https://docs.gitlab.com/user/gitlab_duo/semantic_code_search/
- https://docs.gitlab.com/user/gitlab_duo/turn_on_off/
- https://docs.gitlab.com/development/duo_agent_platform/mcp/
- https://pkg.go.dev/gitlab.com/gitlab-org/api/client-go/v2
- https://forfuncsake.github.io/post/2017/08/trust-extra-ca-cert-in-go-app/
- https://pkg.go.dev/net/http#ProxyFromEnvironment
- https://gitlab.com/gitlab-org/gitlab/-/issues/586184
- https://gitlab.com/gitlab-org/gitlab/-/issues/585699
- https://gitlab.com/gitlab-org/gitlab/-/work_items/566965
- https://gitlab.com/gitlab-org/cli/-/issues/1304
- https://github.com/profclems/glab/issues/592
- https://github.com/profclems/glab/issues/765
- https://github.com/zereight/gitlab-mcp
- https://intel.aikido.dev/cve/AIKIDO-2025-11000
- https://github.com/yoda-digital/mcp-gitlab-server
- https://github.com/mcpland/gitlab-mcp
- https://github.com/ttpears/gitlab-mcp
- https://github.com/jmrplens/gitlab-mcp-server

Researched 2026-05-29; version-specific details should be reconfirmed.
