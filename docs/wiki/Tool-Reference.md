# Kotlin MCP Tool & Action API Reference

This document is the authoritative, code-backed API reference for all **11 MCP tools** provided by `kotlin-mcp`.
All tools use progressive discovery with action-multiplexed parameters to minimize LLM token consumption while providing complete IDE-grade capabilities.

---

## Read-Only Tools (`readOnly = true`)

Read-only tools are safe for research, audits, and discovery. They never modify files on disk or execute untrusted host code.

### `kotlin_docs_read`

**Description:** Search and inspect Kotlin stdlib and framework documentation.

**Supported Actions:** `search`, `lookup`, `explain`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Operation: 'search' (default), 'lookup', 'explain' |
| `query` | `string` | No | Search query or target symbol/feature name for search/lookup/explain operations |
| `preset` | `string` | No | Optional response projection for lookup: 'compact' (signature only) or 'full' (default) |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths for library-aware docs |

### `kotlin_code_analyze`

**Description:** AST static analysis for Kotlin code snippets.

**Supported Actions:** `inspect`, `nullability`, `coroutines`, `compose`, `file_context`, `workmanager`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Analysis action: 'inspect' (default, declared elements), 'nullability' (unsafe null handling), 'coroutines' (scope safety & blocking calls), 'compose' (Compose anti-patterns), 'file_context' (cross-file dependencies of a target file), 'workmanager' (WorkManager & CoroutineWorker architecture) |
| `code` | `string` | No | Kotlin source code snippet to analyze, or absolute path of a .kt file for file_context |
| `workspacePath` | `string` | No | Optional workspace root directory (required for file_context) |

### `kotlin_text_lsp_read`

**Description:** AST text services: find definitions, references, completions, search workspace, trace call/type hierarchies, or hover a symbol.

**Supported Actions:** `definition`, `references`, `completion`, `workspace_search`, `workspace_references`, `type_hierarchy`, `call_hierarchy`, `hover`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | LSP action: 'definition' (default), 'references', 'completion', 'workspace_search' (fuzzy symbol search), 'workspace_references' (exact reference locations), 'type_hierarchy' (super/subtypes), 'call_hierarchy' (incoming/outgoing calls), 'hover' (resolved type, signature and KDoc) |
| `code` | `string` | No | Kotlin source code snippet context |
| `symbol` | `string` | No | Target symbol name (or prefix for completion, or query for workspace_search) |
| `workspacePath` | `string` | No | Optional root directory path of workspace (required for workspace_search/workspace_references/hierarchies) |

### `kotlin_project_inspect`

**Description:** Gradle build script, version catalog, dependencies, Maven version discovery, and project layout inspection.

**Supported Actions:** `structure`, `kmp_targets`, `dependencies`, `schema_digest`, `diagnose_build`, `layout_inventory`, `vulnerabilities`, `package_api`, `coverage_report`, `android_manifest`, `android_config`, `resolve_versions`, `latest_version`, `catalog_updates`, `android_runtime_target`, `android_audit`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Project action: 'structure' (default), 'kmp_targets', 'dependencies', 'schema_digest', 'diagnose_build', 'layout_inventory', 'vulnerabilities', 'package_api', 'coverage_report', 'android_manifest', 'android_config', 'resolve_versions', 'latest_version', 'catalog_updates', 'android_runtime_target', 'android_audit' |
| `buildScriptContent` | `string` | No | Content of build.gradle.kts (or coordinate / manifest content / source snippet) |
| `manifestContent` | `string` | No | Optional AndroidManifest.xml XML content or file path for android_runtime_target |
| `projectPath` | `string` | No | Path to Gradle project root directory (aliases: workspacePath, path) |
| `packageName` | `string` | No | Target package for package_api or category for android_audit (e.g. 'compose', 'permissions', 'r8') or Maven coordinate |
| `category` | `string` | No | Optional target audit category for android_audit: 'COMPOSE_PERFORMANCE', 'RUNTIME_PERMISSIONS', 'R8_MINIFICATION' |
| `coordinate` | `string` | No | Target Maven coordinate 'group:artifact' for resolve_versions and latest_version (e.g. io.ktor:ktor-client-core) |
| `repositoryUrl` | `string` | No | Optional custom Maven repository URL for resolve_versions/latest_version |
| `settingsContent` | `string` | No | Optional settings.gradle.kts content for diagnose_build |
| `gradlePropertiesContent` | `string` | No | Optional gradle.properties content for diagnose_build |
| `connectTimeoutMs` | `string` | No | Optional connect timeout in milliseconds for OSV vulnerability check (default: 4000) |
| `readTimeoutMs` | `string` | No | Optional read timeout in milliseconds for OSV vulnerability check (default: 6000) |
| `maxRetries` | `string` | No | Optional max retry attempts for OSV vulnerability query batch (default: 3) |

### `kotlin_check_snippet`

**Description:** Compile a Kotlin snippet with the embedded K2 compiler and report real syntax/type errors with line:column, run in-memory AST mutation testing, or perform compiler-backed semantic verification (when-exhaustiveness, value classes, contracts, expect/actual, inline-reified, opt-in, deprecated).

**Supported Actions:** `check`, `mutate`, `when_exhaustiveness`, `value_class`, `inline_reified`, `contracts`, `expect_actual`, `experimental_optin`, `deprecated`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Operation: 'check' (default, embedded compiler diagnostics), 'mutate' (in-memory AST mutation testing), 'when_exhaustiveness' (sealed/enum branch checking), 'value_class' (@JvmInline constraints), 'inline_reified' (reified generics & inline size), 'contracts' (contract blocks), 'expect_actual' (KMP multiplatform alignment), 'experimental_optin' (@RequiresOptIn/@OptIn), 'deprecated' (@Deprecated ReplaceWith) |
| `code` | `string` | **Yes** | Kotlin code snippet to compile-check, mutation-test, or semantically verify |
| `testCode` | `string` | No | Optional unit test code containing fun main() assertions to evaluate against generated mutants (used when action='mutate') |
| `preset` | `string` | No | Optional response projection for mutation reports: 'compact', 'full' (default), or 'summary' |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths added to compile classpath |
| `projectPath` | `string` | No | Optional workspace root whose compiled classes (build/classes…), generated sources, and build/libs jars are added automatically to the compile classpath (aliases: workspacePath, path) |

---

## Mutating / Edit Tools (`readOnly = false`)

Mutating tools generate code diffs, format files, rename symbols across workspaces, or execute child JVM processes.

### `kotlin_docs_edit`

**Description:** Register custom documentation entries dynamically at runtime and disk persistence.

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

**Description:** Code refactorings and compiler-diagnostic quick-fixes that produce new code.

**Supported Actions:** `suggest_idioms`, `java_to_kotlin`, `functional`, `quick_fix`, `rxjava`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Refactoring action: 'suggest_idioms' (default), 'java_to_kotlin', 'functional' (collection loops), 'quick_fix' (diagnostic diff), 'rxjava' (RxJava to coroutines) |
| `code` | `string` | **Yes** | Source code snippet |
| `diagnostic` | `string` | No | Diagnostic message for quick_fix |

### `kotlin_library_analyze`

**Description:** Library anti-pattern checks, modernization suggestions, and code-transforming refactors (e.g. Arrow, Android DI).

**Supported Actions:** `ktor`, `serialization`, `tests`, `route_map`, `arrow`, `datetime`, `android_di`, `workmanager`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Primary library analysis action: 'ktor' (default), 'serialization', 'tests', 'route_map', 'arrow', 'datetime', 'android_di', 'workmanager' |
| `domain` | `string` | No | Deprecated backward-compatible alias for 'action'. Domain alias ('ktor', 'serialization', 'tests', 'arrow', 'datetime', 'android_di', 'workmanager') |
| `code` | `string` | **Yes** | Kotlin code snippet to analyze |
| `dataSources` | `string` | No | Optional schema-diff links for serialization analysis |
| `legacy` | `string` | No | Optional 'true' for Arrow 1.x monad mode in arrow refactoring |

### `kotlin_lint`

**Description:** Detekt, KtLint, and Android Lint static analysis, baseline management, and code formatting.

**Supported Actions:** `lint`, `detekt`, `format`, `format_ktlint`, `baseline_read`, `baseline_dump`, `android_lint`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Lint action: 'lint' (default, alias: 'detekt'), 'format' (alias: 'format_ktlint'), 'baseline_read', 'baseline_dump', 'android_lint' |
| `code` | `string` | No | Kotlin source code snippet to lint or format (or XML content / file path for android_lint) |
| `workspacePath` | `string` | No | Optional root directory path of workspace |

### `kotlin_run`

**Description:** Compile and execute standalone Kotlin snippets, Gradle tasks, or test report parsers in an isolated host JVM process.

**Supported Actions:** `snippet`, `gradle_task`, `test_report`

| Parameter | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `action` | `string` | No | Execution action: 'snippet' (default), 'gradle_task', 'test_report' |
| `code` | `string` | No | Kotlin source code snippet containing a main() entry point or top-level expressions |
| `taskName` | `string` | No | Gradle task name to execute for action='gradle_task' (e.g. 'test', 'check') |
| `workspacePath` | `string` | No | Optional root directory path of project/workspace |
| `jvmArgs` | `string` | No | Optional string array of JVM arguments (allow-listed: -D, -Xms, -Xmx, --add-opens) |
| `classpath` | `Array<string>` | No | Optional array of jar/dir paths added to execution classpath |
| `timeoutSeconds` | `string` | No | Execution timeout in seconds (default: 10) |

---

[← Home](Home)
