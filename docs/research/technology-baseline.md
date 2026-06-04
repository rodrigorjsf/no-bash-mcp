# Technology Baseline — Verified Versions (June 2026)

> **Status:** version-truth verification, before-coding phase.
> **Why this doc exists:** the grilling handoff carried an *assumed* baseline (Micronaut 5.0.0,
> Java 25, Micronaut MCP 1.0.0, MCP Java SDK 1.1.2, "JUnit 6.x"). Per the before-coding plan, those
> numbers must be **confirmed against primary sources** before `DESIGN.md` and `pom.xml` are built on
> them — a wrong version invalidates everything downstream. This is the result of that verification.
> Every row carries a primary-source link (see [References](#references)).
>
> **Verification method:** web search + fetch of official release notes / GitHub Releases / Maven
> Central / OpenJDK announcements (June 2026). Secondary sources (blogs) were used only to locate
> primary sources, never as the source of a version number.

## Summary table

| # | Technology | Assumed (handoff) | **Verified current GA** | Release date | Java 25 status | Verdict |
|---|---|---|---|---|---|---|
| 1 | Micronaut Framework (core) | 5.0.0 | **5.0.2** | 2026-06-03 | requires Java 25 (5.x baseline) | corrected (use 5.0.2) |
| 2 | Java / JDK | 25 | **JDK 25** (GA + LTS) | 2025-09-16 | is the LTS | confirmed |
| 3 | Micronaut MCP module | 1.0.0 GA | **1.0.0 GA** | ~2026-05 | contemporaneous w/ Micronaut 5 | confirmed |
| 4 | MCP Java SDK | 1.1.2 | **1.1.3** (latest 1.1.x patch); 1.1.2 valid | 2025-05-21 | n/a (library) | prefer BOM-managed version |
| 5 | GraalVM native-image | (implicit) JDK 25 support | **CE 25.0.2 / Oracle 25.0.3** | CE 2026-01 / Oracle 2026-04 | full JDK 25 native support | confirmed — **unblocks G15** |
| 6 | JUnit (Jupiter / Platform) | "6.x GA" | **6.1.0** (latest); **BOM pins 6.0.3** | 2026-05-19 | compatible (min Java 17) | inherit BOM 6.0.3; 6.1.0 by override |

## Detail

### 1. Micronaut Framework — **5.0.2**
- Current GA is **5.0.2** (patch, 2026-06-03), not 5.0.0. The **5.x line requires Java 25 as its
  baseline**; the 4.x line (currently 4.10.24) stays maintained for Java 17/21 users.
- **Action:** `pom.xml` parent / platform → **5.0.2**.
- Sources: Micronaut release blog [[A]](#a); micronaut-core GitHub Releases [[B]](#b).

### 2. Java / JDK 25 — **GA + LTS** (confirmed)
- JDK 25 is GA (2025-09-16) and is an **LTS** release (the LTS after 21). It is the mandatory
  baseline for Micronaut 5.x.
- Sources: Oracle announcement [[C]](#c); OpenJDK `announce` list [[D]](#d); Oracle SE support
  roadmap [[E]](#e).

### 3. Micronaut MCP module — **1.0.0 GA** (compatibility resolved)
- A **1.0.0 GA exists** on Maven Central. The coordinate for a Java-SDK-backed STDIO server is
  **`io.micronaut.mcp:micronaut-mcp-server-java-sdk`** (not a generic `micronaut-mcp` artifact). Its
  1.0.0 release pinned **MCP Java SDK 1.1.2** (via PR #177).
- **Date / compatibility (corrected by the verification pass):** Maven Central shows 1.0.0 published
  **~2026-05** (≈16 days before this research), i.e. **contemporaneous with Micronaut Framework 5.0.0
  (2026-05)** — *not* the "2025-05 / Micronaut-4-era" an earlier reading suggested (that source had a
  known year-confusion). The `/latest/` guide maps to 1.0.0 and documents `@Tool` on Micronaut-DI
  `@Singleton` beans (current Micronaut idioms). **So 1.0.0 is the current module for the current
  Micronaut — the compatibility concern is resolved.**
- **Tool mechanics (verified):** a tool = a `@Tool` method on a `@Singleton` bean (`@ToolArg` for
  args); input/output schema via `@JsonSchema` (separate `micronaut-json-schema` module,
  complementary to `@Introspected`); native serialization via `@Serdeable` — and the module handles
  MCP-SDK envelope-type native metadata internally (`test-suite-graal`). Detail in
  [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md) §C.2.
- Sources: micronaut-mcp GitHub Releases [[F]](#f); Maven Central artifact [[G]](#g); module guide [[O]](#o).

### 4. MCP Java SDK — **1.1.3** (but defer to the micronaut-mcp BOM)
- 1.1.2 (2025-04-25) is valid; **1.1.3 (2025-05-21)** is the latest 1.1.x patch. A 2.0.0-Mx
  milestone line exists — **not** for production.
- **Action:** do **not** hard-pin blindly. If the micronaut-mcp BOM manages the SDK version, inherit
  it; only override to 1.1.3 if no conflict. (BOM behaviour verification in flight.)
- Sources: java-sdk GitHub Releases [[H]](#h); Maven Central [[I]](#i).

### 5. GraalVM native-image — full JDK 25 support — **unblocks G15**
- GraalVM for JDK 25 shipped alongside JDK 25 (2025-09-16). **Distribution split (verified):** the
  latest **Community Edition** for JDK 25 is **`25.0.2-graalce`** (2026-01 CPU) — **`25.0.3-graalce`
  does not exist**; the 2026-04 `25.0.3` CPU shipped for **Oracle GraalVM only** (`25.0.3-graal`),
  which is what Micronaut 5's notes reference. Either works for an open-source project.
- An early ASM 9.7.1 incompatibility with Java-25 class files was fixed by the ASM 9.8 bump in GraalVM
  25.0.1+. **No known production blocker** for native-image on JDK 25.
- This **resolves gotcha G15** at the research level; the build-time spike still confirms it
  empirically in *this* project's CI. Build/setup details + corrected static-linking and
  `-H:+SharedArenaSupport` guidance live in [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md).
- Sources: GraalVM JDK 25 release notes [[J]](#j); Oracle GraalVM 25 docs [[K]](#k); SDKMAN broker [[P]](#p).

### 6. JUnit — **6.1.0**
- JUnit **6.1.0** (2026-05-19) is current; 6.0.0 went GA 2025-09-30. JUnit 6 unifies Platform +
  Jupiter + Vintage under one version number. **Minimum baseline is Java 17** (so fully compatible
  with Java 25). The 5.x line (5.14.4) is still maintained in parallel.
- **Action:** **inherit the BOM-managed JUnit 6.0.3** for v1 (consistent with micronaut-test 5.0.0;
  all needed APIs are in 6.0.x); override to `junit-bom:6.1.0` only if a 6.1-specific feature is
  required. Exploit the full Jupiter API (parameterized / dynamic / nested / display-name) per
  [`testing-stack-research.md`](./testing-stack-research.md).
- Sources: junit-framework GitHub Releases [[L]](#l); JUnit 6 release notes [[M]](#m); InfoQ JUnit 6
  GA [[N]](#n).

## Open questions

1. ~~**micronaut-mcp 1.0.0 ↔ Micronaut 5 compatibility.**~~ **RESOLVED** — 1.0.0 is contemporaneous
   with Micronaut 5 (both ~2026-05) and its `/latest/` guide documents current-Micronaut idioms
   (`@Tool` on `@Singleton` DI beans). It is the current module for the current framework.
2. ~~**MCP Java SDK version source of truth.**~~ **RESOLVED** — `micronaut-platform:5.0.2` (parent
   BOM) manages `micronaut-mcp` at **1.0.0** and transitively pins `io.modelcontextprotocol.sdk:mcp-core`
   at **1.1.2** (confirmed from `libs.versions.toml` at the micronaut-mcp `v1.0.0` tag). **Inherit the
   BOM — do not override to 1.1.3.** Declare one dependency, `micronaut-mcp-server-java-sdk`, with no
   version. Source: [[Q]](#q).
3. ~~**Supporting test libraries.**~~ **RESOLVED** — `micronaut-test-bom:5.0.0` (imported by the
   platform BOM) manages **AssertJ 3.27.7**, **Mockito 5.23.0**, **mockito-junit-jupiter 5.23.0**, and
   **JUnit 6.0.3** → declare versionless. Pin manually **only** `org.wiremock:wiremock:3.13.2` and
   `com.tngtech.archunit:archunit:1.4.2` (core, not `archunit-junit5`). Use `@MockBean` (not
   `MockitoExtension`). Full detail: [`testing-stack-research.md`](./testing-stack-research.md).
4. **STDIO logging-off-stdout.** Largely **answered at the doc level**: the Micronaut MCP docs state a
   STDIO server **MUST NOT write to stdout**; the fix is `micronaut.banner.enabled: false` +
   logback→`System.err` (see [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md) §C.1).
   Remaining: an **empirical** spike confirming the binary is stdout-clean (gotcha G15).

## References

<a id="a"></a>[A] Micronaut Framework 5.0.0 Released — https://micronaut.io/2026/05/20/micronaut-framework-5-0-0-released/
<a id="b"></a>[B] micronaut-core GitHub Releases — https://github.com/micronaut-projects/micronaut-core/releases
<a id="c"></a>[C] Oracle Releases Java 25 (2025-09-16) — https://www.oracle.com/news/announcement/oracle-releases-java-25-2025-09-16/
<a id="d"></a>[D] JDK 25 General Availability — OpenJDK announce list — https://mail.openjdk.org/pipermail/announce/2025-September/000360.html
<a id="e"></a>[E] Oracle Java SE Support Roadmap (LTS designations) — https://www.oracle.com/java/technologies/java-se-support-roadmap.html
<a id="f"></a>[F] micronaut-mcp GitHub Releases — https://github.com/micronaut-projects/micronaut-mcp/releases
<a id="g"></a>[G] Maven Central — io.micronaut.mcp:micronaut-mcp-server-java-sdk — https://central.sonatype.com/artifact/io.micronaut.mcp/micronaut-mcp-server-java-sdk
<a id="h"></a>[H] modelcontextprotocol/java-sdk GitHub Releases — https://github.com/modelcontextprotocol/java-sdk/releases
<a id="i"></a>[I] Maven Central / mvnrepository — io.modelcontextprotocol.sdk:mcp — https://mvnrepository.com/artifact/io.modelcontextprotocol.sdk/mcp
<a id="j"></a>[J] GraalVM Community Edition for JDK 25 — Release Notes — https://www.graalvm.org/release-notes/JDK_25/
<a id="k"></a>[K] Oracle GraalVM 25 Documentation — Release Notes — https://docs.oracle.com/en/graalvm/jdk/25/docs/release-notes/
<a id="l"></a>[L] junit-team/junit-framework GitHub Releases — https://github.com/junit-team/junit-framework/releases
<a id="m"></a>[M] JUnit release notes — https://docs.junit.org/6.0.2/release-notes.html
<a id="n"></a>[N] JUnit 6.0.0 GA announcement (InfoQ) — https://www.infoq.com/news/2025/10/junit6-java17-kotlin/
<a id="o"></a>[O] Micronaut MCP module guide (@Tool / @JsonSchema / @Serdeable; STDIO) — https://micronaut-projects.github.io/micronaut-mcp/latest/guide/
<a id="p"></a>[P] SDKMAN! broker — Java versions (CE `25.0.2-graalce` vs Oracle `25.0.3-graal`) — https://api.sdkman.io/2/candidates/java/linux/versions/list
<a id="q"></a>[Q] micronaut-mcp `v1.0.0` `libs.versions.toml` (BOM pins `mcp-core` 1.1.2) — https://github.com/micronaut-projects/micronaut-mcp/blob/v1.0.0/gradle/libs.versions.toml

---

_Verified June 2026 via primary-source web research. Re-verify before each release; pin exact
versions in `pom.xml` only after the open questions above are closed._
