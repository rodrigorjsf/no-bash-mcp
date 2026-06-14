# Working Agreement

This repository is **documentation-first** and **agent-first**. These rules are binding for every
contributor, human or agent. All durable artifacts are written in **English**.

## Principles

- **Documentation-first.** A decision is not "made" until it is written down. Design decisions live
  in [`docs/design/`](./docs/design/); the chosen architecture lives in `DESIGN.md` (generated in
  the before-coding phase, see below). Code follows documentation, never the reverse. READMEs are
  for humans, so they and any other User-scoped documentation must always be in-sync with code and the current
  state of the repository.
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

## Versioning policy (binding)

The project is **pre-1.0 / in development**. Until there is a **minimally stable first delivery**
**and** the maintainer's **explicit approval** to ship 1.0.0:

- **The MAJOR version MUST stay `0`.** No contributor — human or agent — may tag or publish a
  `v1.0.0` or higher (any `major >= 1`) release. This is **not** a judgement call; it requires the
  maintainer lifting this rule first.
- **In-development releases use `0.MINOR.PATCH`** and SHOULD carry a SemVer **pre-release identifier**
  to signal maturity: `-alpha.N` (early, unstable), `-beta.N` (feature-stabilizing, under test),
  `-rc.N` (release candidate). SemVer orders them `alpha < beta < rc < (final)`, so the ladder is
  self-sorting. Avoid non-standard tokens such as `omega` — they have no defined SemVer precedence and
  break tooling that relies on the standard ladder.
- **Enforcement is fail-closed in the release pipeline.** `native-release.yml` rejects any tag whose
  major version `>= 1` until this rule is lifted, so an accidental major bump cannot publish. The
  exact-version pin (D42) and CI version-stamping (D56/D57) operate within `0.x`.

To lift the rule: the maintainer approves the 1.0.0 bump, this section is amended, and the pipeline
guard is updated in the same change.

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

## Automation scripts (`scripts/`)

Deterministic, repeatable agent-ops procedures live in [`scripts/`](./scripts/). **Prefer the
script over re-deriving the steps** — each one encodes hard-won traps so they are not re-suffered.
Every script carries a `WHAT / WHY / WHEN / HOW` header comment; read it before first use.

| Script | Use it when |
|---|---|
| [`scripts/npm`](./scripts/npm) | You need `npm`/`npx` from the Bash tool or any non-interactive shell. Plain `npm` resolves a broken shim there (the working `npm` exists only in interactive fish via `mise activate`). `scripts/npm exec -- <pkg>` is the `npx` equivalent. |
| [`scripts/verify-npm-release.sh`](./scripts/verify-npm-release.sh) `<version>` | A `v*` release run is green and you must close the **verification ceiling** — proves all 5 packages are live, provenance attached, and `latest` not hijacked by a prerelease. A green CI job alone is not proof. |
| [`scripts/release-mcp.sh`](./scripts/release-mcp.sh) `<version> [--dry-run]` | Cutting an in-dev release. Enforces the D58 pre-1.0 policy (fail-closed on `major>=1`), tags+pushes, polls the run to its **real** conclusion (never trusts `gh run watch`), then runs the registry verify. Scoped-package OIDC bootstrap is HITL and out of scope (see the handoff). |
| [`scripts/mermaid-gate.sh`](./scripts/mermaid-gate.sh) `[file.md ...]` | After adding/editing any Mermaid diagram, before committing. Extracts every block and renders to PNG (fails on parse error); then eyeball `/tmp/mermaid-gate/*.png`. Needs `libasound2t64`. Pairs with [`.claude/rules/diagrams.md`](./.claude/rules/diagrams.md). |

**Standing rule — export repeatable procedures.** Whenever you hit a multi-step procedure that is
deterministic and likely to recur (release, registry verification, a gating/render check, an
environment workaround), **capture it as a `scripts/` script** with a `WHAT / WHY / WHEN / HOW`
header comment instead of re-deriving it inline, and add a row to the table above. If the script is
also useful to a human maintainer, the header comment is its documentation. This saves tokens and
makes the procedure auditable and reproducible.

## Applied Learning

When something fails repeatedly, when User has to re-explain, or when a workaround is found for a platform/tool
limitation, add a one-line bullet here. Keep each bullet under 15 words. No explanations. Only add things that will save
time in future sessions.

- After `git pull`, run `mvn clean test` — stale `target/` false-REDs `EnvelopeSerde`.
- Standalone issue: bare `/orchestrate`; `/orchestrate <N>` only filters PRD #N children.
- gh blocked by WSL DNS hijack? `curl --resolve api.github.com:443:<github-IP>` + `gh auth token` (git-SSH unaffected).
- STDIO-driving IT: drain stdout line-by-line (`BufferedReader`), not `readAllBytes` (blocks till process exit).
- Native binary build: `mvn package -Dpackaging=native-image` (single flag); needs `JAVA_HOME`=GraalVM + `zlib1g-dev`.
- Non-interactive shell (Bash tool): use `scripts/npm`, not `npm` (mise activates only in interactive fish).
- Verifying/cutting an npm release? Use `scripts/release-mcp.sh` / `scripts/verify-npm-release.sh`, not ad-hoc commands.
- After editing Mermaid diagrams, run `scripts/mermaid-gate.sh` before committing (needs `libasound2t64`).
