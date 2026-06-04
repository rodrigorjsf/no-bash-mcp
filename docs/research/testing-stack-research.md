# Testing Stack & Conventions — no-bash-mcp

> **Status:** before-coding research → **testing stack + conventions** (verified June 2026). Per
> `CLAUDE.md`'s TDD-first working agreement, the test stack is pinned **before** any production code.
> This feeds `DESIGN.md` and is the contract every `/tdd` cycle honors.
>
> **Method.** Surveyed the JUnit 6.1 API, MicronautTest, HTTP-mocking options, and MCP Inspector from
> primary sources, then **adversarially verified** the load-bearing version/compat claims (ArchUnit↔
> JUnit 6, the test-lib BOM, Mockito↔JUnit 6, serde 3.0.0). Corrections from that pass are folded in
> and flagged in [Verification notes](#verification-notes). All links in [References](#references).
>
> **Baseline** ([`technology-baseline.md`](./technology-baseline.md)): Java 25 · Micronaut 5.0.2
> (parent `io.micronaut.platform:micronaut-platform:5.0.2`) · JUnit Jupiter (BOM-managed **6.0.3**;
> 6.1.0 available by override) · Maven · STDIO.

## Decision (the stack)

| Concern | Choice | Pin source |
|---|---|---|
| Test framework | **JUnit Jupiter** (BOM **6.0.3**; override to 6.1.0 only if needed) | BOM |
| Micronaut integration | **`micronaut-test-junit5` 5.0.0** + `@MicronautTest(startApplication=false)` | BOM |
| Mocking | **`@MockBean`** (Micronaut) over `mockito-core` 5.23.0 — **not** `MockitoExtension` | BOM |
| Fluent assertions | **AssertJ 3.27.7** | BOM (`micronaut-test-bom`) |
| Forge HTTP mocking | **WireMock 3.13.2** (in-JVM `WireMockExtension`) | pin manually |
| Architecture tests | **ArchUnit 1.4.2** (core artifact, **not** `archunit-junit5`) | pin manually |
| Protocol acceptance | **MCP Inspector** `--cli` (two-tier) | dev/CI tool (Node) |

> **What to pin vs inherit (verified):** `micronaut-platform:5.0.2` imports `micronaut-test-bom:5.0.0`,
> which **manages** `assertj-core:3.27.7`, `mockito-core:5.23.0`, `mockito-junit-jupiter:5.23.0`, and
> JUnit at **6.0.3** — declare these **versionless**. Pin **only** `org.wiremock:wiremock:3.13.2` and
> `com.tngtech.archunit:archunit:1.4.2` (neither is BOM-managed). [[BOM]](#bom) [[AU]](#au)

---

## 1. JUnit Jupiter 6 (6.0.3 BOM / 6.1 API) — the API we exploit

Mapped to this project's needs (full source set, dynamic, nested, display-name). [[J61]](#j61) [[J60]](#j60) [[JUG]](#jug)

| Feature | Use in no-bash-mcp |
|---|---|
| `@ParameterizedTest` + `@CsvFileSource` | golden-file fixtures for parser tests (`git_log`, `pr_view`); `numLinesToSkip=1` skips header. **FastCSV (new in 6) is strict** — fixtures must be clean-quoted, and `lineSeparator` is auto-detected (attribute removed). |
| `@MethodSource` / `@FieldSource` | multi-axis scenarios too wide for CSV (exit-code × stdout-pattern × expected-status); `@FieldSource` (new in 6) for static golden-list constants. |
| `@EnumSource`, `@ValueSource`, `@NullAndEmptySource` | reject blank/`null` working-dir args on `describe_project`/`run_tests`. |
| `@ArgumentsSource` | load saved GitHub HTTP responses (paginated, rate-limited, 404) for `pr_*` parser tests. |
| **`@TestFactory`** (dynamic) | **the universal test-result schema divergence matrix** — one `DynamicContainer` per (format × outcome × file:line-present) axis, named failures per field, no combinatorial `@ParameterizedTest` explosion. (Note: `@BeforeEach`/`@AfterEach` do **not** fire around individual dynamic tests — keep factories stateless.) |
| **`@Nested`** + `@Tag` | one outer class per verb; nested classes per scenario (clean/dirty repo, merge conflict, large diff). `@Tag("git"/"forge"/"integration"/"windows")` for Surefire filtering. |
| `@DisplayNameGeneration(ReplaceUnderscores)` | apply **globally** via `junit-platform.properties` so verb tests read as specs without per-method `@DisplayName`. |
| `assertAll` / `assertThrowsExactly` / `assertTimeoutPreemptively` | validate every envelope field together; `assertThrowsExactly` enforces the [operational-error] taxonomy boundary (no `RuntimeException` leak); `assertTimeoutPreemptively` kills orphaned `ProcessBuilder` subprocesses. |
| `@EnabledOnOs(OS.WINDOWS)` / `@EnabledIfEnvironmentVariable` | gate `.cmd` shim tests (G13) to Windows runners; gate `pr_*` forge tests on `GITHUB_TOKEN` presence. |
| `@TestInstance(PER_CLASS)` | non-static `@BeforeAll`/`@AfterAll` to own the STDIO process lifecycle in integration tests. |

> **`@ParameterizedClass`** (class-level parameterization) exists but is **experimental** in 6.1.0 —
> XML-report indices and display-name behavior are still stabilizing. **Avoid in production test code
> for v1.** [[J61]](#j61)

[operational-error]: ../../CONTEXT.md

---

## 2. MicronautTest — the STDIO-critical rules

- **`@MicronautTest(startApplication = false)` on every unit test.** The default `startApplication=true`
  starts the **STDIO message loop**, which **hijacks the test JVM's stdin/stdout** — fatal for a STDIO
  server. `false` loads the ApplicationContext (beans + injection) without the loop. This is the right
  mode for ~95% of the suite. [[MT]](#mt)
- **Field injection only** (`@Inject` on fields) — constructor injection isn't supported in JUnit test
  classes; method-parameter injection works when `resolveParameters=true`.
- **`@Property`** (there is no `@TestProperty`) drives config tests: class-level adds a property;
  method-level overrides + auto-restores via `RefreshEvent`. **`TestPropertyProvider`** (needs
  `@TestInstance(PER_CLASS)`) injects a dynamic value (e.g. the WireMock port) **before** the context
  starts.
- **`resolveParameters=false`** on any `@ParameterizedTest` class (else it conflicts with Micronaut's
  parameter resolution).
- `transactional=true` (default) is **inert** here (no datasource) — ignore it.
- **STDIO integration tests have no in-process client.** Micronaut's `McpSyncClient`/`embeddedServer`
  injection is **HTTP-only**; for STDIO, drive the **packaged binary as a subprocess** over piped
  stdin/stdout (Failsafe `verify` phase), or use MCP Inspector (§5). [[MT]](#mt) [[MM]](#mm)

---

## 3. Mocking — `@MockBean`, not `MockitoExtension` (verified)

**Use `@MicronautTest` + `@MockBean`** (returns `Mockito.mock(...)`, framework-agnostic, uses only
`mockito-core`). **Avoid `@ExtendWith(MockitoExtension.class)`** for two independent, verified reasons:

1. **JUnit 6 compat is unresolved** — Mockito issue **#3779** is open; `mockito-junit-jupiter:5.23.0`
   hard-depends on `junit-jupiter-api:5.13.4`, conflicting with JUnit 6.x on the classpath (the
   stalled fix is PR **#3781**, not #3741). [[MK1]](#mk1) [[MK2]](#mk2)
2. **`MockitoExtension` + `@MicronautTest` is itself broken** — micronaut-test issue **#78**: `@Inject`
   beans become `null`. [[MT78]](#mt78)

`@MockBean` sidesteps both (it does not use `MockitoExtension`). Add **only** `mockito-core` to test
scope (versionless — BOM-managed); **do not** add `mockito-junit-jupiter`.

```java
@MicronautTest(startApplication = false)
class RunTestsToolTest {
    @Inject RunTestsTool tool;
    @MockBean(ProcessRunnerImpl.class)              // value = IMPLEMENTATION type, not the interface
    ProcessRunner runner() { return mock(ProcessRunner.class); }
    @Test void maps_exit_code_to_status() { /* when(...).thenReturn(...) ; assertThat(...) */ }
}
```

---

## 4. Forge HTTP mocking — WireMock + the security invariants

The forge adapter makes outbound HTTP to GitHub. Tests **never hit real GitHub** and must prove the
security invariants. **WireMock 3.13.2** (in-JVM `WireMockExtension`) is the choice: declarative
`verify()` maps 1:1 onto the invariants; first-party Micronaut test-resources lineage. (Pin manually —
the BOM manages only the *containerized* `micronaut-test-resources-wiremock`, not the standalone JAR.)
[[WM]](#wm) [[MG]](#mg)

```java
@RegisterExtension static WireMockExtension wm =
    WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();
// TestPropertyProvider feeds wm.baseUrl() into forge.github.base-url before context start
```

| Invariant | Assertion |
|---|---|
| token transmitted correctly | `wm.verify(getRequestedFor(...).withHeader("Authorization", equalTo("Bearer "+TOKEN)))` |
| read-scope (no writes escape) | `wm.verify(0, postRequestedFor(anyUrl()))` |
| **SSRF base-URL rejection** | inject disallowed host → assert adapter throws **before** any call → `wm.verify(0, anyRequestedFor(anyUrl()))` proves zero requests escaped |
| **secret never logged** | Logback `ListAppender` on `io.micronaut.http.client` (logs headers at TRACE) → assert the raw token string never appears (mock-tool-independent) |
| secret never returned | plain assertion on the output envelope — no field carries the raw token |

> SSRF rejection is an **application-layer** guard (an `HttpClientFilter` / pre-call host allowlist),
> not a mock feature — see [`forge-security-model.md`](../design/forge-security-model.md). Skip
> `mockwebserver3` (Kotlin-stdlib weight, imperative API); use an embedded `@Controller` stub only for
> pure contract-shape tests. [[WM]](#wm)

---

## 5. MCP Inspector — two-tier protocol acceptance

Micronaut provides **no in-process STDIO test client**, so Inspector `--cli` is the de-facto
protocol-level acceptance path. [[INS]](#ins) [[MM]](#mm)

- **Tier 1 — dev smoke (interactive):** `npx @modelcontextprotocol/inspector java -jar target/no-bash-mcp.jar`
  (or the native binary) after each build — drives `initialize`, shows `tools/list` + `inputSchema`,
  exercises verbs. Catches **stdout-pollution** bugs (banner/logback) immediately.
- **Tier 2 — CI acceptance per verb (headless):**
  ```bash
  npx @modelcontextprotocol/inspector --cli java -jar target/no-bash-mcp.jar \
    --method tools/call --tool-name git_status | jq -e '.content[0].text != null'
  ```
  Gate on **`jq -e`**, not Inspector exit codes (undocumented on failure — verify empirically before
  relying on them). Snapshot `tools/list` `inputSchema` and diff to catch schema regressions. Runs in
  the Failsafe `verify` phase against the **packaged** artifact, not the source tree.

Inspector v0.21.2 (Apr 2026) needs Node `^22.7.5`. It is a **protocol exerciser + display surface,
not a conformance engine** ("defer deep validation to the server") — you write the schema assertions.
[[INS]](#ins)

---

## 6. AssertJ — envelope & `failures[]` assertions

AssertJ 3.27.7 (BOM-managed) for the structured envelope. [[AJ]](#aj)

- `extracting("code","message")` + `containsExactly(...)` — order+content of `failures[]` in one line,
  with actionable diffs.
- `allSatisfy(f -> ...)` — per-failure invariants without a loop.
- `usingRecursiveComparison()` — deep envelope equality without `equals()` (golden-output tests).
- `SoftAssertions.assertSoftly(...)` — accumulate all envelope-field mismatches per run. **Caveat
  (#1353):** don't nest `SoftAssertions` inside `satisfies()`/`allSatisfy()` (only the first failure
  reports) — use it at the top-level envelope check.

---

## 7. ArchUnit — enforcing the hexagonal boundary (verified)

The architecture ([`architecture-survey.md`](./architecture-survey.md)) is enforced by ArchUnit.
**Pin `com.tngtech.archunit:archunit:1.4.2` (core, test scope) — NOT `archunit-junit5`**, which pulls
JUnit Platform 1.x and is **incompatible with JUnit 6** (`NoClassDefFoundError`; no `archunit-junit6`
yet — issue #1556). ArchUnit 1.4.2 supports Java 25 (class-file major 69). Write rules as plain
Jupiter `@Test` methods. [[AU]](#au) [[AU1556]](#au1556)

```java
class ArchitectureTest {
  private final JavaClasses classes = new ClassFileImporter().importPackages("<base>");

  @Test void core_must_not_depend_on_adapters() {
    noClasses().that().resideInAPackage("..domain..")
      .should().dependOnClassesThat().resideInAPackage("..adapter..")
      .check(classes);
  }
  @Test void verb_slices_must_not_depend_on_each_other() {
    slices().matching("..verb.(*)..").should().notDependOnEachOther().check(classes);
  }
  @Test void layering_is_respected() {
    layeredArchitecture().consideringAllDependencies()          // required since 1.0
      .layer("Domain").definedBy("..domain..")
      .layer("Adapter").definedBy("..adapter..")
      .whereLayer("Domain").mayNotAccessAnyLayer("Adapter")
      .check(classes);
  }
}
```

---

## 8. Domain-record constraints from serde 3.0.0 (verified)

`micronaut-platform:5.0.2` manages **micronaut-serialization 3.0.0**, whose breaking defaults shape
domain records (cross-ref [`architecture-survey.md`](./architecture-survey.md) §3.3). [[SD3]](#sd3)

1. `micronaut.serde.deserialization.subtypes-require-default-impl` → **default `true`**: polymorphic
   hierarchies need a default impl via `@JsonTypeInfo(defaultImpl=…)` (a discriminator that resolves
   to no known subtype otherwise fails). `@JsonSubTypes` alone is not sufficient.
2. `micronaut.serde.deserialization.fail-on-null-for-primitives` → **default `true`**: model
   genuinely-optional wire fields as **boxed types** (`Integer`, `Boolean`) + `@Nullable` — bare
   primitives only for guaranteed-present fields. (Not "avoid nullable primitives" — primitives are
   never null; the constraint is "don't map *optional* fields to primitives".)
3. `Deserializer.deserialize(...)` is now **non-null**: a custom deserializer that must accept null
   **overrides `deserializeNullable()`** / implements `NullableDeserializer` (it is not "call
   `deserializeNullable()` from inside `deserialize()`").

---

## 9. Test patterns to enforce (project conventions)

- **Security tests are first-class** (guard the core contract, not optional): argv-never-a-shell-string
  (`assertThat(builder.command()).doesNotContain("sh","bash","cmd","-c")`); flag allowlist via
  `@CsvFileSource("/security/bad-flags.csv")`; `RESOURCE_BUSY` on same-target collision (ADR-0005);
  secret-never-logged (Logback `ListAppender`); SSRF rejection (`169.254.169.254`, `localhost`,
  private CIDRs); untrusted-content neutralization (P9 — strip `<tool_call>` / `System:` injection from
  forge PR bodies/commit messages). [[SEC]](#sec) [[MCP5]](#mcp5)
- **Golden-file fixtures** in `src/test/resources/fixtures/{maven,jest,go,forge}/` and `/security/` —
  tabular via `@CsvFileSource`, rich envelopes as `.json` via `@MethodSource` factory. Reviewable as a
  corpus; data evolves independently of logic.
- **Universal-schema parsing** = one `@TestFactory`/`@ParameterizedTest` over real fixtures of all
  three formats (JUnit XML, `jest --json`, `go test -json`) — the schema-divergence spike's harness
  ([`schema-divergence-map.md`](../design/schema-divergence-map.md)).
- **`@Nested` per verb; `@DisplayName` throughout; `@Tag` for CI filtering.** Surefire = `unit`; Failsafe
  = `integration,forge,windows` gated on env/OS.
- **TDD red→green→refactor via `/tdd`**; Clean Code / YAGNI / KISS.
- **JVM-first posture:** day-to-day tests on the JVM; native tests (`native:test`) are a separate,
  CI-release-only gate (see [`graalvm-native-wsl-setup.md`](./graalvm-native-wsl-setup.md)).

---

## Open items (for `DESIGN.md` / pom-wiring)

1. **JUnit 6.0.3 (BOM) vs 6.1.0 (override).** v1 = **inherit 6.0.3** (consistent with micronaut-test
   5.0.0; all needed APIs — `@TestFactory`, `@Nested`, `@FieldSource`, `@DisplayNameGeneration` — are
   in 6.0.x). Override to `junit-bom:6.1.0` (a single `dependencyManagement` import **before** the
   platform BOM) only if a 6.1-specific feature is needed. [[BOM]](#bom)
2. **Native-test in CI** — confirm the `native:test` subset and the Maven profile that triggers it; not
   all of the suite need pass under native execution (decide the gated subset).
3. **Coverage** — JaCoCo on Java 25 / GraalVM is an open decision if CI coverage-gates.

---

## Verification notes

| First-pass claim | Verified reality |
|---|---|
| "Platform BOM does NOT manage AssertJ/Mockito — pin them" | **Refuted** — `micronaut-test-bom:5.0.0` manages assertj 3.27.7, mockito 5.23.0, mockito-junit-jupiter 5.23.0, JUnit 6.0.3. **Inherit, don't pin.** [[BOM]](#bom) |
| "`@ExtendWith(MockitoExtension.class)` works in practice" | **Avoid** — Mockito#3779 (JUnit 6 unresolved) **and** micronaut-test#78 (`@Inject` null with `@MicronautTest`). Use `@MockBean`. [[MK1]](#mk1) [[MT78]](#mt78) |
| "Use `archunit-junit5`" | **Incompatible with JUnit 6** — use core `archunit:1.4.2`, rules as plain `@Test`. [[AU]](#au) |
| serde "avoid nullable primitives" | imprecise — "don't map *optional* fields to primitives"; defaultImpl for polymorphism; override `deserializeNullable()`. [[SD3]](#sd3) |

---

## References

<a id="bom"></a>[BOM] micronaut-platform 5.0.2 `libs.versions.toml` + micronaut-test-bom 5.0.0 POM — https://raw.githubusercontent.com/micronaut-projects/micronaut-platform/v5.0.2/gradle/libs.versions.toml · https://central.sonatype.com/artifact/io.micronaut.test/micronaut-test-bom/5.0.0/pom
<a id="j61"></a>[J61] JUnit 6.1.0 Release Notes — https://docs.junit.org/6.1.0/release-notes.html
<a id="j60"></a>[J60] JUnit 6.0.0 Release Notes (5→6 breaking changes) — https://docs.junit.org/6.0.0/release-notes.html
<a id="jug"></a>[JUG] JUnit 6 User Guide (parameterized, dynamic, assertions) — https://docs.junit.org/6.0.3/writing-tests/parameterized-classes-and-tests.html
<a id="mt"></a>[MT] Micronaut Test — official guide (`@MicronautTest`, `startApplication`, `@MockBean`, `@Property`, `TestPropertyProvider`) — https://micronaut-projects.github.io/micronaut-test/latest/guide/
<a id="mt78"></a>[MT78] micronaut-test #78 — `MockitoExtension` + `@MicronautTest` → null `@Inject` beans — https://github.com/micronaut-projects/micronaut-test/issues/78
<a id="mm"></a>[MM] Micronaut MCP — official guide (STDIO test path; client is HTTP-only) — https://micronaut-projects.github.io/micronaut-mcp/latest/guide/
<a id="mk1"></a>[MK1] Mockito #3779 — "Is Mockito compatible with JUnit 6?" — https://github.com/mockito/mockito/issues/3779
<a id="mk2"></a>[MK2] mockito-junit-jupiter 5.23.0 — Maven Central (hard-deps junit-jupiter-api 5.13.4) — https://central.sonatype.com/artifact/org.mockito/mockito-junit-jupiter/5.23.0
<a id="wm"></a>[WM] WireMock — download/install (3.13.2, `org.wiremock:wiremock`) — https://wiremock.org/docs/download-and-installation/
<a id="mg"></a>[MG] Micronaut Guide — Testing REST API integrations with WireMock/MockServer — https://guides.micronaut.io/latest/testing-rest-api-integrations-using-mockserver-maven-java.html
<a id="ins"></a>[INS] MCP Inspector — repo + README (`--cli`, `--method`, `--tool-name`, `--tool-arg`) — https://github.com/modelcontextprotocol/inspector · https://modelcontextprotocol.io/docs/tools/inspector
<a id="aj"></a>[AJ] AssertJ Core — official docs (3.27.x) — https://assertj.github.io/doc/
<a id="au"></a>[AU] ArchUnit — releases (1.4.2; Java 25 in 1.4.1) + user guide (`layeredArchitecture`, `slices`, `noClasses`) — https://github.com/TNG/ArchUnit/releases · https://www.archunit.org/userguide/html/000_Index.html
<a id="au1556"></a>[AU1556] ArchUnit #1556 — "Support JUnit 6.x via aligned module" (open; `archunit-junit5` pulls Platform 1.x) — https://github.com/TNG/ArchUnit/issues/1556
<a id="sd3"></a>[SD3] Micronaut Serialization 3.0.0 — config reference + breaking changes (`subtypes-require-default-impl`, `fail-on-null-for-primitives`, `Deserializer` non-null) — https://micronaut-projects.github.io/micronaut-serialization/3.0.0/guide/configurationreference.html · https://micronaut-projects.github.io/micronaut-serialization/3.0.0/guide/#breakingChanges
<a id="sec"></a>[SEC] Semgrep — Java command-injection cheat sheet — https://semgrep.dev/docs/cheat-sheets/java-command-injection
<a id="mcp5"></a>[MCP5] Testing MCP Servers: the five gates between demo and production — https://dev.to/aws-heroes/testing-mcp-servers-the-five-gates-between-demo-and-production-2inf
<a id="aj1353"></a>[AJ1353] AssertJ #1353 — SoftAssertions nested in `satisfies()`/`allSatisfy()` reports only first failure — https://github.com/joel-costigliola/assertj-core/issues/1353

---

_Verified June 2026 against the linked primary sources. Re-confirm BOM-managed versions when wiring
`pom.xml`; the JUnit/Mockito/ArchUnit↔JUnit-6 interplay is version-sensitive._
