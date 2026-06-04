# Native forge inspection over HTTP — no third-party MCP, no passthrough

**Status:** accepted

Forge inspection is implemented as **native Java HTTP calls** to the forge's REST/GraphQL APIs,
normalized into the common envelope — **not** by composing with a third-party forge MCP server, and
**not** by shelling out to `gh`/`glab`.

## Why not compose with a forge MCP

- The **official GitLab MCP** (`/api/v4/mcp`) requires Premium/Ultimate + GitLab Duo + OAuth DCR
  (no PAT) and returns 404 on CE/Free self-hosted — it **cannot** satisfy the "GitLab on all tiers,
  self-hosted included" requirement.
- **Community GitLab MCPs** carry CVE history (PAT bound to `0.0.0.0`), tool-overload (80–1000+ tool
  schemas — the measured anti-pattern), and no SLA. Mounting a third-party process holding a token
  contradicts the attack-surface-reduction posture of removing Bash.
- **GitHub's MCP** injects ~43 tool schemas (~42k tokens; 71% vs. 95% task accuracy in the corpus
  benchmark) — the exact tool-overload tax our thin-verb + `get_log` design exists to avoid.

## Why not shell out to gh/glab

A binary dependency whose CLI-output JSON is not a stable contract, and it reintroduces the
subprocess surface the project removes. The GitLab corpus itself says: do not shell to `glab` in a
product path.

## No generic `api` passthrough

Unlike `gh api` / `glab api`, the forge surface exposes **no** universal HTTP escape hatch — a
passthrough would let the agent compose an arbitrary request, breaking the forge guarantee. The
forge surface is deliberately **thinner** than the corpus's ~15-tool facade: only the evidence-backed
read-only verbs. The corpus optimizes for `gh`-parity; we optimize for the guarantee.

## Consequences

- One envelope / one `get_log` / one signal-vs-noise discipline across build, test, git, and forge.
- The read-only boundary, base-URL allowlist, and secret handling are owned **in-process** — see
  `forge-security-model.md`.
- REST-vs-GraphQL per verb is a `DESIGN.md`/spike detail, not an architectural commitment here.
