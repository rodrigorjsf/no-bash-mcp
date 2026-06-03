# Build & Distribution

## Baseline

- **Micronaut 5.0.0**, **Java 25** baseline, build with **Maven**.
- **Micronaut MCP 1.0.0** (GA, in the 5.0 platform; MCP Java SDK 1.1.2), **STDIO** transport.
- Verify **GraalVM native-image for JDK 25** is available in CI before relying on the native build
  (gotcha **G15**).

## Two-phase: JVM core, native at release

- **Develop + test on the JVM** — fast iteration, a single cross-platform artifact.
- **GraalVM native image at release** — produced from the same Micronaut codebase.

## Why native (and why *not* for the reason people assume)

The usual selling point of native image is **startup speed** — and that **does not apply here**.
The MCP is a session-lived STDIO server that warms up **once** per session; JVM warmup is amortized
across the whole session and is marginal.

The reasons that **do** apply:

1. **Distribution** — a self-contained binary that runs **without a JRE installed**. Huge for the
   "works on any OS" goal and for zero-friction adoption.
2. **RAM footprint** — low memory for a process that idles in the background all session.

## Cost accepted

- Native image is **per-platform** → "any OS" means a **CI build matrix**
  (linux / macos / windows × arch), not a single artifact. See gotcha **G3**.
- Mitigation: **Micronaut is AOT-native by design** (reflection/resource config generated at
  compile time), so the classic native-config pain is low. `ProcessBuilder` subprocess spawning
  works in native with no special configuration.

## Positioning

Native image is **packaging**, not the thesis. The security + structured-output mechanic works
identically on the JVM, so native must **not** gate the core mechanic or its feedback loop.
