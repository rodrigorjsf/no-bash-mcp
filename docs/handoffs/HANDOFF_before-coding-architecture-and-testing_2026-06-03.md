# Handoff: no-bash-mcp — before-coding (architecture + package structure + testing conventions)

**Created:** 2026-06-03
**Branch:** master (greenfield; no source code yet)
**Phase:** Grilling COMPLETE (extended) → **before-coding** (research → choose architecture → `/prototype` → `DESIGN.md` → `/tdd`)
**Supersedes:** `HANDOFF_grilling-to-before-coding_2026-06-03.md` (still valid for the original decisions; this one carries everything new from the extended grilling session **and** the concrete before-coding plan).

---

## Summary

The grilling phase is now fully complete. Beyond the original handoff, this session (1) resolved the
deferred topics (git surface, logging, concurrency, the test-schema approach) and (2) **expanded the
product to include read-only forge inspection (GitHub in v1, GitLab next, SaaS + self-hosted)** and a
**future sandboxed-container trajectory**. All decisions are captured in `CONTEXT.md`, five ADRs
(`docs/adr/0001–0005`), and two new design docs. **No code, no `pom.xml`, no tests exist yet.** The
next session must do the **before-coding** work: choose an architecture from research, nail the
package structure (src + tests), and — critically — **research and document the testing stack and
conventions (JUnit, MicronautTest, MCP Inspector, parameterized/nested/named tests) before writing
any production code**, per `CLAUDE.md`'s TDD-first working agreement.

---

## Work Completed (this session)

### Decisions locked (each with rationale in its ADR / design doc)

- [x] **git = five discrete read-only verbs**, not a `git(mode)` tool — **convergence rule** (mode-enum
  only when variants share *both* args and output schema; else discrete verbs). `ADR-0001`.
- [x] **Forge inspection added.** v1 = **GitHub read-only** (`pr_checks`, `pr_view`, `pr_diff` +
  existing `get_log`; canonical prefix `pr_`). Adapter **self-hosted-ready from day one** (base URL,
  auth, TLS/CA, proxy, tier/version, per-instance); GHES and GitLab Self-Managed share one
  abstraction, so v1 targets `github.com` *and* GHES. GitLab next, must cover **SaaS + self-hosted,
  all tiers**. `ADR-0002`.
- [x] **Native Java HTTP forge inspection**, no third-party MCP, no shell-out, **no generic `api`
  passthrough** (would break the forge guarantee). `ADR-0003`.
- [x] **Second threat model** → `forge-security-model.md` (one server, two documented security
  domains). Forge guarantee: "the agent cannot compose an arbitrary HTTP request; only fixed
  read-only verbs against a configured, allowlisted instance."
- [x] **Forge access config** = human-authored, non-agent-mutable, fail-closed instance allowlist +
  token **by reference** (env/vault), read-scoped. Refines P8/D10 (config *may* introduce an
  instance, but only in the non-mutable tier). `ADR-0004`.
- [x] **Concurrency**: reads unrestricted; mutating verbs fail-fast with `RESOURCE_BUSY` on
  same-resource collision (never block); run-cache eviction never touches in-flight/active handles.
  `ADR-0005`.
- [x] **Logging/STDIO**: stdout is JSON-RPC only; logs → stderr + optional file; verbosity =
  non-sensitive knob; never log secrets/untrusted content; Micronaut log routing is spike-gated. Not
  an ADR (`operational-model.md`).
- [x] **Test-result schema = a divergence map, not a frozen field list** (`schema-divergence-map.md`,
  8 axes). `file:line` is best-effort/nullable; field-freeze deferred to the universal-schema spike +
  a post-spike ADR.
- [x] **Sandboxed-container execution** = future roadmap evolution (principled answer to G2). One open
  sub-question: primary boundary (host-fs / egress / both; recommended both, egress-prioritized).

### Key Decisions table

| Decision | Rationale | Alternatives considered |
| --- | --- | --- |
| 5 discrete git verbs | tool-selection accuracy + divergent args/output; surface cost is once-per-session | single `git(mode)` |
| Forge in v1 = GitHub only | evidence-backed half; keeps the universal-schema bet the sole big risk | +GitLab SaaS / +GitLab self-hosted in v1 |
| Native HTTP forge, no passthrough | official GitLab MCP is tier-gated/OAuth-only (404 on CE/Free); community MCPs = CVE/overload; passthrough breaks the guarantee | compose with forge MCP; shell to gh/glab |
| Instance allowlist non-agent-mutable | self-hosted URLs can't be compiled-in, but the host list + token are security-sensitive | agent-tunable config; compiled-in |
| Fail-fast `RESOURCE_BUSY` | P4 operational-error consistency; keeps `timeout` clean | queue/block |
| Schema = divergence map | `file:line`/diff are derived/nullable; freezing fields on paper is false precision | freeze field list now |

---

## Files Affected

### Created (untracked — need `git add`)
- `CONTEXT.md` — glossary (seeded from the design docs + the forge cluster).
- `docs/adr/0001-discrete-verbs-vs-mode-enum.md` … `0005-concurrent-mutation-fails-fast.md`.
- `docs/design/forge-security-model.md` — the second (HTTP) threat model.
- `docs/design/schema-divergence-map.md` — universal-schema spike input.

### Modified (unstaged)
- `docs/design/tool-catalog.md` — added the forge-inspection section; added `RESOURCE_BUSY`; fixed the
  stale `git(mode)` hedge (now cites ADR-0001).
- `docs/design/operational-model.md` — added same-resource concurrency policy + Observability/logging.
- `docs/design/roadmap.md` — GitLab forge, sandbox evolution, forge spike, log-routing spike check;
  fixed stale `1.0.1-SNAPSHOT` → GA 1.0.0.
- `docs/design/README.md` — doc-table rows for the two new design docs.

### Reference (read this session)
- `docs/gitlab-integration/*` (7 docs; **staged** before this session) — **research for a *different*
  project** (a Go gh-parity tool). **Mine its facts, discard its architecture** (its
  "CLI-as-Bash-tool, MCP only as thin optional facade" conclusion is the *inverse* of this project's
  thesis). Do **not** inherit its "self-hosted-first" priority.
- `docs/design/*`, `docs/research/roundtrip-waste-evidence.md`, `CLAUDE.md`.

### Not yet created (before-coding deliverables)
- `DESIGN.md` (architecture + package structure), a **testing-conventions** doc/section, `pom.xml`,
  any source/tests.

---

## Technical Context

### The decided design surface (what the architecture must host)
- **Verbs** (tools): `describe_project`, `run_tests(target?)`, `build`, `install`, `lint`, `run_task`,
  `dependencies(mode)`, `get_log(handle,filter)`, git read-only ×5, forge `pr_checks`/`pr_view`/
  `pr_diff`.
- **Adapters, three families:** *ecosystem* (Maven/Node/Go — local subprocess, reporter injection,
  report parsing), *forge* (GitHub now; HTTP REST/GraphQL, self-hosted seams), *harness* (bootstrap
  permission-config writer).
- **Cross-cutting:** common envelope; `handle` + session-scoped run-cache; signal/noise truncation;
  operational-error codes; two security domains (command argv-guardrail; forge HTTP guarantee);
  process-tree kill + timeout; bounded concurrency + `RESOURCE_BUSY`; untrusted-content
  neutralization (P9); secret-by-reference.

### Architecture hypothesis (must be **chosen from research**, not assumed — `CLAUDE.md`)
The design is adapter-heavy with clear inbound (MCP/STDIO) and outbound (process/HTTP/harness) ports,
which makes **Hexagonal / Ports-and-Adapters** the leading hypothesis. **Do not adopt it by default**
— survey current Java application architectures (Hexagonal, Clean/Onion, Vertical-Slice, classic
layered, Micronaut-idiomatic DI/factory/AOP) **and** real MCP-server projects (Micronaut MCP samples,
the MCP Java SDK server layout, other reference MCP servers), then choose one **with a written
rationale** in `DESIGN.md`.

### Baseline (locked)
Micronaut **5.0.0**, Java **25**, build with **Maven**, Micronaut MCP **1.0.0 GA** (MCP Java SDK
1.1.2), **STDIO**. JVM dev/test; GraalVM native image at release (verify native-image supports JDK 25
in CI — G15).

---

## Before-Coding Plan (do these IN ORDER, document each, before any production code)

### A. Architecture research & choice → `DESIGN.md`
Survey the architectures above + real MCP servers; pick one with rationale. Output: `DESIGN.md`
opening with the chosen architecture and why (and the rejected alternatives).

### B. Package structure (src + tests) → `DESIGN.md`
Define and document the package layout for **both** `src/main` and `src/test`. Tests **mirror** src.
A starting hypothesis to validate against the chosen architecture (hexagonal shown; adjust to the
decision):
```
src/main/java/<base>/
  domain/        # Verb, Envelope, normalized TestResult schema, operational-error codes, signal/noise — pure, no framework
  application/   # verb handlers/use-cases; policy (flag allowlist, run_task allowlist, instance allowlist); ports
  adapter/in/mcp/        # MCP tool registration, STDIO, request→verb mapping, envelope serialization, untrusted-content neutralization
  adapter/out/ecosystem/{maven,node,go}/   # detection, ProcessBuilder invocation, reporter injection, report parsing
  adapter/out/forge/{github}/              # HTTP client, REST/GraphQL, normalization
  adapter/out/harness/{claudecode}/        # bootstrap deny-list writer
  infra/         # process exec (tree-kill, timeout), HTTP client factory (CA/proxy/auth), run-cache, secret resolution
src/test/java/<base>/   # mirrors the above
src/test/resources/fixtures/{maven,jest,go,forge}/   # real report + HTTP response golden files
```

### C. Testing stack & conventions — **RESEARCH REQUIRED, then document** (the user's emphasis)
Produce a `docs/design/testing-conventions.md` (or a `DESIGN.md` section) **before** coding, and
consider an ADR for the test-stack choice. Research and pin all of the following:

1. **JUnit (Jupiter) — use the latest GA and exploit its API to the maximum.**
   - **Pin the latest GA version** and confirm Java 25 compatibility. *JUnit 6.x is likely GA and
     Java-17+-baselined* (fits Java 25) — **verify** vs. JUnit 5.12.x and record the choice.
   - Research and standardize use of: `@ParameterizedTest` with the full source set (`@MethodSource`,
     `@CsvSource`/`@CsvFileSource`, `@EnumSource`, `@ValueSource`, `@FieldSource`, `@ArgumentsSource`,
     `@NullSource`/`@EmptySource`), argument converters/aggregators (`@ConvertWith`, `@AggregateWith`,
     `ArgumentsAccessor`); **`@TestFactory` dynamic tests** (ideal for the universal-schema divergence
     axes — one generated test per fixture/axis); **`@Nested`** (group by verb/adapter/scenario);
     **`@DisplayName` + `@DisplayNameGeneration(ReplaceUnderscores.class)`** for readable descriptions;
     grouped assertions (`assertAll`, `assertThrows`/`assertThrowsExactly`, `assertTimeout`);
     lifecycle + `@TestInstance(PER_CLASS)`; `@Tag` (unit/integration/spike); conditional execution
     (`@EnabledOnOs` for the Windows-shim/BatBadBut tests). Check whether **`@ParameterizedClass`**
     (newest) is available and useful.
   - Decide on **AssertJ** (fluent assertions) as a companion — common in Micronaut projects; research
     and choose.
2. **MicronautTest — research correct usage, best practices, and the full API surface.**
   - `@MicronautTest` (with/without `startApplication`), bean injection, `@MockBean`/`@Replaces`
     (Mockito), `@Property`/`@TestProperty` and `environments` for config, `@MicronautTest`
     interaction with JUnit 5 extensions. Decide JUnit5 (not Spock).
   - **Forge adapter tests must mock HTTP** (MockWebServer / WireMock / Micronaut `@Client` against a
     stub) — never hit real GitHub; test the read-scoped/secret-never-leaked/SSRF-rejection guards.
   - Note the **JVM-first test posture**: tests run on the JVM (two-phase build); native-image tests
     (`native-test`) are a separate, later concern.
3. **MCP Inspector (https://github.com/modelcontextprotocol/inspector)** — recommended by the
   Micronaut MCP docs.
   - Research how to launch it against a STDIO server (`npx @modelcontextprotocol/inspector <cmd>
     <args>`), what it validates (initialize handshake, `tools/list` + schemas, `tools/call`,
     notifications), and how to fold it into the verification loop (manual acceptance per verb +,
     if available, scripted/CLI mode). Find and follow the Micronaut MCP doc's recommendation.
   - It is a **dev/test tool** (Node) — not shipped; depending on it for testing is fine.
4. **Standard test patterns to enforce** (document them):
   - Universal-schema parsing: one `@ParameterizedTest`/`@TestFactory` over real fixtures of all three
     formats; golden files in `src/test/resources`.
   - `@Nested` grouping by verb/adapter; `@DisplayName` descriptions throughout.
   - **Security tests are first-class**: argv-never-a-shell-string, flag allowlist (unknown dropped),
     `run_task` fail-closed, `RESOURCE_BUSY` on collision, secret never logged/returned, SSRF
     base-URL rejection, untrusted-content neutralization.
   - **TDD red→green→refactor via `/tdd`**; Clean Code / YAGNI / KISS.

### D. `/prototype`
Flesh out the chosen design (state/data-model focus: the normalized schema + envelope + verb
dispatch) before committing.

### E. Pre-PRD spikes (run before freezing the schema/PRD)
1. **Universal-schema spike** — parse one real report of each format into one struct, reconciling the
   `schema-divergence-map.md` axes. (Riskiest bet.)
2. **Micronaut MCP STDIO spike** — register a trivial tool on the GA 1.0.0 baseline; confirm STDIO
   end-to-end **and that the default logger routes off stdout**; drive it with **MCP Inspector**.
3. **Forge read-only spike** — GitHub CI checks + failed-job log (via `handle`) + PR view/diff,
   against `github.com` **and** a GHES-style configurable base URL, normalized into the envelope.

### F. `/tdd`
Implement strictly test-first against `DESIGN.md` and `docs/design/`.

---

## Things to Know (gotchas & pitfalls)

- **G1–G15** in `docs/design/gotchas.md` remain authoritative (argv-only; honest guarantee; native
  per-platform; Windows `.cmd`/`.bat` shims/BatBadBut; reporter-injection is per *framework* not
  manager; `REPORT_NOT_PRODUCED`; etc.).
- **New:** the **second threat model** (forge HTTP: secrets, SSRF, expanded P9); the
  **gitlab-integration corpus is from another project** (facts yes, architecture no); the **sandbox
  is the answer to G2**; **`file:line` false-precision trap** (best-effort/nullable); the
  **convergence rule** governs every catalog addition; `RESOURCE_BUSY` is the collision code.
- **RTK** rewrites Bash for display — use `git status --porcelain` / `--oneline` when you need
  parseable output (this session's git capture used porcelain).

---

## Current State

### What's done
- All grilling decisions resolved and documented (CONTEXT.md, ADR-0001–0005, design docs).
- Project memory consolidation: a background `dreamer` subagent was launched at end of session to
  fold the new facts into `~/.claude/projects/-home-rodrigo-Workspace-no-bash-mcp/memory/`.

### What's not done
- Architecture not yet chosen; package structure not defined; testing conventions not researched;
  `DESIGN.md` absent; no code/tests/`pom.xml`; spikes not run.

### Tests
- [ ] Unit / integration / manual: **none** (greenfield; TDD starts after `DESIGN.md` + testing
  conventions).

### Uncommitted
- The session's docs are **uncommitted** (untracked: `CONTEXT.md`, `docs/adr/`, two design docs;
  modified: four design docs). **A checkpoint commit is recommended** before starting before-coding.

---

## Next Steps

### Immediate (start here)
1. **Research architectures** (Java app architectures + real MCP servers) → choose one with rationale.
2. **Research the testing stack** (latest JUnit API at max capacity; MicronautTest correct usage +
   full API; MCP Inspector) → write `docs/design/testing-conventions.md` (+ consider a test-stack
   ADR). **This must be nailed before any production code.**
3. **Define the package structure** (src + tests) in `DESIGN.md`.
4. **Write `DESIGN.md`** (architecture + package structure + testing posture).
5. **`/prototype`** the schema/envelope/dispatch.
6. **Run the three spikes** (universal-schema, Micronaut STDIO + Inspector, forge read-only).
7. **`/tdd`** implementation.

### Subsequent
- PRD / `/to-prd` → `/to-issues` once the schema is frozen post-spike.
- Post-v1: GitLab forge (SaaS + self-hosted), sandbox-container, Gradle, more ecosystems.

### Blocked on (verify, don't reason)
- GraalVM native-image availability for JDK 25 in CI (G15).
- Latest GA JUnit version + Java 25 compatibility (research).
- Micronaut MCP default log routing (STDIO spike).

---

## Related Resources

### Documentation (this repo)
- Decisions: `docs/design/` (README, security-model, **forge-security-model**, tool-catalog,
  operational-model, **schema-divergence-map**, build-and-distribution, bootstrap-and-deployment,
  gotchas, roadmap, decision-log).
- Glossary: `CONTEXT.md`. ADRs: `docs/adr/0001–0005`. Evidence: `docs/research/`.
- GitLab reference (facts only): `docs/gitlab-integration/`.

### External (to research in before-coding)
- MCP Inspector: https://github.com/modelcontextprotocol/inspector
- Micronaut MCP docs (tool registration, STDIO, Inspector recommendation), MCP Java SDK.
- JUnit 5/6 user guide (parameterized, dynamic, nested, display-name, extensions).
- Micronaut Test docs (`@MicronautTest`, `@MockBean`, `@Property`, HTTP client testing).

### Commands to run
```bash
git -C /home/rodrigo/Workspace/no-bash-mcp status --porcelain   # parseable status (RTK-safe)
git -C /home/rodrigo/Workspace/no-bash-mcp log --oneline -6
ls docs/design/ docs/adr/                                       # the decisions layer
# Spike (later): npx @modelcontextprotocol/inspector <server-cmd> <args>
```

### Search queries
- `grep -rn "RESOURCE_BUSY\|AMBIGUOUS_SCOPE" docs/` — operational-error codes.
- `grep -rn "self-hosted\|base URL\|read_api" docs/` — forge security seams.
- `grep -rln "ADR-000" docs/` — decisions cross-referenced.

---

## Open Questions
- [ ] Architecture choice (output of step A research).
- [ ] Package structure final shape (step B).
- [ ] Testing stack specifics: JUnit version, AssertJ yes/no, MicronautTest patterns, Inspector
      workflow (step C research).
- [ ] Exact normalized failure-schema fields (pin after the universal-schema spike, then ADR).
- [ ] Sandbox primary boundary (host-fs / egress / both) — future.

---

## Session Notes

Working style to carry forward: documentation-first, durable artifacts in English, decisions captured
**inline** as ADRs/CONTEXT.md as they crystallize; advisor-first / adversarial review before
hard-to-reverse calls (the advisor caught a forge-scope drift this session — "X should be considered"
≠ a v1 blank check); relentless one-question-at-a-time grilling **with** recommendations, generative
not only challenging; verify-before-done (stale refs were grepped and fixed); the user accepts strong
evidence-backed recommendations quickly and commits checkpoints + handoffs for continuity.

---

_This handoff was generated to continue work in a new session. Start there, and execute the
Before-Coding Plan in order — research and document architecture, package structure, and testing
conventions before writing any production code._
