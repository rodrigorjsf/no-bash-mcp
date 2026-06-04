# Concurrent same-resource mutation fails fast, it does not block

**Status:** accepted

Verb calls run in parallel and cross-target concurrency is endorsed. When two **mutating** verbs
target the **same resource** — two `build`/`run_tests` on one module's output dir, or concurrent
`install` contending on a manager's lockfile and shared local repo (`~/.m2`, npm cache) — the second
call returns the structured operational error **`RESOURCE_BUSY`** (+ `hint`) **fail-fast**, rather
than blocking until the resource frees.

## Why fail-fast, not queue/block

- **Consistency with the operational-error model** (P4) — the agent branches deterministically on an
  enumerated code, exactly as for `AMBIGUOUS_SCOPE`, `DEPS_NOT_INSTALLED`, etc.
- **Timeout cleanliness** — a hidden blocking wait could exceed the caller's `timeout` confusingly;
  fail-fast keeps `timeout` meaning only "the operation ran too long," never "it waited in a queue."
- **Surfaces duplicate work** — concurrent same-target mutation is almost always the agent
  double-issuing; an explicit error reveals the mistake instead of silently serializing it.

## Granularity

- Read verbs: unrestricted concurrency (no mutation).
- `build` / `run_tests`: per resolved target.
- `install`: per manager (shared lockfile / local repo / cache).
- Run-cache eviction: never evicts an in-flight or actively-read handle.

## Consequences

- After `RESOURCE_BUSY` the agent may retry; the `hint` says so. This is preferred over hidden
  latency.
