package com.gokorei.kotlinmcp.server

import com.gokorei.kotlinmcp.doc.DocService
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents
import java.net.URLEncoder

/**
 * Exposes the bundled Kotlin documentation index as MCP resources, following
 * progressive discovery: a small curated set of resources (index + guidelines)
 * plus a URI template for direct access to any entry by name. Individual stdlib
 * entries are NOT bulk-registered as one-resource-per-entry (that would surface
 * thousands of resources on every client connect and defeat progressive
 * discovery); the index lists them and the template reads them on demand.
 */
object ResourceRegistrar {

    const val DOCS_INDEX_URI = "kotlin://docs/index.md"
    const val GUIDELINES_URI = "kotlin://guidelines/architecture.md"
    const val RESILIENCE_GUIDELINES_URI = "kotlin://guidelines/resilience.md"
    const val KMP_STORAGE_GUIDELINES_URI = "kotlin://guidelines/kmp-storage.md"
    val SERVER_GUIDE_URI = LlmGuidance.LLM_GUIDE_RESOURCE_URI
    private const val MIME = "text/markdown"

    internal val architectureGuidelinesText: String get() = GUIDELINES_TEXT
    internal val resilienceGuidelinesText: String get() = RESILIENCE_TEXT
    internal val kmpStorageGuidelinesText: String get() = KMP_STORAGE_TEXT

    fun registerAll(server: Server, docService: DocService) {
        server.addResource(
            uri = SERVER_GUIDE_URI,
            name = LlmGuidance.LLM_GUIDE_RESOURCE_NAME,
            mimeType = MIME,
            description = "Operational guidance for LLMs using the Kotlin MCP server. Covers tool selection, read/edit separation, efficiency, and write safety."
        ) { _ ->
            ReadResourceResult(listOf(TextResourceContents(text = LlmGuidance.buildLlmUsageGuide(), uri = SERVER_GUIDE_URI, mimeType = MIME)))
        }

        server.addResource(
            uri = GUIDELINES_URI,
            name = "kotlin-guidelines-architecture",
            mimeType = MIME,
            description = "Architectural and testability guidelines for Kotlin: UI vs business-logic boundary isolation, explicit DTO-to-domain mapping, boundary testability, and domain error modeling."
        ) { _ ->
            ReadResourceResult(listOf(TextResourceContents(text = GUIDELINES_TEXT, uri = GUIDELINES_URI, mimeType = MIME)))
        }

        server.addResource(
            uri = RESILIENCE_GUIDELINES_URI,
            name = "kotlin-guidelines-resilience",
            mimeType = MIME,
            description = "Resilience and fault-tolerance guidelines for Kotlin: independent verification probing, verifiable state caching, and deterministic remediation state machines."
        ) { _ ->
            ReadResourceResult(listOf(TextResourceContents(text = RESILIENCE_TEXT, uri = RESILIENCE_GUIDELINES_URI, mimeType = MIME)))
        }

        server.addResource(
            uri = DOCS_INDEX_URI,
            name = "kotlin-docs-index",
            mimeType = MIME,
            description = "Index of every Kotlin documentation resource available on this server (stdlib symbols and language features), with links to each entry."
        ) { _ ->
            ReadResourceResult(listOf(TextResourceContents(text = buildIndex(docService), uri = DOCS_INDEX_URI, mimeType = MIME)))
        }

        server.addResourceTemplate(
            uriTemplate = "kotlin://guidelines/{name}",
            name = "kotlin-guidelines-entry",
            mimeType = MIME,
            description = "Read a specialized guideline document by name (e.g. kotlin://guidelines/kmp-storage.md)."
        ) { _, variables ->
            val rawName = variables["name"].orEmpty()
            val name = rawName.removeSuffix(".md")
            val text = when (name) {
                "architecture" -> GUIDELINES_TEXT
                "resilience" -> RESILIENCE_TEXT
                "kmp-storage" -> KMP_STORAGE_TEXT
                else -> throw IllegalArgumentException("No guideline found for '$rawName'.")
            }
            ReadResourceResult(
                listOf(TextResourceContents(text = text, uri = "kotlin://guidelines/$rawName", mimeType = MIME))
            )
        }

        server.addResourceTemplate(
            uriTemplate = "kotlin://docs/{kind}/{name}",
            name = "kotlin-doc-entry",
            mimeType = MIME,
            description = "Read a single Kotlin documentation entry. {kind} is 'symbol' or 'feature'; {name} is the entry key (spaces may be %20-encoded, e.g. kotlin://docs/feature/sealed%20interface)."
        ) { _, variables ->
            val kind = variables["kind"]
            val rawName = variables["name"].orEmpty()
            val name = runCatching { java.net.URLDecoder.decode(rawName, Charsets.UTF_8.name()) }.getOrDefault(rawName)
            val doc = kind?.let { docService.docFor(it, name) }
                ?: throw IllegalArgumentException("No documentation entry '$name' of kind '$kind'.")
            ReadResourceResult(
                listOf(TextResourceContents(text = doc, uri = "kotlin://docs/$kind/$rawName", mimeType = MIME))
            )
        }
    }

    private fun buildIndex(docService: DocService): String = buildString {
        // Individual appendLine calls are used instead of multiline raw strings (with trimIndent())
        // to avoid runtime string-trimming overhead and potential indentation calculation issues during multiline interpolation.
        appendLine("# Kotlin Documentation Index")
        appendLine()
        appendLine("Resources are available at `kotlin://docs/symbol/<name>` and `kotlin://docs/feature/<name>`.")
        appendLine()
        appendLine("## Guidelines")
        appendLine("- [Architecture & Testability]($GUIDELINES_URI)")
        appendLine("- [Backend Resilience & Fault Tolerance]($RESILIENCE_GUIDELINES_URI)")
        appendLine()
        appendLine("## Symbols")
        docService.symbolDocs.keys.sorted().forEach { appendLine("- [$it](kotlin://docs/symbol/${encode(it)})") }
        appendLine()
        appendLine("## Features")
        docService.featureDocs.keys.sorted().forEach { appendLine("- [$it](kotlin://docs/feature/${encode(it)})") }
    }

    private fun encode(name: String): String = URLEncoder.encode(name, Charsets.UTF_8.name()).replace("+", "%20")

    private val GUIDELINES_TEXT: String by lazy {
        ResourceRegistrar::class.java.getResourceAsStream("/guidelines/architecture.md")?.use {
            it.bufferedReader().readText()
        } ?: """
            # Kotlin Architecture & Testability Guidelines
            - UI vs business-logic boundary isolation
            - Explicit DTO-to-domain mapping
            - Boundary testability
            - Domain error modeling
        """.trimIndent()
    }

    private val RESILIENCE_TEXT: String by lazy {
        ResourceRegistrar::class.java.getResourceAsStream("/guidelines/resilience.md")?.use {
            it.bufferedReader().readText()
        } ?: """
            # Kotlin Backend Resilience & Fault-Tolerance Guidelines
            - Independent verification probing (Silence != Recovery)
            - Verifiable state caching (Memory Must Not Lie)
            - Deterministic typed state machines for remediation
        """.trimIndent()
    }

    private val KMP_STORAGE_TEXT: String by lazy {
        ResourceRegistrar::class.java.getResourceAsStream("/guidelines/kmp-storage.md")?.use {
            it.bufferedReader().readText()
        } ?: """
            # Kotlin Multiplatform Storage & Persistence Guidelines (Web/Wasm/JS)
            - Room 3.0 on Web (OPFS vs. IndexedDB)
            - SQLite Driver Asymmetry & sqlite-async
            - Room 3.0 Coroutine-Native Architecture
            - DataStore Web Storage Selection
            - Cross-Origin Isolation Headers (COOP / COEP)
        """.trimIndent()
    }

    val USAGE_GUIDE_TEXT: String = """
        # Kotlin MCP Server Instruction Guide for LLMs

        Welcome to the Kotlin MCP Server. This document instructs LLMs and AI agents on how to effectively choose tools, navigate code analysis, execute refactorings, and optimize token usage.

        ## 1. Tool Categorization & Registration Split

        The server separates tool capabilities into two distinct registration entrypoints:

        ### Read-Only Tools (`readOnly = true`)
        Use these tools during research, investigation, code audits, definition lookup, and type/call hierarchy tracing. They NEVER modify source code or disk files:
        - `kotlin_code_analyze`: PSI AST static analysis (`inspect`, `nullability`, `coroutines`, `compose`, `file_context`).
        - `kotlin_project_inspect`: Gradle build script inspection, dependency extraction, pre-build diagnostics, KMP targets, layout inventory, security advisories, and JaCoCo coverage.
        - `kotlin_text_lsp_read`: AST-backed `definition`, `references`, `completion`, `workspace_search`, `workspace_references`, `type_hierarchy`, and `call_hierarchy`.
        - `kotlin_check_snippet`: Fast K2 compiler syntax and type checking.
        - `kotlin_docs_read`: Stdlib symbol search, signature lookup, and feature explanations.

        ### Edit / Mutating Tools (`readOnly = false`)
        Use these tools ONLY when explicitly requested to refactor, format, rename symbols, run Gradle tasks, or persist custom documentation:
        - `kotlin_refactor`: `java_to_kotlin`, `functional` collection loop refactoring, `suggest_idioms`, `quick_fix` compiler diagnostic fixes, and `rxjava` stream migration.
        - `kotlin_library_analyze`: Anti-pattern analysis and refactoring for Ktor (`ktor`, `route_map`), `kotlinx.serialization`, Arrow (`arrow`), and `kotlinx.datetime`.
        - `kotlin_lint`: Detekt static analysis pass, KtLint formatting (`format_ktlint`), and baseline XML generation.
        - `kotlin_run`: Subprocess JVM execution (`snippet`), `./gradlew` task execution (`gradle_task`), and JUnit XML report processing (`test_report`).
        - `kotlin_text_lsp_edit`: AST-based symbol `rename` across snippet and workspace files.
        - `kotlin_docs_edit`: Runtime and persistent custom documentation registration (`register_symbol`, `register_feature`, `register_namespace`).

        ---

        ## 2. Progressive Discovery & Tool Selection Rules

        1. **Use `action` Parameters**: Tools are consolidated into 11 tool definitions using progressive `action` parameters. Specify `action` explicitly in every tool call payload.
        2. **AST-Backed Code Resolution**: `kotlin_text_lsp_read` and `kotlin_code_analyze` parse source code in-memory into actual PSI AST nodes (`KtFile`). They NEVER match inside comments, KDoc, or plain string literals.
        3. **Workspace File Scope**: Always pass `workspacePath` when analyzing cross-file dependencies, searching workspace symbols, or performing workspace-wide symbol renames.
        4. **Call-Time Framework Detection**: Framework tools (e.g. Ktor, Arrow, kotlinx.serialization, kotlinx-datetime) validate active dependencies at execution time. If a framework is not imported in `build.gradle.kts` under `workspacePath`, the server will report missing dependency advisories.
    """.trimIndent()
}

