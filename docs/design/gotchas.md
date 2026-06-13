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
- **G16 — Micronaut Serde needs a *clean* build after a source change in a long-lived working tree.**
  An incremental `mvn test` over a stale `target/` (e.g. right after a `git pull`/fast-forward in the
  main repo) silently drops `@Serdeable` payload fields from serialized JSON → spurious failures:
  `EnvelopeSerdeTest` went **11/14 RED** on `mvn -B test`, then **14/14 GREEN** (370/0 overall) on
  `mvn -B clean test` against the **identical tree**. A fresh `git worktree` checkout has a clean
  `target/`, so worktree-based gating (e.g. orchestrate) never hits it — only a reused working tree
  does. **After a pull/merge, run `mvn clean test` before trusting serde-introspection results.**
- **G17 — macOS arm64 native binaries must be code-signed or the OS SIGKILLs them — even when
  delivered via npm.** Apple Silicon enforces signing on *all* native ARM64 code: an unsigned,
  corrupt, or linker-only signature is not a warning — the kernel kills the process (`SIGKILL`),
  and the user **cannot bypass** it. npm does **not** exempt you: the smoking gun is OpenAI's
  codex CLI (`openai/codex#21199`, May 2026), installed via `npm install -g`, whose Darwin ARM64
  binary fails to spawn with `Unknown system error -88` — an `npm`-channel install hitting the exact
  arm64 signing wall. The fix is an **ad-hoc** signature (`codesign -s -`, free) applied **in CI
  after all post-processing** (strip etc., which can invalidate an earlier signature); do **not**
  trust GraalVM/linker auto-signing (it may be the corrupt linker-only sig that gets killed). Ad-hoc
  is *sufficient* for the npm channel specifically because npm-extracted files do **not** carry the
  `com.apple.quarantine` bit, so Gatekeeper's **notarization** gate never triggers; paid Developer-ID
  notarization is only needed once a channel sets MOTW/quarantine (curl/browser download, `.mcpb`).
  (Drives D41.)
- **G18 — npm `optionalDependencies` for per-platform binaries has install-environment failure
  modes.** The thin-launcher + per-platform scoped-package model (npm `os`/`cpu` selects the one
  matching binary) breaks silently under `--no-optional`, restrictive registry
  mirrors, or airgapped/proxied corporate installs — any of which can skip the platform package so
  the launcher resolves nothing. (`--ignore-scripts` does **not** belong here — with no `postinstall`
  the chosen model is immune to it; it bites only the rejected download model below.) Two
  non-negotiables follow: **publishing order** — every
  `@no-bash-mcp/<os>-<arch>` platform package must be published **before** the main launcher, or its
  `optionalDependencies` resolve against a registry that does not yet have them (a race that yields a
  broken install). And **no `postinstall`-download fallback** — fetching the binary at install time
  is both fragile (the same `--ignore-scripts`/proxy/airgap failure modes, now harder to diagnose)
  and a supply-chain vector (unverified network fetch during install) that directly contradicts a
  tool whose thesis is *removing* a dangerous permission. Instead the launcher **fails clear**:
  name the platform and point to the deferred secondary channel. (Drives D38; see D40.)
- **G19 — `.mcpb` (MCP Bundle) has no manifest integrity/signing fields and `platform_overrides`
  is keyed by OS, not arch (no `${arch}`).** The `.mcpb` manifest carries **zero** integrity or
  signing fields, so trust must come from an external framework (e.g. mpak) — weak for a tool whose
  whole value is security. Worse for packaging: `platform_overrides` is keyed by `process.platform`
  (`win32`/`darwin`/`linux`) **only**, with no per-architecture variable, so a single bundle cannot
  carry the right binary for both `darwin-arm64` and a future `darwin-x64` — per-arch packaging
  forces **one bundle per OS×arch** (or yet another launcher shim inside the bundle). Combined with
  the fact that `.mcpb` sets MOTW/quarantine (re-triggering the paid-notarization gate that the npm
  channel avoids per G17), this is why `.mcpb` is a **deferred secondary** channel, not the v1 primary.
  (Drives D44.)
