# Application architecture: lightweight hexagonal + per-verb feature slices, single module

**Status:** proposed — the macro-architecture is chosen (evidence-backed, user-confirmed); it is
promoted to **accepted** once `/prototype` validates the schema/envelope/dispatch data model, honoring
`CLAUDE.md`'s "*/prototype* to flesh out the chosen design **before committing to it**" rule. The
macro-architecture decided here (hexagonal / single-module / `micronaut-http-client-jdk`) is
independent of the still-unfrozen **data-model** layer (the universal-schema bet — deferred to the
spike + a post-spike ADR); `/prototype` is the gate that could surface a port-boundary problem and so
is what promotes this ADR. DESIGN.md is written from the `/prototype` output, not before it.

The before-coding phase chose the code architecture **from a survey** of real JVM MCP servers and
current Java application architectures (not by invention — see `CLAUDE.md`). The survey, rationale,
rejected alternatives, and all citations live in
[`../research/architecture-survey.md`](../research/architecture-survey.md); the verified version
baseline in [`../research/technology-baseline.md`](../research/technology-baseline.md). This ADR
records the decision; `DESIGN.md` elaborates the concrete structure.

## Decision

- **Macro-architecture: Hexagonal / Ports-and-Adapters (lightweight variant).** The inbound driver
  adapter is a set of Micronaut MCP `@Tool` methods on `@Singleton` beans (no inbound port interface —
  the `@Tool` bean *is* the adapter; transport is **configuration**, `micronaut.mcp.server.transport=STDIO`).
  Two **outbound driven ports** — `CommandExecutorPort` (ecosystem/process execution) and `ForgePort`
  (HTTP forge inspection) — are plain Java interfaces in a pure domain; concrete adapters are
  `@Singleton` beans implementing them.
- **Inside the application: feature-organized by verb** (vertical-slice flavor) over a bounded shared
  kernel (`domain/` pure types + `infra/` shared infrastructure). No separate `common/` package.
- **Single Maven module**, with the dependency rule enforced by an **ArchUnit** test (chosen over a
  multi-module `core`/`adapters` split for KISS at ~15 verbs).
- **Domain types are reflection-free**: Java records annotated `@Serdeable @Introspected` (+
  `@JsonSchema` on MCP tool I/O) → compile-time serialization, GraalVM-native-ready.
- **Forge HTTP client: `micronaut-http-client-jdk`** — Micronaut `@Client` ergonomics over the JDK
  `java.net.http.HttpClient`, **Netty-free**. A minimal `micronaut-mcp-server-java-sdk` STDIO server
  is Netty-free (verified from the module's `build.gradle.kts`); this keeps it so, eliminating the
  `-H:+SharedArenaSupport` native-image concern. (ADR-0003's *"native Java HTTP"* contrasts in-process
  HTTP with shell-out / third-party MCP; it does not mandate a client library — all options satisfy it.)

## Why (the trade-off)

- The deciding variable for hexagonal-over-layered is **adapter count, not project size**: two
  distinct outbound technologies (ProcessBuilder, HTTP forge) each need an independent test seam. The
  `@Tool`-bean → service → port → adapter shape is exactly how real Micronaut MCP servers (official
  guides; `glaforge/mn-mcp-server`) are built.
- **Onion/Clean rejected** (no enterprise entities, no orchestration → the Use-Case ring + DTO-at-every-
  boundary mapping ~triples class count for pass-through verbs). **Layered rejected** (no seam → forge
  untestable without real GitHub). **Pure vertical-slice rejected** (the shared kernel — envelope,
  run-cache, process-exec, concurrency, schema — is larger than per-verb code, so the *hybrid* wins).
  **Full-ceremony hexagonal rejected** (a use-case interface per verb is pure ceremony for 1:1
  verb→use-case mappings).
- **Single module + ArchUnit** keeps the build simple; ArchUnit 1.4.2 (core artifact — *not*
  `archunit-junit5`, which is incompatible with JUnit 6) enforces `domain !-> adapter` and
  `verb.* !-> verb.*`.

## Consequences

- The **harness adapter** (bootstrap permission-config writer) is a third adapter *family* but belongs
  to the **separate bootstrap deliverable**, not the running server's request path — `DESIGN.md` must
  state this explicitly (the grilling handoff listed three families without that scoping).
- The package layout, dispatch flow, run-cache/`handle`, `RESOURCE_BUSY` (ADR-0005), and the native
  build posture are detailed in `DESIGN.md`, grounded in the four `docs/research/` documents.
- Reconsider the multi-module split only if the core grows beyond what an ArchUnit rule comfortably
  guards.
