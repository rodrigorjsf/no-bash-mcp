# Command Round-Trip Waste — Empirical Evidence from Local Agent Transcripts

Research mined local coding-agent session histories on this machine for **command
round-trip waste**: cases where a command's output lacked structured/sufficient
information, forcing the agent to run ADDITIONAL commands to recover what it needed
(file:line, the failing test, the assertion diff, the pass/fail summary, a resolved
version). The goal is to ground the design of `no-bash-mcp` — an MCP server that
replaces the Bash tool with safe, token-efficient, STRUCTURED tools.

Method: per-session, event-ordered sequence analysis (Python over the raw `.jsonl`).
Waste was detected as an adjacency-within-k=4 pair: a *producer* command (test / build /
dep / lint) followed within the next few tool calls by a *recovery* command (grep / sed /
awk / cat on a log or test artifact, or a narrowed re-run). Producer classifiers fire on
the **operative** binary after peeling wrappers (`cd && …`, `ENV=val …`, `timeout N …`,
`mise exec -- …`, `sg docker -c "…"`, `bash -lc '…'`) and reject `gh`/`git`/`echo`/`cat`/
`printf` operatives so that GitHub-issue/PR bodies and commit messages (which contain the
words "test"/"build" in prose) do not inflate counts. Tool results were pulled by
`tool_use_id` only for the handful of quoted examples (the corpus is 383 MB; bulk-reading
results was avoided).

---

## 1. Corpus scanned

| Source | Status | Notes |
|---|---|---|
| `~/.claude/projects/**/*.jsonl` | PRIMARY | 10 project dirs, 962 `.jsonl` files, 383 MB |
| `~/.codex/**` | ABSENT | no Codex history on this machine |
| `~/.cursor/**` | present but NOT useful | only `terminals/1.txt` dumps (8 lines each) + skills/config; no structured tool-call transcripts |
| `~/.config/**` | no agent histories | no codex/aider/continue/cline/copilot session stores |

**Effective signal** (sessions with ≥1 Bash tool call): **156 sessions, 7,804 Bash
commands.** (A raw `grep "name":"Bash"` returns 12,486 markers; the gap is CLAUDE.md
deny-list text, tool_result echoes, and sub-agent content — `jq`-validated `tool_use`
entries are 7,804.)

### Ecosystem census (producer commands, corpus-wide)

| Ecosystem | Hits | Where it lives |
|---|---|---|
| Node — npm/npx/package.json | 505 | med-trilha (dominant), agent-engineering-toolkit |
| Node — pnpm | 398 | med-trilha (`pnpm exec` ×406, `pnpm sandcastle:*`, `pnpm moon`) |
| node/tsc/vitest/jest/eslint | 195 | med-trilha, toolkit |
| Maven (mvn/pom) | 118 | **IBM-MQ only** |
| Go (`go test`/`build`/`run`) | 67 | med-trilha (`apps/api`), cursor-workflow-orchestrator (`cursor-bastion`) |
| Gradle | 1 | effectively absent |
| pytest / unittest | 0 | Python usage is ad-hoc scripts, never a test suite |
| yarn / cargo | 0 / 0 | absent |

**Per-project (Bash-bearing) volume:** med-trilha (Node/pnpm/Go) ≫ agent-engineering-toolkit ≈
IBM-MQ (Maven/docs guide) ≈ cursor-workflow-orchestrator (Go).

**Census verdict for v1 scope (JVM + Node):** evidence is real but *concentrated*, not
spread. The JVM half is **entirely IBM-MQ/Maven** (Gradle has essentially zero evidence —
see §4). The Node half is real and rich, centered on med-trilha (pnpm + `moon` task runner)
and a Go module under the same repo. This tells us which projects to mine deeply and warns
that Gradle support in v1 is being designed on near-zero local evidence.

---

## 2. Top round-trip-waste patterns (ranked)

Counts are hardened adjacency pairs (k=4) after operative-binary reject-list filtering
(wrappers `cd &&`, `ENV=`, `timeout`, `mise exec --`, `rtk`, `sg docker -c`, `bash -lc`, and
`VAR=$(gh …)` substitutions are peeled, and gh/git operatives rejected, so issue/PR-body and
commit-message prose does not inflate counts). For the top three patterns I report **two
numbers — `raw` (the analyzer's adjacency count) and `genuine`** (a strict re-filter that
requires the producer to contain a real `mvn|go|pnpm|moon|tsc` test/build verb AND the
recovery to target a log/test artifact). The gap is residual `cd …\n gh issue comment`
multi-statement false positives; the `genuine` figure is the defensible one. Each example is
a real, verbatim (truncated) command sequence from the corpus; `~` shortens the home path.

### P1 — Test run → re-grep the test output for the failure  ·  **103 raw / ~71 genuine pairs**  (IBM-MQ 47, toolkit 14, med-trilha 8, cursor-wf 2)
The single most-evidenced *test-specific* waste. The agent runs the suite, the
piped/condensed stdout does not reliably surface what it needs, so it re-reads a saved log.
(Reconciliation note: an early narrow hand-scan of IBM-MQ found only 4 mvn-test→parse pairs
because it filtered to short commands recovering from `surefire`/`.txt` only; the dominant
real recovery form is re-reading the **background-task output file**
`/tmp/claude-1000/.../tasks/<id>.output` from long `source …env.sh && mvn … verify`
producers — 47 of the 70 IBM-MQ raw pairs are this genuine pattern.)

```
A: go test -tags integration -count=1 -v ./internal/ingest -run 'TestIngestionGate' 2>&1 | tail -40
B: /usr/bin/cat ~/.local/share/rtk/tee/1778369279_go_test.log | grep -E 'output|Layer|Page|Score' | grep -i 'off_topic\|clean\|classifier'
```
```
A: mvn -B -f ~/IBM-MQ/ibmmq-jms-guide/pom.xml clean test 2>&1 | tail -15
B: grep -E "Tests run:|BUILD SUCCESS|BUILD FAILURE|ERROR" /tmp/claude-1000/.../tasks/blr79cbp2.output
```
**Missing-info diagnosis:** the test command's visible output was truncated (`| tail -15`,
`| tail -40`) precisely where the signal lives — the Maven `Tests run: N, Failures: F`
summary line, or the Go intermediate per-layer trace. The agent had to recover it from a
side-channel log file. Notably, even RTK's *structured* summary (which DID show file:line
and `expected vs actual`) ended with `[full output: …/tee/<ts>_go_test.log]` and the agent
STILL `cat`+`grep`-ed that file — proving the summary truncated the next-needed signal.
**Prevented by:** `run_tests` returning a structured object whose `failures[]` carry
`{test_id, file, line, assertion: {expected, actual}, project_stack_frames[]}` AND a
`summary {total, passed, failed, skipped}` that is never truncated. The verbatim per-test
captured stdout/stderr slice must travel WITH the failure so no second read is needed.

### P7 — Producer piped to `tail`/`head` / redirected to a log (verbose-output coping)  ·  **189 raw / 159 genuine**  (the most frequent overall)
Not a recovery pair per se, but the *cause* of most P1/P8 recoveries: the agent pre-empts a
firehose by truncating to the last N lines, which routinely clips the signal and triggers a
follow-up.

```
A: pnpm sandcastle:self-test 2>&1 | tail -40
A: pnpm sandcastle:build 2>&1 | tail -50
A: mvn -q -DskipITs test 2>&1 | tail -35
```
**Missing-info diagnosis:** `tail -N` is a blind guess at where the relevant lines are.
When the failure is in the middle of the output (compiler errors, the first failing assert),
`tail` misses it entirely. **Prevented by:** structured tools never emit a raw firehose, so
the agent never reaches for `| tail`. The return is already noise-trimmed: build/test
diagnostics are extracted and ranked, dependency-download chatter and progress bars dropped.

### P8 — Build/compile run → grep for the error file:line  ·  **73 raw / 62 genuine**  (IBM-MQ, toolkit, med-trilha)
```
A: cd ~/Workspace/med-trilha-deps/apps/api && mise exec -- go build ./... 2>&1 | tail -20
B: find apps/api -name "*generate*" -o -name "oapi-codegen*.yaml" … ; <inspect generated file>
```
```
A: sg docker -c "pnpm sandcastle:build" 2>&1 | tail -30
B: grep -E "DONE|ERROR|FAIL|success|fail|naming|exporting|writing image" ~/.claude/projects/.../<session>.jsonl
```
**Missing-info diagnosis:** the build output, tail-clipped, did not localize the error to a
`file:line` the agent could open, so it had to hunt for the offending/generated file.
**Prevented by:** `build` returning `errors[] {file, line, column, message, code}` plus the
project's own stack frames, with toolchain progress noise dropped.

### P5 — Project/layout discovery immediately before a producer  ·  **24 pairs**  (med-trilha 16, toolkit 4, IBM-MQ 3)
```
A: find sandcastle-ref/sandcastle -path "*/templates/*" -name "*.md" … ; ls …/.sandcastle/
B: pnpm sandcastle:self-test 2>&1 | tail -40
```
```
A: find apps/api/internal/safety -maxdepth 2 -type d ; grep -rn "StrikeRecorder interface" …
B: cd apps/api && go test -tags integration -run xxx ./internal/ingest/... 2>&1 | tail -10
```
**Missing-info diagnosis:** the agent did not know the module layout / which task or test
target existed, so it explored the tree before it could run anything.
**Prevented by:** `describe_project` returning modules, the package manager, the available
verbs/tasks (including custom scripts like `sandcastle:self-test`, `moon run <proj>:test`),
and per-module source roots — in one call, replacing the `find`/`ls`/`grep` recon.

### P9 — Lint run → re-read the lint output from a side-channel  ·  **22 pairs**  (med-trilha 22)
```
A: (cd …/med-trilha-lint-cache && env -i … pnpm exec moon run api:lint --log debug 2>&1) | grep -iE "GOLANGCI…"
B: cat /tmp/claude-1000/.../tasks/bjr8o6c51.output 2>&1 | tail -40
```
**Missing-info diagnosis:** lint findings were buried in `moon`/golangci-lint noise; the
visible (piped) output did not carry the finding location, so the agent re-read the task
output file. **Prevented by:** `lint` returning `findings[] {file, line, rule, severity,
message}`, noise stripped.

### P6 — Narrowing/changed re-run of a test or build  ·  **17 pairs**  (IBM-MQ 11, med-trilha 5)
```
A: go test -tags integration -run 'TestIngestionGate' 2>&1 | tail -40
B: go test -tags integration -run 'TestIngestionGate_CleanPDF' 2>&1 | tail -25
```
**Missing-info diagnosis:** the broad run named the failing test but not enough context to
fix it, so the agent re-ran a single narrowed test for a cleaner view — a second full
toolchain spin-up. **Prevented by:** `run_tests` with an optional `select` (test id / file)
AND, more importantly, a first run rich enough (full per-failure detail) that the narrowing
re-run is unnecessary. This is the core justification for test-target selection living
inside the tool.

### P4 — Dependency inspection → re-query for a specific package/version  ·  **7 pairs**  (med-trilha 5)
```
A: mise exec -- pnpm list vite typescript --filter @medtrilha/web …
B: mise exec -- npm view @vitejs/plugin-react versions --json | python3 -c "…max compatible…"
```
```
A: npm view jsdom versions --json | python3 -c "…"
B: npm view jsdom@29.1.1 dependencies.html-encoding-sniffer ; npm view jsdom@28.1.0 dependencies…
```
**Missing-info diagnosis:** `pnpm list` / `npm view` answered one slice of the question;
resolving "which version is compatible / what does it depend on" took 2–4 chained queries,
several post-processed through inline `python3 -c` JSON parsing.
**Prevented by:** `dependencies` with `why <pkg>` and `resolve <pkg>` verbs returning the
resolved version, the requesting edges, and transitive deps as structured data — collapsing
the `pnpm list → npm view → npm view <ver> dependencies.<x>` chain into one call.

### P2 — Broad test → narrowed re-run via explicit selector  ·  **3 pairs** (low; see §4)
Genuine but rare in this corpus (the narrowing usually shows up as P6 `-run X` re-runs).
Same prevention as P6.

### P-RTK — Same command re-run as `/usr/bin/<bin>` to bypass RTK condensation  ·  **52 adjacent pairs**
The user's CLAUDE.md documents this as the literal round-trip-waste signature: run `X`, see
mangled output, re-run `/usr/bin/X`. Genuine adjacent same-subcommand pairs: `gh pr` (18),
`gh issue` (9), `grep -n` (3), `git status` (3), `git diff`/`rev-parse`/`docker logs` (2 each).
```
A: gh issue view 127 --json number,title,state,labels,body,comments …
B: /usr/bin/gh issue view 127 --json number,title,state,labels,body,comments …   (identical, un-RTK'd)
```
**Note on the headline:** a naive count ("any `/usr/bin/X` with an earlier plain `X`") gives
**1,108** and is misleading — see §4. The genuine adjacent-bypass count is **52**, and most
of it is `gh`/`git` workflow, not test/build. The lesson for `no-bash-mcp` is in §4.

---

## 3. NEW candidate tools / verbs (recurring in evidence, NOT in the current surface)

Ranked by evidence strength.

1. **`describe_project` must enumerate CUSTOM tasks/scripts, not just standard verbs.**
   The dominant producers here are NOT `npm test` / `mvn test` — they are project-defined
   scripts and task-runner targets: `pnpm sandcastle:self-test` / `sandcastle:build` /
   `sandcastle:run` (≈25 hits), `pnpm exec moon run <project>:test|build|lint|check` (the
   `moon` monorepo task runner, dozens of hits), `pnpm sandcastle:*`. A `describe_project`
   that only knows `run_tests`/`build`/`lint` would miss the actual commands the agent runs.
   **Verb to add:** `describe_project` returns a `tasks[]`/`scripts[]` list (name + which
   tool runs it), and `run_tests`/`build`/`lint` accept a `task` parameter to invoke a named
   custom target. *Evidence: med-trilha pnpm `sandcastle:*` + `moon run …` are the top
   producers in the whole corpus.*

2. **Git/GitHub inspection (read-only) is the single largest command category.**
   `/usr/bin/git` (384 bare) and `/usr/bin/gh` (389 bare) dominate. The *waste* subset is
   small (status→diff→log reconstruction, `gh run view --log-failed` after a failed PR
   check), but the *volume* is enormous and the agent constantly routes git/gh through
   `/usr/bin/` to dodge RTK. A read-only `git_status` / `gh_checks` structured tool
   (changed files, ahead/behind, failing check + its log tail) would absorb a large fraction
   of total Bash traffic. **High volume, moderate waste-density.** Candidate: `vcs_status`,
   `ci_checks`. *Evidence: 773 bare `/usr/bin/{git,gh}` calls; `gh run view 25614853449
   --log-failed | tail-40` recovery after `gh pr create`.*

3. **Read a specific test/build log by handle.** The recovery half of P1/P8/P9 is almost
   always "cat a known log file and grep it" (`~/.local/share/rtk/tee/<ts>_go_test.log`,
   `/tmp/claude-1000/.../tasks/<id>.output`). If `run_tests`/`build` return a `log_handle`
   the agent can request *expanded* (full verbatim) for a specific failing test, the
   cat→grep recovery disappears. **Verb:** `get_log(handle, filter=test_id)`.

4. **Docker / container preflight.** `sg docker -c "pnpm sandcastle:build"` failed with
   `permission denied … docker.sock … Is Docker running?` — a build that round-trips into
   "why did the container step fail." A `build`/`run_tests` that detects and structurally
   reports "docker daemon unreachable" (vs a generic non-zero exit buried in a tail) avoids
   the diagnostic round-trip. *Evidence: ≥7 `/usr/bin/docker` calls + the sandcastle build
   failure above.*

5. **Generated-code / codegen awareness.** `go build ./...` failed and the agent went
   hunting for `oapi-codegen*.yaml` and a `*.gen.go` file — a build error whose true cause
   was stale generated code. Niche, but a `build` that surfaces "error in generated file
   `server.gen.go`" with the codegen source pointer would help. *Low frequency.*

---

## 4. Surprises / counter-evidence

1. **The biggest "waste" headline is self-inflicted by RTK, not inherent to Bash.**
   The naive `/usr/bin/` re-run count is 1,108, but it collapses to **52** genuine adjacent
   same-subcommand bypasses once you account for the fact that this user's CLAUDE.md *forces*
   git/gh to `/usr/bin/` by default (RTK deny-list). So `/usr/bin/<bin>` is frequently the
   agent's PRIMARY call form, not a re-run after a mangled one. **Design implication (the
   most important one): RTK is a cautionary tale for `no-bash-mcp`.** RTK condenses output
   to save tokens and thereby created the exact round-trip waste this project wants to kill —
   agents re-run commands via `/usr/bin/` or `cat` the tee-log to recover signal RTK dropped.
   We even caught RTK's *structured* go-test summary truncating the next-needed signal
   (`[full output: …/tee/…log]`) and forcing a `cat`+`grep`. **An MCP that replaces Bash MUST
   NOT reintroduce lossy summarization. "Truncate noise, never truncate signal" is not a
   nice-to-have — it is the difference between this tool and RTK.**

2. **Gradle has essentially zero local evidence (1 hit).** v1 commits to Maven + Gradle,
   but every JVM data point here is Maven, all inside one docs-guide repo (IBM-MQ). Gradle
   support is being designed blind on this machine. (Not a reason to drop it — just flag that
   the design can't be validated against local transcripts.)

3. **pytest is the canonical "test → parse XML" pattern in the brief, but it has zero
   evidence here.** No `pytest`/`unittest` runs at all; Python appears only as inline
   `python3 -c` JSON post-processors (e.g. parsing `npm view --json`). The classic
   surefire/junit-XML re-grep the brief anticipated shows up in the **Maven** and **Go**
   forms, not Python — and crucially as re-grepping a *tee/task log*, not the XML report
   directly (because RTK/background-task logs sit between the agent and the raw report).

4. **Explicit narrowed re-runs (`-Dtest=`, `--tests`) are rarer than expected (P2=3).**
   The real narrowing is Go's `-run 'TestName'` → `-run 'TestName_SubCase'` (P6). Test-target
   selection IS justified by the evidence, but the evidence is Go `-run`, not Maven/JUnit
   selectors — worth designing the `select` parameter around test-id regex, not just class.

5. **`pnpm exec` (406) is the overwhelming top "producer" token, but most of it is plumbing
   (`pnpm exec moon run …`), not a direct test/build.** The operative tool is almost always
   the `moon` task runner wrapped by `mise exec -- pnpm exec`. Any tool that shells out must
   peel these wrappers (mise + pnpm + moon) to find the real verb — the same wrapper-peeling
   the analyzer needed. If `no-bash-mcp` hard-codes `npm test`/`pnpm test`, it will miss the
   real-world invocation shape entirely.

6. **Cursor and Codex contribute no usable evidence.** Codex is absent; Cursor stores only
   8-line terminal text dumps, not structured transcripts. All evidence is Claude Code.

7. **Counting-method caveat (validity).** Producer prose inside orchestration sessions is a
   real confound: `mvn verify` / `pnpm … test` appear verbatim inside `gh issue create`
   bodies and `git commit` messages. A naive substring matcher classed 218 commands as
   "test"; 75 of those (34%) are gh/git/rtk prose, not real runs. The reported counts peel
   wrappers and reject gh/git operatives; the top-3 patterns additionally carry a stricter
   `genuine` figure (real verb + log/artifact recovery). Net effect: ranking is stable
   (P7 > P1 > P8 > the rest); absolute counts are ~10–30% below the raw adjacency numbers.
   Any downstream use of these numbers should cite the `genuine` figures.
