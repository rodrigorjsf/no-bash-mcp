# Handoff: no-bash-mcp — three spikes DONE + ADR-0007 frozen → **first PRD** (`/to-prd`)

**Created:** 2026-06-04
**Branch:** master (commit `4f53870`, **committed not pushed**)
**Supersedes:** `HANDOFF_prototype-design-complete_to-spikes-and-tdd_2026-06-04.md` (its spike tasks are now done)
**Phase:** before-coding + three de-risking spikes **COMPLETE**; next = **first PRD** (`/to-prd`) → `/to-issues` → implement via `/tdd`

---

## Summary

The three de-risking spikes ran, were adversarially reviewed (5 lenses, 29 findings), and the universal
test-result schema is **frozen in ADR-0007 (accepted)**. Everything is committed (`4f53870`); `src/`
production code, `pom.xml`, and tests still do **not** exist (by design). The next action is to author the
**first PRD** — a tracer-bullet vertical slice — then `/to-issues`, then `/tdd`. This handoff's centerpiece
is the **First PRD guidance** section below.

---

## Work Completed

### Changes Made (all in commit `4f53870`)

- [x] Ran **spike s1** (`spikes/s1-schema/`) — falsified the schema against UNSEEN real Go + JUnit reports
- [x] Ran **spike s2** (`spikes/s2-mcp-stdio/`) — Micronaut MCP STDIO; built + verified a real GraalVM native binary
- [x] Ran **spike s3** (`spikes/s3-forge/`) — forge read-only seams via JDK HttpClient against real GitHub
- [x] **Adversarial 5-lens Workflow review (29 findings)**; applied every real finding, affirmed 2 refuted ones
- [x] Wrote + **froze ADR-0007** (accepted, scoped); updated DESIGN.md §2/§5/§7/§11, schema-divergence-map, forge-security-model, roadmap
- [x] RTK self-healing: denied `curl` + `go test` (output condensation broke parsing); saved project memories

### Key Decisions

| Decision | Rationale | Alternatives Considered |
| --- | --- | --- |
| **ADR-0007 freezes schema field names + 6 rules**, but **forge-security + native-serde are OUT of the freeze** | The review proved the schema sound but the forge-security claims unprovable by the spike | Freeze everything (rejected — would launder unproven guarantees into "accepted") |
| **New rule: ERRORED vs FAILED discriminator** (ERRORED = couldn't-run/abnormal-termination; FAILED = ran+assertion-failed) | The spike normalizers contradicted themselves (Go init-panic→FAILED vs compile-fail→ERRORED) | Leave it implicit (rejected — `/tdd` would hardcode divergent behavior) |
| **Forge `conclusion → Outcome` mapping frozen** (all conclusions pinned) | One observed conclusion (`failure`) is not enough; an unmapped value would false-green (G5 on the forge axis) | Map only `failure` (rejected — false-green risk) |
| **Forge security claims demoted to test-owed** | Spike used a write-capable 40-char OAuth token (not a read-only PAT); the 302 leak assertion was tautological | Assert "read-only seams hold" (rejected — overclaim) |
| **Native binary built via dev workarounds** (libz symlink + tracing-agent logback metadata) | Got a stdout-clean native binary without sudo to discharge G15 | Install `zlib1g-dev` (needs sudo); defer native (rejected — user chose to run it) |

---

## Files Affected (committed in `4f53870`)

### Created
- `docs/adr/0007-normalized-test-result-schema.md` — **the schema freeze (accepted)**. Read this first for the first PRD.
- `spikes/s1-schema/` — Go fixtures + reports + NOTES + driver (the schema falsification).
- `spikes/s2-mcp-stdio/` — Micronaut Launch app + `@Tool` + STDIO test harness + captured-runs + reachability-metadata.json + NOTES.
- `spikes/s3-forge/SpikeForge.java` + NOTES + captured-run — the JDK-HttpClient forge seams.
- `docs/handoffs/HANDOFF_prototype-design-complete_to-spikes-and-tdd_2026-06-04.md` (prior handoff, now committed).

### Modified (committed in `4f53870`)
- `DESIGN.md` — §2 (ADR-0007 ref + native-serde caveat), §5 (forge `ok()` completeness caveats), §7 (stdout-hygiene VERIFIED JVM+native + the **logback native-metadata obligation**), §11 (spike outcomes; Go-stdout item closed/refuted).
- `docs/design/forge-security-model.md` — read-scope = operator-provisioning obligation (not code-provable); pagination/Commit-Statuses/rate-limit completeness.
- `docs/design/schema-divergence-map.md` — resolved → ADR-0007. `docs/design/roadmap.md` — Go removed from post-v1.

### Not yet created
- `pom.xml`, `src/main`, `src/test` — created during `/tdd` after the first PRD → issues.

---

## Technical Context

### The frozen schema (ADR-0007 — the spine the first PRD builds toward)
`sealed Finding { TestFinding(suite, name, path[], …) | ContainerFinding(scope, container, …) }` ·
`Outcome{PASSED,FAILED,ERRORED,SKIPPED}` + retained `rawStatus` · `SourceRef(file, Integer line)` boxed-nullable ·
`Summary` test-counts-only · `NormalizedRun.ok()` **container-aware** (derived from findings, never counts — the G5 guard).
serde wire: `@JsonTypeInfo(use=NAME, property="kind", defaultImpl=…)` on `Finding`; boxed `Integer`.

### Architecture (ADR-0006, accepted; DESIGN.md is canonical)
Lightweight hexagonal + per-verb feature slices, single Maven module + ArchUnit. Inbound = Micronaut MCP
`@Tool` `@Singleton` beans (transport = config STDIO). Two outbound ports: `CommandExecutorPort` (process),
`ForgePort` (HTTP, `micronaut-http-client-jdk`, Netty-free). Domain = reflection-free records. Baseline
(DESIGN.md §10): Java 25 · micronaut-platform 5.0.2 · micronaut-mcp 1.0.0 · JUnit 6.0.3 · serde 3.0.0 ·
GraalVM CE 25.0.2 · pin only WireMock 3.13.2 + ArchUnit 1.4.2 core.

---

## ⭐ First PRD — guidance (the user's explicit ask)

**Scope the first PRD as a TRACER-BULLET vertical slice — NOT all of v1.** The thinnest end-to-end path
that exercises every architectural seam on the highest-evidence ecosystem, so the architecture is de-risked
before fanning out.

**Recommended first slice: `run_tests` for Maven, over STDIO, into the frozen envelope.**

- **Flow (every seam, once):** `@Tool` bean (`adapter/in/mcp`, `BuildTools.run_tests`) → verb use-case
  (`application/verb/tests`) → input validation + **security guards** → preflight (deps/lockfile →
  `DEPS_NOT_INSTALLED`) → `CommandExecutorPort.execute(ExecSpec)` → Maven adapter
  (`adapter/out/ecosystem/maven`: `ProcessBuilder` + Surefire reporter, reads `target/surefire-reports/*.xml`)
  → **Surefire normalizer → frozen `NormalizedRun`** → `Envelope` (+ run-cache `Handle`) → STDIO JSON-RPC →
  `get_log(handle, filter?)` drill-down.
- **Why Maven first:** Surefire JUnit-XML is the most-validated format (prototype + s1, incl. the
  `@Nested`/parametrized + the `<testsuite tests=>` header trap); it proves the spine without the Node
  framework-detection complexity.
- **Acceptance criteria = the frozen contract:** the three envelope shapes (success = counts-only;
  test-failure = `Finding[]`; operational-error = enumerated `code` + `hint`); **container-aware `ok()`**
  (a `@BeforeAll` error / no-test-owner failure must NOT false-green — the G5 keystone); `get_log` expands
  the slice without re-running.
- **Security-tests-first (DESIGN.md §9, NON-NEGOTIABLE):** argv-never-a-shell-string, flag allowlist,
  `RESOURCE_BUSY` on same-target collision (ADR-0005), `INVALID_PATH`. These are first-class tests, written
  first, per the project's security posture (`security-model.md`).
- **Testing posture (DESIGN.md §9):** `@MicronautTest(startApplication=false)`, `@MockBean` (NOT
  `MockitoExtension`), ArchUnit 1.4.2 **core** (not archunit-junit5), `@TestFactory` for the divergence
  matrix, golden fixtures (reuse `spikes/s1-schema/reports/`), MCP Inspector `--cli` acceptance.

**Explicit NON-GOALS for the first PRD (carry as later PRDs / the test-owed backlog):**
- **Node + Go** ecosystems — subsequent PRDs. **Node normalization rules are OWED** (framework detection +
  jest/vitest/mocha variance, ADR-0007) — do not pull forward.
- **Forge verbs** (`pr_checks`/`pr_view`/`pr_diff`) — a separate PRD; **forge security tests owed**
  (read-only PAT 403, `@Client` 302 redirect, pr_checks pagination + Commit-Statuses).
- **Native release** (`--static-nolibc` + `zlib1g-dev` + logback tracing-agent metadata + a native
  polymorphic-`Finding` serde round-trip) — a CI/native PRD; the inner loop is **JVM-only**.
- `run_task` (highest-security-stakes verb — its own PRD), `git_*`, `describe_project`, `dependencies`.

**How to create it:** invoke **`/to-prd`** (turns context into a PRD + publishes to GitHub issues via `gh`;
see `docs/agents/issue-tracker.md`). Keep the slice scope above in context first. The PRD should cite
DESIGN.md §2/§3/§4/§5/§6/§9, ADR-0007 (frozen schema), `security-model.md`, ADR-0005, and `tool-catalog.md`
(the `run_tests` spec). Then **`/to-issues`** breaks it into tracer-bullet issues; then **`/tdd`** implements
each (red→green→refactor). The issue tracker = GitHub issues; triage labels in `docs/agents/triage-labels.md`.

---

## Things to Know

### Gotchas & Pitfalls
- **`ok()` MUST be container-aware** — derive from findings, not counts, or a container-only failure
  false-greens (G5). Frozen regression fixture required.
- **Native (later):** the binary crashes at startup on logback `<target>System.err</target>` reflection
  until tracing-agent metadata is shipped; the link needs `zlib1g-dev` (memory `native-logback-reflection`,
  `wsl-toolchain`). JVM inner loop avoids all of this.
- **WSL toolchain** (memory `wsl-toolchain`): npm/corepack degraded (node-bundled `npm-cli.js`
  `--prefer-offline`; first install ~10min); pin `maven-compiler-plugin >= 3.13` for Java 25; use the
  **generated `./mvnw`**, never the `/mnt/d` Maven 3.8.6; GraalVM via `mise install java@graalvm-community-25.0.2`.
- **RTK denies** added this session: `curl`, `go test` (condensed output broke parsing). For parseable git,
  use `/usr/bin/git` (RTK-bypassed).
- **`.claude/scheduled_tasks.lock`** is an untracked harness session-lock — leave it; do not commit.
- A background **`dreamer`** subagent is finishing memory consolidation (touches `~/.claude/.../memory/`, not the repo).

### Assumptions
- `master` is the working/default branch; history is direct-to-master. Base package `dev.nobash` / groupId
  `dev.nobash` are proposals (settable in the first pom).

---

## Current State

### What's Working
- All 3 spikes pass (captured-runs committed): s1 `mvn -q compile exec:java` (30 hard assertions), s2 STDIO
  stdout-clean on JVM + native (`python3 stdio_client.py … && python3 validate_stdout.py …`), s3
  `GITHUB_TOKEN=$(gh auth token) java spikes/s3-forge/SpikeForge.java`.
- ADR-0007 accepted; DESIGN.md + design docs consistent and cross-referenced; committed `4f53870`.

### What's Not Working / Not Done
- No `src/` production code, `pom.xml`, or tests yet (by design — `/tdd` after the first PRD → issues).
- Native release form (`--static-nolibc`) unbuilt; forge security + Node rules test-owed.

### Tests
- [x] Spike assertions: passing (captured). [ ] Production unit/integration tests: not written (start in `/tdd`).

---

## Next Steps

### Immediate (Start Here)
1. **Author the first PRD** via **`/to-prd`** — scope = the `run_tests`/Maven tracer-bullet slice above.
   Hold that scope in context; cite DESIGN.md + ADR-0007 + security-model.md + tool-catalog.md.
2. **`/to-issues`** — break the PRD into tracer-bullet vertical-slice issues (security-tests-first).
3. **`/tdd`** — implement each issue red→green→refactor against DESIGN.md + ADR-0007 + §9 posture.

### Subsequent
- PRD 2: Node + Go ecosystems for `run_tests` (capture Node normalization rules against real fixtures).
- PRD 3: the other build verbs (`build`/`install`/`lint`/`run_task`). PRD 4: forge (with the owed security tests).
- PRD 5 / CI: the native `--static-nolibc` release build + the native polymorphic-`Finding` serde round-trip.

### Blocked On
- Nothing blocks the first PRD. (Pushing `4f53870` is owed only if you want it remote — not done; ask.)

---

## Related Resources

### Documentation
- **ADR-0007** (frozen schema), `DESIGN.md` (canonical structure), `spikes/s{1,2,3}/NOTES.md` (verdicts),
  `docs/design/` (tool-catalog, operational-model, security-model, forge-security-model, decision-log),
  ADRs 0001–0006, `CONTEXT.md`. Project memory: `~/.claude/projects/-home-rodrigo-Workspace-no-bash-mcp/memory/`.

### Commands to Run
```bash
/usr/bin/git -C /home/rodrigo/Workspace/no-bash-mcp log --oneline -3
cd spikes/s1-schema && mvn -q compile exec:java          # re-verify the schema falsification
# /to-prd  → first PRD (run_tests/Maven slice) → /to-issues → /tdd
```

### Search Queries
- `grep -rn "container-aware\|ContainerFinding\|ERRORED" docs/adr/0007-*.md DESIGN.md` — the frozen counting rules.
- `grep -rn "run_tests\|BuildTools\|CommandExecutorPort" DESIGN.md docs/design/tool-catalog.md` — the first-slice surfaces.
- `grep -rn "security-tests-first\|argv\|flag allowlist" DESIGN.md docs/design/security-model.md` — the §9 obligations.

---

## Open Questions

- [ ] First PRD slice confirmed as `run_tests`/Maven? (recommended; this handoff assumes it.)
- [ ] JUnit 6.0.3 vs 6.1.0; `native:test` CI subset; coverage tool (JaCoCo on Java 25/GraalVM); final base package/groupId — pom-wiring decisions for the first `/tdd` cycle.
- [ ] Push `4f53870` to origin now, or keep local until the first PRD lands?

---

## Session Notes

This session resumed from the spikes handoff, ran all three spikes empirically (including a real GraalVM
native build that surfaced the `zlib1g-dev` + logback-reflection blockers), gated the schema freeze with a
Workflow-based adversarial 5-lens review (29 findings — the forge-security overclaims and the missing
ERRORED-vs-FAILED discriminator were the high-value catches), applied every real finding, froze ADR-0007,
and committed `4f53870`. Working style: documentation-first, **every finding documented in a durable
artifact** (user directive), advisor/adversarial review before hard-to-reverse calls, verify-before-done.

---

_Start a new session and use this document as your initial context. The next action is the **first PRD**
(`/to-prd`) for the `run_tests`/Maven tracer-bullet slice._

---

## ⭐ ADDENDUM — `/grill-with-docs` refinements (2026-06-04, second session)

A `grill-with-docs` pass sharpened the First-PRD scope below into locked decisions. **This addendum is the
authoritative scope for `/to-prd`.** New durable records: **ADR-0008**; decision-log **D21–D26**; CONTEXT.md
**Reporter** term; security-model flag-policy + launcher-resolution rows; schema-divergence-map
report-absence asymmetry.

### Locked PRD-1 scope (the `run_tests`/Maven tracer)

**IN — architectural seams (each exercised once):**
- Inbound `@Tool` bean (`adapter/in/mcp`, `BuildTools.run_tests`) over **STDIO**.
- Verb use-case (`application/verb/tests`) → input validation + programmatic security guards.
- `CommandExecutorPort.execute(ExecSpec)` → Maven adapter (`adapter/out/ecosystem/maven`): trusted
  **system `mvn`** via `ProcessBuilder` (**never `./mvnw`** — ADR-0008), reads `target/surefire-reports/*.xml`.
  (Maven needs **no reporter-flag injection** — Surefire writes JUnit XML by default; the injection seam
  exists but injects nothing here.)
- **Report freshness by construction (D27, revised):** inject a unique per-run reports dir
  (`-Dsurefire.reportsDirectory=<fresh tmp>`, MCP-controlled); empty-before → any XML is this run's; empty
  after exec → `REPORT_NOT_PRODUCED`. (Supersedes the first-cut `mtime >= start` gate, which an adversarial
  review showed reopens the stale-green hole at coarse fs mtime granularity.)
- Pure normalizer → frozen `NormalizedRun` (ADR-0007; counts from `<testcase>`, rule 5; `tool="surefire"`).
- `Envelope` (`manager="mvn"`, agent-facing `failures[]` carrying `Finding` w/ `kind` discriminator — D30)
  + in-memory run-cache + `Handle`. **Envelope `ok` is a positive-evidence failure floor (D28+D29):**
  `ok = NormalizedRun.ok() && exitCode==0 && !timedOut && executedTests>0` (executed = passed+failed+errored);
  a fresh report with zero executed tests → `NO_TESTS_RUN`. Frozen domain `ok()` stays findings-only.
- `get_log(handle, filter?)` — in-memory last-N (~10); **2 filters**: by failing-test identity → that
  finding's full detail/stacktrace; no-filter → whole retained output. (TTL/byte-cap/disk-spill **deferred**
  to an operational-hardening PRD.)
- **Process infra:** `timeout` (default + max cap) + **process-tree kill** (`destroyForcibly` + descendants)
  → structured `TIMEOUT` envelope with partial signal (D12 — "never hangs").
- **Concurrency guard:** `RESOURCE_BUSY` keyed on **`realpath(moduleDir)`** (D22); a target selector does
  not change the key.
- ArchUnit core rules (`domain !-> adapter`, layered) from the first cycle.

**IN — the three envelope shapes (acceptance):** success (counts-only) · test-failure (`Finding[]`) ·
operational-error (enumerated `code` + `hint`). **Container-aware `ok()`** is the G5 keystone — a
`@BeforeAll`/no-test-owner failure must NOT false-green (frozen regression fixture).

**IN — operational errors:** `NO_MANAGER_DETECTED` (no `pom.xml`), `INVALID_PATH`, `RESOURCE_BUSY`,
`TIMEOUT`, **`REPORT_NOT_PRODUCED`** (compile failure → no Surefire XML; hint "run `build`"; raw compiler
output in the handle — D25). `TOOL_NOT_INSTALLED` when `mvn` is absent.

**IN — security-tests-first (non-negotiable):** argv-never-a-shell-string; **flag allowlist** (seed:
`-o/--offline`, `--fail-at-end/-fae` only; forbidden categories: skip-the-verb, arbitrary `-D`,
stdout-verbosity, test-selection — security-model); `RESOURCE_BUSY`; `INVALID_PATH`; **P9** minimal
neutralization (strip control/ANSI/zero-width, per-field cap, mark `untrusted` in the envelope — does not
touch the frozen schema).

**IN — verification:** `@MicronautTest(startApplication=false)` + `@MockBean`; `@TestFactory` divergence
matrix; golden fixtures (reuse `spikes/s1-schema/reports/`); **MCP Inspector `--cli` acceptance against the
packaged JVM jar** in the Failsafe `verify` phase (the real STDIO JSON-RPC proof). JVM inner loop only.

**OUT (later PRDs / backlog):**
- **Preflight `DEPS_NOT_INSTALLED`** — vacuous for Maven (D21); the dispatch seam exists as a Maven no-op;
  first genuinely exercised by the Node/Go PRD. (The one seam the tracer does **not** exercise.)
- **Structured target selector** (`-Dtest=`) — fast-follow issue **within PRD-1**, after the full-suite
  tracer is green (adds no new seam).
- `AMBIGUOUS_SCOPE` (needs a Node detector / multi-module reactor degrades gracefully); Node + Go; forge;
  `run_task`; native release; JaCoCo coverage; run-cache TTL/byte-cap/disk-spill.

**README (D24):** PRD-1's **final issue** — generate `README.md` via `/code-documentation:doc-generate`
**after the tracer is green**, documenting only the shipped surface (banner "v1 in progress"), **pointing to**
DESIGN.md/CONTEXT.md/docs (no duplication); doc-generate is **forbidden from regenerating the hand-authored
corpus**. One-time; CI doc-gen + diagram/API suite deferred.

### Suggested issue spine for `/to-issues`
1. Security-tests-first skeleton: argv/`ProcessBuilder`, flag-allowlist seed, `INVALID_PATH`, `NO_MANAGER_DETECTED`.
2. The tracer: `run_tests` full-suite Maven → **freshness-gated** Surefire normalizer (D27) → frozen
   `NormalizedRun` → 3 envelope shapes + container-aware `ok()` with the **exit-code failure floor** (D28).
   Anti-false-green fixtures: G5 container-only run; stale-XML (compile-fail) run; non-zero-exit-all-PASSED run.
3. Run-cache + `Handle` + `get_log` (2 filters).
4. `RESOURCE_BUSY` (realpath key) + `TIMEOUT`/tree-kill.
5. P9 minimal neutralization.
6. MCP Inspector `--cli` acceptance (packaged jar) + ArchUnit rules.
7. Fast-follow: structured target selector (`-Dtest=`).
8. **Final:** README via `/code-documentation:doc-generate`.

### Still operational (not design — ask the user)
- Push `4f53870` (+ this grilling commit) to origin now, or keep local until the PRD lands?
