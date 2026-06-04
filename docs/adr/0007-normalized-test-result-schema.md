# Normalized test-result schema — field-level freeze

**Status:** **accepted** (2026-06-04) — promoted from *proposed* after an adversarial multi-lens review
(5 lenses) whose unanimous verdict was *"the schema field freeze is sound; finish the rules and scope out
the forge-security overclaims."* Both were done before promotion (see Scope and Rules below).

**Scope of this freeze (read first).** This ADR freezes **(a) the schema field names** and **(b) the
normalization/counting rules** for the universal test-result graph whose *shape* was validated by the
`/prototype` pass (ADR-0006) and **falsified against unseen real reports** by spike `s1`
([`spikes/s1-schema/NOTES.md`](../../spikes/s1-schema/NOTES.md)). It does **NOT** freeze, and must not be
read as accepting:

- **Forge-SECURITY guarantees** (read-only token enforcement, the 302 token-leak control, GHES
  operational seams). Spike `s3` validated the *by-reference + read-GET mechanism* only; the guarantees
  remain **UNPROVEN / test-owed** and live in
  [`forge-security-model.md`](../design/forge-security-model.md), not here.
- **Native serialization of the polymorphic `Finding` graph.** It is **JVM-validated** (s1 folds the real
  records); spike `s2`'s native binary only serialized a flat `PingResult`, so the
  `@JsonTypeInfo`/`defaultImpl`/boxed-nullable native path is **asserted, not yet native-proven** (a
  `/tdd`+CI obligation, see [`DESIGN.md §7`](../../DESIGN.md)).

## Context

The prototype proved the schema *folds* three real reports. The spikes tried to **break** it on reports
it had never seen, hitting the axes still flagged open in `DESIGN.md §11`:

- **Go multi-package** (`go test -json ./...`, 3 packages, sibling-prefixed tests, depth-2 subtests, an
  `init()` panic) — the parent/child/package dedup heuristic held at scale.
- **Go compile-error** — disproved the §11 worry that a build failure interleaves *non-JSON* on stdout
  (Go 1.26 emits JSON `build-output`/`build-fail` events); surfaced a real signal-loss gap + its fix.
- **JUnit `@Nested` + `@ParameterizedTest` + `@DisplayName`** — parametrized identity folded losslessly;
  surfaced that the Surefire `<testsuite>` header is unreliable under `@Nested`.
- **Forge CI checks** (`s3`) — fold into the **same** `Finding` graph via `ContainerFinding(RUN)`.

**Evidence provenance (so the freeze does not overclaim).** `PACKAGE` scope is re-falsified by real Go
reports (s1); `RUN` by a real failed `cli/cli` check (s3) — **but s3 modeled the fold as a `Map`, not the
real `ContainerFinding` record, so the forge fold is field-name-validated, not type-validated** (a `/tdd`
forge-adapter test must construct a real `ContainerFinding(RUN, …)` from a check-runs payload). `SUITE` and
`FILE` are **prototype-backed** (real jest module-load + JUnit `@BeforeAll` reports), not re-run in s1; the
G5 container-only guard (case C) is asserted against a **hand-built** `ContainerFinding`. All spike claims
are falsifiable, captured assertions (each spike exits non-zero on a broken claim).

## Decision — the frozen schema (lives in `domain/result/`)

```java
enum Outcome { PASSED, FAILED, ERRORED, SKIPPED }                  // normalized (axis 2)

record SourceRef(String file, Integer line) {}                     // best-effort, derived, nullable; line BOXED

sealed interface Finding permits TestFinding, ContainerFinding {
    Outcome outcome(); String rawStatus(); String message(); SourceRef source(); String detail();
}
record TestFinding(String suite, String name, List<String> path,   // flexible identity path (axes 1, 7)
                   Outcome outcome, String rawStatus, String message, SourceRef source, String detail)
        implements Finding {}
enum ContainerScope { SUITE, FILE, PACKAGE, RUN }                  // CLOSED for v1 (see note)
record ContainerFinding(ContainerScope scope, String container,    // no-test-owner failure (axis 5)
                        Outcome outcome, String rawStatus, String message, SourceRef source, String detail)
        implements Finding {}

record Summary(int total, int passed, int failed, int errored, int skipped) {}   // TEST counts only
record NormalizedRun(String tool, Summary summary, List<Finding> findings) {
    boolean ok() { return findings.stream()                       // CONTAINER-AWARE — never from counts
        .noneMatch(f -> f.outcome() == Outcome.FAILED || f.outcome() == Outcome.ERRORED); }
}
```

**Field names are now frozen** (`suite`, `name`, `path`, `scope`, `container`, `outcome`, `rawStatus`,
`message`, `source`, `detail`, and the `Summary`/`SourceRef` members) — stable contract for the serialized
agent-facing JSON and the production adapters. serde wire mapping per `DESIGN.md §2`:
`@JsonTypeInfo(use=NAME, property="kind", defaultImpl=…)` on the sealed `Finding`; boxed `Integer line`.

**`ContainerScope` is a CLOSED enum for v1** — deliberately, since no captured report needs a 5th scope.
Unlike the sealed `Finding` (which carries `defaultImpl` for forward-compat *finding kinds*), adding a
scope (e.g. a pytest module, a multi-module Maven aggregator) is an intentional **breaking, ADR-gated**
change. The asymmetry is by design, not oversight.

## Frozen counting & normalization rules

1. **`Summary` counts tests only; `ok()` is container-aware.** A run whose only failure is a
   `ContainerFinding` (Go `init()`/`TestMain` panic, jest module-load failure, JUnit `@BeforeAll` error,
   **or a failed forge CI check**) is **not** `ok`. Deriving `ok` from `failed==0 && errored==0` would
   false-green it (the G5 trap). **Frozen regression fixture:** a container-only run asserts `ok()==false`
   though all *test* counts are clean.
2. **`ERRORED` vs `FAILED` discriminator (the governing principle).** **`FAILED`** = the test/suite ran and
   an assertion failed. **`ERRORED`** = it could not run or terminated abnormally — a compile/build failure,
   an `init()`/`TestMain` panic, or a setup (`@BeforeAll`/`beforeAll`) error. This reconciles the spike's
   own inconsistency (the s1 V1 Go normalizer stamped an `init()` panic `FAILED`; under this rule it is
   **`ERRORED`**, matching the compile-fail case). JUnit maps the source format directly: `<error>` →
   `ERRORED`, `<failure>` → `FAILED`. Both still drive `ok()==false` — the discriminator is for *agent
   triage*, not for the gate.
3. **Go dedup.** Suppress a parent test that failed *only* via a child (keep the leaf), guarded by the
   trailing `/` so a failing `TestFoo` and a failing `TestFooBar` (no slash between them) both survive —
   only true `parent/child` pairs collapse. Suppress a package `fail` that has a failing test under it; keep
   a package `fail` with **no** failing test as `ContainerFinding(PACKAGE)`. *(Owed `/tdd` fixture: a
   no-slash failing-sibling pair — the spike's fixture only exercised genuine `/` parent/child.)*
4. **Go build/compile failure.** `build-output`/`build-fail` events are keyed by **`ImportPath`** (strip the
   ` [pkg.test]` suffix), not `Package`. Fold their `Output` into the owning package's finding and stamp
   `rawStatus="build-fail"` / `Outcome.ERRORED` (per rule 2) — preserving the compiler message + `file:line`.
5. **JUnit counts come from `<testcase>`, never the `<testsuite tests=>` header** — under `@Nested` Surefire
   writes all cases into one file and leaves the outer file `tests="0"`. Identity: parametrized index →
   `name` (`isEven(int)[3]`), nested class → `suite` (`Outer$Inner`); `path[]` may be empty.
6. **Forge CI checks** fold via `ContainerFinding(RUN, container=<check name>, rawStatus=<conclusion>)`;
   `manager` is null for forge verbs. **Frozen `conclusion → Outcome` mapping** (one observed conclusion is
   not enough to freeze by omission — this pins all of them so an unmapped value cannot false-green):
   - `success` / `neutral` / `skipped` → **not a finding** (ok contribution).
   - `failure` → `FAILED`.
   - `timed_out` / `cancelled` / `action_required` / `stale` → `ERRORED` (did not complete normally).
   - `null` / `queued` / `in_progress` → **incomplete, NOT ok** — the run is unfinished; surface as a
     pending/incomplete signal, never silently green (a fail-safe, not a pass).

   **Forge `ok()` completeness (production-adapter obligations, NOT yet de-risked — carry as DESIGN §5
   caveats):** (a) **paginate** check-runs via the `Link rel=next` header until exhausted — the spike's
   single GET would miss a failing check on page 2; (b) **merge the Commit Statuses API**
   (`/commits/{ref}/status`) into the same fold — a red *status* is invisible to `/check-runs` and would
   false-green; (c) per-check **`get_log` drill-down is envelope-level**: the envelope carries the
   `handle?` and `get_log(handle, filter=<check name>)` selects the failing check's job log — **no
   per-finding handle field is added** (the frozen `ContainerFinding` deliberately has none).

**Node/jest normalization rules are explicitly OWED, not frozen.** s1 deliberately skipped re-running jest
(the prototype already folded a real `jest --json` with `test.each` + a `ContainerScope.FILE` module-load
failure, so the **field names** are jest-validated). But the Node *normalization traps* — per-framework
reporter-flag + JSON variance (jest / vitest / mocha; detected from `package.json`, `tool-catalog.md`) — are
the analogue of the Go-dedup / JUnit-header rules and are **uncaptured**. They are captured during `/tdd`
against real Node fixtures before the Node adapter ships. This deferral is on record so the rules section is
not read as complete for the one v1 ecosystem with multi-framework variance.

## Deferred (ratified, not modeled in v1)

- **Retry / flaky (axis 8).** Lower-confidence; two of three formats have no native retry marker
  (`schema-divergence-map.md`). **Explicit assumption:** no captured report carries a retry marker, so the
  omission drops nothing real; the JUnit adapter currently ignores Surefire `<rerunFailure>`/`<flakyFailure>`
  — that is exactly what "deferred" means here. Revisit per the divergence-map axis-8 note when a concrete
  requirement needs it.

## Consequences

- One `Finding` graph, one `ok()` rule, one `get_log` drill-down across **build, test, and forge**.
- The schema is **implementable-by-construction** within serde 3.0 (boxed nullables; polymorphic sealed type
  → `@JsonTypeInfo`) — **validated on the JVM (s1)**; the *native* serialization of the polymorphic graph is
  a `/tdd`+CI obligation (see Scope). The production adapters carry the **six** rules above as tested code
  (rule 1 is the counting invariant; rules 2–6 are the per-format normalization rules).
- `DESIGN.md §2`/`§5`/`§7`/`§11` and `schema-divergence-map.md` are updated to reference this ADR as the
  freeze of record (Go "non-JSON on stdout" §11 item closed/refuted for Go 1.26; forge `ok()` caveats added
  to §5; native logback-metadata obligation added to §7).
