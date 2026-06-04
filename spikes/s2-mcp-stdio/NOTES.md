# Spike s2 — Micronaut MCP STDIO + native posture (verdict)

**Throwaway, versioned.** Discharges the §7 / G15 stdout-hygiene claim empirically and wires the native
build config. Scaffolded via the **Micronaut Launch API** (`mn` CLI absent) with the **verified** feature
ids `mcp-stdio,json-schema,validation`; Launch generated `micronaut-parent` **5.0.2** and
`micronaut.version=5.0.2` — the DESIGN.md §10 baseline, **no version drift**. All builds use the
**generated `./mvnw`** (wrapper Maven 3.9.15), never the `/mnt/d` Maven 3.8.6.

```
# JVM STDIO round-trip + stdout-purity assertion:
cd spikes/s2-mcp-stdio && python3 stdio_client.py mcpspike/target/mcpspike-0.1.jar /tmp/out /tmp/err \
  && python3 validate_stdout.py /tmp/out /tmp/err
```

---

## Finding 1 — the JVM STDIO transport works end-to-end AND stdout is pure JSON-RPC (G15 discharged)

A trivial `@Tool` (`PingTool`, `io.micronaut.mcp.annotations.{Tool,ToolArg}` — verified package) on a
`@Singleton` bean, with a `@JsonSchema`/`@Serdeable`/`@Introspected` output record, was driven over STDIO
by a minimal client (`stdio_client.py`) that reads each response before sending the next:

- `initialize` → `serverInfo` + `protocolVersion` ✓
- `tools/list` → advertises `ping` with **inputSchema** (from `@ToolArg`) **and outputSchema** (from the
  `@JsonSchema` record) ✓ — the json-schema feature works on both directions
- `tools/call ping` → `structuredContent {"reply":"pong: hi","length":2}`, `isError:false` ✓

**The keystone G15 assertion:** stdout carried **3 lines, all JSON-RPC, 0 non-JSON-RPC** — no banner, no
logs. All logs (Micronaut "Startup completed… Server Running: STDIO sync") landed on **stderr**. The §7
"to be verified empirically" stdout-hygiene claim is **discharged for the JVM**.

### The Launch `mcp-stdio` skeleton ships stdout-hygiene by DEFAULT

DESIGN.md §7 framed `banner(false)` + logback→stderr as "a developer obligation, not automatic."
Empirically, the Launch `mcp-stdio` feature generates **both** out of the box:
`Application.java` → `Micronaut.build(args).banner(false)`, and `logback.xml` → `ConsoleAppender` with
`<target>System.err</target>`. This **lowers** the G15 risk (it is not a silent footgun) but the empirical
assertion was still owed and is now met. (One real-client caveat surfaced: a client that closes stdin
immediately after the last request can race the STDIO transport teardown — `Failed to enqueue message`;
a real client keeps the connection open, so this is a test-harness sequencing note, not a server defect.)

## Finding 2 — native config wired and structurally validated; native posture confirmed Netty-free

`-Dpackaging=native-image` makes the Micronaut parent delegate to native-build-tools (no standalone goal).
The pom's `native-maven-plugin` block was added per DESIGN.md §7/§8: `imageName=no-bash-mcp`,
`--no-fallback`, `-Dfile.encoding=UTF-8`, `-Dsun.jnu.encoding=UTF-8`, `metadataRepository` enabled, and
**no `-H:+SharedArenaSupport`** — `micronaut-http-client` (Netty) is **test-scope only**, so the runtime/
native image is Netty-free (the forge client will be `micronaut-http-client-jdk`). `./mvnw -Dpackaging=
native-image help:effective-pom` resolves the plugin + buildArgs against the real parent 5.0.2 — the
config is structurally valid (the parent contributes its own `--no-fallback`; the duplicate is harmless).

## Finding 3 — the native build: compiles clean, but TWO real blockers surfaced (both documented)

GraalVM CE **25.0.2** (= baseline) installed via mise (`native-image 25.0.2`); `GRAALVM_QUICK_BUILD=true`
(`-Ob`) on a memory-constrained host (7.8 GB total; builder grabbed 6.29 GB / 4 threads). The
native-image **analysis + compilation passed all 8 phases with zero unsupported-feature / class-init /
reachability errors** — 14,711 types reachable, image laid out and created. **The Micronaut MCP STDIO
transport + a trivial `@Tool` are native-compatible and the binary is stdout-clean.** Scope honestly:
this was proven via **two non-production workarounds** (a `libz.so` symlink for the absent `zlib1g-dev`,
and a hand-captured logback metadata file) on a **dynamically-linked** binary — **not** the
`--static-nolibc` distribution `DESIGN.md §8` mandates, which is unbuilt here. And the serialized type was
a **flat `PingResult`**, never the polymorphic sealed `Finding` (`@JsonTypeInfo`/`defaultImpl`/boxed
nullables) that ADR-0007 freezes — so the schema's *native* serde path is asserted, not native-proven (a
`/tdd`+CI obligation). Two blockers surfaced, both real and both documented:

### Blocker 3a — link fails without `zlib1g-dev` (validates `graalvm-native-wsl-setup.md §A.1`)
The final `gcc` link failed: `It appears as though libz:.a is missing` / `ld: cannot find -lz`. The host
has `libz.so.1` (runtime) but not the dev `libz.so`/`libz.a` that `-lz` needs — i.e. **`zlib1g-dev` is not
installed**, exactly the §A.1 prerequisite (`apt-get install build-essential zlib1g-dev`). **Production/CI
fix:** install `zlib1g-dev`. **Gotcha found:** setting `LIBRARY_PATH` did **not** reach the `gcc` the
native-image builder spawns (env is not propagated through `mise exec` → maven → native-image → gcc); the
brew `libz` was ignored. **Spike-only no-sudo workaround that worked:** symlink `libz.so` →
`/usr/lib/x86_64-linux-gnu/libz.so.1.3` inside the GraalVM glibc clibraries dir
(`…/graalvm-community-25.0.2/lib/svm/clibraries/linux-amd64/glibc/`), which is already on the linker `-L`
path. Then the link succeeds → a 76 MB dynamically-linked PIE executable.

### Blocker 3b — the native binary CRASHES AT STARTUP on the logback `<target>System.err</target>` reflection
This is the highest-value native finding. The binary built, but **crashed on first run** in
`Micronaut.<clinit>` → logback init:
`MissingReflectionRegistrationError: Cannot reflectively invoke method
'ch.qos.logback.core.ConsoleAppender.setTarget(java.lang.String)'`. **The very stdout-hygiene config that
DESIGN.md §7 mandates** (`<target>System.err</target>`, set reflectively by logback's joran XML
configurator) is what the auto-fetched reachability metadata does NOT cover (the metadata repo covers the
default System.out config, not the non-default `setTarget`). **Fix (production-correct):** run the
native-image **tracing agent** over the JVM jar (`-agentlib:native-image-agent=config-output-dir=…`) with
real STDIO frames piped, capturing the exact logback reflection (86 KB metadata incl. `ConsoleAppender` +
`setTarget`), and ship it at `src/main/resources/META-INF/native-image/<group>/<artifact>/`. This is the
§C.3 "tracing agent only for uncovered third-party reflection" path — and **logback's stderr-target config
is exactly such a case.** DESIGN.md §7 must call this out: the stdout-hygiene logback config is a native
metadata obligation, captured via the agent.

### Native stdout-clean assertion (the spike's done-criterion) — PASSED
With the captured logback metadata shipped, the rebuilt 77 MB native binary was driven by the **same**
STDIO client + validator: **stdout = 3 lines, all JSON-RPC, 0 non-JSON-RPC** (no banner, no logs); logs on
stderr; `initialize` / `tools/list` (ping with input+output schema) / `tools/call` (`structuredContent
{"reply":"pong: hi","length":2}`) all succeed. **Native startup = 11 ms vs the JVM's ~699 ms** — the
native advantage that justifies the two-phase posture. The G15 stdout-hygiene claim is now discharged for
**both** the JVM and the native binary.

```
[PASS] stdout is PURE JSON-RPC (no banner / no logs leaked to the protocol channel)
[PASS] initialize / tools/list (ping) / tools/call (pong)        ALL HARD ASSERTIONS PASSED
stderr: "Startup completed in 11ms. Server Running: STDIO sync"  (logs on stderr, as required)
```

---

## What feeds DESIGN.md + the production setup

1. **DESIGN.md §7 stdout-hygiene** → discharged (JVM proven; native pending build). Keep `banner(false)` +
   logback→stderr; the Launch skeleton already provides both.
2. **Verified feature ids** for the production scaffold: `mcp-stdio,json-schema,validation`; baseline 5.0.2
   confirmed (no Launch drift).
3. **`@Tool` mechanics confirmed:** `io.micronaut.mcp.annotations.{Tool,ToolArg}` on `@Singleton` beans;
   `@JsonSchema` record I/O yields input+output schemas; `structuredContent` returned. No `@McpTool`.
4. **Native posture:** Netty-free runtime (http-client test-scope only); `-Dpackaging=native-image` +
   the wired buildArgs; `GRAALVM_QUICK_BUILD` for dev, strip for release; **CI runs the native stdout-clean
   assertion** as the release gate (graalvm-native-wsl-setup.md §E).
