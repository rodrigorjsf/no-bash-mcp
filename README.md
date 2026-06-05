# No bash MCP

![](assets/readme-logo.png)
---

> **v1 in progress.** The shipped surface is `run_tests` (Maven) and `get_log`. Node/Go/forge verbs
> and a native binary release are roadmap items, not yet available.

A Micronaut MCP server that replaces an agent's Bash tool with safe, structured, token-efficient
operations so the Bash permission can be removed entirely. The agent never composes a command; the
server detects the environment and maps a typed verb to a controlled invocation.

---

## What ships in v1

Two verbs are available over a STDIO JSON-RPC channel:

### `run_tests`

Runs a project's tests via the detected Maven manager. Returns a structured `Envelope` with one of
three shapes: **success** (counts only), **test-failure** (normalized `failures[]` with
`file:line`, assertion message, and a `handle` for drill-down), or **operational-error** (enumerated
`code` + actionable `hint`).

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
STDIO; `java -jar` is the only launcher.

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
- `run_tests` — Maven only
- `get_log` — drill-down into any retained `run_tests` result
- P9 outbound neutralization of untrusted repo-derived strings
- Concurrency guard (`RESOURCE_BUSY`) on overlapping runs

Not yet available (roadmap, not shipped):
- Node/npm/yarn/pnpm (`run_tests` via jest/vitest/mocha)
- Go (`run_tests` via `go test -json`)
- `build`, `install`, `lint`, `run_task` verbs
- Forge inspection (`pr_checks`, `pr_view`, `pr_diff`)
- Git read verbs (`git_log`, `git_diff`, `git_show`, etc.)
- `describe_project`, `dependencies`
- GraalVM native binary release

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
