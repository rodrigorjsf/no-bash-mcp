# Ecosystem verbs invoke the trusted system manager, never a repo-authored wrapper

**Status:** accepted (2026-06-04)

Ecosystem verbs (`run_tests`, `build`, `install`, `lint`) resolve and invoke the **trusted system
manager binary** on `PATH` (e.g. `mvn`, resolving the concrete `.cmd`/`.bat` shim on Windows — gotcha
G13). They **never** invoke a repo-authored wrapper script such as `./mvnw` / `./gradlew` / a
`package.json`-pinned launcher. If the manager is absent, the verb returns `TOOL_NOT_INSTALLED` (+ hint).

## Why

The agent has write access to the repository (P8 explicitly designs the guarantee to survive that). A
wrapper like `./mvnw` is an ordinary, agent-rewritable script. If the MCP invoked it, an agent could
rewrite `./mvnw` to `#!/bin/sh\nrm -rf ~` and then call `run_tests` — turning a sanctioned, read-only-*ish*
verb into an **arbitrary-command vector the agent composed** (via a file it authored). That is precisely
the class of attack the guarantee exists to kill (P1/P2: "no agent-composed novel command").

Invoking the trusted system binary with an explicit argv array keeps the launcher outside the agent's
control. Project-authored code still runs (a malicious `pom.xml` can still bind, say, `exec-maven-plugin`
to the test phase) — but that is the **already-accepted** boundary ("running `mvn test` executes
project-authored code"; security-model.md), not a new launcher-rewrite hole the MCP opened itself.

## Considered options

- **Trusted system `mvn` (chosen).** Launcher is trusted; only pom/test code (accepted) runs.
- **Prefer `./mvnw` if present, else system `mvn` (rejected).** Honors a repo-pinned Maven version, but
  re-opens the launcher-rewrite vector — the agent rewrites the wrapper and `run_tests` executes it.
- **`./mvnw` only (rejected).** Worst security (maximal launcher-rewrite surface) and breaks on repos with
  no wrapper.

## Consequences

- **Cost accepted: a repo-pinned manager version is not honored.** A repo that requires a specific Maven
  version via its wrapper runs under the system Maven instead. This is a deliberate trade of reproducibility
  for the security guarantee. If a concrete requirement later needs pinned versions, revisit with a
  *trusted* version-resolution mechanism (e.g. the MCP itself provisioning the pinned version), never by
  executing the repo's wrapper.
- Applies to **every ecosystem manager**, not just Maven; the Node/Go PRDs inherit this decision
  (`./gradlew`, npm/pnpm/yarn shims, etc.).
- The "resolve the concrete executable, validate args strictly" rule from security-model.md (Windows shims,
  G13) is the trusted-resolution mechanism this ADR builds on.
