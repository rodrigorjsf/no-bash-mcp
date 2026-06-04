# Spike s3 — forge read-only seams (verdict)

**Throwaway, versioned.** Exercises the forge inspection seams on the **production transport family**
— `java.net.http.HttpClient` (the JDK client, same family as the chosen `micronaut-http-client-jdk`,
DESIGN.md §7) — with **zero third-party deps**. JSON is parsed by an inlined minimal reader because
JSON parsing is **not** a seam under test (production uses serde). Run:

```
GITHUB_TOKEN=$(gh auth token) java spikes/s3-forge/SpikeForge.java   # exits non-zero on any failed assert
```

Real public target: **`cli/cli`**, PR **#13412** (merged), commit **`2503dcfd`** carrying a real failed
check (`govulncheck`) whose Actions job log is retrievable — one coherent PR family.

---

## VERDICT: the forge MECHANISMS hold and the envelope shape is common — but the SECURITY guarantees are NOT proven by this spike

An adversarial review corrected three overclaims in an earlier draft of this verdict. Honest scope:
the spike proves the **by-reference + read-GET mechanisms** and the **common envelope shape**; it does
**not** prove read-only *enforcement*, the 302 leak *control*, or the GHES operational seams.

| Seam | What the spike actually proved | What it did NOT prove (test-owed) |
|---|---|---|
| **Secret by reference** (ADR-0004) | Token read from `$GITHUB_TOKEN`, value never logged/returned | **read-only ENFORCEMENT** — the token used was a **write-capable 40-char OAuth token** (`gh auth token`), not a read-only fine-grained PAT (~93 chars). Only GETs were issued, so "no write" ≠ "write rejected". Enforcement is a **GitHub-side token-config obligation**, not a code guarantee |
| **URL construction — SaaS vs GHES** | The string-builder adds `/api/v3` (REST) + `/api/graphql` (GHES) — GHES is not a host swap | Any **live GHES call**: pagination, rate-limit/`Retry-After`, GHES GraphQL schema skew, untrusted-content — **string construction only** |
| **pr_view / pr_diff** | Real PR folded (closed+merged, +241/−17, 13 files); `Accept: …diff` → unified diff | — |
| **pr_checks → common envelope shape** | `{ ok=false, verb=pr_checks, manager=null }`; `govulncheck` → `ContainerFinding(RUN)` shape (modeled as a **Map**, not the real record) | **No-false-green completeness**: the single GET does **not paginate** (a failing check on page 2 → `ok=true`) and ignores the **Commit Statuses API** (a red status → `ok=true`). Production-adapter obligations |
| **get_log drill-down** | A job-id handle parsed from the check; failed-job log (399 lines) retrieved without re-running | — (envelope-level `handle` + `get_log(filter=<check>)`, per ADR-0007 rule 6) |
| **302 redirect handling** | The spike's *manually-built* second hop carries no `Authorization` and the blob fetch returns 200 | **NOT a proven leak control** — `assertNoAuthHeader` checks a request the spike itself built without a header (tautological), and the risky auto-follow path was sidestepped by `Redirect.NEVER`. The production `@Client` redirect/header-propagation behavior is **unverified** — a recommended pattern + a test obligation, not a guarantee |

All hard assertions pass (captured run below) — but read each assertion as proving the **mechanism**, not
the security **guarantee** (which the review showed several assertions do not establish).

---

## Schema-relevant finding (feeds ADR-0007): the forge envelope is the SAME Finding graph

A CI check failure has **no single test owner**, so it folds into `ContainerFinding(RUN, container=<check
name>, rawStatus=<forge conclusion>, …)` — identical to how `run_tests` models a no-owner failure
(`SUITE`/`FILE`/`PACKAGE`). `manager` is **null** for forge verbs (a forge is not a manager — CONTEXT.md).
`ok()` stays container-aware: one failed check ⇒ `ok=false` even though there are no "tests". This is the
last piece confirming the §2 schema is common across **build/test AND forge**, so ADR-0007 can freeze it
with forge evidence, not just local-toolchain evidence.

## Security finding (feeds forge-security-model.md + DESIGN.md §5): don't forward the token on the 302

`GET /repos/{o}/{r}/actions/jobs/{id}/logs` returns **302** to a **signed blob URL on a different host**.
The signed URL is pre-authenticated; forwarding the `Authorization: Bearer` header to the blob store is
both wrong (token exposure to a third party) and commonly rejected. The spike follows the redirect
**manually** (`HttpClient.Redirect.NEVER`) and re-issues the GET to the `Location` **without** the auth
header — the blob fetch returns 200. **Caveat (adversarial review):** this only shows that the spike's
*manually-built* second hop carries no header — it does **not** prove an auto-following client would strip
it. The genuinely risky path (a client auto-follows the 302 and re-sends `Authorization` cross-host) was
sidestepped by `Redirect.NEVER`, so this is a **recommended production pattern**, not a verified control.

> **Production obligation:** the `micronaut-http-client-jdk` `@Client` for the forge must **not**
> auto-follow redirects with header propagation for the logs endpoint (or must strip `Authorization`
> cross-host). Add a test asserting the token never leaves the configured forge host. This belongs in
> the forge threat model (secret management / transport security).

## GHES self-hosted seam — URL CONSTRUCTION ONLY (operational seams NOT de-risked)

> The spike modeled only the URL strings below. The operational seams that actually differ between SaaS
> and GHES — **pagination** (`Link rel=next`), **rate-limit / `Retry-After` / secondary limits**, **GHES
> GraphQL schema-version skew**, and **untrusted-content neutralization (P9)** — were **not** exercised
> (no GHES instance to call). They are explicit production-adapter obligations + a follow-up spike against
> a real GHES instance before GHES support is claimed shippable.

| Logical call | SaaS (`api.github.com`) | GHES (`<host>`) |
|---|---|---|
| REST | `https://api.github.com/repos/cli/cli/pulls/13412` | `https://ghe.example.com/api/v3/repos/cli/cli/pulls/13412` |
| GraphQL | `https://api.github.com/graphql` | `https://ghe.example.com/api/graphql` |

The instance allowlist entry therefore needs `{ baseUrl, apiPrefix }` (or equivalent), not just a host —
the production adapter must build the `/api/v3` (REST) and `/api/graphql` (GraphQL) prefixes for GHES.
(Actual `read_api` / read-only-PAT scope enforcement is a GitHub-side token setting, not provable in code;
the spike exercises the by-reference **mechanism** + the no-write surface, which is all read GETs.)

---

## Captured run (verify-before-done)

```
[PASS] token resolved BY REFERENCE from $GITHUB_TOKEN (never inline)
  token present, length=40 (value never printed/logged/returned)
=== URL construction seam — SaaS vs GHES ===
  SaaS REST : https://api.github.com/repos/cli/cli/pulls/13412
  GHES REST : https://ghe.example.com/api/v3/repos/cli/cli/pulls/13412
  SaaS GQL  : https://api.github.com/graphql
  GHES GQL  : https://ghe.example.com/api/graphql               [PASS]×3
=== pr_view (#13412) === state=closed merged=true +241/-17 files=13   [PASS]
=== pr_diff (#13412) === Accept: diff -> unified diff, 13 files       [PASS]
=== pr_checks (ref 2503dcfd) ===
  envelope: { ok=false, verb=pr_checks, manager=null, total_checks=8, failures=1 }
    CONT [RUN] govulncheck  rawStatus=failure  handle=79422952257    [PASS]×3
=== get_log(handle=79422952257) — 302 sans token-leak ===
  [PASS] 302 redirects to a DIFFERENT (signed blob) host
  [PASS] signed-blob fetch succeeds WITHOUT forwarding the token
  [PASS] NO Authorization header on the signed-blob request (no token leak)
  job-log bytes=32611 lines=399                                       [PASS]
ALL HARD ASSERTIONS PASSED
```

*(Cosmetic: the inlined throwaway JSON reader renders integers as doubles — `241.0` — irrelevant to the
seams; production uses serde.)*
