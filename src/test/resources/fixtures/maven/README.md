# Maven Surefire fixtures — provenance

These are **real, captured Surefire JUnit-XML reports**, copied faithfully from git-tracked
reports produced by actual `mvn test` runs. Nothing here was hand-authored, reconstructed,
edited, or fabricated. Only the file *names* were changed for readability; the XML content
(including the noisy `<properties>` blocks) is verbatim — the captured origin of each file is
recorded below so the unmodified original can be diffed against it. They back the
divergence-matrix tests for the `SurefireNormalizer` (issue #3, ADR-0007).

| Fixture | Captured origin (in this repo) | Real run it proves |
| --- | --- | --- |
| `surefire-container-beforeall-error.xml` | `prototype/reports/TEST-nobash.proto.SetupBrokenTests.xml` | A real `@BeforeAll` throw: `tests="1" errors="1"`, a single `<testcase name="">` with `<error>`. The container-only / G5 anti-false-green fixture (AC5, AC6) and the `<error>`-on-a-no-owner-suite case (rule 1). |
| `surefire-normal-error-failure-skipped.xml` | `prototype/reports/TEST-nobash.proto.NormalTests.xml` | A real run with a test-level `<error>` → `ERRORED`, two `<failure>` → `FAILED` (one parametrized), a `<skipped>`, and passes (AC4, rule 2). |
| `surefire-paramnested-outer.xml` | `spikes/s1-schema/reports/junit-param/TEST-spike.ParamNestedTests.xml` | The `@Nested`/`@ParameterizedTest` outer file: header `tests="0"` with **zero** `<testcase>` elements — Surefire wrote all cases into the `$WhenNegative` sibling (rule 5). |
| `surefire-paramnested-whennegative.xml` | `spikes/s1-schema/reports/junit-param/TEST-spike.ParamNestedTests$WhenNegative.xml` | The sibling file that physically holds **both** `classname="spike.ParamNestedTests"` and `classname="spike.ParamNestedTests$WhenNegative"` testcases. Proves identity must come from each `<testcase classname=>`, not the file's `<testsuite name=>` header (AC3, rule 5). |

## Derived fixtures (issue #4 — `<testcase>` rows lifted VERBATIM, never fabricated)

These two are **derived**, not captured, for the issue #4 execution-tracer floors. Each lifts its
`<testcase>` rows **verbatim** from `surefire-normal-error-failure-skipped.xml` above (whose origin is
the real `prototype/reports/TEST-nobash.proto.NormalTests.xml` run); only the surrounding
`<testsuite>` header counts were adjusted to match the lifted subset and the noisy `<properties>` block
was dropped (it is irrelevant to the normalizer). No `<testcase>` content was hand-authored.

| Fixture | Derived from (rows lifted verbatim) | Real run it proves |
| --- | --- | --- |
| `surefire-all-passed.xml` | the two passing rows of `surefire-normal-error-failure-skipped.xml` (`addPasses`, `evenNumbers(int)[1]`) | An all-PASSED run with `executedTests > 0`. Backs AC1 (counts-only `ok=true`), AC4 (non-zero exit + all-PASSED → `ok=false`, the D28 exit floor), and AC10 (the MCP-injected reportsDirectory). |
| `surefire-all-skipped.xml` | the `<skipped>` row of `surefire-normal-error-failure-skipped.xml` (`skipped`) | An all-`SKIPPED` run: `executedTests == passed+failed+errored == 0` though a fresh report exists. Backs AC6 (`NO_TESTS_RUN`, the D29 positive-evidence floor — an all-`SKIPPED` run must not vacuously green). |
| `surefire-timeout-fresh-passed-partials.xml` | a byte-for-byte copy of `surefire-all-passed.xml` (itself the two passing rows of the real `NormalTests` run) | A timeout that fired MID-RUN after some tests already passed: a fresh report holds PASSED partials yet the run was killed. Backs issue #6 fixture (f) — `timedOut=true` floors `ok=false` and yields a `TIMEOUT` envelope even though the only report rows are green (the partial-PASSED-on-timeout anti-false-green case). |

## The one hand-built artifact (labelled, carried from ADR-0007)

The divergence-matrix tests also include a **hand-built `ContainerFinding` G5 guard**
asserting `NormalizedRun.ok()==false` for a synthetic container-only run. That single
assertion is **carried verbatim from ADR-0007** ("the G5 container-only guard (case C) is
asserted against a hand-built `ContainerFinding`") and is explicitly labelled as carried in
the test.

## Synthetic compiler-output fixtures (labelled, issue #23 / ADR-0009)

The following fixtures were **hand-authored** (synthetic/representative, NOT captured from a
real `mvn` run) for the `CompileDiagnosticParser` unit tests. They represent the
maven-compiler-plugin's structured console-output shape faithfully, but the content is
constructed, not captured from a real compilation.

| Fixture | Purpose | Origin |
| --- | --- | --- |
| `compiler-diagnostics-errors-and-warnings.txt` | Mixed ERROR/WARNING diagnostic lines plus Maven noise (BUILD FAILURE, COMPILATION ERROR) — proves noise filtering and coordinate parsing | **synthetic** |
| `compiler-clean-build.txt` | Successful build output (BUILD SUCCESS, no diagnostics) | **synthetic** |
| `compiler-noise-only.txt` | Only Maven noise `[ERROR]` lines without `[file:[line,col]]` shape — proves zero diagnostics when only noise present | **synthetic** |
