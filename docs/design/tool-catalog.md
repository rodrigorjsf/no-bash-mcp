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
| `build(path?, flags?, timeout?)` | Build / compile. | Compile errors → `diagnostics[]` of `CompileDiagnostic{file, line, col, severity, message}`, parsed from the manager's structured compiler output (Maven `[ERROR] <file>:[<line>,<col>]`); full output via `handle`. A **build-specific** shape — **not** the test `failures[]`/`Finding` schema (a compile error has no test identity / `Outcome` / column). See [ADR-0009](../adr/0009-build-compile-diagnostic-output.md). Else truncated-with-cap. |
| `install(path?, flags?)` | Install dependencies. | Project-sanctioned; runs lifecycle hooks (accepted by the guarantee). |
| `lint(path?, flags?)` | Lint. | Structured findings (eslint `--format json`, checkstyle XML) when available. |
| `run_task(name, path?)` | Run a **project-defined** task the user has **opted in**. | **Opt-in allowlist, fail-closed**: by default *no* custom task is runnable; the human allow-lists tasks in the non-agent-mutable project config. "Project-defined ≠ safe to auto-run" — real repos hold `deploy:prod`, `db:migrate:prod`, `release` (gotcha **G14**). The 4 core verbs stay always-available. **No** arbitrary extra args (gotcha **G10**). |
| `dependencies(path?, mode)` | Query-oriented dependency info. | Modes: `direct`, `why <pkg>`, `resolve <pkg>`. Normalized single schema. **Never** dumps the full transitive tree. |
| `get_log(handle, filter?)` | Drill-down into a retained run result. | Expands exactly the requested slice (one failure, a test's system-out, full stderr) **without re-running**. The anti-RTK keystone (gotcha **G5**). |
| `git_status` / `git_diff` / `git_log` / `git_show` / `git_branch` (read-only) | Structured git inspection. | **Read-only only.** Ecosystem-agnostic (one cheap adapter). Highest-volume evidence category (773 calls). **Per-verb normalized shapes parsed from `--porcelain=v2` / `--format=`** (git's machine contract, locale-stable — the D8 "parse the machine format, never scrape stdout" rule applied to git); `git_log` returns a capped commit list with full body via `git_show`; large `git_diff`/`git_show` patches sit behind a `handle` + `get_log`. `manager` is null for git. Mutating git is post-v1. **Five discrete verbs, not a `git(mode)` tool** — see ADR-0001. See decision-log D34. |

### Verb taxonomy

The catalog groups by category: a **test/build/dep** execution group, the ecosystem-agnostic
**git read-only** group (five discrete verbs, not `git(mode)` — ADR-0001), the **drill-down**
keystone `get_log`, and the **forge** group. Core verbs, git read-only, and forge `pr_*` are all
shipped (forge as of PRD-6, D62–D69, GitHub.com-only — GHES seams built, claim withheld).

```mermaid
flowchart TB
    classDef exec fill:#2d6cdf,stroke:#9ec1ff,color:#ffffff
    classDef gitv fill:#2e8b57,stroke:#a6e3c0,color:#ffffff
    classDef drill fill:#b8860b,stroke:#f0d98c,color:#1a1a1a
    classDef forge fill:#c0392b,stroke:#f3b1a8,color:#ffffff

    subgraph Exec["Execution (test / build / deps) — shipped"]
        RT[run_tests]
        BLD[build]
        INS[install]
    end

    subgraph Git["git read-only — shipped"]
        GS[git_status]
        GD[git_diff]
        GL[git_log]
        GH[git_show]
        GB[git_branch]
    end

    subgraph Drill["Drill-down keystone — shipped"]
        GLG[get_log]
    end

    subgraph Forge["Forge pr_* — shipped (GitHub.com-only)"]
        PC[pr_checks]
        PV[pr_view]
        PD[pr_diff]
    end

    RT -->|handle| GLG
    BLD -->|handle| GLG
    GD -->|handle| GLG
    GH -->|handle| GLG
    PC -->|handle| GLG
    PD -->|handle| GLG

    class RT,BLD,INS exec
    class GS,GD,GL,GH,GB gitv
    class GLG drill
    class PC,PV,PD forge
```

*Verb catalog by category: blue execution verbs, green git read-only verbs, and red forge verbs
(shipped, GitHub.com-only) all funnel large results through the amber `get_log` drill-down keystone (G5).*

## Forge inspection (PRD-6 — GitHub-first, decision-log D62)

Remote, read-only inspection of a code-hosting forge over **HTTP** (ADR-0002, ADR-0003). Deferred
from v1 by **D46**; scoped as **PRD-6** by **D62** and now **shipped** (D62–D69). The first forge
target is **GitHub** —
`github.com` shippable; **GHES seams built, claim withheld** (URL construction modeled; operational
seams unvalidated against any real instance). GitLab (SaaS + self-hosted) follows in a later PRD.
**REST-only** (spike s3 proved every verb + drill-down without GraphQL; D62). **No** generic `api`
passthrough. Governed by a separate security domain — see [`forge-security-model.md`](./forge-security-model.md).

| Tool | Purpose | Notes |
|---|---|---|
| `pr_checks(path?, ref?, repo?)` | CI check status for a PR / branch / commit. | Per-check `name` + `conclusion` (pass/fail/pending) + a `handle`. Folds **check-runs AND the Commit Statuses API**, paginating (`Link rel=next`) to exhaustion — a failed check on page 2 or a red status must flip `ok=false` (spike-s3 production obligations). The failing check's log is drilled into via `get_log(handle)` — the `gh run view --log-failed` pattern, **non-lossy, no separate log verb**. The entry point of the CI-gated loop. |
| `pr_view(path?, pr?, repo?)` | PR metadata in one call. | State, mergeable, review status, head/base, checks summary. |
| `pr_diff(path?, pr?, repo?)` | The PR's diff. | Reuses `handle` + `get_log` for large diffs (same as `git_diff`). |

- **Canonical verb prefix `pr_`** — "pull request (GitHub) / merge request (GitLab)". Forge-neutral
  logical verbs (P5); each tool's description disambiguates per forge.
- **`pr_list` is deferred** (low local evidence; YAGNI until it appears).
- Forge verbs **return a `handle`**, so `get_log` is the universal drill-down for CI logs and diffs.
- Instance (base URL) + optional token-ref are **per-instance, human-authored, non-agent-mutable**
  config — never agent input — supplied via Micronaut external config (`MICRONAUT_CONFIG_FILES` →
  `forge.instances[]`). **Token is OPTIONAL per instance** (D62): tokenless serves public repos at
  the unauthenticated rate limit; rate-limit / private-404 fail clear (structured code + hint,
  no auto-retry). See `forge-security-model.md`.
- **`repo` override** (D62) — the default mapping parses `git remote get-url origin` and matches the
  host against the allowlist; the explicit `repo` param disambiguates fork workflows (`origin` =
  fork) and SSH host aliases. SSRF-neutral: the host still comes only from the allowlist.

## Output contract

- **Common envelope** for every verb: `{ ok, verb, manager, summary, handle?, ... }`.
- **Success** → minimal payload (counts only; the report is not even read).
- **Failure (test)** → normalized `failures[]` (class, test, message, `file:line`, project-side
  stack frames), with caps that truncate noise but never signal (pillar P4).
- **Failure (build/compile)** → `diagnostics[]` of `CompileDiagnostic{file, line, col, severity,
  message}` — a build-specific shape distinct from test `failures[]` ([ADR-0009](../adr/0009-build-compile-diagnostic-output.md));
  full compiler output via `handle`.
- **Failure (operational)** → enumerated `code` (`NO_MANAGER_DETECTED`, `TOOL_NOT_INSTALLED`,
  `DEPS_NOT_INSTALLED`, `UNSUPPORTED_TEST_FRAMEWORK`, `INSTALL_FAILED`, `REPORT_NOT_PRODUCED`,
  `TIMEOUT`, `INVALID_PATH`, `AMBIGUOUS_SCOPE`, `RESOURCE_BUSY`, …) +
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
> assumptions. See [`schema-divergence-map.md`](./schema-divergence-map.md) for the divergence axes.

> **Reporter injection is per test *framework*, not per manager** (gotcha **G12**). The JVM side is
> easy — Surefire/Failsafe emit standardized JUnit XML regardless of JUnit4/5/TestNG. The Node side
> is not — jest / vitest / mocha each need a different reporter flag and emit different JSON, so the
> adapter must detect the framework from `package.json`. Go's `go test -json` is uniform.
