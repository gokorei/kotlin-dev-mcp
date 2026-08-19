package com.gokorei.kotlinmcp.doc

import org.junit.jupiter.api.Assertions.assertEquals
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
}
