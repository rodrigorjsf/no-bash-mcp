# Spike s1 — universal-schema FALSIFICATION (verdict)

**Throwaway, versioned.** This spike does NOT re-confirm the prototype; it tries to **break** the
schema against reports the prototype never saw, hitting the axes still flagged open in `DESIGN.md §11`.
The normalizers under test are **copied verbatim** from `prototype/`. Run:

```
cd spikes/s1-schema && mvn -q compile exec:java   # exits non-zero if any hard assertion fails
```

All hard assertions pass (captured run below). The schema survived; two **Go-normalizer** improvements
and one **counting invariant** are surfaced for the production adapters + ADR-0007.

---

## VERDICT: the schema holds on unseen reports → ready to freeze in ADR-0007

| # | Falsification target (UNSEEN real report) | Result |
|---|---|---|
| A | **Go multi-package** dedup heuristic at scale | **HOLDS** — 3 leaf `TestFinding` + 1 `ContainerFinding(PACKAGE)`; 3 redundant parents + 2 package-fails suppressed; cross-package keys distinct; deep `path=[TestReverseDeep, unicode]`; `ok()=false` |
| B | **Go compile-error** non-JSON-on-stdout claim | **CLAIM REFUTED** for Go 1.26 (see below) + a real **signal-loss** gap surfaced and **remedied** (V2) |
| C | **Container-only** run (G5 false-green keystone) | **HOLDS** — 3 tests passed by count, `ok()` STILL false. Frozen as an ADR test case |
| D | **JUnit @Nested + @ParameterizedTest + @DisplayName** identity at scale | **HOLDS** — param index in `name`, nested class in `suite`; `path[]` empty and lossless; + a **counting invariant** (below) |

`jest` was **deliberately not re-run** here: the prototype already folded a real `jest --json` with
`test.each` and a collection/module-load `ContainerFinding(FILE)`; the host `npm` is degraded (Node
22.11 < pnpm floor; `npm-cli.js` missing) so the marginal de-risking is low value at high infra cost.
Scope call recorded, not skipped silently.

---

## Finding B — the DESIGN.md §11 "compile-error interleaves non-JSON on stdout" worry does NOT reproduce

Real `go test -json ./...` on a non-compiling package (Go 1.26) emits the build failure as **JSON**
events, not raw text on stdout (`reports/go-compile-error.json`):

```
{"ImportPath":"spike/compileerror/buggy [spike/compileerror/buggy.test]","Action":"build-output","Output":"buggy/buggy_test.go:9:5: undefined: Heigth\n"}
{"ImportPath":"...","Action":"build-fail"}
{"Action":"output","Package":"spike/compileerror/buggy","Output":"FAIL\tspike/compileerror/buggy [build failed]\n"}
{"Action":"fail","Package":"spike/compileerror/buggy","FailedBuild":"..."}
```

So **every stdout line parses as JSON** — there is no non-JSON interleaving to defend against. The §11
open item "a Go build failure interleaves non-JSON output with the JSON-lines on stdout" is **closed
(refuted)** for the Go 1.26 baseline. (`stderr` was empty in both captured runs.)

### But a real signal-loss gap (and its fix → carry into the production Go normalizer)

`build-output`/`build-fail` events are keyed by **`ImportPath`** (with a ` [pkg.test]` suffix), **not**
`Package`. The verbatim prototype normalizer ignores those actions, so:

- `ok()` is **correctly false** (the later package `fail` event with no `Test` → `ContainerFinding`), so
  there is **no false-green** — the guarantee holds.
- …but the actionable compiler detail (`undefined: Heigth`, `buggy_test.go:9`) is **dropped**; the
  finding's message degrades to the useless `FAIL … [build failed]`, `source=null`.

`GoTestJsonNormalizerV2` is the remedy proven in the same run: fold `build-output` into the owning
package's buffer (strip the ` [..test]` suffix from `ImportPath`), mark `build-fail` packages, and emit
the package `ContainerFinding` with `rawStatus="build-fail"` + `Outcome.ERRORED` (a **compile** error,
not a test FAIL). After V2: `src=buggy/buggy_test.go:9  msg=undefined: Heigth`. **The schema is
unchanged** — only the Go-format knowledge in the adapter grows.

---

## Finding D — Surefire `<testsuite>` headers are UNRELIABLE under `@Nested`; count `<testcase>`

Real Surefire 3.5.2 + JUnit 6.0.3 wrote **all 12 testcases into one file** (named after the nested
class) and left the **outer** file with `tests="0"` and zero `<testcase>` elements:

| File | `<testsuite tests=>` header | actual `<testcase>` count |
|---|---|---|
| `TEST-spike.ParamNestedTests.xml` | `tests="0"` | 0 |
| `TEST-spike.ParamNestedTests$WhenNegative.xml` | `tests="12"` | 12 (mixed: 8 with classname `…ParamNestedTests`, 4 with `…$WhenNegative`) |

**Invariant to freeze:** identity and counts come from each **`<testcase classname=…>`** and its child
`<failure>`/`<error>`/`<skipped>` elements — **never** from the `<testsuite>` header. The verbatim
normalizer already counts per-`<testcase>`, so it yields the correct **12 / 7 pass / 5 fail**. A
production implementer who reads `<testsuite tests=>` would get `0` or double-count — add a `@Nested`
report as an explicit regression fixture.

Identity at scale held losslessly: param index → `name` (`isEven(int)[3]`, `singleChar(String)[3]`),
nested class → `suite` (`spike.ParamNestedTests$WhenNegative`), `path[]` stays empty. `file:line` was
parsed from the **project** stack frame (`ParamNestedTests.java:33`), not JUnit internals.

---

## What feeds ADR-0007 (schema freeze) and the production adapters

1. **Freeze the field names** of the §2 record graph as-validated (`Outcome`, `SourceRef(file, Integer
   line)`, sealed `Finding` → `TestFinding(suite, name, path[], …)` | `ContainerFinding(scope, container,
   …)`, `Summary` test-counts-only, `NormalizedRun.ok()` container-aware).
2. **Counting rules (frozen):** `Summary` counts tests only; containers live in `findings` and drive
   `ok()`. Go: dedup parent-failed-only-via-child + suppress package-fail-with-failing-test. JUnit:
   count `<testcase>`, never `<testsuite tests=>`.
3. **Go normalizer must handle `build-output`/`build-fail`** (V2) → preserve compile-error detail +
   `rawStatus="build-fail"` / `ERRORED`.
4. **Container-only run** is a frozen, must-not-false-green test case (scope SUITE/FILE/PACKAGE/RUN).
5. **retry/flaky (axis 8)** — **deferred from v1** (ratified; lower-confidence, no native marker in two
   of three formats).
6. §11's Go "non-JSON on stdout" open item → **closed (refuted)** for Go 1.26.

---

## Captured run (verify-before-done)

```
=== A) Go multi-package — dedup heuristic at scale (UNSEEN report) ===
  tool=go-test ok=false summary{total=13 passed=10 failed=3 errored=0 skipped=0} findings=4
    TEST  suite=spike/multipkg/mathx path=[TestAddTable] name=neg src=mathx_test.go:27 ...
    TEST  suite=spike/multipkg/mathx path=[] name=TestMul src=mathx_test.go:36 ...
    TEST  suite=spike/multipkg/strx path=[TestReverseDeep, unicode] name=fails src=strx_test.go:36 ...
    CONT  [PACKAGE] spike/multipkg/initbroken src=initbroken.go:8 msg=panic: ... init failure
  [PASS] ×12

=== B) Go compile-error — verbatim normalizer LOSES the compiler detail (documented gap)  [PASS] ×3
=== B2) Go compile-error — REMEDY (V2): src=buggy/buggy_test.go:9 msg=undefined: Heigth  [PASS] ×5
=== C) Container-only — 3 passed by count, ok() STILL false  [PASS] ×2
=== D) JUnit @Nested+param — 12 testcases counted (not header), identity lossless  [PASS] ×8

ALL HARD ASSERTIONS PASSED  (30 PASS total: A×12 + B×3 + B2×5 + C×2 + D×8)
```
