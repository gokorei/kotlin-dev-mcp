package com.gokorei.kotlinmcp.doc.tooling

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class DocumentationSyncTest {

    private val generator: McpDocGenerator = DefaultMcpDocGenerator()
    private val changelogGenerator: ChangelogGenerator = DefaultChangelogGenerator()

    @Test
    fun `verify committed Tool-Reference md matches in-code McpDocGenerator output`() {
        val toolRefFile = File("docs/wiki/Tool-Reference.md")
        assertTrue(
            toolRefFile.exists(),
            "docs/wiki/Tool-Reference.md does not exist! Run './gradlew generateMcpDocs' to generate it."
        )

        val committedContent = toolRefFile.readText().replace("\r\n", "\n").trim()
        val generatedContent = generator.generateToolReferenceMarkdown().replace("\r\n", "\n").trim()

        assertEquals(
            generatedContent,
            committedContent,
            "docs/wiki/Tool-Reference.md is out of sync with in-code tool definitions! Run './gradlew generateMcpDocs' to update."
        )
    }

    @Test
    fun `verify Home md references Tool Reference guide`() {
        val homeFile = File("docs/wiki/Home.md")
        assertTrue(homeFile.exists(), "docs/wiki/Home.md must exist")
        val homeContent = homeFile.readText()
        assertTrue(
            homeContent.contains("[Tool Reference](Tool-Reference)"),
            "docs/wiki/Home.md should include link to Tool Reference guide"
        )
    }

    @Test
    fun `verify committed CHANGELOG md matches Release-Notes md generated content`() {
        val releaseNotesFile = File("docs/wiki/Release-Notes.md")
        val changelogFile = File("CHANGELOG.md")
        assertTrue(releaseNotesFile.exists(), "docs/wiki/Release-Notes.md must exist")
        assertTrue(changelogFile.exists(), "CHANGELOG.md must exist! Run './gradlew generateChangelog' to create it.")

        val expected = changelogGenerator.generateFromReleaseNotes(releaseNotesFile.readText())
            .replace("\r\n", "\n").trim()
        val actual = changelogFile.readText()
            .replace("\r\n", "\n").trim()

        assertEquals(
            expected,
            actual,
            "CHANGELOG.md is out of sync with docs/wiki/Release-Notes.md! Run './gradlew generateChangelog' to synchronize."
        )
    }
}
