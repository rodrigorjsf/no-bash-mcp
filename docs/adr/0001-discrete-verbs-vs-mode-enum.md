# Discrete verbs vs. mode-enum: the convergence rule

**Status:** accepted

The tool catalog mixes two shapes — discrete verbs (`run_tests`, `build`, `lint`) and a single verb
with a `mode` enum (`dependencies(mode=direct|why|resolve)`). To keep that principled rather than
ad hoc, we adopt a **convergence rule**:

> Consolidate variants under a `mode` enum **only when they share both argument shape and output
> schema**. When arguments or output diverge, expose **discrete verbs**.

## First application — git read-only inspection

git read-only inspection is exposed as **five discrete verbs** (`git_status`, `git_diff`,
`git_log`, `git_show`, `git_branch`), **not** a single `git(mode, …)`. The modes diverge on both
axes the rule cares about: `status` (changed files + ahead/behind), `diff` (a patch retrieved via
`handle`), `log` (a commit list), `show` (one object's detail), and `branch` (refs) take different
arguments and return different schemas. `dependencies`, by contrast, keeps its `mode` enum because
its modes share an argument (a package name) and one normalized output schema.

## Why (the trade-off)

- **Tool-selection accuracy** — the agent picks a tool by its description; `git status` / `git diff`
  are already distinct commands in its mental model, so five focused descriptions map 1:1, whereas a
  `git(mode)` abstraction must be learned.
- **Schema cleanliness** — a single git tool would need the union of all modes' arguments, most
  valid for only some modes, inviting invalid combinations and conditional validation.
- **Predictable return** — discrete verbs each have a fixed return shape; a unified tool's return
  would be a union the agent cannot predict.
- **Surface-token cost is not decisive** — five schemas vs. one is a once-per-session cost in the
  tool list, negligible against the round-trip waste the project targets.

## Consequences

- Post-v1 mutating git verbs (`commit`, `add`, `stash`, `checkout`) will add discrete tools rather
  than overloading a `git(mode)`; read-only and mutating stay separate tool families, which also
  keeps the security tiers visibly separate.
- The convergence rule governs every future catalog addition, not just git.
