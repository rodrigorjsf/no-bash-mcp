# Architecture Survey & Recommendation — no-bash-mcp

> **Status:** before-coding research → **architecture recommendation** (verified June 2026). This is
> the *survey + rationale* that feeds `DESIGN.md`. It is **not** the binding architecture document —
> `DESIGN.md` is written after this recommendation is confirmed (see
> [`CLAUDE.md`](../../CLAUDE.md): "architecture from research, not invention").
>
> **Method.** Per the before-coding plan, the choice is made **from a survey** of (a) real,
> well-regarded **JVM MCP server** layouts and (b) current Java application architectures (Hexagonal,
> Clean/Onion, classic layered, Vertical-Slice, Micronaut-idiomatic), each fetched from primary
> sources (official Micronaut/MCP docs, real GitHub repos, canonical architecture references). Every
> claim carries a link in [References](#references). The leading hypothesis from grilling was
> Hexagonal; it was **not** adopted by default — it was tested against the evidence, and against the
> rejected alternatives below.

## Decision (recommendation)

> **Macro-architecture: Hexagonal / Ports-and-Adapters (lightweight variant).**
> **Inside the application: feature-organized by verb (vertical-slice flavor) over a bounded shared
> kernel.** Single Maven module, with **ArchUnit** enforcing the dependency rule.
>
> - **Inbound driver adapter** = Micronaut MCP `@Tool` methods on `@Singleton` beans (no inbound port
>   interface — the `@Tool` bean *is* the adapter). Transport (STDIO) is **configuration, not code**.
> - **Outbound driven ports** = two narrow Java interfaces in the pure domain — `CommandExecutorPort`
>   (ecosystem/process execution) and `ForgePort` (HTTP forge inspection). Concrete adapters are
>   `@Singleton` beans implementing them.
> - **Domain** = pure Java records (the [envelope], normalized test-result schema, [operational-error]
>   codes, [signal]/[noise]) annotated `@Serdeable @Introspected` (+ `@JsonSchema` on MCP tool I/O) —
>   reflection-free, GraalVM-native-ready.
> - **Harness adapter** (bootstrap permission-config writer) is a third adapter family, but belongs to
>   the **separate bootstrap deliverable**, not the running server's request path.
>
> This is the smallest structure that (1) maps 1:1 onto how the Micronaut MCP module *wants* a server
> built, (2) gives the two real test seams (process exec, forge HTTP) clean in-memory mockability, and
> (3) keeps the GraalVM-native story trivial. It rejects heavier (Onion/Clean) and looser (plain
> layered, pure VSA) options for concrete, evidenced reasons (see [Rejected alternatives](#rejected-alternatives)).

[envelope]: ../../CONTEXT.md
[operational-error]: ../../CONTEXT.md
[signal]: ../../CONTEXT.md
[noise]: ../../CONTEXT.md

---

## 1. What real JVM MCP servers look like (the discriminating evidence)

The discriminating question is not "which pattern is prettiest" but **what package structure does a
Micronaut MCP server naturally fall into**. Surveyed prior art:

| Project | Shape | Signal for us |
|---|---|---|
| **micronaut-projects/micronaut-mcp** (official) [[1]](#r1) | modules: `micronaut-mcp-annotations` (`@Tool`, `@ToolArg`, …), `micronaut-mcp-server-java-sdk` (wires annotated beans into the MCP Java SDK `McpSyncServer`) | the annotation surface + transport-as-config is the contract we build on |
| **Official STDIO guides** (diskspace, weather) [[2]](#r2) [[3]](#r3) | flat `Application` + `@Singleton Tools` + injected service + `@Serdeable @JsonSchema` records + `logback.xml`→stderr | the *minimal* shape; `Application.banner(false)` and stderr logging are **verified, non-negotiable** |
| **glaforge/mn-mcp-server** (real, non-toy) [[4]](#r4) | explicit **service/tool split**: `MoonPhasesService` (`@Singleton`, business logic) vs `MoonPhasesMcpServer` (`@Singleton @Tool` wrapper that injects the service) | the real-world pattern grows a tool-vs-service split — exactly the hexagonal inbound-adapter→service shape |
| **modelcontextprotocol/java-sdk** [[5]](#r5) | low-level: `McpServer.sync(StdioServerTransportProvider)`; hand-coded schemas unless a framework annotation layer sits on top | confirms Micronaut's annotation layer is the value-add we want; bare SDK = boilerplate |
| **Spring AI MCP** [[6]](#r6), **mcp-declarative-java-sdk** [[7]](#r7) | `@McpTool` on beans; tools grouped by capability | annotation-over-SDK + capability-grouping is an **independently recurring** pattern across ecosystems |

**Finding:** every framework path converges on *thin annotated tool beans that delegate to services/
adapters, with transport selected by configuration*. That is hexagonal in effect — and the official
Micronaut diskspace guide already demonstrates the lightweight split (`DiskUtils` pure domain +
`MyTools` `@Singleton @Tool` adapter) [[8]](#r8).

---

## 2. Pattern comparison (why hexagonal, not the others)

All four candidates share the dependency-inversion DNA (dependencies point inward). They differ in
**how many rings** sit between domain and adapters and **what ceremony** each ring costs. Estimated
for this project's shape (~15 verbs + 2 server-side outbound families) [[9]](#r9) [[10]](#r10) [[11]](#r11):

| Dimension | Classic Layered | **Hexagonal** ✅ | Onion / Clean | Pure Vertical-Slice |
|---|---|---|---|---|
| Formal layers / rings | 2–4 informal | **2 (core + adapters)** | 4 rings | slice-local + kernel |
| Mandatory interfaces | 0 | **1 per outbound family** | 1 per use-case + 1 per gateway | 0 |
| Classes for 15 verbs | ~30–40 | **~40–60** | ~90–120 | ~30–40 |
| Dependency rule | drifts | **enforced (inward imports)** | strictly enforced | per-slice discipline |
| Forge adapter test seam | none (mock HttpClient internals) | **port interface → in-memory stub** | gateway interface | slice-local |
| Verdict for us | **under-engineered** | **correctly sized** | **over-engineered** | **kernel > slices → hybrid only** |

- **Layered is under-engineered:** no formal seam for the forge/process adapters → the forge adapter
  can't be tested without a real GitHub connection.
- **Onion/Clean is over-engineered:** there are **no enterprise entities** to protect and **no
  orchestration logic** — verbs are pass-through (validate → call adapter → normalize → return). The
  Use-Case/Interactor ring + mandatory DTO-at-every-boundary mapping would **triple** the class count
  for zero semantic gain [[10]](#r10) [[12]](#r12).
- **Pure Vertical-Slice doesn't fit** because the **shared concerns are larger than the per-verb
  code**: the [envelope], [run-cache], process-exec, concurrency/`RESOURCE_BUSY`, and the normalized
  schema are cross-cutting. VSA's own literature says "thin slices over a fat shared core" is *not*
  pure VSA — use the **hybrid** [[13]](#r13) [[14]](#r14) [[15]](#r15).
- **Hexagonal is correctly sized:** the deciding variable is **adapter count, not project size**
  (Cockburn's "small project → cure worse than disease" caveat is about size; multiple driven-side
  technologies is the *canonical* hexagonal use case) [[9]](#r9). We have exactly that: process exec +
  forge HTTP, each a distinct technology with independent test/mock needs.

**Synthesis (the load-bearing nuance):** Hexagonal and Vertical-Slice are **not mutually exclusive**
[[16]](#r16). Use hexagonal for the **macro** boundary (pure core, ports, adapters) and organize the
**application layer by verb** (feature slices) over a bounded shared kernel. This is the
independently-recommended hybrid — and it directly answers the handoff's hexagonal hypothesis with
the verb-catalog reality.

---

## 3. The chosen architecture in detail

### 3.1 Package layout (single Maven module)

```
src/main/java/<base>/
  Application.java              # Micronaut.build(args).banner(false).start()

  domain/                       # PURE — no framework beyond annotations; reflection-free
    envelope/                   # Envelope, Handle
    result/                     # normalized TestResult schema, failures[], Signal/Noise split
    error/                      # OperationalError + codes (NO_MANAGER_DETECTED, RESOURCE_BUSY, …)
    port/out/                   # CommandExecutorPort, ForgePort   ← plain Java interfaces, 0 annotations

  application/                  # @Singleton use-cases; constructor injection; verb-sliced
    verb/
      tests/ build/ lint/ install/ deps/ project/ log/ git/ forge/   # one slice per verb (group)
    policy/                     # flag allowlist, run_task allowlist, instance allowlist (P8/P3)
    dispatch/                   # shared verb orchestration (validate → port → normalize → envelope)

  adapter/
    in/mcp/                     # @Singleton @Tool beans (BuildTools, GitTools, ForgeTools, …);
                                #   request→verb mapping, envelope serialization, P9 neutralization
    out/ecosystem/{maven,node,go}/   # ProcessBuilder invocation, reporter injection, report parsing
                                #        → implements CommandExecutorPort
    out/forge/{github}/         # HTTP REST/GraphQL client, response normalization → implements ForgePort
    out/harness/{claudecode}/   # bootstrap permission-config writer (SEPARATE deliverable)

  infra/                        # process exec (tree-kill + timeout), HTTP client factory (CA/proxy/auth),
                                #   run-cache, secret resolution, concurrency/RESOURCE_BUSY guard
  config/                       # @ConfigurationProperties records (non-sensitive knobs only)

src/main/resources/
  application.yml               # micronaut.mcp.server.transport=STDIO ; micronaut.banner.enabled=false
  logback.xml                   # ConsoleAppender → System.err  (stdout is JSON-RPC only)
  META-INF/native-image/<group>/<artifact>/   # reachability metadata (mostly auto-generated)

src/test/java/<base>/           # MIRRORS src/main (tests-mirror-src convention)
src/test/resources/fixtures/{maven,jest,go,forge}/   # golden report + HTTP response files
```

> The **shared kernel** is `domain/` (pure types) + `infra/` (shared infrastructure) — there is no
> separate `common/` package; that keeps it KISS while still bounding the cross-cutting concerns. The
> **two outbound ports** live in `domain/port/out`; there is deliberately **no inbound port
> interface** (the `@Tool` bean is the driver adapter) — adding `RunTestsUseCase`-style interfaces per
> verb would be pure ceremony for 1:1 verb→use-case mappings [[8]](#r8) [[9]](#r9).

### 3.2 How it maps onto the verified Micronaut MCP mechanics

| Concern | Mechanism (verified) | Source |
|---|---|---|
| Dependency / parent | `io.micronaut.platform:micronaut-platform:5.0.2` manages `micronaut-mcp` 1.0.0 + `mcp-core` 1.1.2 | [[17]](#r17) [[18]](#r18) |
| Server artifact | one dep: `io.micronaut.mcp:micronaut-mcp-server-java-sdk` (no version) | [[18]](#r18) |
| Transport | `micronaut.mcp.server.transport=STDIO` (config — tool beans import no transport class) | [[1]](#r1) |
| Tool declaration | `@Tool` (+ `@Tool.ToolAnnotations(readOnlyHint=…)`) on `@Singleton` bean methods; `@ToolArg` renames params | [[1]](#r1) [[19]](#r19) |
| Input/output schema | Java record + `@JsonSchema` + `@Introspected` → compile-time schema (separate `micronaut-json-schema` module) | [[1]](#r1) [[20]](#r20) |
| Serialization (native) | `@Serdeable` (Micronaut Serialization) → compile-time serializers, zero runtime reflection | [[21]](#r21) |
| Escape hatch | `McpStatelessServerFeatures.SyncToolSpecification` bean in a `@Factory` for full manual control | [[1]](#r1) |
| stdout hygiene | `banner(false)` + `logback.xml`→`System.err` (developer obligation, **not** automatic) | [[1]](#r1) [[2]](#r2) |
| Scaffolding | `mn create-app --features=mcp-stdio,json-schema,validation` | [[2]](#r2) |

The inbound `@Tool` beans live in `adapter/in/mcp/`, grouped by verb family (`BuildTools`,
`ProjectTools`, `GitTools`, `ForgeTools`) — matching the real-world capability-grouping pattern — and
each delegates to an `application/verb/*` use-case, which calls a `domain/port/out` interface,
satisfied at startup by an `adapter/out/*` bean via Micronaut compile-time DI [[1]](#r1) [[8]](#r8) [[22]](#r22).

### 3.3 GraalVM-native constraints the structure must honor (verified)

- **Domain records carry `@Serdeable @Introspected`** (+ `@JsonSchema` on MCP I/O). Records are
  first-class for `@Serdeable`; use **constructor injection everywhere**, never field injection. [[21]](#r21)
- **Compile-time AOP only** (`@Around`/introduction). Micronaut 5's runtime proxies (Byte Buddy / JDK
  dynamic proxies) are **native-image-hostile** — never in production beans. AOP targets must not be
  `final`. [[22]](#r22)
- **serde 3.0.0 defaults shape domain records** (BOM-managed at 3.0.0; **verified** against the
  release notes): polymorphic hierarchies need a **default impl** via `@JsonTypeInfo(defaultImpl=…)`
  (`subtypes-require-default-impl` defaults true); model **optional** wire fields as boxed
  `Integer`/`Boolean` + `@Nullable`, never bare primitives (`fail-on-null-for-primitives` defaults
  true). Detail in [`testing-stack-research.md`](./testing-stack-research.md) §8. [[21]](#r21)
- For unavoidable third-party reflection (a `ProcessBuilder`/HTTP path), prefer `@ReflectiveAccess` /
  `@ReflectionConfig` over hand-edited JSON; capture with the tracing agent (`mvn -Pagent test`). [[23]](#r23)
- Detail + the WSL/build runbook: [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md).

### 3.4 Enforcing the dependency rule

Hexagonal's only failure mode is "interfaces everywhere without enforcement," degrading into layered.
Enforce that **`domain/` never imports `adapter/`** and **`verb.*` never imports `verb.*`** with an
**ArchUnit** test. **Verified:** pin `com.tngtech.archunit:archunit:1.4.2` (the **core** artifact,
test scope — **not** `archunit-junit5`, which pulls JUnit Platform 1.x and is incompatible with JUnit
6); ArchUnit 1.4.2 supports Java 25, and rules run as plain Jupiter `@Test` methods. A Maven
multi-module split (`core` + `adapters`) is the heavier alternative — see open sub-decisions. The
concrete rule snippets are in [`testing-stack-research.md`](./testing-stack-research.md) §7.
[[9]](#r9) [[24]](#r24)

---

## Rejected alternatives

| Alternative | Why rejected (evidence) |
|---|---|
| **Classic layered** | No formal seam for the forge/process adapters → forge untestable without real GitHub; coupling drifts silently. Under-engineered for 2 distinct outbound technologies. [[9]](#r9) [[25]](#r25) |
| **Onion / Clean (4 rings)** | No enterprise entities, no orchestration → the Use-Case/Interactor ring + DTO-at-every-boundary mapping ~triples class count for pass-through verbs. Over-engineered. [[10]](#r10) [[12]](#r12) |
| **Pure Vertical-Slice** | Shared kernel (envelope, run-cache, process-exec, concurrency, schema) is larger than per-verb code → VSA's own guidance says use the hybrid, not pure slices. [[13]](#r13) [[14]](#r14) |
| **Full-ceremony hexagonal** (use-case interface per verb) | 1:1 verb→use-case mapping → inbound port interfaces add files and injection points with no independent mockability. [[8]](#r8) [[9]](#r9) |
| **Bare MCP Java SDK** (no Micronaut layer) | Hand-coded JSON schemas + manual `SyncToolSpecification` wiring = boilerplate the annotation layer removes; loses compile-time/native ergonomics. [[5]](#r5) |
| **Spring AI MCP** | Spring AI 2.0 still **pre-GA** (June 2026); runtime-reflection posture needs extra GraalVM hints vs Micronaut's compile-time/native-first model. [[6]](#r6) |

---

## Sub-decisions — resolved + remaining

The macro-architecture and the two consequential sub-decisions are **decided** and recorded in
[ADR-0006](../adr/0006-application-architecture.md). ✅ = decided; ◻ = a `DESIGN.md` detail.

1. ✅ **Module structure → single module + ArchUnit** (chosen over multi-module for KISS at ~15
   verbs; ArchUnit 1.4.2 core enforces `domain !-> adapter` and `verb.* !-> verb.*`). Revisit only if
   the core grows. [[9]](#r9) [[22]](#r22) [[24]](#r24)
2. ✅ **Forge HTTP client → `micronaut-http-client-jdk`** (Netty-free; see below for the full
   trade-off). **Pick a Netty-free client over the Netty-backed default.**
   **Verified fact (primary sources):** a minimal `micronaut-mcp-server-java-sdk:1.0.0` STDIO server
   is **Netty-free** — `mcp-core:1.1.2` has no Netty, the module pulls `micronaut-http-server` only
   `compileOnly`, and Micronaut core is Netty-free; Netty enters **only** via
   `micronaut-http-server-netty` or the default Netty-backed `micronaut-http-client` [[27]](#r27)
   [[28]](#r28). So the forge client choice **decides whether Netty exists at all**:
   - **`micronaut-http-client-jdk`** (recommended) — Micronaut `@Client` ergonomics (compile-time,
     `@Serdeable`-friendly) wrapping the JDK `java.net.http.HttpClient`; **Netty-free** → no
     `-H:+SharedArenaSupport`, smaller native surface.
   - **raw `java.net.http.HttpClient`** — zero new deps, also Netty-free; more manual.
   - **default `micronaut-http-client`** (avoid) — `DefaultHttpClient` is Netty-backed → pulls Netty
     and the Arena flag for no benefit on a server with no inbound HTTP.

   **ADR-0003 does not constrain this:** its *"native Java HTTP calls"* contrasts in-process HTTP with
   shelling out / a third-party MCP — it does **not** mandate a specific client library. All three
   options satisfy ADR-0003; this is a pure native-surface call. Pin the exact client in `DESIGN.md`
   (recommendation: `micronaut-http-client-jdk`). (See
   [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md) §C.4.)
3. ◻ **`@Tool` bean granularity** (a `DESIGN.md` detail) — one bean per verb-family (`BuildTools`,
   `GitTools`) vs one per verb. Family-grouping matches the real-world capability pattern and keeps
   the inbound surface small; per-verb maximizes slice isolation. Recommendation: **per family**
   (≈4–6 beans). [[4]](#r4) [[16]](#r16)

---

## References

### Real JVM MCP servers & Micronaut MCP mechanics
<a id="r1"></a>[1] micronaut-projects/micronaut-mcp + Official Guide — https://github.com/micronaut-projects/micronaut-mcp · https://micronaut-projects.github.io/micronaut-mcp/latest/guide/
<a id="r2"></a>[2] Micronaut Guide — Disk Space MCP Server (STDIO, Maven/Java) — https://guides.micronaut.io/latest/micronaut-mcp-diskspace-stdio-maven-java.html
<a id="r3"></a>[3] Micronaut Guide — Weather MCP Server (STDIO) — https://guides.micronaut.io/latest/micronaut-mcp-weather-stdio-maven-java.html
<a id="r4"></a>[4] glaforge/mn-mcp-server — real Micronaut MCP server (service/tool split) — https://github.com/glaforge/mn-mcp-server
<a id="r5"></a>[5] modelcontextprotocol/java-sdk + server docs — https://github.com/modelcontextprotocol/java-sdk · https://java.sdk.modelcontextprotocol.io/latest/server/
<a id="r6"></a>[6] Spring AI — MCP Server Boot Starter — https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html
<a id="r7"></a>[7] codeboyzhou/mcp-declarative-java-sdk — https://github.com/codeboyzhou/mcp-declarative-java-sdk
<a id="r8"></a>[8] Micronaut Guide — Disk Space MCP Server (HTTP, the lightweight hexagonal split) — https://guides.micronaut.io/latest/micronaut-mcp-diskspace-http-maven-java.html
<a id="r19"></a>[19] glaforge — Creating an MCP server with Micronaut (`@Tool`/`@ToolArg`/`@JsonSchema` example) — https://glaforge.dev/posts/2025/09/16/creating-a-streamable-http-mcp-server-with-micronaut/
<a id="r20"></a>[20] Micronaut JSON Schema — guide (`@JsonSchema` module) — https://micronaut-projects.github.io/micronaut-json-schema/latest/guide/

### Architecture patterns (canonical + 2025–2026 analysis)
<a id="r9"></a>[9] Alistair Cockburn — Hexagonal (Ports & Adapters), canonical — https://jmgarridopaz.github.io/content/hexagonalarchitecture.html
<a id="r10"></a>[10] Robert C. Martin — The Clean Architecture — https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html
<a id="r11"></a>[11] kamilmazurek — hexagonal-architecture-template (real Java repo) — https://github.com/kamilmazurek/hexagonal-architecture-template
<a id="r12"></a>[12] CCD Akademie — Clean vs Onion vs Hexagonal (over-structuring critique) — https://ccd-akademie.de/en/clean-architecture-vs-onion-architecture-vs-hexagonal-architecture/
<a id="r25"></a>[25] Edana — Layered vs Hexagonal (Feb 2026; adapter count drives the choice) — https://edana.ch/en/2026/02/16/layered-architecture-vs-hexagonal-architecture-choosing-between-immediate-simplicity-and-long-term-robustness/
<a id="r26"></a>[26] Boni García — Implementing an MCP Server in Java (keep logic out of the MCP adapter) — https://medium.com/@boni.gg/implementing-an-mcp-server-in-java-4a08c509ee7f

### Vertical-Slice / feature-organization (the hybrid)
<a id="r13"></a>[13] Jimmy Bogard — Vertical Slice Architecture (canonical) — https://www.jimmybogard.com/vertical-slice-architecture/
<a id="r14"></a>[14] Spring Modulith — Fundamentals (`sharedModules`, `allowedDependencies`) — https://docs.spring.io/spring-modulith/reference/fundamentals.html
<a id="r15"></a>[15] Milan Jovanović — VSA: where does shared logic live (Rule of Three) — https://www.milanjovanovic.tech/blog/vertical-slice-architecture-where-does-the-shared-logic-live
<a id="r16"></a>[16] InfoQ (2026) — MCP in the Java World (tools grouped by capability) — https://www.infoq.com/articles/mcp-java-architectural-strategy-llm-integrations/

### Micronaut framework & native-image
<a id="r17"></a>[17] io.micronaut.platform:micronaut-platform:5.0.2 — Maven Central — https://central.sonatype.com/artifact/io.micronaut.platform/micronaut-platform/5.0.2
<a id="r18"></a>[18] micronaut-mcp `v1.0.0` libs.versions.toml (BOM pins mcp-core 1.1.2) — https://github.com/micronaut-projects/micronaut-mcp/blob/v1.0.0/gradle/libs.versions.toml
<a id="r21"></a>[21] Micronaut Serialization — guide (`@Serdeable`, serde 3.0.0) — https://micronaut-projects.github.io/micronaut-serialization/latest/guide/
<a id="r22"></a>[22] Micronaut Core — guide (compile-time DI, AOP, `@Factory`, `@Introspected`) — https://docs.micronaut.io/latest/guide/
<a id="r23"></a>[23] Micronaut Guide — reflection metadata for GraalVM Native Image — https://guides.micronaut.io/latest/micronaut-graalvm-reflection-maven-java.html
<a id="r24"></a>[24] ArchUnit — enforcing package/layer dependency rules — https://www.archunit.org/

---

_Survey verified June 2026 against the linked primary sources. The architecture decision is recorded
here as a recommendation; the binding form (with an ADR) lands in `DESIGN.md` after confirmation._
