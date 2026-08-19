package com.gokorei.kotlinmcp.doc.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpDocGeneratorTest {

    private val generator: McpDocGenerator = DefaultMcpDocGenerator()

    @Test
    fun `toolSpecs are generated with expected 11 core tools, actions, and required parameters`() {
        val docSpecs = generator.toolSpecs
        val expectedToolNames = listOf(
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

        assertEquals(
            expectedToolNames,
            docSpecs.map { it.name },
            "Generated tool specs must cover all 11 core tools in order"
        )

        val readOnlyNames = docSpecs.filter { it.readOnly }.map { it.name }
        assertEquals(
            listOf("kotlin_docs_read", "kotlin_code_analyze", "kotlin_text_lsp_read", "kotlin_project_inspect", "kotlin_check_snippet"),
            readOnlyNames,
            "Read-only tools must match expected list"
        )

        val checkSnippet = docSpecs.first { it.name == "kotlin_check_snippet" }
        assertEquals(listOf("code"), checkSnippet.requiredParams)

        val docsEdit = docSpecs.first { it.name == "kotlin_docs_edit" }
        assertEquals(listOf("name"), docsEdit.requiredParams)
        assertEquals(listOf("register_symbol", "register_feature", "register_namespace"), docsEdit.actions)

        val lspEdit = docSpecs.first { it.name == "kotlin_text_lsp_edit" }
        assertEquals(listOf("oldName", "newName"), lspEdit.requiredParams)
        assertEquals(listOf("rename"), lspEdit.actions)

        val refactor = docSpecs.first { it.name == "kotlin_refactor" }
        assertEquals(listOf("code"), refactor.requiredParams)
        assertEquals(listOf("suggest_idioms", "java_to_kotlin", "functional", "quick_fix", "rxjava"), refactor.actions)

        val libAnalyze = docSpecs.first { it.name == "kotlin_library_analyze" }
        assertEquals(listOf("code"), libAnalyze.requiredParams)
        assertEquals(listOf("ktor", "serialization", "tests", "route_map", "arrow", "datetime"), libAnalyze.actions)
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
