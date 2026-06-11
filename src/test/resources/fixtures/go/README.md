# Go `go test -json` fixtures (PRD-3, slice 2)

NDJSON captures of `go test -json` stdout, driving the divergence matrix for
`GoTestJsonParser` (the pure, I/O-free domain normalizer). The parser takes the
stdout NDJSON as an in-memory `String` and folds it into a `NormalizedRun`
(ADR-0007 schema), exactly as `SurefireNormalizer` does for Surefire XML.

## Provenance

| Fixture | Source | What it proves |
|---|---|---|
| `go-build-fail-src.ndjson` | **Real capture** (Go 1.26, `/tmp/nbm-gofail/out-brokensrc.json`) | a non-compiling Go *source* file → `build-output`/`build-fail` events + a package `fail` with `FailedBuild` → folded INTO the graph as `ContainerFinding(PACKAGE, ERRORED)` (the report-absence asymmetry: Go folds, Maven returns `REPORT_NOT_PRODUCED`). `build-output.Output` carries `file:line:col` → best-effort `SourceRef{file,line}` (col dropped). |
| `go-build-fail-test.ndjson` | **Real capture** (Go 1.26, `/tmp/nbm-gofail/out-brokentest.json`) | a non-compiling Go *test* file → same fold; the compiler diagnostic is `undefined: undefinedSymbol`. |
| `go-build-fail-multi.ndjson` | **Real capture** (Go 1.26, `/tmp/nbm-gofail/out-all.json`) | two packages fail to compile in one run → two distinct `ContainerFinding(PACKAGE, ERRORED)`, each keyed to its own `Package`. |
| `go-pass-fail-skip.ndjson` | **Hand-authored** from the documented `go test -json` grammar (the `/tmp` captures are compile-fail ONLY — no runtime pass/fail/skip was captured during the grill) | a normal run: `TestAdd` pass, `TestSubtract` fail (`calc_test.go:21` in the output), `TestDivideByZero` skip → `Summary(2,1,1,0,1)`, one `TestFinding(FAILED)` carrying best-effort `file:line`. The package-level `fail` (no `Test`) is suppressed because a leaf test already failed. |
| `go-subtest-fail.ndjson` | **Hand-authored** from the grammar | `TestValidate/rejects_empty` (a subtest) fails; `TestValidate` (the parent) and the package both emit `fail`. The leaf carries the failure; the redundant parent and package `fail` events are SUPPRESSED → exactly ONE `TestFinding`, identity `name="rejects_empty"`, `path=["TestValidate"]`. |
| `go-all-passed.ndjson` | **Hand-authored** from the grammar | an all-green run → `Summary(2,2,0,0,0)`, no findings, `run.ok()==true`. |

## `go test -json` grammar (events used)

Each line is a flat JSON object `{Time, Action, Package, Test, Elapsed, Output, ImportPath, FailedBuild}`:

- `Action ∈ {start, run, pause, cont, output, pass, fail, skip}` for test/package events;
  `{build-output, build-fail}` for compile failures (keyed by `ImportPath`, not `Package`).
- A test: `run` then exactly one terminal `pass | fail | skip`. Subtests carry `Test="Parent/child"`.
- A package-level terminal (`pass | fail` with **no** `Test`) is the container/redundant signal.
- A compile failure: `build-output` (header) → `build-output` (the `file:line:col: message` diagnostic)
  → `build-fail` → package `start` → package `output` (`FAIL\t<pkg> [build failed]`) → package
  `fail` (carries `FailedBuild=<ImportPath>`). Output is 100% valid NDJSON (Go 1.26, even with `2>&1`).
