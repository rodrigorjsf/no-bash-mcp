# Bootstrap & Deployment

A thin companion **skill** makes the "remove Bash" posture actually deployable. It is part of the
v1 deliverable but a distinct component from the MCP.

## Responsibilities (v1, thin)

1. **Register the MCP** in the harness config (e.g. `.mcp.json` for Claude Code).
2. **Write a transitional dangerous-git deny-list** — a guardrail for the window where the agent
   still uses Bash for git (because git **mutation** is post-v1). Explicitly marked as temporary.
3. **Suggest removing the Bash permission** once MCP coverage is sufficient.

## Mechanism — declarative deny-list, not a hook

The transitional git guardrail is implemented as a **declarative deny-list** in the harness's own
permission config — **not** a `PreToolUse` bash hook. The hook is disqualified for a cross-OS tool:

- It is **bash** → breaks on Windows-native (violates pillar P7).
- It is **regex-on-command-string** → the same minefield rejected for the MCP itself (gotcha **G7**,
  **G1**).

A declarative deny-list has neither problem: no shell, no fragile regex, evaluated by the harness.

## Harness-agnostic

The MCP itself is **portable** (MCP is a standard protocol). But the Bash guardrail is
**harness-coupled** — every harness expresses permissions differently. So the bootstrap skill
**detects which harness is in use** and writes the correct config through a **per-harness adapter**.
Claude Code adapter ships first.

## Lifecycle note

The deny-list is a **denylist** — fragile and harness-coupled by nature (gotcha **G6**). It exists
only until git mutation moves into the MCP (allowlist-based, harness-agnostic). Retiring it is a
driver for prioritizing native mutating-git verbs post-v1.
