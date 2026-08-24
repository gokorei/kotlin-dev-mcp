package com.gokorei.kotlinmcp.doc.tooling

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChangelogGeneratorTest {

    private val generator: ChangelogGenerator = DefaultChangelogGenerator()

    @Test
    fun `parses release notes markdown and generates keep-a-changelog output`() {
        val sampleReleaseNotes = """
            # Release Notes

            Overview of new features, bug fixes, and improvements.

            ## Next

            ### New Features
            - **Web storage**: Added KMP web storage guidelines.

            ### Improvements
            - **Clean shutdown**: Optimized process shutdown hooks.

            ### Bug Fixes
            - **Output race**: Fixed subprocess stdout race.

            ---

            ## v1.1.0 — 2026-08-19

            ### New Features
            - **K2 engine**: Workspace-aware K2 semantic engine.

            ### Improvements
            - **AST resolver**: Modularized K2 resolvers.

            ### Bug Fixes
            - **Output truncation**: Fixed output truncation.

            ---

            ## v1.0.0 — 2026-08-16

            ### New Features
            - **Initial release**: Initial MCP release with 11 tools.
        """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(
            sampleReleaseNotes,
            repoUrl = "https://github.com/gokorei/kotlin-dev-mcp"
        )

        assertTrue(changelog.startsWith("# Changelog\n\nAll notable changes to `kotlin-mcp` are documented in this file."))
        assertTrue(changelog.contains("## [Unreleased]"))
        assertTrue(changelog.contains("### Added\n- **Web storage**: Added KMP web storage guidelines."))
        assertTrue(changelog.contains("### Changed\n- **Clean shutdown**: Optimized process shutdown hooks."))
        assertTrue(changelog.contains("### Fixed\n- **Output race**: Fixed subprocess stdout race."))
        assertTrue(changelog.contains("## [1.1.0] - 2026-08-19"))
        assertTrue(changelog.contains("## [1.0.0] - 2026-08-16"))

        // Verify compare links
        assertTrue(changelog.contains("[Unreleased]: https://github.com/gokorei/kotlin-dev-mcp/compare/v1.1.0...HEAD"))
        assertTrue(changelog.contains("[1.1.0]: https://github.com/gokorei/kotlin-dev-mcp/compare/v1.0.0...v1.1.0"))
        assertTrue(changelog.contains("[1.0.0]: https://github.com/gokorei/kotlin-dev-mcp/releases/tag/v1.0.0"))
    }

    @Test
    fun `empty sections in release notes are omitted in generated changelog`() {
        val releaseNotes = """
            # Release Notes

            ## Next

            ### New Features

            ### Bug Fixes

            ### Improvements

            ---

            ## v1.0.0 — 2026-08-16

            ### New Features
            - Initial release
        """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(
            releaseNotes,
            repoUrl = "https://github.com/gokorei/kotlin-dev-mcp"
        )

        assertTrue(changelog.contains("## [Unreleased]\n\n## [1.0.0] - 2026-08-16"))
        assertTrue(changelog.contains("[1.0.0]: https://github.com/gokorei/kotlin-dev-mcp/releases/tag/v1.0.0"))
    }

    @Test
    fun `flushes list items before separator line and footer text`() {
        val releaseNotes = """
            # Release Notes

            ## v1.0.0 — 2026-08-16

            ### New Features
            - Initial feature
              Additional line detail

            ---
            [← Home](Home)
        """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(
            releaseNotes,
            repoUrl = "https://github.com/gokorei/kotlin-dev-mcp"
        )

        assertTrue(changelog.contains("### Added\n- Initial feature\n  Additional line detail"))
        org.junit.jupiter.api.Assertions.assertFalse(changelog.contains("[← Home](Home)"))
    }

    @Test
    fun `category entries are strictly cleared and do not bleed across releases`() {
        val bleedTestNotes = """
            # Release Notes

            ## Next
            ### Security
            - Unreleased security advisory

            ## v1.2.0 — 2026-08-20
            ### Improvements
            - Changed in 1.2.0
            ### Security
            - Security fix in 1.2.0

            ## v1.1.0 — 2026-08-19
            ### New Features
            - Added in 1.1.0
            ### Bug Fixes
            - Fixed in 1.1.0

            ## v1.0.0 — 2026-08-16
            ### New Features
            - Added in 1.0.0

            ## v0.9.0 — 2026-08-01
            ### Bug Fixes
            - Fixed in 0.9.0
        """.trimIndent()

        val changelog = generator.generateFromReleaseNotes(bleedTestNotes)
        val sec11 = changelog.substringAfter("## [1.1.0]").substringBefore("## [1.0.0]")
        org.junit.jupiter.api.Assertions.assertFalse(sec11.contains("### Changed"))
        org.junit.jupiter.api.Assertions.assertFalse(sec11.contains("### Security"))

        val sec10 = changelog.substringAfter("## [1.0.0]").substringBefore("## [0.9.0]")
        org.junit.jupiter.api.Assertions.assertFalse(sec10.contains("### Fixed"))

        val sec09 = changelog.substringAfter("## [0.9.0]").substringBefore("[Unreleased]")
        org.junit.jupiter.api.Assertions.assertFalse(sec09.contains("### Added"))
    }
}
