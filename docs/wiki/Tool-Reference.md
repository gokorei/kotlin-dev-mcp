# Kotlin MCP Tool & Action API Reference

This document is the authoritative, code-backed API reference for all **11 MCP tools** provided by `kotlin-mcp`.
All tools use progressive discovery with action-multiplexed parameters to minimize LLM token consumption while providing complete IDE-grade capabilities.

---

## Read-Only Tools (`readOnly = true`)

Read-only tools are safe for research, audits, and discovery. They never modify files on disk or execute untrusted host code.

### `kotlin_docs_read`

**Description:** Search and inspect Kotlin standard library documentation, symbol signatures, and language feature explanations.

**Supported Actions:** `search`, `lookup`, `explain`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Operation: 'search' (default), 'lookup', 'explain' |
| `query` | `string` | No | Search query or target symbol/feature name for search/lookup/explain operations |
| `preset` | `string` | No | Optional response projection for lookup: 'compact' (signature only) or 'full' (default) |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths for library-aware docs |

### `kotlin_code_analyze`

**Description:** AST static analysis for Kotlin source code snippets and files without running external Gradle daemons.

**Supported Actions:** `inspect`, `nullability`, `coroutines`, `compose`, `file_context`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Analysis action: 'inspect' (default, declared elements), 'nullability' (unsafe null handling), 'coroutines' (scope safety & blocking calls), 'compose' (Compose anti-patterns), 'file_context' (cross-file dependencies of a target file) |
| `code` | `string` | No | Kotlin source code snippet to analyze, or absolute path of a .kt file for file_context |
| `workspacePath` | `string` | No | Optional workspace root directory (required for file_context) |

### `kotlin_text_lsp_read`

**Description:** AST-backed text services: find symbol definitions, references, completions, fuzzy workspace search, and call/type hierarchies.

**Supported Actions:** `definition`, `references`, `completion`, `workspace_search`, `workspace_references`, `type_hierarchy`, `call_hierarchy`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | LSP action: 'definition' (default), 'references', 'completion', 'workspace_search' (fuzzy symbol search), 'workspace_references' (exact reference locations), 'type_hierarchy' (super/subtypes), 'call_hierarchy' (incoming/outgoing calls) |
| `code` | `string` | No | Kotlin source code snippet context |
| `symbol` | `string` | No | Target symbol name (or prefix for completion, or query for workspace_search) |
| `workspacePath` | `string` | No | Optional root directory path of workspace (required for workspace_search/workspace_references/hierarchies) |

### `kotlin_project_inspect`

**Description:** Gradle build script analysis, multiplatform targets, dependency audits, security advisories, API/DB schema digests, and coverage reports.

**Supported Actions:** `structure`, `kmp_targets`, `dependencies`, `schema_digest`, `diagnose_build`, `layout_inventory`, `vulnerabilities`, `package_api`, `coverage_report`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Inspection action: 'structure' (default, plugins & source sets), 'kmp_targets', 'dependencies', 'schema_digest' (SQL DDL, Exposed tables, @Serializable DTOs, OpenAPI), 'diagnose_build', 'layout_inventory', 'vulnerabilities', 'package_api', 'coverage_report' |
| `buildScriptContent` | `string` | No | Content of build.gradle.kts |
| `projectPath` | `string` | No | Path to Gradle project root directory (aliases: workspacePath, path) |
| `packageName` | `string` | No | Target package for package_api (e.g. com.example.app) |
| `settingsContent` | `string` | No | Optional settings.gradle.kts content for diagnose_build |
| `gradlePropertiesContent` | `string` | No | Optional gradle.properties content for diagnose_build |
| `connectTimeoutMs` | `string` | No | Optional connect timeout in milliseconds for OSV vulnerability check (default: 4000) |
| `readTimeoutMs` | `string` | No | Optional read timeout in milliseconds for OSV vulnerability check (default: 6000) |
| `maxRetries` | `string` | No | Optional max retry attempts for OSV vulnerability query batch (default: 3) |

### `kotlin_check_snippet`

**Description:** Compile a Kotlin snippet with the embedded K2 compiler and report real syntax/type errors with line:column positions.

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `code` | `string` | **Yes** | Kotlin code snippet to compile-check |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths added to compile classpath |

---

## Mutating / Edit Tools (`readOnly = false`)

Mutating tools generate code diffs, format files, rename symbols across workspaces, or execute child JVM processes.

### `kotlin_docs_edit`

**Description:** Register custom documentation entries dynamically at runtime and persist them to disk.

**Supported Actions:** `register_symbol`, `register_feature`, `register_namespace`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Operation: 'register_symbol' (default), 'register_feature', 'register_namespace' |
| `name` | `string` | **Yes** | Target name/prefix for register operations |
| `content` | `string` | No | Markdown documentation content for register operations |

### `kotlin_text_lsp_edit`

**Description:** AST-based symbol renaming across snippet and workspace files in place.

**Supported Actions:** `rename`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | LSP action: 'rename' (default) |
| `code` | `string` | No | Kotlin source code snippet context |
| `oldName` | `string` | **Yes** | Current symbol name for rename |
| `newName` | `string` | **Yes** | New symbol name for rename |
| `workspacePath` | `string` | No | Optional root directory path of workspace |

### `kotlin_refactor`

**Description:** Automated AST code refactorings, Java-to-Kotlin translation, imperative loop modernization, idiom suggestions, and RxJava migration.

**Supported Actions:** `java_to_kotlin`, `functional`, `suggest_idioms`, `quick_fix`, `rxjava`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Refactoring action: 'java_to_kotlin' (default), 'functional' (collection loops to map/filter), 'suggest_idioms', 'quick_fix' (compiler diagnostic fixes), 'rxjava' (RxJava to Kotlin Coroutines/Flow) |
| `code` | `string` | No | Source code context or snippet to refactor |
| `filePath` | `string` | No | Optional file path for quick_fix diagnostic resolution |
| `fixType` | `string` | No | Optional fix type for quick_fix (e.g. 'UNRESOLVED_REFERENCE') |

### `kotlin_library_analyze`

**Description:** Library anti-pattern checks, framework modernization suggestions, and code-transforming refactors (Ktor, Arrow, kotlinx.serialization, kotlinx-datetime).

**Supported Actions:** `ktor`, `serialization`, `tests`, `route_map`, `arrow`, `datetime`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Library check domain: 'ktor' (Ktor plugins & config), 'serialization' (kotlinx.serialization rules), 'tests' (test hygiene), 'route_map' (HTTP routes), 'arrow' (Arrow FP refactoring), 'datetime' (kotlinx-datetime) |
| `code` | `string` | No | Kotlin code snippet to analyze/refactor |
| `domain` | `string` | No | Domain alias for action ('ktor', 'serialization', 'tests', 'arrow', 'datetime') |
| `workspacePath` | `string` | No | Optional workspace root directory to detect framework dependencies |

### `kotlin_lint`

**Description:** In-process Detekt static analysis and KtLint code formatting running in isolated worker classloaders.

**Supported Actions:** `detekt`, `format_ktlint`, `baseline_read`, `baseline_dump`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Linter action: 'detekt' (run detekt static analysis rules), 'format_ktlint' (run ktlint formatter), 'baseline_read' (parse detekt baseline XML), 'baseline_dump' (create/update detekt baseline XML) |
| `code` | `string` | No | Kotlin code snippet to lint or format in memory |
| `filePath` | `string` | No | Optional file path context or target file to format in place |
| `configFile` | `string` | No | Optional path to custom detekt.yml config file |
| `baselineFile` | `string` | No | Optional path to detekt baseline XML file |
| `ruleset` | `string` | No | Optional rule set filter for detekt (e.g. 'complexity', 'style', 'naming') |
| `workspacePath` | `string` | No | Optional workspace root directory path |

### `kotlin_run`

**Description:** Compile and execute standalone Kotlin snippets, Gradle tasks, or test report parsers in isolated host JVM subprocesses.

**Supported Actions:** `snippet`, `gradle_task`, `test_report`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Execution action: 'snippet' (default, run standalone fun main()), 'gradle_task' (run ./gradlew <task>), 'test_report' (parse JUnit XML test results) |
| `code` | `string` | No | Kotlin code containing fun main() to compile and execute |
| `taskName` | `string` | No | Gradle task name to execute for gradle_task |
| `reportPath` | `string` | No | Directory path containing JUnit XML test report files for test_report |
| `workspacePath` | `string` | No | Optional workspace root directory path |
| `timeoutSeconds` | `string` | No | Process execution timeout in seconds (default: 30s) |
| `jvmArgs` | `string` | No | Optional space- or comma-separated JVM arguments passed to subprocess |
| `programArgs` | `string` | No | Optional program arguments passed to fun main(args) |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths added to execution classpath |

---

[← Home](Home)
