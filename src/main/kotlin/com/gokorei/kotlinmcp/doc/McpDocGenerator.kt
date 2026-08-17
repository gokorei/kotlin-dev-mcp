package com.gokorei.kotlinmcp.doc

import java.io.File

/**
 * Specification of an MCP tool for documentation generation.
 */
data class ToolDocSpec(
    val name: String,
    val description: String,
    val readOnly: Boolean,
    val actions: List<String> = emptyList(),
    val params: List<ParamDocSpec> = emptyList(),
    val requiredParams: List<String> = emptyList(),
    val notes: String? = null
)

/**
 * Specification of an MCP tool parameter.
 */
data class ParamDocSpec(
    val name: String,
    val description: String,
    val type: String = "string",
    val itemsType: String? = null,
    val required: Boolean = false
)

/**
 * Explicit interface for generating Markdown documentation directly from in-code tool metadata.
 */
interface McpDocGenerator {
    /**
     * Generates a complete GitHub Flavored Markdown reference of all 11 MCP tools,
     * including parameter tables, actions, and usage descriptions.
     */
    fun generateToolReferenceMarkdown(): String

    /**
     * Generates a compact Markdown table summarizing all tools for inclusion in README.md.
     */
    fun generateToolSummaryTable(): String
}

/**
 * Default implementation of [McpDocGenerator] holding the single source of truth
 * for MCP tool documentation.
 */
class DefaultMcpDocGenerator : McpDocGenerator {

    val toolSpecs: List<ToolDocSpec> = listOf(
        // 1. kotlin_docs_read
        ToolDocSpec(
            name = "kotlin_docs_read",
            description = "Search and inspect Kotlin standard library documentation, symbol signatures, and language feature explanations.",
            readOnly = true,
            actions = listOf("search", "lookup", "explain"),
            params = listOf(
                ParamDocSpec("action", "Operation: 'search' (default), 'lookup', 'explain'", "string"),
                ParamDocSpec("query", "Search query or target symbol/feature name for search/lookup/explain operations", "string"),
                ParamDocSpec("preset", "Optional response projection for lookup: 'compact' (signature only) or 'full' (default)", "string"),
                ParamDocSpec("classpath", "Optional array of jar/dir paths for library-aware docs", "array", "string")
            )
        ),
        // 2. kotlin_code_analyze
        ToolDocSpec(
            name = "kotlin_code_analyze",
            description = "AST static analysis for Kotlin source code snippets and files without running external Gradle daemons.",
            readOnly = true,
            actions = listOf("inspect", "nullability", "coroutines", "compose", "file_context"),
            params = listOf(
                ParamDocSpec("action", "Analysis action: 'inspect' (default, declared elements), 'nullability' (unsafe null handling), 'coroutines' (scope safety & blocking calls), 'compose' (Compose anti-patterns), 'file_context' (cross-file dependencies of a target file)", "string"),
                ParamDocSpec("code", "Kotlin source code snippet to analyze, or absolute path of a .kt file for file_context", "string"),
                ParamDocSpec("workspacePath", "Optional workspace root directory (required for file_context)", "string")
            )
        ),
        // 3. kotlin_text_lsp_read
        ToolDocSpec(
            name = "kotlin_text_lsp_read",
            description = "AST-backed text services: find symbol definitions, references, completions, fuzzy workspace search, and call/type hierarchies.",
            readOnly = true,
            actions = listOf("definition", "references", "completion", "workspace_search", "workspace_references", "type_hierarchy", "call_hierarchy"),
            params = listOf(
                ParamDocSpec("action", "LSP action: 'definition' (default), 'references', 'completion', 'workspace_search' (fuzzy symbol search), 'workspace_references' (exact reference locations), 'type_hierarchy' (super/subtypes), 'call_hierarchy' (incoming/outgoing calls)", "string"),
                ParamDocSpec("code", "Kotlin source code snippet context", "string"),
                ParamDocSpec("symbol", "Target symbol name (or prefix for completion, or query for workspace_search)", "string"),
                ParamDocSpec("workspacePath", "Optional root directory path of workspace (required for workspace_search/workspace_references/hierarchies)", "string")
            )
        ),
        // 4. kotlin_project_inspect
        ToolDocSpec(
            name = "kotlin_project_inspect",
            description = "Gradle build script analysis, multiplatform targets, dependency audits, security advisories, API/DB schema digests, and coverage reports.",
            readOnly = true,
            actions = listOf("structure", "kmp_targets", "dependencies", "schema_digest", "diagnose_build", "layout_inventory", "vulnerabilities", "package_api", "coverage_report"),
            params = listOf(
                ParamDocSpec("action", "Inspection action: 'structure' (default, plugins & source sets), 'kmp_targets', 'dependencies', 'schema_digest' (SQL DDL, Exposed tables, @Serializable DTOs, OpenAPI), 'diagnose_build', 'layout_inventory', 'vulnerabilities', 'package_api', 'coverage_report'", "string"),
                ParamDocSpec("buildScriptContent", "Content of build.gradle.kts", "string"),
                ParamDocSpec("projectPath", "Path to Gradle project root directory (aliases: workspacePath, path)", "string"),
                ParamDocSpec("packageName", "Target package for package_api (e.g. com.example.app)", "string"),
                ParamDocSpec("settingsContent", "Optional settings.gradle.kts content for diagnose_build", "string"),
                ParamDocSpec("gradlePropertiesContent", "Optional gradle.properties content for diagnose_build", "string"),
                ParamDocSpec("connectTimeoutMs", "Optional connect timeout in milliseconds for OSV vulnerability check (default: 4000)", "string"),
                ParamDocSpec("readTimeoutMs", "Optional read timeout in milliseconds for OSV vulnerability check (default: 6000)", "string"),
                ParamDocSpec("maxRetries", "Optional max retry attempts for OSV vulnerability query batch (default: 3)", "string")
            )
        ),
        // 5. kotlin_check_snippet
        ToolDocSpec(
            name = "kotlin_check_snippet",
            description = "Compile a Kotlin snippet with the embedded K2 compiler and report real syntax/type errors with line:column positions.",
            readOnly = true,
            actions = emptyList(),
            params = listOf(
                ParamDocSpec("code", "Kotlin code snippet to compile-check", "string", required = true),
                ParamDocSpec("classpath", "Optional array of jar/dir paths added to compile classpath", "array", "string")
            ),
            requiredParams = listOf("code")
        ),
        // 6. kotlin_docs_edit
        ToolDocSpec(
            name = "kotlin_docs_edit",
            description = "Register custom documentation entries dynamically at runtime and persist them to disk.",
            readOnly = false,
            actions = listOf("register_symbol", "register_feature", "register_namespace"),
            params = listOf(
                ParamDocSpec("action", "Operation: 'register_symbol' (default), 'register_feature', 'register_namespace'", "string"),
                ParamDocSpec("name", "Target name/prefix for register operations", "string", required = true),
                ParamDocSpec("content", "Markdown documentation content for register operations", "string")
            ),
            requiredParams = listOf("name")
        ),
        // 7. kotlin_text_lsp_edit
        ToolDocSpec(
            name = "kotlin_text_lsp_edit",
            description = "AST-based symbol renaming across snippet and workspace files in place.",
            readOnly = false,
            actions = listOf("rename"),
            params = listOf(
                ParamDocSpec("action", "LSP action: 'rename' (default)", "string"),
                ParamDocSpec("code", "Kotlin source code snippet context", "string"),
                ParamDocSpec("oldName", "Current symbol name for rename", "string", required = true),
                ParamDocSpec("newName", "New symbol name for rename", "string", required = true),
                ParamDocSpec("workspacePath", "Optional root directory path of workspace", "string")
            ),
            requiredParams = listOf("oldName", "newName")
        ),
        // 8. kotlin_refactor
        ToolDocSpec(
            name = "kotlin_refactor",
            description = "Automated AST code refactorings, Java-to-Kotlin translation, imperative loop modernization, idiom suggestions, and RxJava migration.",
            readOnly = false,
            actions = listOf("java_to_kotlin", "functional", "suggest_idioms", "quick_fix", "rxjava"),
            params = listOf(
                ParamDocSpec("action", "Refactoring action: 'java_to_kotlin' (default), 'functional' (collection loops to map/filter), 'suggest_idioms', 'quick_fix' (compiler diagnostic fixes), 'rxjava' (RxJava to Kotlin Coroutines/Flow)", "string"),
                ParamDocSpec("code", "Source code context or snippet to refactor", "string"),
                ParamDocSpec("filePath", "Optional file path for quick_fix diagnostic resolution", "string"),
                ParamDocSpec("fixType", "Optional fix type for quick_fix (e.g. 'UNRESOLVED_REFERENCE')", "string")
            )
        ),
        // 9. kotlin_library_analyze
        ToolDocSpec(
            name = "kotlin_library_analyze",
            description = "Library anti-pattern checks, framework modernization suggestions, and code-transforming refactors (Ktor, Arrow, kotlinx.serialization, kotlinx-datetime).",
            readOnly = false,
            actions = listOf("ktor", "serialization", "tests", "route_map", "arrow", "datetime"),
            params = listOf(
                ParamDocSpec("action", "Library check domain: 'ktor' (Ktor plugins & config), 'serialization' (kotlinx.serialization rules), 'tests' (test hygiene), 'route_map' (HTTP routes), 'arrow' (Arrow FP refactoring), 'datetime' (kotlinx-datetime)", "string"),
                ParamDocSpec("code", "Kotlin code snippet to analyze/refactor", "string"),
                ParamDocSpec("domain", "Domain alias for action ('ktor', 'serialization', 'tests', 'arrow', 'datetime')", "string"),
                ParamDocSpec("workspacePath", "Optional workspace root directory to detect framework dependencies", "string")
            )
        ),
        // 10. kotlin_lint
        ToolDocSpec(
            name = "kotlin_lint",
            description = "In-process Detekt static analysis and KtLint code formatting running in isolated worker classloaders.",
            readOnly = false,
            actions = listOf("detekt", "format_ktlint", "baseline_read", "baseline_dump"),
            params = listOf(
                ParamDocSpec("action", "Linter action: 'detekt' (run detekt static analysis rules), 'format_ktlint' (run ktlint formatter), 'baseline_read' (parse detekt baseline XML), 'baseline_dump' (create/update detekt baseline XML)", "string"),
                ParamDocSpec("code", "Kotlin code snippet to lint or format in memory", "string"),
                ParamDocSpec("filePath", "Optional file path context or target file to format in place", "string"),
                ParamDocSpec("configFile", "Optional path to custom detekt.yml config file", "string"),
                ParamDocSpec("baselineFile", "Optional path to detekt baseline XML file", "string"),
                ParamDocSpec("ruleset", "Optional rule set filter for detekt (e.g. 'complexity', 'style', 'naming')", "string"),
                ParamDocSpec("workspacePath", "Optional workspace root directory path", "string")
            )
        ),
        // 11. kotlin_run
        ToolDocSpec(
            name = "kotlin_run",
            description = "Compile and execute standalone Kotlin snippets, Gradle tasks, or test report parsers in isolated host JVM subprocesses.",
            readOnly = false,
            actions = listOf("snippet", "gradle_task", "test_report"),
            params = listOf(
                ParamDocSpec("action", "Execution action: 'snippet' (default, run standalone fun main()), 'gradle_task' (run ./gradlew <task>), 'test_report' (parse JUnit XML test results)", "string"),
                ParamDocSpec("code", "Kotlin code containing fun main() to compile and execute", "string"),
                ParamDocSpec("taskName", "Gradle task name to execute for gradle_task", "string"),
                ParamDocSpec("reportPath", "Directory path containing JUnit XML test report files for test_report", "string"),
                ParamDocSpec("workspacePath", "Optional workspace root directory path", "string"),
                ParamDocSpec("timeoutSeconds", "Process execution timeout in seconds (default: 30s)", "string"),
                ParamDocSpec("jvmArgs", "Optional space- or comma-separated JVM arguments passed to subprocess", "string"),
                ParamDocSpec("programArgs", "Optional program arguments passed to fun main(args)", "string"),
                ParamDocSpec("classpath", "Optional array of jar/dir paths added to execution classpath", "array", "string")
            )
        )
    )

    override fun generateToolReferenceMarkdown(): String = buildString {
        appendLine("# Kotlin MCP Tool & Action API Reference")
        appendLine()
        appendLine("This document is the authoritative, code-backed API reference for all **11 MCP tools** provided by `kotlin-mcp`.")
        appendLine("All tools use progressive discovery with action-multiplexed parameters to minimize LLM token consumption while providing complete IDE-grade capabilities.")
        appendLine()
        appendLine("---")
        appendLine()

        val readOnlyTools = toolSpecs.filter { it.readOnly }
        val mutatingTools = toolSpecs.filter { !it.readOnly }

        appendLine("## Read-Only Tools (`readOnly = true`)")
        appendLine()
        appendLine("Read-only tools are safe for research, audits, and discovery. They never modify files on disk or execute untrusted host code.")
        appendLine()
        readOnlyTools.forEach { appendToolSection(it) }

        appendLine("---")
        appendLine()
        appendLine("## Mutating / Edit Tools (`readOnly = false`)")
        appendLine()
        appendLine("Mutating tools generate code diffs, format files, rename symbols across workspaces, or execute child JVM processes.")
        appendLine()
        mutatingTools.forEach { appendToolSection(it) }

        appendLine("---")
        appendLine()
        appendLine("[← Home](Home)")
    }

    private fun StringBuilder.appendToolSection(tool: ToolDocSpec) {
        appendLine("### `${tool.name}`")
        appendLine()
        appendLine("**Description:** ${tool.description}")
        appendLine()
        if (tool.actions.isNotEmpty()) {
            appendLine("**Supported Actions:** ${tool.actions.joinToString(", ") { "`$it`" }}")
            appendLine()
        }
        appendLine("| Parameter | Type | Required | Description |")
        appendLine("| :--- | :--- | :--- | :--- |")
        tool.params.forEach { param ->
            val reqStr = if (param.required || tool.requiredParams.contains(param.name)) "**Yes**" else "No"
            val typeStr = if (param.type == "array" && param.itemsType != null) "Array<${param.itemsType}>" else param.type
            appendLine("| `${param.name}` | `$typeStr` | $reqStr | ${param.description} |")
        }
        appendLine()
    }

    override fun generateToolSummaryTable(): String = buildString {
        appendLine("| Tool Name | Actions / Targets | Description |")
        appendLine("| :--- | :--- | :--- |")
        toolSpecs.forEach { tool ->
            val actionsStr = if (tool.actions.isNotEmpty()) tool.actions.joinToString(", ") { "`$it`" } else "*(Direct)*"
            val shortDesc = tool.description.substringBefore(".")
            appendLine("| `${tool.name}` | $actionsStr | **${if (tool.readOnly) "Read-Only" else "Mutating"}**: $shortDesc. |")
        }
    }
}

/**
 * CLI entrypoint for Gradle task `generateMcpDocs`.
 */
fun main(args: Array<String>) {
    val generator = DefaultMcpDocGenerator()
    val outputWikiFile = File(args.getOrNull(0) ?: "docs/wiki/Tool-Reference.md")
    outputWikiFile.parentFile?.mkdirs()
    outputWikiFile.writeText(generator.generateToolReferenceMarkdown())
    println("Generated MCP Tool Reference: ${outputWikiFile.absolutePath}")
}
