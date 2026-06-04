# no-bash-mcp — Research (empirical grounding)

> The **empirical grounding** layer (`CLAUDE.md` source-of-truth). *What & why* of product decisions
> lives in [`../design/`](../design/) and [`../adr/`](../adr/); *how* the code is structured lives in
> `DESIGN.md`. This folder holds the evidence those rest on.
>
> The before-coding research (verified **June 2026** against primary sources) was produced by a
> fan-out of web-research agents with an **adversarial verification pass** on every load-bearing
> version/compat claim. Each document carries its own references; corrections caught by verification
> are logged inline.

## Documents

| Doc | Context |
|---|---|
| [`roundtrip-waste-evidence.md`](./roundtrip-waste-evidence.md) | Empirical evidence from local agent transcripts (the project's original motivation). |
| [`technology-baseline.md`](./technology-baseline.md) | **Verified version-truth** — Java 25, Micronaut 5.0.2, Micronaut MCP 1.0.0 (BOM-pinned mcp-core 1.1.2), GraalVM (CE 25.0.2 / Oracle 25.0.3), JUnit (BOM 6.0.3), with primary-source links. Corrects the assumed baseline; resolves the open version questions. |
| [`architecture-survey.md`](./architecture-survey.md) | **Architecture choice** — survey of real JVM MCP servers + Java patterns → lightweight Hexagonal + per-verb feature slices, single module + ArchUnit. Rationale, rejected alternatives, citations. Recorded in [ADR-0006](../adr/0006-application-architecture.md). |
| [`testing-stack-research.md`](./testing-stack-research.md) | **Testing stack + conventions** — JUnit 6, MicronautTest (`startApplication=false`), `@MockBean` (not `MockitoExtension`), WireMock, ArchUnit 1.4.2, MCP Inspector; BOM-managed vs pinned; security-first patterns. |
| [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md) | **GraalVM native-image + Micronaut on WSL2 runbook** — environment setup (apt, SDKMAN, `.wslconfig`, ext4), Maven build mechanics, STDIO/native configuration, static linking; with a corrections log. |

## How this feeds the pipeline

```
docs/research/  ──►  ADR-0006 (decision)  ──►  /prototype (schema/envelope/dispatch)  ──►  DESIGN.md  ──►  /tdd
   (grounding)         docs/adr/                 throwaway model                          architecture     implementation
```
