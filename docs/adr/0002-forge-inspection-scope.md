# Forge inspection: scope, self-hosted seams, and security framing

**Status:** accepted

no-bash-mcp gains a read-only **forge inspection** surface (CI check status, failed-job logs,
PR/MR view and diff) so the Bash permission can be removed for forge-gated autonomous flows.
git/forge read-only inspection is the single largest category of Bash traffic in the local
evidence (773 bare calls; `gh` ≈ `git`).

## Scope

- **v1: GitHub read-only only** — the evidence-backed half and the simplest (one well-understood
  API). git read-only verbs (ADR-0001) are local; forge verbs are remote HTTP.
- **The forge adapter is designed self-hosted-ready from day one** — configurable base URL,
  pluggable auth, TLS trust (private CA), proxy, and tier/version gating — even though v1 implements
  only GitHub. **GitHub Enterprise Server (GHES) needs the same seams as GitLab Self-Managed**, so v1
  targets `github.com` *and* GHES, not just SaaS — the self-hosted abstraction pays off immediately
  rather than being GitLab-only future work.
- **GitLab is the next phase and must cover both SaaS and self-hosted** (all tiers, including
  CE/Free). It drops into the same seams.

## One server, two security domains

Forge inspection ships in the **same MCP server** (one small surface is the whole point of removing
Bash), but it is a **distinct security domain** with its own guarantee and threat model, documented
separately in [`../design/forge-security-model.md`](../design/forge-security-model.md) — **not**
folded into the command-execution `security-model.md`. HTTP introduces secret management,
SSRF/base-URL exposure, and an expanded untrusted-content surface that the command-composition
guarantee does not address. The separation is deliberate so the forge threat model is not
under-served by living in the shadow of the command model.

## Consequences

- The architecture carries a `Forge` abstraction with `host` / `auth` / `tls` / `tier` seams;
  `github.com` and GHES are the first two concrete implementations; GitLab (SaaS + self-hosted)
  follows.
- The same seams that make GitLab self-hosted possible make GHES possible — designed once, used
  twice.
