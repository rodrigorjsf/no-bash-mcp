# Gotchas

Traps surfaced during design, and why they are traps.

- **G1 — Shell-string parsing is a minefield.** Escaping, metacharacters, aliases. Never accept a
  raw command line. → argv-array only. (Drives D3.)
- **G2 — "100% safe" is impossible if you run tests/builds.** Project code executes (test scripts,
  postinstall hooks). State the honest guarantee: no *agent-composed* commands. (Drives D2.)
- **G3 — GraalVM native image is per-platform.** "Any OS" = a CI build matrix, not one artifact.
  Startup is **not** the justification (a session-lived server warms up once).
- **G4 — JSpecify ≠ input validation.** It is nullness-only, static. Use Jakarta Bean Validation
  for value constraints and explicit programmatic guards for security rules.
- **G5 — Lossy summarization causes *more* round-trips.** Empirically the #1 waste pattern locally
  (159×) is self-inflicted by RTK-style truncation that drops signal and forces re-runs / `cat` of
  a tee-log. Token-efficiency must be **non-lossy** → `handle` + `get_log`. *This is the structural
  difference between this MCP and a lossy summarizer.*
- **G6 — The transitional git deny-list is fragile + harness-coupled.** It is a denylist; treat it
  as explicitly temporary and prioritize native git verbs to retire it.
- **G7 — A bash `PreToolUse` hook breaks Windows-native and re-introduces regex-on-string.** Prefer
  a declarative deny-list.
- **G8 — Fullstack repos carry multiple managers at once** (Gradle + npm). Directory-scoping is
  required from v1 — this is the common case, not a monorepo edge case.
- **G9 — Some runners need a reporter flag/plugin to emit machine-readable output** (pytest
  `--junitxml`, mocha reporter). Handle "report not produced" as the operational error
  `REPORT_NOT_PRODUCED`.
- **G10 — `run_task` arg-passthrough is an escape-surface** (no per-task flag allowlist). Forbid or
  tightly limit extra args.
- **G11 — Evidence gap.** In local transcripts, **Gradle ≈ 0** and **pytest = 0**; Maven evidence
  lives in a single repo. **Result:** Gradle is deferred post-v1; the v1 set (Maven + Node + Go) is
  the evidence-backed one. Still validate the single-repo Maven evidence against more real repos.
  (See [`../research/roundtrip-waste-evidence.md`](../research/roundtrip-waste-evidence.md).)
- **G12 — Reporter injection is per test *framework*, not per manager.** On the JVM,
  Surefire/Failsafe emit standardized JUnit XML regardless of JUnit4/5/TestNG → one parser. On Node,
  jest / vitest / mocha each need a different reporter flag and emit different JSON → the adapter
  must detect the framework from `package.json` deps. Go's `go test -json` is uniform.
- **G13 — Windows package-manager launchers are `.cmd`/`.bat` shims.** `ProcessBuilder("npm", …)`
  cannot execute them directly; you must resolve the concrete `npm.cmd`/`mvn.cmd` path. Beware the
  `.cmd`/`.bat` **argument-quoting** vulnerability class (BatBadBut, CVE-2024-1874 et al.) — apply
  strict arg validation for shim targets. This refines pillar P2: the invariant is *no
  agent-controlled shell string*, not *never spawn an OS process facility*.
- **G14 — Composition-safe ≠ consequence-safe.** The formal guarantee (no *novel* command) governs
  composition; the project goal governs *consequence* (no autonomous damage). `run_task` is
  composition-safe (the body is project-authored) but **not** consequence-safe — `deploy:prod`,
  `release`, `db:migrate:prod` are project-defined yet catastrophic. → opt-in allowlist, fail-closed.
- **G15 — The foundational dependency is young.** Micronaut MCP reached **GA 1.0.0** in the
  Micronaut 5.0.0 platform (MCP Java SDK 1.1.2), so the earlier `1.0.1-SNAPSHOT` concern is resolved
  — but a fresh 1.0.0 on a **Java 25** baseline is still young. Pin versions, watch for breaking
  changes, verify STDIO tool registration with the Micronaut-MCP spike, and **confirm GraalVM
  native-image supports JDK 25** in CI before relying on it.
