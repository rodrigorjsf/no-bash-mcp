# Handoff: no-bash-mcp — before-coding RESEARCH COMPLETE → `/prototype` → `DESIGN.md`

**Created:** 2026-06-03
**Branch:** master
**Checkpoint commit:** `ee25933` (6 files, +1216) — research docs + ADR-0006
**Supersedes:** `HANDOFF_before-coding-architecture-and-testing_2026-06-03.md` (its research tasks are now done)
**Phase:** before-coding — research **done**; next = **`/prototype`** (schema/envelope/dispatch) → **`DESIGN.md`** → spikes → `/tdd`

---

## Summary

The before-coding **research** is complete, verified against primary sources (June 2026), and
committed (`ee25933`). The architecture is chosen and user-confirmed (ADR-0006, status **proposed →
accepted after `/prototype`**). **No code, `pom.xml`, or tests exist yet.** The next session runs
**`/prototype`** on the riskiest open piece — the normalized test-result schema + envelope + verb
dispatch — then writes **`DESIGN.md`** from that output. Per `CLAUDE.md`, `/prototype` comes **before**
`DESIGN.md` is committed; the prototype is what promotes ADR-0006 to *accepted*.

---

## Work Completed (this session)

- [x] **Version-truth verified** (corrects grilling-era assumptions) → `docs/research/technology-baseline.md`
- [x] **Architecture chosen from a survey** (+ user-confirmed) → `docs/research/architecture-survey.md` + `docs/adr/0006-application-architecture.md`
- [x] **Testing stack + conventions pinned** → `docs/research/testing-stack-research.md`
- [x] **GraalVM native-image + Micronaut on WSL2 runbook** (user-requested) → `docs/research/graalvm-native-wsl-setup.md`
- [x] **Research-layer index** → `docs/research/README.md`
- [x] **Adversarial verification** of every load-bearing version/compat claim; 4 anchor URLs spot-checked & resolve
- [x] **Committed** as `ee25933`; project-memory consolidation (`dreamer`) spawned in background

### Key Decisions

| Decision | Rationale | Alternatives considered |
| --- | --- | --- |
| Lightweight **Hexagonal + per-verb feature slices** | adapter count (process exec + forge HTTP) is the deciding variable; matches real Micronaut MCP servers | Onion/Clean (over-engineered), layered (no seam), pure VSA (kernel > slices), full-ceremony hexagonal |
| **Single Maven module + ArchUnit 1.4.2** (core artifact) | KISS at ~15 verbs; `archunit-junit5` is incompatible with JUnit 6 (issue #1556) | multi-module `core`/`adapters` |
| Forge client = **`micronaut-http-client-jdk`** | `@Client` ergonomics, **Netty-free** → no `-H:+SharedArenaSupport` | raw `java.net.http`; Netty-backed `micronaut-http-client` |
| **Inherit BOM** mcp-core 1.1.2 / JUnit 6.0.3 / AssertJ 3.27.7 / Mockito 5.23.0 | `micronaut-platform:5.0.2` + `micronaut-test-bom:5.0.0` manage them | pin manually (refuted) |
| `@MockBean` over `MockitoExtension` | Mockito #3779 (JUnit-6 unresolved) + micronaut-test #78 (null `@Inject`) | `@ExtendWith(MockitoExtension.class)` |

---

## Files Affected

### Created (committed in `ee25933`)
- `docs/research/technology-baseline.md` — verified versions + primary-source links; open Qs resolved.
- `docs/research/architecture-survey.md` — the survey + recommendation + rejected alternatives.
- `docs/research/testing-stack-research.md` — JUnit 6 / MicronautTest / WireMock / ArchUnit / Inspector.
- `docs/research/graalvm-native-wsl-setup.md` — native-image build + WSL2 setup runbook.
- `docs/research/README.md` — research index.
- `docs/adr/0006-application-architecture.md` — the architecture ADR (status proposed → accepted after `/prototype`).

### Reference (read this session)
- `docs/design/*` (tool-catalog, operational-model, schema-divergence-map, security models), `docs/adr/0001–0005`, `CONTEXT.md`, the prior handoff.

### Not yet created
- A throwaway prototype, `DESIGN.md`, `pom.xml`, any source/tests.

---

## Technical Context (the decided shape DESIGN.md must host)

- **Verbs:** `describe_project`, `run_tests`, `build`, `install`, `lint`, `run_task`, `dependencies(mode)`, `get_log(handle,filter)`, git read-only ×5, forge `pr_checks`/`pr_view`/`pr_diff`.
- **Baseline (verified):** Java 25 · Micronaut **5.0.2** (parent `io.micronaut.platform:micronaut-platform:5.0.2`) · Micronaut MCP **1.0.0** (`io.micronaut.mcp:micronaut-mcp-server-java-sdk`, BOM-pinned mcp-core **1.1.2**) · GraalVM (CE 25.0.2 / Oracle 25.0.3, full JDK 25 native — **G15 unblocked**) · JUnit BOM **6.0.3** · serde 3.0.0 · Maven · **STDIO**.
- **Architecture (ADR-0006):** inbound = `@Tool` on `@Singleton` beans, transport = config (`micronaut.mcp.server.transport=STDIO`), no inbound port interface; outbound ports `CommandExecutorPort` + `ForgePort`; domain = Java records `@Serdeable @Introspected @JsonSchema` (reflection-free); single module; ArchUnit-enforced boundary.
- **Confirmed package layout** (single module): `domain/{envelope,result,error,port/out}` · `application/{verb/*,policy,dispatch}` · `adapter/{in/mcp, out/ecosystem, out/forge, out/harness}` · `infra/` · `config/`; tests mirror src; fixtures in `src/test/resources/fixtures/`.
- **stdout hygiene is load-bearing:** `micronaut.banner.enabled=false` + `logback.xml`→`System.err` (a developer obligation, not automatic).

---

## Next Steps — **START HERE**

### 1. `/prototype` — HOW and WHERE (the immediate action)

**Goal:** flesh out and de-risk the **data model** before committing it in `DESIGN.md` — the
normalized test-result schema + the common envelope + verb dispatch (the project's riskiest bet, the
"universal-schema" question, still deliberately unfrozen).

- **Branch of `/prototype` to use:** the **runnable terminal-app** branch (this is a state/data-model
  question, **not** a UI question).
- **WHERE:** a **throwaway** location — **not** `src/main`. Use a scratch dir (e.g. `prototype/` at
  repo root, git-ignored or deleted after) or the skill's own sandbox. Nothing here becomes production
  code; it exists to validate the model, then is discarded. `DESIGN.md` records the *learnings*, not
  the prototype code.
- **WHAT to model (in priority order):**
  1. **Universal schema:** parse **one real report of each of the three formats** — JUnit XML
     (Surefire/Failsafe), `jest --json`, `go test -json` — into **one** struct, reconciling the **8
     divergence axes** in [`docs/design/schema-divergence-map.md`](../design/schema-divergence-map.md).
     Confirm the "safe to assert now" items hold (flexible identity path; `file:line`/diff
     best-effort/nullable; a first-class failure-not-owned-by-a-test; outcome enum + raw status). This
     is the **riskiest** piece — do it first.
  2. **Envelope:** `{ ok, verb, manager, summary, handle?, failures[], code?, hint? }` — success
     (counts-only) vs test-failure (`failures[]`) vs operational-error (`code`+`hint`) shapes.
  3. **Dispatch:** the flow `typed input record → validate → call port → normalize → envelope`, plus
     `get_log(handle)` drill-down against a stub run-cache, and the `RESOURCE_BUSY` same-target guard
     (ADR-0005).
- **Validate the port boundaries** (`CommandExecutorPort`, `ForgePort`) hold for a sample of verbs
  (e.g. `run_tests`, `pr_checks`). If a boundary doesn't hold cleanly, that is the signal to amend
  ADR-0006 **before** it's promoted.
- **Outcome:** the prototype's learnings + a confirmed/adjusted schema → **promotes ADR-0006 from
  proposed → accepted** and becomes the spine of `DESIGN.md`.

### 2. `DESIGN.md` — write it FROM the `/prototype` output

Consolidate (don't duplicate — reference `docs/research/` and `docs/adr/`):
- **Architecture + rationale** → from `architecture-survey.md` + ADR-0006 (flip ADR status to *accepted*).
- **Package structure (src + test)** → the confirmed single-module hexagonal + feature-slice layout above; tests mirror src.
- **Component model** → inbound `@Tool` beans per verb-family (recommendation: ≈4–6 beans —
  `BuildTools`/`ProjectTools`/`GitTools`/`ForgeTools`) → `application/verb/*` services → `domain/port/out`
  → `adapter/out/*`.
- **Mechanics** → tool registration (`@Tool`/`@ToolArg`/`@JsonSchema`/`@Serdeable`), transport=STDIO,
  stdout hygiene, dispatch, run-cache/`handle`, `RESOURCE_BUSY`, native posture, **forge client =
  `micronaut-http-client-jdk`**, serde 3.0.0 record constraints (boxed optionals, `@JsonTypeInfo(defaultImpl)`).
- **Testing posture** → from `testing-stack-research.md` (`@MicronautTest(startApplication=false)`,
  `@MockBean`, WireMock, ArchUnit rules, MCP Inspector two-tier, security-first).
- **Build/native** → from `graalvm-native-wsl-setup.md` (two-phase; `mvn package -Dpackaging=native-image`).
- **Reconcile the harness-adapter scoping** explicitly: it belongs to the **separate bootstrap
  deliverable**, not the running server's request path (a divergence from the grilling handoff to
  resolve here).

### 3. Subsequent
- **Three spikes** (after `DESIGN.md`): universal-schema (the big bet); Micronaut MCP STDIO + **MCP
  Inspector** (confirm logger routes off stdout — G15 empirical); forge read-only (GitHub + a
  GHES-style base URL).
- **`/tdd`** implementation; then `/to-prd` → `/to-issues` once the schema is frozen post-spike.

### Blocked on (verify empirically, don't reason)
- Native-image on JDK 25 in CI (research says unblocked via GraalVM 25; confirm in *this* project's CI).
- Final JUnit call (inherit BOM 6.0.3 vs override 6.1.0) and a coverage tool (JaCoCo on Java 25).

---

## Things to Know (gotchas)

- **`@MockBean`, not `MockitoExtension`** (Mockito #3779 + micronaut-test #78).
- **`archunit:1.4.2` core, NOT `archunit-junit5`** (incompatible with JUnit 6).
- **Inherit BOM versions** (mcp-core 1.1.2, JUnit 6.0.3, AssertJ/Mockito) — only pin `wiremock:3.13.2`
  and `archunit:1.4.2`.
- **Netty-free** depends on choosing `micronaut-http-client-jdk` (or raw JDK) — the default
  `micronaut-http-client` would re-introduce Netty + the SharedArenaSupport flag.
- **G1–G15** in `docs/design/gotchas.md` remain authoritative; G15 (native JDK 25) is research-unblocked
  but still gets a CI check.
- **RTK** rewrites Bash for display — use `git status --porcelain` / `git log --oneline` for parseable
  output (this session's git capture used them).

---

## Related Resources

- Research: `docs/research/` (README, technology-baseline, architecture-survey, testing-stack-research, graalvm-native-wsl-setup).
- Decisions: `docs/adr/0001–0006`, `docs/design/`, `CONTEXT.md`.
- External: Micronaut MCP guide (https://micronaut-projects.github.io/micronaut-mcp/latest/guide/); MCP Inspector (https://github.com/modelcontextprotocol/inspector); ADR-0006 cites the rest.

### Commands to run
```bash
git -C /home/rodrigo/Workspace/no-bash-mcp log --oneline -3
ls docs/research/ docs/adr/
# /prototype: model the schema/envelope/dispatch in a throwaway terminal app (NOT src/main)
# Later spike: npx @modelcontextprotocol/inspector java -jar target/no-bash-mcp.jar
# Later build: ./mvnw package -Dpackaging=native-image   (see graalvm-native-wsl-setup.md)
```

### Search queries
- `grep -rn "micronaut-http-client-jdk\|SharedArenaSupport" docs/` — the forge-client/Netty decision.
- `grep -rn "startApplication\|@MockBean\|archunit" docs/research/testing-stack-research.md` — testing rules.
- `grep -rln "ADR-0006\|proposed" docs/` — the architecture decision + its provisional status.

---

## Open Questions
- [ ] Does the universal schema survive one real report of each format? (the `/prototype` deliverable)
- [ ] Do `CommandExecutorPort` / `ForgePort` boundaries hold for all verbs? (promotes/amends ADR-0006)
- [ ] `@Tool` bean granularity — per-family (recommended) vs per-verb (DESIGN.md detail).
- [ ] Exact normalized failure-schema fields (freeze post-spike, then an ADR).
- [ ] JUnit 6.0.3 vs 6.1.0; coverage tool; native-test CI subset.

---

## Session Notes

The user asked to use `/deep-research`, but the installed `deep-research` skill is an **academic
13-agent APA/PRISMA pipeline** — a poor fit for technical version/architecture research. A **custom
Workflow research-harness** (fan-out web research + **adversarial verification** of every load-bearing
version/compat claim, ultracode mode) was used instead and worked well: it caught real errors
(Micronaut 5.0.2 not 5.0.0; mcp-core BOM-pin 1.1.2; `archunit-junit5`↔JUnit-6 incompatibility; the
Netty fact). The user requires **verified version-truth** and **references/links on every saved doc**.
The advisor caught a genuine blind spot — an ADR marked "accepted" ahead of the `CLAUDE.md`
prototype-before-commit step — hence ADR-0006 is **proposed**. Working style: documentation-first,
durable artifacts in English, advisor/adversarial review before hard-to-reverse calls,
verify-before-done.

---

_This handoff was generated to continue work in a new session. Start at **Next Steps → 1. `/prototype`**._
