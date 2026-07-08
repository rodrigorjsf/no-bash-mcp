# No bash MCP

![](assets/readme-logo.png)
---

> **v1 shipped.** The shipped surface is `run_tests` (Maven, Node/jest, Go), `build`, `install`,
> the five git read verbs, `get_log`, and — as of **PRD-6** — the three forge inspection verbs
> (`pr_checks`, `pr_view`, `pr_diff`, GitHub.com-only). The npm/npx native launcher is available
> (PRD-5 #44 S3, v0.0.1-alpha.2 published).

A Micronaut MCP server that replaces an agent's Bash tool with safe, structured, token-efficient
operations so the Bash permission can be removed entirely. The agent never composes a command; the
server detects the environment and maps a typed verb to a controlled invocation.

---

## What ships in v1

The following verbs are available over a STDIO JSON-RPC channel:

### `run_tests`

Runs a project's tests via the detected manager (Maven, Node/jest, Go). Returns a structured
`Envelope` with one of three shapes: **success** (counts only), **test-failure** (normalized
`failures[]` with `file:line`, assertion message, and a `handle` for drill-down), or
**operational-error** (enumerated `code` + actionable `hint`).

| Parameter    | Type          | Required | Description |
|---|---|---|---|
| `path`       | string        | no       | Path to the project directory; absent/blank fails closed to `INVALID_PATH` |
| `flags`      | string[]      | no       | Manager flags, vetted against the per-operation allowlist |
| `timeout`    | integer       | no       | Timeout in seconds; clamped to the policy cap; exceeded runs get a hard tree-kill |
| `targetKind` | `CLASS`/`METHOD` | no    | Narrows the run to a class or method; absent means full-suite |
| `target`     | string        | no       | Test identity matching the kind (`FooTest` or `FooTest#bar`) |

The structured target selector is translated into a controlled `-Dtest=<value>` flag. The agent
cannot pass `-Dtest=` directly via `flags` — the allowlist drops it. An invalid `targetKind`/`target`
pair returns `INVALID_TARGET` before any process is launched.

A concurrency guard prevents two overlapping `run_tests` calls on the same project path from
racing; the second caller receives `RESOURCE_BUSY` immediately.

### `build`

Compiles the project via the detected manager and returns a structured `Envelope`. On a compile
failure, compiler diagnostics are returned as `diagnostics[]` with `file`, `line`, `col`,
`severity`, and `message`. A successful build returns a minimal counts payload with no
`diagnostics[]` noise. Full compiler output is retained behind the `handle` for `get_log`.

### `install`

Installs Node.js dependencies via `npm install` and returns a structured `Envelope`. A successful
install returns a minimal counts payload (`manager:"npm"`, `installSummary:{added, removed,
changed}`). A failed install surfaces `INSTALL_FAILED` with npm output retained behind the `handle`.

### Git read verbs

Five read-only verbs expose structured git inspection, each returning a normalized envelope:

| Verb | Description |
|---|---|
| `git_status` | Working-tree status — branch, upstream, ahead/behind, staged/unstaged/untracked changes |
| `git_log` | Capped commit list (sha, short, author, dateIso, subject), newest first |
| `git_show` | Commit metadata and body; the diff is retrievable via `get_log(handle)` |
| `git_diff` | Structured diff summary (`gitDiff[]`) for working-tree vs HEAD; full patch via `get_log(handle)` |
| `git_branch` | Normalized branch list (name, current, upstream, ahead, behind) |

All git verbs are read-only and exempt from the concurrency lock.

### `get_log`

Expands a retained run result without re-running. Returns the requested slice from the session run
cache indexed by the `handle` returned with the previous `run_tests` result.

| Parameter | Type   | Required | Description |
|---|---|---|---|
| `handle`  | string | yes      | Opaque handle id from a previous `run_tests` result |
| `filter`  | string | no       | Test identity (`suite.name` or `name`); omit to get full raw output |

With `filter`: returns the full detail/stack trace for the matching failing test.
Without `filter`: returns the whole retained raw output (stdout + stderr).

Returns `null` when the handle is unknown or evicted from the run cache.

### Forge inspection (`pr_checks`, `pr_view`, `pr_diff`) — GitHub-first

Remote, read-only inspection of a code-hosting forge over HTTP (PRD-6, decision-log D62–D69).
**GitHub.com-only shippable** — see *GHES claim withheld* below.

| Verb | Description |
|---|---|
| `pr_checks(path?, ref?, repo?)` | CI check status for a PR / branch / commit. Folds check-runs **and** the Commit Statuses API, paginating to exhaustion — a failed check flips `ok=false`. The failing log is drilled into via `get_log(handle)` (retires the `gh run view --log-failed` pattern). |
| `pr_view(path?, pr?, repo?)` | PR metadata in one call — state, mergeable, review status, head/base, checks summary. |
| `pr_diff(path?, pr?, repo?)` | The PR's diff, behind a `handle` + `get_log` for large patches. |

#### Operator config — the first operator-provisioned config surface

Forge access is **fail-closed**: no configured instance means no forge access. The operator
authors a small external YAML listing allowlisted instances and points `MICRONAUT_CONFIG_FILES`
at it from the harness `env` block (`forge.instances[]`, bound via Micronaut
`@EachProperty(list=true)`, D63). A minimal example, one `github.com` entry:

```yaml
forge:
  instances:
    - baseUrl: https://api.github.com
      tokenEnv: GITHUB_TOKEN
```

`baseUrl` and an optional `apiPrefix` locate the instance; `tokenEnv` (optional) names an
environment variable holding a read-scoped token — never the secret inline. `http://` base URLs
are accepted **only** in the test profile; the production binding rejects them (TLS always).

#### Token-optional posture — honest tokenless budget

Token is **optional per allowlisted instance** (D62/D65). A tokenless instance still serves
**public** repos via unauthenticated GETs, at GitHub's 60 req/h unauthenticated rate limit; a
`pr_checks` call costs 3–5 GETs, so the honest budget is **~10–15 `pr_checks` inspections/hour
tokenless**. The tokened path adds private-repo access and keeps the cross-origin
no-`Authorization`-forward control on 302 redirects (D69). There is **no auto-retry** on rate
limiting — the `Retry-After` header is surfaced for the agent to decide.

#### Fail-clear error surface

Every forge failure returns a structured operational error, never an unstructured exception:

| `ErrorCode` | When it fires |
|---|---|
| `FORGE_HOST_NOT_ALLOWLISTED` | The resolved repo host is not in the operator allowlist; fails closed **before** any HTTP call (D64). |
| `FORGE_RATE_LIMITED` | 429 / 403-with-`Retry-After` / `X-RateLimit-Remaining:0`; surfaces `Retry-After`, never auto-retries (D65). |
| `FORGE_RESOURCE_NOT_FOUND` | 404 or 401 — e.g. a private repo queried tokenlessly returns 404; never folded into an empty check set. Hint: provision a read-scoped token (D66). |
| `FORGE_ORIGIN_UNRESOLVED` | No `origin` remote / unparseable URL and no `repo` override; fails closed before any HTTP. Hint: pass `repo` (owner/repo) or run from an allowlisted checkout (D67). |
| `FORGE_REQUEST_FAILED` | Any other non-2xx / I/O / malformed-body failure; fails closed rather than leaking an unstructured exception (D68). |

#### GHES claim withheld

The forge seams support a `{baseUrl, apiPrefix}` shape general enough for GitHub Enterprise
Server, and are stub-tested against it — but **only GitHub.com is claimed shippable**. GHES's
**pagination behavior, rate-limit headers, secondary limits, untrusted content** are unvalidated
against any real GHES instance (D62 sub-decision 7) — weaker evidence than, for example, the
win32 native tuple's real CI runner. GHES support is not claimed to work.

---

## Why: the problem it solves

Agents with an open Bash permission can compose arbitrary commands (`rm -rf`, `curl | sh`) with no
structural limit. This server replaces Bash-mediated build/test operations with typed verbs:

- The agent expresses intent (`run_tests`), not a command string.
- The server validates, guards, and invokes the system manager via a trusted launcher.
- Untrusted repo-derived content in the response (test names, messages, paths) is neutralized
  before it reaches the agent (P9 outbound neutralization).
- The token-efficient envelope separates **signal** (actionable `file:line`, assertion diffs,
  failing test identity) from **noise** (framework frames, progress bars), and defers raw detail
  to `get_log` via a `Handle`.

---

## Platform support

The native binary is built and acceptance-tested in CI on four target tuples (GraalVM JDK-25;
native-image does **not** cross-compile, so each tuple is built on its own runner).

### Supported tuples (npm/npx channel — v0.0.1-alpha.2 published)

| Tuple | Native binary | `run_tests`: Maven / Go / Node |
|---|---|---|
| `linux-x64`    | ✅ static (`--static-nolibc`, portable across glibc hosts) | ✅ all three |
| `linux-arm64`  | ✅ static (`--static-nolibc`)                              | ✅ all three |
| `darwin-arm64` | ✅ system-dynamic, ad-hoc codesigned                       | ✅ all three |
| `win32-x64`    | ✅ system-dynamic (`no-bash-mcp.exe`)                      | ⚠️ **Go only** — see below |

**Windows caveat (`win32-x64`).** Maven and Node tests do **not** run from the native Windows
binary. Their launchers (`mvn.cmd`, `npx.cmd`) are `.cmd` shims, and the server spawns launchers
directly with **no shell** (the trusted-launcher security posture, ADR-0008) — but Windows
`CreateProcess` only ever executes `.exe`, never a `.cmd`, without a shell. `go` (a real `go.exe`)

works. Maven/Node `run_tests` (and `build`/`install`) is therefore **unsupported on the native
Windows binary**: the resolver finds `mvn.cmd`/`npx.cmd` on PATH, but the binary cannot spawn a
`.cmd` without a shell, so the launch **fails closed with a structured `MANAGER_NOT_SPAWNABLE`
operational error** — naming the launcher and pointing at the JVM-jar / WSL2 remedy, never an
unstructured exception (#71). On Windows, use the JVM jar (`java -jar`) for Maven/Node projects, or
run the native binary under **WSL2** (a `linux-x64` / `linux-arm64` environment).

### Unsupported tuples — fail-clear

`win32-arm64` and `darwin-x64` (Intel) do not have a GraalVM JDK-25 toolchain (`win32-arm64` has
none; `darwin-x64` is deprecated upstream — 25.0.1 was the last release). No platform package is
produced for these tuples. When the launcher runs on one of them it emits a structured JSON error on
stderr (exit code 78 / `EX_CONFIG`) and exits immediately — it never starts a half-open channel:

```json
{
  "error": "no-bash-mcp-launcher",
  "reason": "unsupported-platform",
  "platform": "<tuple>",
  "supported": ["linux-x64", "linux-arm64", "darwin-arm64", "win32-x64"],
  "hint": "No native binary is produced for this OS/arch. Run the server from the JVM jar instead (java -jar no-bash-mcp.jar), or, on win32-arm64, the win32-x64 binary under emulation."
}
```

`win32-arm64` users can run the `win32-x64` build under Windows x64 emulation as a workaround
(US11). A JVM-jar published distribution channel is **deferred** to the roadmap (it would
reintroduce the JRE dependency native exists to kill — YAGNI until evidence of a real user
on an uncovered platform).

### Launcher footprint trade (D45)

The npm/npx channel keeps a **thin Node shim process** in front of the native MCP binary for the
whole session (it pipes stdio), adding roughly **30–50 MB RSS** plus the Node dependency. The
server's *operations* run in the native binary; Node is the delivery and session-level bridge.
This is an accepted, eyes-open trade — esbuild does the same, and Node is present anyway because
Claude Code itself ships via npm. See [ADR-0010](./docs/adr/0010-npm-launcher-distribution.md) and
[`docs/design/build-and-distribution.md`](./docs/design/build-and-distribution.md).

### Release artifacts

Pushing a version tag (`v*`) runs the full 4-tuple matrix as a release gate
(a red acceptance IT on any tuple blocks the release) and publishes both the GitHub Release assets
and the npm packages:

| Asset / package | Tuple |
|---|---|
| `no-bash-mcp-linux-x64` / `@no-bash-mcp/linux-x64`     | linux-x64 (static)    |
| `no-bash-mcp-linux-arm64` / `@no-bash-mcp/linux-arm64`  | linux-arm64 (static)  |
| `no-bash-mcp-darwin-arm64` / `@no-bash-mcp/darwin-arm64`| darwin-arm64 (signed) |
| `no-bash-mcp-win32-x64.exe` / `@no-bash-mcp/win32-x64`  | win32-x64             |
| `SHA256SUMS`                                             | integrity manifest    |

---

## Registering the server (manual)

Registration is a manual step — the Bootstrap skill (#78, not yet landed) that will auto-write
`.mcp.json` is not yet available. Add the server block by hand.

### 1. Register in your harness (example: Claude Code `settings.json`)

Add the server under `mcpServers` in your harness configuration. The server communicates over
STDIO. Use the **npx channel** (primary, available now — PRD-5 S3, [ADR-0010]):

```json
{
  "mcpServers": {
    "no-bash-mcp": {
      "command": "npx",
      "args": ["-y", "no-bash-mcp@0.0.1-alpha.2"]
    }
  }
}
```

The pin (`0.0.1-alpha.2`) is **exact, never `@latest`** (D42/D37) — a security-critical binary
must not auto-update silently; bump the pin explicitly when upgrading. npm's `os`/`cpu` fields
select the correct platform package automatically on install.

**Uncovered platforms.** On `win32-arm64` or `darwin-x64` (Intel), no native binary is available
(see *Unsupported tuples — fail-clear* above). The launcher emits a structured JSON error on stderr
and exits without starting a channel. Those platforms may use the JVM jar as a manual fallback
(build from source with `mvn package -DskipTests` and run `java -jar target/no-bash-mcp-<version>-SNAPSHOT.jar`);
a published JVM-jar distribution channel is deferred to the roadmap.

### 2. Remove the Bash permission (the point of the server)

After confirming the MCP verbs work, **manually remove the agent's Bash permission** from your
harness configuration. The exact mechanism is harness-specific (e.g. a `permissions.deny` entry
in Claude Code's `settings.json`). Keeping Bash enabled alongside the MCP defeats the purpose.

**Deny-list narrowing (PRD-6, D62 sub-decision 9).** Forge read-only retires the `gh` **read**
patterns — each now has a direct MCP replacement:

| Retired `gh` read pattern | Replacement |
|---|---|
| `gh run view --log-failed` | `pr_checks` + `get_log` |
| `gh pr view` | `pr_view` |
| `gh pr diff` | `pr_diff` |
| `gh pr checks` | `pr_checks` |

`gh` **write** commands (`gh issue create`, `gh pr create`, `gh pr merge`, …) **stay bridged** —
PRD-6 is read-only and does **not** advance removing Bash for `gh` on its own behalf; mutating-git
and mutating-`gh` remain a later, not-yet-shipped candidate (D32/D46/D62 sub-decision 9). Deny only
the retired read patterns above; leave `gh` write commands allowed until that later work ships.

---

## v1 scope and roadmap

Shipped in v1:
- `run_tests` — Maven, Node (jest), Go
- `build` — Maven (compile diagnostics via `diagnostics[]`)
- `install` — Node/npm dependency install
- `git_status`, `git_log`, `git_show`, `git_diff`, `git_branch` — read-only git inspection
- `get_log` — drill-down into any retained run result via a `handle`
- P9 outbound neutralization of untrusted repo-derived strings
- Concurrency guard (`RESOURCE_BUSY`) on overlapping runs

Also shipped (PRD-5 S3):
- npm/npx native binary launcher (`no-bash-mcp` + `@no-bash-mcp/<os>-<arch>` platform packages, v0.0.1-alpha.2; [ADR-0010](./docs/adr/0010-npm-launcher-distribution.md))

Also shipped (PRD-6, GitHub-first, decision-log D62–D69):
- Forge inspection — `pr_checks`, `pr_view`, `pr_diff` (GitHub.com-only; GHES seams built, claim withheld — see above)
- `forge.instances[]` operator config surface, token-optional posture, fail-clear `FORGE_*` error codes

Not yet available (roadmap, not shipped):
- `lint`, `run_task` verbs
- `describe_project`, `dependencies`
- Mutating-git / mutating-`gh` verbs (deny-list retirement for `gh` write commands)
- GHES / GitLab forge instances (seams built for GHES; claim withheld — see *Forge inspection* above)
- Bootstrap auto-write of `.mcp.json` for the npx channel (#78, halted/needs-triage)
- Published JVM-jar distribution channel (for uncovered platforms; deferred — YAGNI)

---

## Going deeper

The corpus documents every design decision; the README only summarizes the shipped surface. Many of
these documents carry **Mermaid diagrams** (color-keyed structural, flow, and state views),
so the corpus is as much a **visual study book** as a written one — start with the worked-example
lesson below for the architecture read at a glance.

| Document | What it covers |
|---|---|
| [`docs/lessons/`](./docs/lessons/) | Worked-example lessons that read the ADRs and decision-log back as teachable principles — resilience, security, scalability of an agentic backend — with Mermaid diagrams throughout |
| [`DESIGN.md`](./DESIGN.md) | Architecture (hexagonal, package structure, schema, output contract, testing posture, version baseline), with structural and flow diagrams |
| [`CONTEXT.md`](./CONTEXT.md) | Ubiquitous language — what every term means (Verb, Manager, Reporter, Envelope, Handle, Guardrail, …) |
| [`docs/design/`](./docs/design/) | Pillars, security model, forge security model, tool catalog, operational model, schema divergence map, roadmap, gotchas, decision log — several with diagrams |
| [`docs/adr/`](./docs/adr/) | Architecture Decision Records (0001–0011) |
| [`docs/research/`](./docs/research/) | Empirical grounding: architecture survey, technology baseline, testing stack, GraalVM/WSL setup |
| [`prototype/NOTES.md`](./prototype/NOTES.md) | Schema/port validation from three real reports (Maven, jest, Go) |
| [`spikes/`](./spikes/) | De-risking spike outcomes (universal schema, MCP STDIO, forge read-only) |
