---
name: kotlin-mcp
description: >-
  Comprehensive guide, action matrix, state-machine refactoring pipelines, and
  best practices for using the kotlin-mcp server tools (K2 AST analysis, diagnostics,
  LSP search, detekt/ktlint, and subprocess snippet execution).
---

# Kotlin MCP Server (`kotlin-mcp`) Guide & Skill

This skill provides step-by-step guidance, tool choice decision trees, state-machine pipelines, and token-optimization strategies for AI agents working with the `kotlin-mcp` server.

---

## 1. Overview & Tool Loading Modes

The `kotlin-mcp` server exposes **11 consolidated tools** (5 read-only tools and 6 mutating/edit tools) for high-performance Kotlin development.

### Eager vs. Lazy Loading Configuration

By default, `kotlin-mcp` tools should be registered **eagerly** (`"eager": true`) in your client's MCP configuration (`mcp_config.json` / `opencode.json`):

```json
{
  "mcpServers": {
    "kotlin-mcp": {
      "command": "java",
      "args": ["-jar", "/path/to/kotlin-mcp-1.1.0-all.jar"],
      "eager": true
    }
  }
}
```

- **Eager Mode (`"eager": true`, Default Recommended)**: All 11 tool definitions and JSON parameter schemas are injected directly into the LLM system prompt on startup. The LLM invokes tools in a single turn without intermediate schema lookups.
- **Lazy Mode (`"eager": false`)**: For context-sensitive environments, tool schemas are resolved on-demand to conserve prompt token window space.

---

## 2. Tool Matrix & Capabilities

### Read-Only Tools (`readOnly = true`)

| Tool | Actions / Parameters | Description | Token Cost |
| :--- | :--- | :--- | :--- |
| `kotlin_check_snippet` | `code`, `classpath` | **Diagnostics**: Fast in-process static compiler check using embedded K2 compiler. Reports line:column type errors. | Low (<200) |
| `kotlin_docs_read` | `search`, `lookup`, `explain` | **Documentation**: Query stdlib references, signature lookups, and language feature explanations. | Low-Med (Use `preset="compact"`) |
| `kotlin_code_analyze` | `inspect`, `nullability`, `coroutines`, `compose`, `file_context` | **Code Analysis**: In-memory PSI AST analysis for single-file dependencies, null safety, Compose anti-patterns, and coroutines scopes. | Low-Med |
| `kotlin_text_lsp_read` | `definition`, `references`, `completion`, `workspace_search`, `workspace_references`, `type_hierarchy`, `call_hierarchy` | **Text / LSP**: Symbol definition lookup, workspace search, reference locations, and type/call hierarchies. | Med |
| `kotlin_project_inspect` | `structure`, `kmp_targets`, `dependencies`, `diagnose_build`, `layout_inventory`, `vulnerabilities`, `package_api`, `coverage_report` | **Project**: Gradle build script inspection, KMP targets, security advisories (CVEs), package API dumping, and JaCoCo coverage summaries. | Med |

### Mutating / Edit Tools (`readOnly = false`)

| Tool | Actions / Domains / Targets | Description | Token Cost |
| :--- | :--- | :--- | :--- |
| `kotlin_docs_edit` | `register_symbol`, `register_feature`, `register_namespace` | **Doc Persistence**: Register custom documentation entries dynamically at runtime and persist to disk. | Low-Med |
| `kotlin_text_lsp_edit` | `rename` | **LSP Refactoring**: AST-based symbol renaming across snippet and workspace files in place. | Med |
| `kotlin_refactor` | `java_to_kotlin`, `functional`, `suggest_idioms`, `quick_fix`, `rxjava` | **Refactoring**: AST transformations, Java-to-Kotlin conversion, collection loop modernization, and RxJava migration. | Med |
| `kotlin_library_analyze` | `ktor`, `serialization`, `tests`, `route_map`, `arrow`, `datetime` | **Library Modernization**: Progressive discovery checks for Ktor, kotlinx.serialization, kotlinx-datetime, and Arrow refactoring. | Med |
| `kotlin_lint` | `detekt`, `format_ktlint`, `baseline_read`, `baseline_dump` | **Lint & Format**: Runs real detekt static analysis engine and KtLint code formatting. | Med-High |
| `kotlin_run` | `snippet`, `gradle_task`, `test_report` | **Execution**: Runs `fun main()` in isolated JVM subprocess, executes `./gradlew` tasks, or parses JUnit XML test reports. | High |

---

## 3. Workflows & State Machines

### A. Code Refactoring & Mutation Pipeline
Always follow this 4-step state machine when modifying existing Kotlin code:
1. **Inspect AST Context**: Run `kotlin_code_analyze(action="file_context", code=...)` to analyze single-file structure without reading raw file blobs.
2. **Dry-Run Validation**: Run `kotlin_check_snippet(code=proposedSnippet)` to verify syntax and type correctness in <50ms.
3. **Execute AST Mutation**: Call `kotlin_refactor(action="functional", code=...)` or `kotlin_text_lsp_edit(action="rename", symbol=..., workspacePath=...)`.
4. **Re-Verify Integrity**: Re-run `kotlin_check_snippet(code=updatedCode)` or `kotlin_lint(action="detekt")` to guarantee AST integrity.

### B. Fast Diagnostic Pipeline
- Avoid launching heavy `./gradlew build` tasks for minor syntax edits.
- Use `kotlin_check_snippet` first. Only run `kotlin_run(target="gradle_task", task="test")` for full multi-module integration verification.

---

## 4. Token Efficiency & Presets

- **Compact Discovery (`preset="compact"`)**: Always supply `preset="compact"` when calling `kotlin_docs_read(action="search", ...)` or `kotlin_project_inspect(action="package_api", ...)`. This strips detailed KDocs and returns single-line signatures, saving up to 90% of token budget (~350 tokens vs ~4,500 tokens).
- **Read-Only First**: Perform read/inspect calls before executing mutating write operations (`kotlin_text_lsp_edit(action="rename")`, `kotlin_lint(action="format_ktlint")`).

---

## 5. Quick Examples

```kotlin
// 1. Fast in-memory syntax & type check
kotlin_check_snippet(code = "fun main() { val numbers = listOf(1, 2, 3) }")

// 2. Find definition of a workspace symbol
kotlin_text_lsp_read(action = "definition", symbol = "UserRepository", workspacePath = "/path/to/project")

// 3. Convert imperative loop to functional pipeline
kotlin_refactor(action = "functional", code = """
    val evens = mutableListOf<Int>()
    for (n in numbers) {
        if (n % 2 == 0) evens.add(n)
    }
""")

// 4. Format workspace file with KtLint
kotlin_lint(action = "format_ktlint", code = "class MyClass { fun foo() = 42 }")
```
