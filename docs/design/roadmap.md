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
- **More ecosystems**: Python (pytest / poetry / uv), Rust (cargo), .NET. (Go is **v1**, not a future
  evolution — decision-log D7 / tool-catalog.md.)
- **Custom task-runner awareness**: moon, nx, turbo, make — evidence shows these are the top
  producers, so first-class detection/dispatch is high value.
- **Affected-test selection** (git-diff-driven).
- **Outdated / updatable dependencies** (`npm outdated`, Maven versions plugin).
- **Docker preflight** structured errors.
- **Richer `get_log` filters** (by severity / file / phase).
- **Streaming / progress** notifications for long runs (MCP progress).
- **Toolchain / version reporting** in `describe_project` (JDK, Node).
- **Build-artifact path** reporting.
- **`.mcpb` one-click desktop channel** — a parallel channel for GUI install in Claude Desktop /
  Claude Code (MCP Bundle, ex-DXT; `server.type="binary"`, self-contained). Deferred because the
  `.mcpb` manifest has **zero integrity / signing fields** (would need external supply-chain trust,
  e.g. `mpak` — weak for a security tool), and `platform_overrides` keys by `process.platform`
  (`win32`/`darwin`/`linux`) **not by arch** (no `${arch}` variable) → per-arch packaging is awkward
  (one bundle per OS×arch, or a launcher). It also sets MOTW / quarantine, which **forces the paid
  Apple notarization** deferred below. See decision-log **D44**, gotcha **G19**, and `ADR-0010`.
- **`curl | sh` / Homebrew / Scoop PATH installer** — a zero-runtime-dependency channel, the
  **truest to the native thesis** (no Node shim in front of the binary, unlike the v1 npx launcher).
  Deferred because it needs a discrete install step **and** these channels set MOTW / quarantine →
  requires **paid Apple Developer ID + notarization** (~$99/yr) and a **Windows OV/EV
  code-signing cert**. See decision-log **D44**, `ADR-0010`, and `build-and-distribution.md`.
- **JVM-jar fallback** — an explicit escape hatch for the platforms GraalVM native-image for JDK 25
  does not cover (`win32-arm64`; `darwin-x64` / Intel). Deferred because it **reintroduces the JRE
  dependency native exists to kill** (YAGNI); `win32-arm64` runs the `win32-x64` build under Windows
  emulation meanwhile. See decision-log **D40** and `build-and-distribution.md`.
  _Open: add only on evidence of a real user on an uncovered platform._

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
- **Native-build hardening (the new PRD-4 — decision-log D53)** — the s2 spike proved native only for
  a trivial `PingTool` (dynamic-linked, flat serde, no subprocess, linux). PRD-4 productionizes it:
  move the `native-maven-plugin` profile into `pom.xml`, ship the logback reflection metadata, and
  native-prove the three unexercised legs of the *real* server — `--static-nolibc` link (linux),
  polymorphic `Finding` serde, and **`ProcessBuilder` subprocess spawning** — across the 4-tuple
  matrix (static on linux, system-dynamic on darwin/win; D55), emitting **ad-hoc-signed** binaries
  (D54) as CI artifacts. **Blocks PRD-5.**
- **Distribution-channel spike (the PRD-5 gate — D53)** — validate the *chosen primary channel*
  end-to-end (ADR-0010), **consuming PRD-4's signed artifacts**; its tracer is **`darwin-arm64`**
  (does the ad-hoc signature survive `npm pack`→extract→`npx` spawn — the `openai/codex#21199`
  residual; D54): an
  `npx -y no-bash-mcp@<pin>` invocation resolves the host's `@no-bash-mcp/<os>-<arch>` platform
  package via npm `os`/`cpu`, the launcher shim spawns the native binary, and the **STDIO JSON-RPC
  handshake** completes — including the **ad-hoc-signed `darwin-arm64`** leg actually launching (not
  `SIGKILL`). Primary evidence this exact mechanism fails in the wild: `openai/codex#21199` (an
  npm-delivered native arm64 binary that does not spawn). Spike-gated, not assumed (G15 discipline);
  de-risks the channel before any package is published.

## Open questions (resolved)

- Exact normalized failure schema (field-level) — **resolved**: frozen in
  [ADR-0007](../adr/0007-normalized-test-result-schema.md) by the universal-schema spike.
- Deliverable of the PRD-3 grill (PRD / plan / issues) — **resolved** (2026-06-05): published as
  **PRD-003** (issue #45) with slices S1–S5 (#46–#50); decisions in decision-log D46–D52 +
  [ADR-0011](../adr/0011-ecosystem-dispatch-strategy.md).
- Frontier after PRD-3 — distribution one PRD or two? — **resolved** (2026-06-11): the s2 spike proved
  native only for a trivial `PingTool`, so the real server's native build is itself unproven →
  **split** into **PRD-4 = native binary hardening** (matrix → signed artifacts) and **PRD-5 = npm/npx
  distribution channel** (consumes PRD-4's artifacts). Supersedes D46's "Distribution (#44) = PRD-4";
  issue **#44** is re-scoped to **PRD-005** and blocked by the new **PRD-004 (#57)** — slices P0 #58,
  S1 #59, S2 #60, S3a #61, S3b #62, S4 #63. Decisions in decision-log **D53–D55**; ADR-0010 unaffected.

