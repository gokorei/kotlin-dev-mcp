# Release Notes

Overview of new features, bug fixes, and improvements shipped in each `kotlin-mcp` release.

---

## Next

### New Features

- **In-code MCP tool reference generator** — added `McpDocGenerator` and `./gradlew generateMcpDocs` task to generate `docs/wiki/Tool-Reference.md` directly from in-code tool definitions and metadata.
- **CI documentation sync test** — added `DocumentationSyncTest` enforcing that committed Markdown wiki docs match in-code tool definitions during `./gradlew test`.

### Bug Fixes

- **`kotlin_run` could report an empty result despite the snippet printing output** — the host-JVM runner now joins the output-drain thread before reading the captured stdout/stderr, eliminating a race where a fast snippet's output was lost.

- **Snippet imports resolve under `java -jar <all.jar>` fat-jar launch** — `SnippetCompiler` now falls back to a build-time-dumped, resource-bundled set of the library jars snippets may import (kotlin-stdlib, kotlinx-coroutines/-serialization/-datetime, arrow-core, mockk, turbine, ktor) when the JVM's `java.class.path` contains no matching entries (i.e. the single flat fat jar). The jars are materialized to a temp dir at first use and reused for both `kotlin_check_snippet` compilation and `kotlin_run` execution, eliminating spurious `unresolved reference` errors and runtime failures under fat-jar deployment.

- **Corrected inaccurate built-in docs entries** — reviewed against JUnit, tailrec, coroutine, and channel semantics: instance `@BeforeAll`/`@AfterAll` under default `PER_METHOD` lifecycle now correctly described as throwing a JUnit Jupiter configuration error requiring static/`companion object` + `@JvmStatic` (permitted as instance methods under `PER_CLASS`, not "silently ignored"); `tailrec` no longer claims a single tail-call limit (branching recursion is legal); `Channel` described as a point-to-point queue (not a broadcast); the `async` barrier samples now compile inside `coroutineScope { }` with `async { }` blocks instead of invalid `x.async()` calls.

- **Expanded built-in docs registry** — added 11 feature entries (GoF→idiomatic Kotlin pattern mapping, Kotlin DSL builder recipe, cooperative cancellation, structured concurrency/`supervisorScope`, Flow cold semantics + `buffer`/`conflate` backpressure, start-all-then-await barrier (`awaitAll`), biased `select` vs `selectUnbiased`, sealed+`Nothing` algebraic data types, `require` vs `check`, `@Serializable` DTO requirements) and 11 symbol entries (`kotlin.Nothing`, `require`/`check`/`requireNotNull`/`checkNotNull`, `supervisorScope`, `select`/`selectUnbiased`, `awaitAll`, `@BeforeAll`, `tailrec`). All served via `kotlin_docs_read` (search/lookup/explain) and the `kotlin://docs/{kind}/{name}` resource template, with `DocServiceTest` coverage. Also expanded `src/main/resources/docs/coroutines.md` to cover cancellation, exception propagation, backpressure, barrier, and select bias.
- **Tool documentation schema single source of truth** — updated `McpDocGenerator` to derive tool specifications and parameter metadata directly from `ToolRegistrar`, ensuring 100% synchronization and adding automated parameter metadata verification in `McpDocGeneratorTest`.

- **Dokka 2.2.0 integration** — applied `org.jetbrains.dokka` plugin and registered `dokkaDocs` task to generate KDoc API documentation automatically.
- **Explicit API mode and Binary Compatibility Validator** — enabled `kotlin.explicitApiWarning()` and applied `org.jetbrains.kotlinx.binary-compatibility-validator` (BCV) with `./gradlew apiCheck` and `api/kotlin-mcp.api` baseline dump.
- **Reorganized README structure** — replaced dense top architecture section with concise, human-friendly Key Features and moved detailed technical architecture lower down.
- **Clarified project status in README.md** — removed unofficial claim of being an official server.
- **Updated contribution status** — specified in `README.md` that public contributions are locked pending community interest.
- **Agent release-note directive** — all codebase changes must now update the next-release section of this page, and a `## Next` section is created automatically if none exists yet.
- **Enriched release notes** — updated v1.0.0 release notes with tool call details, stdio transport safety, linter process isolation, and security controls.
- **Dedicated Configuration wiki page** — created `Configuration.md` detailing system properties, environment variables, offline/air-gapped operation, and MCP tool loading modes.

---

## v1.0.0 — 2026-08-16

### New Features

- **Action-multiplexed tool suite** — 11 tools covering documentation (`kotlin_docs_read`/`edit`), embedded K2 compiler diagnostics (`kotlin_check_snippet`), static AST analysis (`kotlin_code_analyze`), LSP text services (`kotlin_text_lsp_read`/`edit`), project inspection (`kotlin_project_inspect`), library checks (`kotlin_library_analyze`), detekt/ktlint linters (`kotlin_lint`), AST refactoring (`kotlin_refactor`), and JVM execution runner (`kotlin_run`).
- **Embedded K2 compiler** (`SnippetCompiler`, `kotlin-compiler-embeddable`) — in-process static type checking and diagnostics without external Gradle daemons.
- **Isolated detekt and ktlint backends** — executed in dedicated worker JVM subprocesses on isolated tooling classpaths to power `kotlin_lint` without classloader interference.
- **Host JVM process runner** (`kotlin_run`) — executes Kotlin snippets, Gradle tasks, and JUnit XML test report parsing.
- **Progressive discovery** — automatic workspace dependency detection (`build.gradle.kts` / `libs.versions.toml`) with framework-aware tool descriptions.
- **MCP protocol extensions** — bundled stdlib documentation (`kotlin://docs/index.md`) and architecture guidelines (`kotlin://guidelines/architecture.md`) exposed as Markdown resources, plus `kotlin-task` and `kotlin-architecture` guidance prompts.
- **Context injection tools** — dependency and API/DB schema digests.
- **Built-in agent skill** ([`skills/kotlin-mcp/SKILL.md`](file:///Users/davymaddelein/Documents/kotlin-mcp/skills/kotlin-mcp/SKILL.md)) — operational guidance, action matrices, and refactoring pipelines for AI agents.
- **CI, release, and wiki workflows** — GitHub Actions automation with issue and pull request templates.

### Bug Fixes

> Initial release — non-breaking design fixes consolidated into initial shipping state.

### Improvements & Security

- **Stdio transport safety** — all server logging strictly isolated to `stderr` (`kotlin-logging-jvm` + `slf4j-simple`), keeping `stdout` clean for JSON-RPC transport frames.
- **K2 PSI AST traversal** — replaced brittle regex and multiline pattern matching across all code analysis tools with embeddable K2 PSI AST visitors.
- **Sandboxed network audits** — network security audits in project inspection disabled by default (`kmcp.disable_network_audits`), guaranteeing safe offline/isolated runs.
- **Hardened Maven dependency resolution** — handles malformed artifact versions and pre-release markers gracefully during library analysis.

---

[← Home](Home)