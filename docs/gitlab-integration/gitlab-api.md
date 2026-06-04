# GitLab API Reference (REST v4 + GraphQL)

Implementation-grade reference for both GitLab HTTP APIs, written for engineers
and LLM agents building integrations against **self-hosted (Self-Managed)**
GitLab first, with **gitlab.com** as the secondary case.

> Researched 2026-05-29. Several facts below are version- and tier-sensitive;
> anything marked *(as of 2026-05-29; reconfirm against your instance/version)*
> must be re-verified against the specific GitLab version and edition you target.
> Always call `GET /api/v4/version` and gate feature use on the result.

---

## 1. Base URLs & self-hosted addressing

There is exactly one active REST version (**v4**) and one GraphQL endpoint. API
version 3 was removed in GitLab 9.0 and no longer exists.

| Surface            | Self-hosted (priority)                       | gitlab.com                          |
| ------------------ | -------------------------------------------- | ----------------------------------- |
| REST v4            | `https://<host>/api/v4/`                     | `https://gitlab.com/api/v4/`        |
| GraphQL            | `https://<host>/api/graphql`                 | `https://gitlab.com/api/graphql`    |
| GraphiQL explorer  | `https://<host>/-/graphql-explorer`          | `https://gitlab.com/-/graphql-explorer` |
| Version probe      | `https://<host>/api/v4/version`              | `https://gitlab.com/api/v4/version` |

The `/api/v4` prefix is **mandatory and non-configurable** on every REST
request. v4 follows semantic versioning: backward-incompatible changes are
reserved for major GitLab releases only; minor additions are backward-compatible
within v4.

### Self-hosted addressing rules

- The host is determined entirely by `external_url` in `/etc/gitlab/gitlab.rb`
  (Omnibus) or `config/gitlab.yml` (source). There is **no fixed hostname** —
  every CLI/tool must accept a configurable base URL.
- Store the base as `https://<host>` **without** a trailing slash, then append
  `/api/v4/` (REST), `/api/graphql` (GraphQL), or `/api/v4/mcp` (official MCP
  server).
- `glab` resolves the host in this order: explicit `--hostname` flag →
  `GITLAB_HOST` env var → `GL_HOST` env var → `host` key in
  `~/.config/glab-cli/config.yml` → Git remote detection (inside a repo) →
  fallback `https://gitlab.com`.

```bash
# ✅ Do — parameterize the base URL, never hardcode gitlab.com for self-hosted
GITLAB_HOST="https://gitlab.example.com"
curl --silent --header "PRIVATE-TOKEN: $TOKEN" \
  "${GITLAB_HOST}/api/v4/version"   # {"version":"18.1.0","revision":"abc123",...}
```

```bash
# ❌ Don't — three classic self-hosted base-URL mistakes
curl --header "PRIVATE-TOKEN: $TOKEN" "https://gitlab.com/api/v4/projects"          # wrong server: returns gitlab.com data
curl --header "PRIVATE-TOKEN: $TOKEN" "https://gitlab.example.com/projects"         # 404: missing /api/v4
curl --header "PRIVATE-TOKEN: $TOKEN" "https://gitlab.example.com/api/v3/projects"  # 404: v3 removed in GitLab 9.0
```

---

## 2. Authentication & token scopes

All token types are passed via **headers**, not query parameters. The same
tokens work for both REST and GraphQL.

### 2.1 How each token is passed

| Token type                                   | Prefix    | Header                              |
| -------------------------------------------- | --------- | ----------------------------------- |
| Personal access token (PAT)                  | `glpat-`  | `PRIVATE-TOKEN: <token>` or `Authorization: Bearer <token>` |
| Project access token                         | `glpat-`  | `PRIVATE-TOKEN: <token>`            |
| Group access token                           | `glpat-`  | `PRIVATE-TOKEN: <token>`            |
| Impersonation token (admin-created PAT)      | `glpat-`  | `PRIVATE-TOKEN: <token>`            |
| OAuth 2.0 access token                       | —         | `Authorization: Bearer <token>` (only) |
| CI/CD job token (`$CI_JOB_TOKEN`)            | `glcbt-`  | `JOB-TOKEN: $CI_JOB_TOKEN`          |
| Admin sudo (additional header)               | —         | `Sudo: <username_or_id>` (requires `sudo` scope) |
| Deploy token                                 | `gldt-`   | package-registry endpoints only — **not** general REST |
| Runner auth token                            | `glrt-` / `glrtr-` | runner registration — not API auth |

> Note: the `glcbt-` job-token prefix was added in GitLab 16.9. Token-detection
> logic should also accept unprefixed job tokens for older instances *(as of
> 2026-05-29; reconfirm against your version)*.

```bash
# ✅ Do — pass the token in the PRIVATE-TOKEN header (never logged in URLs/proxies)
curl --request GET \
     --header "PRIVATE-TOKEN: glpat-xxxxxxxxxxxxxxxxxxxx" \
     "https://gitlab.example.com/api/v4/projects/mygroup%2Fmyrepo"
```

```bash
# ❌ Don't — token in the query string is recorded verbatim in nginx/Apache access logs,
#            browser history, and intermediary proxy logs
curl "https://gitlab.example.com/api/v4/projects?private_token=glpat-xxxxxxxxxxxxxxxxxxxx"
```

### 2.2 PAT scopes

Exact scope names (self-managed, all tiers unless noted):

`api` (full read/write across API, registries, groups, projects), `read_api`
(read-only), `read_user`, `read_repository`, `write_repository`,
`read_registry`, `write_registry`, `read_virtual_registry`,
`write_virtual_registry`, `create_runner`, `manage_runner`, `ai_features`,
`k8s_proxy`, `self_rotate`, `read_service_ping` (admin), `sudo` (admin;
required for impersonation), `admin_mode` (**self-managed and GitLab Dedicated
only** — not gitlab.com). OAuth 2.0 additionally supports `openid`, `profile`,
`email`.

> Corrected from common references: `admin_mode` is available on **both**
> self-managed and GitLab Dedicated, not "self-managed only".

A token's effective access is the **intersection** of its scope and the
holder's GitLab role — scopes never override role-based permissions.

```go
// ✅ Do — scope tokens to the minimum capability. read_api is a hard write-blocker.
//   read-only agent:   read_api + read_repository
//   code-push agent:    write_repository + read_api   (NOT full `api`)
//   never:              `sudo` scope in an unattended agent unless impersonation is required
```

### 2.3 OAuth 2.0

- Access tokens are valid for **7,200 seconds (2 hours)**, non-configurable;
  refresh via the `refresh_token` grant.
- Supported flows: **Authorization Code with PKCE** (`code_challenge_method=S256`;
  recommended, no client secret needed on public clients); **Authorization
  Code** (standard, server-side `client_secret`); **Device Authorization Grant**
  (GA in GitLab 17.9 — `POST /oauth/authorize_device` then poll `/oauth/token`).
- Implicit and Resource Owner Password Credentials flows are **not supported**
  (omitted per the OAuth 2.1 draft — GitLab does not label them "deprecated", it
  simply does not implement them). Do not build new integrations on these.

### 2.4 Project & group access tokens

Create non-licensed bot users scoped to a single project or group; passed
identically to PATs (`PRIVATE-TOKEN`). You **cannot** use a project access token
to create another project access token — that call requires a PAT.

> Tier gate (corrected): on **gitlab.com**, project/group access tokens require
> **Premium or Ultimate** — they are **not** available on the gitlab.com Free
> tier. On **self-managed and Dedicated** they are available on **all tiers
> including Free**. Gate this on subscription tier or handle the 403 with a
> tier-upgrade hint.

```go
// ❌ Don't — assume project access tokens work on gitlab.com Free; this 403s.
func createProjectToken(host, projectID, name, pat string) error {
    // On gitlab.com this requires Premium/Ultimate. Self-managed/Dedicated: all tiers.
    url := fmt.Sprintf("https://%s/api/v4/projects/%s/access_tokens", host, projectID)
    _ = url
    return nil
}
```

### 2.5 Admin sudo & impersonation (self-hosted/Dedicated)

- **Sudo:** an admin whose token carries the `sudo` scope can act as any user by
  adding `Sudo: <username_or_id>` (header) or `?sudo=<username_or_id>`. Returns
  403 if the caller is not an admin or the token lacks `sudo`; 404 if the target
  user does not exist.
- **Impersonation tokens:** a PAT subtype (`glpat-` prefix), created by admins
  only via `POST /users/:user_id/impersonation_tokens`. Available on Self-Managed
  and Dedicated; **not** on gitlab.com. The token value is returned only at
  creation. Behaves identically to a PAT (`PRIVATE-TOKEN`).
- **Admin Mode** (self-managed/Dedicated only) restricts admins from holding
  admin privileges by default. When enabled, admin endpoints require a PAT with
  the `admin_mode` scope — without it, an otherwise-valid token returns **403**.

```bash
# ✅ Do — admin token (api + admin_mode + sudo scopes) on a self-managed/Dedicated instance
export ADMIN_TOKEN="glpat-ADMIN_WITH_api_admin_mode_sudo"
curl --header "PRIVATE-TOKEN: $ADMIN_TOKEN" \
     "https://gitlab.example.com/api/v4/admin/users"            # needs admin_mode when Admin Mode is on
curl --header "PRIVATE-TOKEN: $ADMIN_TOKEN" --header "Sudo: jdoe" \
     "https://gitlab.example.com/api/v4/projects"               # returns projects visible to jdoe
```

### 2.6 Token expiry (self-managed)

Since GitLab 16.0 the UI no longer allows creating non-expiring tokens (max 365
days; 400 days behind the `buffered_token_expiration_limit` feature flag in
17.6+, **not** on Dedicated). The **admin setting** that re-enables/disables
this enforcement was introduced in **17.3** — these two facts are commonly
conflated *(as of 2026-05-29; reconfirm against your version)*. Forcing a
maximum lifetime (1–365 days) is **Ultimate-only**. Expired tokens return
**401**; inspect `api_json.log` for `meta.auth_fail_reason: token_expired`
(GitLab 17.2+).

---

## 3. REST API essentials

### 3.1 Pagination — offset vs keyset

**Offset (default):** `page=1`, `per_page=20`, max `per_page=100`. Response
headers: `x-page`, `x-per-page`, `x-next-page`, `x-prev-page`, `x-total`,
`x-total-pages`, plus a `Link` header (`rel=prev/next/first/last`).

> Critical gotcha: when a query would return **more than 10,000 records**,
> GitLab **omits** `x-total`, `x-total-pages`, and the `rel="last"` link. Code
> that treats an absent `x-total` as zero silently stops after page 1.

On self-managed the max offset (for endpoints that also support keyset) defaults
to **50,000**, configurable via Rails console
`Plan.default.actual_limits.update!(offset_pagination_limit: N)` (0 disables).

**Keyset** is O(1) per page at the DB layer regardless of collection size, and
avoids both the 10,000-record header gap and the offset ceiling. Activate with
`?pagination=keyset&order_by=<col>&sort=asc|desc`. Supported on (incomplete,
expanding): Groups, Projects, Users (keyset since GitLab 16.5; offset deprecated
for `/users` in 16.5), Audit Events (group/instance/project), Package Pipelines,
Project Jobs, Registry Repository Tags, Repository Trees, and Project Issues
(since GitLab 18.3). For endpoints without keyset support, fall back to offset.

The cursor mechanism is dual: `X-NEXT-CURSOR` / `X-PREV-CURSOR` response headers
carry the raw cursor, **and** the `Link` header carries ready-to-use next-page
URLs. The cursor form in the `Link` header depends on ordering: `id_after=N`
(id-ordered) or `cursor=<base64>` (other orderings, e.g. name). **Always follow
the `Link` `rel="next"` URL verbatim** rather than reconstructing cursor params.

```go
// ✅ Do — keyset pagination by following the Link header's rel="next" URL.
//   Cursor form (id_after=N vs cursor=<base64>) varies by order_by — don't hand-build it.
baseURL := "https://gitlab.example.com/api/v4/projects"
params := "?pagination=keyset&order_by=id&sort=asc&per_page=100"
for url := baseURL + params; url != ""; {
    req, _ := http.NewRequest("GET", url, nil)
    req.Header.Set("PRIVATE-TOKEN", token)
    resp, _ := http.DefaultClient.Do(req)
    // process resp.Body ...
    url = extractNextURL(resp.Header.Get("Link")) // parse rel="next"; "" when done
}
```

```go
// ❌ Don't — assume x-total is always present; it vanishes above 10,000 records.
resp, _ := http.Get("https://gitlab.example.com/api/v4/projects?per_page=100")
total := resp.Header.Get("x-total")                                  // "" when result set > 10,000
totalPages, _ := strconv.Atoi(resp.Header.Get("x-total-pages"))      // also absent → 0
// Code that loops `for p := 1; p <= totalPages; p++` silently processes only page 1.
```

### 3.2 Rate limits

**Self-hosted: rate limiting is DISABLED by default.** Do not assume any limit
exists unless an admin configured it under *Admin > Settings > Network > User and
IP rate limits*. When enabled, defaults are **7,200 authenticated req / 3,600 s
per user** and **3,600 unauthenticated req / 3,600 s per IP**. Admins can raise,
disable, or dry-run limits. A 429 returns the plain-text body `Retry later`
(customizable).

**Rate-limit headers** (corrected — the full set is six on all responses, plus
two on 429):

- On **all** responses: `RateLimit-Limit`, `RateLimit-Name` (which throttle
  fired — useful for diagnostics), `RateLimit-Observed`, `RateLimit-Remaining`,
  `RateLimit-Reset` (Unix timestamp).
- On **429 only**: `RateLimit-ResetTime` (RFC 2616 date) and `Retry-After`
  (seconds).

**gitlab.com** enforces hard per-endpoint limits (permanently active since the
April 2, 2025 rollout; the `rate_limit_groups_and_projects_api` feature flag was
removed in GitLab 18.1, so any configured self-managed values become permanent):

| Endpoint                                  | Authenticated      | Unauthenticated      |
| ----------------------------------------- | ------------------ | -------------------- |
| `GET /api/v4/projects`                    | 2,000 / 10 min     | 400 / 10 min per IP  |
| `GET /api/v4/projects/:id`                | 400 / min          | —                    |
| `GET /api/v4/groups`                      | 200 / min          | —                    |
| `GET /api/v4/groups/:id`                  | 400 / min          | —                    |
| `GET /api/v4/groups/:id/projects`         | 600 / min          | —                    |
| `GET /api/v4/users/:user_id/projects`     | 300 / min          | —                    |
| `GET /api/v4/users/:id`                   | 300 / 10 min       | —                    |
| `GET /api/v4/projects/:id/members/all`    | 200 / min          | —                    |
| `DELETE` group members                    | 60 / min           | —                    |

> The exact numbers above should be reconfirmed against the official
> `rate_limit_on_projects_api` / `rate_limit_on_groups_api` docs pages, which are
> authoritative *(as of 2026-05-29)*.

```go
// ✅ Do — read Retry-After on 429 (self-hosted windows can be 3,600 s, not 60).
func handleResponse(resp *http.Response) {
    if resp.StatusCode == 429 {
        retryAfter := resp.Header.Get("Retry-After")          // seconds
        throttle := resp.Header.Get("RateLimit-Name")         // e.g. throttle_authenticated_api
        log.Printf("rate limited by %s; retry after %ss", throttle, retryAfter)
        // parse retryAfter and back off accordingly; jitter recommended
    }
}
```

### 3.3 Error model

| Status | Meaning                                                             |
| ------ | ------------------------------------------------------------------ |
| 200    | GET/PUT/PATCH/DELETE success                                       |
| 201    | POST created a resource                                            |
| 202    | Async work scheduled                                              |
| 204    | Success, no body                                                  |
| 301    | Resource moved (follow `Location`)                                |
| 400    | Bad request / missing attribute                                   |
| 401    | Unauthenticated (e.g. expired token)                              |
| 403    | Forbidden / insufficient scope / Admin Mode not satisfied         |
| 404    | Not found **or** access denied to a private resource              |
| 405    | Method not allowed                                                |
| 409    | Conflict                                                          |
| 412    | Precondition failed                                               |
| 422    | Unprocessable entity                                              |
| 429    | Rate limited                                                       |
| 500/503| Server error / service unavailable                               |

Error JSON shapes:

```json
// missing attribute
{ "message": "400 (Bad request) \"title\" not given" }
// validation failure
{ "message": { "name": ["has already been taken"] } }
```

GitLab returns **404 instead of 403** for private resources the caller cannot
see (prevents existence disclosure). Every response carries an `x-request-id`
correlation UUID — log it; it is the single most useful artifact for asking an
admin to trace a request in server logs.

### 3.4 URL-encoded project IDs & paths

Namespaced project paths must URL-encode `/` as `%2F`. File paths in repository
endpoints also require encoding (`src%2FREADME.md`). ISO 8601 `+` in query
params needs `%2B`. Follow redirects (`301`) for moved projects rather than
hardcoding URLs.

```bash
# ✅ Do — encode the namespace separator before placing it in the path
PROJECT="mygroup/mysubgroup/myrepo"
ENCODED=$(python3 -c "import urllib.parse,sys; print(urllib.parse.quote(sys.argv[1], safe=''))" "$PROJECT")
curl --header "PRIVATE-TOKEN: $TOKEN" \
     "https://gitlab.example.com/api/v4/projects/$ENCODED"   # → .../projects/mygroup%2Fmysubgroup%2Fmyrepo
```

> **Self-hosted reverse-proxy trap:** Apache/nginx may decode `%2F` before
> forwarding, turning `/projects/group%2Fproject` into `/projects/group/project`
> → a **404 with no encoding hint**. Apache fix: `AllowEncodedSlashes NoDecode`
> on the VirtualHost **and** `nocanon` on `ProxyPass`. nginx generally handles
> this correctly with standard `proxy_pass`. Protocol mismatch (HTTP vs HTTPS
> between proxy and GitLab) also produces internal 404s.

### 3.5 `id` vs `iid`

Issues and merge requests have both a globally-unique `id` and a project-scoped
`iid` (the `#42` / `!42` number shown in the UI). Single-resource endpoints use
**`iid`**: `GET /projects/:id/issues/:issue_iid`,
`GET /projects/:id/merge_requests/:merge_request_iid`. List endpoints return the
global `id`; cache both to avoid re-fetching.

```bash
# ✅ Do — look up an MR/issue by its UI number (iid), not the global id
MR_IID=42
curl --header "PRIVATE-TOKEN: $TOKEN" \
     "https://gitlab.example.com/api/v4/projects/123/merge_requests/$MR_IID"
```

### 3.6 Key endpoints

- **Projects:** `GET /projects`, `GET /projects/:id`, `GET /projects?membership=true`.
- **Merge requests / Issues / Commits / Pipelines / Jobs:** under
  `/projects/:id/...`.
- **Repository files:** `GET /projects/:id/repository/files/:file_path` returns
  Base64 content (`encoding: "base64"`); `/raw` returns raw bytes; `HEAD`
  returns metadata only (defaults to the default branch). The `ref` param
  accepts branch, tag, or SHA. Rate limits: blobs >10 MB → 5 req/min;
  create/update >20 MB → 3 req per 30 s; max request size 300 MB.
- **Members:** `GET /projects/:id/members` (direct only) vs
  `GET /projects/:id/members/all` (direct + inherited + invited). Access levels:
  No access=0, Minimal=5, Guest=10, Planner=15, Reporter=20, Security
  Manager=25, Developer=30, Maintainer=40, Owner=50.
- **Job artifacts:** `GET /projects/:id/jobs/:job_id/artifacts` (full archive);
  `GET /projects/:id/jobs/:job_id/artifacts/*artifact_path` (single file);
  by-ref archive `GET /projects/:id/jobs/artifacts/:ref_name/download?job=<name>`;
  by-ref single file
  `GET /projects/:id/jobs/artifacts/:ref_name/raw/*artifact_path?job=<name>`
  (note the `/raw/` segment). The `job_token` query-param auth for artifacts is
  **Premium/Ultimate-only on gitlab.com**.

```bash
# ❌ Don't — on gitlab.com, artifact downloads may 302 to a CDN; without -L you save the redirect HTML
curl --header "PRIVATE-TOKEN: $TOKEN" \
     "https://gitlab.com/api/v4/projects/123/jobs/456/artifacts" --output artifacts.zip
```

```bash
# ✅ Do — always follow redirects for artifact downloads
curl --location --header "PRIVATE-TOKEN: $TOKEN" \
     "https://gitlab.com/api/v4/projects/123/jobs/456/artifacts" --output artifacts.zip
```

> On self-hosted backed by local storage / MinIO, artifacts are usually served
> directly without a redirect — but always follow redirects anyway (Go's
> `http.DefaultClient` does this automatically).

### 3.7 CI job tokens

`CI_JOB_TOKEN` is short-lived (job duration) and works only with a restricted
endpoint set (Branches, Tags, Commits, raw Files, Merge Requests GET,
Deployments, Environments, Releases, Packages, own-project Container Registry,
Job Artifacts download, `GET /job`). Cross-project access requires adding the
caller to the target project's job-token allowlist; self-hosted admins can
enforce this instance-wide. The exact endpoint list shifts between releases —
check the CI/CD job-token docs for your version.

```bash
# ✅ Do — use the JOB-TOKEN header inside a CI job (never a PAT in CI scripts)
curl --request GET --header "JOB-TOKEN: $CI_JOB_TOKEN" \
     "https://gitlab.example.com/api/v4/projects/123/releases"

curl --request PUT --header "JOB-TOKEN: $CI_JOB_TOKEN" --upload-file package.zip \
     "https://gitlab.example.com/api/v4/projects/123/packages/generic/myapp/1.0.0/package.zip"
```

---

## 4. GraphQL API essentials

GraphQL is versionless, schema-driven, served from `/api/graphql` (POST), and
available on all tiers (Free/Premium/Ultimate) across gitlab.com, Self-Managed,
and Dedicated. It became always-on in **GitLab 12.1**. *(The "introduced 11.0
behind a feature flag" framing is unverified; the always-on 12.1 milestone is
confirmed — reconfirm earlier history against changelogs.)*

### 4.1 Endpoint, explorer, auth

- Endpoint: `POST https://<host>/api/graphql` with `Content-Type:
  application/json`.
- Explorer (GraphiQL): `https://<host>/-/graphql-explorer`.
- Auth: same tokens as REST. `Authorization: Bearer <token>` works for PAT and
  OAuth; `PRIVATE-TOKEN` also works for PATs.

```bash
# ✅ Do — minimal GraphQL request against a self-hosted instance
curl --silent --request POST \
  --header "Content-Type: application/json" \
  --header "Authorization: Bearer $GITLAB_TOKEN" \
  --data '{"query":"{ currentUser { username } }"}' \
  "https://gitlab.example.com/api/graphql"
```

### 4.2 Limits & deprecation

- **Query complexity:** unauthenticated capped at **200**, authenticated at
  **250**. This is a hard ceiling — over-limit queries are **rejected outright**,
  not throttled. There is no documented way to raise it on gitlab.com; on
  self-managed it would require patching `gitlab_schema.rb` (hardcoded values,
  not admin-configurable in the UI — *plausible but unverified, reconfirm*).
- **Query character limit:** 10,000 characters. **Request timeout:** 30 s.
  **Max page size per connection:** 100 nodes. **Multi-blob requests:** capped
  at 20 MB total.
- Inspect your own cost with `queryComplexity { score limit }` inside any query.
- **Deprecation policy:** deprecated schema items remain for at least six
  releases before removal at the next major (`XX.0`). Test against the
  deprecation-stripped schema via `?remove_deprecated=true` (URL) or
  `remove_deprecated: true` (body).

```graphql
# ✅ Do — co-request the complexity score during development to stay under 250
query {
  queryComplexity { score limit }
  project(fullPath: "mygroup/myproject") {
    name
    issues(first: 20) { nodes { iid title } }
  }
}
```

### 4.3 Global IDs

Every object identifier is a **Global ID**: `gid://gitlab/ObjectType/DatabaseID`
(e.g. `gid://gitlab/Issue/27039960`). Database integers are never exposed
directly. Treat Global IDs as **opaque strings** — the internal structure is not
a stable contract, and IDs like work-item type IDs are **not** guaranteed
identical across instances/namespaces.

### 4.4 Connections & cursor pagination

Collections follow the Relay Cursor Connections spec: they expose
`pageInfo { endCursor hasNextPage }` and accept `first`, `last`, `after`,
`before`. Default and max page size is **100**. Cursors are Base64-encoded JSON —
opaque; do not parse them. Prefer `nodes {}` over `edges { node {} }` when you
don't need per-node cursors (one less nesting level, fewer tokens).

```graphql
# ✅ Do — cursor-paginated, token-minimal field selection
query FetchIssues($cursor: String) {
  project(fullPath: "mygroup/myproject") {
    issues(first: 50, after: $cursor, state: opened) {
      nodes {
        iid
        title
        state
        assignees { nodes { username } }
      }
      pageInfo { hasNextPage endCursor }
    }
  }
}
```

```bash
# ✅ Do — glab advances $endCursor automatically with --paginate.
#         The query MUST declare $endCursor: String and return pageInfo { hasNextPage endCursor }.
glab api graphql --paginate -f query='
query($endCursor: String) {
  project(fullPath: "mygroup/myproject") {
    issues(first: 50, after: $endCursor, state: opened) {
      nodes { iid title }
      pageInfo { hasNextPage endCursor }
    }
  }
}'
```

### 4.5 Queries that replace many REST calls

```graphql
# ✅ Do — one round-trip replaces ~4 REST calls (project + issues + MRs + pipelines)
query ProjectSnapshot($path: ID!) {
  project(fullPath: $path) {
    id
    name
    visibility
    openIssuesCount
    statistics { commitCount repositorySize }
    mergeRequests(state: opened, first: 10) {
      nodes { iid title draft author { username } }
    }
    pipelines(first: 5, status: RUNNING) {
      nodes { id status createdAt }
    }
  }
}
```

### 4.6 Mutations — Work Items (new Epic features)

New Epic features (assignees, health status, cross-type linked items) are
available **only** via `workItemCreate` / `workItemUpdate` since GitLab 17.2;
the legacy `createEpic` mutation and Epic REST API are deprecated and slated for
removal in GitLab 19.0. Work Items GA'd in **18.1**.

> The example below corrects three common mistakes: (1) the Epic
> `workItemTypeId` is **not** stable — query it dynamically, never hardcode
> `gid://gitlab/WorkItems::Type/0`; (2) the assignees widget uses **`assigneeIds`**
> (Global IDs), not `assigneeUsernames`; (3) the health-status enum is camelCase
> **`onTrack`** (also `needsAttention`, `atRisk`), not `ON_TRACK`.

```graphql
# Step 1 — resolve the Epic work-item type ID for THIS namespace (do not hardcode)
query GetEpicTypeId($namespacePath: ID!) {
  namespace(fullPath: $namespacePath) {
    workItemTypes { nodes { id name } }   # pick the node where name == "Epic"
  }
}
```

```graphql
# Step 2 — create the work item with the resolved type ID
mutation CreateEpicWorkItem(
  $projectPath: ID!, $title: String!,
  $epicTypeId: WorkItemsTypeID!, $assigneeId: GitLabUserID!
) {
  workItemCreate(input: {
    projectPath: $projectPath
    title: $title
    workItemTypeId: $epicTypeId
    hierarchyWidget: { parentId: null }
    assigneesWidget: { assigneeIds: [$assigneeId] }   # ✅ Global IDs, not usernames
    healthStatusWidget: { healthStatus: onTrack }      # ✅ camelCase enum
  }) {
    workItem { id title widgets { type } }
    errors
  }
}
```

### 4.7 GraphQL-only vs REST-only

| GraphQL-only (or stronger)                                             | REST-only (GraphQL gaps)                                         |
| ---------------------------------------------------------------------- | ---------------------------------------------------------------- |
| Work Items API (new Epic features, 17.2+; GA 18.1)                     | Label change history — `resource_label_events` not in GraphQL   |
| `aiAction` mutation (rate-limited: 160 calls / 8 h / user)             | Repository file CRUD (`/repository/files`)                      |
| Real-time subscriptions (Action Cable / WebSocket — frontend-oriented) | Wiki attachment (multipart) uploads                             |
| Vulnerability / security profile management                           | Certain admin system-hooks & instance settings                  |
| `CiLint` mutation (the legacy `lintCI` **query** field is deprecated 18.1) | Some MR filter params historically absent from GraphQL (parity ongoing) |

```graphql
# ❌ Don't — try to read label add/remove history via GraphQL; it is NOT exposed.
query {
  project(fullPath: "mygroup/myproject") {
    issue(iid: "42") {
      notes { nodes { body system } }   # system notes omit label events entirely
    }
  }
}
```

```bash
# ✅ Do — label history lives in REST only
curl --request GET \
  --header "Authorization: Bearer $GITLAB_TOKEN" \
  "https://gitlab.example.com/api/v4/projects/123/issues/42/resource_label_events"
```

### 4.8 Rate-limit headers caveat

GraphQL responses do **not** reliably return `RateLimit-*` / `Retry-After`
headers (open issues #352409, #365728). Agents must use **exponential backoff
with jitter** on HTTP 429 rather than header-driven throttling. REST does return
the headers (§3.2).

### 4.9 Introspection & version-graceful schemas (self-hosted)

- Introspection is enabled by default and **cannot currently be disabled** on
  self-managed (open issue #462626). Because GitLab is open source the schema is
  public regardless.
- Prefer fetching the pre-generated static schema to avoid spending complexity
  budget: `public/-/graphql/introspection_result.json` (full) and
  `public/-/graphql/introspection_result_no_deprecated.json` (stripped).
- The **`@gl_introduced(version: "X.Y.Z")`** directive lets one client target a
  range of self-managed versions: on backends older than the declared version
  the annotated node is **stripped from the query** (the consumer sees it as
  absent/null) rather than erroring. Constraints: it cannot be used on
  arguments, standalone fields, or fragments themselves, and annotated fields
  must have sibling selections. When targeting heterogeneous version fleets,
  explicitly handle null on newly introduced fields.
- Multiplex (a JSON array of operations in one POST) is supported server-side by
  graphql-ruby but is **not** documented for external consumers — treat as
  medium-confidence and test against your target version. (The claim that `glab`
  uses Apollo `batchKey` batching is **false** — `glab --paginate` fetches pages
  sequentially.)

---

## 5. REST vs GraphQL — decision guidance

| Dimension                  | REST v4                                   | GraphQL                                             |
| -------------------------- | ----------------------------------------- | --------------------------------------------------- |
| Field selection            | None — always full resource (~80 fields on a project) | Exact fields only                            |
| Multi-resource fetch       | N calls                                   | One round-trip can join issues+MRs+pipelines+stats  |
| Pagination                 | Offset (default) + keyset on some endpoints | Relay cursors everywhere                          |
| Rate-limit headers         | Returned (when enabled)                   | **Not reliable** — backoff on 429 only              |
| Version/parity stability   | Highest; safest for heterogeneous self-managed fleets | Parity varies by version/tier               |
| Coverage gaps              | label history, file CRUD, wiki uploads (REST has these) | label history, file CRUD, wiki uploads missing |
| Newest features            | Epics deprecated here                     | Work Items, AI actions land here first/exclusively  |

**Rules of thumb**

- **Self-hosted universal CLI default → REST.** Feature parity and stability
  across versions/tiers make REST the safer baseline. Route specific operations
  (label history, raw file CRUD, wiki attachments) to REST always.
- **Read-heavy aggregation / token-sensitive agent reads → GraphQL.** Field
  projection plus single round-trip fan-out is the primary token-reduction lever
  (a 3-field project query returns <1% of the JSON a `GET /projects/:id`
  returns).
- **New Epic / Work Item / AI workflows → GraphQL** (no REST equivalent).
- No ETag/`If-None-Match` 304 caching is documented for most REST endpoints —
  don't rely on conditional requests to shrink payloads.

### Token / LLM-agent efficiency

```bash
# ❌ Don't — fetch the whole project object in REST when you need 3 fields (~80 returned)
curl --header "Authorization: Bearer $GITLAB_TOKEN" \
     "https://gitlab.example.com/api/v4/projects/mygroup%2Fmyproject"
```

```graphql
# ✅ Do — GraphQL field projection returns only what the agent's context needs
query { project(fullPath: "mygroup/myproject") { name visibility openIssuesCount } }
```

Additional agent guidance:

- Always set `per_page=100` on REST list ops to cut round trips.
- Prefer **keyset** over offset for large REST traversals (avoids the
  10,000-record `x-total` gap and the 50,000 offset ceiling).
- REST has **no field projection** — if payload size dominates token budget and
  the data exists in GraphQL with adequate parity, use GraphQL.
- Use POST JSON bodies (not query strings) for large/many parameters to avoid
  `414 Request-URI Too Large`; set `Content-Type: application/json`.
- Avoid global `GET /search` with `scope=blobs`/`commits` on large instances —
  it needs Elasticsearch/Advanced Search and otherwise returns 400 or empty.
  Prefer `GET /projects/:id/repository/files/:file_path`.
- Log `x-request-id` alongside agent actions for traceability.
- Prefer webhooks / `glab ci watch` over polling pipelines in a loop (each poll
  burns tokens and rate-limit budget).
- `glab api --paginate --output ndjson` emits one JSON object per line —
  stream-friendly for incremental agent parsing.

---

## 6. Self-hosted notes (cross-cutting)

1. **Base URL is configurable, not fixed** — driven by `external_url`. Store
   `https://<host>` without trailing slash; append `/api/v4/`, `/api/graphql`,
   `/api/v4/mcp`.
2. **Rate limits disabled by default** — assume none until an admin enables
   them; the per-endpoint Projects/Groups/Users limits default to 0 through
   GitLab 18.0 and become permanent once the feature flag is removed in 18.1.
3. **Offset ceiling** defaults to 50,000 (Rails console configurable; 0
   disables).
4. **Impersonation tokens** and **Admin Mode / `admin_mode` scope** exist on
   Self-Managed and Dedicated, never on gitlab.com — gate these on an
   `isSelfHosted`/Dedicated flag.
5. **Reverse-proxy URL-encoding** — Apache needs `AllowEncodedSlashes NoDecode`
   + `nocanon`; otherwise `%2F` paths silently 404.
6. **TLS / internal CA** — trust the CA, never disable verification. For Go-based
   tools (incl. `glab`), `SSL_CERT_FILE` / `SSL_CERT_DIR` and the OS trust store
   are honored. `glab config set ca_cert <path> --host <hostname>` works on
   `glab` ≥ v1.58.0 *(version from secondary sources; reconfirm)* — note the flag
   is `--host`, not `--hostname`, on `glab config set`.
   ```bash
   # ❌ Don't — disabling TLS verification leaks tokens to MITM
   export GIT_SSL_NO_VERIFY=true
   # ✅ Do — trust the internal CA at the OS level (durable, covers all Go binaries)
   sudo cp internal-ca.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
   ```
7. **Air-gapped** — `glab config set check_update false --global` (prevents
   startup hang reaching gitlab.com) and `GLAB_SEND_TELEMETRY=false`. OAuth web
   and device flows need connectivity; PAT login (`--token` / `GITLAB_TOKEN`) is
   the only offline-viable auth.
8. **Proxies** — `glab`'s Go HTTP client honors `HTTP_PROXY`, `HTTPS_PROXY`,
   `NO_PROXY` (and lowercase). There is **no** dedicated `glab config` proxy key.
9. **Maintenance window** — security fixes cover only N-2 minor versions; bug
   fixes only the current minor. Instances older than ~3 months may lack
   documented endpoints. Always probe `GET /api/v4/version` and guard against 404
   on newer features.
10. **CE vs EE gates relevant to tooling:** service accounts require EE (still
    unavailable on CE/FOSS — but GA on Free **EE** up to 100 as of GitLab 18.11
    *(reconfirm)*); maximum PAT-lifetime enforcement is Ultimate-only;
    project/group access tokens are gitlab.com Premium/Ultimate but all-tier on
    self-managed/Dedicated; the official MCP server is Premium/Ultimate + Duo.

### glab auth (self-hosted)

```bash
# ✅ Do — pin glab to the self-managed instance; --api-host only if API host ≠ Git remote host
glab auth login \
  --hostname gitlab.example.com \
  --api-host gitlab.example.com \
  --api-protocol https \
  --git-protocol ssh

# Non-interactive (CI / scripted)
glab auth login --hostname gitlab.example.com --token "$GITLAB_TOKEN"
echo "$GITLAB_TOKEN" | glab auth login --hostname gitlab.example.com --stdin   # equivalent
glab auth status --hostname gitlab.example.com
```

`glab` token precedence (high→low): `GITLAB_TOKEN` > `GITLAB_ACCESS_TOKEN` >
`OAUTH_TOKEN` > `CI_JOB_TOKEN` (when `GLAB_ENABLE_CI_AUTOLOGIN` is set) > stored
token in `~/.config/glab-cli/config.yml` (repo-local `.git/glab-cli/config.yml`
takes precedence; override dir with `GLAB_CONFIG_DIR`).

### go-gitlab client skeleton

```go
// Minimal go-gitlab setup against a self-hosted instance with a custom base URL.
import gitlab "gitlab.com/gitlab-org/api/client-go" // (formerly xanzy/go-gitlab)

client, err := gitlab.NewClient(
    os.Getenv("GITLAB_TOKEN"), // PAT — sent as PRIVATE-TOKEN
    gitlab.WithBaseURL("https://gitlab.example.com/api/v4"), // self-hosted base; lib appends paths
)
if err != nil { /* handle */ }

// Keyset-friendly listing: the client follows Link headers when you loop on the response.
opt := &gitlab.ListProjectsOptions{
    ListOptions: gitlab.ListOptions{Pagination: "keyset", PerPage: 100, OrderBy: "id", Sort: "asc"},
}
for {
    projects, resp, err := client.Projects.ListProjects(opt)
    if err != nil { break }
    _ = projects
    if resp.NextLink == "" { break }     // keyset: stop when no next Link
    opt.ListOptions.Pagination = "keyset"
    // for keyset, prefer driving the next request from resp.NextLink rather than incrementing Page
    break // illustrative
}
```

> `go-gitlab` is now `gitlab.com/gitlab-org/api/client-go`; the older import path
> `github.com/xanzy/go-gitlab` is archived. Use `WithBaseURL` to point at any
> self-hosted host; pass OAuth tokens with `gitlab.NewOAuthClient`. *(API surface
> evolves — reconfirm option/method names against the version you pin.)*

---

## 7. Sources

- https://docs.gitlab.com/api/rest/
- https://docs.gitlab.com/api/rest/authentication/
- https://docs.gitlab.com/api/rest/troubleshooting/
- https://docs.gitlab.com/user/profile/personal_access_tokens/
- https://docs.gitlab.com/security/tokens/
- https://docs.gitlab.com/api/oauth2/
- https://docs.gitlab.com/integration/oauth_provider/
- https://docs.gitlab.com/ci/jobs/ci_job_token/
- https://docs.gitlab.com/api/user_tokens/
- https://docs.gitlab.com/api/project_access_tokens/
- https://docs.gitlab.com/user/project/settings/project_access_tokens/
- https://docs.gitlab.com/api/repository_files/
- https://docs.gitlab.com/api/project_members/
- https://docs.gitlab.com/api/job_artifacts/
- https://docs.gitlab.com/administration/settings/user_and_ip_rate_limits/
- https://docs.gitlab.com/administration/settings/rate_limit_on_projects_api/
- https://docs.gitlab.com/administration/instance_limits/
- https://docs.gitlab.com/administration/logs/tracing_correlation_id/
- https://about.gitlab.com/blog/2024/05/14/rate-limitations-announced-for-projects-groups-and-users-apis/
- https://docs.gitlab.com/api/graphql/
- https://docs.gitlab.com/api/graphql/getting_started/
- https://docs.gitlab.com/development/api_graphql_styleguide/
- https://docs.gitlab.com/api/graphql/reference/
- https://docs.gitlab.com/api/graphql/removed_items/
- https://docs.gitlab.com/development/graphql_guide/pagination/
- https://docs.gitlab.com/api/graphql/epic_work_items_api_migration_guide/
- https://docs.gitlab.com/security/rate_limits/
- https://gitlab.com/gitlab-org/gitlab/-/issues/352409
- https://gitlab.com/gitlab-org/gitlab/-/issues/462626
- https://forum.gitlab.com/t/difference-between-rest-api-and-graphql-api-issue-discussion/69465
- https://docs.gitlab.com/cli/
- https://docs.gitlab.com/cli/auth/login/
- https://docs.gitlab.com/cli/config/
- https://docs.gitlab.com/cli/api/
- https://docs.gitlab.com/administration/settings/account_and_limit_settings/
- https://docs.gitlab.com/administration/settings/sign_in_restrictions/
- https://docs.gitlab.com/user/profile/service_accounts/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/
- https://docs.gitlab.com/topics/offline/quick_start_guide/
- https://docs.gitlab.com/omnibus/settings/ssl/ssl_troubleshooting/
- https://docs.gitlab.com/policy/maintenance/
- https://docs.gitlab.com/api/admin/token/

Researched 2026-05-29; version-specific details should be reconfirmed.
