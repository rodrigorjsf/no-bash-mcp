# GitLab Integration Surfaces — Comparison & Decision Guide

The decision/benchmark document for choosing **how** a tool (or agent) talks to GitLab. Five surfaces are compared: the **`glab` CLI**, the **raw REST API (v4)**, the **GraphQL API**, the **official GitLab MCP server** (`/api/v4/mcp`), and **community MCP servers**. It also benchmarks the GitLab ecosystem against **`gh`** (GitHub CLI) — the bar a future Go tool must clear. **Self-hosted (GitLab Self-Managed and Dedicated) is the priority audience throughout; `gitlab.com` is the secondary case.**

> **Accuracy note.** Facts below are tagged where the underlying research was *corrected* or *unverified* during fact-checking. Versions, tier gating, tool counts, and maturity labels (experiment vs. beta vs. GA) move fast. Treat anything marked "as of 2026-05-29; reconfirm against your instance/version" as a snapshot, not a contract. Researched 2026-05-29; version-specific details should be reconfirmed.

> **Companion docs.** [`glab.md`](./glab.md) and [`gitlab-api.md`](./gitlab-api.md) hold the deep references for the CLI and the HTTP APIs respectively. This file is the cross-surface decision layer.

---

## 1. Executive Summary — Which Surface, When

| If you need… | Use | Why |
|---|---|---|
| Common dev workflows with stored auth (open MR, list issues, view pipeline) | **`glab` CLI** | Credential-aware front door; `--output json` + `--jq`; zero per-call auth plumbing. |
| A field or endpoint no high-level command surfaces | **`glab api`** → REST or `graphql` | Universal escape hatch; reuses the same session. |
| Token-minimal structured reads (only the fields you want) | **GraphQL** (`/api/graphql`) | Field selection eliminates fat REST payloads; one round-trip joins many resources. |
| Broad, version-stable coverage on self-hosted | **REST v4** | Sole active API version; identical across versions; widest endpoint coverage. |
| Write actions guided by a typed schema, inside an MCP-speaking client, Premium/Ultimate available | **Official MCP** (`/api/v4/mcp`) | Smallest tool catalog (15 tools); OAuth DCR; first-party. |
| MCP coverage on **Free tier** or against many self-hosted instances | **Community MCP** (PAT-auth) | Work on any tier without Duo; far broader tool surface; per-instance routing. |
| Headless / CI / M2M automation | **`glab` CLI** or **REST/GraphQL** with a PAT or `CI_JOB_TOKEN` | Official MCP has **no PAT support** (issue #586184) — OAuth DCR only. |

**One-line heuristic:** read-heavy → CLI + `jq` or GraphQL; write-with-schema-guidance → MCP (if licensed and not headless); anything not covered → `glab api`.

---

## 2. Capability Matrix

Scores are relative across the five surfaces for the stated dimension. "SH" = self-hosted.

| Dimension | `glab` CLI | REST v4 | GraphQL | Official MCP | Community MCP |
|---|---|---|---|---|---|
| **Auth model** | Stored cred + env (`GITLAB_TOKEN`), `--stdin`, CI auto-login | PAT / project / group / OAuth / `CI_JOB_TOKEN` / impersonation (SH) | Same token types as REST | **OAuth 2.0 DCR only**; no PAT (issue #586184) | PAT (most), per-connection OAuth (yoda-digital), `CI_JOB_TOKEN` (zereight) |
| **Self-hosted support** | First-class (`--hostname` / `GITLAB_HOST`) | First-class (base URL = `external_url`) | First-class (identical to .com) | Yes, **18.6+**, needs Premium/Ultimate + Duo enabled | Yes, any tier, single env var (`GITLAB_API_URL` / `GITLAB_URL`) |
| **Free-tier on SH** | Yes | Yes | Yes | **No** (Premium/Ultimate + Duo) | **Yes** |
| **Coverage (breadth)** | High (mirrors `gh` + GitLab-native groups) | Widest (all v4 endpoints) | High; some REST-only gaps | **Narrow** (15 tools) | Very high (40–1,031 tools) |
| **Scripting / JSON** | `--output json` / `ndjson` + `--jq`; no `--json` field-list, no `--template` | JSON always (full object); no sparse fieldsets | Field selection (best) | Structured tool I/O (JSON) | Structured tool I/O (JSON) |
| **Agent-action reliability** | High (deterministic; 100% in CLI-vs-MCP benchmark) | High (deterministic) | High (deterministic) | Medium (~72% in benchmark; schema guides writes) | Medium (varies; schema guides writes) |
| **Token cost (per agent turn)** | Lowest (no persistent schema) | Low (response size only) | Lowest for reads (field selection) | Low–medium (15 schemas) | Medium–very high (linear in tool count) |
| **Setup friction** | Low (one binary, `auth login`) | Low (token + curl) | Low–medium (learn schema/cursors) | Medium (OAuth DCR, Duo, beta toggles) | Medium (npx/Docker/binary + env) |
| **Maturity** | GA | GA | GA (versionless) | **Beta** (as of 18.11; not GA) | Varies; no SLA |

> Tier/version cells reconfirm against your instance — official MCP gating and tool counts in particular drift between minor releases (as of 2026-05-29).

---

## 3. The `gh`-Parity Bar — What the Future Go Tool Must Hit

`gh` (GitHub CLI) is the gold standard for composable, agent-friendly CLI integration. It is a standalone Go binary (not a `git` wrapper) whose core contract is: **TTY auto-detection** (human output to a terminal; tab-delimited, no ANSI, no truncation when piped — *with no flag*), a universal `gh api` escape hatch, and a first-class extension system. The table below is the concrete bar.

| `gh` capability | What it does | `glab` today (as of 2026-05-29) | Gap the Go tool must close |
|---|---|---|---|
| `--json field1,field2` | Sparse field projection; invalid field name **enumerates all valid fields** (self-documenting schema). `gh pr list --json` exposes **47 fields** (corrected from "47+"). | **No** per-command `--json` field-list. `--output json` returns the **full object**; filter externally with `--jq`. | Add `--json <field-list>` with field discovery on error. |
| `gh api --jq` | Embedded jq filter — **system `jq` binary not required**. | `glab api` has `--jq`/`--output`, but **lacks** `--jq` parity nuances, `--template`, `--slurp`, `--cache`, `--verbose`. *(Parity-gap framing unverified against current docs; reconfirm `glab api` flag surface.)* | Bring `api` filtering/rendering/caching to parity. |
| `gh api -t/--template` | Go templates + custom funcs: `tablerow`/`tablerender`, `timeago`, `truncate`, `hyperlink`, `autocolor`, plus 4 Sprig funcs (`contains`, `hasPrefix`, `hasSuffix`, `regexMatch`). *("Full Sprig library" was overclaimed — only 4 confirmed.)* | **No** `--template`. | Add templated output with columnar table funcs. |
| `gh api --paginate` (REST + GraphQL) | Auto-fetch all pages; `--slurp` collects pages into one JSON array. | `glab api --paginate` exists (REST sequential; GraphQL needs `$endCursor` + `pageInfo`). **No `--slurp`.** | Add `--slurp`; keep cursor auto-advance. |
| `gh extension install/browse/search` | Any repo named `gh-NAME` becomes `gh NAME`; bash or precompiled (Go via `cli/go-gh`). | **No extension system.** Tracked in issue #1053 (open since 2022, unimplemented). | Ship a first-class extension system + a Go SDK. |
| Exit-code contract | `0` success, `1` general, `2` cancel, `4` auth-required — small, stable, scriptable. | Exit codes not documented as a stable contract; **no structured error JSON** (umbrella issue #8177). | Define a stable exit-code set + machine-readable errors. |
| `gh auth token` | Prints active token to stdout for piping to `curl`/`docker login`/`helm`. | `glab auth status --show-token` exists; piping ergonomics weaker. | Add `auth token [--hostname]` → stdout. |
| Multi-host | `--hostname`, `GH_HOST`, `GH_ENTERPRISE_TOKEN`; `gh config set k v --host h`. | `--hostname` / `GITLAB_HOST` / per-host config — **broadly at parity**. | Mostly met; keep per-command `--hostname` override. |

**The three load-bearing gaps:** (1) per-command `--json` field projection with self-documenting schema; (2) a first-class extension system (`gh`'s superpower for team-distributed subcommands); (3) `gh api`'s `--jq`/`--template`/`--slurp`/`--cache` rendering pipeline. `glab` already matches `gh` on multi-host config and the `api` escape-hatch concept.

```bash
# ✅ Do (gh): self-documenting field discovery — typo a field, get the full list back
gh pr list --json number,titel   # error lists every valid field name
```

```bash
# ❌ Don't (glab): expect gh-style sparse projection — glab returns the whole object
glab mr list --json iid,title    # not a glab flag; use --output json --jq instead
# ✅ glab equivalent:
glab mr list --output json --jq '[.[] | {iid, title, state}]'
```

---

## 4. Pros & Cons by Surface

### 4.1 `glab` CLI

**Pros:** credential-aware (auth once, reuse everywhere); `glab api` is the universal escape hatch reusing the same session; `--output json`/`ndjson` + `--jq`; lowest token cost for agents (no persistent schema injected); first-class self-hosted (`--hostname`, `GITLAB_HOST`, `--api-host`, `--api-protocol http` for internal TLS-less instances); CI auto-login (`GLAB_ENABLE_CI_AUTOLOGIN=true` reads `CI_JOB_TOKEN`, `CI_SERVER_FQDN`, `CI_SERVER_PROTOCOL`, `CI_SERVER_SHELL_SSH_HOST`).

**Cons:** no `--json` field-list, no `--template`, **no extension system** (issue #1053); inconsistent `--output json` coverage (some commands launch interactive TUIs — `glab ci status`, `glab ci view`); `glab ci trace` is always streaming/interactive; **no structured error JSON** (umbrella issue #8177); `GITLAB_HOST` doubled-scheme bug (use bare hostname, issue #592).

```bash
# ✅ Do: bare hostname (CLI prepends protocol internally)
export GITLAB_HOST=gitlab.example.com
glab config set api_protocol https --host gitlab.example.com
```

```bash
# ❌ Don't: include the scheme — produces https://https://… and a malformed URL
export GITLAB_HOST=https://gitlab.example.com   # doubled-scheme bug (#592)
```

### 4.2 REST API (v4)

**Pros:** sole active API version (v3 removed); widest endpoint coverage; behaviorally identical across GitLab versions (safest for heterogeneous SH fleets); rich auth (PAT, project/group tokens, `CI_JOB_TOKEN`, OAuth, impersonation on SH); keyset pagination for large collections.

**Cons:** **no sparse fieldsets** — always returns the full object (fat payloads; pipe through `jq`); offset pagination suppresses `x-total`/`x-total-pages` above 10,000 records (silent page-1-only bug if you treat absent headers as zero); namespaced paths must be URL-encoded (`%2F`); `id` vs `iid` confusion (single-resource lookups use **`iid`**); no general-purpose idempotency keys on mutations (dedupe client-side; idempotency keys exist only for webhook retries since 17.4).

```bash
# ✅ Do: token in PRIVATE-TOKEN header; URL-encode the namespace
curl --header "PRIVATE-TOKEN: $TOKEN" \
  "https://gitlab.example.com/api/v4/projects/mygroup%2Fmyrepo/merge_requests/42"
```

```bash
# ❌ Don't: token in the query string (logged by nginx/Apache/proxies) or unencoded slash
curl "https://gitlab.example.com/api/v4/projects/mygroup/myrepo?private_token=$TOKEN"  # leaks + 404
```

### 4.3 GraphQL API

**Pros:** field selection = best token efficiency for structured reads; one round-trip joins issues + MRs + pipelines + statistics (vs 4+ REST calls); versionless; `@gl_introduced` directive degrades gracefully on older SH backends (the node is stripped from the query, reading as absent/null — *mechanism is query-stripping, not a null sentinel*); `queryComplexity { score limit }` is introspectable; new capabilities (Work Items, AI) land here first.

**Cons:** hard complexity ceiling (200 unauth / 250 auth — a query over the cap is **rejected**, not throttled); **rate-limit headers not reliably returned** (issues #352409/#365728 — use exponential backoff on 429, not header-based); REST-only gaps (label change history `resource_label_events`, repository file CRUD, wiki attachments, some admin system-hooks); Global IDs (`gid://gitlab/Type/ID`) are opaque — never hardcode (e.g. Work Item type IDs vary per instance); mutations always need full `api` scope (not `read_api`).

```graphql
# ✅ Do: select only the fields the agent acts on; cursor-paginate
query($cursor: String) {
  project(fullPath: "mygroup/myrepo") {
    issues(first: 50, after: $cursor, state: opened) {
      nodes { iid title state }
      pageInfo { hasNextPage endCursor }
    }
  }
}
```

```graphql
# ❌ Don't: try to read label-change history via GraphQL — it's a known coverage gap
query { project(fullPath: "g/r") { issue(iid: "42") { notes { nodes { body system } } } } }
# system notes do NOT include label events. Use REST instead:
# GET /api/v4/projects/:id/issues/:iid/resource_label_events
```

### 4.4 Official MCP Server (`/api/v4/mcp`)

**Pros:** first-party, smallest catalog (**15 tools** — corrected from "14"), so smallest schema footprint of any GitLab MCP option; OAuth 2.0 Dynamic Client Registration (no static token to manage); zero install (built into GitLab); aggregated tools (e.g. `manage_pipeline`) reduce tool count; tool-name prefix header (`X-Gitlab-Mcp-Server-Tool-Name-Prefix`, 18.11+) disambiguates multiple instances.

**Cons:** **Beta**, not GA (as of 18.11); **Premium/Ultimate + Duo enabled + beta features toggled** (404 on `/api/v4/mcp` otherwise); **no PAT auth** (issue #586184) — blocks headless/CI/M2M; DCR apps get only the restricted `mcp` scope (pre-register an OAuth app with `api`/`read_api` as a workaround — undocumented); `mcp-remote ≥ 0.1.27` scope bug (`--static-oauth-client-metadata '{"scope":"mcp"}'` workaround); tool annotations (`readOnlyHint`/`destructiveHint`) **not yet set** on tools (issue #585082) — no annotation-based governance; `semantic_code_search` needs a vector store + AI Gateway + completed indexing (silently empty otherwise).

> **Do not conflate** the server-side `/api/v4/mcp` endpoint (OAuth, Premium/Ultimate) with `glab mcp serve` (CLI-based, **experimental**, PAT-capable, stdio). They are separate products. The "15 tools / Premium-Ultimate" facts belong to `/api/v4/mcp`; `glab mcp serve` exposes a smaller set over stdio. *(Earlier research conflated the two — corrected.)*

```json
// ✅ Do: HTTP transport to self-hosted, with a tool-name prefix (18.11+)
{ "mcpServers": { "gitlab": {
  "type": "http",
  "url": "https://gitlab.example.com/api/v4/mcp",
  "headers": { "X-Gitlab-Mcp-Server-Tool-Name-Prefix": "gl_" }
} } }
```

```json
// ❌ Don't: pass a PAT to /api/v4/mcp — not implemented (issue #586184); falls through to OAuth/401
{ "mcpServers": { "gitlab": {
  "type": "http",
  "url": "https://gitlab.example.com/api/v4/mcp",
  "headers": { "Authorization": "Bearer glpat-xxxx" }
} } }
```

### 4.5 Community MCP Servers

**Pros:** work on **any tier** without Duo (the only MCP path for SH Free); broad coverage (zereight ~156 tools, yoda-digital 86, mcpland 80+, ttpears **43** [corrected from ~40], jmrplens 866 CE / 1,025 EE); PAT auth (viable for M2M); progressive disclosure to fight context bloat (jmrplens `TOOL_SURFACE=dynamic` → 2 tools / ~20.5k tokens vs `individual` → ~491k tokens); enterprise policy engines (mcpland: read-only mode, allow/deny lists, project-ID scoping, capability disabling); multi-instance routing (zereight `ENABLE_DYNAMIC_API_URL` + `X-GitLab-API-URL`).

**Cons:** PAT lives in a third-party process (inherent exposure; 53% of MCP servers use static secrets, only 8.5% OAuth — Astrix 2025); historical CVEs (zereight 2.0.0–2.0.20 bound `0.0.0.0` by default — AIKIDO-2025-11000, fixed 2.0.21); no SLA / informal disclosure; feature flags default **off** (zereight `USE_PIPELINE`/`USE_MILESTONE`/`USE_GITLAB_WIKI`) so tools silently absent; no native `semantic_code_search` (text search only); read-only env-var names differ per server (jmrplens `GITLAB_READ_ONLY`, yoda-digital `GITLAB_READ_ONLY_MODE` — not interchangeable).

```bash
# ✅ Do: network-exposed deployment uses OAuth mode (no static token held by the server)
docker run -d -e AUTH_MODE=oauth -e GITLAB_API_URL=https://gitlab.example.com/api/v4 \
  -e HOST=0.0.0.0 -e USE_STREAMABLE_HTTP=true -p 3000:3000 \
  ghcr.io/yoda-digital/mcp-gitlab-server:latest
```

```bash
# ❌ Don't: inject a PAT into a non-loopback bind — credential reachable from the whole network
docker run -d -e GITLAB_PERSONAL_ACCESS_TOKEN=glpat-xxxx -e HOST=0.0.0.0 \
  -e STREAMABLE_HTTP=true -p 3002:3002 zereight050/gitlab-mcp   # AIKIDO-2025-11000 class
```

> **Config gotchas (reconfirm):** zereight default `PORT` is **3002** (not 3000 — that's yoda-digital); `GITLAB_POOL_MAX_SIZE` default is **100**; jmrplens domain-grouped mode is `TOOL_SURFACE=meta` (**not** `meta-tools`); mcpland npm package is `gitlab-mcp` (**not** `mcpland-gitlab-mcp`). The "DCR-restricted-to-`mcp`-scope blocks community-server API calls" claim is **unverified** against any primary source — treat as speculative.

---

## 5. Token-Efficiency & LLM-Agent Ergonomics

The dominant cost in agent loops is **tokens, not latency** (MCP protocol overhead < 10 ms; GitLab API calls 50–500 ms). Every MCP tool's name + description + JSON schema is injected into **every** completion request before the first user turn — a persistent tax the CLI and direct APIs never pay.

### 5.1 Surface ranking (lowest → highest token cost per turn, equivalent read task)

| Rank | Surface | Per-turn token shape | Notes |
|---|---|---|---|
| 1 | `glab` CLI + `--jq` projection | ~200–3,000; **0 schema overhead** | Fetched only when called; project to minimal fields before the model sees data. |
| 1 (tie) | GraphQL with field selection | Variable; smallest for multi-field reads | Server returns only requested fields; ~5–10× less JSON than equivalent REST. |
| 2 | Direct REST + `jq` | Response size only; **0 schema overhead** | No sparse fieldsets — always `jq` before context. Keyset pagination for large sets. |
| 3 | Official MCP (15 tools) | ~3,000–6,000 schema (**estimate, unverified**) | Smallest MCP footprint; write-schema guidance aids mutation accuracy. |
| 4 | Community MCP w/ allow-list (ttpears 43 / yoda 86) | ~10k–50k schema | Token cost grows linearly with exposed tool count. |
| 5 | jmrplens **individual** mode (866–1,025 tools) | ~491,512 schema — **avoid** | Use `dynamic` (~20,488) or `meta` (Meta/Full = **105,478**) instead. |

### 5.2 Benchmark evidence

| Metric | Finding | Source posture |
|---|---|---|
| CLI vs MCP token ratio | "What language is this repo?": **1,365 (CLI)** vs **44,026 (MCP)** = 32× | Confirmed (Scalekit). GitHub MCP = 43 tools ≈ 42k tokens schema. |
| Cost at 10k ops/mo (Claude Sonnet 4) | **~$3.20 (CLI)** vs **~$55.20 (MCP)** ≈ 17× | Confirmed (Scalekit). |
| Success rate | **CLI 100%** vs **MCP ~72%** | Confirmed (Scalekit). |
| Tool-count → accuracy | **95%** at 4 tools/~1.2k tokens vs **71%** at 46 tools/~42k tokens | Confirmed (dev.to). *Tool count cited as 46 here vs 43 elsewhere — secondary-source drift; version-pin.* |
| Pre-fetch + de-bloat savings | **19–62%** effective-token reduction across 5 GitHub workflows (Auto-Triage 62%, Smoke 59%, Security 43%, Attribution 37%, Compiler 19%) | Confirmed (GitHub blog). *Earlier "43–62%" omitted the two lower workflows.* |
| MCP tool removal | ~**8–12 KB** saved per eliminated tool | Confirmed. |
| Effective-token weighting | Output tokens weighted **4.0×**; cache-read **0.1×** → minimizing model **output** is ~40× more valuable per token than minimizing input | Confirmed (GitHub blog). |

### 5.3 Ergonomic patterns

```bash
# ✅ Do: pre-fetch deterministic data OUTSIDE the agent loop; agent reads the file
glab ci status --output json > /tmp/pipeline.json   # zero tokens spent on the fetch
glab api "projects/42/issues?state=opened&per_page=100" --paginate --output ndjson \
  | jq -c '{iid, title, labels: [.labels[].name]}' > /tmp/issues.ndjson
```

```bash
# ✅ Do: make glab non-interactive and structured for agents
export GLAB_NO_PROMPT=true        # NO_PROMPT is deprecated since v2.0.0; both work for now
glab mr view 456 --output json
```

```bash
# ❌ Don't: call glab/REST inside the agent's tool loop for data you could pre-fetch
# Each tool call injects raw output into context; deterministic HTTP belongs in setup steps.
```

```json
// ❌ Don't: mount an 86-tool MCP server for a 2-tool workflow — ~84 schemas are pure waste
{ "mcpServers": { "gitlab": { "command": "npx", "args": ["-y","@yoda-digital/mcp-gitlab-server"] } } }
// ✅ Do: official 15-tool server, an allow-list, or jmrplens dynamic mode (2 visible tools).
```

**Reliability corollary:** for **mutations**, an MCP tool's typed schema guides the model to fill required fields correctly — worth the schema tax when write accuracy matters and the workflow is not headless. For **reads**, CLI + `jq` or GraphQL wins on both cost (7–32×) and reliability (100% vs ~72%).

---

## 6. State-of-the-Art Combined Usage — Request → Recommended Surface

The strongest setups **combine** surfaces: CLI/REST/GraphQL for deterministic reads outside the loop, MCP only where typed-write guidance earns its schema cost. "SH Free" = self-hosted Free tier (no Duo, no official MCP).

| Request type | Primary surface | Fallback / combination | Why |
|---|---|---|---|
| **Open an MR** | `glab mr create --fill --yes` (CI: `--fill-commit-body --remove-source-branch`) | REST `POST /projects/:id/merge_requests`; or official MCP `create_merge_request` if licensed | CLI is non-interactive, reuses auth, no schema tax. MCP only adds value via typed write-guidance inside an MCP client. |
| **Bulk-triage issues** | `glab api '.../issues?...' --paginate --output ndjson \| jq` for the read; `glab issue` (or REST) for label/assignee writes | GraphQL for the read if field selection matters; loop writes via REST | Read once cheaply, project to minimal fields, then mutate. **Dedupe client-side** (no idempotency keys). |
| **Read a file** | REST `GET /projects/:id/repository/files/:path/raw` (or `glab api .../raw`) | `/files/:path` (base64 in `content`) for metadata | File CRUD is **REST-only** (GraphQL gap). `/raw` avoids base64 overhead; follow redirects (`-L`) on .com CDN. |
| **Summarize a pipeline failure** | `glab ci get --output json` + `glab ci trace --pipeline-id N` (job log) | REST `GET /projects/:id/jobs/:id/trace`; official MCP `get_pipeline_jobs` + `manage_pipeline` | Avoid `glab ci status`/`view` TUIs in automation — pass `--pipeline-id`. Pre-fetch the trace, summarize from the file. |
| **Cross-resource snapshot** (project + open issues + running pipelines) | **GraphQL** single query | 4+ REST calls | One round-trip, only requested fields — biggest token win. |
| **Label change history** | **REST** `.../issues/:iid/resource_label_events` | — | GraphQL coverage gap; only REST exposes label events. |
| **Create/update an Epic (new fields)** | **GraphQL** `workItemCreate`/`workItemUpdate` | — | Work Items GraphQL only (GA 18.1). **Query the Epic `workItemTypeId` dynamically** (not stable across instances); assignees use `assigneeIds` (Global IDs); health enum is camelCase `onTrack`. |
| **Headless / CI / M2M** | `glab` (CI auto-login or `GITLAB_TOKEN`); REST/GraphQL with `CI_JOB_TOKEN`/PAT | `glab mcp serve` (PAT, experimental) for an MCP shape | Official `/api/v4/mcp` has **no PAT** (issue #586184) — unusable headless. |
| **MCP on SH Free tier** | Community MCP (ttpears / yoda-digital / jmrplens), PAT-auth | `glab mcp serve` (experimental, stdio) | Official MCP needs Premium/Ultimate + Duo. |
| **Multi-instance agent** | `glab -R` per call; or zereight `ENABLE_DYNAMIC_API_URL` + `X-GitLab-API-URL` | Official MCP with tool-name prefixes per instance | Without distinct prefixes, MCP tools collide and writes route to the wrong instance. |
| **Code search (semantic)** | Official MCP `semantic_code_search` (Premium/Ultimate + vector store + AI Gateway) | REST/GraphQL text search; community text search | Semantic ranking is GitLab-native only; community servers do text search. |

```bash
# ✅ Do: combined pattern — deterministic reads pre-fetched, then a guided write
glab ci get --output json --jq '{id,status,ref}'                 # read (cheap, outside loop)
glab api "projects/42/pipelines/789/jobs" --output json \
  | jq -r '.[] | select(.status=="failed") | .id'                # narrow to the failure
# …agent reads summarized JSON, then issues ONE create_issue (MCP) or POST /issues (REST).
```

```bash
# ❌ Don't: route a bulk read through a 156-tool MCP server inside the loop
# 156 schemas injected every turn; a paginated glab api + jq does the same read for ~1% of tokens.
```

---

## 7. Decision Flow (Self-Hosted First)

1. **Is it a common dev workflow with stored auth?** → `glab` high-level command.
2. **Does a high-level command not surface the field/endpoint?** → `glab api` (REST path) or `glab api graphql`.
3. **Is it a token-sensitive structured read joining multiple resources?** → **GraphQL** with explicit field selection (and a REST fallback for the known gaps: label history, file CRUD, wiki attachments).
4. **Is it a guided write inside an MCP-speaking client, Premium/Ultimate available, not headless?** → **Official MCP** (`/api/v4/mcp`), prefix-namespaced.
5. **Free tier, or many SH instances, or headless MCP needed?** → **Community MCP** (PAT) with read-only mode + project scoping + progressive disclosure; or `glab mcp serve` (experimental).
6. **Headless / CI / M2M?** → `glab` (CI auto-login) or REST/GraphQL with `CI_JOB_TOKEN`/PAT. **Never** the official MCP (no PAT).

---

## Sources

- glab: `https://docs.gitlab.com/cli/`, `https://docs.gitlab.com/cli/api/`, `https://docs.gitlab.com/cli/auth/login/`, `https://docs.gitlab.com/cli/mcp/serve/`, `https://github.com/gl-cli/glab`, `https://gitlab.com/gitlab-org/cli/-/issues/1053`, `https://gitlab.com/gitlab-org/cli/-/work_items/8177`, `https://github.com/profclems/glab/issues/592`
- REST v4: `https://docs.gitlab.com/api/rest/`, `https://docs.gitlab.com/api/rest/authentication/`, `https://docs.gitlab.com/api/rest/troubleshooting/`, `https://docs.gitlab.com/api/repository_files/`, `https://docs.gitlab.com/api/job_artifacts/`, `https://docs.gitlab.com/administration/instance_limits/`, `https://docs.gitlab.com/administration/settings/rate_limit_on_projects_api/`
- GraphQL: `https://docs.gitlab.com/api/graphql/`, `https://docs.gitlab.com/development/api_graphql_styleguide/`, `https://docs.gitlab.com/development/graphql_guide/pagination/`, `https://docs.gitlab.com/api/graphql/epic_work_items_api_migration_guide/`, `https://gitlab.com/gitlab-org/gitlab/-/issues/352409`
- Official MCP: `https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/`, `https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_tools/`, `https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_troubleshooting/`, `https://docs.gitlab.com/development/duo_agent_platform/mcp/`, `https://gitlab.com/gitlab-org/gitlab/-/issues/586184`, `https://gitlab.com/gitlab-org/gitlab/-/work_items/566965`, `https://gitlab.com/gitlab-org/gitlab/-/issues/585082`
- Community MCP: `https://github.com/zereight/gitlab-mcp`, `https://github.com/yoda-digital/mcp-gitlab-server`, `https://github.com/mcpland/gitlab-mcp`, `https://github.com/ttpears/gitlab-mcp`, `https://github.com/jmrplens/gitlab-mcp-server`, `https://intel.aikido.dev/cve/AIKIDO-2025-11000`, `https://github.com/detailobsessed/efficient-gitlab-mcp`, `https://astrix.security/learn/blog/state-of-mcp-server-security-2025/`
- `gh` parity: `https://cli.github.com/manual/gh_api`, `https://cli.github.com/manual/gh_pr_list`, `https://cli.github.com/manual/gh_help_formatting`, `https://cli.github.com/manual/gh_help_exit-codes`, `https://docs.github.com/en/github-cli/github-cli/using-github-cli-extensions`, `https://github.com/cli/go-gh`, `https://github.com/cli/cli/blob/trunk/docs/gh-vs-hub.md`
- Token efficiency: `https://www.scalekit.com/blog/mcp-vs-cli-use`, `https://dev.to/nebulagg/mcp-tool-overload-why-more-tools-make-your-agent-worse-5a49`, `https://github.blog/ai-and-ml/github-copilot/improving-token-efficiency-in-github-agentic-workflows/`, `https://about.gitlab.com/blog/give-your-ai-agent-direct-structured-gitlab-access-with-glab-cli/`

Researched 2026-05-29; version-specific details should be reconfirmed.
