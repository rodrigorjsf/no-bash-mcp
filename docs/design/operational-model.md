# Operational Model

## Install — global / stateless

The MCP is **not** bound to a single project. It can be installed **globally** and serve any repo.
Every verb accepts an optional `path` (default cwd); the MCP detects the manager **at that path**
and reads any non-sensitive project config from there.

## Scoping / ambiguity

Detection is **file-marker based** and deterministic (`pom.xml`, `build.gradle`,
`package.json` + lockfile, `Cargo.toml`, `go.mod`, `pyproject.toml`, …) — nothing is executed to
detect.

A fullstack repo carries **multiple managers at once** (e.g. Gradle backend + npm frontend). This
is the common case for the target audience, not a monorepo edge case (gotcha **G8**). Resolution is
**directory-scoped** via the `path` argument — no magic precedence:

```
run_tests(path="backend")    # Gradle
run_tests(path="frontend")   # npm/pnpm
```

When a verb is called ambiguously (multi-manager repo, no `path`), it returns the operational error
`AMBIGUOUS_SCOPE` listing the available modules/paths — discovery comes for free on the error path,
complementing `describe_project`.

## Timeout

Long verbs (`run_tests`, `build`) accept a `timeout`:

- **Default** per verb (sane).
- **Override** by the agent up to a **max cap** — the cap is a non-sensitive tuning knob, so the
  agent cannot set an infinite timeout.
- **On expiry**: kill the **whole process tree** (`destroyForcibly` + descendants) and return a
  structured `TIMEOUT` envelope with any partial signal. The MCP never hangs.

## Run cache (for `get_log`)

To deliver token-efficiency **without** signal loss, a verb returns a tight `summary` plus a
`handle`. The MCP **retains the full result** (report + stdout + stderr) indexed by that handle:

- Retention is **transient and session-scoped** (last-N runs / TTL).
- It is **not** durable config — it does not break the stateless-install property.
- `get_log(handle, filter?)` expands exactly the slice the agent asks for, with no re-run.
- **Defaults** (non-sensitive tuning knobs): keep the last ~10 runs, ~30 min, **or a total size
  cap (~N MB)** — whichever comes first. A single build log can be hundreds of MB; large logs are
  spilled to a temp file or truncated-with-pointer rather than held whole in memory (relevant given
  native-image-for-footprint).

The full run lifecycle — from the agent's verb call to the optional `get_log` drill-down — passes
through detection, verb mapping, the trusted Launcher, capture, and the common Envelope, with
`TIMEOUT` and `RESOURCE_BUSY` as fail-fast branches that never hang:

```mermaid
stateDiagram-v2
    classDef agent fill:#2d6cdf,stroke:#9ec1ff,color:#ffffff
    classDef core fill:#6a4fb3,stroke:#c3b1f0,color:#ffffff
    classDef adapter fill:#2e8b57,stroke:#8ff0bd,color:#ffffff
    classDef branch fill:#c0392b,stroke:#f5b7b1,color:#ffffff

    [*] --> Request
    Request: Request — verb(path?) (default cwd)
    Request --> Detect

    Detect: Detect environment — file-marker, deterministic, nothing executed
    Detect --> Ambiguous: multi-manager, no path
    Detect --> MapVerb: resolved target

    Ambiguous: AMBIGUOUS_SCOPE — list modules/paths
    Ambiguous --> [*]

    MapVerb: Map verb — use-case · validate · preflight (DEPS_NOT_INSTALLED)
    MapVerb --> Busy: mutating + resource in use
    MapVerb --> Invoke: lock acquired

    Busy: RESOURCE_BUSY — fail-fast (+ hint)
    Busy --> [*]

    Invoke: Invoke — CommandExecutorPort to ecosystem adapter to Launcher (mvn/npm on PATH)
    Invoke --> Capture: process exits
    Invoke --> Timeout: timeout cap exceeded

    Timeout: TIMEOUT — kill whole process tree, keep partial signal
    Timeout --> Envelope

    Capture: Capture — child stdout / stderr / report
    Capture --> Envelope

    Envelope: Envelope — normalize to NormalizedRun · summary + handle · retain full result in run-cache · P9 neutralize to stdout JSON-RPC
    Envelope --> GetLog: agent calls get_log(handle, filter?)
    Envelope --> [*]

    GetLog: get_log drill-down — expand slice, no re-run (G5 keystone)
    GetLog --> [*]

    class Request agent
    class GetLog agent
    class Detect core
    class MapVerb core
    class Envelope core
    class Invoke adapter
    class Capture adapter
    class Ambiguous branch
    class Busy branch
    class Timeout branch
```

*The real run lifecycle: detection executes nothing, the Launcher is reached only through `CommandExecutorPort`, and `TIMEOUT`/`RESOURCE_BUSY`/`AMBIGUOUS_SCOPE` are fail-fast exits — the MCP never hangs, and the full result survives behind the `handle` for `get_log`.*

## Concurrency

Verb calls may run **in parallel** (e.g. `run_tests(path="backend")` and
`run_tests(path="frontend")` at once). Each call spawns its own process and gets its own `handle`;
calls are independent. The MCP applies a **bounded concurrency cap** to avoid resource exhaustion
from many heavy builds at once.

**Same-resource collisions.** Cross-target concurrency is fine; **same-resource mutation** is not.
By verb class:

- **Read verbs** (`git_*`, `pr_*`, `dependencies`, `describe_project`, `get_log`) — unrestricted
  concurrency; they mutate nothing.
- **Mutating verbs** (`build`, `run_tests`, `install`) — **mutual exclusion at the granularity of the
  resource they mutate**, enforced **fail-fast** with the operational error `RESOURCE_BUSY` (+ `hint`),
  **never** by hidden blocking (blocking interacts badly with the caller's `timeout` and hides
  duplicate work):
  - `build` / `run_tests` → per **resolved target** (the module owning the output dir).
  - `install` → per **manager** (lockfile and the shared local repo/cache — Maven `~/.m2`, npm cache —
    are global beyond a single `path`).
- **Run cache** — byte-cap eviction **never touches an in-flight or actively-read handle**; it evicts
  only completed, idle entries, oldest-first within the cap.

```mermaid
stateDiagram-v2
    classDef live fill:#2e8b57,stroke:#8ff0bd,color:#ffffff
    classDef idle fill:#b8860b,stroke:#f0d58c,color:#ffffff
    classDef gone fill:#c0392b,stroke:#f5b7b1,color:#ffffff

    [*] --> InFlight: verb spawns process, handle issued
    InFlight: In-flight — process running
    InFlight --> Idle: process completes, result retained

    Idle: Idle — full result cached (report + stdout + stderr)
    Idle --> Reading: get_log(handle)
    Reading: Actively read — get_log expanding slice
    Reading --> Idle: read returns

    Idle --> Evicted: oldest-first, over byte-cap / TTL / last-N
    Evicted: Evicted — entry dropped
    Evicted --> [*]

    note right of InFlight: eviction NEVER touches in-flight
    note right of Reading: eviction NEVER touches actively-read

    class InFlight live
    class Reading live
    class Idle idle
    class Evicted gone
```

*Handle eviction only ever reclaims **completed, idle** entries oldest-first — an in-flight or actively-read handle is exempt, so a `get_log` drill-down can never race its own cache entry away.*

Fail-fast over queue/block keeps the operational-error model (P4) consistent and `timeout` clean —
see ADR-0005.

## Observability / logging

STDIO uses **stdout for the JSON-RPC channel**, so **no log may ever be written to stdout** — doing
so corrupts the protocol. Therefore:

- **All logs go to stderr**, with an **optional log file** (path via a non-sensitive config knob) for
  post-hoc debugging when the harness does not capture stderr.
- **Verbosity** is a non-sensitive, agent-tunable config knob.
- **Never log secrets or raw untrusted content** — forge tokens (see `forge-security-model.md`) and
  repo-derived content (P9) are excluded; the neutralize-and-cap discipline applies to logs too.
- **Spike-gated, not assumed:** confirm Micronaut MCP's default logger routes **off stdout** in the
  STDIO spike — an empirical check (gotcha **G15**), never reasoned to a conclusion.
