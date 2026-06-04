# GitLab Integration Corpus

A curated, implementation-grade reference for integrating with GitLab across its
three programmable surfaces — the **`glab` CLI**, the **HTTP APIs** (REST v4 +
GraphQL), and the **Model Context Protocol** (official + community MCP servers).

The corpus exists to ground a future, friendlier, more robust Go tool that gives
GitLab the kind of fluid LLM-agent integration the GitHub CLI (`gh`) gives
GitHub. **Self-hosted GitLab (Self-Managed and Dedicated) is the priority
audience throughout; `gitlab.com` is the secondary case.**

> **Provenance.** Researched **2026-05-29** via a fan-out web-research workflow
> (9 research angles → per-angle adversarial verification against primary sources
> → synthesis). Every doc tags claims that were *corrected* or left *unverified*
> during fact-checking. Versions, tier gating, tool counts, and maturity labels
> (experiment vs. beta vs. GA) move fast — treat anything marked
> *"as of 2026-05-29; reconfirm against your instance/version"* as a snapshot,
> not a contract.

## The documents

| Doc | What it covers | Read it when |
|---|---|---|
| [`glab.md`](./glab.md) | The official GitLab CLI: install, self-hosted auth & config, the full command-group surface, the `glab api` escape hatch, scripting, and the gaps vs. `gh`. | You want the CLI front door, or to know what `glab` already does so you don't reimplement it. |
| [`gitlab-api.md`](./gitlab-api.md) | REST v4 **and** GraphQL: base URLs, token types & scopes, pagination (offset vs. keyset), rate limits, error model, key endpoints, and a REST-vs-GraphQL decision guide. | You're calling GitLab over HTTP or deciding which API a given operation belongs on. |
| [`gitlab-mcp.md`](./gitlab-mcp.md) | GitLab over MCP: the official `/api/v4/mcp` server (OAuth DCR, 15 tools, tier gating) vs. community servers (`@zereight/gitlab-mcp` et al.), client config, and the candid token/tool-count overhead. | You're exposing GitLab *to* an agent and weighing MCP against the CLI/API. |
| [`comparison.md`](./comparison.md) | The cross-surface decision layer: capability matrix, the `gh`-parity bar, pros/cons per surface, token & agent ergonomics, and a request→surface lookup table. | You need to pick the right surface (or combination) for a specific task. |
| [`config-self-hosted.md`](./config-self-hosted.md) | The end-to-end self-hosted runbook: base-URL conventions per surface, token scoping & Admin Mode, TLS with a private CA (the right way, with the disable-verification anti-pattern called out), proxies, CE-vs-EE gating, air-gapped notes, and a troubleshooting table. | You're configuring any of these against a self-hosted instance. |
| [`state-of-the-art.md`](./state-of-the-art.md) | The synthesis: an opinionated, layered-hybrid architecture for the future Go tool — `go-gitlab/v2` SDK as the spine, raw GraphQL/REST for gaps, a thin MCP facade for agents, and a `gh`-parity roadmap. | You're designing or starting the implementation. |

## Suggested reading order

1. **Designing the tool?** [`state-of-the-art.md`](./state-of-the-art.md) →
   [`comparison.md`](./comparison.md) → the per-surface deep dives as needed.
2. **Picking a surface for one task?** [`comparison.md`](./comparison.md) first.
3. **Wiring up a self-hosted instance?** [`config-self-hosted.md`](./config-self-hosted.md) first.

## Conventions

- Every doc uses labeled **✅ Do** / **❌ Don't** fenced examples with language tags.
- Disable-TLS-verification snippets appear **only** as labeled anti-patterns,
  paired with the correct trust-the-CA fix — they are teaching counter-examples,
  not recommendations.
- Go snippets are *documentation*, not shipped code. Per this repo's rules,
  production Go must be authored via the `golang-pro` subagent and linted with
  `golangci-lint run ./...`.

---

Researched 2026-05-29; version-specific details should be reconfirmed against your instance/version.
