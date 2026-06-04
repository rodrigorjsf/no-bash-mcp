# Roadmap

## Post-v1 evolutions (in compliance with the proposal)

- **GitLab forge inspection (SaaS + self-hosted, all tiers)** — drops into the v1 forge adapter's
  self-hosted seams (base URL, auth, TLS/CA, proxy, tier/version gating). Native REST/GraphQL
  (ADR-0003), **not** the tier-gated official MCP. See `forge-security-model.md`.
- **Sandboxed-container execution** — run the MCP (and therefore every process its verbs spawn)
  inside a sandboxed container. This is the principled answer to gotcha **G2**: the guardrail (P1)
  deliberately does not contain *project-authored* code (test scripts, postinstall hooks) and
  honestly cannot; a container **contains the blast radius** of that code — orthogonal to, and
  layered on top of, the guardrail (input-side: no agent-composed commands; containment-side: bound
  what runs). Synergies: (a) the forge **base-URL allowlist (anti-SSRF) is the same list as the
  container network-egress policy** — together they defend the scariest autonomous-flow attack, a
  malicious `install` hook exfiltrating secrets, which the guardrail alone cannot; (b) the **native
  static binary in a distroless image** (D11) is the natural packaging — tiny surface, no JRE; (c) an
  **optional deployment topology** alongside the global host install (`path` per verb → mounted
  workspace volume). v1 stays an honest guardrail ("not a sandbox"); this is the sanctioned
  trajectory to strengthen the guarantee for untrusted-repo / autonomous flows.
  _Open: primary boundary — host-fs protection, network-egress/exfiltration control, or both
  (recommended: both, egress-prioritized)._
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
  See `schema-divergence-map.md` for the divergence axes this spike must reconcile.
- **Micronaut MCP STDIO spike** — register a trivial tool on the chosen baseline and confirm STDIO
  transport works end-to-end on the **GA 1.0.0** baseline, **and that the default logger routes off
  stdout** (stderr/file only — never the JSON-RPC channel) (see gotcha **G15**).
- **Forge read-only spike** — fetch GitHub CI check status + a failed-job log (via `handle`) and a
  PR view/diff, against `github.com` **and** a GHES-style configurable base URL, normalized into the
  common envelope. De-risks the self-hosted seams and the forge-side envelope before GitLab.

## Open questions (still to grill)

- Exact normalized failure schema (field-level) — to be pinned in the PRD/spec after the spike.
- Deliverable of this session (PRD / plan / issues).
