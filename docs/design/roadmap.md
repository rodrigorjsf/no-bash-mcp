# Roadmap

## Post-v1 evolutions (in compliance with the proposal)

- **Gradle adapter** — deferred from v1 (≈0 local evidence, redundant JUnit-XML format). Cheap to
  add: reuses Maven's JUnit-XML parser; only detection + verb-mapping + reporter-injection differ.
- **Mutating git verbs** (commit, add, branch create/switch, stash) — allowlist-based, careful;
  retires the transitional deny-list bridge.
- **Safe Unix utilities surface** (the parked scope-root choice) — only where it adds value beyond
  native Read/Grep/Glob, implemented **Java-native** for cross-platform (no shelling to
  `grep`/`find`, which Windows lacks).
- **More ecosystems**: Python (pytest / poetry / uv), **Go** (`go test -json` — already appears in
  local evidence), Rust (cargo), .NET.
- **Custom task-runner awareness**: moon, nx, turbo, make — evidence shows these are the top
  producers, so first-class detection/dispatch is high value.
- **Affected-test selection** (git-diff-driven).
- **Outdated / updatable dependencies** (`npm outdated`, Maven versions plugin).
- **Docker preflight** structured errors.
- **Richer `get_log` filters** (by severity / file / phase).
- **Streaming / progress** notifications for long runs (MCP progress).
- **Toolchain / version reporting** in `describe_project` (JDK, Node).
- **Build-artifact path** reporting.

## Pre-PRD spikes (de-risk before freezing)

- **Universal-schema spike** — parse one *real* report of each format (JUnit XML, `jest --json`,
  `go test -json`) into one struct. The riskiest bet must be validated empirically, not on paper.
- **Micronaut MCP STDIO spike** — register a trivial tool on the chosen baseline and confirm STDIO
  transport works end-to-end (the dependency is `1.0.1-SNAPSHOT`; see gotcha **G15**).

## Open questions (still to grill)

- Exact normalized failure schema (field-level) — to be pinned in the PRD/spec after the spike.
- Deliverable of this session (PRD / plan / issues).
