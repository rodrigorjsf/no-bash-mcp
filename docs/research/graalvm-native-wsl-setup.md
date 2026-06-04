# GraalVM Native Image + Micronaut on WSL2 — Setup, Build & Configuration Runbook

> **Status:** before-coding research (verified June 2026). Practical how-to for building, configuring
> and shipping this Micronaut MCP server as a GraalVM native image, developed on **WSL2 (Ubuntu)**.
> **Every command, flag and package name was fact-checked against primary sources** (official
> Micronaut / GraalVM / Oracle / SDKMAN / Microsoft docs). A first research pass produced several
> plausible-but-wrong claims; those were caught by an adversarial verification pass and corrected
> here — see [Verification notes](#verification-notes-what-the-first-pass-got-wrong) for the audit
> trail. Sources are linked in [References](#references).
>
> **Baseline** (see [`technology-baseline.md`](./technology-baseline.md)): Java 25 LTS · Micronaut
> Framework 5.0.2 · Micronaut MCP 1.0.0 · GraalVM for JDK 25 (CE **25.0.2** / Oracle **25.0.3**) ·
> Maven · MCP transport = **STDIO**.

## Two-phase build posture (why this matters)

This project develops and tests on the **JVM** (fast inner loop) and produces a **GraalVM native
image only at release** (the slow, closed-world compile). Keep that split:

| Phase | Command | Speed | When |
|---|---|---|---|
| Dev run | `./mvnw mn:run` | fast | local iteration |
| JVM tests | `./mvnw test` | fast | every TDD cycle |
| Native tests | `./mvnw test -Dpackaging=native-image` (native-build-tools `native:test`) | slow | CI release gate only |
| Native binary | `./mvnw package -Dpackaging=native-image` | slow | release |

> **Rationale:** `native:test` runs the whole `native-image` compiler over the test classpath. Use
> JVM tests for red→green→refactor; reserve native tests for the release branch in CI. [[MV]](#mv)

---

## Part A — WSL2 environment setup

### A.1 System prerequisites (Ubuntu/Debian)

The **only** two apt packages the GraalVM "Native Image on Linux" prerequisites require:

```bash
sudo apt-get update
sudo apt-get install build-essential zlib1g-dev
```

`build-essential` provides `gcc`/`make`/`libc6-dev`; `zlib1g-dev` provides the zlib headers the image
builder links against. **GCC ≥ 10.x is required** — Ubuntu 22.04 (GCC 11) and 24.04 (GCC 13) are
safe; Ubuntu 20.04 (GCC 9) can fail. [[GL]](#gl) [[OL]](#ol)

### A.2 Install GraalVM for JDK 25 via SDKMAN!

`native-image` is **bundled** in GraalVM for JDK 17.0.7+/20.0.1+; **do not** run
`gu install native-image` — the GraalVM Updater (`gu`) was **removed entirely in GraalVM for JDK 21**
and no longer exists. After SDKMAN installs GraalVM, `native-image` is already on `PATH`. [[J20]](#j20) [[J21]](#j21)

**Do not hardcode a patch version** — discover the current identifier, then install:

```bash
sdk list java | grep graalce          # find the highest 25.x.y-graalce entry
sdk install java 25.0.2-graalce       # GraalVM Community Edition (open-source) — current CE for JDK 25
sdk default java 25.0.2-graalce
```

> **CE vs Oracle distribution — a verified gotcha.** As of June 2026 the latest **Community Edition**
> for JDK 25 is **`25.0.2-graalce`** (Jan 2026 CPU). **`25.0.3-graalce` does not exist** — the
> April-2026 `25.0.3` CPU shipped for **Oracle GraalVM only** (`25.0.3-graal`). Micronaut 5's release
> notes reference GraalVM 25.0.3 (the Oracle build). For an open-source project either is fine: use
> CE `25.0.2-graalce`, or Oracle `25.0.3-graal` if its (free) terms are acceptable. [[SB]](#sb) [[RC]](#rc) [[CEB]](#ceb)

```bash
# Oracle GraalVM alternative (latest patch):
sdk install java 25.0.3-graal
```

### A.3 WSL2 memory & swap — `.wslconfig`

`native-image` analysis is memory-hungry; WSL2's default (50% host RAM / 25% swap) is often too low.
Create/edit **`%UserProfile%\.wslconfig`** on the **Windows** host (e.g.
`C:\Users\<you>\.wslconfig`):

```ini
[wsl2]
memory=12GB        # >= 8GB; give native-image headroom
processors=4       # >= 4 for parallel image-build stages
swap=8GB           # absorbs build peaks

[experimental]
autoMemoryReclaim=dropCache   # promptly reclaim build cache (already the default on recent WSL)
```

`autoMemoryReclaim` is still under `[experimental]`; `dropCache` is the current default on recent WSL,
so setting it explicitly only matters on older installs. **Changes take effect only after a full WSL
restart** (mandatory, not optional): [[WC]](#wc)

```powershell
wsl --shutdown        # in Windows PowerShell/Terminal, then reopen Ubuntu
```

### A.4 Filesystem — build inside Linux ext4, never `/mnt/c`

Keep the repo in the **Linux ext4** home (`~/projects/…`), **not** under `/mnt/c/…`. File I/O to
`/mnt/c` crosses the VM boundary via the 9P protocol on every syscall; build tools that stat/open
thousands of files run **5–10× slower** there, and native-image is especially I/O-heavy. [[FS]](#fs)

```bash
# correct:  ~/projects/no-bash-mcp/
# wrong:    /mnt/c/Users/rodrigo/projects/no-bash-mcp/   (9P overhead)
cp -r /mnt/c/Users/<you>/projects/no-bash-mcp ~/projects/no-bash-mcp   # if migrating from Windows
```

### A.5 Verify

```bash
java -version             # GraalVM CE 25.0.2 (or Oracle GraalVM 25.0.3)
native-image --version    # bundled; same GraalVM build
which native-image        # the SDKMAN-managed one, not a stale system install
```

---

## Part B — Maven build mechanics

### B.1 The packaging command delegates to native-build-tools

```bash
./mvnw package -Dpackaging=native-image
```

`-Dpackaging=native-image` makes the **Micronaut Maven Plugin** delegate to
`org.graalvm.buildtools:native-maven-plugin` (`native:compile-no-fork`, bound to `package`). There is
**no standalone `mn:native-image` goal** in Micronaut Maven Plugin 5.0.0. [[MP]](#mp)

### B.2 POM skeleton

Both plugin versions are managed by the **Micronaut BOM** — don't pin them:

```xml
<build>
  <plugins>
    <plugin>
      <groupId>io.micronaut.maven</groupId>
      <artifactId>micronaut-maven-plugin</artifactId>
      <extensions>true</extensions>
    </plugin>
    <plugin>
      <groupId>org.graalvm.buildtools</groupId>
      <artifactId>native-maven-plugin</artifactId>
      <extensions>true</extensions>
      <configuration>
        <imageName>no-bash-mcp</imageName>
        <buildArgs>
          <buildArg>--no-fallback</buildArg>
          <!-- STDIO/UTF-8 + Netty arena flags: see Part C -->
        </buildArgs>
        <metadataRepository>
          <enabled>true</enabled>   <!-- auto-fetch library metadata from oracle/graalvm-reachability-metadata -->
        </metadataRepository>
      </configuration>
    </plugin>
  </plugins>
</build>
```

[[MP]](#mp) [[NBT]](#nbt)

### B.3 Micronaut AOT (release builds)

AOT runs **after** annotation processing and precomputes optimizations; for native, its
`graalvm.config` optimizer emits GraalVM config for AOT-transformed code and
`property-source-loader.generate` turns `application.yml` into Java (no YAML parsing in the binary).
[[AOT]](#aot)

```bash
./mvnw package -Dpackaging=native-image -Dmicronaut.aot.enabled=true -Dmicronaut.aot.runtime=native
./mvnw mn:aot-sample-config -Dmicronaut.aot.enabled=true   # inspect available knobs
```

### B.4 Build-time vs run-time initialization

GraalVM's closed-world model needs initialization policy declared up front. Micronaut ships curated
`--initialize-at-build-time` directives for its own internals (and Netty/SLF4J/Logback); you rarely
add them. When a third-party static initializer touches network/random/time, force run-time init via
`buildArgs`. Debug with `-H:+PrintClassInitialization` / `--trace-class-initialization=<FQCN>`. [[RM]](#rm)

### B.5 Quick builds for iteration (`-Ob`)

```xml
<configuration><quickBuild>true</quickBuild></configuration>
```
or `GRAALVM_QUICK_BUILD=true ./mvnw package -Dpackaging=native-image`. Halves compile time by
dropping optimizations — **dev only; strip for release.** [[NBT]](#nbt) [[OPT]](#opt)

---

## Part C — Native-image correctness for an MCP **STDIO** server

### C.1 stdout hygiene is load-bearing (the #1 failure mode)

Under STDIO, **stdout is the JSON-RPC channel** — anything else printed there corrupts the protocol
and every MCP client rejects the server. The official Micronaut MCP docs state the server **MUST NOT
write anything to stdout**. Two changes must ship **together**: [[MM]](#mm)

`src/main/resources/application.yml`:
```yaml
micronaut:
  application:
    name: no-bash-mcp
  banner:
    enabled: false          # the banner prints to stdout — disable it
  mcp:
    server:
      transport: STDIO
```

`src/main/resources/logback.xml` — route **all** logging to **stderr** (never `System.out`):
```xml
<configuration>
  <appender name="STDERR" class="ch.qos.logback.core.ConsoleAppender">
    <target>System.err</target>
    <encoder><pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n</pattern></encoder>
  </appender>
  <root level="warn"><appender-ref ref="STDERR"/></root>
</configuration>
```

> **Native caveat:** do not let a `RollingFileAppender` initialize at image **build** time (file
> descriptors opened during the build are not open at run time). Prefer `ConsoleAppender(System.err)`
> or a lazily-initialized `FileAppender`.

**Charset:** native-image fixes the default charset from the build host's locale. On Windows/WSL2 add
UTF-8 build args so JSON streams aren't corrupted by an inherited code page: [[CS]](#cs)
```xml
<buildArg>-Dfile.encoding=UTF-8</buildArg>
<buildArg>-Dsun.jnu.encoding=UTF-8</buildArg>
```

### C.2 Tool registration & reflection-free serialization (verified mechanism)

How Micronaut MCP 1.0.0 actually declares a tool/verb (confirmed against the module guide + a working
sample): [[MM]](#mm) [[GLF]](#glf)

- **A tool is a `@Tool`-annotated method on a `@Singleton` bean.** The method name is the tool name
  (override with `@Tool(name=…)`). Rename arguments with **`@ToolArg`**. Discovery is via Micronaut
  **DI** — no manual registry. (There is **no** `@McpTool`.)
- **Input schema** comes from one of three mechanisms — pick per verb:
  1. a Java **record** annotated with **`@JsonSchema` _and_ `@Introspected`** used as the method
     parameter (the framework uses the compile-time-generated schema);
  2. plain method parameters + **`@ToolArg`** (names→schema properties, types→schema types);
  3. a hand-built `McpSchema.JsonSchema` for full control.
- **Output / structured content:** annotate the **return-type record** with `@JsonSchema` (+
  `@Introspected`). A plain `String` → text content; a `McpSchema.CallToolResult` → full envelope
  control.

> **`@JsonSchema` is real but is its own module** — `io.micronaut.jsonschema:micronaut-json-schema-annotations`
> — and is **complementary to** `@Introspected`, not an alternative. It generates
> `META-INF/schemas/*.schema.json` at compile time. [[JS]](#js)

**Serialization for native:** annotate the envelope and tool I/O types with **`@Serdeable`**
(Micronaut Serialization, `micronaut-serde-jackson`) so serializers are generated at compile time
with **zero runtime reflection** — never Jackson Databind's reflective path. `@Serdeable` is
meta-annotated with `@Introspected`. [[SD]](#sd)

```java
@Serdeable @Introspected @JsonSchema
public record RunTestsInput(String path, String target) {}

@Singleton
class RunTestsTool {
  @Tool(description = "Run the project's tests and return normalized failures")
  RunTestsResult runTests(@ToolArg RunTestsInput input) { /* ... */ }
}
```

> **MCP SDK envelope types** (`io.modelcontextprotocol.sdk` — `CallToolResult`, `Tool`,
> `JsonRpcMessage`, …) are third-party. The micronaut-mcp module ships a **`test-suite-graal`** CI
> module and a `test-suite-jackson-databind` suite, indicating it handles their native metadata
> **internally**. **Do not add `@SerdeImport`/`reflect-config` for SDK types** unless a native build
> error proves a gap. (The earlier "`@SerdeImport` on `Application.class` for SDK types" claim was
> unverified and is **not** required by default.) [[MMG]](#mmg) [[SD]](#sd)

### C.3 Resources & reachability metadata are mostly automatic

The Micronaut Maven plugin (now via native-build-tools `native:generateResourceConfig`) **scans all
of `src/main/resources`** and auto-registers them for native image — **including `application.yml`,
`logback.xml`, and the generated `META-INF/micronaut/**` descriptors.** You do **not** need a manual
`-H:IncludeResources` for those standard files. Only resources produced **outside**
`src/main/resources` need explicit registration. Likewise, Micronaut's annotation processors generate
reachability metadata for framework beans, AOP proxies and `@Introspected`/`@Serdeable` types
automatically. [[GS]](#gs) [[RM]](#rm)

Modern format (GraalVM 25): a single **`reachability-metadata.json`** under
`META-INF/native-image/<groupId>/<artifactId>/` (replaces the old `reflect-config.json` /
`resource-config.json` / `jni-config.json` split). [[RM]](#rm)

**Tracing agent** — only when a third-party type isn't covered. The Micronaut-idiomatic invocation
(no fat-jar needed — runs against test classes) writes to `target/native/agent-output/test/`: [[MA]](#ma)
```bash
./mvnw -Pnative -Dagent=true test
```
The explicit `java -jar` form **does** require a prior `./mvnw package`, and for this STDIO server you
must feed it representative JSON-RPC frames so the SDK reflection paths are exercised:
```bash
echo '{"jsonrpc":"2.0","method":"tools/list","id":1}' | \
  java -agentlib:native-image-agent=config-output-dir=target/ni-config/ -jar target/no-bash-mcp.jar
# add more payloads with config-merge-dir= to accumulate coverage
```

### C.4 Outbound HTTP & the `-H:+SharedArenaSupport` flag (design-relevant)

The forge adapter makes **outbound** HTTP calls to GitHub. The HTTP-client choice has a native-image
consequence:

- **If you use `micronaut-http-client`** (declarative `@Client`), **Netty is on the classpath.** On
  GraalVM 25, Netty's `Arena.ofShared()` use means you should add the flag explicitly — the Micronaut
  Maven Plugin does **not** auto-inject it (verified: 0 hits for `SharedArenaSupport` in the plugin
  source). With Netty **≥ 4.2.8** the omission won't crash (graceful fallback) but **silently
  disables Arena-backed allocators**; with Netty **< 4.2.8** it's a hard `UnsupportedFeatureError`.
  Add: `‹buildArg›-H:+SharedArenaSupport‹/buildArg›`. [[SA]](#sa) [[NT]](#nt)
- **If you use the JDK's `java.net.http.HttpClient`** (no Netty), the flag is moot and the native
  surface is smaller. ADR-0003 specifies *"native Java HTTP calls"*; **whether that means the JDK
  client or Micronaut's Netty client is a `DESIGN.md` decision** — the JDK client avoids the Netty/
  Arena concern entirely on a server that has no inbound HTTP. Flagged for the architecture doc.

---

## Part D — Packaging & distribution

### D.1 Static linking (portable binary)

| Goal | Flags | Toolchain |
|---|---|---|
| **Mostly-static** (recommended) | `--static-nolibc` | none beyond gcc/zlib — glibc stays dynamic |
| Fully static (scratch container) | `--static --libc=musl` | **musl toolchain** (see caveat) |

Both flag spellings are confirmed for GraalVM for JDK 25. **Recommendation: `--static-nolibc`** — it
produces a binary portable across glibc Linux hosts without the musl toolchain. [[ST]](#st)

> **musl caveat (verified):** the GraalVM JDK 25 guide does **not** use `apt-get install musl-tools`.
> Ubuntu's `musl-tools` ships musl ≤ 1.2.5 (affected by CVE-2025-26519) and lacks the **static zlib**
> the `--static --libc=musl` path needs. Full-static requires Oracle's pre-built musl toolchain
> tarball (`musl-toolchain-…-linux-amd64.tar.gz` from gds.oracle.com) or musl ≥ 1.2.6 built from
> source. Avoid full-static unless you specifically need a scratch container. [[ST]](#st)

### D.2 UPX compression — **not recommended here**

UPX is **contraindicated** for this server. The often-cited "3–4× smaller / ~170 ms penalty" figures
are from a **community** blog (2021), **not** official GraalVM docs; UPX has **known Linux crashes**
on GraalVM native images (`oracle/graal#2830`, `upx#670`); and for an **on-demand STDIO server**
(fresh process per invocation) the per-launch decompression (~170–265 ms) **negates** native-image's
sub-10 ms startup advantage. Ship the uncompressed binary. [[UPX]](#upx) [[U2830]](#u2830)

### D.3 Docker-native (optional)

`./mvnw package -Dpackaging=docker-native` builds inside a container (musl/static) for a minimal
scratch image — relevant only for the future containerized deployment (roadmap), not v1's STDIO
binary. [[MP]](#mp)

---

## Part E — CI & open items

- **G15 (native-image on JDK 25) is unblocked at the research level** (GraalVM 25 has full JDK 25
  native support) but still gets an **empirical CI check**: run `./mvnw test -Dpackaging=native-image`
  on the release branch and confirm the binary is **stdout-clean** (pipe an `initialize` frame, assert
  only a JSON-RPC envelope on stdout). [[MM]](#mm)
- **Open design decision → `DESIGN.md`:** forge HTTP client = JDK `java.net.http.HttpClient` (no
  Netty, smaller native surface) **vs** Micronaut `@Client` (Netty + `-H:+SharedArenaSupport`). See
  C.4.
- **Spike:** confirm the Micronaut MCP STDIO transport keeps logs off stdout by default *and* that
  the native binary is stdout-clean (the observability/G15 spike).

---

## Verification notes (what the first pass got wrong)

This runbook's first research pass was adversarially verified; corrections applied:

| First-pass claim | Verified reality |
|---|---|
| Plugin auto-injects `-H:+SharedArenaSupport` for Java 25+ | **False** — 0 hits in plugin source; add it manually, and only if Netty is on the classpath. [[SA]](#sa) |
| `apt-get install musl-tools` sets up full-static | **Insufficient** — needs Oracle musl toolchain (zlib + musl ≥1.2.6); prefer `--static-nolibc`. [[ST]](#st) |
| GraalVM CE for JDK 25 is `25.0.3-graalce` | **No such build** — CE is `25.0.2-graalce`; `25.0.3` is Oracle-only. [[SB]](#sb) |
| Dev must manually register `logback.xml`/`application.yml` resources | **Auto-included** from `src/main/resources`. [[GS]](#gs) |
| `@SerdeImport` on `Application` needed for MCP SDK types | **Not by default** — module handles SDK metadata (`test-suite-graal`). [[MMG]](#mmg) |
| UPX "~170 ms / 3–4×" is authoritative | **Community-sourced**, with known crashes; not recommended for STDIO. [[UPX]](#upx) |

---

## References

<a id="gl"></a>[GL] GraalVM — Native Image installation on Linux (apt prerequisites) — https://www.graalvm.org/latest/getting-started/linux/
<a id="ol"></a>[OL] Oracle GraalVM JDK 25 — Installation on Linux — https://docs.oracle.com/en/graalvm/jdk/25/docs/getting-started/linux/
<a id="j20"></a>[J20] GraalVM for JDK 20 Release Notes (native-image bundled, no `gu install`) — https://www.graalvm.org/release-notes/JDK_20/
<a id="j21"></a>[J21] Oracle GraalVM for JDK 21 Release Notes (GraalVM Updater `gu` removed) — https://docs.oracle.com/en/graalvm/jdk/21/docs/release-notes/
<a id="sb"></a>[SB] SDKMAN! Broker API — Java versions list (CE `25.0.2-graalce`, Oracle `25.0.3-graal`) — https://api.sdkman.io/2/candidates/java/linux/versions/list
<a id="rc"></a>[RC] GraalVM Release Calendar — https://www.graalvm.org/release-calendar/
<a id="ceb"></a>[CEB] GraalVM CE Builds — GitHub Releases (no `jdk-25.0.3` tag) — https://github.com/graalvm/graalvm-ce-builds/releases
<a id="wc"></a>[WC] Advanced settings configuration in WSL (`.wslconfig` memory/swap/processors, `autoMemoryReclaim`) — https://learn.microsoft.com/en-us/windows/wsl/wsl-config
<a id="fs"></a>[FS] WSL2 filesystem performance — keep projects in Linux ext4, not `/mnt/c` — https://learn.microsoft.com/en-us/windows/wsl/filesystems
<a id="mp"></a>[MP] Micronaut Maven Plugin — Package examples (`-Dpackaging=native-image` delegation, docker-native, agent) — https://micronaut-projects.github.io/micronaut-maven-plugin/latest/examples/package.html
<a id="nbt"></a>[NBT] GraalVM Native Build Tools — Maven plugin (`native:compile-no-fork`, `quickBuild`, `metadataRepository`) — https://graalvm.github.io/native-build-tools/latest/maven-plugin.html
<a id="aot"></a>[AOT] Micronaut AOT — optimizer guide (`graalvm.config`, `property-source-loader.generate`) — https://micronaut-projects.github.io/micronaut-aot/latest/guide/
<a id="mv"></a>[MV] Micronaut Maven Plugin — AOT/native examples — https://micronaut-projects.github.io/micronaut-maven-plugin/latest/examples/aot.html
<a id="rm"></a>[RM] GraalVM — Reachability Metadata (`reachability-metadata.json`, build-time vs run-time init) — https://www.graalvm.org/latest/reference-manual/native-image/metadata/
<a id="opt"></a>[OPT] GraalVM — Native Image Optimizations and Performance (`-Ob`, `-Os`, `-O2`) — https://www.graalvm.org/latest/reference-manual/native-image/optimizations-and-performance/
<a id="mm"></a>[MM] Micronaut MCP — module guide (STDIO config, "MUST NOT write to stdout", `@Tool`, `@JsonSchema` output) — https://micronaut-projects.github.io/micronaut-mcp/latest/guide/
<a id="mmg"></a>[MMG] micronaut-mcp — GitHub repo (`test-suite-graal`, `test-suite-jackson-databind`) — https://github.com/micronaut-projects/micronaut-mcp
<a id="glf"></a>[GLF] Guillaume Laforge — Creating an MCP server with Micronaut (working `@Tool` + `@JsonSchema` + `@Introspected` example) — https://glaforge.dev/posts/2025/09/16/creating-a-streamable-http-mcp-server-with-micronaut/
<a id="js"></a>[JS] Micronaut JSON Schema — guide (`@JsonSchema` module, `META-INF/schemas/*.schema.json`) — https://micronaut-projects.github.io/micronaut-json-schema/latest/guide/
<a id="sd"></a>[SD] Micronaut Serialization — guide (`@Serdeable`, `@SerdeImport`, build-time serializers) — https://micronaut-projects.github.io/micronaut-serialization/latest/guide/
<a id="gs"></a>[GS] Micronaut Core — GraalVM resource handling (`graalServices`, auto resource-config from `src/main/resources`) — https://github.com/micronaut-projects/micronaut-core/blob/4.10.x/src/main/docs/guide/languageSupport/graal/graalServices.adoc
<a id="ma"></a>[MA] Micronaut Guides — Generate reflection metadata for GraalVM (`mvn -Pnative -Dagent=true test`) — https://guides.micronaut.io/latest/micronaut-graalvm-reflection-maven-java.html
<a id="cs"></a>[CS] Native image charset / `sun.jnu.encoding` UTF-8 caveat — https://github.com/quarkusio/quarkus/issues/1971
<a id="sa"></a>[SA] oracle/graal #12318 — `Arena.ofShared is not active` → `-H:+SharedArenaSupport` (not plugin-auto-injected) — https://github.com/oracle/graal/issues/12318
<a id="nt"></a>[NT] netty/netty #15877 — graceful Arena fallback (merged 4.2.8.Final) — https://github.com/netty/netty/pull/15877
<a id="st"></a>[ST] GraalVM for JDK 25 — Build a Statically/Mostly-Statically Linked Native Executable (`--static-nolibc`, `--static --libc=musl`, Oracle musl toolchain) — https://docs.oracle.com/en/graalvm/jdk/25/docs/reference-manual/native-image/guides/build-static-executables/
<a id="upx"></a>[UPX] Compressed GraalVM Native Images (community Medium — origin of the 170 ms / 3–4× figures) — https://medium.com/graalvm/compressed-graalvm-native-images-4d233766a214
<a id="u2830"></a>[U2830] oracle/graal #2830 — UPX Linux crash ("Failed to create a new Isolate. code 7") — https://github.com/oracle/graal/issues/2830

---

_All commands, flags and package names verified against the linked primary sources, June 2026.
Re-verify GraalVM/Micronaut/Netty versions before each release; the `-H:+SharedArenaSupport` and
static-linking guidance is version-sensitive._
