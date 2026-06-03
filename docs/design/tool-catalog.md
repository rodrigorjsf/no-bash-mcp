# Tool Catalog (v1)

Manager-agnostic logical verbs (pillar P5). **One** MCP server (not one-per-manager). Every verb
accepts an optional `path` (default cwd) so the MCP works when **installed globally**.

**v1 ecosystems:** Maven (JVM) + Node (npm, pnpm, yarn) + Go. git is ecosystem-agnostic. **Gradle is
deferred post-v1** — it shares Maven's JUnit-XML report format (low de-risk) and had ≈0 local
evidence. See gotcha **G11**.

## Tools

| Tool | Purpose | Notes |
|---|---|---|
| `describe_project(path?)` | Orientation in one call: detected modules, manager per module, available verbs, **valid flags, and custom tasks/scripts**. | Custom tasks (`pnpm sandcastle:*`, `moon run <proj>:test`, Makefile targets, Gradle tasks) are **first-class** — evidence shows these are the top producers, not the standard verbs. |
| `run_tests(path?, target?, flags?, timeout?)` | Run tests; return normalized failures. | **Structured target selector** (class / file / method / module) → translated to `-Dtest=`, `--tests`, jest pattern, `-k`. Avoids full-suite re-runs (waste pattern P6). |
| `build(path?, flags?, timeout?)` | Build / compile. | Compile errors parsed to `file:line` when a parser exists; else truncated-with-cap. |
| `install(path?, flags?)` | Install dependencies. | Project-sanctioned; runs lifecycle hooks (accepted by the guarantee). |
| `lint(path?, flags?)` | Lint. | Structured findings (eslint `--format json`, checkstyle XML) when available. |
| `run_task(name, path?)` | Run a **project-defined** task the user has **opted in**. | **Opt-in allowlist, fail-closed**: by default *no* custom task is runnable; the human allow-lists tasks in the non-agent-mutable project config. "Project-defined ≠ safe to auto-run" — real repos hold `deploy:prod`, `db:migrate:prod`, `release` (gotcha **G14**). The 4 core verbs stay always-available. **No** arbitrary extra args (gotcha **G10**). |
| `dependencies(path?, mode)` | Query-oriented dependency info. | Modes: `direct`, `why <pkg>`, `resolve <pkg>`. Normalized single schema. **Never** dumps the full transitive tree. |
| `get_log(handle, filter?)` | Drill-down into a retained run result. | Expands exactly the requested slice (one failure, a test's system-out, full stderr) **without re-running**. The anti-RTK keystone (gotcha **G5**). |
| `git_status` / `git_diff` / `git_log` / `git_show` / `git_branch` (read-only) | Structured git inspection. | **Read-only only.** Ecosystem-agnostic (one cheap adapter). Highest-volume evidence category (773 calls). `git_diff` reuses `handle` + `get_log` for verbose output. Mutating git is post-v1. May be exposed as a **single `git(mode, …)` tool** to keep the surface small. |

## Output contract

- **Common envelope** for every verb: `{ ok, verb, manager, summary, handle?, ... }`.
- **Success** → minimal payload (counts only; the report is not even read).
- **Failure (test)** → normalized `failures[]` (class, test, message, `file:line`, project-side
  stack frames), with caps that truncate noise but never signal (pillar P4).
- **Failure (operational)** → enumerated `code` (`NO_MANAGER_DETECTED`, `TOOL_NOT_INSTALLED`,
  `DEPS_NOT_INSTALLED`, `REPORT_NOT_PRODUCED`, `TIMEOUT`, `INVALID_PATH`, `AMBIGUOUS_SCOPE`, …) +
  message + actionable `hint`. Distinct from test failures so the agent branches deterministically.
- **Preflight** → before `run_tests`/`build`, if dependencies are missing or out of sync with the
  lockfile, return `DEPS_NOT_INSTALLED` (hint: "run `install`") instead of letting the agent hit a
  cryptic module-not-found stack trace and waste a round-trip diagnosing it.

## Result source

Parse machine-readable **report files** (Surefire/Failsafe XML, JUnit XML, `jest --json`,
`go test -json`, …) — **not** stdout scraping (fragile to version/locale/color/flags). The MCP
**injects the reporter flag** and **knows where the report is written**, then normalizes into the
single schema.

> Normalizing dissimilar frameworks into one schema is the project's riskiest technical bet — which
> is why v1 deliberately spans **three dissimilar report formats** (JUnit XML, `jest --json`,
> `go test -json`) to validate the universal schema from day one rather than baking in JUnit
> assumptions.

> **Reporter injection is per test *framework*, not per manager** (gotcha **G12**). The JVM side is
> easy — Surefire/Failsafe emit standardized JUnit XML regardless of JUnit4/5/TestNG. The Node side
> is not — jest / vitest / mocha each need a different reporter flag and emit different JSON, so the
> adapter must detect the framework from `package.json`. Go's `go test -json` is uniform.
