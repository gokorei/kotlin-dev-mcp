package com.gokorei.kotlinmcp.doc

import com.gokorei.kotlinmcp.server.ToolRegistrar
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpDocGeneratorTest {

    private val generator: McpDocGenerator = DefaultMcpDocGenerator()

    @Test
    fun `toolSpecs are perfectly synchronized with ToolRegistrar tool definitions and complete parameter metadata`() {
        val derivedSpecs = ToolRegistrar.buildToolDocSpecs()
        val docSpecs = generator.toolSpecs

        assertEquals(
            derivedSpecs.map { it.name },
            docSpecs.map { it.name },
            "Tool names in McpDocGenerator must match ToolRegistrar"
        )

        for (derived in derivedSpecs) {
            val docSpec = docSpecs.first { it.name == derived.name }
            assertEquals(derived.readOnly, docSpec.readOnly, "readOnly status mismatch for ${derived.name}")
            assertEquals(derived.actions, docSpec.actions, "Actions mismatch for ${derived.name}")
            assertEquals(
                derived.params.map { "${it.name}:${it.type}:${it.itemsType}:${it.required}" },
                docSpec.params.map { "${it.name}:${it.type}:${it.itemsType}:${it.required}" },
                "Complete parameter metadata mismatch for tool ${derived.name}"
            )
            assertEquals(derived.requiredParams, docSpec.requiredParams, "requiredParams mismatch for ${derived.name}")
        }
    }

    @Test
    fun `generateToolReferenceMarkdown produces comprehensive markdown documentation`() {
        val markdown = generator.generateToolReferenceMarkdown()

        // Verify title and structure
        assertTrue(markdown.contains("# Kotlin MCP Tool & Action API Reference"))
        assertTrue(markdown.contains("## Read-Only Tools"))
        assertTrue(markdown.contains("## Mutating / Edit Tools"))

        // Verify all 11 tools are documented
        val expectedTools = listOf(
            "kotlin_docs_read",
            "kotlin_code_analyze",
            "kotlin_text_lsp_read",
            "kotlin_project_inspect",
            "kotlin_check_snippet",
            "kotlin_docs_edit",
            "kotlin_text_lsp_edit",
            "kotlin_refactor",
            "kotlin_library_analyze",
            "kotlin_lint",
            "kotlin_run"
        )
        for (tool in expectedTools) {
            assertTrue(
                markdown.contains("### `$tool`"),
                "Markdown should contain documentation section for '$tool'"
            )
        }

        // Verify parameter table and action descriptions
        assertTrue(markdown.contains("| Parameter | Type | Required | Description |"))
        assertTrue(markdown.contains("`nullability`"))
        assertTrue(markdown.contains("`schema_digest`"))
        assertTrue(markdown.contains("`format_ktlint`"))
    }

    @Test
    fun `generateToolSummaryTable produces table of all 11 tools`() {
        val table = generator.generateToolSummaryTable()

        assertTrue(table.contains("| Tool Name | Actions / Targets | Description |"))
        assertTrue(table.contains("`kotlin_docs_read`"))
        assertTrue(table.contains("`kotlin_run`"))
    }
}
