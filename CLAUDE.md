# Working Agreement

This repository is **documentation-first** and **agent-first**. These rules are binding for every
contributor, human or agent. All durable artifacts are written in **English**.

## Principles

- **Documentation-first.** A decision is not "made" until it is written down. Design decisions live
  in [`docs/design/`](./docs/design/); the chosen architecture lives in `DESIGN.md` (generated in
  the before-coding phase, see below). Code follows documentation, never the reverse.
- **Agent-first.** The product *and* the repository are designed for agent consumption: structured,
  token-efficient, sufficiency-first. The same discipline applies to docs and tool contracts.
- **Implementation discipline (mandatory):**
  - **TDD** — red → green → refactor (`/tdd`). No production code without a failing test first.
  - **Clean Code** — intention-revealing names, small units, no dead code.
  - **YAGNI** — build only what a decided requirement needs. Roadmap items stay in the roadmap.
  - **KISS** — the simplest design that satisfies the decision. Prefer boring over clever.
- **Architecture from research, not invention.** The architecture is **chosen from a survey** of
  current, well-regarded Java application architectures and real MCP-server projects — with an
  explicit rationale for the choice — not improvised.

## Pipeline (grill → architecture → code)

1. **Grilling** — resolve every load-bearing decision. Captured in [`docs/design/`](./docs/design/)
   (pillars, security model, tool catalog, gotchas, roadmap, decision log).
2. **Before-coding analysis** (after grilling concludes):
   - **Research** the main Java / MCP-server architectures in use today; choose one with a written
     rationale.
   - Run **`/prototype`** to flesh out the chosen design before committing to it.
   - Produce **`DESIGN.md`** — the architecture document — from that analysis. This is the
     "before coding" deliverable; implementation does not start until it exists.
3. **Implementation** — strictly via **`/tdd`**, honoring Clean Code / YAGNI / KISS, against
   `DESIGN.md` and the decisions in `docs/design/`.

## Source of truth

- **What & why** of the product decisions → [`docs/design/`](./docs/design/).
- **How** it is structured in code → `DESIGN.md`.
- **Empirical grounding** → [`docs/research/`](./docs/research/).

## Documentation conventions

- **Diagram by default.** Whenever a document would be clearer with a structural, flow, sequence, or
  state view, include a **Mermaid** diagram — not prose alone. Applies to `DESIGN.md`, ADRs,
  `docs/design/`, READMEs, and handoffs, wherever a diagram is applicable.
- **Mermaid styling & compatibility** (theme-neutral colors, animation rules, canonical skeletons):
  see [`.claude/rules/diagrams.md`](./.claude/rules/diagrams.md) — auto-loads when editing `*.md`.

## Agent skills

### Issue tracker

Issues and PRDs are tracked as GitHub issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical triage roles using their default label names. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` + `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Applied Learning

When something fails repeatedly, when User has to re-explain, or when a workaround is found for a platform/tool limitation, add a one-line bullet here. Keep each bullet under 15 words. No explanations. Only add things that will save time in future sessions.

- After `git pull`, run `mvn clean test` — stale `target/` false-REDs `EnvelopeSerde`.
- Standalone issue: bare `/orchestrate`; `/orchestrate <N>` only filters PRD #N children.
