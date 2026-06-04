# Universal Test-Result Schema — Divergence Map

The project's riskiest technical bet is normalizing dissimilar test frameworks into **one** schema
(see `tool-catalog.md`). v1 spans three **dissimilar** report formats — JUnit XML
(Surefire/Failsafe), `jest --json`, and `go test -json` — to validate the schema from day one rather
than baking in JUnit assumptions.

This document is the **input to the universal-schema spike** (roadmap). It maps *where the three
formats refuse to share a shape*. It deliberately does **not** freeze field names — that is the
spike's job (parse one real report of each into one struct), recorded in an ADR **after** the spike.
Freezing fields on paper here would manufacture false precision — the very thing the spike exists to
settle.

## Divergence axes

| # | Axis | JUnit XML | `jest --json` | `go test -json` |
|---|---|---|---|---|
| 1 | Identity / hierarchy | `classname` + `name` (~2 levels) | file → `ancestorTitles[]` (arbitrary `describe` nesting) → `title` | `Package` + `Test` (+ `/`-joined subtests) |
| 2 | Outcome taxonomy | failure / error / skipped | passed / failed / pending / skipped / todo | pass / fail / skip (+ build-fail / panic implicit) |
| 3 | `file:line` | often **absent** — parse from `<failure>` stacktrace | **not a field** — parse from `failureMessages[]` stack | **not a field** — parse from `Output` lines |
| 4 | Message vs assertion diff | `message` attr + stacktrace body | `failureMessages[]` (message + diff + stack as one string) | interleaved in `Output` text |
| 5 | Failure with no single test owner | suite-level `<error>` / setup testcase | file-level (`beforeAll` throws) → assertions vanish + top-level message | **package-level FAIL** with no `Test` (build failure, `TestMain`, `init` panic) |
| 6 | Captured output | `system-out` / `system-err` per suite/case | console per test (reporter-dependent) | `Output` events interleaved |
| 7 | Parametrized identity | `name[0]`, display names | `test.each` interpolated titles | subtests `Test/case` |
| 8 | Retry / flaky *(lower-confidence; confirm in spike)* | Surefire `flakyFailure` / `rerunFailure` | `jest.retryTimes` re-runs | `-count` / tooling re-runs; no native retry marker |

## Safe to assert now (without freezing field names)

1. **Identity is a flexible path** (`suite`/`name` + optional `path[]`), never a fixed `classname`
   (axes 1, 7).
2. **`file:line` and assertion diff are best-effort, derived, nullable** — never guaranteed fields
   (axes 3, 4). The false-precision trap: `file:line` is parsed, not a field, in two of three formats
   and sometimes absent in the third.
3. The schema needs a **first-class failure not attributable to a single test** — suite / file /
   package-level (axis 5, a structural divergence).
4. **Outcome = a normalized enum + the raw status retained** (axis 2).
5. **Expected-vs-actual cannot be reliably structured** across all three → keep a `message` + a
   best-effort diff (axis 4).

## What stays open until the spike

- Exact field names and the concrete struct.
- Whether retry/flaky (axis 8) is modeled in v1 or deferred.
- The recorded schema → an ADR **after** the spike, not before.
