package com.gokorei.kotlinmcp.doc

/**
 * Parsed section entry representing changes under a category.
 */
data class ReleaseCategory(
    val title: String,
    val items: List<String>
)

/**
 * Parsed release entry from Release-Notes.md.
 */
data class ReleaseEntry(
    val version: String?, // null if unreleased (Next)
    val date: String?,
    val added: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val security: List<String> = emptyList()
)

/**
 * Explicit interface for generating Keep a Changelog Markdown directly from Release-Notes.md.
 */
interface ChangelogGenerator {
    /**
     * Generates a complete Keep a Changelog Markdown document from Release-Notes.md text.
     */
    fun generateFromReleaseNotes(
        releaseNotesText: String,
        repoUrl: String = "https://github.com/gokorei/kotlin-dev-mcp"
    ): String
}

/**
 * Default implementation of [ChangelogGenerator].
 */
class DefaultChangelogGenerator : ChangelogGenerator {

    override fun generateFromReleaseNotes(
        releaseNotesText: String,
        repoUrl: String
    ): String {
        val entries = parseReleaseNotes(releaseNotesText)

        return buildString {
            appendLine("# Changelog")
            appendLine()
            appendLine("All notable changes to `kotlin-mcp` are documented in this file.")
            appendLine()
            appendLine("The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),")
            appendLine("and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).")
            appendLine()

            val releasedVersions = mutableListOf<String>()

            for (entry in entries) {
                if (entry.version == null) {
                    appendLine("## [Unreleased]")
                } else {
                    releasedVersions.add(entry.version)
                    val datePart = if (entry.date != null) " - ${entry.date}" else ""
                    appendLine("## [${entry.version}]$datePart")
                }

                var hasContent = false
                if (entry.added.isNotEmpty()) {
                    appendLine()
                    appendLine("### Added")
                    entry.added.forEach { appendLine(it) }
                    hasContent = true
                }
                if (entry.changed.isNotEmpty()) {
                    appendLine()
                    appendLine("### Changed")
                    entry.changed.forEach { appendLine(it) }
                    hasContent = true
                }
                if (entry.fixed.isNotEmpty()) {
                    appendLine()
                    appendLine("### Fixed")
                    entry.fixed.forEach { appendLine(it) }
                    hasContent = true
                }
                if (entry.security.isNotEmpty()) {
                    appendLine()
                    appendLine("### Security")
                    entry.security.forEach { appendLine(it) }
                    hasContent = true
                }

                if (!hasContent && entry.version == null) {
                    // Empty unreleased section
                }
                appendLine()
            }

            // Compare links
            if (releasedVersions.isNotEmpty()) {
                val latest = releasedVersions.first()
                appendLine("[Unreleased]: $repoUrl/compare/v$latest...HEAD")
                for (i in 0 until releasedVersions.size - 1) {
                    val current = releasedVersions[i]
                    val previous = releasedVersions[i + 1]
                    appendLine("[$current]: $repoUrl/compare/v$previous...v$current")
                }
                val oldest = releasedVersions.last()
                appendLine("[$oldest]: $repoUrl/releases/tag/v$oldest")
            }
        }.trimEnd() + "\n"
    }

    private fun parseReleaseNotes(text: String): List<ReleaseEntry> {
        val lines = text.lines()
        val entries = mutableListOf<ReleaseEntry>()

        var currentVersion: String? = null
        var currentDate: String? = null
        var isNext = false

        var currentCategory: String? = null
        val added = mutableListOf<String>()
        val changed = mutableListOf<String>()
        val fixed = mutableListOf<String>()
        val security = mutableListOf<String>()
        val currentItemLines = mutableListOf<String>()

        fun flushItem() {
            if (currentItemLines.isNotEmpty()) {
                val item = currentItemLines.joinToString("\n").trimEnd()
                when (currentCategory) {
                    "added" -> added.add(item)
                    "changed" -> changed.add(item)
                    "fixed" -> fixed.add(item)
                    "security" -> security.add(item)
                }
                currentItemLines.clear()
            }
        }

        fun flushEntry() {
            flushItem()
            if (isNext || currentVersion != null) {
                entries.add(
                    ReleaseEntry(
                        version = if (isNext) null else currentVersion,
                        date = currentDate,
                        added = added.toList(),
                        changed = changed.toList(),
                        fixed = fixed.toList(),
                        security = security.toList()
                    )
                )
                added.clear()
                changed.clear()
                fixed.clear()
                security.clear()
                currentCategory = null
                currentVersion = null
                currentDate = null
                isNext = false
            }
        }

        for (line in lines) {
            val trimmed = line.trim()

            if (trimmed == "## Next" || trimmed.startsWith("## Next ")) {
                flushEntry()
                isNext = true
                currentVersion = null
                currentDate = null
                continue
            }

            if (trimmed.startsWith("## v") || trimmed.startsWith("## [") || (trimmed.startsWith("## ") && trimmed.contains("—"))) {
                flushEntry()
                isNext = false
                // Parse "## v1.1.0 — 2026-08-19" or "## [1.1.0] - 2026-08-19"
                val clean = trimmed.removePrefix("## ").trim().removePrefix("v").removePrefix("[").removeSuffix("]")
                if (clean.contains("—")) {
                    val parts = clean.split("—", limit = 2)
                    currentVersion = parts[0].trim().removePrefix("v").removeSuffix("]")
                    currentDate = parts.getOrNull(1)?.trim()
                } else if (clean.contains(" - ")) {
                    val parts = clean.split(" - ", limit = 2)
                    currentVersion = parts[0].trim().removePrefix("v").removeSuffix("]")
                    currentDate = parts.getOrNull(1)?.trim()
                } else {
                    currentVersion = clean.trim()
                    currentDate = null
                }
                continue
            }

            if (trimmed.startsWith("### ")) {
                flushItem()
                val heading = trimmed.removePrefix("### ").trim().lowercase()
                currentCategory = when {
                    heading.contains("new feature") || heading.contains("added") -> "added"
                    heading.contains("improvement") || heading.contains("changed") -> "changed"
                    heading.contains("bug fix") || heading.contains("fixed") -> "fixed"
                    heading.contains("security") -> "security"
                    else -> null
                }
                continue
            }

            if (trimmed.startsWith("---") || trimmed.startsWith("# Release Notes") || trimmed.startsWith("Overview of")) {
                continue
            }

            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                flushItem()
                if (currentCategory != null) {
                    currentItemLines.add(line)
                }
            } else if (currentItemLines.isNotEmpty()) {
                currentItemLines.add(line)
            }
        }

        flushEntry()
        return entries
    }
}

/**
 * CLI entrypoint for Gradle task `generateChangelog`.
 */
fun main(args: Array<String>) {
    val releaseNotesFile = java.io.File(args.getOrNull(0) ?: "docs/wiki/Release-Notes.md")
    val changelogFile = java.io.File(args.getOrNull(1) ?: "CHANGELOG.md")
    if (!releaseNotesFile.exists()) {
        System.err.println("Release notes file not found: ${releaseNotesFile.absolutePath}")
        return
    }
    val generator = DefaultChangelogGenerator()
    val content = generator.generateFromReleaseNotes(releaseNotesFile.readText())
    changelogFile.writeText(content)
    println("Synchronized CHANGELOG.md from ${releaseNotesFile.name} -> ${changelogFile.absolutePath}")
}

