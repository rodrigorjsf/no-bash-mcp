# Handoff: no-bash-mcp — `/prototype` + `DESIGN.md` DONE → **spikes** → `/tdd`

**Created:** 2026-06-04
**Branch:** master (synced with `origin/master`)
**Checkpoint commit:** `ede6a8f` (committed **and pushed**) — DESIGN.md + prototype/ (25 files) + ADR-0006 accepted + divergence-map fix
**Supersedes:** `HANDOFF_research-complete_to-prototype-and-design_2026-06-03.md` (its `/prototype` → `DESIGN.md` tasks are now done)
**Phase:** before-coding **COMPLETE**; next = **three de-risking spikes** → **`/tdd`** (red→green→refactor) → `/to-prd` → `/to-issues`

---

## Summary

The before-coding pipeline step is finished: `/prototype` validated the two undecided bets against
**three real reports**, `DESIGN.md` was written from that output, and **ADR-0006 was promoted
`proposed` → `accepted`**. Everything is committed (`ede6a8f`) and pushed; project memory is
consolidated. **No production code, `pom.xml`, or tests exist in `src/` yet** — the next session runs
the **three spikes** (`DESIGN.md §11`) to empirically close the last open items, then starts `/tdd`.

---

## Work Completed

### Changes Made

- [x] Ran **`/prototype`** (logic branch, throwaway in `prototype/`) — a runnable Maven+Jackson app that folds three REAL reports into one schema and dispatches through the two ports
- [x] Generated **three genuine reports** from real toolchains: Surefire JUnit-XML (Corretto 25), `jest --json` 29.7.0, `go test -json` 1.26 — each hitting the hard divergence axes
- [x] Wrote **`DESIGN.md`** — the binding architecture doc, from the prototype output (the canonical code-structure source of truth)
- [x] Promoted **ADR-0006** `proposed` → `accepted` (2026-06-04), with prototype evidence
- [x] Corrected **`docs/design/schema-divergence-map.md`** axis-5 (jest) — a real-report finding (see below)
- [x] Ran an **adversarial multi-lens review** (Workflow, 5 lenses) and fixed every real finding (BLOCKER + 4 majors + minors)
- [x] Committed `ede6a8f` and **pushed**; spawned `dreamer` → project memory consolidated

### Key Decisions

| Decision | Rationale | Alternatives Considered |
| --- | --- | --- |
| Universal schema = **sealed `Finding` (TestFinding \| ContainerFinding)** | The no-test-owner failure (axis 5) must be first-class, not a fake empty test; cleaner agent-facing JSON; maps to serde `@JsonTypeInfo(defaultImpl)` | flat record with nullable `TestId` + `kind` discriminator |
| **`ok()` is container-aware (derived from findings)**, `Summary` = test-counts only | A container-only failure must NOT false-green (the G5 trap the project exists to prevent) | `ok = failed==0 && errored==0` (refuted — false-greens) |
| **`manager` null for git/forge verbs** | A forge is not a manager; git is ecosystem-agnostic (CONTEXT.md) | `manager` always present (refuted — category error) |
| Prototype **throwaway but versioned** | User asked to version it; learnings live in DESIGN.md/NOTES.md; reports kept as future spike fixtures | delete after DESIGN.md |
| Report source routed by the **verb**, not the port | Maven writes a file, `go test -json` writes stdout; `CommandExecutorPort` stays format-agnostic | port returns a typed report (refuted — leaks format) |

---

## Files Affected

### Created (committed in `ede6a8f`)
- `DESIGN.md` — the architecture document (sections: architecture, the validated schema §2, package structure §3, component model + dispatch §4, the two ports §5, output contract §6, Micronaut/native mechanics §7, build+native+harness scoping §8, testing posture §9, version baseline §10, open items §11).
- `prototype/` (25 versioned files) — `NOTES.md` (the verdict), `src/main/java/proto/*` (Schema, Ports, three normalizers, Main), `fixtures/{go-gen,jest-gen,junit-gen}/` (the three real generators), `reports/` (the three real captured reports), `pom.xml`, `.gitignore`.

### Modified (committed in `ede6a8f`)
- `docs/adr/0006-application-architecture.md` — status `proposed` → **accepted** (Status block, top of file).
- `docs/design/schema-divergence-map.md` — axis-5 jest cell corrected + an empirical correction note after the table.

### Read (Reference)
- `docs/design/*` (tool-catalog, operational-model, gotchas, bootstrap-and-deployment), `docs/research/*` (architecture-survey, technology-baseline, testing-stack-research, graalvm-native-wsl-setup), `CONTEXT.md`, ADRs 0001–0005.

### Not yet created
- `pom.xml`, `src/main`, `src/test` (production). Created only during `/tdd` after the spikes.

---

## Technical Context

### The validated schema (DESIGN.md §2 — the spine; field names provisional until post-spike ADR)
`sealed Finding { TestFinding(suite, name, path[], …) | ContainerFinding(scope, container, …) }` ·
`SourceRef(file, Integer line)` boxed-nullable · `Outcome` enum + retained `rawStatus` ·
`Summary` (test counts only) · `NormalizedRun.ok()` derived from findings. The no-test-owner failure
folds in three scopes, one type: `[SUITE]` (JUnit `@BeforeAll`), `[FILE]` (jest module-load),
`[PACKAGE]` (Go `init()` panic).

### Architecture (ADR-0006, accepted)
Lightweight hexagonal + per-verb feature slices, **single Maven module** + **ArchUnit**. Inbound =
`@Tool` `@Singleton` beans (≈4–6 per verb-family), transport = config (STDIO). Two outbound ports:
`CommandExecutorPort` (process), `ForgePort` (HTTP). Domain = reflection-free records
(`@Serdeable @Introspected @JsonSchema`). Forge client = **`micronaut-http-client-jdk`** (Netty-free).

### Baseline (verified, DESIGN.md §10)
Java 25 · Micronaut platform 5.0.2 · micronaut-mcp 1.0.0 (BOM) → mcp-core 1.1.2 · JUnit 6.0.3 (BOM) ·
serde 3.0.0 · GraalVM CE 25.0.2 / Oracle 25.0.3. Pin only WireMock 3.13.2 + ArchUnit 1.4.2 (core).

---

## Things to Know

### Gotchas & Pitfalls
- **`ok()` MUST be container-aware** when implementing — derive from findings, not test counts, or a container-only failure false-greens (G5). Add a container-only run as an explicit test.
- **jest 29.7 finding:** a `beforeAll` throw is attributed **per test** (NOT "assertions vanish"); jest's genuine no-owner case is a **collection/module-load** failure. `schema-divergence-map.md` is corrected.
- **WSL toolchain quirks** (also in project memory `wsl-toolchain.md`): host **npm/corepack are degraded** (Node 22.11 < pnpm 22.13 floor; `npm-cli.js` missing from `~/.local` prefix). Workaround: node-bundled `npm-cli.js` + `--prefer-offline` (first install ~10min priming cache, retry fast). **Pin `maven-compiler-plugin >= 3.13`** for Java 25 (super-pom default is `source 5`). Go 1.26 clean.
- **stdout hygiene is owed to a spike** — DESIGN.md §7 marks it "to be verified," not done. `banner.enabled=false` + logback→stderr must ship together; confirm empirically (G15).
- **`@MockBean` not `MockitoExtension`** (Mockito #3779 + micronaut-test #78); **ArchUnit core 1.4.2 not `archunit-junit5`** (JUnit-6 incompatible).

### Assumptions
- `master` is this repo's working/default branch (no `main` exists; all history is direct-to-master).
- Base package `dev.nobash` / groupId `dev.nobash` are proposals (DESIGN.md flags as settable).

### Known Issues / Tech debt
- Go stdout parsing is de-risked only for clean JSON (runtime panic); a **compile-error** interleaving non-JSON on stdout is NOT yet tested (spike item).
- `.claude/scheduled_tasks.lock` is an untracked harness session-lock — leave it; do not commit.

---

## Current State

### What's Working
- Prototype: `cd prototype && mvn -q compile exec:java` → folds all three real reports, prints the false-green guard (`ok() = false — correct`), dispatches `run_tests`(Maven+Go)/`pr_checks`/`get_log`. Compiles exit 0.
- Both before-coding bets validated; ADR-0006 accepted; DESIGN.md complete; memory consolidated.

### What's Not Working / Not Done
- No `src/` production code, `pom.xml`, or tests yet (by design — TDD starts after spikes).
- The three spikes have NOT run (empirical de-risking still owed).

### Tests
- [ ] Unit tests: not written (start in `/tdd`).
- [ ] Integration tests: not written.
- [x] Manual: the prototype was driven and verified by hand this session.

---

## Next Steps

### Immediate (Start Here) — the three spikes (`DESIGN.md §11`)

1. **Universal-schema spike** — re-run the fold on **broader real repos**; freeze the field-level schema names in a **follow-up ADR** (e.g. `0007-normalized-test-result-schema.md`); confirm the Go parent/child dedup heuristic on a **multi-package** repo; **add a container-only run as an explicit test case** (must not false-green); decide whether retry/flaky (axis 8) is in v1 (currently deferred). The committed `prototype/reports/*` and `fixtures/*` are ready-made starting fixtures.
2. **Micronaut MCP STDIO spike (G15)** — `mn create-app --features=mcp-stdio,json-schema,validation`; register a trivial `@Tool`; confirm STDIO end-to-end **and** the default logger routes **off stdout** — pipe an `initialize` frame and assert only JSON-RPC on stdout, for **both the JVM and the native binary** (`./mvnw package -Dpackaging=native-image`). This discharges the §7 stdout-hygiene claim.
3. **Forge read-only spike** — fetch GitHub CI check status + a failed-job log (via `handle`) + a PR view/diff, against `github.com` **and** a GHES-style configurable base URL; normalize into the common envelope. De-risks the self-hosted seams.
4. **Go stdout edge** (fold into spike 1) — validate `go test -json` parsed from real `ExecResult.stdout` when a **build/compile failure interleaves non-JSON** output with the JSON-lines.

### Subsequent
- **`/tdd`** implementation against `DESIGN.md` + `docs/design/` + the post-spike schema ADR, honoring the §9 testing posture (`@MicronautTest(startApplication=false)`, `@MockBean`, WireMock, ArchUnit, MCP Inspector two-tier, security-tests-first).
- Then **`/to-prd`** → **`/to-issues`** once the schema is frozen.
- Decide: delete `prototype/` or keep it (reports are useful spike fixtures — recommend keep `reports/`+`fixtures/`, the `src/` is throwaway).

### Blocked On (verify empirically, don't reason)
- Native-image on JDK 25 in **this project's CI** (research says unblocked via GraalVM 25; confirm).
- Final JUnit call (inherit 6.0.3 vs override 6.1.0), the `native:test` CI subset, coverage tool (JaCoCo on Java 25), final base package / groupId — all "pom-wiring decisions" (DESIGN.md §11).

---

## Related Resources

### Documentation
- `DESIGN.md` (architecture), `prototype/NOTES.md` (prototype verdict), `docs/adr/0006` (accepted), `docs/design/` (decisions), `docs/research/` (verified grounding), `CONTEXT.md` (ubiquitous language).
- Project memory: `~/.claude/projects/-home-rodrigo-Workspace-no-bash-mcp/memory/` (`pipeline-continuity.md`, `wsl-toolchain.md`, `schema-divergence-map.md`, `empirical-grounding.md`, …).

### Commands to Run
```bash
git -C /home/rodrigo/Workspace/no-bash-mcp log --oneline -3
cd prototype && mvn -q compile exec:java          # re-run the prototype validation
# Spike 2 (native): ./mvnw package -Dpackaging=native-image  (see graalvm-native-wsl-setup.md)
# Spike 2 (inspector): npx @modelcontextprotocol/inspector java -jar target/no-bash-mcp.jar
# WSL jest (npm degraded): node ~/.local/share/mise/installs/node/22.11.0/lib/node_modules/npm/bin/npm-cli.js install --prefer-offline
```

### Search Queries
- `grep -rn "micronaut-http-client-jdk\|SharedArenaSupport" docs/ DESIGN.md` — forge-client/Netty decision.
- `grep -rn "container-aware\|false-green\|ContainerFinding" DESIGN.md prototype/` — the §2 counting rule.
- `grep -rn "spike" DESIGN.md docs/design/roadmap.md` — the de-risking list.

---

## Open Questions

- [ ] Exact normalized field names — freeze in a post-spike ADR (0007).
- [ ] Is retry/flaky (axis 8) in v1 or deferred?
- [ ] `@Tool` bean granularity confirmed at per-family (≈4–6) — validate during `/tdd`.
- [ ] JUnit 6.0.3 vs 6.1.0; `native:test` subset; coverage tool; base package/groupId.

---

## Session Notes

This session: resumed from the research-complete handoff, ran `/prototype` (advisor-gated, throwaway),
wrote `DESIGN.md`, flipped ADR-0006. The **advisor sharpened scope** (cut the already-decided
envelope/run-cache/RESOURCE_BUSY from the prototype, focus the two real bets, reports-first to avoid
fixture bias). A **Workflow-based adversarial multi-lens review** caught real defects the author missed
— most notably a **G5 false-green** in `ok()` — which were fixed and re-verified before commit. Working
style: documentation-first, durable artifacts in English, advisor/adversarial review before
hard-to-reverse calls, verify-before-done (every "done" mapped to fresh command output). Committed and
pushed (`ede6a8f`); memory consolidated via `dreamer`.

---

_This handoff was generated to continue work in a new session. Start at **Next Steps → Immediate (the three spikes)**._
