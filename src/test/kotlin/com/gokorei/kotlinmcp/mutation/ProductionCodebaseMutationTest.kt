package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

@Tag("hardening")
class ProductionCodebaseMutationTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    @Test
    fun `mutation test production ChangelogGenerator source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/doc/tooling/ChangelogGenerator.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        // Strip package declaration and CLI main() method so it compiles in-memory
        val rawSource = file.readText()
        val productionCode = rawSource
            .substringBefore("\n/**\n * CLI entrypoint")
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        // Comprehensive test suite executing production DefaultChangelogGenerator methods
        val testSuiteCode = """
            fun main() {
                val generator = DefaultChangelogGenerator()
                
                // 1. Test full parsing with Next, multiple versions, and all categories
                val sampleReleaseNotes = ""${'"'}
                    # Release Notes

                    Overview of new features, bug fixes, and improvements.

                    ## Next

                    ### New Features
                    - **Web storage**: Added KMP web storage guidelines.

                    ### Improvements
                    - **Clean shutdown**: Optimized process shutdown hooks.

                    ### Bug Fixes
                    - **Output race**: Fixed subprocess stdout race.

                    ### Security
                    - **Safe parsing**: Hardened input bounds.

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
                ""${'"'}.trimIndent()

                val changelog = generator.generateFromReleaseNotes(
                    sampleReleaseNotes,
                    repoUrl = "https://github.com/gokorei/kotlin-dev-mcp"
                )

                check(changelog.startsWith("# Changelog\n\nAll notable changes to `kotlin-mcp` are documented in this file.\n\nThe format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),\nand this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).\n\n## [Unreleased]\n")) { "exact header check" }
                check(changelog.contains("## [Unreleased]\n\n### Added\n- **Web storage**: Added KMP web storage guidelines.\n\n### Changed\n- **Clean shutdown**: Optimized process shutdown hooks.\n\n### Fixed\n- **Output race**: Fixed subprocess stdout race.\n\n### Security\n- **Safe parsing**: Hardened input bounds.")) { "unreleased section exact formatting check" }
                check(changelog.contains("## [1.1.0] - 2026-08-19")) { "v1.1.0 heading check" }
                check(changelog.contains("## [1.0.0] - 2026-08-16")) { "v1.0.0 heading check" }
                
                // Compare links
                check(changelog.contains("[Unreleased]: https://github.com/gokorei/kotlin-dev-mcp/compare/v1.1.0...HEAD")) { "unreleased compare link check" }
                check(changelog.contains("[1.1.0]: https://github.com/gokorei/kotlin-dev-mcp/compare/v1.0.0...v1.1.0")) { "1.1.0 compare link check" }
                check(changelog.contains("[1.0.0]: https://github.com/gokorei/kotlin-dev-mcp/releases/tag/v1.0.0")) { "1.0.0 release link check" }

                // 2. Strict category bleed & list clearing across 4 consecutive releases
                val bleedTestNotes = ""${'"'}
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
                ""${'"'}.trimIndent()

                val bleedChangelog = generator.generateFromReleaseNotes(bleedTestNotes)
                check(bleedChangelog.contains("## [1.2.0] - 2026-08-20\n\n### Changed\n- Changed in 1.2.0\n\n### Security\n- Security fix in 1.2.0")) { "1.2.0 exact categories" }
                check(bleedChangelog.contains("## [1.1.0] - 2026-08-19\n\n### Added\n- Added in 1.1.0\n\n### Fixed\n- Fixed in 1.1.0")) { "1.1.0 exact categories" }
                
                // Verify no category bleed into 1.1.0 from 1.2.0
                val sec11 = bleedChangelog.substringAfter("## [1.1.0]").substringBefore("## [1.0.0]")
                check(!sec11.contains("### Changed")) { "changed list was cleared between 1.2.0 and 1.1.0" }
                check(!sec11.contains("### Security")) { "security list was cleared between 1.2.0 and 1.1.0" }

                // Verify no category bleed into 1.0.0 from 1.1.0
                val sec10 = bleedChangelog.substringAfter("## [1.0.0]").substringBefore("## [0.9.0]")
                check(!sec10.contains("### Fixed")) { "fixed list was cleared between 1.1.0 and 1.0.0" }
                check(!sec10.contains("### Changed")) { "changed list was not present in 1.0.0" }
                check(!sec10.contains("### Security")) { "security list was not present in 1.0.0" }

                // Verify no added bleed into 0.9.0 from 1.0.0
                val sec09 = bleedChangelog.substringAfter("## [0.9.0]").substringBefore("[Unreleased]")
                check(!sec09.contains("### Added")) { "added list was cleared between 1.0.0 and 0.9.0" }

                // 3. Multi-line list item flushing, consecutive items, and footer stripping
                val multilineNotes = ""${'"'}
                    # Release Notes

                    ## v1.0.0 — 2026-08-16

                    ### New Features
                    - Initial feature
                      Additional line detail
                    * Second feature with asterisk bullet
                    - Third feature directly adjacent

                    ---
                    [← Home](Home)
                ""${'"'}.trimIndent()

                val multilineChangelog = generator.generateFromReleaseNotes(multilineNotes)
                check(multilineChangelog.contains("### Added\n- Initial feature\n  Additional line detail\n* Second feature with asterisk bullet\n- Third feature directly adjacent")) { "multiline and multiple bullet points flushed correctly" }
                check(!multilineChangelog.contains("[← Home](Home)")) { "footer omitted check" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ChangelogGenerator.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ChangelogGenerator.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ChangelogGenerator.kt")
        assertTrue(
            report.score >= 90.0,
            "Mutation score for ChangelogGenerator.kt (${report.score}%) must be at least 90%"
        )
    }
}
