# Forge access config: instances are configurable, but only in the non-agent-mutable tier

**Status:** accepted

The command-execution policy is compiled into the binary — "project config can never introduce a new
command or flag" (P8 / D10). Forge access **refines** this: a self-hosted forge's base URL is
deployment-specific and **cannot** be compiled in, so configuration *must* be able to introduce a new
forge **instance**. The guarantee is preserved by putting the instance allowlist in the **same
human-authored, non-agent-mutable, fail-closed tier as the `run_task` allowlist** (D19) — never in
the agent-tunable "non-sensitive knobs" tier.

## The decision

- **Instance allowlist** — `{ base URL, auth reference, optional CA path, optional tier hint }` per
  instance. Default: **no instance configured → no forge access** (fail-closed). The agent cannot add
  or edit an instance; doing so would be SSRF / arbitrary-host access.
- **Token by reference, never inline** — config stores a *reference* (env-var name / secret path),
  not the secret. Resolved at runtime; never logged, never returned in an envelope, never accepted
  from agent input.
- **Read-scoped** — `read_api` (GitLab) / read-only fine-grained PAT (GitHub): a hard capability
  boundary the agent cannot exceed even under prompt injection.

## Why this is not a hole in P8

P8's intent is "the agent cannot escalate its own capability by writing the repo config." That holds:
the instance allowlist lives outside agent-mutable config, the token is a reference the agent cannot
read, and read-scope caps capability. What changes is only the *mechanism* (config, not compile-in)
for the *instance list* — forced by self-hosted URLs being deployment-specific — not the *trust
tier*.

## Consequences

- The instance allowlist doubles as the **network-egress policy** for the future sandboxed-container
  deployment (roadmap) — one list, two purposes (SSRF defense + exfiltration defense).
- Tokens never live in the binary, the repo, an envelope, or a log — only in env/vault, by reference.
