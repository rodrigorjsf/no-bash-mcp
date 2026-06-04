# no-bash-mcp

The ubiquitous language of `no-bash-mcp` — a Micronaut MCP server that replaces an agent's Bash
tool with safe, structured, token-efficient operations so the Bash permission can be removed.

This file is a **glossary only**. It defines what terms *mean*, never how they are implemented.
Decisions and rationale live in [`docs/design/`](./docs/design/) and [`docs/adr/`](./docs/adr/);
the architecture lives in `DESIGN.md`.

## Language

### Dispatch & detection

**Verb**:
A logical, manager-agnostic operation the agent invokes by *intent* (`run_tests`, `build`,
`install`, `lint`, `dependencies`, `describe_project`, `get_log`, git inspection). The MCP detects
the environment and maps the verb to a concrete invocation. The unit of the tool catalog.
_Avoid_: command (an agent never composes a command — that is the whole point), action, operation
(reserve "operation" for the flag-policy phrasing only).

**Mode**:
A sub-selector inside a single verb (`dependencies(mode=why)`). Used **only** when the variants
share both argument shape and output schema; when they diverge, prefer separate verbs.
_Avoid_: subcommand, action.

**Manager**:
A concrete package/build tool detected at a path (`mvn`, `npm`, `pnpm`, `yarn`, `go`). One
ecosystem may have several interchangeable managers. The `Envelope`'s `manager` field holds this
(e.g. `"mvn"` — never `"maven"`, which is the ecosystem).
_Avoid_: package manager, build tool, tool (when you mean the manager — see **Reporter**).

**Reporter**:
The test tool that actually **produces** the machine-readable report the adapter parses (`surefire`
for Maven, `jest`/`vitest`/`mocha` for Node, `go` for Go test). Distinct from the **Manager** (which
launches the build) and the test **framework** (which the tests are written in, e.g. JUnit). The
frozen `NormalizedRun.tool` field holds the Reporter — its name is the one place the codebase keeps
the word "tool", and it means the Reporter, **never** the Manager.
_Avoid_: tool (as a free word), runner, framework (when you mean the reporter).

**Ecosystem**:
A family of managers and conventions that share report formats and detection markers
(JVM/Maven, Node, Go). The unit at which adapters and de-risking are reasoned about.
_Avoid_: language, stack, platform, runtime.

**Adapter**:
The per-ecosystem (and, where frameworks diverge, per-test-framework) unit that maps a verb to a
concrete invocation, injects the reporter, locates the report, and normalizes it into the common
schema. _Distinct compound term:_ a **harness adapter** is the bootstrap-side unit that writes
permission config for a specific agent harness — a different sense, never shortened to "adapter".
_Avoid_: plugin, driver, connector.

### Output contract

**Envelope**:
The common result shape every verb returns (`ok`, `verb`, `manager`, `summary`, `handle?`, …).
Uniform across ecosystems so the agent parses one shape.
_Avoid_: response, payload, wrapper.

**Handle**:
An opaque token returned with a verb result that lets the agent retrieve the full, unsummarized
output of that run **later, without re-running it**. The keystone of non-lossy token efficiency.
_Avoid_: log_handle, run id, token, ref.

**Run cache**:
The transient, session-scoped retention of full run results (report + stdout + stderr) that a
handle points into. Bounded (last-N / TTL / byte cap); never durable config.
_Avoid_: store, database, history.

**Signal**:
The actionable content of a run the agent needs to act in one round-trip — `file:line`, assertion
diffs, failing test identity, project-side stack frames, error `code` + `hint`. Never truncated.
_Avoid_: output, details.

**Noise**:
Non-actionable content — framework stack frames, progress bars, passing-test chatter, ANSI. Freely
truncated. Token efficiency comes from removing noise, **never** from summarizing signal.
_Avoid_: verbosity, chatter (informal only).

**Operational error**:
A failure of the *operation itself* — enumerated `code` (`NO_MANAGER_DETECTED`, `TOOL_NOT_INSTALLED`,
`DEPS_NOT_INSTALLED`, `REPORT_NOT_PRODUCED`, `TIMEOUT`, `INVALID_PATH`, `AMBIGUOUS_SCOPE`, …) plus a
`hint`. Deliberately distinct from a test failure so the agent branches deterministically.
_Avoid_: error (unqualified), exception, tool error.

**Test failure**:
A failing test surfaced inside a successful run — a normalized entry in `failures[]`. Not an
operational error; the verb ran fine, a test did not pass.
_Avoid_: error, failure (unqualified).

**Untrusted content**:
Any repo-derived string returned to the agent (test names, messages, paths, stderr). Treated as
data, never instructions; neutralized and marked in the envelope (prompt-injection defense).
_Avoid_: input, user content.

### Security framing

**Guardrail**:
The project's actual guarantee — it prevents the *agent* from composing novel dangerous commands.
It is **not** a sandbox and does not claim "zero dangerous code runs" (project code still executes).
_Avoid_: sandbox, jail, isolation (these claim a stronger, false guarantee).

**Agent-composed**:
A command the agent itself invented (`rm -rf`, `curl | sh`). The load-bearing qualifier of the
guarantee: agent-composed novel commands are impossible; project-authored code running under a
sanctioned verb is accepted.
_Avoid_: arbitrary, user-defined (a project task is human-authored, not agent-composed).

**Composition-safe**:
A `run_task` body cannot be a *novel* command (its body is project-authored). True by construction.
_Avoid_: safe (unqualified).

**Consequence-safe**:
Running an operation causes no autonomous damage. **Not** implied by composition-safe — a
project-defined `deploy:prod` is composition-safe yet catastrophic. The gap that forces `run_task`
to be opt-in, fail-closed.
_Avoid_: safe (unqualified).

**Core verb**:
One of the always-available sanctioned operations (`run_tests`, `build`, `install`, `lint`).
Contrast with a **project-defined task**, which is runnable only when a human opts it into the
allowlist.
_Avoid_: built-in, default task.

### Deployment

**Harness**:
The agent runtime that hosts the MCP and enforces permissions (Claude Code, etc.). Permissions are
expressed per-harness; the MCP protocol itself is portable, the permission config is not.
_Avoid_: client, host, IDE, agent (the agent is the LLM; the harness is its runtime).

**Bootstrap skill**:
The thin companion skill that registers the MCP, writes the transitional git deny-list, and
suggests removing the Bash permission. A distinct deliverable from the MCP server.
_Avoid_: installer, setup, plugin.

### Forge inspection

**Forge**:
A code-hosting platform the MCP inspects **read-only over HTTP** (GitHub.com, GitHub Enterprise
Server, GitLab SaaS, GitLab Self-Managed/Dedicated). Distinct from an ecosystem (a locally-detected
build/test toolchain); a forge is a remote, authenticated service.
_Avoid_: provider, git host (a forge is more than git — CI, PRs/MRs, checks), remote.

**Forge verb**:
A fixed, read-only forge operation (CI check status, failed-job log via `handle`, PR/MR view, PR/MR
diff). The MCP exposes **no** generic HTTP passthrough — a passthrough would let the agent compose
an arbitrary request and break the forge guarantee.
_Avoid_: api, passthrough, escape hatch (deliberately absent).

**Forge adapter**:
The per-forge unit that maps a forge verb to authenticated REST/GraphQL calls and normalizes the
result into the common envelope. Built on configurable seams — base URL, auth, TLS trust, proxy,
tier/version — so SaaS and self-hosted (GHES, GitLab Self-Managed) share one abstraction. Distinct
from an ecosystem adapter (local subprocess) and a harness adapter (permission config).
_Avoid_: connector, client, integration.

**Forge guarantee**:
The forge-side counterpart to the command-execution guarantee: the agent cannot compose an arbitrary
HTTP request; it can only trigger fixed read-only forge verbs against a configured, allowlisted
instance. Enforced at the token layer, not by hiding verbs.
_Avoid_: forge sandbox.

**Read-scoped token**:
A forge credential limited to read operations (`read_api` on GitLab; a read-only fine-grained PAT on
GitHub). A hard capability boundary the agent cannot exceed even under prompt injection.
_Avoid_: api token (unqualified), full token.

**Instance** (forge):
A specific forge deployment addressed by base URL (`github.com`, `ghe.corp`, `gitlab.com`,
`gitlab.internal`). Config and credentials are per-instance; multiple instances coexist.
_Avoid_: host (overloaded with harness/OS), server, endpoint.
