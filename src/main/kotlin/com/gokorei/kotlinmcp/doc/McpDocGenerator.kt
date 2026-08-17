package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.server.ToolRegistrar
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
    val toolSpecs: List<ToolDocSpec>

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
 * for MCP tool documentation by deriving specs directly from [ToolRegistrar].
 */
class DefaultMcpDocGenerator(
    override val toolSpecs: List<ToolDocSpec> = ToolRegistrar.buildToolDocSpecs()
) : McpDocGenerator {

    override fun generateToolReferenceMarkdown(): String = buildString {
        appendLine("# Kotlin MCP Tool & Action API Reference")
        appendLine()
        appendLine("This document is the authoritative, code-backed API reference for all **${toolSpecs.size} MCP tools** provided by `kotlin-mcp`.")
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
            val typeStr =
                if (param.type == "array" && param.itemsType != null) "Array<${param.itemsType}>" else param.type
            appendLine("| `${param.name}` | `$typeStr` | $reqStr | ${param.description} |")
        }
        appendLine()
    }

    override fun generateToolSummaryTable(): String = buildString {
        appendLine("| Tool Name | Actions / Targets | Description |")
        appendLine("| :--- | :--- | :--- |")
        toolSpecs.forEach { tool ->
            val actionsStr =
                if (tool.actions.isNotEmpty()) tool.actions.joinToString(", ") { "`$it`" } else "*(Direct)*"
            val shortDesc = tool.description.trimEnd().removeSuffix(".")
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
