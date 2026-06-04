# GitLab via the Model Context Protocol (MCP)

Implementation-grade reference for connecting LLM agents and IDE clients to
GitLab over the Model Context Protocol, written for **self-hosted (Self-Managed)**
GitLab first, with **gitlab.com** as the secondary case. Covers the official
first-party server, the dominant community servers, and the token/tool-count
overhead that determines whether MCP is even the right surface.

> Researched 2026-05-29. Almost everything below is version-, tier-, and
> edition-sensitive. Claims marked *(as of 2026-05-29; reconfirm against your
> instance/version)* are either fast-moving or only partially verified — re-check
> them against the specific GitLab version, edition, and server release you
> target. Always probe `GET /api/v4/version` before gating on a feature.

See also: [`gitlab-api.md`](./gitlab-api.md) (REST/GraphQL surface, token scopes)
and [`glab.md`](./glab.md) (CLI auth and self-hosted addressing).

---

## 1. What MCP means here, and why it matters for agents

The **Model Context Protocol (MCP)** is a JSON-RPC protocol that lets an LLM
client (Cursor, Claude Code, Claude Desktop, VS Code, JetBrains) discover and
call a server's *tools* during a conversation. For GitLab, an MCP server is the
adapter that turns "create an issue", "fetch this MR's diff", or "search the
codebase" into authenticated GitLab API calls — without the agent constructing
raw REST bodies or managing pagination by hand.

The key fact that governs every decision in this document: **every tool's name,
description, and JSON schema is injected into the model's prompt context before
the first user turn.** GitLab's own MCP developer guidelines state it plainly:

> "Every tool definition (name, description, JSON schema) is added to the model's
> prompt context, increasing input token count." … "Larger prompts create slower
> response times and higher API costs." … "Research indicates both increased
> context and more tools reduce agent accuracy."
> — <https://docs.gitlab.com/development/duo_agent_platform/mcp/>

So an MCP server is not free. A server exposing 86 tools pays ~86 schemas of
context tax on *every* completion request, whether or not the agent uses them.
This is the central trade-off: MCP gives a structured, schema-guided surface
that improves *mutation accuracy* (the model fills required fields correctly),
but for *read-heavy* work the `glab` CLI piped through `jq` is dramatically
cheaper (see [§7](#7-token--tool-count-overhead-the-candid-part)).

There are two distinct "official GitLab MCP" things, often conflated:

| Thing | What it is | Transport | Auth |
| --- | --- | --- | --- |
| **`/api/v4/mcp` server** | First-party server built *into the GitLab application*. The subject of [§2](#2-the-official-gitlab-mcp-server-apiv4mcp). | Streamable HTTP (native); stdio via proxy | OAuth 2.0 DCR only |
| **`glab mcp serve`** | A *separate*, experimental stdio server built into the `glab` CLI. Does **not** share code with `/api/v4/mcp`. See [§2.7](#27-glab-mcp-serve--the-separate-experimental-cli-server). | stdio only | `glab`'s existing auth (PAT) |

---

## 2. The official GitLab MCP server (`/api/v4/mcp`)

### 2.1 Status, version gating, and tier

The official server is built into GitLab itself and reachable at the well-known
path `/api/v4/mcp` on any GitLab host.

| Aspect | Value | Source confidence |
| --- | --- | --- |
| Introduced | **Experiment** in GitLab **18.3** (feature flags `mcp_server` + `oauth_dynamic_client_registration`, disabled by default) | confirmed |
| Promoted | **Beta** in GitLab **18.6** (flags removed, always-on for eligible instances) | confirmed |
| Current status | **Still beta** as of GitLab 18.11 (May 2026) — **not GA**, no GA date announced | confirmed |
| Tier | **Premium or Ultimate** on GitLab.com, Self-Managed, *and* Dedicated. **Not** on Free. Duo Core (bundled with Premium/Ultimate) is sufficient — neither Duo Pro nor Duo Enterprise is required for the server itself | confirmed |
| MCP protocol specs | `2025-03-26` and `2025-06-18` supported from GitLab **18.7** | confirmed |

> Note: it was an *experiment behind feature flags* in 18.3 — not a shipped
> feature. Treat **18.6** as the minimum for production-shaped use. On 18.3–18.5
> you must manually enable `mcp_server` and `oauth_dynamic_client_registration`
> via the Rails console.

> *(As of 2026-05-29; reconfirm against your instance/version.)* One search
> signal suggested Free-tier access may have arrived in 18.10; the official docs
> page still shows Premium/Ultimate only. Re-check tier gating against your exact
> version's docs.

### 2.2 Self-hosted enablement (the priority case)

The official server is fully available on **Self-Managed and Dedicated** from
18.6 onward. Two **admin-level, instance-global** prerequisites must both be met
before `/api/v4/mcp` responds — omitting either returns **HTTP 404** on the
endpoint (the single most common self-hosted setup failure):

1. **GitLab Duo enabled at the instance level.**
2. **Beta and experimental features turned on.**

Navigation (GitLab 17.4+ Self-Managed): **Admin** (upper-right) → **Settings** →
**GitLab Duo** → **Change configuration**.

```text
# ✅ Do — enable both toggles before connecting any client
# Admin > Settings > GitLab Duo > Change configuration
#   [x] GitLab Duo enabled for this instance
#   [x] Use experiment and beta GitLab Duo features   <- the "Feature preview" checkbox
# > Save changes
#
# Verify the endpoint now answers (should be a JSON MCP response, not 404):
curl -sS https://gitlab.example.com/api/v4/mcp
```

```text
# ❌ Don't — assume the endpoint is live just because GitLab is Premium/Ultimate
# If either toggle is off, /api/v4/mcp returns HTTP 404 with no useful hint.
# A 404 here almost always means "beta features not enabled", not "wrong URL".
```

The MCP endpoint URL is **always** `https://<your-gitlab-host>/api/v4/mcp` — no
extra path configuration. GitLab Dedicated follows the identical enablement
path. Admin Mode (Self-Managed/Dedicated) does not block the endpoint itself,
but admin-scoped tool actions inherit the caller's permissions.

> Roadmap signal *(as of 2026-05-29; reconfirm)*: GitLab has stated MCP settings
> will be "decoupled from Duo into a standalone configuration area", which would
> simplify this enablement. No timeline published.

### 2.3 Transport

| Transport | How | Notes |
| --- | --- | --- |
| **Streamable HTTP** (recommended) | Point the client directly at `/api/v4/mcp` with `type: "http"` | No Node.js, no proxy, lowest latency. The default and preferred path. |
| **stdio** (proxy) | `npx mcp-remote <url>` as an stdio→HTTP shim | Requires **Node.js 20+**. Needed for clients without native Streamable HTTP (e.g. Claude Desktop). Subject to the mcp-remote scope bug — see [§2.6](#26-known-bugs--limitations). |

### 2.4 Authentication: OAuth 2.0 DCR only (no PAT)

Authentication is **exclusively OAuth 2.0 Dynamic Client Registration (DCR,
RFC 7591)**. The self-managed instance acts as its own OAuth authorization
server; clients self-register on first connect. There is **no Personal Access
Token (PAT) support** on the official server as of May 2026 — PAT support is an
open feature request (issue
[#586184](https://gitlab.com/gitlab-org/gitlab/-/issues/586184)).

This is the single biggest constraint for **M2M / CI-CD / headless-agent**
workflows: there is no non-interactive credential path to `/api/v4/mcp`. Use
`glab mcp serve` (PAT, stdio) or a community server for automation.

A second, subtler limitation: dynamically-registered OAuth apps are granted only
the **`mcp` scope**, *not* `api` or `read_api`. The `mcp` scope is sufficient for
the MCP tools themselves but is a restricted scope. Pre-registering an OAuth
application with `api`/`read_api` scopes is the workaround, but this is
**undocumented** in official docs.

```json
// ❌ Don't — PAT auth against /api/v4/mcp does not work (issue #586184)
// As of GitLab 18.11 the endpoint ignores Authorization: Bearer <glpat-...>:
// it falls through to the OAuth flow or returns 401. Use glab mcp serve
// or a community server for PAT/M2M scenarios instead.
{
  "mcpServers": {
    "gitlab": {
      "type": "http",
      "url": "https://gitlab.example.com/api/v4/mcp",
      "headers": { "Authorization": "Bearer glpat-xxxxxxxxxxxxxxxxxxxx" }
    }
  }
}
```

### 2.5 Client configuration

**Cursor / VS Code** — `mcp.json` with an HTTP entry (no extra dependency):

```json
// ✅ Do — Cursor: ~/.cursor/mcp.json (or project-local .cursor/mcp.json)
{
  "mcpServers": {
    "gitlab": {
      "type": "http",
      "url": "https://gitlab.example.com/api/v4/mcp",
      "headers": {
        "X-Gitlab-Mcp-Server-Tool-Name-Prefix": "gl_"
      }
    }
  }
}
```

The optional `X-Gitlab-Mcp-Server-Tool-Name-Prefix` header (GitLab **18.11+**,
truncated to 32 chars) prefixes every tool name to avoid collisions when several
MCP servers (or several GitLab instances) are active at once.

**Claude Code** — register over HTTP via the CLI (handles OAuth natively, so it
sidesteps the mcp-remote scope bug entirely):

```bash
# ✅ Do — Claude Code stores config in ~/.claude/mcp.json
claude mcp add --transport http GitLab https://gitlab.example.com/api/v4/mcp
claude mcp list   # verify
```

**Claude Desktop** — lacks native Streamable HTTP, so it must proxy through
`mcp-remote` (stdio). Pass the scope override to dodge the mcp-remote 0.1.27+
bug:

```json
// ✅ Do — Claude Desktop: claude_desktop_config.json
{
  "mcpServers": {
    "GitLab": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote",
        "https://gitlab.example.com/api/v4/mcp",
        "--static-oauth-client-metadata", "{\"scope\": \"mcp\"}"
      ]
    }
  }
}
```

**GitLab Duo IDE clients (VS Code extension, JetBrains plugin)** use a separate
`mcp.json` to configure the IDE *as an MCP client* (connecting to external
servers — not the GitLab server itself):

- Lookup: `~/.gitlab/duo/mcp.json` (user-global) or
  `<workspace>/.gitlab/duo/mcp.json` (project-scoped).
- Overridable via `$GLAB_CONFIG_DIR` or `$XDG_CONFIG_HOME`.
- `type` field accepts `stdio`, `http`, or `sse`.
- `approvedTools`: `true` (auto-approve all) or an array of tool-name strings
  (pre-approve specific tools). *(As of 2026-05-29; reconfirm — an "omitted =
  manual per-session approval" state is implied by secondary docs but not spelled
  out as a third named state on the primary `mcp_clients` page.)*

**Debugging clients:**

- OAuth credentials cache in `~/.mcp-auth/mcp-remote*` — delete this directory to
  force re-authentication and clear stale-cache false positives.
- Cursor/VS Code: **View → Output → `MCP: <server-name>`**.
- `mcp-remote`: add `--debug`.

### 2.6 Known bugs & limitations

- **mcp-remote scope bug** (issues
  [#585699](https://gitlab.com/gitlab-org/gitlab/-/issues/585699) /
  [#566965](https://gitlab.com/gitlab-org/gitlab/-/work_items/566965)):
  mcp-remote **≥ 0.1.27** explicitly passes `&scope=mcp` in the OAuth
  authorization URL, which GitLab rejects with *"The requested scope is invalid,
  unknown, or malformed."* Affects GitLab.com and Self-Managed alike *(as of
  2026-05-29; confirm the workaround applies to all your Self-Managed
  versions ≥ 18.6)*. Workarounds: pass
  `--static-oauth-client-metadata '{"scope": "mcp"}'` (preferred), or pin
  mcp-remote to **0.1.26** (not recommended for security). Clear
  `~/.mcp-auth/mcp-remote*` to resolve stale-cache false positives.
- **No tool annotations yet** (issue
  [#585082](https://gitlab.com/gitlab-org/gitlab/-/issues/585082), open): GitLab's
  MCP implementation *supports* `ToolAnnotations` (`readOnlyHint`,
  `destructiveHint`, `idempotentHint`) from the protocol side (spec support added
  18.7), but the server does **not** annotate its own tools. Clients therefore
  **cannot** enforce read-only or confirmation policies via annotation-based
  governance with the official server.
- **No per-tool access control**: once a user authorizes the OAuth app, all tools
  are accessible at that user's permission level. There is no server-side way to
  expose a subset. *(As of 2026-05-29; issue #597664 appears to track tool-level
  access control — reconfirm its status.)*
- **`semantic_code_search` needs infrastructure** — see [§2.8](#28-the-semantic_code_search-caveat).
- **Protocol version**: clients on spec `2025-06-18` get "protocol version not
  supported" on GitLab ≤ 18.6 (spec support added 18.7).
- **Prompt injection** is an explicitly documented risk; use MCP tools only on
  trusted GitLab objects.
- **AI Catalog MCP servers** (18.10+, experimental) is a *separate* feature:
  admins register *third-party* MCP servers (Jira, Confluence) for custom AI
  Catalog agents. It is **GitLab.com-only**, HTTP-transport-only, and offers no
  selective tool restriction (all of an associated server's tools become
  available). Self-Managed cannot use it. *(As of 2026-05-29; reconfirm.)*

### 2.7 `glab mcp serve` — the separate, experimental CLI server

A **distinct** stdio MCP server built into the `glab` CLI. It does **not** share
code with `/api/v4/mcp`.

- **Experimental**, explicitly "might be unstable or removed at any time" /
  "not ready for production use."
- **stdio transport only.**
- Relies on `glab`'s existing auth → works with a **PAT** and **without** Duo or
  Premium. This makes it the practical fallback for **Free-tier**, **CE**,
  **air-gapped**, or **CI-CD M2M** scenarios where `/api/v4/mcp` is unavailable.
- Smaller tool set (issues, MRs, projects, pipelines/jobs).

```json
// ✅ Do — fallback for Free-tier / pre-18.6 / M2M (experimental, stdio)
{
  "mcpServers": {
    "glab": {
      "type": "stdio",
      "command": "glab",
      "args": ["mcp", "serve"]
    }
  }
}
```

> *(As of 2026-05-29; reconfirm.)* `GITLAB_TOKEN` / `GITLAB_HOST` are the standard
> `glab` env vars, but their explicit support in `glab mcp serve` specifically is
> **not** documented for that sub-command. Prefer configuring auth via
> `glab auth login` first, or check `glab mcp serve --help` for supported env
> vars, rather than injecting them in the MCP config.

### 2.8 The `semantic_code_search` caveat

`semantic_code_search` (experiment 18.5+, beta 18.7+, Premium/Ultimate only)
requires infrastructure most Self-Managed deployments will **not** have
preconfigured:

- A vector store: **Elasticsearch 8.0+**, **OpenSearch 2.0+**, or **PostgreSQL
  with the `pgvector` extension**.
- **GitLab AI Gateway** connectivity.
- A **completed code-embeddings indexing job** for the target project (can take
  hours on large repos).

`pgvector` is documented as suitable only for "setups with a few small
repositories" with "limited indexing and querying performance" — not for large
repos.

```text
# ❌ Don't — expose semantic_code_search to agents on a fresh Self-Managed instance
# The tool appears in the catalog but returns EMPTY results until:
#   - a vector store (Elasticsearch 8.0+ / OpenSearch 2.0+ / pgvector) is configured
#   - the AI Gateway is reachable
#   - the code-embeddings indexing job has actually completed
# Empty results are silent — they look like "no matches", not "not configured".
```

### 2.9 Official tool catalog (15 tools, as of GitLab 18.11)

> Count correction: the catalog has **15** tools (earlier drafts said 14;
> `semantic_code_search` is both listed and counted).

| Tool | Since | Notes |
| --- | --- | --- |
| `get_mcp_server_version` | 18.3 | health/version probe |
| `create_issue` | 18.3 | write |
| `get_issue` | 18.3 | read |
| `create_merge_request` | 18.5 | write |
| `get_merge_request` | — | read |
| `get_merge_request_commits` | — | read |
| `get_merge_request_diffs` | — | read |
| `get_merge_request_pipelines` | — | read |
| `get_pipeline_jobs` | — | read |
| `manage_pipeline` | 18.10 | aggregated CI/CD tool |
| `create_workitem_note` | 18.7 | write |
| `get_workitem_notes` | 18.7 | read |
| `search` | 18.4 | renamed from `gitlab_search` in 18.8; supports a `fields` array to limit returned fields |
| `search_labels` | 18.9 | read |
| `semantic_code_search` | experiment 18.5; beta 18.7 | needs vector store (see §2.8) |

All tools run at the caller's permission level and are Premium/Ultimate-gated.
`manage_pipeline` is the canonical **aggregated-tool** example — one tool
consolidates several related pipeline operations rather than registering each as
a separate tool. *(The "replaces ~5 tools / ~5x token savings" framing is an
editorial inference, not a sourced GitLab figure — treat it as illustrative.)*

---

## 3. Community GitLab MCP servers

The community ecosystem substantially **outpaces the official server** in tool
breadth, self-hosted flexibility, PAT support, and token-efficiency controls — at
the cost of trust, security review, and no GitLab SLA. **All** major community
servers support self-hosted instances via a single env-var base-URL override.

### 3.1 Server landscape

| Server | Lang / pkg | Tools | Self-hosted var | Standout trait |
| --- | --- | --- | --- | --- |
| **zereight/gitlab-mcp** | Node — `@zereight/mcp-gitlab` | **156** | `GITLAB_API_URL` (`…/api/v4`) | Most-installed (996K+ downloads); feature toggles; multi-instance routing |
| **yoda-digital/mcp-gitlab-server** | Node — `@yoda.digital/gitlab-mcp-server` | **86** | `GITLAB_API_URL` (`…/api/v4`) | Sigstore provenance; Helm chart; hard loopback guard in PAT mode |
| **mcpland/gitlab-mcp** | Node — `gitlab-mcp` | **80+** | `GITLAB_API_URL` (comma-sep multi) | Enterprise policy engine (allow/deny, capabilities, project scoping) |
| **ttpears/gitlab-mcp** | Node — `@ttpears/gitlab-mcp` | **43** (10 search / 20 read / 13 write) | `GITLAB_URL` (base, no path) | Any tier, no Duo; GraphQL introspection + custom queries |
| **jmrplens/gitlab-mcp-server** | Go static binary | **866 CE / 1025 EE** | `GITLAB_URL` (base) | Progressive disclosure (`TOOL_SURFACE`); air-gap-friendly single binary |
| `sgaunet/gitlab-mcp` | Go binary | ~14 | `GITLAB_URI` (base) | Minimal, stdio-only, targeted CI/CD log inspection |
| `mcp/gitlab` (Docker Hub) | Node — `@modelcontextprotocol/server-gitlab` | 9 | `GITLAB_API_URL` | **Archived** reference image; superseded |

> *(As of 2026-05-29; reconfirm — versions, tool counts, and global download
> ranks move fast.)* zereight latest stable was **v2.1.16** (2026-05-25);
> yoda-digital **v0.9.1** (2026-05-27); jmrplens **v2.0.5** (2026-05-22). The
> archived `mcp/gitlab` image needs **Docker Desktop ≥ 4.37.1** for automatic
> integration.

### 3.2 `@zereight/gitlab-mcp` — install & config (the dominant server)

```json
// ✅ Do — self-hosted zereight via npx (stdio), feature toggles explicit
{
  "mcpServers": {
    "gitlab": {
      "command": "npx",
      "args": ["-y", "@zereight/mcp-gitlab"],
      "env": {
        "GITLAB_PERSONAL_ACCESS_TOKEN": "glpat-xxxxxxxxxxxxxxxxxxxx",
        "GITLAB_API_URL": "https://gitlab.example.com/api/v4",
        "USE_PIPELINE": "true",
        "USE_MILESTONE": "true",
        "USE_GITLAB_WIKI": "true",
        "GITLAB_READ_ONLY_MODE": "false"
      }
    }
  }
}
```

**Key environment variables:**

| Variable | Purpose |
| --- | --- |
| `GITLAB_API_URL` | Self-hosted endpoint, **with `/api/v4`** suffix |
| `GITLAB_PERSONAL_ACCESS_TOKEN` | PAT auth |
| `GITLAB_JOB_TOKEN` | CI/CD job-token auth alternative |
| `GITLAB_READ_ONLY_MODE` | Hides **all write tools at registration time** (see caveat below) |
| `USE_PIPELINE` / `USE_MILESTONE` / `USE_GITLAB_WIKI` | Feature toggles — **default `false`**; omit and those tool categories are absent |
| `SSE` / `STREAMABLE_HTTP` | Transport: default **stdio**; SSE (legacy) or Streamable HTTP (remote) |
| `ENABLE_DYNAMIC_API_URL` + `REMOTE_AUTHORIZATION` | Per-request `X-GitLab-API-URL` header routing across multiple self-hosted instances (v2.1+, HTTP only) |
| `OAUTH_STATELESS_MODE` + `OAUTH_STATELESS_SECRET` | Shared state for multi-pod horizontal scaling |
| `HOST` / `PORT` | Bind address / port — **default `PORT=3002`** (not 3000) |
| `HTTP_PROXY` / `HTTPS_PROXY` / `GITLAB_CA_CERT_PATH` | Corporate proxy + custom CA |
| `GITLAB_POOL_MAX_SIZE` | Connection-pool size — **default 100** |

> Transports: **stdio** (default, local), **SSE** (`SSE=true`, legacy),
> **Streamable HTTP** (`STREAMABLE_HTTP=true`, modern remote).

**Read-only mode** (`GITLAB_READ_ONLY_MODE=true`) filters write tools **at
registration time**, not via request validation. It is a UX/context guard, not a
hard boundary — see the security note in [§3.6](#36-trust--security).

### 3.3 Multi-instance self-hosted routing (zereight v2.1+)

A single server deployment can serve many self-hosted instances via per-request
header routing:

```bash
# ✅ Do — one server, many self-hosted instances, LRU connection pool
export ENABLE_DYNAMIC_API_URL=true
export REMOTE_AUTHORIZATION=true       # mandatory: per-request Bearer is forwarded
export STREAMABLE_HTTP=true            # stdio/SSE do NOT support header routing
export GITLAB_POOL_MAX_SIZE=50
export GITLAB_POOL_IDLE_TIMEOUT=300000 # 5-min idle eviction

# Client selects the target instance per request:
curl -X POST https://mcp-gateway.example.com/mcp \
  -H "Authorization: Bearer glpat-instanceA-token" \
  -H "X-GitLab-API-URL: https://gitlab-dev.example.com/api/v4" \
  -H "Content-Type: application/json" \
  -d '{"method":"tools/call","params":{"name":"list_projects"}}'
```

### 3.4 `jmrplens/gitlab-mcp-server` — progressive disclosure (token control)

A Go static binary (zero runtime deps — good for air-gapped self-hosted). Its
`TOOL_SURFACE` env var is the most systematic token control of any server:

| `TOOL_SURFACE` | Visible tools | ~Schema token cost | Use when |
| --- | --- | --- | --- |
| `dynamic` (default) | **2** (`gitlab_find_action`, `gitlab_execute_action`) | **~20,488** | Narrow task scope; default for agents |
| `meta` | ~33 CE domain dispatchers | **~105,478** | Grouped-domain workflows |
| `individual` | all 866+ tools | **~491,512** | Avoid for almost all agent workloads |

> **Correction**: the dispatcher mode value is **`meta`**, *not* `meta-tools`.
> Any config using `TOOL_SURFACE=meta-tools` is wrong.

```json
// ✅ Do — Go binary, dynamic surface (97.6% schema-token reduction vs individual)
{
  "mcpServers": {
    "gitlab-go": {
      "command": "/usr/local/bin/gitlab-mcp-server",
      "env": {
        "GITLAB_TOKEN": "glpat-xxxxxxxxxxxxxxxxxxxx",
        "GITLAB_URL": "https://gitlab.example.com",   // base URL, NO /api/v4
        "GITLAB_SKIP_TLS_VERIFY": "false",
        "GITLAB_READ_ONLY": "false",
        "TOOL_SURFACE": "dynamic",
        "GITLAB_ENTERPRISE": "false"
      }
    }
  }
}
```

Self-hosted extras: `GITLAB_SKIP_TLS_VERIFY=true` (self-signed certs — dev only),
`GITLAB_ENTERPRISE=true` (Premium/Ultimate tool set), `GITLAB_SAFE_MODE=true`
(dry-run previews).

> Note the **read-only env-var name differs by server**: jmrplens uses
> `GITLAB_READ_ONLY`; yoda-digital and zereight use `GITLAB_READ_ONLY_MODE`.
> They are not interchangeable — a typo silently no-ops the guard.

### 3.5 Network-exposed deployment done right (yoda-digital)

```bash
# ✅ Do — network-exposed deployment uses OAuth; server holds NO static token
docker run -d \
  -e AUTH_MODE=oauth \
  -e GITLAB_API_URL=https://gitlab.example.com/api/v4 \
  -e HOST=0.0.0.0 -e PORT=3000 \
  -e USE_STREAMABLE_HTTP=true \
  -p 3000:3000 \
  ghcr.io/yoda-digital/mcp-gitlab-server:latest
# In AUTH_MODE=oauth each connection supplies its own Bearer token, forwarded to
# GitLab. GITLAB_PERSONAL_ACCESS_TOKEN must NOT be set.
```

yoda-digital enforces a **hard rule**: in `AUTH_MODE=pat` the HTTP server binds
only to `127.0.0.1` and **refuses to start** if `HOST` is non-loopback. For
network exposure, `AUTH_MODE=oauth` is mandatory.

### 3.6 Trust & security

Community servers are third-party software handling your GitLab credentials.
Treat them accordingly:

- **In-process PAT exposure**: any community server in PAT mode holds the token
  in its process environment, visible to the operator and any process that can
  read that env. Inherent to the PAT model; only per-connection OAuth avoids it.
- **`AIKIDO-2025-11000`** (zereight **2.0.0–2.0.20**): `HOST` defaulted to
  `0.0.0.0`, exposing embedded credentials to the LAN in SSE/HTTP mode. **Fixed
  in v2.0.21** (loopback bind by default). Verify you run **≥ 2.0.21** or set
  `HOST=127.0.0.1` explicitly.
- **Ecosystem baseline** (Astrix Security 2025): 53% of MCP servers rely on
  long-lived static secrets, 79% pass them via plain env vars, only 8.5% use
  OAuth. Community GitLab servers in PAT mode sit in the majority.
- **Read-only mode is not a hard boundary**: `GITLAB_READ_ONLY_MODE` filters at
  registration. The true enforcement boundary is the **token scope**
  (`read_api`, not `api`). Defense-in-depth means *both*.
- **No formal disclosure SLA**: track CVEs manually (GitHub Private Vulnerability
  Reporting for yoda-digital; public issue trackers otherwise).

```bash
# ❌ Don't — PAT injected into a network-accessible server process
docker run -d \
  -e GITLAB_PERSONAL_ACCESS_TOKEN=glpat-xxxx \
  -e HOST=0.0.0.0 \
  -e STREAMABLE_HTTP=true \
  -p 3002:3002 \
  zereight050/gitlab-mcp
# A single long-lived credential is reachable from the whole network.
# yoda-digital refuses this; zereight 2.0.0-2.0.20 silently allowed it
# (AIKIDO-2025-11000). For networked deployments use AUTH_MODE=oauth,
# or keep HOST=127.0.0.1 in PAT mode. (zereight default PORT is 3002.)
```

```text
# ❌ Don't — put the PAT anywhere the LLM provider can log it
User: "My GitLab token is glpat-abc123xyz, please list my projects"
# Also wrong: hardcoding it in CLAUDE.md or a rules file.
# Provider inference logs are permanent. Pass tokens ONLY via the MCP server's
# env block in mcp.json (or a runtime secrets vault) — never as conversation text.
```

A layered, least-privilege community-server config for a read-only audit agent:

```bash
# ✅ Do — defense-in-depth: scope + read-only + project allowlist + capabilities
export GITLAB_PERSONAL_ACCESS_TOKEN=glpat-xxxx   # read_api scope ONLY
export GITLAB_API_URL=https://gitlab.example.com/api/v4
export GITLAB_READ_ONLY_MODE=true                # hides mutating tools
export GITLAB_ALLOWED_PROJECT_IDS=123,456,789    # mcpland: project scoping
export GITLAB_DISABLED_CAPABILITIES=admin,graphql
export USE_PIPELINE=true
export USE_GITLAB_WIKI=false
npx -y gitlab-mcp@latest        # mcpland npm package name is 'gitlab-mcp'
# No single misconfiguration can expose write operations.
```

> **Correction**: the mcpland package is `gitlab-mcp` — `npx -y mcpland-gitlab-mcp`
> is the wrong name and will fail.

---

## 4. Official vs community: comparison

| Dimension | Official `/api/v4/mcp` | Community (representative) |
| --- | --- | --- |
| Maintainer / SLA | GitLab, formal security team | Third party, no SLA |
| Tier required | **Premium/Ultimate + Duo** | **Any tier, no Duo** (incl. Free/CE) |
| Min GitLab version | **18.6** (beta) | REST v4 only (~13+) |
| Tools | **15** (compact) | 9 → 1025 (configurable) |
| Auth | **OAuth 2.0 DCR only** (no PAT) | PAT, OAuth, or job token |
| M2M / CI-CD | ✗ (no PAT) → use `glab mcp serve` | ✓ (PAT / `GITLAB_JOB_TOKEN`) |
| Self-hosted | ✓ (Duo + beta toggles required) | ✓ (single base-URL env var) |
| Multi-instance routing | header prefix only (naming) | zereight/mcpland dynamic routing |
| Token-surface control | small fixed set; `fields` on `search` | feature toggles, read-only, `TOOL_SURFACE` |
| Read-only enforcement | ✗ (no annotations yet, #585082) | server flag + token scope (defense-in-depth) |
| Semantic code search | ✓ (needs vector store) | ✗ (REST text search only) |
| Install overhead | zero (built-in) | npm/Docker/Go binary to run & trust |
| Security responsibility | GitLab | **You** (audit, CVE tracking) |

**Decision heuristic:**

- **Premium/Ultimate + Duo, interactive IDE agent, want zero install and a
  vendor-supported surface** → official `/api/v4/mcp`.
- **Free/CE/air-gapped, OR need PAT/M2M/CI-CD, OR need file/branch/label CRUD
  beyond the 15 tools** → community server (or `glab mcp serve` for the smallest
  trusted footprint).
- **Read-heavy retrieval at scale** → consider skipping MCP entirely; use `glab`
  + `jq` ([§7](#7-token--tool-count-overhead-the-candid-part)).

---

## 5. Do / Don't config quick-reference

```json
// ✅ Do — distinct tool-name prefixes when two GitLab instances are registered
{
  "mcpServers": {
    "gitlab-prod":    { "type": "http", "url": "https://gitlab.example.com/api/v4/mcp",
                        "headers": { "X-Gitlab-Mcp-Server-Tool-Name-Prefix": "prod_" } },
    "gitlab-staging": { "type": "http", "url": "https://staging.gitlab.example.com/api/v4/mcp",
                        "headers": { "X-Gitlab-Mcp-Server-Tool-Name-Prefix": "stg_" } }
  }
}
```

```json
// ❌ Don't — two instances, no prefixes (requires GitLab 18.11+ to fix)
{
  "mcpServers": {
    "gitlab-prod":    { "type": "http", "url": "https://gitlab.example.com/api/v4/mcp" },
    "gitlab-staging": { "type": "http", "url": "https://staging.gitlab.example.com/api/v4/mcp" }
  }
}
// Both expose identically named tools (create_issue, get_merge_request, ...).
// The model cannot tell the instances apart and may silently route a WRITE to
// the wrong environment.
```

```bash
# ❌ Don't — expose a full-scope PAT to a community server in read-write
#            when the agent only ever reads.
export GITLAB_PERSONAL_ACCESS_TOKEN=glpat-FULL-API-SCOPE   # 'api' = read+write+delete
export GITLAB_READ_ONLY_MODE=false
# A read-only triage agent now has a credential that can delete branches,
# merge MRs, and edit project settings. Over-privilege + a registration-time-only
# read-only flag is the worst combination.
```

```bash
# ✅ Do — match token scope to the agent's job; let scope be the hard boundary
export GITLAB_PERSONAL_ACCESS_TOKEN=glpat-READ-API-ONLY    # 'read_api'
export GITLAB_READ_ONLY_MODE=true
# Even if a write tool slips past the registration-time filter, the token
# physically cannot perform the write.
```

---

## 6. Self-hosted summary (the priority audience)

| Concern | Official `/api/v4/mcp` | Community |
| --- | --- | --- |
| Endpoint / base URL | `https://<host>/api/v4/mcp` (fixed) | `GITLAB_API_URL=https://<host>/api/v4` (zereight/yoda/mcpland) or `GITLAB_URL=https://<host>` (ttpears/jmrplens) |
| Prerequisites | Duo enabled **+** beta features on (else 404) | none beyond REST v4 reachability |
| Tier | Premium/Ultimate | any (incl. Free/CE) |
| Auth offline | ✗ (OAuth browser flow) | ✓ (PAT, no browser) |
| Self-signed TLS | trust the CA at OS level | `GITLAB_CA_CERT_PATH` (mcpland/jmrplens); `GITLAB_SKIP_TLS_VERIFY` (jmrplens, dev only) |
| Corporate proxy | client/OS proxy | `HTTP_PROXY`/`HTTPS_PROXY` honored (zereight/mcpland) |
| WAF/Cloudflare | — | `GITLAB_CLOUDFLARE_BYPASS=true` (mcpland) |
| Multi-instance | header prefix (naming only) | dynamic routing (zereight v2.1+, mcpland comma-sep) |

```bash
# ❌ Don't — disable TLS verification to get a self-signed instance "working"
export GITLAB_SKIP_TLS_VERIFY=true   # or NODE_TLS_REJECT_UNAUTHORIZED=0
# This exposes every forwarded token to MITM. Trust the internal CA instead:
#   sudo cp internal-ca.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates
# (Go binaries also honor SSL_CERT_FILE; Node honors GITLAB_CA_CERT_PATH where supported.)
```

---

## 7. Token & tool-count overhead (the candid part)

This is the section most "just install the MCP server" guides skip. **MCP is not
the cheapest GitLab surface for agents — it is frequently the most expensive.**

### 7.1 The benchmark reality

Independent benchmarks of GitHub's official MCP server (architecturally
comparable) found:

- **43 tools ≈ 42,000 tokens** of schema injected into context *before any task
  content*, on every turn.
- A trivial "what language is this repo?" task: **1,365 tokens via CLI vs 44,026
  via MCP** — a **32x** ratio.
- At 10,000 monthly ops: **~$3.20/month (CLI) vs ~$55.20/month (MCP)** — a ~17x
  cost gap.
- **Accuracy** *dropped* with tool count: ~**95%** correct tool selection with a
  4-tool / ~1,200-token set vs **~71%** with the full ~46-tool / ~42,000-token
  set — a 24-point drop attributed purely to context bloat. Rule of thumb: if
  tool schemas exceed **20%** of your context budget, that is a bloat problem.

> *(As of 2026-05-29; reconfirm.)* These are **secondary-source** benchmarks on
> GitHub's MCP, not direct GitLab measurements; tool counts cited vary (43 vs 46)
> by version. The schema-token cost of the **official GitLab 15-tool server has
> not been independently measured** — a ~3,000–6,000-token estimate is
> extrapolated (low-confidence). Treat magnitudes, not exact numbers, as the
> takeaway.

### 7.2 Per-surface token ranking (read tasks, lowest → highest)

| Rank | Surface | Schema overhead | Notes |
| --- | --- | --- | --- |
| 1 | `glab` CLI + `jq` projection | **0** | Output fetched only when called; project fields before the LLM sees them |
| 2 | Raw REST via curl | **0** | No sparse fieldsets — always pipe through `jq` |
| 3 | GraphQL `/api/graphql` | **0** | Best for structured multi-field reads: returns *only* requested fields (5–10x less JSON than REST) |
| 4 | Official 15-tool server | small | Best when you need *write* accuracy + Premium tier |
| 5 | Community, allow-listed (ttpears 43 / yoda 86) | 10k–50k | Viable only with allow-lists / feature toggles |
| 6 | jmrplens `individual` (866+) | ~491k | **Avoid** — use `dynamic` (~20k) or `meta` (~105k) |

### 7.3 Controls that actually move the needle

- **Pick the surface by task shape.** Read-heavy → CLI/GraphQL. Write-heavy →
  MCP (schema guidance lifts mutation accuracy).
- **Progressive disclosure** (jmrplens `TOOL_SURFACE=dynamic`): ~20k tokens for
  full catalog access vs ~491k for `individual` — a ~97.6% reduction.
- **Feature toggles as token controls** (zereight `USE_PIPELINE`,
  `USE_MILESTONE`, `USE_GITLAB_WIKI` default off): omit categories the agent
  won't use to keep their schemas out of context.
- **Read-only mode** roughly halves the registered tool set for analysis agents —
  cutting both startup context *and* accidental-write risk.
- **Tool-name prefixes are not free**: the prefix token rides every tool call.
  Keep it short (the 32-char cap is generous).
- **Annotations can't help yet** (official server, #585082): you cannot
  context-filter to read-only tools via annotations — do it with feature
  toggles / read-only mode instead.
- **Aggregated tools** (e.g. `manage_pipeline`) consolidate related operations
  into one schema — GitLab's recommended pattern for keeping the tool list small.
- **Field selection**: GitLab REST returns 100+ fields per item with no sparse
  fieldsets. Prefer GraphQL field selection, the `search` tool's `fields`
  parameter, or post-filter with `jq` before the data reaches the model.

---

## Sources

- <https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/>
- <https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_tools/>
- <https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_troubleshooting/>
- <https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_clients/>
- <https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/ai_catalog_mcp_servers/>
- <https://docs.gitlab.com/user/gitlab_duo/semantic_code_search/>
- <https://docs.gitlab.com/user/gitlab_duo/turn_on_off/>
- <https://docs.gitlab.com/cli/mcp/serve/>
- <https://docs.gitlab.com/development/duo_agent_platform/mcp/>
- <https://docs.gitlab.com/api/rest/>
- <https://docs.gitlab.com/api/graphql/>
- <https://docs.gitlab.com/user/profile/personal_access_tokens/>
- <https://docs.gitlab.com/security/rate_limits/>
- <https://gitlab.com/gitlab-org/gitlab/-/issues/586184> (PAT support — open)
- <https://gitlab.com/gitlab-org/gitlab/-/issues/585699> (OAuth scope error)
- <https://gitlab.com/gitlab-org/gitlab/-/work_items/566965> (mcp-remote scope workaround)
- <https://gitlab.com/gitlab-org/gitlab/-/issues/585082> (tool annotations — open)
- <https://github.com/zereight/gitlab-mcp>
- <https://mcpservers.org/servers/zereight/gitlab-mcp>
- <https://intel.aikido.dev/cve/AIKIDO-2025-11000>
- <https://github.com/yoda-digital/mcp-gitlab-server>
- <https://github.com/mcpland/gitlab-mcp>
- <https://github.com/ttpears/gitlab-mcp>
- <https://github.com/jmrplens/gitlab-mcp-server>
- <https://github.com/detailobsessed/efficient-gitlab-mcp> (archived; superseded by zereight v2.1+)
- <https://hub.docker.com/r/mcp/gitlab>
- <https://astrix.security/learn/blog/state-of-mcp-server-security-2025/>
- <https://www.scalekit.com/blog/mcp-vs-cli-use>
- <https://dev.to/nebulagg/mcp-tool-overload-why-more-tools-make-your-agent-worse-5a49>
- <https://github.blog/ai-and-ml/github-copilot/improving-token-efficiency-in-github-agentic-workflows/>
- <https://about.gitlab.com/blog/give-your-ai-agent-direct-structured-gitlab-access-with-glab-cli/>

> Researched 2026-05-29; version-specific details should be reconfirmed.
