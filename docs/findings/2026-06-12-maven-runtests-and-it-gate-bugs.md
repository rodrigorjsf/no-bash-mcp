# Findings — Maven `run_tests` report-dir bug + CI acceptance-gate no-op (2026-06-12)

> Discovered while preparing PRD-4 S1 (#59) — verifying the native acceptance IT's Maven leg.
> Both are pre-existing on `development`/`master`; neither is native-specific. Empirically proven
> on this host (GraalVM CE 25.0.2 JDK 25, system mvn 3.8.6, wrapper mvn 3.9.15).

## Finding 1 — Maven `run_tests` returns `REPORT_NOT_PRODUCED` for **every** real Maven project

### Symptom
A passing Maven project driven through `run_tests` returns
`{ ok: false, error.code: REPORT_NOT_PRODUCED }` even though the tests compiled, ran, and passed.

Reproduced (JVM jar, raw STDIO, no Inspector):
```
run_tests(path=src/test/resources/fixtures/it/passing)
→ { "ok": false, "error": { "code": "REPORT_NOT_PRODUCED", ... } }
   DEFAULT target/surefire-reports written? 1   ← the report DID get produced
```
The same result was seen earlier through the **native** binary (#58 verdict, "Maven caveat").
So it is JVM + native — not native-specific.

### Root cause
- `ArgvBuilder.REPORTS_DIR_FLAG = "-Dsurefire.reportsDirectory="` (`ArgvBuilder.java:27`).
- `MavenEcosystemAdapter.buildExec()` allocates a fresh temp dir
  (`Files.createTempDirectory("no-bash-mcp-surefire-")`) and injects
  `-Dsurefire.reportsDirectory=<that temp dir>` (`MavenEcosystemAdapter.java:93–95`).
- `interpret()` reads **only that injected temp dir**; empty ⇒ `REPORT_NOT_PRODUCED`
  (`MavenEcosystemAdapter.java:104–108`).
- **`-Dsurefire.reportsDirectory` is not a valid Surefire user-property** — the `reportsDirectory`
  parameter of `surefire:test` has no `property=` binding, so the `-D` is silently ignored and
  Surefire writes to its default `${project.build.directory}/surefire-reports`. The injected temp
  dir therefore stays empty for **every** project.

### Honor matrix (proves it is version-independent, not a host-mvn quirk)
`mvn -B test -Dsurefire.reportsDirectory=/tmp/sfr` on the passing fixture; count XML in `/tmp/sfr`
vs the default dir:

| surefire | mvn 3.8.6 (system) | mvn 3.9.15 (wrapper) |
|---|---|---|
| 3.2.5 | override 0 / default 1 | override 0 / default 1 |
| 3.5.3 | override 0 / default 1 | override 0 / default 1 |

The override directory is **always empty**; the report **always** lands in the default dir.

### Scope
Maven only. Go (`run_tests`) parses NDJSON from stdout and Node/jest uses `--outputFile` (a real,
honored flag) — both unaffected and proven working (#58 go leg; v1 Node adapter). The flagship
Maven test verb (PRD-1) cannot return result findings for any real project.

### Why it was never caught
- Unit tests stub `CommandExecutorPort` and pre-populate the fresh dir, so they simulate Surefire
  writing where it never does — the tests mirror the bug.
- The acceptance IT that would catch it does not run in CI (Finding 2).

### Candidate fix (for a TDD slice, not done here)
Read Surefire's **default** `<projectDir>/target/surefire-reports`, and guarantee freshness by
deleting that directory before the run (minimal mutation; not a full `clean`) rather than relying on
the inert `-Dsurefire.reportsDirectory` override. (Alternative: a real pom-level `<reportsDirectory>`
override is config-only and cannot be injected per-invocation from the CLI.) The D27
"freshness by construction" intent is preserved by the pre-run delete.

## Finding 2 — the CI "acceptance gate" never runs the integration tests

### Symptom
`.github/workflows/integration-acceptance.yml` step 9 (the acceptance gate) runs:
```
mvn -B verify -DskipTests
```
The step comment claims `-DskipTests` "skips Surefire unit tests but runs Failsafe integration
tests." That is **false**: `skipTests` is shared and Failsafe honors it.

Proven:
```
$ mvn -B failsafe:integration-test -DskipTests
[INFO] --- maven-failsafe-plugin:3.5.6:integration-test ... ---
[INFO] Tests are skipped.
[INFO] BUILD SUCCESS
```

So `InspectorAcceptanceIT`, `GoInspectorAcceptanceIT`, `BuildInspectorAcceptanceIT`,
`NodeInspectorAcceptanceIT` **compile but never execute in CI**. The acceptance gate is green
because it runs nothing — which is exactly why Finding 1 was never surfaced by CI.

### Candidate fix
Use `-DskipUnitTests`-style separation: run the ITs with Surefire skipped but Failsafe enabled —
e.g. bind unit vs IT to distinct properties, or run `mvn -B verify -Dsurefire.skip=true` (skips only
Surefire) instead of `-DskipTests`. Verify locally that Failsafe actually executes (`Tests run: N`,
not `Tests are skipped`).

## Finding 3 — the CI workflow never triggered on `development`

`integration-acceptance.yml` watched `branches: [master, main, develop]`. The repo's integration
base is **`development`** (and default `master`); `main`/`develop` never existed. So PRs targeting
`development` — the actual integration flow — never triggered the gate at all (a third reason it
went unverified). Fixed to watch `master` + `development` (+ `slice-*`/`orchestrate/**` for push).

## Finding 4 — git_status / git_branch fail the MCP outputSchema for any repo without an upstream

Surfaced the moment Finding 2 made the gate actually run the Inspector ITs in CI:
`GitInspectorAcceptanceIT` failed `git_status` (dirty repo) and `git_branch` with `ok` not true.
Reproduced locally (dev box, git 2.43) — **not** CI-specific:

```
git_status -> isError: true
  "structuredContent does not match tool outputSchema:
   /gitStatus/ahead: null found, integer expected
   /gitStatus/behind: null found, integer expected
   /gitStatus/upstream: null found, string expected"
```

The `GitStatus` **domain** record is correct — `ahead`/`behind` are boxed `@Nullable Integer`,
`upstream` is `@Nullable String`, all deliberately null when the branch has no upstream tracking
(the common local-repo case). But the generated **MCP `@JsonSchema` outputSchema** declares them
non-nullable (`integer`/`string`), so the framework's structuredContent validation rejects the
envelope → `isError: true`. So git_status/git_branch are broken for **any repo without an upstream
remote** — i.e. most local repos.

Triple-masked until now: (a) the no-op gate (Finding 2) never ran the IT; (b) unit tests never
exercise the framework's outputSchema validation (only the domain serde); (c) the IT self-skips
locally without the MCP Inspector (`npx`). This is a real PRD-2 product bug in a different verb,
exposed — not caused — by the gate fix.

**Candidate fix:** make the generated outputSchema honor the nullable header fields (the
`@JsonSchema` processor does not treat Micronaut `@Nullable` as schema-nullable for nested records);
mirror however the `Finding`/`SourceRef` nullable fields (ADR-0007) are made schema-nullable. Owns
its own slice/issue — distinct domain from the Maven/CI fixes here.

## Impact on PRD-4 S1 (#59)
#59's native acceptance IT Maven leg ("ProcessBuilder spawns a real `mvn` test and returns
normalized Findings") **cannot be made green** for a passing/failing Maven project until Finding 1
is fixed — the bug is not native-specific. The Go leg (both `TestFinding` and `ContainerFinding`
subtypes) and the stdout-purity leg are unaffected and remain the honest native proof. If #59's
native IT is wired into CI with the same `-DskipTests` pattern, it would also be a no-op (Finding 2).
