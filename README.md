# No bash MCP

![](assets/readme-logo.png)
---

> **v1 shipped.** The shipped surface is `run_tests` (Maven, Node/jest, Go), `build`, `install`,
> the five git read verbs, and `get_log`. Forge verbs and the npm/npx native binary launcher
> (PRD-4 #44) are roadmap items, not yet available.

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
native-image does **not** cross-compile, so each tuple is built on its own runner). The JVM jar
(`java -jar`) runs anywhere a JDK 25 runs and has none of the per-tuple caveats below.

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
works. This is a documented, fail-clear limitation, not a silent failure: a Maven/Node `run_tests`
on Windows returns a clear operational error. On Windows, use the JVM jar (`java -jar`) for
Maven/Node projects, or run the native binary under **WSL2** (a `linux-x64` / `linux-arm64`
environment).

**Not produced.** `win32-arm64` and `darwin-x64` native binaries are intentionally not built; use
the JVM jar on those platforms.

---

## Registering the server (manual)

v1 requires manual MCP registration. There is no Bootstrap skill yet — registration and Bash
permission removal are done by hand.

### 1. Build the server jar

```bash
mvn package -DskipTests
```

The packaged jar lands at `target/no-bash-mcp-0.1.0-SNAPSHOT.jar`.

### 2. Register in your harness (example: Claude Code `settings.json`)

Add the server under `mcpServers` in your harness configuration. The server communicates over
STDIO. **Interim launcher (until PRD-5 #44 ships):** `java -jar` is the current way to run the
server. The decided distribution channel is npm/npx (ADR-0010) — the npm/npx native launcher
ships in PRD-5 (#44), consuming the signed native artifacts PRD-4 (#57) produces, and will replace
this `java -jar` step.

```json
{
  "mcpServers": {
    "no-bash-mcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/no-bash-mcp-0.1.0-SNAPSHOT.jar"]
    }
  }
}
```

Replace `/absolute/path/to/` with the actual location of the jar.

### 3. Remove the Bash permission (the point of the server)

After confirming the MCP verbs work, **manually remove the agent's Bash permission** from your
harness configuration. The exact mechanism is harness-specific (e.g. a `permissions.deny` entry
in Claude Code's `settings.json`). Keeping Bash enabled alongside the MCP defeats the purpose.

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

Not yet available (roadmap, not shipped):
- `lint`, `run_task` verbs
- Forge inspection (`pr_checks`, `pr_view`, `pr_diff`)
- `describe_project`, `dependencies`
- npm/npx native binary launcher (PRD-5 #44, ADR-0010); PRD-4 (#57) ships the signed native artifacts it consumes

---

## Going deeper

The corpus documents every design decision; the README only summarizes the shipped surface.

| Document | What it covers |
|---|---|
| [`DESIGN.md`](./DESIGN.md) | Architecture (hexagonal, package structure, schema, output contract, testing posture, version baseline) |
| [`CONTEXT.md`](./CONTEXT.md) | Ubiquitous language — what every term means (Verb, Manager, Reporter, Envelope, Handle, Guardrail, …) |
| [`docs/design/`](./docs/design/) | Pillars, security model, forge security model, tool catalog, operational model, schema divergence map, roadmap, gotchas, decision log |
| [`docs/adr/`](./docs/adr/) | Architecture Decision Records (0001–0008) |
| [`docs/research/`](./docs/research/) | Empirical grounding: architecture survey, technology baseline, testing stack, GraalVM/WSL setup |
| [`prototype/NOTES.md`](./prototype/NOTES.md) | Schema/port validation from three real reports (Maven, jest, Go) |
| [`spikes/`](./spikes/) | De-risking spike outcomes (universal schema, MCP STDIO, forge read-only) |
