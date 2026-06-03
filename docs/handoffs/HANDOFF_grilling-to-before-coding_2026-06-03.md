# Handoff: no-bash-mcp — grilling complete, entering before-coding

**Created:** 2026-06-03
**Branch:** master (greenfield; first commit `6cfea71`)
**Phase:** Grilling COMPLETE → next is the before-coding phase (research → `/prototype` → `DESIGN.md` → `/tdd`)

---

## Summary

`no-bash-mcp` is a Micronaut MCP server that replaces an LLM agent's **Bash** tool with safe,
structured, token-efficient tools so the Bash permission can be removed (especially in autonomous
flows). A full **grilling** session resolved every load-bearing design decision; they are documented
in `docs/design/` and committed (`6cfea71`). This handoff exists so a **new session continues
seamlessly**.

---

## ▶ How to continue (read this first)

1. **Resume grilling with the `/grill-with-docs` skill** (NOT `/grill-me`). Keep the **same style and
   evolution** as the previous session:
   - Relentless, **one question at a time**, each with a **recommended answer**.
   - **Both** challenge the plan **and** proactively propose design insights (the user explicitly
     wants generative insights, not only stress-testing).
   - **Evidence-driven** (mine real signal; see `docs/research/`) and **sufficiency-first** (every
     tool return must let the agent act in one round-trip; token-efficient but never lossy of
     actionable signal — the anti-RTK lesson).
   - `/grill-with-docs` reconciles the plan against `CONTEXT.md` + `docs/adr/`. **Neither exists
     yet** — seed `CONTEXT.md` (domain language / ubiquitous terms) and ADRs from the decisions in
     `docs/design/decision-log.md` as the skill runs.
   - Remaining grill topics to consider: field-level normalized failure schema; server
     observability/logging (MCP STDIO must keep logs off stdout); concurrency edge cases; whether to
     collapse the 5 read-only git verbs into one `git(mode)` tool.
2. **Then the before-coding phase** (per `CLAUDE.md`):
   a. **Research** the main current Java application architectures **and** real MCP-server projects;
      choose one **with a written rationale** (hexagonal/ports-and-adapters is the leading
      hypothesis given the per-ecosystem adapter design, but it must be chosen from research, not
      assumed).
   b. Run **`/prototype`** to flesh out the chosen design before committing.
   c. Produce **`DESIGN.md`** (architecture doc) at repo root from that analysis.
   d. Implement strictly via **`/tdd`** (Clean Code / YAGNI / KISS).
3. **Run the two pre-PRD spikes** (`docs/design/roadmap.md`) before freezing the schema: parse one
   real report of each format (JUnit XML, `jest --json`, `go test -json`) into one struct; and a
   Micronaut MCP STDIO tool-registration spike on the Micronaut 5 / Java 25 baseline.

---

## Sources of truth

- **What & why (decisions):** `docs/design/` — `README.md` (vision + 9 pillars), `security-model.md`,
  `tool-catalog.md`, `operational-model.md`, `build-and-distribution.md`,
  `bootstrap-and-deployment.md`, `gotchas.md` (G1–G15), `roadmap.md`, `decision-log.md` (D1–D20).
- **How (architecture):** `DESIGN.md` — **does not exist yet**; it is the before-coding deliverable.
- **Empirical grounding:** `docs/research/roundtrip-waste-evidence.md` (156 sessions, 7,814 Bash
  calls; #1 waste = verbose-output truncation; git read-only = 773 calls; custom task runners are
  top producers; RTK's lossy summaries cause re-runs).
- **Working agreement:** `CLAUDE.md` (documentation-first, agent-first, TDD/Clean Code/YAGNI/KISS,
  architecture-from-research pipeline).

---

## Decisions locked (summary — full list in `decision-log.md` D1–D20)

- **Security:** guardrail **not** sandbox; guarantee = "no agent-composed novel commands"; **argv via
  `ProcessBuilder`, never a shell string**; **allowlist of flags per operation** (unknown dropped);
  policy **compiled-in**, project config tunes only non-sensitive knobs; outbound repo content
  treated as **untrusted data** (injection defense); validation = JSpecify + Jakarta + programmatic
  guards (security boundary in code, never annotations).
- **`run_task` governance:** **opt-in allowlist, fail-closed** — "composition-safe ≠
  consequence-safe" (a project-defined `deploy:prod` is catastrophic to auto-run).
- **Tool catalog (v1):** `run_tests`(+structured target), `build`, `install`, `lint`, `run_task`,
  `describe_project` (enumerates custom tasks too), `dependencies` (query: direct/why/resolve),
  `get_log(handle, filter)` (non-lossy drill-down — the anti-RTK keystone), **git read-only**
  (`status`/`diff`/`log`/`show`/`branch`).
- **Ecosystems (v1):** **Maven + Node (npm/pnpm/yarn) + Go** — three *dissimilar* report formats to
  de-risk the universal schema. **Gradle deferred** (≈0 local evidence, redundant JUnit-XML format).
- **Output:** parse machine-readable **report files** → **single normalized schema**; common
  envelope; sufficiency-first / noise-truncated; operational errors = enumerated `code` + `hint`
  (incl. `DEPS_NOT_INSTALLED` preflight).
- **Runtime:** global/stateless install; `path` per verb; `timeout` + max cap + **process-tree kill**;
  session-scoped run cache (last-N / TTL / **byte cap**) backing `get_log`; bounded concurrency.
- **Build:** **Micronaut 5 / Java 25 / Micronaut MCP 1.0.0 (GA, STDIO)**; JVM core, **GraalVM native
  at release** justified by distribution (no JRE) + footprint, not startup; accept per-platform CI
  matrix.
- **Bootstrap:** thin skill registers the MCP + writes a **transitional dangerous-git deny-list**
  (declarative, **harness-agnostic** adapter — NOT a bash hook), suggests removing Bash when coverage
  suffices.

---

## Key gotchas (full list `gotchas.md` G1–G15)

- **G1** shell-string parsing is a minefield → argv only.
- **G5** lossy summarization (RTK-style) causes *more* round-trips → `get_log` is non-lossy.
- **G13** Windows package-manager launchers are `.cmd`/`.bat` shims — `ProcessBuilder` can't exec
  them directly; resolve the concrete path + strict arg validation (BatBadBut quoting class). P2 is
  refined to "*no agent-controlled shell string*," not "never spawn an OS process facility."
- **G14** composition-safe ≠ consequence-safe → `run_task` opt-in allowlist.
- **G15** foundation is young: Micronaut MCP 1.0.0 on Java 25 — pin versions; verify GraalVM
  native-image supports JDK 25 in CI.

---

## Files affected this session

### Created
- `CLAUDE.md` — merged: working agreement prepended, prior "Agent skills" section preserved.
- `docs/design/{README,security-model,tool-catalog,operational-model,build-and-distribution,bootstrap-and-deployment,gotchas,roadmap,decision-log}.md`
- `docs/research/roundtrip-waste-evidence.md` (by a research subagent).
- `docs/handoffs/HANDOFF_grilling-to-before-coding_2026-06-03.md` (this file).

### Not yet created (next-session deliverables)
- `DESIGN.md` (architecture), `CONTEXT.md`, `docs/adr/*` — produced during the next phases.
- No source code, no `pom.xml` yet — greenfield.

---

## Current state

- **Decisions:** complete and committed (`6cfea71`).
- **Code/tests:** none yet (greenfield — TDD starts after `DESIGN.md`).
- **Memory:** a background `dreamer` subagent was spawned to consolidate project memory into
  `~/.claude/projects/-home-rodrigo-Workspace-no-bash-mcp/memory/` (outside the repo).

---

## Open questions

- [ ] Field-level normalized failure schema (pin via the spike, then PRD).
- [ ] One `git(mode)` tool vs five discrete git verbs (surface-size vs explicitness).
- [ ] GraalVM native-image availability for JDK 25 in CI.
- [ ] Architecture choice (output of before-coding research).

---

## Commands to continue

```bash
git -C /home/rodrigo/Workspace/no-bash-mcp log --oneline   # see checkpoint 6cfea71
ls docs/design/                                            # the decisions layer
# In a new session:
/grill-with-docs   # resume grilling against CONTEXT.md/ADRs, same style
```

---

## Session notes — user working style (carry this forward)

Documentation-first; durable artifacts in English; docs organized by context and updated **as**
decisions are made. Advisor-first / adversarial review before hard-to-reverse calls. Verify-before-
done (he corrected my "Micronaut 5" doubt with the primary source — and was right). Prefers
allowlist / fail-closed security. Wants grilling that is relentless, evidence-grounded, and
**generative** (propose insights, don't only challenge). Commits checkpoints and uses handoffs for
continuity.

---

_Start a new session and use this document as your initial context. Resume with `/grill-with-docs`._
