# glab — The Official GitLab CLI

A complete, implementation-grade reference for `glab`, the official GitLab CLI. **Self-hosted (GitLab Self-Managed and Dedicated) is the priority audience throughout; `gitlab.com` is the secondary case.**

> **Accuracy note.** Facts below are tagged where the underlying research was *corrected* or *unverified* during fact-checking. Version numbers, tier gating, and feature-maturity labels (experiment vs. beta) move fast — treat anything marked "as of 2026-05-29; reconfirm against your instance/version" as a snapshot, not a contract. Researched 2026-05-29; version-specific details should be reconfirmed.

---

## 1. Overview & Where It Fits

`glab` is the **official, open-source GitLab CLI**, written in Go and shipped as a single static binary. The canonical project lives at `gitlab.com/gitlab-org/cli` and is mirrored at `github.com/gl-cli/glab`.

- **Targets:** GitLab.com, GitLab Dedicated, and GitLab Self-Managed.
- **Minimum supported instance:** GitLab **16.0** (confirmed). Some commands require newer versions — e.g. OAuth device flow needs 17.9+, GitLab Duo CLI needs 18.11+.
- **Design lineage:** the command surface deliberately mirrors GitHub's `gh` (auth, `mr`/`issue`, `ci`, `release`, `repo`, `api`, `alias`, `config`) and adds GitLab-native groups (`ci`, `variable`, `cluster`, `duo`, `mcp`, `stack`, `schedule`, `runner`).
- **The universal escape hatch:** `glab api` hits the GitLab REST v4 API or GraphQL using the same stored session credentials.

Where it fits in an automation/agent stack: `glab` is the credential-aware front door to a GitLab instance. High-level commands cover common workflows; when a field or endpoint isn't surfaced by a dedicated command, `glab api` reuses the existing auth with zero extra credential plumbing.

> **Version reality check.** The findings originally pinned "latest series v1.6x"; that is stale. The release series is well past that — around **v1.100.0 (released 2026-05-27)** as of this writing. Do **not** hardcode a version; check `https://gitlab.com/gitlab-org/cli/-/releases`. (Corrected during verification.)

---

## 2. Installation

The officially supported method on macOS and Linux is **Homebrew**. WinGet is also officially supported (it is *not* community-maintained — a correction from the original research). The remainder are community-maintained.

| Method | Command | Status |
|---|---|---|
| Homebrew | `brew install glab` | Official |
| WinGet | `winget install glab.glab` | Official |
| Chocolatey | `choco install glab` | Community |
| Scoop | `scoop install glab` | Community |
| Pacman (Arch) | `pacman -S glab` | Community |
| DNF (Fedora) | `dnf install glab` | Community |
| APK (Alpine) | `apk add --no-cache glab` | Community |
| Nix | `nix-env -iA nixos.glab` | Community |
| Snap | `sudo snap install glab` | Community |
| Docker | `docker pull gitlab/glab` | Community |
| Spack | `spack install glab` | Community |
| ASDF / mise | via plugin | Community |

Binary releases: `https://gitlab.com/gitlab-org/cli/-/releases`.

```bash
# ✅ Do: verify the binary after install
glab version
glab auth status   # confirms whether any host is authenticated
```

```bash
# ❌ Don't: assume the binary is on PATH inside a minimal CI image without installing it
glab mr list   # "command not found" — install glab in the job's before_script first
```

---

## 3. Authentication

### 3.1 Token resolution order

When `glab` needs a token it resolves in this order (highest priority first):

1. **`GITLAB_TOKEN`** environment variable — confirmed and documented.
   - `GITLAB_ACCESS_TOKEN` and `OAUTH_TOKEN` may still function as aliases but are **not documented in the current `gl-cli/glab` README** — treat as low-confidence (as of 2026-05-29; reconfirm). Implement against `GITLAB_TOKEN` only.
2. **Stored credential** in `~/.config/glab-cli/config.yml`.
3. **CI job token** via `GLAB_ENABLE_CI_AUTOLOGIN` when running inside a GitLab CI job.

```bash
# ✅ Do: use the documented env var only
export GITLAB_TOKEN=glpat-xxxxxxxxxxxxxxxxxxxx
glab mr list
```

```bash
# ❌ Don't: rely on the undocumented aliases as your primary mechanism
export OAUTH_TOKEN=glpat-...   # may break silently; not in the current README
glab mr list
```

### 3.2 Self-hosted login (priority case)

`glab auth login --hostname <hostname>` is the entry point for a Self-Managed or Dedicated instance.

**Confirmed flags:** `--hostname`, `--stdin`, `--job-token`, `--api-host` (`-a`), `--api-protocol` (`https`|`http`), `--git-protocol` (`ssh`|`https`|`http`), `--ssh-hostname`, `--device` (OAuth 2.0 device flow, **requires GitLab 17.9+**).

**Also documented (added during verification):** `--token` (pass a PAT directly), `--web` (OAuth/web login), `--use-keyring` (store the token in the OS keyring), `--container-registry-domains`.

> **Unverified:** the original "minimum recommended scopes: `api` and `write_repository`" guidance was **not found on the official auth-login doc page**. Treat scope guidance as unverified (as of 2026-05-29) — check your instance's token-scope docs before provisioning.

```bash
# ✅ Do: non-interactive self-hosted bootstrap, token via stdin (no token in argv/history)
glab auth login \
  --hostname gitlab.example.com \
  --api-protocol https \
  --git-protocol ssh \
  --stdin < /run/secrets/gitlab_token

# Verify (note: --show-token prints the secret — only in a controlled shell)
glab auth status --hostname gitlab.example.com
```

```bash
# ✅ Do (alternative): pass the token directly with --token
glab auth login --hostname gitlab.example.com --token "$GITLAB_TOKEN" --git-protocol ssh

# ✅ Do (canonical stdin form): pipe, don't here-string
echo "$GITLAB_TOKEN" | glab auth login --hostname gitlab.example.com --stdin
```

The `--api-host` (`-a`) flag matters when the API endpoint lives on a different address than the Git remote (e.g. an internal load balancer, `api.gitlab.example.com` vs `gitlab.example.com`). `--api-protocol http` enables plain HTTP for TLS-less internal instances.

### 3.3 gitlab.com login (secondary case)

```bash
# ✅ Do: interactive browser-based login to SaaS
glab auth login --hostname gitlab.com
# or non-interactively
export GITLAB_TOKEN=glpat-...
glab auth login --hostname gitlab.com --stdin <<< "$GITLAB_TOKEN"
```

When unset, the active host defaults to `https://gitlab.com`.

### 3.4 CI job-token auth

Inside a GitLab CI job, set `GLAB_ENABLE_CI_AUTOLOGIN=true`. `glab` reads `CI_JOB_TOKEN`, `CI_SERVER_FQDN`, `CI_SERVER_PROTOCOL`, **and `CI_SERVER_SHELL_SSH_HOST`** (the SSH-host variable was omitted from the original research — added during verification). Only commands that accept CI job tokens work under auto-login; for everything else, fall back to a PAT in a masked CI/CD variable exposed as `GITLAB_TOKEN`.

```bash
# ✅ Do: explicit CI job-token login when auto-login isn't enabled
glab auth login \
  --job-token "$CI_JOB_TOKEN" \
  --hostname "$CI_SERVER_FQDN" \
  --api-protocol "$CI_SERVER_PROTOCOL"
```

### 3.5 Git credential helper (nuance vs. `gh`)

The auth page synopsis mentions that `glab` can be configured as a credential helper for **Git and Docker**, so the original "glab does not configure Git auth at all" framing **overstates the gap** (corrected). However, there is no single named subcommand for the HTTPS Git credential helper analogous to `gh`'s automatic setup — it may require manual `git config` wiring. Verify against your `glab` version before claiming parity or a gap (as of 2026-05-29).

---

## 4. Configuration

### 4.1 File locations

| Scope | Path | Notes |
|---|---|---|
| Global | `~/.config/glab-cli/config.yml` | Overridable via `GLAB_CONFIG_DIR`. Per-host settings always land here. |
| Local (repo) | `.git/glab-cli/config.yml` | Repo-level overrides; takes precedence over global for repo-scoped keys. |

Per-host config (`token`, `api_protocol`, `api_host`, `client_id`, `git_protocol`, `ssh_hostname`, `ca_cert`) is **always written to the global file**, never to the local repo file — even when `--global` is not passed (confirmed).

### 4.2 Settable keys

- **Global-scope keys:** `browser`, `check_update`, `display_hyperlinks`, `editor`, `glab_pager`, `glamour_style`, `host`, `token`, `visual`.
- **Per-host keys** (require `--host <hostname>`, short `-h`): `token`, `api_host`, `api_protocol` (`http`|`https`), `git_protocol` (`ssh`|`https`|`http`), `ssh_hostname`, `client_id`, `ca_cert`.

> **Flag gotcha:** `glab config set` scopes per-host with `--host` (short `-h`), **not** `--hostname`. `--hostname` belongs to `glab auth login`. (Corrected during verification.)

```bash
# ✅ Do: scope a per-host key correctly
glab config set api_protocol https --host gitlab.example.com
glab config set git_protocol ssh   --host gitlab.example.com
```

```bash
# ❌ Don't: use --hostname with glab config set (wrong flag for this command)
glab config set api_protocol https --hostname gitlab.example.com   # not the right flag
```

### 4.3 Config read precedence

Highest to lowest:

1. **Environment variables** (`GITLAB_TOKEN`, `GITLAB_HOST`, `GLAB_*`, `BROWSER`, `VISUAL`, `EDITOR`, …)
2. **Local** `.git/glab-cli/config.yml`
3. **Global** `~/.config/glab-cli/config.yml`

The `--global` flag forces read/write against the global file.

### 4.4 The `GITLAB_HOST` scheme trap (critical for self-hosted)

When used as an **environment variable**, `GITLAB_HOST` (aliases `GL_HOST`, `GITLAB_URI`) must be a **bare hostname with no scheme**. The CLI prepends the protocol internally (from `api_protocol`). Supplying a full URL causes a doubled-scheme bug (`https://https://…`). This is a long-standing documentation/implementation mismatch (issue #592, archived `profclems/glab`; confirmed).

```bash
# ✅ Do: bare hostname; protocol controlled separately
export GITLAB_HOST=gitlab.example.com
glab config set api_protocol https --host gitlab.example.com
glab mr list   # resolves to https://gitlab.example.com/api/v4/...
```

```bash
# ❌ Don't: include the scheme in the env var
export GITLAB_HOST=https://gitlab.example.com
glab mr list
# Error: GET https://https://gitlab.example.com/api/v4/projects/.../merge_requests
```

> **Note the asymmetry.** Some GitLab REST documentation uses `GITLAB_HOST="https://gitlab.example.com"` (full URL) for raw `curl` examples — that's fine for `curl`, but **not** for `glab`'s `GITLAB_HOST` env var. Keep the two mental models separate.

### 4.5 Environment-variable → config-key mapping (selected)

| Env Var | Config Key | Scope |
|---|---|---|
| `GITLAB_TOKEN` | `hosts.<host>.token` | per-host |
| `GITLAB_HOST` / `GL_HOST` / `GITLAB_URI` | `host` | global |
| `GITLAB_API_HOST` | `hosts.<host>.api_host` | per-host *(env-var form unverified; `api_host` config key is confirmed)* |
| `GITLAB_CLIENT_ID` | `hosts.<host>.client_id` | per-host |
| `GLAB_CONFIG_DIR` | (config dir path) | global |
| `GLAB_CHECK_UPDATE` | `check_update` | global |
| `GLAB_SEND_TELEMETRY` | `telemetry` | global |
| `GLAB_ENABLE_CI_AUTOLOGIN` | (runtime flag) | global |
| `NO_PROMPT` | `no_prompt` | global |
| `NO_COLOR` | (runtime flag) | global |
| `GLAMOUR_STYLE` | `glamour_style` | global |
| `BROWSER` | `browser` | global |
| `VISUAL` / `EDITOR` | `visual` / `editor` | global (`VISUAL` wins) |

> **`GLAB_` prefix migration (planned, not shipped).** A future **v2.0.0** is slated to move tool-internal env vars under a `GLAB_` prefix (e.g. `GLAB_NO_PROMPT`, `GLAB_GLAMOUR_STYLE`), tracked in work-item #7999. **v2.0.0 has not shipped as of 2026-05-29.** The old names still work and currently emit deprecation warnings. `BROWSER`, `NO_COLOR`, `VISUAL`, and `EDITOR` are explicitly confirmed to keep their names permanently. Code targeting the new names should set the old names as fallback until v2.0.0 ships. (Corrected during verification.)

### 4.6 Custom CA / self-signed TLS (self-hosted)

The correct approach is always to **trust the CA**, never to disable verification.

```bash
# ✅ Option A: glab config (ca_cert support is reported as v1.58.0+; reconfirm against releases)
glab config set ca_cert /etc/ssl/certs/internal-ca.pem --host gitlab.example.com

# ✅ Option B: Go HTTP client standard env vars (any glab version)
export SSL_CERT_FILE=/etc/ssl/certs/internal-ca.pem

# ✅ Option C (most durable): trust at the OS level
sudo cp internal-ca.crt /usr/local/share/ca-certificates/
sudo update-ca-certificates
```

```bash
# ❌ Don't: disable TLS verification in production
export GIT_SSL_NO_VERIFY=true   # exposes tokens to MITM — anti-pattern
```

> The `ca_cert` config key is confirmed to exist; the specific `v1.58.0` floor came from a secondary source — reconfirm against the releases page (as of 2026-05-29).

### 4.7 HTTP proxy

`glab`'s Go HTTP client honors the standard `HTTP_PROXY` / `HTTPS_PROXY` / `NO_PROXY` env vars (and lowercase variants). There is **no dedicated `glab config set http_proxy` key** in current `glab` — set proxy variables in the environment instead. (The proxy-config-key idea was a request on the archived `profclems/glab` repo, never merged; corrected.)

```bash
# ✅ Do: rely on standard Go proxy env vars
export HTTPS_PROXY=http://corp-proxy:3128
export NO_PROXY=gitlab.internal,registry.internal
glab mr list
```

### 4.8 Air-gapped / offline self-hosted

```bash
# ✅ Do: stop glab from hanging on the gitlab.com update check at startup
glab config set check_update false --global
export GLAB_SEND_TELEMETRY=false          # suppress outbound telemetry
export SSL_CERT_FILE=/etc/pki/ca-trust/internal-ca.pem
export GITLAB_HOST=gitlab.internal        # bare hostname
glab auth login --hostname gitlab.internal --token "$GITLAB_TOKEN" --git-protocol ssh
```

OAuth web/device flows require external connectivity; in air-gapped environments, **PAT login is the only viable method**.

---

## 5. Command-Group Surface

Organized by group with representative commands. Subcommand lists are accurate as of 2026-05-29; reconfirm against `glab <group> --help` for your version.

### 5.1 `glab auth`
`login`, `logout`, `status`, `token` — credential lifecycle and inspection.

### 5.2 `glab mr` (merge requests)
`create`, `list`, `view`, `merge`, `checkout`, `diff`, `approve`, `close`, `reopen`, `update`, `note`, `todo`, `rebase`. List/view support `--output json` + `--jq`.

```bash
# ✅ Do: fully non-interactive MR creation from CI
glab mr create \
  --fill --fill-commit-body \
  --target-branch main \
  --label automated \
  --remove-source-branch \
  --yes
```

> The short flag for `--output` on `glab mr list` is `-F` (the long form `--output` is what this doc uses throughout for clarity). Don't confuse `-F` here with the `-F/--field` flag on `glab api`.

### 5.3 `glab issue`
`create`, `list`, `view`, `close`, `reopen`, `update`, `note`, `board`, `subscribe`. List/view support `--output json` + `--jq`.

### 5.4 `glab ci` (pipelines & jobs)

**Confirmed subcommands (13):** `cancel`, `config`, `delete`, `get`, `lint`, `list`, `retry`, `run`, `run-trig`, `status`, `trace`, `trigger`, `view`. Deprecated aliases `pipe` and `pipeline` still work.

> **Correction:** `artifact` is **not** a `glab ci` subcommand — it lives under `glab job` as `glab job artifact`. (Corrected during verification.)

```bash
# ✅ Do: download artifacts via the correct group
glab job artifact main build
glab job artifact main deploy --path="artifacts/"
```

```bash
# ❌ Don't: call artifact under glab ci
glab ci artifact main build   # wrong group; artifact is under glab job
```

`glab ci trace` streams job logs in real time (always streaming/interactive — no `--output json`). Pass `--pipeline-id` to skip the interactive TUI job picker so it's usable in automation.

```bash
# ✅ Do: stream a specific job's log non-interactively
glab ci trace --branch main --pipeline-id 987654
```

> `glab ci status` / `glab ci view` launch TUI selectors that block automation. There is **no documented `--no-prompt` flag** for `ci trace` or `ci status` (a request, issue #8171, was open mid-2025). For automation, prefer `glab ci list --output json` + `glab ci get --output json`, or pass `--pipeline-id`.

### 5.5 `glab job`
`artifact`, `trace`, `play`, `retry`, `cancel` — single-job operations.

### 5.6 `glab release`
`create`, `list`, `view`, `delete`, `download`, `upload`.

### 5.7 `glab repo`
`clone`, `create`, `fork`, `view`, `archive`, `mirror`, `search`, `contributors`.

### 5.8 `glab variable`
`set`, `get`, `list`, `delete`, `update`, `export` — CI/CD variables at project and group scope.

### 5.9 `glab schedule`
`create`, `list`, `delete`, `run` — pipeline schedules.

### 5.10 `glab cluster` / `glab runner`
Agent/cluster management and runner registration/management.

### 5.11 `glab stack` (stacked diffs — experimental)

**Confirmed 13 subcommands:** `amend`, `create`, `first`, `infer`, `last`, `list`, `move`, `next`, `prev`, `reorder`, `save`, `switch`, `sync`. *(The original research listed 14 — one too many; corrected.)*

Explicitly marked **"not production-ready and might be unstable or removed at any time."** Avoid in durable automation.

### 5.12 `glab duo` (AI)
- `glab duo ask` — generates Git commands from natural language.
- `glab duo cli` — GitLab Duo Agentic Chat in the terminal.

`glab duo cli` requires **GitLab 18.11+**, **Premium/Ultimate**, **glab 1.87.0+**, and is in **beta** (changed from experiment to beta in 18.11). Works on Self-Managed and Dedicated. Whether `glab duo ask` also requires Premium/Ultimate or works on Free is **unverified** — reconfirm (as of 2026-05-29).

### 5.13 `glab mcp serve` (MCP server for agents)

`glab mcp serve` starts a Model Context Protocol server over **stdio**.

> **Maturity correction — read carefully.** The official `docs.gitlab.com/cli/mcp/serve/` page still marks `glab mcp serve` as **EXPERIMENTAL** ("not ready for production use") as of 2026-05-29 — **not** beta. The "15 tools / beta in 18.6 / Premium-Ultimate-required" figures from the original research describe the **separate, GitLab-native MCP server** (`/api/v4/mcp`, documented under `gitlab_duo/model_context_protocol/`), which is a **different product** from `glab mcp serve`. Do not conflate them. The claim that `glab mcp serve` auto-appends `--output json` to supported commands is **unverified** from official docs.

```bash
# Experimental — may change or be removed
glab mcp serve
```

### 5.14 `glab config` / `glab alias` / `glab api`
Covered in §4, §6, and §7 respectively.

---

## 6. The `glab api` Raw REST/GraphQL Escape Hatch

`glab api` is the universal fallback: any endpoint not surfaced by a dedicated command is reachable here, reusing stored credentials.

- **REST:** pass a v4 path, e.g. `projects/:fullpath/issues`.
- **GraphQL:** pass the literal keyword `graphql` as the endpoint.
- **Base paths (self-hosted):** REST is always `https://<host>/api/v4/`; GraphQL is always `https://<host>/api/graphql`. There is no `/api/v3/` (removed in GitLab 9.0). The `/api/v4` prefix is mandatory and non-configurable.

**Path placeholders auto-substituted from the current Git remote:** `:branch`, `:fullpath`, `:group`, `:id`, `:namespace`, `:repo`, `:user`, `:username`.

**Field flags:**
- `-F` / `--field` — **type inference** (bool, int, null). Prefer this for booleans/numbers.
- `-f` / `--raw-field` — always treats the value as a string.

**Pagination:**
- `--paginate` — fetch all pages sequentially (REST). No parallel-fetch optimization; large repos incur latency.
- GraphQL `--paginate` requires the query to accept `$endCursor: String` **and** return `pageInfo { hasNextPage endCursor }`. Without these, only the first page returns silently.

**Output:**
- `--output json` (default, pretty-printed)
- `--output ndjson` (one element per line; memory-efficient and streaming-friendly for large datasets)

```bash
# ✅ Do: paginated REST + ndjson piped to jq (bounded memory, incremental processing)
glab api 'projects/:fullpath/issues?state=opened' \
  --paginate \
  --output ndjson \
| jq -r 'select(.labels | contains(["bug"])) | [.iid, .title] | @tsv'
```

```bash
# ✅ Do: GraphQL with cursor-based pagination (note the mandatory pageInfo shape)
glab api graphql --paginate \
  -f query='
    query($endCursor: String) {
      project(fullPath: "mygroup/myrepo") {
        mergeRequests(state: opened, after: $endCursor) {
          nodes { iid title author { username } }
          pageInfo { hasNextPage endCursor }
        }
      }
    }'
```

```bash
# ✅ Do: use -F for typed params to avoid string-coercion bugs
glab api projects/:fullpath/issues -F confidential=true -F weight=3
```

```bash
# ❌ Don't: use -f (raw string) for booleans/numbers
glab api projects/:fullpath/issues -f confidential=true   # sends the string "true"
```

```bash
# ❌ Don't: point at gitlab.com or omit /api/v4 for a self-hosted instance
curl -H "PRIVATE-TOKEN: $T" https://gitlab.com/api/v4/projects        # wrong server
curl -H "PRIVATE-TOKEN: $T" https://gitlab.example.com/projects        # 404 (no /api/v4)
curl -H "PRIVATE-TOKEN: $T" https://gitlab.example.com/api/v3/projects  # 404 (v3 removed in 9.0)
```

### 6.1 Self-hosted auth headers (for raw `curl`, when you bypass glab)

| Token type | Header |
|---|---|
| PAT / project / group / impersonation | `PRIVATE-TOKEN: <token>` or `Authorization: Bearer <token>` |
| OAuth | `Authorization: Bearer <token>` only |
| CI job token | `JOB-TOKEN: $CI_JOB_TOKEN` |
| Admin sudo | add `Sudo: <user>`; requires `sudo` scope + admin |

> **Admin Mode (self-hosted/Dedicated, not gitlab.com):** when enabled, admin API calls need a PAT with the **`admin_mode`** scope or they silently return `403` even though the token looks valid. (Admin Mode is available on Self-Managed **and** Dedicated — correcting the "self-managed only" framing.)

### 6.2 Token-prefix cheat sheet (self-hosted)
`glpat-` (PAT/most tokens), `gldt-` (deploy token), `glrt-`/`glrtr-` (runner auth; the `glrtr-` registration-flow variant was missing from the original research), `glcbt-` (CI job token).

---

## 7. Aliases

`glab alias set NAME EXPANSION` supports positional expansion (`$1`, `$2`, `$@`). The `--shell` / `-s` flag routes the expansion through a shell interpreter, enabling pipes and redirections. On Windows, shell aliases require the `sh` shipped with Git for Windows. Aliases are stored in `~/.config/glab-cli/config.yml`.

> **Unverified:** the original claim that "aliases cannot override built-in glab commands" is **not stated** in the official alias-set docs — treat as unverified until tested (as of 2026-05-29).

```bash
# ✅ Do: shell alias with positional param + jq projection
glab alias set --shell issues-by \
  'glab issue list --assignee="$1" --output json | jq -r ".[] | [.iid,.title] | @tsv"'

glab issues-by johndoe
```

```bash
# ❌ Don't: expect pipes to work without --shell
glab alias set issues-by 'glab issue list --assignee="$1" | jq ...'
# Without --shell, the pipe is passed as a literal argument, not interpreted
```

---

## 8. Scripting & Machine-Readable Output

### 8.1 The core pattern

Most list/view commands accept `--output json`; combine with `--jq <expr>` for inline field projection. `glab api` defaults to `json` and offers `ndjson`.

```bash
# ✅ Do: project only the fields you need, inline
glab mr list --output json --jq '[.[] | {iid, title, state}]'
```

### 8.2 Non-interactive guarantees

```bash
# ✅ Do: disarm interactive prompts in any automation harness
export NO_PROMPT=true        # (or GLAB_NO_PROMPT once v2.0.0 ships; set both as fallback)
glab mr merge 42 --yes
```

```bash
# ❌ Don't: run TUI-selector commands in a non-TTY context
glab ci status   # launches an interactive picker; hangs in CI. Use ci list --output json instead.
```

### 8.3 Pagination in loops

```bash
# ✅ Do: always paginate list operations in agent loops (default page size ~20–30)
glab api 'projects/:fullpath/merge_requests?state=opened' --paginate --output ndjson
```

### 8.4 Error handling

`glab` emits **human-readable errors on stderr**; there is **no structured/JSON error format** (a known gap, acknowledged but unshipped as of mid-2025). Capture stderr separately in scripts and agents — don't try to parse it as JSON.

### 8.5 Cross-repo operations

Every repo-scoped command accepts `-R OWNER/REPO` (or `-R GROUP/NAMESPACE/REPO`), so you can operate on another project without `cd`.

```bash
# ✅ Do: target another project from anywhere
glab mr list -R mygroup/subgroup/service-a --output json
```

---

## 9. Do / Don't Quick Reference

| Topic | ✅ Do | ❌ Don't |
|---|---|---|
| `GITLAB_HOST` env var | `gitlab.example.com` (bare) | `https://gitlab.example.com` (doubled scheme) |
| Token env var | `GITLAB_TOKEN` | rely on `OAUTH_TOKEN`/`GITLAB_ACCESS_TOKEN` aliases |
| `glab config set` host scope | `--host` | `--hostname` (that's for `auth login`) |
| `glab api` typed params | `-F key=true` | `-f key=true` (string coercion) |
| Artifacts | `glab job artifact …` | `glab ci artifact …` |
| TLS on self-signed | trust the CA | `GIT_SSL_NO_VERIFY=true` |
| CI pipeline reads | `ci list/get --output json` | `ci status`/`ci view` (TUI blocks) |
| Aliases with pipes | `--shell` | bare alias body |
| Prompts in automation | `NO_PROMPT=true` + `--yes` | leave prompts armed |

---

## 10. Limitations vs. `gh` (GitHub CLI)

`gh` is the rich-CLI gold standard; `glab` has several concrete gaps. Plan around these.

| Capability | `gh` | `glab` |
|---|---|---|
| **Extension system** | `gh extension install/upgrade/browse/search` (first-class) | **None.** Requested in issue #1053 (open since 2022), unimplemented. Cannot add subcommands without forking the binary. |
| **Field-selecting `--json`** | `--json field1,field2` returns a sparse projection; invalid field name self-documents the full field list | **No equivalent.** `--output json` returns the **full object**; project via external/embedded `--jq`. |
| **Go-template output** | `--template` with `tablerow`/`tablerender`/`timeago`/`autocolor` etc. | **No `--template`.** Post-process externally. |
| **`api` filtering/templating** | `gh api` has `-q/--jq`, `-t/--template`, `--slurp`, `--cache`, `--verbose`, `--silent`, `--include` | `glab api` has `--output json|ndjson` (+ inline `--jq` on many high-level commands). No `--template`/`--slurp`/`--cache`/`--verbose`/`--include` equivalent surface on `glab api`. *(This parity gap is asserted from the docs; reconfirm the current `glab api` flag set, as of 2026-05-29.)* |
| **JSON-output coverage** | Broad and consistent across list/view | Inconsistent — present on `mr`/`issue`/`ci`/`release`/`milestone` list/view, **absent** on some (e.g. `ci status`, `ci view` are interactive TUIs). Issue #7689 tracks expansion. |
| **Structured errors** | exit-code contract (0/1/2/4); errors parseable | Human-readable stderr only; no structured error JSON. |
| **Git credential helper** | configured automatically by `gh auth login` | available as a Git/Docker credential helper but **not** auto-wired the same way; may need manual `git config`. |

**What `glab` has that `gh` lacks:** GitLab-native groups — `ci`/`job`/`pipeline`, `duo` (Duo Chat), `mcp`, `cluster`/`runner` (infra), `incident`, `stack`, `variable`, `schedule`, `work-items`.

---

## 11. For Agents — Token-Efficient Invocation

`glab` is well-suited as an LLM-agent tool. Patterns, ordered by leverage:

1. **GraphQL for reads.** `glab api graphql` with a tightly scoped query returns *only* the requested fields — typically **5–10× fewer tokens** than the equivalent REST object. This is the single biggest token lever.
2. **`--output json --jq` projection.** On high-level commands, project to exactly the scalars you need before output enters the model context: `glab mr list --output json --jq '[.[] | {iid,title,state}]'`.
3. **`--output ndjson --paginate`** for large result sets via `glab api` — streaming-friendly, bounded peak memory.
4. **Pre-fetch outside the agent loop.** Run `glab` in a setup step, write output to a workspace file, and have the agent *read the file* rather than spending tokens on the fetch. (GitHub's own agentic-workflow team measured **19–62% token reduction** across five workflows using this pattern with `gh`; the principle transfers directly to `glab`.)
5. **Force non-interactivity.** Always set `NO_PROMPT=true` (and `--yes` where applicable). Avoid TUI commands (`ci status`, `ci view`) entirely — use `ci list --output json` + `ci get --output json`.
6. **Keyset pagination at the API layer.** For raw API reads, `?pagination=keyset&per_page=100&order_by=id&sort=asc` avoids the `x-total`/`x-total-pages` overhead of offset pagination on large collections.
7. **Minimal token scope.** Issue a `read_api`-scoped PAT for read-only agents — a hard capability boundary that prevents accidental writes. Project access tokens are even more constrained (cannot escape the project).
8. **Capture stderr separately.** Errors are unstructured human text; don't feed them back as JSON. Check for auth-required failures and surface them rather than retrying blind.
9. **MCP caveat.** If using `glab mcp serve`, remember it is **experimental** (§5.13) — and is distinct from the GitLab-native `/api/v4/mcp` server. Pin which one your harness targets.

---

## Sources

Relied-upon source URLs (all accessed/verified for the 2026-05-29 research pass):

- https://docs.gitlab.com/cli/
- https://docs.gitlab.com/cli/auth/login/
- https://docs.gitlab.com/cli/auth/
- https://docs.gitlab.com/cli/api/
- https://docs.gitlab.com/cli/config/
- https://docs.gitlab.com/cli/config/set/
- https://docs.gitlab.com/cli/config/get/
- https://docs.gitlab.com/cli/alias/set/
- https://docs.gitlab.com/cli/mr/create/
- https://docs.gitlab.com/cli/mr/list/
- https://docs.gitlab.com/cli/ci/
- https://docs.gitlab.com/cli/ci/list/
- https://docs.gitlab.com/cli/ci/trace/
- https://docs.gitlab.com/cli/job/artifact/
- https://docs.gitlab.com/cli/stack/
- https://docs.gitlab.com/cli/mcp/serve/
- https://docs.gitlab.com/cli/duo/ask/
- https://docs.gitlab.com/user/gitlab_duo_cli/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server/
- https://docs.gitlab.com/user/gitlab_duo/model_context_protocol/mcp_server_tools/
- https://docs.gitlab.com/api/rest/
- https://docs.gitlab.com/api/rest/authentication/
- https://docs.gitlab.com/api/rest/troubleshooting/
- https://docs.gitlab.com/api/graphql/
- https://docs.gitlab.com/user/profile/personal_access_tokens/
- https://docs.gitlab.com/security/tokens/
- https://docs.gitlab.com/administration/settings/account_and_limit_settings/
- https://docs.gitlab.com/administration/settings/sign_in_restrictions/
- https://docs.gitlab.com/administration/settings/user_and_ip_rate_limits/
- https://docs.gitlab.com/omnibus/settings/ssl/ssl_troubleshooting/
- https://docs.gitlab.com/topics/offline/quick_start_guide/
- https://github.com/gl-cli/glab
- https://github.com/gitlabhq/cli/blob/main/README.md
- https://github.com/gitlabhq/cli/blob/main/docs/installation_options.md
- https://gitlab.com/gitlab-org/cli/-/releases
- https://gitlab.com/gitlab-org/cli/-/issues/1053
- https://gitlab.com/gitlab-org/cli/-/issues/7689
- https://gitlab.com/gitlab-org/cli/-/issues/8171
- https://gitlab.com/gitlab-org/cli/-/work_items/7999
- https://github.com/profclems/glab/issues/592
- https://mise-versions.jdx.dev/tools/glab
- https://cli.github.com/manual/gh_api
- https://cli.github.com/manual/gh_pr_list
- https://github.blog/ai-and-ml/github-copilot/improving-token-efficiency-in-github-agentic-workflows/

*Researched 2026-05-29; version-specific details should be reconfirmed.*
