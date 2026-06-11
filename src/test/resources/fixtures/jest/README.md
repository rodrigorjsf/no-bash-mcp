# jest `--json` fixtures (PRD-3, slice 4)

`jest --json` report captures, driving the divergence matrix for `JestJsonParser`
(the pure, I/O-free domain normalizer). The parser takes the report JSON as an
in-memory `String` and folds it into a `NormalizedRun` (ADR-0007 schema), exactly
as `SurefireNormalizer` does for Surefire XML and `GoTestJsonParser` does for the
`go test -json` NDJSON.

Unlike Go's NDJSON, a jest report is **one** big JSON object with NESTED arrays
(`testResults[]`, `assertionResults[]`, `failureMessages[]`) and nested objects
(`location{line,column}`), so the parser uses a small brace/bracket-aware scanner
rather than the Go flat-line scanner.

## The exact invocation

```
npx jest --json --outputFile=<fresh> --testLocationInResults --no-install
```

The MCP injects these flags (the Node analog of Surefire's injected
`-Dsurefire.reportsDirectory`): `--json` (machine report), `--outputFile=<fresh>`
(D27 freshness; the report goes to the FILE and **stdout stays empty**),
`--testLocationInResults` (populates `assertionResults[].location = {line,column}`
— load-bearing, see `jest-all.json`), and `--no-install` (anti-network-fetch, D38;
a missing jest binary is a preflight `DEPS_NOT_INSTALLED`, never an implicit
download).

## Provenance

| Fixture | Source | What it proves |
|---|---|---|
| `jest-loc.json` | **Real capture** (jest 30.4.1, `/tmp/nbm-jest/jest-loc.json`) | the canonical run: 1 pass (`pass.test.js`), 1 assertion-fail (`fail.test.js`), 1 module-load failure (`loadfail.test.js`). Captured WITH `--testLocationInResults` → `assertionResults[].location = {line,column}` is populated. The failing assertion → `TestFinding` + best-effort `file:line` (file = `name` basename, line = `location.line`, col dropped). The module-load suite → `status:"failed"` + EMPTY `assertionResults` → `ContainerFinding(FILE, ERRORED)` (`testExecError` is NULL in jest 30 — keyed on empty `assertionResults`, never on `testExecError`). Note `testResults[]` ordering varies run-to-run, so the parser keys on content, never index. |
| `jest-all.json` | **Real capture** (jest 30.4.1, `/tmp/nbm-jest/jest-all.json`) | the SAME suite captured WITHOUT `--testLocationInResults` → `assertionResults[].location` is `null`. Proves the flag is load-bearing (`file:line` derivation must tolerate `location:null` without NPE → `SourceRef` is null/file-only) and that `testResults[]` ordering is non-deterministic (this capture lists `loadfail` first, `jest-loc.json` lists it second). |
| `jest-all-passed.json` | **Hand-authored** from the jest 30.4.1 grammar | an all-green run (`sum.test.js`: 2 passing assertions under a `describe("sum")` block) → `Summary(2,2,0,0,0)`, no findings, `run.ok()==true`. Exercises `ancestorTitles[]` folding into the flexible `path[]`. |
| `jest-module-load-fail.json` | **Hand-authored** from the grammar (the `loadfail` suite, isolated) | a module-load-ONLY run → ZERO `assertionResults`, `numTotalTests:0` → `executedTests==0`, yet a single `ContainerFinding(FILE, ERRORED)` makes `run.ok()==false`. The **G5 keystone**: the use-case floor must route this to a test-failure envelope, NOT `NO_TESTS_RUN`. |

## jest `--json` grammar (fields used)

A jest report is one object `{numTotalTests, numPassedTests, …, success, testResults[]}`:

- `testResults[]` — one element per test FILE. Fields used: `name` (absolute file
  path; the basename is the `suite`/`file` identity), `status` (`"passed"` /
  `"failed"`), `message` (the file-level `● Test suite failed to run …` text on a
  module-load failure), `assertionResults[]`.
- `assertionResults[]` — one element per `it`/`test`. Fields used: `title` (the leaf
  name), `ancestorTitles[]` (the `describe` nesting → the flexible `path[]`),
  `status` (`"passed"` / `"failed"` / `"pending"` / `"todo"` → pending/todo map to
  SKIPPED), `failureMessages[]` (`[0]` → `message`; joined → `detail`),
  `location` (`{line,column}` when `--testLocationInResults`, else `null`).
- **No-test-owner (axis 5) discriminator** = `status:"failed"` + EMPTY
  `assertionResults` → `ContainerFinding(FILE, ERRORED)`, message from
  `testResults[].message`. `testExecError` is `null` in jest 30 — NOT consulted.
- Counts derive from `assertionResults[]` per ADR-0007 (counts-from-elements),
  NEVER from the top-level `numPassedTests`/`numFailedTests` header. The container
  finding is EXCLUDED from the test counts so a module-load-only run is
  `executedTests==0` and `ok()` is driven by findings.
