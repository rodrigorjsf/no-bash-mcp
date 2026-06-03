# Decision Log

Each row is a decision locked during the grill, with its rationale. Pillars are `P*`, gotchas `G*`.

| # | Decision | Rationale |
|---|---|---|
| D1 | Scope = command execution + (parked) safe Unix utilities | Read/Write/Edit/Grep/Glob already exist outside Bash; execution is Bash's unique territory. |
| D2 | Guarantee = "no agent-composed novel commands" | Honest & defensible; "zero dangerous code" needs a sandbox (G2). |
| D3 | Mechanism = argv via `ProcessBuilder`, never shell | Shell-string sanitization is a minefield (G1). |
| D4 | Flags = allowlist per operation, unknown dropped | Free flags = composition; denylist leaks (P3). |
| D5 | Catalog = logical verbs, one MCP | Small constant surface; dissolves per-repo tool filtering. |
| D6 | v1 verbs: `run_tests`(+target), `build`, `install`, `lint`, `run_task`, `describe_project`, `dependencies`, `get_log`, git read-only | Covers the implementation loop; evidence-driven additions. |
| D7 | v1 ecosystems = Maven (JVM) + Node (npm, pnpm, yarn) + Go; **Gradle deferred** | Three *dissimilar* report formats (JUnit XML, `jest --json`, `go test -json`) maximize schema de-risk and all have real local evidence. Gradle shares Maven's JUnit-XML parser → low marginal de-risk, deferred (G11). |
| D8 | Output = report-file parse → single normalized schema | Deterministic, locale/color-immune; stdout scraping is fragile. |
| D9 | Sufficiency-first / noise-truncated; operational errors = `code` + `hint` | One round-trip to actionable (P4). |
| D10 | Policy compiled-in; project config tunes non-sensitive knobs only | Guarantee survives agent repo-write (P8). |
| D11 | Native image decoupled: JVM core, native at release | Justified by distribution + footprint, not startup (G3). |
| D12 | Global / stateless install; `path` per verb; `timeout` + cap + process-tree kill | Works installed globally; no hangs. |
| D13 | Validation = JSpecify + Jakarta + programmatic guards | JSpecify is nullness-only; security boundary stays in code (G4). |
| D14 | Outbound content neutralized + marked untrusted | Confused-deputy / prompt-injection defense (P9). |
| D15 | Bootstrap skill (thin) writes MCP config + transitional git deny-list | Makes "remove Bash" deployable. |
| D16 | Guardrail mechanism = declarative deny-list, harness-agnostic adapter | Hook is bash + regex-on-string (G7); detect the harness. |
| D17 | `get_log` + `handle` for non-lossy token efficiency | Directly kills the #1 / P1 waste patterns (G5). |
| D18 | git read-only pulled into v1 | 773-call evidence; cheap ecosystem-agnostic adapter. |
| D19 | `run_task` = opt-in allowlist, fail-closed (human-authored) | Composition-safe ≠ consequence-safe (G14); `deploy:prod` is project-defined yet catastrophic. |
| D20 | Baseline = Micronaut **5.0.0** + Java **25**; Micronaut MCP **1.0.0** (MCP Java SDK 1.1.2) | Verified against the 5.0.0 platform release: Micronaut MCP 1.0.0 ships GA in the 5.0 platform with STDIO → 5/Java 25 is a GA, compatible foundation. |
