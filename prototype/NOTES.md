# Prototype verdict — universal test-result schema + ports

**Throwaway** (`prototype/`). Validates the riskiest before-coding bets against **real**
reports, then is deleted/absorbed. The *answer* below is the only thing kept — it is the spine of
`DESIGN.md` and promotes `docs/adr/0006-application-architecture.md` from *proposed* → *accepted*.

Run it: `cd prototype && mvn -q compile exec:java`

---

## The questions (exactly two undecided bets)

The macro-architecture (hexagonal / single-module / `micronaut-http-client-jdk`) was already decided in
ADR-0006. Everything in the output contract (envelope, run-cache, `get_log`, `RESOURCE_BUSY`) is already
specified in `operational-model.md` + ADR-0005. Only two things were genuinely unproven:

1. **Bet #1 — universal schema.** Do three *dissimilar* real reports (Surefire JUnit-XML, `jest --json`,
   `go test -json`) fold into **one** struct without signal loss and without false precision — in
   particular, is a **failure with no test owner** (divergence axis 5) representable as a *first-class*
   thing rather than a degenerate test with empty fields?
2. **Bet #2 — port boundaries.** Does `CommandExecutorPort` stay **format-agnostic** (process result
   only), with *all* JUnit/jest/go knowledge above it? Does `ForgePort` fold into the same envelope?

Method (advisor-directed): **generate real reports first, each hitting the hard axes, then write the
normalizers against them** — never shape a fixture to fit the struct. Reports were produced by real
toolchains (Corretto 25 + Surefire 3.5.2; Go 1.26), committed under `reports/`.

---

## VERDICT: both bets hold → ADR-0006 promoted to *accepted*

- **Bet #1 holds.** JUnit, jest, and Go fold into the identical `NormalizedRun` graph. The no-test-owner
  failure is a first-class `ContainerFinding` in **all three** — `CONT [SUITE] …SetupBrokenTests` (a
  `@BeforeAll` throw, which Surefire emits as a `<testcase name="">`), `CONT [FILE] module-broken.test.js`
  (a jest collection/module-load failure → empty `assertionResults` + file message), and
  `CONT [PACKAGE] gofix/broken` (an `init()` panic, which `go test -json` emits as a package `fail`
  event with **no `Test` field**). At no point did a package/suite/file-level failure have to be faked
  as an empty test — the discriminator the bet rode on.
- **Bet #2 holds.** `CommandExecutorPort.execute` returns only `{exitCode, stdout, stderr, timedOut}`;
  the same port serves the Maven and Go `run_tests` dispatch with zero format knowledge — including the
  file-vs-stdout report-source routing (Maven reads the file; Go parses `ExecResult.stdout`, exercised
  via the stub returning the report on stdout). (jest was folded by its normalizer directly, not driven
  through the verb in this run — its dispatch branch exists but was not exercised.) The normalizers hold
  100% of the format logic. `ForgePort` CI checks fold into the **same** envelope via
  `ContainerFinding(RUN, …)` — a CI check failure is itself a no-test-owner failure, so the schema is
  genuinely common across `run_tests` and `pr_checks`.

**Validated against all THREE real reports** (Surefire 3.5.2 on Corretto 25; jest 29.7.0; Go 1.26). All
three fold into the identical `NormalizedRun` graph, and the no-test-owner failure surfaces as a
`ContainerFinding` in **three distinct scopes, one type**: `[SUITE]` (JUnit `@BeforeAll` throw),
`[FILE]` (jest module-load failure), `[PACKAGE]` (Go `init()` panic).

> **Real-report finding that changes a design doc.** The host `npm`/`corepack` was degraded (Node 22.11
> < pnpm's 22.13 floor; `npm-cli.js` missing from the global prefix); the jest report was produced with
> the node-bundled npm via `--prefer-offline`. Producing it surfaced a factual error in
> `schema-divergence-map.md` (axis 5): it states jest's no-owner case is "`beforeAll` throws → assertions
> vanish + top-level message." **Untrue for jest 29.7.** A `beforeAll` throw is attributed to **each
> test** in the suite (they appear as failed `assertionResults`, they do *not* vanish). jest's genuine
> no-test-owner case is a **collection/module-load failure** (a top-level throw), which yields
> `assertionResults: []` + a file-level `message`. The schema handles both correctly (per-test →
> `TestFinding`; collection failure → `ContainerFinding(FILE)`). The divergence map's axis-5 jest cell is
> corrected accordingly.

---

## The validated schema (lift into DESIGN.md / the post-spike ADR)

Plain Java 25 records, **designed within serde 3.0 constraints** so they are implementable-by-construction
(boxed nullables; a polymorphic finding that maps to `@JsonTypeInfo`):

```java
enum Outcome { PASSED, FAILED, ERRORED, SKIPPED }          // normalized; raw status kept per finding

record SourceRef(String file, Integer line) {}             // best-effort, derived, nullable (line BOXED)

sealed interface Finding permits TestFinding, ContainerFinding {
    Outcome outcome(); String rawStatus(); String message(); SourceRef source(); String detail();
}
record TestFinding(String suite, String name, List<String> path,                 // flexible identity path
                   Outcome outcome, String rawStatus, String message, SourceRef source, String detail)
        implements Finding {}
enum ContainerScope { SUITE, FILE, PACKAGE, RUN }
record ContainerFinding(ContainerScope scope, String container,                  // axis 5: no test owner
                        Outcome outcome, String rawStatus, String message, SourceRef source, String detail)
        implements Finding {}

record Summary(int total, int passed, int failed, int errored, int skipped) { boolean ok(){…} }
record NormalizedRun(String tool, Summary summary, List<Finding> findings) {}
```

It honors the five "safe to assert now" invariants from `schema-divergence-map.md`:

| # | Invariant | How the schema encodes it |
|---|---|---|
| 1 | Identity is a flexible path | `TestFinding{suite, name, path[]}` — never a fixed `classname` |
| 2 | `file:line` + diff best-effort / nullable | `SourceRef` nullable, `Integer line` boxed; `message` nullable |
| 3 | First-class failure with no test owner | `ContainerFinding` (sealed sibling), carries **no** test name |
| 4 | Outcome enum + raw status retained | `Outcome` + `rawStatus` ("failure"/"error"/"fail"/"failed") |
| 5 | Expected-vs-actual not reliably structurable | `message` + raw `detail`; no structured expected/actual |

### Serde 3.0 note for DESIGN.md
- `sealed Finding` → `@JsonTypeInfo(use = NAME, property = "kind", defaultImpl = …)` so the agent branches
  on JSON shape, not on a null `name`; `defaultImpl` keeps it forward-compatible if a finding kind is added.
- `Integer line` (boxed), not `int` — serde 3.0 nullables must be boxed.
- All types `@Serdeable @Introspected` (+ `@JsonSchema` on tool I/O) → reflection-free / native-ready.

---

## How each divergence axis was reconciled (evidence from the run)

| # | Axis | JUnit (real) | jest (real) | Go (real) | Reconciliation |
|---|---|---|---|---|---|
| 1 | Identity / nesting | `classname` + `name` | file → `ancestorTitles[]` → `title` | `Package` + `/`-joined `Test` | `suite` + `name` + `path[]` |
| 2 | Outcome taxonomy | `<failure>` vs `<error>` vs `<skipped>` | `passed`/`failed`/`pending` | `pass`/`fail`/`skip` | `Outcome` enum + `rawStatus` |
| 3 | `file:line` | stacktrace frame **matching classname** (buried under JUnit frames) | stack string inside `failureMessages[]` | `Output` text `file.go:NN:` | `SourceRef`, derived, nullable |
| 4 | Message vs diff | `message` attr + stack body | message+diff+stack as ONE string | interleaved in `Output` | `message` (best-effort) + raw `detail` |
| 5 | **No test owner** | `<testcase name="">` w/ `<error>` | **collection/load failure** → `assertionResults: []` + `message` (NOT `beforeAll` — see finding) | package `fail` event, **no `Test`** | `ContainerFinding` (first-class) |
| 6 | Captured output | CDATA stack | `failureMessages[]` / file `message` | `Output` events | folded into `detail` (drilled via `get_log`) |
| 7 | Parametrized identity | `evenNumbers(int)[2]` | `test.each` interpolated title | subtest `Test/case` → `path[]` | rendered name and/or `path[]` |

---

## Boundary findings for DESIGN.md (bet #2 detail)

- **`CommandExecutorPort` is format-blind.** It exposes `{exitCode, stdout, stderr, timedOut}` only. The
  reporter-flag injection, report-source resolution, and normalizer selection all live in the verb layer.
- **Report source differs by ecosystem and the port absorbs it cleanly:** Surefire writes a **file**
  (`target/surefire-reports/`); `go test -json` writes to **stdout**. The port returns *both* stdout and
  the side-effect of a file the process wrote; the verb decides which to read. **Exercised** (not just
  asserted): the Go normalizer parses `ExecResult.stdout` (the stub returns the report on stdout), the
  Maven normalizer reads the file. _(Open: a Go **build** failure interleaves non-JSON output on stdout —
  the fixture used a runtime `init()` panic, clean JSON; the compile-error path is un-de-risked.)_
- **`ok` must be container-aware (the false-green trap).** A `ContainerFinding` is not a test, so
  `Summary` holds **test counts only** and `ok` is derived from **findings** (`no Finding is FAILED/
  ERRORED`), never from `failed==0 && errored==0`. The latter would false-green a run whose only failure
  is a no-test-owner container — the exact G5 lossy-summary failure. A synthetic container-only run guards
  it; all three normalizers count containers identically (into `findings`, never the test counts).
- **`get_log(handle)` separates signal from noise:** the envelope stays tight (counts + per-failure
  `message`/`SourceRef`); the raw stacktrace/output (`detail`) is retained in the run-cache and expanded
  on demand by handle — never re-run.
- **The envelope is genuinely common across verb families:** `pr_checks` reuses `ContainerFinding(RUN)`.
  `manager` is **null for forge/git verbs** (a forge is not a manager; git is ecosystem-agnostic —
  CONTEXT.md) and present only for ecosystem verbs.

---

## Open items handed to the spike / DESIGN.md (not blockers)

- **Divergence-map axis-5 correction (jest)** — applied to `docs/design/schema-divergence-map.md`: jest's
  no-owner case is a collection/load failure, not a `beforeAll` throw (which jest 29.7 attributes per-test).
- **Parent/child dedup (Go)** — a failing subtest also fails its parent and the package; the prototype
  suppresses the redundant parent (keeps the leaf) and suppresses a package `fail` that has a failing
  test under it. Confirm this heuristic on a multi-package real repo in the spike.
- **Count semantics (resolved into a rule)** — `Summary` counts tests only; container findings live in
  `findings` and drive `ok` (container-aware). Add a **container-only run** as an explicit spike test
  case so the false-green never regresses; pin exact count fields in the post-spike schema ADR.
- **Retry/flaky (axis 8)** — deliberately **deferred** from v1 (lower-confidence; `schema-divergence-map.md`).
- **Field names are still provisional** — frozen in an ADR **after** the spike, per the documentation-first rule.

---

## What is throwaway vs. kept

- **Kept (the answer):** this file; the record graph above; the boundary findings. These flow into
  `DESIGN.md` and the ADR-0006 flip.
- **Throwaway:** all Java under `src/`, the three fixture generators under `fixtures/`, and the captured
  `reports/`. Delete after `DESIGN.md` absorbs the learnings. The *reports* may be kept as spike fixtures
  if useful.
