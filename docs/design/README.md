# no-bash-mcp — Design

> Status: **DRAFT — grilling in progress.** These documents grow as design decisions are resolved.
> Every locked decision maps to an explicit choice recorded in
> [`decision-log.md`](./decision-log.md).
>
> **This folder is the *decisions* layer (what & why).** The *architecture* layer (how it is
> structured in code) is `DESIGN.md`, generated in the before-coding phase (architecture research +
> `/prototype`). See the working agreement in [`../../CLAUDE.md`](../../CLAUDE.md).

## Vision / Proposal

An MCP server (Micronaut MCP 1.0.0 over STDIO, Micronaut 5 / Java 25 / Maven, GraalVM native image
at release) that replaces the agent's **Bash** tool with a small set of **safe, structured,
token-efficient** tools.
The goal is to let users **remove the Bash permission** from LLM agents — especially in
**autonomous flows** — so the agent cannot *decide on its own* to run a dangerous or malicious
command, while still doing the legitimate work of an implementation loop (run tests, build, inspect
dependencies, read git state, run project-defined tasks).

The MCP is a **guardrail + efficiency layer**, not a sandbox. It is **not** responsible for what the
user instructs the agent to do, nor for scripts the user authors — that remains the user's
responsibility. Its job: provide efficiency and a security guardrail for the well-known commands.

## Pillars / Tenets

- **P1 — Guardrail, not sandbox.** Prevents *agent-composed* dangerous commands. Project code
  (`npm test`, postinstall hooks) still runs; that is accepted and unavoidable.
- **P2 — No agent-composed shell strings.** Every process is launched via `ProcessBuilder` with an
  explicit **argv array** built by the MCP. The agent never supplies a command line, and the MCP
  never composes a shell string from agent input — no `/bin/sh -c`, no native pipes. (The invariant
  is *no agent-controlled shell string*, not *no OS process facility ever* — Windows shim handling
  is gotcha **G13**.)
- **P3 — Allowlist over denylist** for the MCP's own surface. Fail-closed.
- **P4 — Sufficiency-first, noise-truncated.** A return must let the agent act in **one
  round-trip**. Truncate *noise* (framework frames, progress bars, passing-test chatter), **never**
  *signal* (`file:line`, assertion diffs, project frames). Token-efficiency comes from removing
  noise and on-demand drill-down — **never** from lossy summarization.
- **P5 — Logical verbs, manager-agnostic.** The agent thinks in *intent*; the MCP detects the
  environment and maps to the right tool.
- **P6 — Structured, normalized output.** One schema across ecosystems. Typed fields also serve as
  prompt-injection defense (repo content lands as data, not instructions).
- **P7 — Cross-platform by construction.** No shell and no bash scripts in shipped artifacts.
- **P8 — Security policy is compiled & auditable.** Project config may only tune non-sensitive
  knobs; it can never introduce a new command or flag.
- **P9 — Untrusted-by-default.** All repo-derived content returned to the agent is neutralized and
  marked untrusted.

## Documents

| Doc | Context |
|---|---|
| [`security-model.md`](./security-model.md) | Guarantee, execution mechanism, flag policy, policy governance, input validation, prompt-injection defense. |
| [`tool-catalog.md`](./tool-catalog.md) | v1 tools, output contract, result source. |
| [`operational-model.md`](./operational-model.md) | Install model, scoping, timeout, run cache. |
| [`build-and-distribution.md`](./build-and-distribution.md) | JVM dev vs GraalVM native at release, packaging. |
| [`bootstrap-and-deployment.md`](./bootstrap-and-deployment.md) | Bootstrap skill, transitional git guardrail, harness-agnostic deployment. |
| [`gotchas.md`](./gotchas.md) | Traps and why they are traps. |
| [`roadmap.md`](./roadmap.md) | Post-v1 evolutions + open questions. |
| [`decision-log.md`](./decision-log.md) | Every locked decision with rationale. |
| [`../research/roundtrip-waste-evidence.md`](../research/roundtrip-waste-evidence.md) | Empirical evidence from local agent transcripts. |
