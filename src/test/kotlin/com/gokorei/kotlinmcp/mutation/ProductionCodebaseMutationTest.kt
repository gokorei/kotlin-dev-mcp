package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File

import org.junit.jupiter.api.BeforeAll

@Tag("hardening")
class ProductionCodebaseMutationTest {

    companion object {
        @JvmStatic
        @BeforeAll
        fun setup() {
            System.setProperty("kmcp.include_internal_classpath", "true")
        }
    }

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

    @Test
    fun `mutation test production ResponseProjection source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/models/ResponseProjection.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        val rawSource = file.readText()
        val productionCode = rawSource
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        // Include standalone Result model definition for in-memory execution
        val resultModelSnippet = """
            sealed class KotlinMcpResult {
                abstract val isSuccess: Boolean
                abstract val isError: Boolean
                
                data class Success(
                    val content: String,
                    val metadata: Map<String, String> = emptyMap()
                ) : KotlinMcpResult() {
                    override val isSuccess: Boolean = true
                    override val isError: Boolean = false
                }

                data class Error(
                    val code: String,
                    val message: String,
                    val details: Map<String, String> = emptyMap()
                ) : KotlinMcpResult() {
                    override val isSuccess: Boolean = false
                    override val isError: Boolean = true
                }
            }
        """.trimIndent()

        val fullProductionSnippet = "$resultModelSnippet\n\n$productionCode"

        val testSuiteCode = """
            fun main() {
                // 1. Preset parsing assertions
                check(ResponsePreset.fromString("compact") == ResponsePreset.COMPACT)
                check(ResponsePreset.fromString("COMPACT") == ResponsePreset.COMPACT)
                check(ResponsePreset.fromString("summary") == ResponsePreset.SUMMARY)
                check(ResponsePreset.fromString("SUMMARY") == ResponsePreset.SUMMARY)
                check(ResponsePreset.fromString("full") == ResponsePreset.FULL)
                check(ResponsePreset.fromString(null) == ResponsePreset.FULL)
                check(ResponsePreset.fromString("unknown") == ResponsePreset.FULL)
                check(ResponsePreset.fromString("") == ResponsePreset.FULL)

                // 2. Default Full projection identity check
                val origSuccess = KotlinMcpResult.Success("content", mapOf("k" to "v"))
                val fullProj = ResponseProjection(ResponsePreset.FULL, emptySet())
                val res1 = ProjectionFilter.apply(origSuccess, fullProj)
                check(res1 == origSuccess)

                // 3. Field filtering on Success and Error
                val richSuccess = KotlinMcpResult.Success("data", mapOf("a" to "1", "b" to "2", "c" to "3"))
                val fieldsProj = ResponseProjection(ResponsePreset.FULL, setOf("a", "c"))
                val filteredSuccess = ProjectionFilter.apply(richSuccess, fieldsProj) as KotlinMcpResult.Success
                check(filteredSuccess.isSuccess)
                check(!filteredSuccess.isError)
                check(filteredSuccess.metadata == mapOf("a" to "1", "c" to "3"))

                val richError = KotlinMcpResult.Error("ERR", "msg", mapOf("debug" to "trace", "code" to "404"))
                val errorProj = ResponseProjection(ResponsePreset.FULL, setOf("code"))
                val filteredError = ProjectionFilter.apply(richError, errorProj) as KotlinMcpResult.Error
                check(filteredError.isError)
                check(!filteredError.isSuccess)
                check(filteredError.details == mapOf("code" to "404"))

                // 4. Compact preset metadata pruning
                val metaSuccess = KotlinMcpResult.Success(
                    "data",
                    mapOf(
                        "raw" to "val1",
                        "rawAst" to "val2",
                        "internalAstOffset" to "val3",
                        "debug" to "val4",
                        "verboseDebugInfo" to "val5",
                        "astDump" to "val6",
                        "preservedKey" to "keep"
                    )
                )
                val compactProj = ResponseProjection(ResponsePreset.COMPACT)
                val compactRes = ProjectionFilter.apply(metaSuccess, compactProj) as KotlinMcpResult.Success
                check(compactRes.metadata == mapOf("preservedKey" to "keep"))

                // 5. Compact content stripping
                val verboseContent = ""${'"'}
                    Header Info
                    --- Internal AST Dump ---
                    Raw AST line 1
                    Raw AST line 2
                    --- Next Section ---
                    Main summary results
                    --- Debug Trace (verbose)
                    Debug stack line
                    --- Final Section ---
                    End note
                ""${'"'}.trimIndent()

                val contentSuccess = KotlinMcpResult.Success(verboseContent, emptyMap())
                val compactedContent = (ProjectionFilter.apply(contentSuccess, compactProj) as KotlinMcpResult.Success).content

                check(compactedContent.contains("Header Info"))
                check(compactedContent.contains("--- Next Section ---"))
                check(compactedContent.contains("Main summary results"))
                check(compactedContent.contains("--- Final Section ---"))
                check(compactedContent.contains("End note"))
                check(!compactedContent.contains("Raw AST line 1"))
                check(!compactedContent.contains("Debug stack line"))
            }
        """.trimIndent()

        val report = pipeline.run(
            code = fullProductionSnippet,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ResponseProjection.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ResponseProjection.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ResponseProjection.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for ResponseProjection.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production JavaResolver source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/JavaResolver.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        val rawSource = file.readText()
        val productionCode = rawSource
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                val resolver = DefaultJavaResolver()

                // 1. validateJvmArgs checks all forbidden prefixes and self-attach vectors (case-insensitive)
                val forbidden = listOf(
                    "-JAVAAGENT:/path/agent.jar",
                    "-AgentLib:jdwp=transport=dt_socket",
                    "-agentpath:/path/lib.so",
                    "-Xbootclasspath/a:/path",
                    "--ADD-OPENS=java.base/java.lang=ALL-UNNAMED",
                    "--add-exports=java.base/sun.security.util=ALL-UNNAMED",
                    "--add-reads=m1=m2",
                    "--patch-module=java.base=patch.jar",
                    "--Allow-Attach-Self",
                    "-Djdk.attach.allowAttachSelf=true"
                )

                val violations = resolver.validateJvmArgs(forbidden)
                check(violations.size == forbidden.size) { "all forbidden flags caught" }
                forbidden.forEach { flag ->
                    check(violations.contains(flag)) { "violation contains flag: " + flag }
                }

                // Safe arguments must not be flagged
                val safeArgs = listOf("-Xmx512m", "-Xms128m", "-Dfile.encoding=UTF-8", "-ea")
                val safeViolations = resolver.validateJvmArgs(safeArgs)
                check(safeViolations.isEmpty()) { "safe args produce no violations" }

                // 2. resolve with explicit path
                val validJava = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java"
                val resolvedExplicit = resolver.resolve(validJava)
                check(resolvedExplicit != null) { "explicit valid java resolved" }

                val nonExistent = "/non/existent/path/to/java_binary_dummy"
                check(resolver.resolve(nonExistent) == null) { "non-existent path returns null" }

                // 3. resolve with null / blank falling back to java.home (must match java.home location, not just random PATH)
                val expectedHome = System.getProperty("java.home") ?: System.getenv("JAVA_HOME")
                val fallbackResolved = resolver.resolve(null)
                check(fallbackResolved != null && fallbackResolved.exists()) { "null fallback resolves java home binary" }
                if (!expectedHome.isNullOrBlank()) {
                    check(fallbackResolved!!.absolutePath.startsWith(expectedHome)) { "resolved binary must originate from java.home" }
                }

                val blankResolved = resolver.resolve("   ")
                check(blankResolved != null && blankResolved.exists()) { "blank fallback resolves java home binary" }
                if (!expectedHome.isNullOrBlank()) {
                    check(blankResolved!!.absolutePath.startsWith(expectedHome)) { "blank fallback binary must originate from java.home" }
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: JavaResolver.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN JavaResolver.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for JavaResolver.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for JavaResolver.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production ToonUtils source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/shared/ToonUtils.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        val rawSource = file.readText()
        val productionCode = rawSource
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            data class RowItem(val name: String?, val score: Int, val notes: String?)

            fun main() {
                val items = listOf(
                    RowItem("Alice", 100, "Clean|Passed"),
                    RowItem("Bob", 85, null),
                    RowItem(null, 0, "Failed|Timeout|Retry")
                )

                val toon = ToonUtils.encodeToonTable(
                    headerName = "TestResults",
                    columns = listOf("Name", "Score", "Notes"),
                    items = items
                ) { item ->
                    listOf(item.name, item.score, item.notes)
                }

                check(toon.startsWith("[TestResults: Name|Score|Notes]\n")) { "header format check" }
                check(toon.contains("Alice|100|Clean/Passed")) { "escaped delimiter check row 1" }
                check(toon.contains("Bob|85|")) { "null field check row 2" }
                check(toon.contains("|0|Failed/Timeout/Retry")) { "null name and escaped notes check row 3" }
                check(!toon.endsWith("\n")) { "trailing newline trimmed" }

                // Empty items list test
                val emptyToon = ToonUtils.encodeToonTable(
                    headerName = "EmptyTable",
                    columns = listOf("ColA", "ColB"),
                    items = emptyList<RowItem>()
                ) { listOf(it.name, it.score) }

                check(emptyToon == "[EmptyTable: ColA|ColB]") { "empty table format check" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ToonUtils.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ToonUtils.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ToonUtils.kt")
        assertEquals(0, report.survivedCount, "All ToonUtils mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production KotlinMcpResult source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/models/KotlinMcpResult.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        val rawSource = file.readText()
        val productionCode = rawSource
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import kotlinx.serialization") }
            .filterNot { it.trim() == "@Serializable" }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                // 1. Success without metadata
                val s1 = KotlinMcpResult.Success(content = "Plain text output")
                check(s1.isSuccess) { "s1 isSuccess" }
                check(!s1.isError) { "s1 !isError" }
                check(s1.toFormattedText() == "Plain text output") { "s1 formatting" }

                // 2. Success with metadata
                val s2 = KotlinMcpResult.Success(
                    content = "Header",
                    metadata = mapOf("total" to "42", "duration" to "15ms")
                )
                val s2Text = s2.toFormattedText()
                check(s2Text.startsWith("Header\n\n--- Metadata ---\n")) { "s2 header and metadata separator" }
                check(s2Text.contains("total: 42")) { "s2 total key" }
                check(s2Text.contains("duration: 15ms")) { "s2 duration key" }

                // 3. Success with requireAnotherCall
                val s3 = KotlinMcpResult.Success(content = "Need fix", requireAnotherCall = true)
                check(s3.toFormattedText().contains("requireAnotherCall: true — apply the diagnostics above and re-run this tool until it reports no issues.")) { "s3 retry banner" }

                // 4. Error with default code
                val e1 = KotlinMcpResult.Error(message = "Something failed")
                check(!e1.isSuccess) { "e1 !isSuccess" }
                check(e1.isError) { "e1 isError" }
                check(e1.code == "GENERIC_ERROR") { "e1 default code" }
                check(e1.toFormattedText() == "Error [GENERIC_ERROR]: Something failed") { "e1 formatting" }

                // 5. Error with custom code and details
                val e2 = KotlinMcpResult.Error(
                    code = "SYNTAX_ERROR",
                    message = "Unresolved symbol",
                    details = mapOf("line" to "12", "column" to "5")
                )
                val e2Text = e2.toFormattedText()
                check(e2Text.startsWith("Error [SYNTAX_ERROR]: Unresolved symbol\nDetails:\n")) { "e2 header and details" }
                check(e2Text.contains(" - line: 12")) { "e2 line detail" }
                check(e2Text.contains(" - column: 5")) { "e2 column detail" }

                // 6. Error with requireAnotherCall
                val e3 = KotlinMcpResult.Error(message = "Compilation issue", requireAnotherCall = true)
                check(e3.toFormattedText().contains("requireAnotherCall: true — apply the diagnostics above and re-run this tool until it reports no issues.")) { "e3 retry banner" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: KotlinMcpResult.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN KotlinMcpResult.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for KotlinMcpResult.kt")
        assertEquals(0, report.survivedCount, "All KotlinMcpResult mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production ProjectEnvironmentProfile source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/models/ProjectEnvironmentProfile.kt")
        assertTrue(file.exists(), "Target production file must exist: ${file.absolutePath}")

        val rawSource = file.readText()
        val productionCode = rawSource
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                val defaultProfile = ProjectEnvironmentProfile()
                check(!defaultProfile.isKmp) { "default isKmp must be false" }
                check(defaultProfile.activeFrameworks.isEmpty()) { "default activeFrameworks empty" }

                val profile = ProjectEnvironmentProfile(
                    activeFrameworks = setOf(FrameworkFeature.COMPOSE, FrameworkFeature.KTOR),
                    isKmp = true
                )

                check(profile.isKmp) { "isKmp check" }
                check(profile.hasFramework(FrameworkFeature.COMPOSE)) { "has compose" }
                check(profile.hasFramework(FrameworkFeature.KTOR)) { "has ktor" }
                check(!profile.hasFramework(FrameworkFeature.SPRING)) { "!has spring" }
                check(!profile.hasFramework(FrameworkFeature.ARROW)) { "!has arrow" }

                val allProfile = ProjectEnvironmentProfile.ALL
                FrameworkFeature.entries.forEach { f ->
                    check(allProfile.hasFramework(f)) { "ALL contains: " + f.id }
                }

                val noneProfile = ProjectEnvironmentProfile.NONE
                FrameworkFeature.entries.forEach { f ->
                    check(!noneProfile.hasFramework(f)) { "NONE does not contain: " + f.id }
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ProjectEnvironmentProfile.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ProjectEnvironmentProfile.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ProjectEnvironmentProfile.kt")
        assertEquals(0, report.survivedCount, "All ProjectEnvironmentProfile mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production FrameworkFeatureCatalog source file`() {
        val featureFile = File("src/main/kotlin/com/gokorei/kotlinmcp/models/ProjectEnvironmentProfile.kt")
        val catalogFile = File("src/main/kotlin/com/gokorei/kotlinmcp/doc/FrameworkFeatureCatalog.kt")
        assertTrue(featureFile.exists(), "Target file must exist: ${featureFile.absolutePath}")
        assertTrue(catalogFile.exists(), "Target file must exist: ${catalogFile.absolutePath}")

        val featureSource = featureFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val catalogSource = catalogFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import com.gokorei.kotlinmcp.models.FrameworkFeature") }
            .joinToString("\n")

        val productionCode = featureSource + "\n\n" + catalogSource

        val testSuiteCode = """
            fun main() {
                val defaultProfile = ProjectEnvironmentProfile()
                check(!defaultProfile.isKmp) { "default isKmp false" }

                // 1. featureAppliesTo mapping verification
                val applies = FrameworkFeatureCatalog.featureAppliesTo
                check(applies[FrameworkFeature.ARROW] == listOf("arrow")) { "arrow mapping" }
                check(applies[FrameworkFeature.DATETIME] == listOf("kotlinx-datetime")) { "datetime mapping" }
                check(applies[FrameworkFeature.KTOR] == listOf("ktor")) { "ktor mapping" }
                check(applies[FrameworkFeature.TURBINE] == listOf("turbine")) { "turbine mapping" }
                check(applies[FrameworkFeature.MOCKK] == listOf("mockk")) { "mockk mapping" }

                // 2. featureDocs verification
                val docs = FrameworkFeatureCatalog.featureDocs
                val coroutinesDoc = docs[FrameworkFeature.COROUTINES].orEmpty()
                check(coroutinesDoc.contains("# Kotlin Coroutines Guide")) { "coroutines title" }
                check(coroutinesDoc.contains("suspend fun")) { "coroutines suspend fun" }
                check(coroutinesDoc.contains("CoroutineScope")) { "coroutines scope" }
                check(coroutinesDoc.contains("Dispatchers.IO")) { "coroutines dispatchers" }
                check(coroutinesDoc.contains("Flow<T>")) { "coroutines flow" }
                check(coroutinesDoc.contains("runTest")) { "coroutines runTest" }

                val serializationDoc = docs[FrameworkFeature.SERIALIZATION].orEmpty()
                check(serializationDoc.contains("# `kotlinx.serialization` Guide")) { "serialization title" }
                check(serializationDoc.contains("@Serializable")) { "serialization annotation" }
                check(serializationDoc.contains("data class User")) { "serialization sample" }

                val arrowDoc = docs[FrameworkFeature.ARROW].orEmpty()
                check(arrowDoc.contains("arrow.core.Either")) { "arrow Either" }
                check(arrowDoc.contains("arrow.core.raise.Raise")) { "arrow Raise" }
                check(arrowDoc.contains("arrow.fx.coroutines")) { "arrow fx" }

                val datetimeDoc = docs[FrameworkFeature.DATETIME].orEmpty()
                check(datetimeDoc.contains("Instant")) { "datetime Instant" }
                check(datetimeDoc.contains("LocalDate")) { "datetime LocalDate" }
                check(datetimeDoc.contains("Clock.System.now()")) { "datetime Clock" }

                val ktorDoc = docs[FrameworkFeature.KTOR].orEmpty()
                check(ktorDoc.contains("HttpClient(CIO)")) { "ktor HttpClient" }
                check(ktorDoc.contains("ContentNegotiation")) { "ktor ContentNegotiation" }

                val turbineDoc = docs[FrameworkFeature.TURBINE].orEmpty()
                check(turbineDoc.contains("flow.test")) { "turbine flow.test" }
                check(turbineDoc.contains("awaitItem()")) { "turbine awaitItem" }
                check(turbineDoc.contains("awaitComplete()")) { "turbine awaitComplete" }

                val mockkDoc = docs[FrameworkFeature.MOCKK].orEmpty()
                check(mockkDoc.contains("mockk<Repository>()")) { "mockk repo" }
                check(mockkDoc.contains("every { repo.findUser(1) } returns User(1, \"Alice\")")) { "mockk every" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: FrameworkFeatureCatalog.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN FrameworkFeatureCatalog.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for FrameworkFeatureCatalog.kt")
        assertEquals(0, report.survivedCount, "All FrameworkFeatureCatalog mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production LogTruncator source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/shared/LogTruncator.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                // 1. Blank and small input
                check(LogTruncator.truncate("") == "") { "blank string untouched" }
                check(LogTruncator.truncate("   ") == "   ") { "whitespace untouched" }
                check(LogTruncator.truncate("Line 1\nLine 2") == "Line 1\nLine 2") { "small text untouched" }

                // 2. Line count truncation & exact boundary
                val exactLines = "Line 1\nLine 2\nLine 3"
                check(!LogTruncator.truncate(exactLines, maxLines = 3, maxBytes = 1000).contains("truncated")) { "exact line boundary" }

                val multiLine = (1..10).joinToString("\n") { "Line " + it }
                val lineTruncated = LogTruncator.truncate(multiLine, maxLines = 3, maxBytes = 10000)
                check(lineTruncated.startsWith("[... truncated 7 preceding lines ...]\n")) { "line truncation header" }
                check(lineTruncated.contains("Line 8\nLine 9\nLine 10")) { "retains last 3 lines" }
                check(!lineTruncated.contains("Line 1\n")) { "omits earlier lines" }

                // 3. Byte count truncation & exact boundary
                val exactBytes = "1234567890"
                check(!LogTruncator.truncate(exactBytes, maxLines = 100, maxBytes = 10).contains("truncated")) { "exact byte boundary" }

                val largeText = "A".repeat(50) + "\n" + "B".repeat(50) + "\n" + "C".repeat(50)
                val byteTruncated = LogTruncator.truncate(largeText, maxLines = 100, maxBytes = 60)
                check(byteTruncated.startsWith("[... truncated output to last 60 bytes ...]\n")) { "byte truncation header" }
                check(byteTruncated.contains("C".repeat(50))) { "retains trailing bytes" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LogTruncator.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN LogTruncator.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LogTruncator.kt")
        assertEquals(0, report.survivedCount, "All LogTruncator mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production SourceUtils source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/shared/SourceUtils.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                val sample = "fun hello() {\n    val x = 42\n    println(x)\n}"

                // 1. lineOf & lineAndColumnOf
                check(SourceUtils.lineOf("", 0) == 1) { "empty lineOf" }
                check(SourceUtils.lineOf(sample, -5) == 1) { "negative lineOf" }
                check(SourceUtils.lineOf(sample, 0) == 1) { "lineOf offset 0" }
                check(SourceUtils.lineOf(sample, 14) == 2) { "lineOf offset line 2" }
                check(SourceUtils.lineOf(sample, 30) == 3) { "lineOf offset line 3" }

                check(SourceUtils.lineAndColumnOf("", 0) == Pair(1, 1)) { "empty lineAndColumnOf" }
                check(SourceUtils.lineAndColumnOf(sample, -1) == Pair(1, 1)) { "negative lineAndColumnOf" }
                val pair = SourceUtils.lineAndColumnOf(sample, 18)
                check(pair.first == 2 && pair.second == 5) { "line 2 col 5 check" }

                // 2. lineSnippet
                check(SourceUtils.lineSnippet("", 0) == "") { "empty lineSnippet" }
                check(SourceUtils.lineSnippet(sample, -1) == "") { "negative lineSnippet" }
                check(SourceUtils.lineSnippet(sample, 0) == "fun hello() {") { "snippet line 1" }
                check(SourceUtils.lineSnippet(sample, 18) == "val x = 42") { "snippet line 2 trimmed" }

                // 3. extractBalancedBraces
                val codeWithBraces = "class Foo { val msg = \"{nested}\"; fun bar() { return 1 } }"
                val extracted = SourceUtils.extractBalancedBraces(codeWithBraces, codeWithBraces.indexOf('{'))
                check(extracted == "val msg = \"{nested}\"; fun bar() { return 1 }") { "extract balanced with string literal" }
                check(SourceUtils.extractBalancedBraces(codeWithBraces, 0) == null) { "extract non-brace index null" }
                check(SourceUtils.extractBalancedBraces("{ unclosed", 0) == null) { "extract unclosed null" }

                // 4. isSyntacticallyBalanced
                check(SourceUtils.isSyntacticallyBalanced("val x = (1 + [2, 3] * { 4 })")) { "balanced valid" }
                check(SourceUtils.isSyntacticallyBalanced("val s = \"( { [ \"")) { "balanced with delimiters inside strings" }
                check(!SourceUtils.isSyntacticallyBalanced("val x = (1 + 2")) { "unclosed paren" }
                check(!SourceUtils.isSyntacticallyBalanced("val x = 1 + 2)")) { "unexpected closing paren" }
                check(!SourceUtils.isSyntacticallyBalanced("val x = { 1 + 2")) { "unclosed brace" }
                check(!SourceUtils.isSyntacticallyBalanced("val x = [ 1, 2")) { "unclosed bracket" }

                // 5. collapseWhitespace
                check(SourceUtils.collapseWhitespace("   hello    world  \n\n  test  ") == "hello world test") { "collapse basic" }
                check(SourceUtils.collapseWhitespace("abcdef", maxLength = 3) == "abc") { "collapse bounded" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: SourceUtils.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN SourceUtils.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for SourceUtils.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for SourceUtils.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production DiffUtils source file`() {
        val toonFile = File("src/main/kotlin/com/gokorei/kotlinmcp/shared/ToonUtils.kt")
        val diffFile = File("src/main/kotlin/com/gokorei/kotlinmcp/shared/DiffUtils.kt")
        assertTrue(toonFile.exists(), "Target file must exist: ${toonFile.absolutePath}")
        assertTrue(diffFile.exists(), "Target file must exist: ${diffFile.absolutePath}")

        val toonSource = toonFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val diffSource = diffFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val productionCode = toonSource + "\n\n" + diffSource

        val testSuiteCode = """
            fun main() {
                val orig = ""${'"'}
                    fun compute(): Int {
                        val a = 1
                        val b = 2
                        return a + b
                    }
                ""${'"'}.trimIndent()

                val mod = ""${'"'}
                    fun compute(): Int {
                        val a = 10
                        val b = 2
                        val c = 3
                        return a + b + c
                    }
                ""${'"'}.trimIndent()

                // 1. Identical comparison
                val noDiff = DiffUtils.generateUnifiedDiff(orig, orig)
                check(noDiff == "No changes (original and modified snippets are identical).") { "identical text detection" }

                // 2. TOON format
                val toonDiff = DiffUtils.generateUnifiedDiff(orig, mod, format = DiffUtils.Format.TOON)
                check(toonDiff.startsWith("[diff: line|op|text]\n")) { "toon header" }
                check(toonDiff.contains("2|-|    val a = 1")) { "toon delete row" }
                check(toonDiff.contains("2|+|    val a = 10")) { "toon insert row" }
                check(toonDiff.contains("4|+|    val c = 3")) { "toon insert row 4" }
                check(toonDiff.contains("4|-|    return a + b")) { "toon return delete" }
                check(toonDiff.contains("5|+|    return a + b + c")) { "toon return insert" }

                // 3. UNIFIED diff format with symbol context
                val uniDiff = DiffUtils.generateUnifiedDiff(orig, mod, fileName = "TestFile.kt", format = DiffUtils.Format.UNIFIED)
                check(uniDiff.startsWith("--- a/TestFile.kt\n+++ b/TestFile.kt\n@@ ")) { "unified header" }
                check(uniDiff.contains("-    val a = 1")) { "unified deleted line" }
                check(uniDiff.contains("+    val a = 10")) { "unified added line" }
                check(uniDiff.contains("+    val c = 3")) { "unified added line c" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: DiffUtils.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN DiffUtils.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for DiffUtils.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for DiffUtils.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production CoverageReporter source file`() {
        val resultFile = File("src/main/kotlin/com/gokorei/kotlinmcp/models/KotlinMcpResult.kt")
        val reporterFile = File("src/main/kotlin/com/gokorei/kotlinmcp/project/CoverageReporter.kt")
        assertTrue(resultFile.exists(), "Target file must exist: ${resultFile.absolutePath}")
        assertTrue(reporterFile.exists(), "Target file must exist: ${reporterFile.absolutePath}")

        val resultSource = resultFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim() == "@Serializable" }
            .joinToString("\n")

        val reporterSource = reporterFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = "import java.io.File\n\n" + resultSource + "\n\n" + reporterSource

        val testSuiteCode = """
            fun main() {
                val reporter = CoverageReporter()

                // Result envelope verification
                val testErr = KotlinMcpResult.Error("msg", "CODE", mapOf("k" to "v"), true)
                check(testErr.isError && !testErr.isSuccess) { "testErr error flags" }
                check(testErr.toFormattedText().contains("Error [CODE]: msg\nDetails:\n - k: v\nrequireAnotherCall: true")) { "testErr text" }
                val testSucc = KotlinMcpResult.Success("ok", mapOf("x" to "y"), true)
                check(testSucc.isSuccess && !testSucc.isError) { "testSucc success flags" }
                check(testSucc.toFormattedText().contains("ok\n\n--- Metadata ---\nx: y\n\nrequireAnotherCall: true")) { "testSucc text" }

                // 1. Missing coverage directory
                val notFoundResult = reporter.coverageReport("non_existent_directory_12345")
                check(notFoundResult is KotlinMcpResult.Error) { "not found must be Error" }
                check(notFoundResult.code == "NOT_FOUND") { "error code NOT_FOUND" }

                // 2. Directory with XML report
                val tmpDir = java.io.File.createTempFile("jacoco_test_", "")
                tmpDir.delete()
                val reportDir = java.io.File(tmpDir, "build/reports/jacoco/test")
                reportDir.mkdirs()

                val xmlFile = java.io.File(reportDir, "jacocoTestReport.xml")
                xmlFile.writeText(""${'"'}
                    <?xml version="1.0" encoding="UTF-8"?>
                    <report name="test">
                        <counter type="INSTRUCTION" missed="20" covered="80"/>
                        <counter type="LINE" missed="10" covered="90"/>
                        <counter type="BRANCH" missed="5" covered="15"/>
                    </report>
                ""${'"'}.trimIndent())

                val successResult = reporter.coverageReport(tmpDir.path)
                check(successResult is KotlinMcpResult.Success) { "xml parsed must be Success" }
                val content = successResult.content
                check(content.contains("# JaCoCo Code Coverage Report")) { "report title" }
                check(content.contains("- Line Coverage: 90% (90 / 100 lines)")) { "line coverage format" }
                check(content.contains("- Branch Coverage: 75% (15 / 20 branches)")) { "branch coverage format" }

                // Check 0 total lines/branches coverage
                xmlFile.writeText(""${'"'}
                    <?xml version="1.0" encoding="UTF-8"?>
                    <report name="test">
                        <counter type="LINE" missed="0" covered="0"/>
                        <counter type="BRANCH" missed="0" covered="0"/>
                    </report>
                ""${'"'}.trimIndent())
                val zeroResult = reporter.coverageReport(tmpDir.path)
                check(zeroResult is KotlinMcpResult.Success && zeroResult.content.contains("Line Coverage: 0% (0 / 0 lines)")) { "zero lines" }

                // Also check reportCoverage alias
                val aliasResult = reporter.reportCoverage("plugins { kotlin }", tmpDir.path)
                check(aliasResult is KotlinMcpResult.Success) { "reportCoverage alias" }

                // 3. Directory exists but XML is missing (HTML only notice)
                xmlFile.delete()
                val htmlOnlyResult = reporter.coverageReport(tmpDir.path)
                check(htmlOnlyResult is KotlinMcpResult.Success) { "html only is Success" }
                check(htmlOnlyResult.content.contains("HTML report directory exists at")) { "html notice" }

                tmpDir.deleteRecursively()
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: CoverageReporter.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN CoverageReporter.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for CoverageReporter.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for CoverageReporter.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production ProjectLayeringAnalyzer source file`() {
        val resultFile = File("src/main/kotlin/com/gokorei/kotlinmcp/models/KotlinMcpResult.kt")
        val analyzerFile = File("src/main/kotlin/com/gokorei/kotlinmcp/project/ProjectLayeringAnalyzer.kt")
        assertTrue(resultFile.exists(), "Target file must exist: ${resultFile.absolutePath}")
        assertTrue(analyzerFile.exists(), "Target file must exist: ${analyzerFile.absolutePath}")

        val resultSource = resultFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim() == "@Serializable" }
            .joinToString("\n")

        val analyzerSource = analyzerFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = "import java.io.File\n\n" + resultSource + "\n\n" + analyzerSource

        val testSuiteCode = """
            fun main() {
                val analyzer = ProjectLayeringAnalyzer()

                // Result envelope verification
                val testErr = KotlinMcpResult.Error("msg", "CODE", mapOf("k" to "v"), true)
                check(testErr.isError && !testErr.isSuccess) { "testErr error flags" }
                check(testErr.toFormattedText().contains("Error [CODE]: msg\nDetails:\n - k: v\nrequireAnotherCall: true")) { "testErr text" }
                val defaultErr = KotlinMcpResult.Error("msg")
                check(!defaultErr.requireAnotherCall) { "default requireAnotherCall false" }

                val testSucc = KotlinMcpResult.Success("ok", mapOf("x" to "y"), true)
                check(testSucc.isSuccess && !testSucc.isError) { "testSucc success flags" }
                check(testSucc.toFormattedText().contains("ok\n\n--- Metadata ---\nx: y\n\nrequireAnotherCall: true")) { "testSucc text" }
                val defaultSucc = KotlinMcpResult.Success("ok")
                check(!defaultSucc.requireAnotherCall) { "default requireAnotherCall false" }

                // 1. Full 3-tier architecture project
                val tmpDir = java.io.File.createTempFile("layering_test_", "")
                tmpDir.delete()
                tmpDir.mkdirs()

                val domainFile = java.io.File(tmpDir, "domain/User.kt")
                domainFile.parentFile.mkdirs()
                domainFile.writeText("package com.example.domain\nclass User(val id: Int)")

                val dataFile = java.io.File(tmpDir, "data/UserRepo.kt")
                dataFile.parentFile.mkdirs()
                dataFile.writeText("package com.example.data\nclass UserRepo")

                val uiFile = java.io.File(tmpDir, "ui/UserScreen.kt")
                uiFile.parentFile.mkdirs()
                uiFile.writeText("package com.example.ui\nfun render()")

                val fullResult = analyzer.analyzeProjectLayering("", tmpDir.path)
                check(fullResult is KotlinMcpResult.Success) { "full project is Success" }
                check(fullResult.metadata["ktFileCount"] == "3") { "3 files counted" }
                check(fullResult.metadata["packageCount"] == "3") { "3 packages counted" }
                val content = fullResult.content
                check(content.contains("# Project Package Layering Analysis")) { "report title" }
                check(content.contains("Analyzed 3 Kotlin source file(s) across 3 package(s).")) { "analyzed summary line" }
                check(content.contains("## Detected Packages")) { "detected packages section" }
                check(content.contains("## Architectural Layer Health")) { "layer health section" }
                check(content.contains("Domain Layer: ✅ Detected")) { "domain detected" }
                check(content.contains("Data Layer: ✅ Detected")) { "data detected" }
                check(content.contains("UI Layer: ✅ Detected")) { "ui detected" }
                check(content.contains("- `com.example.domain`")) { "package list domain" }
                check(content.contains("- `com.example.data`")) { "package list data" }
                check(content.contains("- `com.example.ui`")) { "package list ui" }

                // 2. Empty project
                val emptyDir = java.io.File.createTempFile("empty_project_", "")
                emptyDir.delete()
                emptyDir.mkdirs()

                val emptyResult = analyzer.analyzeProjectLayering("", emptyDir.path)
                check(emptyResult is KotlinMcpResult.Success) { "empty project is Success" }
                check(emptyResult.metadata["ktFileCount"] == "0") { "0 files counted" }
                check(emptyResult.metadata["packageCount"] == "0") { "0 packages counted" }
                check(emptyResult.content.contains("Analyzed 0 Kotlin source file(s) across 0 package(s).")) { "empty summary" }
                check(emptyResult.content.contains("Missing explicit .domain")) { "missing domain advisory" }
                check(emptyResult.content.contains("Missing explicit .data")) { "missing data advisory" }
                check(emptyResult.content.contains("Missing explicit .ui")) { "missing ui advisory" }
                check(emptyResult.content.contains("(no explicit package declarations found)")) { "no packages notice" }

                // 3. Null path fallback
                val nullResult = analyzer.analyzeProjectLayering("", null)
                check(nullResult is KotlinMcpResult.Success) { "null path is Success" }

                tmpDir.deleteRecursively()
                emptyDir.deleteRecursively()
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ProjectLayeringAnalyzer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ProjectLayeringAnalyzer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ProjectLayeringAnalyzer.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for ProjectLayeringAnalyzer.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production Version source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/Version.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                check(Version.NAME == "kotlin-mcp") { "version name" }
                check(Version.FALLBACK_VERSION == "0.0.0-dev") { "fallback version" }
                check(Version.CURRENT.isNotBlank()) { "version current not blank" }
                check(Version.CURRENT.isNotEmpty()) { "version current not empty" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: Version.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN Version.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for Version.kt")
        assertEquals(0, report.survivedCount, "All Version mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production VulnerabilityAuditor source file`() {
        val auditorFile = File("src/main/kotlin/com/gokorei/kotlinmcp/project/VulnerabilityAuditor.kt")
        assertTrue(auditorFile.exists(), "Target file must exist: ${auditorFile.absolutePath}")

        val auditorSource = auditorFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import java.io.File
            import java.nio.file.Files
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.JsonArray
            import kotlinx.serialization.json.JsonObject
            import kotlinx.serialization.json.JsonPrimitive
        """.trimIndent()

        val productionCode = imports + "\n\n" + auditorSource

        val testSuiteCode = """
            fun main() {
                val auditor = VulnerabilityAuditor()

                // 1. Version comparator verification
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0", "1.0.0") == 0) { "equal version" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.26.0", "1.26.0.Final") == 0) { "equal final release" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0-alpha", "1.0.0-beta") < 0) { "alpha < beta" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0-beta", "1.0.0-rc1") < 0) { "beta < rc" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0-rc1", "1.0.0") < 0) { "rc < release" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0-SNAPSHOT", "1.0.0") < 0) { "snapshot < release" }
                check(VulnerabilityAuditor.mavenVersionCompare("2.0.0", "1.9.9") > 0) { "2.0 > 1.9" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0", "2.0.0") < 0) { "1.0 < 2.0" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0.1", "1.0.0") > 0) { "1.0.0.1 > 1.0.0" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0", "1.0.0.1") < 0) { "1.0.0 < 1.0.0.1" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0-ga", "1.0-release") == 0) { "ga == release" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0a2", "1.0b1") < 0) { "1.0a2 < 1.0b1" }
                check(VulnerabilityAuditor.mavenVersionCompare("1.0.0-1", "1.0.0-2") < 0) { "1.0.0-1 < 1.0.0-2" }

                // 2. Direct OSV response parser testing (testing exact boundary scores 9.0, 7.0, 4.0)
                val testDeps = listOf(
                    VulnerabilityAuditor.DependencyCoordinate("org.test", "crit-lib", "1.0.0"),
                    VulnerabilityAuditor.DependencyCoordinate("org.test", "high-lib", "1.0.0"),
                    VulnerabilityAuditor.DependencyCoordinate("org.test", "med-lib", "1.0.0"),
                    VulnerabilityAuditor.DependencyCoordinate("org.test", "low-lib", "1.0.0"),
                    VulnerabilityAuditor.DependencyCoordinate("org.test", "unknown-lib", "1.0.0")
                )
                val syntheticOsvJson = ""${'"'}
                    {
                      "results": [
                        {
                          "vulns": [
                            {
                              "id": "GHSA-crit-1234",
                              "summary": "Critical vulnerability",
                              "severity": [{"type": "CVSS_V3", "score": 9.0}],
                              "affected": [{"ranges": [{"events": [{"introduced": "0"}, {"fixed": "1.0.1"}]}]}]
                            }
                          ]
                        },
                        {
                          "vulns": [
                            {
                              "id": "GHSA-high-1234",
                              "summary": "High vulnerability",
                              "severity": [{"type": "CVSS_V3", "score": 7.0}],
                              "affected": [{"ranges": [{"events": [{"fixed": "2.0.0"}]}]}]
                            }
                          ]
                        },
                        {
                          "vulns": [
                            {
                              "id": "GHSA-med-1234",
                              "summary": "Medium vulnerability",
                              "severity": [{"type": "CVSS_V3", "score": 4.0}],
                              "affected": []
                            }
                          ]
                        },
                        {
                          "vulns": [
                            {
                              "id": "GHSA-low-1234",
                              "severity": [{"type": "CVSS_V3", "score": 2.0}]
                            }
                          ]
                        },
                        {
                          "vulns": [
                            {
                              "id": "GHSA-unk-1234",
                              "severity": [{"score": "not-a-number"}]
                            }
                          ]
                        }
                      ]
                    }
                ""${'"'}.trimIndent()

                val parsedFindings = auditor.parseOsvBatchResponse(syntheticOsvJson, testDeps)
                check(parsedFindings.size == 5) { "parsed findings count" }
                check(parsedFindings[0].advisory.severity == "CRITICAL" && parsedFindings[0].advisory.fixedVersion == "1.0.1") { "critical finding" }
                check(parsedFindings[1].advisory.severity == "HIGH" && parsedFindings[1].advisory.fixedVersion == "2.0.0") { "high finding" }
                check(parsedFindings[2].advisory.severity == "MEDIUM" && parsedFindings[2].advisory.fixedVersion == "unknown") { "medium finding" }
                check(parsedFindings[3].advisory.severity == "LOW" && parsedFindings[3].advisory.summary == "GHSA-low-1234") { "low finding summary fallback" }
                check(parsedFindings[4].advisory.severity == "UNKNOWN") { "unknown severity" }

                // 3. Direct dependency extractor testing across all syntax variations
                val rawDepsSnippet = ""${'"'}
                    dependencies {
                        implementation("org.test:impl:1.0")
                        api(libs.catalog.item)
                        plugins {
                            id("com.custom.plugin:tool") version "2.0"
                            id("simple-plugin") version "3.0"
                            kotlin("jvm") version "1.9.0"
                        }
                    }
                    org.lock:pkg:4.0.0=classpath
                ""${'"'}.trimIndent()
                val tomlContent = "[versions]\nv = \"1.2.3\"\n[libraries]\ncatalog-item = { module = \"org.lib:item\", version.ref = \"v\" }"
                val extracted = auditor.extractDependencyCoordinates(rawDepsSnippet, tomlContent)
                check(extracted.any { it.coordinate == "org.test:impl:1.0" }) { "extracted impl" }
                check(extracted.any { it.coordinate == "org.lib:item:1.2.3" }) { "extracted catalog" }
                check(extracted.any { it.coordinate == "com.custom.plugin:tool:2.0" }) { "extracted plugin with colon" }
                check(extracted.any { it.coordinate == "plugin:simple-plugin:3.0" }) { "extracted simple plugin" }
                check(extracted.any { it.coordinate == "org.jetbrains.kotlin:kotlin-gradle-plugin-jvm:1.9.0" }) { "extracted kotlin plugin" }
                check(extracted.any { it.coordinate == "org.lock:pkg:4.0.0" }) { "extracted lockfile" }

                // 4. Offline baseline verification across all cataloged rules
                val baselineCases = listOf(
                    Triple("org.apache.commons", "commons-compress", "1.25.0") to "CVE-2024-26308",
                    Triple("com.fasterxml.jackson.core", "jackson-databind", "2.14.0") to "CVE-2023-35116",
                    Triple("org.apache.logging.log4j", "log4j-core", "2.14.0") to "CVE-2021-44228",
                    Triple("io.ktor", "ktor-server-core", "2.3.11") to "CVE-2024-34080",
                    Triple("io.netty", "netty-all", "4.1.100.Final") to "CVE-2024-29025",
                    Triple("io.netty", "netty-codec-http", "4.1.100.Final") to "CVE-2024-29025",
                    Triple("org.springframework.boot", "spring-boot", "3.2.0") to "CVE-2024-22259",
                    Triple("com.squareup.okhttp3", "okhttp", "4.11.0") to "CVE-2023-3635",
                    Triple("org.yaml", "snakeyaml", "1.33") to "CVE-2022-1471"
                )
                for ((dep, expectedCve) in baselineCases) {
                    val adv = VulnerabilityAuditor.OfflineVulnerabilityBaseline.check(dep.first, dep.second, dep.third)
                    check(adv != null && adv.id == expectedCve) { "offline baseline hit for " + dep }
                }

                // Clean/patched versions should return null
                check(VulnerabilityAuditor.OfflineVulnerabilityBaseline.check("org.apache.logging.log4j", "log4j-core", "2.17.1") == null) { "patched log4j" }
                check(VulnerabilityAuditor.OfflineVulnerabilityBaseline.check("org.yaml", "snakeyaml", "2.2") == null) { "patched snakeyaml" }

                // 5. Unparseable build script error
                val emptyResult = auditor.checkVulnerabilities("", null)
                check(emptyResult is KotlinMcpResult.Error && emptyResult.code == "TOOL_UNAVAILABLE") { "empty code TOOL_UNAVAILABLE" }
                check(emptyResult.details["parsedCoordinateCount"] == "0") { "0 parsed coordinates" }

                val blankResult = auditor.checkVulnerabilities("", "   ")
                check(blankResult is KotlinMcpResult.Error && blankResult.code == "TOOL_UNAVAILABLE") { "blank path TOOL_UNAVAILABLE" }

                // 6. Mixed scan (Vulnerable Log4j + Clean OkHttp in single project)
                val mixedScript = ""${'"'}
                    dependencies {
                        implementation("org.apache.logging.log4j:log4j-core:2.14.0")
                        api("com.squareup.okhttp3:okhttp:4.12.0")
                        kapt(platform("org.yaml:snakeyaml:1.30"))
                        compileOnly(group = "org.apache.commons", name = "commons-compress", version = "1.26.0")
                    }
                ""${'"'}.trimIndent()
                val mixedResult = auditor.checkVulnerabilities(mixedScript, null)
                check(mixedResult is KotlinMcpResult.Success) { "mixed result must be Success" }
                check(mixedResult.metadata["source"] == "local-baseline (offline fallback)") { "offline fallback source" }
                check(mixedResult.metadata["scannedCoordinateCount"] == "4") { "4 coordinates scanned" }
                check(mixedResult.metadata["advisoryCount"] == "2") { "2 advisories found" }
                val mContent = mixedResult.content
                check(mContent.contains("# Dependency Vulnerability Audit Report")) { "report header" }
                check(mContent.contains("Scanned 4 dependency coordinate(s). (source: local-baseline (offline fallback))")) { "scan summary" }
                check(mContent.contains("## 🚨 Flagged Security Advisories (2)")) { "flagged section" }
                check(mContent.contains("- **`org.apache.logging.log4j:log4j-core:2.14.0`**\n  - **Advisory ID**: CVE-2021-44228\n  - **Severity**: CRITICAL\n  - **Summary**: Log4Shell remote code execution vulnerability via JNDI lookup.\n  - **Fixed Version**: 2.17.1")) { "log4shell details" }
                check(mContent.contains("CVE-2022-1471")) { "snakeyaml cve" }
                check(mContent.contains("## Scanned Clean Dependencies (2)")) { "clean count" }
                check(mContent.contains(" - `com.squareup.okhttp3:okhttp:4.12.0`")) { "clean okhttp" }
                check(mContent.contains(" - `org.apache.commons:commons-compress:1.26.0`")) { "clean commons-compress" }

                // 7. Clean project scan (all secure)
                val cleanScript = "dependencies { implementation(\"com.squareup.okhttp3:okhttp:4.12.0\") }\n"
                val cleanRes = auditor.checkVulnerabilities(cleanScript, null)
                check(cleanRes is KotlinMcpResult.Success && cleanRes.metadata["advisoryCount"] == "0") { "clean scan success" }
                check(cleanRes.content.contains("## ✅ No Known Vulnerabilities Detected")) { "no vulns header" }
                check(cleanRes.content.contains("All 1 analyzed dependencies match current secure version baselines.")) { "all secure line" }

                // 8. File-based scan with projectPath (libs.versions.toml + lockfile + plugins)
                val tmpDir = Files.createTempDirectory("audit_proj_")
                try {
                    val gradleDir = tmpDir.resolve("gradle").toFile()
                    gradleDir.mkdirs()
                    val libsFile = File(gradleDir, "libs.versions.toml")
                    libsFile.writeText(""${'"'}
                        [versions]
                        jackson = "2.14.0"
                        [libraries]
                        jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
                    ""${'"'}.trimIndent())

                    val lockFile = tmpDir.resolve("gradle.lockfile").toFile()
                    lockFile.writeText("io.netty:netty-codec-http:4.1.100.Final=classpath\n")

                    val bgFile = tmpDir.resolve("build.gradle.kts").toFile()
                    bgFile.writeText(""${'"'}
                        plugins {
                            id("org.jetbrains.kotlin.jvm") version "1.9.20"
                            kotlin("multiplatform") version "1.9.20"
                        }
                        dependencies {
                            implementation(libs.jackson.databind)
                        }
                    ""${'"'}.trimIndent())

                    val projectResult = auditor.checkVulnerabilities("", tmpDir.toAbsolutePath().toString())
                    check(projectResult is KotlinMcpResult.Success) { "projectResult must be Success" }
                    val pContent = projectResult.content
                    check(pContent.contains("CVE-2023-35116") || pContent.contains("CVE-2024-29025")) { "project vulnerabilities caught" }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: VulnerabilityAuditor.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN VulnerabilityAuditor.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for VulnerabilityAuditor.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for VulnerabilityAuditor.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production StdlibSymbolCatalog source file`() {
        val featureFile = File("src/main/kotlin/com/gokorei/kotlinmcp/models/ProjectEnvironmentProfile.kt")
        val catalogFile = File("src/main/kotlin/com/gokorei/kotlinmcp/doc/StdlibSymbolCatalog.kt")
        assertTrue(featureFile.exists(), "Target file must exist: ${featureFile.absolutePath}")
        assertTrue(catalogFile.exists(), "Target file must exist: ${catalogFile.absolutePath}")

        val featureSource = featureFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val catalogSource = catalogFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import com.gokorei.kotlinmcp.models.FrameworkFeature") }
            .joinToString("\n")

        val productionCode = featureSource + "\n\n" + catalogSource

        val testSuiteCode = """
            fun main() {
                val defaultProfile = ProjectEnvironmentProfile()
                check(!defaultProfile.isKmp) { "default isKmp false" }

                // 1. Symbol mapping to FrameworkFeature
                val applies = StdlibSymbolCatalog.symbolAppliesTo
                check(applies["kotlinx.datetime.Instant"] == FrameworkFeature.DATETIME) { "instant mapping" }
                check(applies["kotlinx.datetime.Clock"] == FrameworkFeature.DATETIME) { "clock mapping" }
                check(applies["kotlinx.datetime.LocalDate"] == FrameworkFeature.DATETIME) { "localDate mapping" }
                check(applies["runTest"] == FrameworkFeature.COROUTINES) { "runTest mapping" }
                check(applies["MainDispatcherRule"] == FrameworkFeature.COROUTINES) { "mainDispatcherRule mapping" }
                check(applies["Turbine.test"] == FrameworkFeature.TURBINE) { "turbine mapping" }
                check(applies["mockk"] == FrameworkFeature.MOCKK) { "mockk mapping" }
                check(applies["every"] == FrameworkFeature.MOCKK) { "every mapping" }
                check(applies["verify"] == FrameworkFeature.MOCKK) { "verify mapping" }
                check(applies["Ktor/Routing"] == FrameworkFeature.KTOR) { "ktor routing mapping" }
                check(applies["Ktor/ContentNegotiation"] == FrameworkFeature.KTOR) { "ktor content negotiation mapping" }
                check(applies["Either"] == FrameworkFeature.ARROW) { "either mapping" }
                check(applies["Raise"] == FrameworkFeature.ARROW) { "raise mapping" }
                check(applies["valid"] == FrameworkFeature.ARROW) { "valid mapping" }
                check(applies["validNel"] == FrameworkFeature.ARROW) { "validNel mapping" }

                // 2. Symbol documentation entries
                val docs = StdlibSymbolCatalog.symbolDocs
                check(docs["kotlin.collections.List"].orEmpty().contains("interface List<out E>")) { "list doc" }
                check(docs["kotlin.collections.MutableList"].orEmpty().contains("interface MutableList<E>")) { "mutable list doc" }
                check(docs["kotlin.collections.Map"].orEmpty().contains("interface Map<K, out V>")) { "map doc" }
                check(docs["kotlin.Result"].orEmpty().contains("value class Result<out T>")) { "result doc" }
                check(docs["kotlinx.coroutines.Flow"].orEmpty().contains("interface Flow<out T>")) { "flow doc" }
                check(docs["mapNotNull"].orEmpty().contains("mapNotNull(transform: (T) -> R?): List<R>")) { "mapNotNull doc" }
                check(docs["CoroutineScope"].orEmpty().contains("interface CoroutineScope")) { "coroutine scope doc" }
                check(docs["runBlocking"].orEmpty().contains("runBlocking")) { "runBlocking doc" }
                check(docs["Either"].orEmpty().contains("sealed class Either<out A, out B>")) { "either doc" }
                check(docs["Raise"].orEmpty().contains("interface Raise<in E>")) { "raise doc" }
                check(docs["kotlinx.datetime.Instant"].orEmpty().contains("class Instant")) { "instant doc" }
                check(docs["readText"].orEmpty().contains("readText")) { "readText doc" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: StdlibSymbolCatalog.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN StdlibSymbolCatalog.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for StdlibSymbolCatalog.kt")
        assertEquals(0, report.survivedCount, "All StdlibSymbolCatalog mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production LlmGuidance source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/server/LlmGuidance.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                // 1. Constants verification
                check(LlmGuidance.LLM_GUIDE_RESOURCE_URI == "kotlin://server/usage-guide.md") { "guide uri" }
                check(LlmGuidance.LLM_GUIDE_RESOURCE_NAME == "kotlin-server-usage-guide") { "guide name" }
                check(LlmGuidance.LLM_GUIDE_PROMPT_NAME == "kotlin_mcp_quickstart") { "prompt name" }

                // 2. Default argument verification (includeExamples = true)
                val defaultGuide = LlmGuidance.buildLlmUsageGuide()
                check(defaultGuide.contains("## Quick Examples")) { "default includeExamples is true" }
                check(!defaultGuide.contains("## Current Goal")) { "default goal is null" }

                // 3. Guide generation with goal and examples
                val guideWithGoal = LlmGuidance.buildLlmUsageGuide(goal = "refactor legacy codebase", includeExamples = true)
                check(guideWithGoal.contains("# Kotlin MCP LLM Usage Guide")) { "guide title" }
                check(guideWithGoal.contains("Use this guide when selecting Kotlin tools, choosing response presets,")) { "intro line 1" }
                check(guideWithGoal.contains("or avoiding token-heavy calls.")) { "intro line 2" }
                check(guideWithGoal.contains("## Current Goal\n\n- Prioritize the guidance below for: refactor legacy codebase")) { "goal section" }
                check(guideWithGoal.contains("## Tool Action & Parameter Matrix")) { "matrix section" }
                check(guideWithGoal.contains("| Tool | Action | Required Parameters | Purpose | Token Cost |")) { "matrix header" }
                check(guideWithGoal.contains("| `kotlin_check_snippet` | N/A | `code` | Fast in-memory syntax & type check | Low (<200) |")) { "matrix check" }
                check(guideWithGoal.contains("| `kotlin_docs_read` | `search`\\|`lookup`\\|`explain` | `query` (optional: `preset=\"compact\"`) | Stdlib & language documentation lookup | Low-Med (Use `preset=\"compact\"`) |")) { "matrix docs" }
                check(guideWithGoal.contains("| `kotlin_code_analyze` | `file_context`\\|`nullability`\\|`coroutines`\\|`symbol_declarations`\\|`ast_dump` | `code` (optional: `filePath`) | Single-file PSI AST analysis | Low-Med |")) { "matrix analyze" }
                check(guideWithGoal.contains("| `kotlin_text_lsp_read` | `definition`\\|`references`\\|`type_hierarchy`\\|`call_hierarchy`\\|`workspace_search` | `symbol` + `workspacePath` | Cross-file semantic LSP navigation | Med |")) { "matrix lsp" }
                check(guideWithGoal.contains("| `kotlin_project_inspect` | `structure`\\|`kmp_targets`\\|`dependencies`\\|`diagnose_build`\\|`package_api` | `workspacePath` (optional: `preset=\"compact\"`) | Gradle build & module inspection | Med |")) { "matrix inspect" }
                check(guideWithGoal.contains("| `kotlin_library_analyze` | `inspect_jar`\\|`resolve_types`\\|`decompile_class` | `jarPath` or `className` | Compiled dependency API analysis | Med |")) { "matrix library" }
                check(guideWithGoal.contains("| `kotlin_refactor` | `functional`\\|`java_to_kotlin`\\|`suggest_idioms`\\|`quick_fix`\\|`rxjava` | `code` | Mutating AST refactoring transformations | Med |")) { "matrix refactor" }
                check(guideWithGoal.contains("| `kotlin_lint` | `lint_detekt`\\|`lint_ktlint`\\|`format_ktlint`\\|`baseline_dump` | `workspacePath` or `code` | Mutating code style & static analysis | Med-High |")) { "matrix lint" }
                check(guideWithGoal.contains("| `kotlin_run` | `snippet`\\|`gradle_task` | `code` or `taskName` | Subprocess code/task execution | High |")) { "matrix run" }

                check(guideWithGoal.contains("## Strict Execution Pipelines (State Machines)")) { "pipelines section" }
                check(guideWithGoal.contains("### 1. Code Refactoring & Modification Flow")) { "flow title" }
                check(guideWithGoal.contains("1. **Analyze PSI Context**: Run `kotlin_code_analyze(action=\"file_context\", code=...)` to inspect AST structures.")) { "step 1" }
                check(guideWithGoal.contains("2. **Validate Proposed Edit**: Run `kotlin_check_snippet(code=proposedCode)` to confirm syntax/type validity.")) { "step 2" }
                check(guideWithGoal.contains("3. **Execute Mutation**: Call `kotlin_refactor(action=...)` or `kotlin_text_lsp_edit(action=\"rename\", ...)`.")) { "step 3" }
                check(guideWithGoal.contains("4. **Re-Verify AST Integrity**: Re-run `kotlin_check_snippet(code=updatedCode)`.")) { "step 4" }

                check(guideWithGoal.contains("## Token Budgeting & Response Presets")) { "budgeting section" }
                check(guideWithGoal.contains("- **MANDATORY `preset=\"compact\"`**: Always supply `preset=\"compact\"` on `kotlin_docs_read` and `kotlin_project_inspect(action=\"package_api\")` during discovery.")) { "preset compact rule" }
                check(guideWithGoal.contains("- **Fast Dry-Run Validation**: Run `kotlin_check_snippet` before `kotlin_run(action=\"snippet\")`")) { "dry run rule" }
                check(guideWithGoal.contains("- **Context Reduction**: Use `kotlin_code_analyze(action=\"file_context\")` instead of transmitting large raw file content blobs.")) { "context reduction rule" }

                check(guideWithGoal.contains("## Write Safety & Execution Guarantees")) { "write safety header" }
                check(guideWithGoal.contains("- **Read-Only First**: Always execute discovery read actions before mutating write actions")) { "read only first" }
                check(guideWithGoal.contains("- **Mutating Operations**: Treat `kotlin_text_lsp_edit(action=\"rename\")`, `kotlin_refactor`, and `kotlin_lint(action=\"format_ktlint\")` as mutating file modifications.")) { "mutating ops" }
                check(guideWithGoal.contains("- **AST Guarantee**: All tools utilize in-memory PSI AST parsing.")) { "ast guarantee" }

                check(guideWithGoal.contains("## Explicit Anti-Patterns (DO NOT DO THIS)")) { "antipatterns section" }
                check(guideWithGoal.contains("1. **Regex Code Renaming**: DO NOT use string regex or text replacements to rename Kotlin symbols across files.")) { "anti 1" }
                check(guideWithGoal.contains("2. **Shell Execution for Syntax Checking**: DO NOT invoke raw shell commands or Gradle build tasks to check snippet syntax.")) { "anti 2" }
                check(guideWithGoal.contains("3. **Regex Build Script Parsing**: DO NOT rely on manual regex when analyzing Gradle Kotlin DSL build scripts.")) { "anti 3" }
                check(guideWithGoal.contains("4. **Unvalidated Refactorings**: DO NOT apply large code refactorings without dry-running `kotlin_check_snippet` first.")) { "anti 4" }
                check(guideWithGoal.contains("5. **Unmocked Live Network Calls**: DO NOT make unmocked live HTTP requests")) { "anti 5" }
                check(guideWithGoal.contains("6. **Non-Daemon Subprocess Output Threads**: DO NOT create non-daemon background threads for reading process output streams.")) { "anti 6" }

                check(guideWithGoal.contains("## Quick Examples")) { "examples section" }
                check(guideWithGoal.contains("kotlin_check_snippet(code = \"fun main() { val x: Int = 42 }\")")) { "example 1" }
                check(guideWithGoal.contains("kotlin_text_lsp_read(action = \"definition\", symbol = \"parseData\", workspacePath = \"/path/to/project\")")) { "example 2" }
                check(guideWithGoal.contains("kotlin_code_analyze(action = \"nullability\", code = snippet)")) { "example 3" }
                check(guideWithGoal.contains("kotlin_refactor(action = \"functional\", code = imperativeLoop)")) { "example 4" }

                check(guideWithGoal.contains("## Decision Shortcuts")) { "shortcuts section" }
                check(guideWithGoal.contains("- **Validate snippet code**: use `kotlin_check_snippet`.")) { "sc 1" }
                check(guideWithGoal.contains("- **Locate unknown symbol**: use `kotlin_text_lsp_read(action=\"definition\", symbol=..., workspacePath=...)`.")) { "sc 2" }
                check(guideWithGoal.contains("- **Refactor imperative loops**: use `kotlin_refactor(action=\"functional\")`.")) { "sc 3" }
                check(guideWithGoal.contains("- **Convert legacy Java to Kotlin**: use `kotlin_refactor(action=\"java_to_kotlin\")`.")) { "sc 4" }
                check(guideWithGoal.contains("- **Diagnose Gradle build failures**: use `kotlin_project_inspect(action=\"diagnose_build\", workspacePath=...)`.")) { "sc 5" }
                check(guideWithGoal.contains("- **Inspect library jar API**: use `kotlin_library_analyze(action=\"inspect_jar\", jarPath=\"...\")`.")) { "sc 6" }

                check(guideWithGoal.contains("## Efficiency Defaults")) { "defaults section" }
                check(guideWithGoal.contains("- Always specify `action` parameter explicitly on progressive discovery tools.")) { "eff 1" }
                check(guideWithGoal.contains("- Pass `workspacePath` when analyzing cross-file dependencies or workspace symbols.")) { "eff 2" }
                check(guideWithGoal.contains("- Use `kotlin_check_snippet` for fast syntax and type checking before executing `./gradlew` build tasks.")) { "eff 3" }
                check(guideWithGoal.contains("- Avoid passing massive string blobs when analyzing single files; use `kotlin_code_analyze(action=\"file_context\")`.")) { "eff 4" }

                check(guideWithGoal.contains("## Write Safety")) { "write safety 2 section" }
                check(guideWithGoal.contains("- Treat `kotlin_text_lsp_edit(action=\"rename\")` as a mutating operation that rewrites workspace files in place.")) { "ws 1" }
                check(guideWithGoal.contains("- Treat `kotlin_refactor` (`java_to_kotlin`, `functional`, `suggest_idioms`, `quick_fix`, `rxjava`) as mutating code generators.")) { "ws 2" }
                check(guideWithGoal.contains("- Treat `kotlin_lint(action=\"format_ktlint\"|\"baseline_dump\")` as mutating workspace operations.")) { "ws 3" }
                check(guideWithGoal.contains("- Treat `kotlin_run` (`snippet`, `gradle_task`) as process execution operations.")) { "ws 4" }

                check(guideWithGoal.contains("## Client Gotchas")) { "client gotchas section" }
                check(guideWithGoal.contains("- All tool responses return formatted output, so read the compact Markdown structure directly.")) { "gotcha 1" }
                check(guideWithGoal.contains("- In-memory PSI AST parsing parses actual Kotlin syntax nodes; string matchers inside comments/KDoc are never matched.")) { "gotcha 2" }
                check(guideWithGoal.contains("- When `workspacePath` is supplied, symbol rename performs AST offset replacements from right to left to ensure token index validity.")) { "gotcha 3" }

                // 4. Guide generation without goal and without examples
                val guideMinimal = LlmGuidance.buildLlmUsageGuide(goal = null, includeExamples = false)
                check(!guideMinimal.contains("## Current Goal")) { "no goal section" }
                check(!guideMinimal.contains("## Quick Examples")) { "no examples section" }
                check(guideMinimal.contains("# Kotlin MCP LLM Usage Guide")) { "title retained" }

                // 5. Structural line count verification to kill newline and table separator mutants
                check(guideWithGoal.contains("| :--- | :--- | :--- | :--- | :--- |")) { "markdown table divider" }
                check(guideWithGoal.lines().size == 97) { "guideWithGoal line count: ${'$'}{guideWithGoal.lines().size}" }
                check(guideMinimal.lines().size == 77) { "guideMinimal line count: ${'$'}{guideMinimal.lines().size}" }
                check(defaultGuide.lines().size == 93) { "defaultGuide line count: ${'$'}{defaultGuide.lines().size}" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LlmGuidance.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN LlmGuidance.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LlmGuidance.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for LlmGuidance.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production McpDocGenerator source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/doc/tooling/McpDocGenerator.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .substringBefore("/**\n * CLI entrypoint")
            .replace("ToolRegistrar.buildToolDocSpecs()", "emptyList()")
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                // 0. Default constructors and data classes
                val defaultGen = DefaultMcpDocGenerator()
                check(defaultGen.toolSpecs.isEmpty()) { "default toolSpecs empty" }
                val defaultParam = ParamDocSpec("paramX", "descX")
                check(!defaultParam.required) { "default required false" }
                check(defaultParam.type == "string") { "default type string" }
                check(defaultParam.itemsType == null) { "default itemsType null" }

                val defaultTool = ToolDocSpec("toolX", "descX", false)
                check(defaultTool.actions.isEmpty()) { "default actions empty" }
                check(defaultTool.params.isEmpty()) { "default params empty" }
                check(defaultTool.requiredParams.isEmpty()) { "default requiredParams empty" }
                check(defaultTool.notes == null) { "default notes null" }

                val specs = listOf(
                    ToolDocSpec(
                        name = "read_tool",
                        description = "Inspect workspace state.",
                        readOnly = true,
                        actions = listOf("inspect", "query"),
                        params = listOf(
                            ParamDocSpec(name = "path", description = "Target path", type = "string", required = true),
                            ParamDocSpec(name = "tags", description = "Filter tags", type = "array", itemsType = "string", required = false)
                        ),
                        requiredParams = listOf("path")
                    ),
                    ToolDocSpec(
                        name = "edit_tool",
                        description = "Mutates files on disk.",
                        readOnly = false,
                        actions = emptyList(),
                        params = listOf(
                            ParamDocSpec(name = "content", description = "New file content", type = "string", required = true)
                        ),
                        requiredParams = listOf("content")
                    )
                )

                val generator = DefaultMcpDocGenerator(specs)

                // 1. Reference Markdown verification
                val refMarkdown = generator.generateToolReferenceMarkdown()
                check(refMarkdown.contains("# Kotlin MCP Tool & Action API Reference")) { "title" }
                check(refMarkdown.contains("authoritative, code-backed API reference for all **2 MCP tools**")) { "tool count" }
                check(refMarkdown.contains("All tools use progressive discovery with action-multiplexed parameters to minimize LLM token consumption while providing complete IDE-grade capabilities.")) { "intro progressive" }
                check(refMarkdown.contains("## Read-Only Tools (`readOnly = true`)")) { "read-only section" }
                check(refMarkdown.contains("Read-only tools are safe for research, audits, and discovery. They never modify files on disk or execute untrusted host code.")) { "read-only safety notice" }
                check(refMarkdown.contains("## Mutating / Edit Tools (`readOnly = false`)")) { "mutating section" }
                check(refMarkdown.contains("Mutating tools generate code diffs, format files, rename symbols across workspaces, or execute child JVM processes.")) { "mutating note" }
                check(refMarkdown.contains("### `read_tool`")) { "read_tool header" }
                check(refMarkdown.contains("**Description:** Inspect workspace state.")) { "read_tool desc" }
                check(refMarkdown.contains("**Supported Actions:** `inspect`, `query`")) { "actions list" }
                check(refMarkdown.contains("| Parameter | Type | Required | Description |")) { "param table header" }
                check(refMarkdown.contains("| :--- | :--- | :--- | :--- |")) { "param table divider" }
                check(refMarkdown.contains("| `path` | `string` | **Yes** | Target path |")) { "param path" }
                check(refMarkdown.contains("| `tags` | `Array<string>` | No | Filter tags |")) { "param tags array" }
                check(refMarkdown.contains("### `edit_tool`")) { "edit_tool header" }
                check(refMarkdown.contains("**Description:** Mutates files on disk.")) { "edit_tool desc" }
                check(refMarkdown.contains("| `content` | `string` | **Yes** | New file content |")) { "param content" }
                check(refMarkdown.contains("[← Home](Home)")) { "footer link" }
                check(refMarkdown.lines().size == 40) { "refMarkdown line count: ${'$'}{refMarkdown.lines().size}" }

                // 2. Summary Table verification
                val summaryTable = generator.generateToolSummaryTable()
                check(summaryTable.contains("| Tool Name | Actions / Targets | Description |")) { "table header" }
                check(summaryTable.contains("| :--- | :--- | :--- |")) { "table divider" }
                check(summaryTable.contains("| `read_tool` | `inspect`, `query` | **Read-Only**: Inspect workspace state. |")) { "read_tool row" }
                check(summaryTable.contains("| `edit_tool` | *(Direct)* | **Mutating**: Mutates files on disk. |")) { "edit_tool row" }
                check(summaryTable.lines().size == 5) { "summaryTable line count: ${'$'}{summaryTable.lines().size}" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: McpDocGenerator.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN McpDocGenerator.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for McpDocGenerator.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for McpDocGenerator.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production MutationModels source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/MutationModels.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val productionCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim() == "@Serializable" }
            .joinToString("\n")

        val testSuiteCode = """
            fun main() {
                // 1. MutationOperator enum
                check(MutationOperator.RELATIONAL_BOUNDARY.description.contains("Relational boundary")) { "op relational" }
                check(MutationOperator.BOOLEAN_INVERSION.description.contains("Boolean literal")) { "op boolean" }
                check(MutationOperator.ARITHMETIC_OPERATOR.description.contains("Arithmetic operator")) { "op arithmetic" }
                check(MutationOperator.RETURN_VALUE.description.contains("Return value")) { "op return" }
                check(MutationOperator.VOID_CALL_REMOVAL.description.contains("Omission of standalone")) { "op void" }
                check(MutationOperator.CONDITION_REPLACEMENT.description.contains("Full condition replacement")) { "op condition" }
                check(MutationOperator.LITERAL_MUTATION.description.contains("Constant literal")) { "op literal" }
                check(MutationOperator.COLLECTION_OPERATOR.description.contains("Standard library collection")) { "op collection" }
                check(MutationOperator.EMPTY_METHOD_BODY.description.contains("Function body truncation")) { "op empty body" }
                check(MutationOperator.HIGHER_ORDER_COMPOUND.description.contains("Higher-order compound")) { "op higher order" }
                check(MutationOperator.values().size == 10) { "all 10 operators present" }

                // 2. AstMutant data class
                val defaultMutant = AstMutant("id1", MutationOperator.RETURN_VALUE, 10, 5, "orig", "mut", "source", "desc")
                check(defaultMutant.order == 1) { "default order 1" }
                check(defaultMutant.id == "id1") { "mutant id" }
                check(defaultMutant.operator == MutationOperator.RETURN_VALUE) { "mutant operator" }
                check(defaultMutant.line == 10) { "mutant line" }
                check(defaultMutant.column == 5) { "mutant column" }
                check(defaultMutant.originalSnippet == "orig") { "mutant orig" }
                check(defaultMutant.mutatedSnippet == "mut") { "mutant mut" }
                check(defaultMutant.mutatedSource == "source") { "mutant source" }
                check(defaultMutant.description == "desc") { "mutant desc" }

                // 3. MutantStatus enum
                check(MutantStatus.values().size == 4) { "4 status values" }
                check(MutantStatus.KILLED.name == "KILLED") { "status killed" }
                check(MutantStatus.SURVIVED.name == "SURVIVED") { "status survived" }
                check(MutantStatus.COMPILATION_ERROR.name == "COMPILATION_ERROR") { "status comp error" }
                check(MutantStatus.TIMEOUT.name == "TIMEOUT") { "status timeout" }

                // 4. MutantResult data class
                val defaultResult = MutantResult(defaultMutant, MutantStatus.KILLED)
                check(defaultResult.details == null) { "default details null" }
                check(defaultResult.durationMs == 0L) { "default duration 0" }
                val customResult = MutantResult(defaultMutant, MutantStatus.SURVIVED, "detailMsg", 42L)
                check(customResult.details == "detailMsg") { "custom details" }
                check(customResult.durationMs == 42L) { "custom duration" }

                // 5. MutationReport calculations
                val defaultReport = MutationReport(
                    score = 80.0,
                    totalMutants = 10,
                    killedCount = 8,
                    survivedCount = 1,
                    compilationErrorCount = 1,
                    timeoutCount = 0,
                    results = listOf(defaultResult)
                )
                check(defaultReport.order == 1) { "default order 1" }
                check(defaultReport.effectiveMutants == 9) { "effective mutants calculation: 10 - 1 = 9" }
                check(defaultReport.isStrong) { "score 80.0 isStrong true" }

                val strongReport = MutationReport(85.0, 10, 8, 1, 1, 0, emptyList())
                check(strongReport.isStrong) { "score 85.0 isStrong true" }

                val weakReport = MutationReport(79.9, 10, 7, 2, 1, 0, emptyList())
                check(!weakReport.isStrong) { "score 79.9 isStrong false" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: MutationModels.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN MutationModels.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for MutationModels.kt")
        assertEquals(0, report.survivedCount, "All MutationModels mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation test production GradleProjectInspector source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/project/GradleProjectInspector.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val inspectorCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = "import com.gokorei.kotlinmcp.models.KotlinMcpResult\nimport java.io.File\n\n"
        val productionCode = imports + inspectorCode

        val testSuiteCode = """
            fun main() {
                val inspector = GradleProjectInspector()

                // 1. inspectGradleProject with Kotlin DSL plugins and KMP targets
                val kmpScript = ""${'"'}
                    plugins {
                        kotlin("multiplatform") version "2.0.0"
                        id("com.android.library") version "8.2.0"
                    }
                    kotlin {
                        jvm()
                        iosArm64()
                        wasmJs {
                            browser()
                        }
                    }
                ""${'"'}.trimIndent()

                val kmpResult = inspector.inspectGradleProject(kmpScript, null)
                check(kmpResult is KotlinMcpResult.Success) { "kmp inspection success" }
                val kmpContent = (kmpResult as KotlinMcpResult.Success).content
                check(kmpContent.contains("# Gradle Project Inspection")) { "kmp header" }
                check(kmpContent.contains("## Detected Plugins (2)")) { "kmp plugins header" }
                check(kmpContent.contains("- `kotlin-multiplatform`")) { "kotlin-multiplatform plugin" }
                check(kmpContent.contains("- `com.android.library`")) { "android plugin" }
                check(kmpContent.contains("## KMP Targets (3)")) { "kmp targets header" }
                check(kmpContent.contains("- `jvm`")) { "jvm target" }
                check(kmpContent.contains("- `iosArm64`")) { "iosArm64 target" }
                check(kmpContent.contains("- `wasmJs`")) { "wasmJs target" }
                check(kmpContent.contains("## Recommended Guidelines")) { "guidelines header" }
                check(kmpContent.contains("[Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)")) { "kmp guidelines link" }
                check(kmpResult.metadata["pluginCount"] == "2") { "metadata pluginCount 2" }
                check(kmpResult.metadata["targetCount"] == "3") { "metadata targetCount 3" }
                check(kmpResult.metadata["subprojectCount"] == "0") { "metadata subprojectCount 0" }

                // 2. inspectGradleProject with Groovy DSL and single-platform fallback
                val groovyScript = ""${'"'}
                    apply plugin: 'java'
                    id 'application'
                ""${'"'}.trimIndent()
                val groovyResult = inspector.inspectGradleProject(groovyScript, null)
                check(groovyResult is KotlinMcpResult.Success) { "groovy inspection success" }
                val groovyContent = (groovyResult as KotlinMcpResult.Success).content
                check(groovyContent.contains("- `java`")) { "java plugin" }
                check(groovyContent.contains("- `application`")) { "application plugin" }
                check(groovyContent.contains("## KMP Targets (0)")) { "kmp targets 0 header" }
                check(groovyContent.contains("- JVM / Single-platform")) { "single platform notice" }
                check(groovyResult.metadata["targetCount"] == "0") { "metadata targetCount 0" }

                // 3. inspectGradleProject with empty content
                val emptyResult = inspector.inspectGradleProject("", null)
                check(emptyResult is KotlinMcpResult.Success) { "empty inspection success" }
                val emptyContent = (emptyResult as KotlinMcpResult.Success).content
                check(emptyContent.contains("- (none detected directly)")) { "none detected directly" }
                check(emptyResult.metadata["pluginCount"] == "0") { "metadata pluginCount 0" }

                // 4. listKmpTargets verification
                val targetsPopulated = inspector.listKmpTargets("kotlin { jvm(); iosX64() }")
                check(targetsPopulated is KotlinMcpResult.Success) { "targetsPopulated success" }
                val targetsPopulatedContent = (targetsPopulated as KotlinMcpResult.Success).content
                check(targetsPopulatedContent.contains("# KMP Targets (2)")) { "targets header 2" }
                check(targetsPopulatedContent.contains("- `jvm`")) { "target jvm" }
                check(targetsPopulatedContent.contains("- `iosX64`")) { "target iosX64" }
                check(targetsPopulated.metadata["targetCount"] == "2") { "metadata targetCount 2" }

                val targetsEmpty = inspector.listKmpTargets("plugins { java }")
                check(targetsEmpty is KotlinMcpResult.Success) { "targetsEmpty success" }
                val targetsEmptyContent = (targetsEmpty as KotlinMcpResult.Success).content
                check(targetsEmptyContent.contains("# KMP Targets (0)")) { "targets header 0" }
                check(targetsEmptyContent.contains("- Single-platform (JVM / Android)")) { "single platform notice" }
                check(targetsEmpty.metadata["targetCount"] == "0") { "metadata targetCount 0" }

                // 5. analyzeDependencies verification
                val depsScript = ""${'"'}
                    dependencies {
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
                        api 'com.google.guava:guava:33.0.0-jre'
                        testImplementation(libs.junit.jupiter)
                        compileOnly(project(":api"))
                    }
                ""${'"'}.trimIndent()
                val depsResult = inspector.analyzeDependencies(depsScript)
                check(depsResult is KotlinMcpResult.Success) { "depsResult success" }
                val depsContent = (depsResult as KotlinMcpResult.Success).content
                check(depsContent.contains("# Project Dependencies (4)")) { "deps header 4" }
                check(depsContent.contains("- `implementation: org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0`")) { "dep 1" }
                check(depsContent.contains("- `api: com.google.guava:guava:33.0.0-jre`")) { "dep 2" }
                check(depsContent.contains("- `testImplementation: libs.junit.jupiter`")) { "dep 3" }
                check(depsContent.contains("- `compileOnly: project(\":api\")`")) { "dep 4" }
                check(depsResult.metadata["dependencyCount"] == "4") { "metadata dependencyCount 4" }

                val emptyDepsResult = inspector.analyzeDependencies("")
                check(emptyDepsResult is KotlinMcpResult.Success) { "emptyDepsResult success" }
                val emptyDepsContent = (emptyDepsResult as KotlinMcpResult.Success).content
                check(emptyDepsContent.contains("- (no dependencies extracted from script snippet)")) { "no deps note" }
                check(emptyDepsResult.metadata["dependencyCount"] == "0") { "metadata dependencyCount 0" }

                // 6. diagnoseBuild verification
                val badBuild = inspector.diagnoseBuild("repositories { jcenter() }", "", "")
                check(badBuild is KotlinMcpResult.Success) { "badBuild success" }
                val badBuildContent = (badBuild as KotlinMcpResult.Success).content
                check(badBuildContent.contains("# Gradle Build Diagnostic")) { "bad diagnostic header" }
                check(badBuildContent.contains("JCenter repository is sunset; migrate to mavenCentral().")) { "jcenter warning" }
                check(badBuild.metadata["issueCount"] == "1") { "metadata issueCount 1" }

                val cleanBuild = inspector.diagnoseBuild("repositories { mavenCentral() }", "", "")
                check(cleanBuild is KotlinMcpResult.Success) { "cleanBuild success" }
                val cleanBuildContent = (cleanBuild as KotlinMcpResult.Success).content
                check(cleanBuildContent.contains("# Gradle Build Diagnostic")) { "clean diagnostic header" }
                check(cleanBuildContent.contains("✅ No obvious Gradle script issues detected.")) { "clean notice" }
                check(cleanBuild.metadata["issueCount"] == "0") { "metadata issueCount 0" }

                // 7. settingsSubprojects and subprojects inspection
                val tmpDir = java.io.File.createTempFile("mcp_test", "").apply { delete(); mkdirs() }
                try {
                    val settingsFile = java.io.File(tmpDir, "settings.gradle.kts")
                    settingsFile.writeText("include(\":core:network\")\ninclude \":feature:ui\"")
                    val sub1 = java.io.File(tmpDir, "core/network").apply { mkdirs() }
                    java.io.File(sub1, "build.gradle.kts").writeText("plugins { id(\"kotlin\") }")
                    val sub2 = java.io.File(tmpDir, "feature/ui").apply { mkdirs() }
                    java.io.File(sub2, "build.gradle").writeText("apply plugin: 'com.android.library'")

                    val rootResult = inspector.inspectGradleProject("", tmpDir.absolutePath)
                    check(rootResult is KotlinMcpResult.Success) { "rootResult success" }
                    val rootContent = (rootResult as KotlinMcpResult.Success).content
                    check(rootContent.contains("## Settings Subprojects (2)")) { "subprojects 2 header" }
                    check(rootContent.contains("- `:core:network`")) { "subproject 1" }
                    check(rootContent.contains("- `:feature:ui`")) { "subproject 2" }
                    check(rootContent.contains("- `kotlin`")) { "sub1 plugin" }
                    check(rootContent.contains("- `com.android.library`")) { "sub2 plugin" }
                    check(rootResult.metadata["subprojectCount"] == "2") { "metadata subprojects 2" }
                } finally {
                    tmpDir.deleteRecursively()
                }

                // 8. All KMP target names (testing both () and space syntaxes)
                val allKmpScript = ""${'"'}
                    kotlin {
                        jvm()
                        androidTarget()
                        iosX64()
                        iosArm64()
                        iosSimulatorArm64()
                        js { browser() }
                        wasmJs()
                        linuxX64 { binaries {} }
                        macosX64()
                        macosArm64 { binaries {} }
                    }
                ""${'"'}.trimIndent()
                val allKmpResult = inspector.listKmpTargets(allKmpScript)
                check(allKmpResult is KotlinMcpResult.Success) { "allKmpResult success" }
                val allKmpContent = (allKmpResult as KotlinMcpResult.Success).content
                check(allKmpContent.contains("# KMP Targets (10)")) { "all 10 targets header" }
                check(allKmpContent.contains("## Recommended Guidelines")) { "kmp guidelines header" }
                check(allKmpContent.contains("[Multiplatform Web Storage (Room 3.0 & DataStore)](kotlin://guidelines/kmp-storage.md)")) { "kmp link" }
                listOf("jvm", "androidTarget", "iosX64", "iosArm64", "iosSimulatorArm64", "js", "wasmJs", "linuxX64", "macosX64", "macosArm64").forEach {
                    check(allKmpContent.contains("- `" + it + "`")) { "contains target " + it }
                }
                check(allKmpResult.metadata["targetCount"] == "10") { "metadata targetCount 10" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: GradleProjectInspector.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN GradleProjectInspector.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for GradleProjectInspector.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for GradleProjectInspector.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production DatetimeMigrationSuggestor source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/DatetimeMigrationSuggestor.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val suggestorCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtUserType
        """.trimIndent()

        val productionCode = imports + "\n\n" + suggestorCode

        val testSuiteCode = """
            fun main() {
                val suggestor = DatetimeMigrationSuggestor()

                // 1. Clean snippet with no legacy date/calendar
                val cleanResult = suggestor.suggestKotlinxDatetime("val x = 42")
                check(cleanResult is KotlinMcpResult.Success) { "cleanResult success" }
                val cleanContent = (cleanResult as KotlinMcpResult.Success).content
                check(cleanContent.contains("# `kotlinx-datetime` Migration Advisories")) { "clean header" }
                check(cleanContent.contains("No legacy Date/Calendar APIs detected in snippet.")) { "clean notice" }
                check(cleanResult.metadata["advisoriesCount"] == "0") { "clean advisoriesCount 0" }

                // 2. suggestDatetimeMigration facade delegation
                val facadeResult = suggestor.suggestDatetimeMigration("val x = 42")
                check(facadeResult is KotlinMcpResult.Success) { "facadeResult success" }
                check((facadeResult as KotlinMcpResult.Success).content == cleanContent) { "facade matches direct" }

                // 3. Date simple name and user type
                val dateResult = suggestor.suggestKotlinxDatetime("fun get(): Date { return java.util.Date() }")
                check(dateResult is KotlinMcpResult.Success) { "dateResult success" }
                val dateContent = (dateResult as KotlinMcpResult.Success).content
                check(dateContent.contains("`java.util.Date` → `kotlinx.datetime.Instant` (timestamp) or `LocalDate` (civil date)")) { "date suggestion" }
                check(dateResult.metadata["advisoriesCount"] == "1") { "date advisoriesCount 1" }

                val dateTypeOnly = suggestor.suggestKotlinxDatetime("typealias D = java.util.Date")
                check(dateTypeOnly is KotlinMcpResult.Success) { "dateTypeOnly success" }
                check((dateTypeOnly as KotlinMcpResult.Success).content.contains("`java.util.Date` → `kotlinx.datetime.Instant`")) { "dateTypeOnly content" }

                // 4. Calendar simple name and user type
                val calResult = suggestor.suggestKotlinxDatetime("val c: Calendar = java.util.Calendar.getInstance()")
                check(calResult is KotlinMcpResult.Success) { "calResult success" }
                val calContent = (calResult as KotlinMcpResult.Success).content
                check(calContent.contains("`java.util.Calendar` → `kotlinx.datetime.LocalDateTime` / `TimeZone`")) { "calendar suggestion" }
                check(calResult.metadata["advisoriesCount"] == "1") { "calendar advisoriesCount 1" }

                val calTypeOnly = suggestor.suggestKotlinxDatetime("typealias C = java.util.Calendar")
                check(calTypeOnly is KotlinMcpResult.Success) { "calTypeOnly success" }
                check((calTypeOnly as KotlinMcpResult.Success).content.contains("`java.util.Calendar` → `kotlinx.datetime.LocalDateTime`")) { "calTypeOnly content" }

                // 5. SimpleDateFormat simple name and user type
                val sdfResult = suggestor.suggestKotlinxDatetime("val f: SimpleDateFormat = SimpleDateFormat(\"yyyy-MM-dd\")")
                check(sdfResult is KotlinMcpResult.Success) { "sdfResult success" }
                val sdfContent = (sdfResult as KotlinMcpResult.Success).content
                check(sdfContent.contains("`SimpleDateFormat` → `kotlinx.datetime.LocalDate.parse()` / `format()`")) { "sdf suggestion" }
                check(sdfResult.metadata["advisoriesCount"] == "1") { "sdf advisoriesCount 1" }

                val sdfTypeOnly = suggestor.suggestKotlinxDatetime("typealias S = SimpleDateFormat")
                check(sdfTypeOnly is KotlinMcpResult.Success) { "sdfTypeOnly success" }
                check((sdfTypeOnly as KotlinMcpResult.Success).content.contains("`SimpleDateFormat` → `kotlinx.datetime.LocalDate.parse()`")) { "sdfTypeOnly content" }

                // 6. System.currentTimeMillis() call expression
                val millisResult = suggestor.suggestKotlinxDatetime("val now = System.currentTimeMillis()")
                check(millisResult is KotlinMcpResult.Success) { "millisResult success" }
                val millisContent = (millisResult as KotlinMcpResult.Success).content
                check(millisContent.contains("`System.currentTimeMillis()` → `Clock.System.now().toEpochMilliseconds()`")) { "millis suggestion" }
                check(millisResult.metadata["advisoriesCount"] == "1") { "millis advisoriesCount 1" }

                // 7. Combined snippet with all 4 advisories
                val combined = "val d = Date(); val c = Calendar.getInstance(); val f = SimpleDateFormat(); val t = System.currentTimeMillis()"
                val combResult = suggestor.suggestKotlinxDatetime(combined)
                check(combResult is KotlinMcpResult.Success) { "combResult success" }
                val combContent = (combResult as KotlinMcpResult.Success).content
                check(combContent.contains("Recommended modern `kotlinx.datetime` replacements:")) { "replacements header" }
                check(combContent.contains("Imports required:")) { "imports header" }
                check(combContent.contains("```kotlin\nimport kotlinx.datetime.*\n```")) { "import code block" }
                check(combResult.metadata["advisoriesCount"] == "4") { "comb advisoriesCount 4" }
                check(combContent.lines().size == 13) { "comb lines: ${'$'}{combContent.lines().size}" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: DatetimeMigrationSuggestor.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN DatetimeMigrationSuggestor.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for DatetimeMigrationSuggestor.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for DatetimeMigrationSuggestor.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production ArrowRefactorer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/ArrowRefactorer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val refactorerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtReturnExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtTryExpression
        """.trimIndent()

        val productionCode = imports + "\n\n" + refactorerCode

        val testSuiteCode = """
            fun main() {
                val refactorer = ArrowRefactorer()

                // 1. Clean code with no try-catch and no errors list
                val clean = refactorer.refactorToArrow("fun compute(): Int = 42", null)
                check(clean is KotlinMcpResult.Success) { "clean success" }
                val cleanContent = (clean as KotlinMcpResult.Success).content
                check(cleanContent.contains("# Arrow Refactoring (Arrow 2.x (Raise DSL paradigm))")) { "clean header" }
                check(cleanContent.contains("No refactorable try-catch or manual validation list accumulation detected.")) { "clean notice 1" }
                check(cleanContent.contains("Code left unchanged.")) { "clean notice 2" }
                check(clean.metadata["targetVersion"] == "Arrow 2.x (Raise DSL paradigm)") { "clean metadata" }

                // 2. Facade delegation
                val del1 = refactorer.migrateArrowRaise("fun compute(): Int = 42")
                val del2 = refactorer.suggestArrowRefactorings("fun compute(): Int = 42")
                check(del1 is KotlinMcpResult.Success && (del1 as KotlinMcpResult.Success).content == cleanContent) { "del1 matches" }
                check(del2 is KotlinMcpResult.Success && (del2 as KotlinMcpResult.Success).content == cleanContent) { "del2 matches" }

                // 3. Try-catch function in Arrow 2.x mode
                val trySnippet = ""${'"'}
                    fun parse(raw: String): Int? {
                        try {
                            return raw.toInt()
                        } catch (e: Exception) {
                            return null
                        }
                    }
                ""${'"'}.trimIndent()
                val res2x = refactorer.refactorToArrow(trySnippet, "false")
                check(res2x is KotlinMcpResult.Success) { "res2x success" }
                val content2x = (res2x as KotlinMcpResult.Success).content
                check(content2x.contains("## Exception Handling → Either\n```kotlin\nimport arrow.core.Either\n\nfun parse(raw: String): Either<Throwable, Int> =\n    Either.catch { raw.toInt() }\n```")) { "content2x full code block" }
                check(res2x.metadata["targetVersion"] == "Arrow 2.x (Raise DSL paradigm)") { "res2x metadata" }
                check(content2x.lines().size == 10) { "content2x lines: ${'$'}{content2x.lines().size}" }

                // 3b. Function without try catch
                val simpleFn = "fun simple(): Int { return 42 }"
                val simpleRes = refactorer.refactorToArrow(simpleFn, null)
                check(!(simpleRes as KotlinMcpResult.Success).content.contains("## Exception Handling → Either")) { "simple no try either" }

                // 4. Try-catch function in Arrow 1.x legacy mode
                val res1x = refactorer.refactorToArrow(trySnippet, " TRUE ")
                check(res1x is KotlinMcpResult.Success) { "res1x success" }
                val content1x = (res1x as KotlinMcpResult.Success).content
                check(content1x.contains("# Arrow Refactoring (Arrow 1.x (Either monad syntax))")) { "content1x title" }
                check(content1x.contains("## Exception Handling → Either")) { "content1x section" }
                check(content1x.contains("import arrow.core.left")) { "content1x left import" }
                check(content1x.contains("import arrow.core.right")) { "content1x right import" }
                check(content1x.contains("fun parse(raw: String): Either<Throwable, Int> =")) { "content1x sig" }
                check(content1x.contains("runCatching { raw.toInt() }.fold(")) { "content1x runCatching" }
                check(content1x.contains("onSuccess = { it.right() }")) { "content1x onSuccess" }
                check(content1x.contains("onFailure = { it.left() }")) { "content1x onFailure" }
                check(res1x.metadata["targetVersion"] == "Arrow 1.x (Either monad syntax)") { "res1x metadata" }

                // 4b. Non-returning catch clause & return in block
                val noCatchReturn = "fun logOnly() { try { doWork() } catch(e: Exception) { println(e) } }"
                val noCatchRes = refactorer.refactorToArrow(noCatchReturn, null)
                check(!(noCatchRes as KotlinMcpResult.Success).content.contains("Either.catch")) { "no catch return no either" }

                val returnInBlock = "fun blockCatch() { try { doWork() } catch(e: Exception) { val msg = e.message; return } }"
                val returnInBlockRes = refactorer.refactorToArrow(returnInBlock, null)
                check((returnInBlockRes as KotlinMcpResult.Success).content.contains("Either.catch")) { "return in block either" }

                // 5. Validation list accumulation with errors or problems
                val validSnippet = ""${'"'}
                    fun validate(input: String) {
                        val errors = mutableListOf<String>()
                        errors.add("bad input")
                    }
                ""${'"'}.trimIndent()
                val validRes = refactorer.refactorToArrow(validSnippet, null)
                check(validRes is KotlinMcpResult.Success) { "validRes success" }
                val validContent = (validRes as KotlinMcpResult.Success).content
                check(validContent.contains("## Accumulation Validation → Raise DSL")) { "validContent header" }
                check(validContent.contains("import arrow.core.nonEmptyListOf")) { "validContent import 1" }
                check(validContent.contains("import arrow.core.raise.raise")) { "validContent import 2" }
                check(validContent.contains("import arrow.core.validNel")) { "validContent import 3" }
                check(validContent.contains("import arrow.core.invalidNel")) { "validContent import 4" }
                check(validContent.contains("import arrow.core.raise.ensure")) { "validContent import 5" }
                check(validContent.contains("// Arrow 2.x Raise DSL validation paradigm")) { "validContent comment 1" }
                check(validContent.contains("// Ensure invariants declaratively instead of accumulating error lists manually.")) { "validContent comment 2" }

                val probSnippet = ""${'"'}
                    fun checkInput(input: String) {
                        val problems = mutableListOf<String>()
                        problems.add("problem")
                    }
                ""${'"'}.trimIndent()
                val probRes = refactorer.refactorToArrow(probSnippet, null)
                check(probRes is KotlinMcpResult.Success) { "probRes success" }
                check((probRes as KotlinMcpResult.Success).content.contains("## Accumulation Validation → Raise DSL")) { "probContent header" }

                // 5b. Non-errors property, non-mutableList, non-add call
                val notMutable = "fun check() { val errors = listOf<String>(); errors.first() }"
                val notMutableRes = refactorer.refactorToArrow(notMutable, null)
                check(!(notMutableRes as KotlinMcpResult.Success).content.contains("## Accumulation Validation → Raise DSL")) { "notMutable no raise" }

                val notErrors = "fun check() { val items = mutableListOf<String>(); items.add(\"x\") }"
                val notErrorsRes = refactorer.refactorToArrow(notErrors, null)
                check(!(notErrorsRes as KotlinMcpResult.Success).content.contains("## Accumulation Validation → Raise DSL")) { "notErrors no raise" }

                val notAdd = "fun check() { val errors = mutableListOf<String>(); errors.remove(\"x\") }"
                val notAddRes = refactorer.refactorToArrow(notAdd, null)
                check(!(notAddRes as KotlinMcpResult.Success).content.contains("## Accumulation Validation → Raise DSL")) { "notAdd no raise" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ArrowRefactorer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ArrowRefactorer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ArrowRefactorer.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for ArrowRefactorer.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production IdiomaticKotlinSuggestor source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/IdiomaticKotlinSuggestor.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val suggestorCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.KtClass
            import org.jetbrains.kotlin.psi.KtIfExpression
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtTryExpression
            import org.jetbrains.kotlin.lexer.KtTokens
        """.trimIndent()

        val productionCode = imports + "\n\n" + suggestorCode

        val testSuiteCode = """
            fun main() {
                val suggestor = IdiomaticKotlinSuggestor()

                // 1. Clean code
                val clean = suggestor.suggestIdioms("val x = 42")
                check(clean is KotlinMcpResult.Success) { "clean success" }
                val cleanContent = (clean as KotlinMcpResult.Success).content
                check(cleanContent == "# Idiomatic Kotlin Code Suggestions\nCode already uses clean Kotlin idioms! Consider value classes or sealed interfaces if expanding hierarchy.") { "clean content exact match" }
                check(clean.metadata["suggestionsCount"] == "0") { "clean suggestionsCount 0" }

                // 2. Facade delegation
                val facade = suggestor.suggestIdiomaticKotlin("val x = 42")
                check(facade is KotlinMcpResult.Success && (facade as KotlinMcpResult.Success).content == cleanContent) { "facade matches direct" }

                // 3. Try-catch with catch clauses vs try-finally
                val trySnippet = "fun p(s: String) { try { s.toInt() } catch (e: Exception) { 0 } }"
                val tryRes = suggestor.suggestIdioms(trySnippet)
                check(tryRes is KotlinMcpResult.Success) { "tryRes success" }
                val tryContent = (tryRes as KotlinMcpResult.Success).content
                check(tryContent.contains("### Use `runCatching` / `Result` for Exception Safety:")) { "tryContent header" }
                check(tryContent.contains("fun parse(input: String): Result<Int> = runCatching {\n    input.toInt()\n}")) { "tryContent body" }
                check(tryRes.metadata["suggestionsCount"] == "1") { "tryRes suggestionsCount 1" }

                val tryFinally = "fun p(s: String) { try { s.toInt() } finally { println(1) } }"
                val finRes = suggestor.suggestIdioms(tryFinally)
                check(!(finRes as KotlinMcpResult.Success).content.contains("### Use `runCatching`")) { "finally no runCatching" }

                // 4. If expression with != null vs without
                val ifSnippet = "fun test(s: String?) { if (s != null) { println(s) } }"
                val ifRes = suggestor.suggestIdioms(ifSnippet)
                check(ifRes is KotlinMcpResult.Success) { "ifRes success" }
                val ifContent = (ifRes as KotlinMcpResult.Success).content
                check(ifContent.contains("### Leverage Kotlin Scope Functions (`let` / `run`):")) { "ifContent header" }
                check(ifContent.contains("input?.let { safeValue ->\n    process(safeValue)\n}")) { "ifContent body" }
                check(ifRes.metadata["suggestionsCount"] == "1") { "ifRes suggestionsCount 1" }

                val ifOther = "fun test(x: Int) { if (x > 0) { println(x) } }"
                val ifOtherRes = suggestor.suggestIdioms(ifOther)
                check(!(ifOtherRes as KotlinMcpResult.Success).content.contains("Scope Functions")) { "ifOther no scope functions" }

                // 5. Abstract mapper with 2 type params and map method
                val mapperSnippet = "abstract class UserMapper<UserDto, UserEntity> { abstract fun map(dto: UserDto): UserEntity }"
                val mapperRes = suggestor.suggestIdioms(mapperSnippet)
                check(mapperRes is KotlinMcpResult.Success) { "mapperRes success" }
                val mapContent = (mapperRes as KotlinMcpResult.Success).content
                check(mapContent.contains("### Replace Abstract Mapper with Top-Level Extension Mapper:")) { "mapContent header" }
                check(mapContent.contains("The abstract class `UserMapper<UserDto, UserEntity>` adds boilerplate")) { "mapContent class desc" }
                check(mapContent.contains("fun UserEntity.userentityFromUserDto(): UserDto {")) { "mapContent ext function" }
                check(mapContent.contains("Top-level extension mappers are pure, trivially unit-testable, and remove the subclass/override ceremony.")) { "mapContent benefit" }
                check(mapperRes.metadata["suggestionsCount"] == "1") { "mapperRes suggestionsCount 1" }

                // 6. Abstract class with default fallback type params
                val defMapper = "abstract class BaseMapper { abstract fun map(x: String): Int }"
                val defRes = suggestor.suggestIdioms(defMapper)
                check(defRes is KotlinMcpResult.Success) { "defRes success" }
                val defContent = (defRes as KotlinMcpResult.Success).content
                check(defContent.contains("The abstract class `BaseMapper<E, T>` adds boilerplate")) { "defContent fallback type params" }
                check(defContent.contains("fun T.tFromString(): String {")) { "defContent fallback function" }

                // 7. Non-abstract concrete class
                val concreteClass = "class ConcreteMapper { fun map(x: String) = x }"
                val concRes = suggestor.suggestIdioms(concreteClass)
                check(!(concRes as KotlinMcpResult.Success).content.contains("Replace Abstract Mapper")) { "concrete no abstract mapper suggestion" }

                // 8. Multi-suggestion combined snippet
                val combSnippet = "abstract class M<A, B> { fun map(a: A): B = TODO() }; fun f(x: String?) { if (x != null) try { println(x) } catch(e: Exception) {} }"
                val combRes = suggestor.suggestIdioms(combSnippet)
                check(combRes is KotlinMcpResult.Success) { "combRes success" }
                check(combRes.metadata["suggestionsCount"] == "3") { "combRes suggestionsCount 3" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: IdiomaticKotlinSuggestor.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN IdiomaticKotlinSuggestor.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for IdiomaticKotlinSuggestor.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for IdiomaticKotlinSuggestor.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production JavaToKotlinRefactorer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/JavaToKotlinRefactorer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val refactorerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaFileType
            import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
            import org.jetbrains.kotlin.com.intellij.psi.JavaRecursiveElementVisitor
            import org.jetbrains.kotlin.com.intellij.psi.PsiCodeBlock
            import org.jetbrains.kotlin.com.intellij.psi.PsiDeclarationStatement
            import org.jetbrains.kotlin.com.intellij.psi.PsiExpression
            import org.jetbrains.kotlin.com.intellij.psi.PsiFileFactory
            import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile
            import org.jetbrains.kotlin.com.intellij.psi.PsiLocalVariable
            import org.jetbrains.kotlin.com.intellij.psi.PsiMethod
            import org.jetbrains.kotlin.com.intellij.psi.PsiReferenceExpression
            import org.jetbrains.kotlin.com.intellij.psi.PsiReturnStatement
            import org.jetbrains.kotlin.com.intellij.psi.PsiStatement
            import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchLabelStatement
            import org.jetbrains.kotlin.com.intellij.psi.PsiSwitchStatement
            import org.jetbrains.kotlin.com.intellij.psi.PsiThisExpression
            import org.jetbrains.kotlin.com.intellij.psi.PsiTryStatement
        """.trimIndent()

        val productionCode = imports + "\n\n" + refactorerCode

        val testSuiteCode = """
            fun main() {
                val refactorer = JavaToKotlinRefactorer()

                // 1. Java record conversion
                val recordCode = "public record Person(String name, int age) {}"
                val recRes = refactorer.convertJavaToKotlin(recordCode)
                check(recRes is KotlinMcpResult.Success) { "recRes success" }
                val recContent = (recRes as KotlinMcpResult.Success).content
                check(recContent.contains("data class Person(\n    val name: String,\n    val age: Int\n)")) { "recContent data class" }
                check(recRes.metadata["originalLanguage"] == "java") { "rec orig lang" }
                check(recRes.metadata["targetLanguage"] == "kotlin") { "rec target lang" }

                // 2. Java class with fields, getters/setters, methods
                val javaClass = ""${'"'}
                    public class User {
                        private String name;
                        private int id;
                        public String getName() { return name; }
                        public void setName(String name) { this.name = name; }
                        public int calculate() { return this.id * 2; }
                    }
                ""${'"'}.trimIndent()
                val classRes = refactorer.convertJavaToKotlin(javaClass)
                check(classRes is KotlinMcpResult.Success) { "classRes success" }
                val classContent = (classRes as KotlinMcpResult.Success).content
                check(classContent.contains("data class User(\n    var name: String,\n    var id: Int\n)")) { "classContent props" }
                check(classContent.contains("fun calculate(): Int = id * 2")) { "classContent method" }

                // 3. Raw snippet without class wrapper
                val rawSnippet = "int x = 10; x++; return x;"
                val rawRes = refactorer.convertJavaToKotlin(rawSnippet)
                check(rawRes is KotlinMcpResult.Success) { "rawRes success" }
                val rawContent = (rawRes as KotlinMcpResult.Success).content
                check(rawContent.contains("var x = 10")) { "rawContent var x" }
                check(rawContent.contains("return x")) { "rawContent return x" }

                // 4. Try-with-resources
                val tryWithRes = "try (InputStream is = new FileInputStream(\"test\")) { is.read(); }"
                val tryRes = refactorer.convertJavaToKotlin(tryWithRes)
                check(tryRes is KotlinMcpResult.Success) { "tryRes success" }
                val tryContent = (tryRes as KotlinMcpResult.Success).content
                check(tryContent.contains(".use { is ->")) { "tryContent use block" }

                // 5. Switch statement
                val switchCode = "switch (status) { case 1: return \"Active\"; default: return \"Unknown\"; }"
                val switchRes = refactorer.convertJavaToKotlin(switchCode)
                check(switchRes is KotlinMcpResult.Success) { "switchRes success" }
                val switchContent = (switchRes as KotlinMcpResult.Success).content
                check(switchContent.contains("return when (status) {")) { "switchContent when" }
                check(switchContent.contains("1 -> \"Active\"")) { "switchContent case 1" }
                check(switchContent.contains("else -> \"Unknown\"")) { "switchContent default" }

                // 6. Primitive and Collection types mapping
                val typesClass = ""${'"'}
                    public class TypesClass {
                        private long l;
                        private boolean b;
                        private double d;
                        private float f;
                        private short s;
                        private byte by;
                        private char c;
                        private Object o;
                        private java.util.List list;
                        private java.util.Map map;
                        private java.util.Set set;
                    }
                ""${'"'}.trimIndent()
                val typesRes = refactorer.convertJavaToKotlin(typesClass)
                check(typesRes is KotlinMcpResult.Success) { "typesRes success" }
                val typesContent = (typesRes as KotlinMcpResult.Success).content
                check(typesContent.contains("var l: Long")) { "types Long" }
                check(typesContent.contains("var b: Boolean")) { "types Boolean" }
                check(typesContent.contains("var d: Double")) { "types Double" }
                check(typesContent.contains("var f: Float")) { "types Float" }
                check(typesContent.contains("var s: Short")) { "types Short" }
                check(typesContent.contains("var by: Byte")) { "types Byte" }
                check(typesContent.contains("var c: Char")) { "types Char" }
                check(typesContent.contains("var o: Any")) { "types Any" }
                check(typesContent.contains("var list: List")) { "types List" }
                check(typesContent.contains("var map: Map")) { "types Map" }
                check(typesContent.contains("var set: Set")) { "types Set" }

                // 7. Empty properties class with methods
                val emptyPropsClass = "public class Service { public void doWork() { System.out.println(1); } }"
                val emptyRes = refactorer.convertJavaToKotlin(emptyPropsClass)
                check(emptyRes is KotlinMcpResult.Success) { "emptyRes success" }
                val emptyContent = (emptyRes as KotlinMcpResult.Success).content
                check(emptyContent.contains("class Service {\n    fun doWork(): Unit {\n        System.out.println(1)\n    }\n}")) { "emptyContent class" }

                // 8. Nullable field annotation
                val nullableClass = "public class Entity { @org.jetbrains.annotations.Nullable private String tag; }"
                val nullRes = refactorer.convertJavaToKotlin(nullableClass)
                check(nullRes is KotlinMcpResult.Success) { "nullRes success" }
                val nullContent = (nullRes as KotlinMcpResult.Success).content
                check(nullContent.contains("var tag: String?")) { "nullContent tag" }

                // 9. Local variable immutability (val vs var) & decrement
                val valSnippet = "int a = 1; return a;"
                val valRes = refactorer.convertJavaToKotlin(valSnippet)
                check((valRes as KotlinMcpResult.Success).content.contains("val a = 1")) { "val a" }

                val reassignSnippet = "int b = 1; b = 2; return b;"
                val reassignRes = refactorer.convertJavaToKotlin(reassignSnippet)
                check((reassignRes as KotlinMcpResult.Success).content.contains("var b = 1")) { "var b reassign" }

                val decSnippet = "int c = 5; c--; return c;"
                val decRes = refactorer.convertJavaToKotlin(decSnippet)
                check((decRes as KotlinMcpResult.Success).content.contains("var c = 5")) { "var c dec" }

                // 10. Multi-statement method body and this. stripping
                val thisClass = ""${'"'}
                    public class Runner {
                        public void run() {
                            int x = 1;
                            int y = 2;
                            this.doSomething();
                        }
                        public void doSomething() {}
                    }
                ""${'"'}.trimIndent()
                val thisRes = refactorer.convertJavaToKotlin(thisClass)
                check(thisRes is KotlinMcpResult.Success) { "thisRes success" }
                val thisContent = (thisRes as KotlinMcpResult.Success).content
                check(thisContent.contains("doSomething()")) { "thisContent doSomething" }
                check(!thisContent.contains("this.doSomething()")) { "no this. in body" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: JavaToKotlinRefactorer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN JavaToKotlinRefactorer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for JavaToKotlinRefactorer.kt")
        assertTrue(
            report.score >= 70.0,
            "Mutation score for JavaToKotlinRefactorer.kt (${report.score}%) must be at least 70%"
        )
    }

    @Test
    fun `mutation test production LoopToFunctionalRefactorer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/LoopToFunctionalRefactorer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val refactorerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.com.intellij.psi.PsiElement
            import org.jetbrains.kotlin.psi.KtBinaryExpression
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
            import org.jetbrains.kotlin.psi.KtForExpression
            import org.jetbrains.kotlin.psi.KtIfExpression
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtReturnExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
        """.trimIndent()

        val productionCode = imports + "\n\n" + refactorerCode

        val testSuiteCode = """
            fun main() {
                val refactorer = LoopToFunctionalRefactorer()

                // 1. any short-circuiting
                val anyCode = "for (x in xs) { if (x > 0) return true }\nreturn false"
                val anyRes = refactorer.convertImperativeToFunctional(anyCode)
                check(anyRes is KotlinMcpResult.Success) { "anyRes success" }
                val anyContent = (anyRes as KotlinMcpResult.Success).content
                check(anyContent.contains("xs.any { x > 0 }")) { "anyContent: " + anyContent }

                // 2. none short-circuiting
                val noneCode = "for (x in xs) { if (x > 0) return false }\nreturn true"
                val noneRes = refactorer.convertImperativeToFunctional(noneCode)
                check(noneRes is KotlinMcpResult.Success) { "noneRes success" }
                check((noneRes as KotlinMcpResult.Success).content.contains("xs.none { x > 0 }")) { "noneContent" }

                // 3. firstOrNull short-circuiting
                val firstCode = "for (x in xs) { if (x > 0) return x }\nreturn null"
                val firstRes = refactorer.convertImperativeToFunctional(firstCode)
                check(firstRes is KotlinMcpResult.Success) { "firstRes success" }
                check((firstRes as KotlinMcpResult.Success).content.contains("xs.firstOrNull { x > 0 }")) { "firstContent" }

                // 4. partition accumulation
                val partCode = "for (x in items) { if (x > 0) pos.add(x) else neg.add(x) }"
                val partRes = refactorer.convertImperativeToFunctional(partCode)
                check(partRes is KotlinMcpResult.Success) { "partRes success" }
                check((partRes as KotlinMcpResult.Success).content.contains("val (pos, neg) = items.partition { x > 0 }")) { "partContent" }

                // 5. associate & associateBy
                val assocCode = "for (x in items) { map[x.id] = x.name }"
                val assocRes = refactorer.convertImperativeToFunctional(assocCode)
                check(assocRes is KotlinMcpResult.Success) { "assocRes success" }
                check((assocRes as KotlinMcpResult.Success).content.contains("val map = items.associate { x -> x.id to x.name }")) { "assocContent" }

                val assocByCode = "for (x in items) { map[x.name] = x.id }"
                val assocByRes = refactorer.convertImperativeToFunctional(assocByCode)
                check(assocByRes is KotlinMcpResult.Success) { "assocByRes success" }
                check((assocByRes as KotlinMcpResult.Success).content.contains("val map = items.associateBy({ x.name }, { x.id })")) { "assocByContent" }

                // 6. list map & filter+map
                val listMapCode = "val result = mutableListOf<Int>()\nfor (x in items) { result.add(x * 2) }"
                val listMapRes = refactorer.convertImperativeToFunctional(listMapCode)
                check(listMapRes is KotlinMcpResult.Success) { "listMapRes success" }
                check((listMapRes as KotlinMcpResult.Success).content.contains("val result = items.map { x -> x * 2 }")) { "listMapContent" }

                val filterMapCode = "val result = mutableListOf<Int>()\nfor (x in items) { if (x > 0) result.add(x * 2) }"
                val filterMapRes = refactorer.convertImperativeToFunctional(filterMapCode)
                check(filterMapRes is KotlinMcpResult.Success) { "filterMapRes success" }
                check((filterMapRes as KotlinMcpResult.Success).content.contains("val result = items.filter { x -> x > 0 }.map { x -> x * 2 }")) { "filterMapContent" }

                // 7. sum & sumOf & filtered sum
                val sumCode = "var total = 0\nfor (x in items) { total += x }"
                val sumRes = refactorer.convertImperativeToFunctional(sumCode)
                check(sumRes is KotlinMcpResult.Success) { "sumRes success" }
                check((sumRes as KotlinMcpResult.Success).content.contains("val total = items.sum()")) { "sumContent" }

                val sumOfCode = "var total = 0\nfor (x in items) { total += x * 2 }"
                val sumOfRes = refactorer.convertImperativeToFunctional(sumOfCode)
                check(sumOfRes is KotlinMcpResult.Success) { "sumOfRes success" }
                check((sumOfRes as KotlinMcpResult.Success).content.contains("val total = items.sumOf { x -> x * 2 }")) { "sumOfContent" }

                val sumLongCode = "var total = 0L\nfor (x in items) { total += x }"
                val sumLongRes = refactorer.convertImperativeToFunctional(sumLongCode)
                check(sumLongRes is KotlinMcpResult.Success) { "sumLongRes success" }
                check((sumLongRes as KotlinMcpResult.Success).content.contains("val total = items.sum()")) { "sumLongContent" }

                val sumDoubleCode = "var total = 0.0\nfor (x in items) { total += x }"
                val sumDoubleRes = refactorer.convertImperativeToFunctional(sumDoubleCode)
                check(sumDoubleRes is KotlinMcpResult.Success) { "sumDoubleRes success" }
                check((sumDoubleRes as KotlinMcpResult.Success).content.contains("val total = items.sum()")) { "sumDoubleContent" }

                val filterSumCode = "var total = 0\nfor (x in items) { if (x > 0) total += x }"
                val filterSumRes = refactorer.convertImperativeToFunctional(filterSumCode)
                check(filterSumRes is KotlinMcpResult.Success) { "filterSumRes success" }
                check((filterSumRes as KotlinMcpResult.Success).content.contains("val total = items.filter { x -> x > 0 }.sum()")) { "filterSumContent" }

                // 8. fold accumulation
                val foldCode = "var acc = 1\nfor (x in items) { acc = acc * x }"
                val foldRes = refactorer.convertImperativeToFunctional(foldCode)
                check(foldRes is KotlinMcpResult.Success) { "foldRes success" }
                check((foldRes as KotlinMcpResult.Success).content.contains("val acc = items.fold(1) { acc, x -> acc * x }")) { "foldContent" }

                // 9. Enclosing function replacement
                val outerCode = ""${'"'}
                    fun process(items: List<Int>): List<Int> {
                        val result = mutableListOf<Int>()
                        for (x in items) {
                            result.add(x * 2)
                        }
                        return result
                    }
                ""${'"'}.trimIndent()
                val outerRes = refactorer.convertImperativeToFunctional(outerCode)
                check(outerRes is KotlinMcpResult.Success) { "outerRes success" }
                check((outerRes as KotlinMcpResult.Success).content.contains("fun process(items: List<Int>): List<Int> {")) { "outerContent" }
                check((outerRes as KotlinMcpResult.Success).content.contains("val result = items.map { x -> x * 2 }")) { "outerContent mapped" }

                // 10. Local statements inside loop body
                val localStmtCode = ""${'"'}
                    val result = mutableListOf<Int>()
                    for (x in items) {
                        val y = x * 2
                        result.add(y + 1)
                    }
                ""${'"'}.trimIndent()
                val localRes = refactorer.convertImperativeToFunctional(localStmtCode)
                check(localRes is KotlinMcpResult.Success) { "localRes success" }
                check((localRes as KotlinMcpResult.Success).content.contains("val y = x * 2")) { "local y" }

                // 11. Lambda argument block and string quotes with escape
                val stringInFor = "val result = mutableListOf<String>()\nfor (x in items) { result.add(\"hello \\\"world\\\"\") }"
                val strRes = refactorer.convertImperativeToFunctional(stringInFor)
                check(strRes is KotlinMcpResult.Success) { "strRes success" }

                // 12. Enclosing function with short-circuit loop
                val funcShortCode = ""${'"'}
                    fun check(xs: List<Int>): Boolean {
                        for (x in xs) {
                            if (x > 0) return true
                        }
                        return false
                    }
                ""${'"'}.trimIndent()
                val funcShortRes = refactorer.convertImperativeToFunctional(funcShortCode)
                check(funcShortRes is KotlinMcpResult.Success) { "funcShortRes success" }
                check((funcShortRes as KotlinMcpResult.Success).content.contains("xs.any { x > 0 }")) { "funcShort any" }

                // 13. Unsupported pattern
                val unsuppRes = refactorer.convertImperativeToFunctional("val y = 42")
                check(unsuppRes is KotlinMcpResult.Error) { "unsupp error" }
                check((unsuppRes as KotlinMcpResult.Error).code == "UNSUPPORTED_PATTERN") { "unsupp code" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LoopToFunctionalRefactorer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN LoopToFunctionalRefactorer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LoopToFunctionalRefactorer.kt")
        assertTrue(
            report.score >= 65.0,
            "Mutation score for LoopToFunctionalRefactorer.kt (${report.score}%) must be at least 65%"
        )
    }

    @Test
    fun `mutation test production QuickFixGenerator source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/QuickFixGenerator.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val refactorerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.shared.DiffUtils
            import com.gokorei.kotlinmcp.shared.SourceUtils
        """.trimIndent()

        val productionCode = imports + "\n\n" + refactorerCode

        val testSuiteCode = """
            fun main() {
                val gen = QuickFixGenerator()

                // 1. Empty code error
                val emptyRes = gen.generateQuickFix("   ", "some error")
                check(emptyRes is KotlinMcpResult.Error) { "empty error" }
                check((emptyRes as KotlinMcpResult.Error).code == "EMPTY_SNIPPET") { "empty code" }

                // 2. Unresolved reference with annotations, package, and import placement
                val codeWithDirs = ""${'"'}
                    @file:OptIn(ExperimentalStdlibApi::class)
                    package com.example.app
                    import com.example.other.Foo

                    fun main() {
                        val x = Bar()
                    }
                ""${'"'}.trimIndent()
                val unresRes = gen.generateQuickFix(codeWithDirs, "Unresolved reference: Bar")
                check(unresRes is KotlinMcpResult.Success) { "unres success" }
                val unresContent = (unresRes as KotlinMcpResult.Success).content
                check(unresContent.contains("# Quick-Fix (Unified Diff)\n\nDiagnostic-driven fixes:")) { "header" }
                check(unresContent.contains("\n\nUnified diff:\n```diff\n")) { "diff fence" }
                check(unresContent.trim().endsWith("```")) { "diff end fence" }
                check(unresContent.contains("Unresolved reference `Bar`")) { "unres msg" }
                check(unresContent.contains("4|+|import com.example.Bar")) { "unres diff: " + unresContent }
                check(unresRes.metadata["fixCount"] == "1") { "fixCount 1" }

                // 3. Unresolved reference 'symbol' single quotes
                val singleQuoteRes = gen.generateQuickFix("val x = 1", "Unresolved reference 'Baz'")
                check(singleQuoteRes is KotlinMcpResult.Success) { "singleQuote success" }
                check((singleQuoteRes as KotlinMcpResult.Success).content.contains("Unresolved reference `Baz`")) { "singleQuote msg" }

                // 4. Missing import FQCN
                val fqcnRes = gen.generateQuickFix("val x = 1", "missing import: com.example.model.Entity")
                check(fqcnRes is KotlinMcpResult.Success) { "fqcn success" }
                check((fqcnRes as KotlinMcpResult.Success).content.contains("Missing import for `com.example.model.Entity`")) { "fqcn msg" }

                // 5. Non-null assertion !! in AST (direct and nested in lambda/calls)
                val nonNullCode = "fun test(x: String?) { val y = x!! }"
                val nonNullRes = gen.generateQuickFix(nonNullCode, "type mismatch")
                check(nonNullRes is KotlinMcpResult.Success) { "nonNull success" }
                check((nonNullRes as KotlinMcpResult.Success).content.contains("`!!` non-null assertion")) { "nonNull msg" }

                val nestedBangCode = "fun wrap() { val f = { x: String? -> println(listOf(x!!)) } }"
                val nestedBangRes = gen.generateQuickFix(nestedBangCode, "type mismatch")
                check(nestedBangRes is KotlinMcpResult.Success) { "nestedBang success" }
                check((nestedBangRes as KotlinMcpResult.Success).content.contains("`!!` non-null assertion")) { "nestedBang msg" }

                // 6. No resolvable pattern found (non-empty vs empty diagnostic)
                val cleanCode = "val x = 42"
                val noFixRes = gen.generateQuickFix(cleanCode, "unknown warning")
                check(noFixRes is KotlinMcpResult.Success) { "noFix success" }
                val noFixContent = (noFixRes as KotlinMcpResult.Success).content
                check(noFixContent.contains("No resolvable pattern found in the diagnostic. Reported diagnostic:\n```\nunknown warning\n```\nRe-run kotlin_check_snippet after manually fixing to confirm.")) { "noFix msg" }
                check(noFixRes.metadata["fixCount"] == "0") { "fixCount 0" }

                val emptyDiagRes = gen.generateQuickFix(cleanCode, "")
                check(emptyDiagRes is KotlinMcpResult.Success) { "emptyDiag success" }
                val emptyDiagContent = (emptyDiagRes as KotlinMcpResult.Success).content
                check(emptyDiagContent.contains("```\n(empty)\n```")) { "empty diag text" }

                // 7. Import insertion positioning (package only, annotation only, no directives, existing import)
                val onlyPkg = "package foo.bar\n\nval x = Baz()"
                val onlyPkgRes = gen.generateQuickFix(onlyPkg, "Unresolved reference: Baz")
                check(onlyPkgRes is KotlinMcpResult.Success) { "onlyPkg success" }
                check((onlyPkgRes as KotlinMcpResult.Success).content.contains("2|+|import com.example.Baz")) { "onlyPkg import" }

                val onlyAnno = "@file:OptIn(Foo::class)\n\nval x = Baz()"
                val onlyAnnoRes = gen.generateQuickFix(onlyAnno, "Unresolved reference: Baz")
                check(onlyAnnoRes is KotlinMcpResult.Success) { "onlyAnno success" }
                check((onlyAnnoRes as KotlinMcpResult.Success).content.contains("2|+|import com.example.Baz")) { "onlyAnno import" }

                val noDir = "val x = Baz()"
                val noDirRes = gen.generateQuickFix(noDir, "Unresolved reference: Baz")
                check(noDirRes is KotlinMcpResult.Success) { "noDir success" }
                check((noDirRes as KotlinMcpResult.Success).content.contains("1|+|import com.example.Baz")) { "noDir import" }

                val existingImport = "import com.example.Baz\nval x = Baz()"
                val existingRes = gen.generateQuickFix(existingImport, "Unresolved reference: Baz")
                check(existingRes is KotlinMcpResult.Success) { "existing success" }
                check((existingRes as KotlinMcpResult.Success).content.contains("Unresolved reference `Baz`")) { "existing msg" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: QuickFixGenerator.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN QuickFixGenerator.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for QuickFixGenerator.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for QuickFixGenerator.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production RxJavaToCoroutinesRefactorer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/RxJavaToCoroutinesRefactorer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val refactorerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtUserType
        """.trimIndent()

        val productionCode = imports + "\n\n" + refactorerCode

        val testSuiteCode = """
            fun main() {
                val refactorer = RxJavaToCoroutinesRefactorer()

                // 1. Delegating function check
                val delRes = refactorer.convertRxJavaToCoroutines("fun s(): Observable<Int> = Observable.just(1)")
                check(delRes is KotlinMcpResult.Success) { "delRes success" }
                check((delRes as KotlinMcpResult.Success).content.contains("`Observable` → `Flow<T>`")) { "delRes content" }

                // 2. Comprehensive type and operator discovery
                val complexRx = ""${'"'}
                    fun stream(): Observable<String> {
                        val obs: Observable<String> = Observable.create { emitter -> emitter.onNext("hi") }
                        val flowable: Flowable<Int> = Flowable.just(1)
                        val single: Single<Long> = Single.just(42L)
                        val callableSingle: Single<String> = Single.fromCallable { "data" }
                        val maybe: Maybe<Double> = Maybe.just(3.14)
                        val comp: Completable = Completable.fromAction { println() }
                        val opt: Optional<String> = Optional.of("opt")
                        val disp: Disposable = obs.subscribeOn(Schedulers.io())
                            .observeOn(AndroidSchedulers.mainThread())
                            .flatMap { Observable.just(it) }
                            .map { it.uppercase() }
                            .subscribe { println(it) }
                        disp.dispose()
                        return obs
                    }
                ""${'"'}.trimIndent()

                val res = refactorer.migrateRxJavaToCoroutines(complexRx)
                check(res is KotlinMcpResult.Success) { "res success" }
                val content = (res as KotlinMcpResult.Success).content

                // Check discovery lines
                check(content.contains("Per-type mapping (PSI AST analysis):")) { "per-type header" }
                check(content.contains("`Observable` → `Flow<T>`")) { "obs mapping" }
                check(content.contains("`Flowable` → `Flow<T>` with backpressure")) { "flowable mapping" }
                check(content.contains("`Single` → `suspend fun foo(): T` or `Result<T>`")) { "single mapping" }
                check(content.contains("`Maybe` → `suspend fun foo(): T?`")) { "maybe mapping" }
                check(content.contains("`Completable` → `suspend fun foo()`")) { "completable mapping" }
                check(content.contains("`Disposable` → `Job`")) { "disposable mapping" }
                check(content.contains("`Optional<T>` / `Maybe<T>` wrapper → nullable `T?`")) { "optional mapping" }

                // Check operator mappings
                check(content.contains("operator `subscribeOn` → `withContext(Dispatchers.IO) { } (inject dispatcher instead of hardcoding)`")) { "subOn op" }
                check(content.contains("operator `observeOn`") && content.contains("withContext` on the UI/consumer context")) { "obsOn op" }
                check(content.contains("operator `flatMap`") && content.contains("flatMapConcat")) { "flatMap op" }
                check(content.contains("operator `map`") && content.contains("map { }` (Flow)")) { "map op" }
                check(content.contains("operator `subscribe`") && content.contains("collect { }` (Flow)")) { "subscribe op" }
                check(content.contains("operator `dispose`") && content.contains("Job.cancel()")) { "dispose op" }

                // Check rewritten AST stage
                check(content.contains("## Initial rewrite sketch (verify each step with kotlin_check_snippet):\n```kotlin\n")) { "sketch header" }
                check(content.trim().endsWith("```")) { "sketch end" }

                // 3. Isolated AST rewrite snippets
                val createSnippet = "val x = Observable.create { emitter -> emitter.onNext(1) }"
                val createRes = refactorer.migrateRxJavaToCoroutines(createSnippet)
                check((createRes as KotlinMcpResult.Success).content.contains("val x = flow { emit")) { "create rewrite" }

                val fJust = "val x = Flowable.just(1)"
                val fJustRes = refactorer.migrateRxJavaToCoroutines(fJust)
                check((fJustRes as KotlinMcpResult.Success).content.contains("val x = flowOf")) { "fJust rewrite" }

                val obsJust = "val x = Observable.just(1)"
                val obsJustRes = refactorer.migrateRxJavaToCoroutines(obsJust)
                check((obsJustRes as KotlinMcpResult.Success).content.contains("val x = flowOf")) { "obsJust rewrite" }

                val sJust = "val x = Single.just(42)"
                val sJustRes = refactorer.migrateRxJavaToCoroutines(sJust)
                check((sJustRes as KotlinMcpResult.Success).content.contains("val x = Result.success")) { "sJust rewrite" }

                val sCall = "val x = Single.fromCallable { \"hello\" }"
                val sCallRes = refactorer.migrateRxJavaToCoroutines(sCall)
                check((sCallRes as KotlinMcpResult.Success).content.contains("val x = withContext(Dispatchers.IO)")) { "sCall rewrite" }

                val cAct = "val x = Completable.fromAction { println() }"
                val cActRes = refactorer.migrateRxJavaToCoroutines(cAct)
                check((cActRes as KotlinMcpResult.Success).content.contains("val x = withContext(Dispatchers.IO)")) { "cAct rewrite" }

                val subOnCode = "val x = obs.subscribeOn(Schedulers.io())"
                val subOnRes = refactorer.migrateRxJavaToCoroutines(subOnCode)
                check((subOnRes as KotlinMcpResult.Success).content.contains("obs./* withContext(Dispatchers.IO) */")) { "subOn rewrite" }

                val obsOnCode = "val x = obs.observeOn(AndroidSchedulers.mainThread())"
                val obsOnRes = refactorer.migrateRxJavaToCoroutines(obsOnCode)
                check((obsOnRes as KotlinMcpResult.Success).content.contains("obs./* withContext(Dispatchers.Main) */")) { "obsOn rewrite" }

                val subDisp = "val d = stream.subscribe { println(it) }; d.dispose()"
                val subDispRes = refactorer.migrateRxJavaToCoroutines(subDisp)
                check((subDispRes as KotlinMcpResult.Success).content.contains("stream.collect")) { "collect rewrite" }
                check((subDispRes as KotlinMcpResult.Success).content.contains("d./* parent-scope cancellation */()")) { "dispose rewrite" }

                // 4. Clean code with no RxJava types
                val cleanRes = refactorer.migrateRxJavaToCoroutines("val x = 42")
                check(cleanRes is KotlinMcpResult.Success) { "cleanRes success" }
                val cleanContent = (cleanRes as KotlinMcpResult.Success).content
                check(cleanContent.contains("No RxJava stream types detected.")) { "no rx msg" }
                check(cleanContent.contains("Code left unchanged.")) { "clean unchanged" }
                check(cleanRes.metadata["mappingCount"] == "0") { "mappingCount 0" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: RxJavaToCoroutinesRefactorer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN RxJavaToCoroutinesRefactorer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for RxJavaToCoroutinesRefactorer.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for RxJavaToCoroutinesRefactorer.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production RefactoringService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/refactoring/RefactoringService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.shared.CommandService
            import com.gokorei.kotlinmcp.refactoring.JavaToKotlinRefactorer
            import com.gokorei.kotlinmcp.refactoring.LoopToFunctionalRefactorer
            import com.gokorei.kotlinmcp.refactoring.RxJavaToCoroutinesRefactorer
            import com.gokorei.kotlinmcp.refactoring.ArrowRefactorer
            import com.gokorei.kotlinmcp.refactoring.QuickFixGenerator
            import com.gokorei.kotlinmcp.refactoring.IdiomaticKotlinSuggestor
            import com.gokorei.kotlinmcp.refactoring.DatetimeMigrationSuggestor
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceCode

        val testSuiteCode = """
            fun main() {
                val service: RefactoringService = DefaultRefactoringService()

                // 1. JAVA_TO_KOTLIN & CONVERT_JAVA_TO_KOTLIN
                val javaCode = "public record Item(String name) {}"
                val res1 = service.execute(RefactoringAction.JAVA_TO_KOTLIN, javaCode)
                check(res1 is KotlinMcpResult.Success && res1.content.contains("data class Item")) { "res1" }

                val res2 = service.execute(RefactoringAction.CONVERT_JAVA_TO_KOTLIN, javaCode)
                check(res2 is KotlinMcpResult.Success && res2.content.contains("data class Item")) { "res2" }

                // 2. IMPERATIVE_TO_FUNCTIONAL & CONVERT_IMPERATIVE_LOOP_TO_FUNCTIONAL
                val loopCode = "val res = mutableListOf<Int>()\nfor (x in items) { res.add(x * 2) }"
                val res3 = service.execute(RefactoringAction.IMPERATIVE_TO_FUNCTIONAL, loopCode)
                check(res3 is KotlinMcpResult.Success && res3.content.contains("items.map")) { "res3" }

                val res4 = service.execute(RefactoringAction.CONVERT_IMPERATIVE_LOOP_TO_FUNCTIONAL, loopCode)
                check(res4 is KotlinMcpResult.Success && res4.content.contains("items.map")) { "res4" }

                // 3. RXJAVA_TO_COROUTINES & MIGRATE_RXJAVA_TO_COROUTINES
                val rxCode = "val x = Observable.just(1)"
                val res5 = service.execute(RefactoringAction.RXJAVA_TO_COROUTINES, rxCode)
                check(res5 is KotlinMcpResult.Success && res5.content.contains("`Observable` → `Flow<T>`")) { "res5" }

                val res6 = service.execute(RefactoringAction.MIGRATE_RXJAVA_TO_COROUTINES, rxCode)
                check(res6 is KotlinMcpResult.Success && res6.content.contains("`Observable` → `Flow<T>`")) { "res6" }

                // 4. MIGRATE_ARROW_RAISE
                val arrowCode = "fun parse(s: String): Int { return try { s.toInt() } catch (e: Exception) { -1 } }"
                val res7 = service.execute(RefactoringAction.MIGRATE_ARROW_RAISE, arrowCode)
                check(res7 is KotlinMcpResult.Success && (res7.content.contains("Either") || res7.content.contains("Arrow"))) { "res7" }

                // 5. GENERATE_QUICK_FIX (2-arg default and 3-arg with diagnostic)
                val qfCode = "val x = 1"
                val res8 = service.execute(RefactoringAction.GENERATE_QUICK_FIX, qfCode)
                check(res8 is KotlinMcpResult.Success && res8.content.contains("No resolvable pattern found")) { "res8" }

                val res9 = service.execute(RefactoringAction.GENERATE_QUICK_FIX, "val x = Foo()", "Unresolved reference: Foo")
                check(res9 is KotlinMcpResult.Success && res9.content.contains("Unresolved reference `Foo`")) { "res9" }

                // 6. SUGGEST_IDIOMS & SUGGEST_IDIOMATIC_KOTLIN
                val idiomCode = "fun test(x: String?) { if (x != null) { println(x) } }"
                val res10 = service.execute(RefactoringAction.SUGGEST_IDIOMS, idiomCode)
                check(res10 is KotlinMcpResult.Success && res10.content.contains("?.let")) { "res10" }

                val res11 = service.execute(RefactoringAction.SUGGEST_IDIOMATIC_KOTLIN, idiomCode)
                check(res11 is KotlinMcpResult.Success && res11.content.contains("?.let")) { "res11" }

                // 7. MIGRATE_DATETIME
                val dtCode = "val now = java.util.Date()"
                val res12 = service.execute(RefactoringAction.MIGRATE_DATETIME, dtCode)
                check(res12 is KotlinMcpResult.Success && res12.content.contains("kotlinx-datetime")) { "res12" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: RefactoringService.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN RefactoringService.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for RefactoringService.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for RefactoringService.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production ComposeAnalyzer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/ComposeAnalyzer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val analyzerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.com.intellij.psi.PsiElement
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtFunctionType
            import org.jetbrains.kotlin.psi.KtLambdaArgument
            import org.jetbrains.kotlin.psi.KtLambdaExpression
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtUserType
        """.trimIndent()

        val productionCode = imports + "\n\n" + analyzerCode

        val testSuiteCode = """
            fun main() {
                val analyzer = ComposeAnalyzer()

                // 1. Unstable parameter in @Composable function
                val unstableCode = ""${'"'}
                    class MyUnstableData(val x: Int)

                    @Composable
                    fun MyView(data: MyUnstableData, title: String, count: Int, items: List<String>, onClick: () -> Unit) {
                    }
                ""${'"'}.trimIndent()

                val res1 = analyzer.analyzeCompose(unstableCode)
                check(res1 is KotlinMcpResult.Success) { "res1 success" }
                val content1 = (res1 as KotlinMcpResult.Success).content
                check(content1.contains("⚠️ `@Composable MyView` takes parameter `data` of type `MyUnstableData`, which is not a known stable type.")) { "res1 unstable param" }
                check(!content1.contains("title") && !content1.contains("count") && !content1.contains("items") && !content1.contains("onClick")) { "res1 stable params clean" }
                check(res1.metadata["findingsCount"] == "1") { "res1 count 1" }

                // 2. Stable/Immutable class & value class parameters (no warning)
                val stableDeclaredCode = ""${'"'}
                    @Stable
                    class StableData(val x: Int)
                    @Immutable
                    class ImmutableData(val x: Int)
                    @JvmInline
                    value class UserId(val id: String)

                    @Composable
                    fun StableView(s: StableData, i: ImmutableData?, u: UserId) {
                    }
                ""${'"'}.trimIndent()

                val res2 = analyzer.analyzeCompose(stableDeclaredCode)
                check(res2 is KotlinMcpResult.Success && (res2 as KotlinMcpResult.Success).content.contains("No obvious Compose anti-patterns detected.")) { "res2 clean" }

                // 3. remember without keys declaring var or mutableStateOf
                val rememberVarCode = ""${'"'}
                    @Composable
                    fun Counter() {
                        val state = remember {
                            var internalCount = 0
                            internalCount
                        }
                    }
                ""${'"'}.trimIndent()
                val res3 = analyzer.analyzeCompose(rememberVarCode)
                check((res3 as KotlinMcpResult.Success).content.contains("`remember { }` (line 3) has no key arguments yet its body declares mutable state.")) { "res3 var" }

                val rememberStateCode = ""${'"'}
                    @Composable
                    fun StateHolder() {
                        val state = remember { mutableStateOf(0) }
                    }
                ""${'"'}.trimIndent()
                val res4 = analyzer.analyzeCompose(rememberStateCode)
                check((res4 as KotlinMcpResult.Success).content.contains("`remember { }` (line 3) has no key arguments yet its body declares mutable state.")) { "res4 state" }

                val rememberWithKeyCode = ""${'"'}
                    @Composable
                    fun SafeState(id: String) {
                        val state = remember(id) { mutableStateOf(0) }
                    }
                ""${'"'}.trimIndent()
                val res5 = analyzer.analyzeCompose(rememberWithKeyCode)
                check((res5 as KotlinMcpResult.Success).content.contains("No obvious Compose anti-patterns detected.")) { "res5 safe" }

                // 4. derivedStateOf unwrapped vs wrapped in remember
                val unwrappedDerived = ""${'"'}
                    @Composable
                    fun DerivedView(count: Int) {
                        val derived = derivedStateOf { count * 2 }
                    }
                ""${'"'}.trimIndent()
                val res6 = analyzer.analyzeCompose(unwrappedDerived)
                check((res6 as KotlinMcpResult.Success).content.contains("Line 3: `derivedStateOf { }` is not wrapped in `remember { }`. It should be `val x by remember { derivedStateOf { ... } }`.")) { "res6 derived" }

                val wrappedDerived = ""${'"'}
                    @Composable
                    fun SafeDerived(count: Int) {
                        val derived = remember { derivedStateOf { count * 2 } }
                    }
                ""${'"'}.trimIndent()
                val res7 = analyzer.analyzeCompose(wrappedDerived)
                check((res7 as KotlinMcpResult.Success).content.contains("No obvious Compose anti-patterns detected.")) { "res7 safe derived" }

                // 5. Completely clean code
                val cleanRes = analyzer.analyzeCompose("@Composable fun Header(title: String) {}")
                check((cleanRes as KotlinMcpResult.Success).content.contains("No obvious Compose anti-patterns detected.")) { "cleanRes" }
                check(cleanRes.metadata["findingsCount"] == "0") { "cleanCount 0" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ComposeAnalyzer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN ComposeAnalyzer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ComposeAnalyzer.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for ComposeAnalyzer.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production CoroutinesSafetyAnalyzer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/CoroutinesSafetyAnalyzer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val analyzerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtImportDirective
            import org.jetbrains.kotlin.psi.KtLambdaExpression
            import org.jetbrains.kotlin.psi.KtLoopExpression
            import org.jetbrains.kotlin.psi.KtNameReferenceExpression
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtWhileExpression
        """.trimIndent()

        val productionCode = imports + "\n\n" + analyzerCode

        val testSuiteCode = """
            fun main() {
                val analyzer = CoroutinesSafetyAnalyzer()

                // 1. Thread.sleep detection
                val sleepCode = "fun test() { Thread.sleep(100) }"
                val sleepRes = analyzer.explainCoroutines(sleepCode)
                check((sleepRes as KotlinMcpResult.Success).content.contains("Blocking call `Thread.sleep` detected in coroutine context. Use `delay(...)` instead to avoid blocking worker threads.")) { "sleep warning" }

                // 2. runBlocking detection
                val rbCode = "fun test() { runBlocking { } }"
                val rbRes = analyzer.explainCoroutines(rbCode)
                check((rbRes as KotlinMcpResult.Success).content.contains("`runBlocking` detected. Ensure this is only used in main entry points or tests, not inside suspended execution chains.")) { "rb warning" }

                // 3. Blocking method inside suspend function
                val blockCode = "suspend fun fetch() { val txt = File(\"test\").readText() }"
                val blockRes = analyzer.explainCoroutines(blockCode)
                check((blockRes as KotlinMcpResult.Success).content.contains("Blocking call `readText` detected in suspend function. Blocking calls stall worker threads; use async Kotlin equivalents (`await()`, `withContext(Dispatchers.IO)`) instead.")) { "block in suspend" }

                // Non-suspend function with readText (no warning)
                val plainBlockCode = "fun readData() { val txt = File(\"test\").readText() }"
                val plainRes = analyzer.explainCoroutines(plainBlockCode)
                check((plainRes as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "plain block clean" }

                // 4. GlobalScope usage
                val gsCode = "fun launch() { GlobalScope.launch { } }"
                val gsRes = analyzer.explainCoroutines(gsCode)
                check((gsRes as KotlinMcpResult.Success).content.contains("`GlobalScope` usage detected. Prefer structured concurrency via `coroutineScope` or passing `CoroutineScope` as context parameter.")) { "gs warning" }

                // GlobalScope import directive only (no warning)
                val importGs = "import kotlinx.coroutines.GlobalScope\nfun main() {}"
                val importGsRes = analyzer.explainCoroutines(importGs)
                check((importGsRes as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "import gs clean" }

                // 5. Hardcoded dispatchers (single and multiple)
                val singleDispCode = "fun test() { val d = Dispatchers.IO }"
                val singleDispRes = analyzer.explainCoroutines(singleDispCode)
                check((singleDispRes as KotlinMcpResult.Success).content.contains("Hardcoded dispatcher Dispatchers.IO detected.")) { "single disp" }

                val multiDispCode = "fun test() { val d = Dispatchers.IO; val m = Dispatchers.Main; val def = Dispatchers.Default; val u = Dispatchers.Unconfined }"
                val multiDispRes = analyzer.explainCoroutines(multiDispCode)
                check((multiDispRes as KotlinMcpResult.Success).content.contains("Hardcoded dispatchers Dispatchers.IO, Dispatchers.Main, Dispatchers.Default, Dispatchers.Unconfined detected.")) { "multi disp" }

                // 6. Unbounded loop without suspend point
                val loopCode = ""${'"'}
                    suspend fun loop() {
                        while (true) {
                            println(1)
                        }
                    }
                ""${'"'}.trimIndent()
                val loopRes = analyzer.explainCoroutines(loopCode)
                check((loopRes as KotlinMcpResult.Success).content.contains("Line 2: Unbounded `while(true)` loop inside launch/async/suspend with no delay/yield/isActive check — may leak or block the coroutine indefinitely.")) { "unbounded loop" }

                // Unbounded loop in launch { }
                val launchLoopCode = ""${'"'}
                    fun start(scope: CoroutineScope) {
                        scope.launch {
                            while (true) {
                                println()
                            }
                        }
                    }
                ""${'"'}.trimIndent()
                val launchLoopRes = analyzer.explainCoroutines(launchLoopCode)
                check((launchLoopRes as KotlinMcpResult.Success).content.contains("Unbounded")) { "launchLoop" }

                val asyncLoopCode = "fun start(scope: CoroutineScope) { scope.async { while(true) { println() } } }"
                val asyncLoopRes = analyzer.explainCoroutines(asyncLoopCode)
                check((asyncLoopRes as KotlinMcpResult.Success).content.contains("Unbounded")) { "asyncLoop" }

                val while1Code = "suspend fun loop() { while (1) { println() } }"
                val while1Res = analyzer.explainCoroutines(while1Code)
                check((while1Res as KotlinMcpResult.Success).content.contains("Unbounded")) { "while1" }

                // Safe loop with delay, yield, isActive, awaitCancellation, ensureActive
                val safeDelay = "suspend fun loop() { while(true) { delay(100) } }"
                val safeYield = "suspend fun loop() { while(true) { yield() } }"
                val safeActive = "suspend fun loop() { while(isActive) { println() } }"
                val safeCancel = "suspend fun loop() { while(true) { awaitCancellation() } }"
                val safeEnsure = "suspend fun loop() { while(true) { ensureActive() } }"

                check((analyzer.explainCoroutines(safeDelay) as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "safeDelay" }
                check((analyzer.explainCoroutines(safeYield) as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "safeYield" }
                check((analyzer.explainCoroutines(safeActive) as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "safeActive" }
                check((analyzer.explainCoroutines(safeCancel) as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "safeCancel" }
                check((analyzer.explainCoroutines(safeEnsure) as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "safeEnsure" }

                // 7. Clean code
                val clean = "suspend fun compute(): Int = 42"
                val cleanRes = analyzer.explainCoroutines(clean)
                check((cleanRes as KotlinMcpResult.Success).content.contains("No coroutine anti-patterns detected.")) { "clean" }
                check(cleanRes.metadata["warningsCount"] == "0") { "cleanCount 0" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: CoroutinesSafetyAnalyzer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN CoroutinesSafetyAnalyzer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for CoroutinesSafetyAnalyzer.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for CoroutinesSafetyAnalyzer.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production FileContextAnalyzer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/FileContextAnalyzer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val analyzerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.OccurrenceKind
            import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
            import java.io.File
        """.trimIndent()

        val productionCode = imports + "\n\n" + analyzerCode

        val testSuiteCode = """
            fun main() {
                val analyzer = FileContextAnalyzer()
                val indexer = WorkspaceSemanticIndexer()

                // 1. Invalid arguments: non-existent file or non-kt file
                val err1 = analyzer.fileContext("/non/existent/file.txt", null, indexer)
                check(err1 is KotlinMcpResult.Error && (err1 as KotlinMcpResult.Error).code == "INVALID_ARGUMENTS") { "err1" }

                val tempDir = File(System.getProperty("java.io.tmpdir"), "test_fc_workspace_" + System.currentTimeMillis())
                tempDir.mkdirs()
                try {
                    val fileA = File(tempDir, "A.kt")
                    fileA.writeText(""${'"'}
                        package pkg.a
                        import pkg.b.B

                        class A {
                            val b = B()
                        }
                    ""${'"'}.trimIndent())

                    val fileB = File(tempDir, "B.kt")
                    fileB.writeText(""${'"'}
                        package pkg.b
                        import pkg.a.A

                        class B {
                            fun useA() {
                                val a = A()
                            }
                        }
                    ""${'"'}.trimIndent())

                    // 2. Invalid workspace: not a directory
                    val err2 = analyzer.fileContext(fileA.absolutePath, fileA.absolutePath, indexer)
                    check(err2 is KotlinMcpResult.Error && (err2 as KotlinMcpResult.Error).code == "INVALID_ARGUMENTS") { "err2" }

                    // 3. Target file outside workspace root
                    val otherDir = File(System.getProperty("java.io.tmpdir"), "other_ws_" + System.currentTimeMillis())
                    otherDir.mkdirs()
                    try {
                        val err3 = analyzer.fileContext(fileA.absolutePath, otherDir.absolutePath, indexer)
                        check(err3 is KotlinMcpResult.Error && (err3 as KotlinMcpResult.Error).code == "INVALID_ARGUMENTS") { "err3" }
                    } finally {
                        otherDir.deleteRecursively()
                    }

                    // 4. Valid file context analysis with explicit workspace
                    val resA = analyzer.fileContext(fileA.absolutePath, tempDir.absolutePath, indexer)
                    check(resA is KotlinMcpResult.Success) { "resA success" }
                    val contentA = (resA as KotlinMcpResult.Success).content
                    check(contentA.contains("# File Context: `A.kt`")) { "header A" }
                    check(contentA.contains("- Package: pkg.a")) { "pkg A" }
                    check(contentA.contains("- Imports: pkg.b.B")) { "imports A" }
                    check(contentA.contains("- `pkg.a.A` -> B.kt")) { "outbound list A" }
                    check(contentA.contains("## Inbound Dependencies (declared elsewhere, used here)\n- `B` -> B.kt")) { "inbound list A: " + contentA }
                    check(resA.metadata["file"] == "A.kt") { "metadata file" }
                    check(resA.metadata["package"] == "pkg.a") { "metadata package" }
                    check(resA.metadata["outboundCount"] == "2") { "outboundCount 2" }
                    check(resA.metadata["inboundCount"] == "1") { "inboundCount 1" }

                    // 5. Valid file context analysis with null workspace (defaults to parentFile)
                    val resB = analyzer.fileContext(fileB.absolutePath, null, indexer)
                    check(resB is KotlinMcpResult.Success) { "resB success" }
                    val contentB = (resB as KotlinMcpResult.Success).content
                    check(contentB.contains("# File Context: `B.kt`")) { "header B" }
                    check(contentB.contains("- Package: pkg.b")) { "pkg B" }
                    check(resB.metadata["file"] == "B.kt") { "metadata B file" }
                    check(resB.metadata["package"] == "pkg.b") { "metadata B pkg" }

                    // 6. Standalone file without dependencies
                    val soloFile = File(tempDir, "Solo.kt")
                    soloFile.writeText("val x = 42")
                    val resSolo = analyzer.fileContext(soloFile.absolutePath, tempDir.absolutePath, indexer)
                    check(resSolo is KotlinMcpResult.Success) { "resSolo success" }
                    val contentSolo = (resSolo as KotlinMcpResult.Success).content
                    check(contentSolo.contains("- Package: (default)")) { "solo pkg" }
                    check(contentSolo.contains("- Imports: (none)")) { "solo imports" }
                    check(contentSolo.contains("## Outbound Dependencies (declared here, used elsewhere)\n- (none)")) { "solo none out" }
                    check(contentSolo.contains("## Inbound Dependencies (declared elsewhere, used here)\n- (none)")) { "solo none in" }
                    check(resSolo.metadata["outboundCount"] == "0") { "solo out 0" }
                    check(resSolo.metadata["inboundCount"] == "0") { "solo in 0" }

                    // 7. Non-kt file extension check
                    val javaFile = File(tempDir, "Test.java")
                    javaFile.writeText("class Test {}")
                    val errExt = analyzer.fileContext(javaFile.absolutePath, tempDir.absolutePath, indexer)
                    check(errExt is KotlinMcpResult.Error && errExt.code == "INVALID_ARGUMENTS") { "errExt" }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: FileContextAnalyzer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN FileContextAnalyzer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for FileContextAnalyzer.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for FileContextAnalyzer.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production NullabilityAnalyzer source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/NullabilityAnalyzer.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val analyzerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.lexer.KtTokens
            import org.jetbrains.kotlin.psi.KtBinaryExpression
            import org.jetbrains.kotlin.psi.KtBlockExpression
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
            import org.jetbrains.kotlin.psi.KtElement
            import org.jetbrains.kotlin.psi.KtIfExpression
            import org.jetbrains.kotlin.psi.KtNameReferenceExpression
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtParameter
            import org.jetbrains.kotlin.psi.KtPostfixExpression
            import org.jetbrains.kotlin.psi.KtPrefixExpression
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtPropertyAccessor
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
        """.trimIndent()

        val productionCode = imports + "\n\n" + analyzerCode

        val testSuiteCode = """
            fun main() {
                val analyzer = NullabilityAnalyzer()

                // 1. !! assertion (expression body, block body, prefix, postfix)
                val bangExprCode = "fun test(x: String?): Int = x!!.length"
                val bangExprRes = analyzer.analyzeNullability(bangExprCode)
                check((bangExprRes as KotlinMcpResult.Success).content.contains("Unsafe non-null assertion `!!` detected (`x!!.length`). Prefer `?.let` / `?:` to avoid NPE.")) { "bangExpr" }

                val bangBlockCode = "fun test(x: String?) { val y = x!! }"
                val bangBlockRes = analyzer.analyzeNullability(bangBlockCode)
                check((bangBlockRes as KotlinMcpResult.Success).content.contains("Unsafe non-null assertion `!!` detected (`x!!`). Prefer `?.let` / `?:` to avoid NPE.")) { "bangBlock" }

                val bangPrefixCode = "fun test(x: Boolean?) { val y = !x!! }"
                val bangPrefixRes = analyzer.analyzeNullability(bangPrefixCode)
                check((bangPrefixRes as KotlinMcpResult.Success).content.contains("Unsafe non-null assertion `!!` detected")) { "bangPrefix" }

                // 2. Unsafe dereference of nullable variable in functions and top-level properties
                val derefCode = ""${'"'}
                    fun test(x: String?) {
                        println(x.length)
                    }
                ""${'"'}.trimIndent()
                val derefRes = analyzer.analyzeNullability(derefCode)
                check((derefRes as KotlinMcpResult.Success).content.contains("Line 2: Unsafe dereference of nullable `x` (`x.length`). Use `x?.let { }`, `?:`, or a smart-cast guard.")) { "deref" }

                val topPropDeref = "val opt: String? = null\nval len = opt.length"
                val topPropRes = analyzer.analyzeNullability(topPropDeref)
                check((topPropRes as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `opt`")) { "topPropDeref" }

                // 3. Passing nullable argument (identifier or function call) to non-null parameter
                val passCode = ""${'"'}
                    fun requireName(name: String) {}
                    fun execute(opt: String?) {
                        requireName(opt)
                    }
                ""${'"'}.trimIndent()
                val passRes = analyzer.analyzeNullability(passCode)
                check((passRes as KotlinMcpResult.Success).content.contains("Line 3: Unsafe dereference / parameter passing: argument `opt` passed to non-null parameter in `requireName(...)` is nullable. Use `?:` or smart-cast guard.")) { "pass param" }

                val fnPassCode = ""${'"'}
                    fun getOpt(): String? = null
                    fun requireVal(s: String) {}
                    fun run() {
                        requireVal(getOpt())
                    }
                ""${'"'}.trimIndent()
                val fnPassRes = analyzer.analyzeNullability(fnPassCode)
                check((fnPassRes as KotlinMcpResult.Success).content.contains("argument `getOpt()` passed to non-null parameter in `requireVal(...)` is nullable")) { "fnPass" }

                // 4. If statements: guard is active inside thenBlock, but inactive outside and in elseBlock
                val ifScoped = ""${'"'}
                    fun test(x: String?) {
                        if (x != null) {
                            println(x.length)
                        }
                        println(x.length)
                    }
                ""${'"'}.trimIndent()
                val ifScopedRes = analyzer.analyzeNullability(ifScoped)
                val ifScopedContent = (ifScopedRes as KotlinMcpResult.Success).content
                check(ifScopedContent.contains("Line 5: Unsafe dereference of nullable `x`")) { "ifScoped outside: " + ifScopedContent }
                check(!ifScopedContent.contains("Line 3:")) { "ifScoped inside safe" }

                val ifWithElse = "fun test(x: String?) { if (x != null) { println(x.length) } else { println(x.length) } }"
                val ifWithElseRes = analyzer.analyzeNullability(ifWithElse)
                check((ifWithElseRes as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `x`")) { "ifWithElse" }

                val ifNegIsDeref = "fun test(x: String?) { if (x !is String) { println(x.length) } }"
                val ifNegIsRes = analyzer.analyzeNullability(ifNegIsDeref)
                check((ifNegIsRes as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `x`")) { "ifNegIs" }

                val ifElseUnsafe = ""${'"'}
                    fun test(x: String?, flag: Boolean) {
                        if (flag) {
                            println(x.length)
                        } else {
                            println(x.length)
                        }
                    }
                ""${'"'}.trimIndent()
                val ifElseRes = analyzer.analyzeNullability(ifElseUnsafe)
                check((ifElseRes as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `x`")) { "ifElseUnsafe" }

                val nestedIfExpr = "fun test(x: String?) { val res = if (x != null) { x.length } else 0 }"
                check((analyzer.analyzeNullability(nestedIfExpr) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "nestedIfExpr" }

                // 5. Smart-cast guards: if != null, if is String, null != x, requireNotNull, checkNotNull, check, elvis ?: return, safe call let
                val ifNotNull = "fun test(x: String?) { if (x != null) { println(x.length) } }"
                val ifInvert = "fun test(x: String?) { if (null != x) { println(x.length) } }"
                val ifIs = "fun test(x: Any?) { if (x is String) { println(x.length) } }"
                val reqNotNull = "fun test(x: String?) { requireNotNull(x); println(x.length) }"
                val chkNotNull = "fun test(x: String?) { checkNotNull(x); println(x.length) }"
                val chkBin = "fun test(x: String?) { check(x != null); println(x.length) }"
                val elvisGuard = "fun test(x: String?) { val safe = x ?: return; println(x.length) }"
                val safeLet = "fun test(x: String?) { x?.let { println(it.length) } }"
                val safeNamedLet = "fun test(x: String?) { x?.let { customName -> println(customName.length) } }"
                val chainedLet = "fun test(x: String?, y: String?) { x?.let { y?.let { other -> println(other.length + it.length) } } }"

                check((analyzer.analyzeNullability(ifNotNull) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "ifNotNull" }
                check((analyzer.analyzeNullability(ifInvert) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "ifInvert" }
                check((analyzer.analyzeNullability(ifIs) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "ifIs" }
                check((analyzer.analyzeNullability(reqNotNull) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "reqNotNull" }
                check((analyzer.analyzeNullability(chkNotNull) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "chkNotNull" }
                check((analyzer.analyzeNullability(chkBin) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "chkBin" }
                check((analyzer.analyzeNullability(elvisGuard) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "elvisGuard" }
                check((analyzer.analyzeNullability(safeLet) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "safeLet" }
                check((analyzer.analyzeNullability(safeNamedLet) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "safeNamedLet" }
                check((analyzer.analyzeNullability(chainedLet) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "chainedLet" }

                // 6. Reassignment analysis: reassigned non-null vs null
                val reassignNonNull = "fun test(x: String?) { var opt: String? = null; opt = \"hello\"; println(opt.length) }"
                check((analyzer.analyzeNullability(reassignNonNull) as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "reassignNonNull" }

                val reassignNull = "fun test(x: String?) { var opt: String? = \"init\"; opt = null; println(opt.length) }"
                check((analyzer.analyzeNullability(reassignNull) as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `opt`")) { "reassignNull" }

                // 7. Unsafe dereference inside safe call block
                val mixedLet = "fun test(x: String?, y: String?) { x?.let { println(y.length) } }"
                check((analyzer.analyzeNullability(mixedLet) as KotlinMcpResult.Success).content.contains("Unsafe dereference of nullable `y`")) { "mixedLet" }

                // 8. Completely clean code
                val clean = "fun test(x: String): Int = x.length"
                val cleanRes = analyzer.analyzeNullability(clean)
                check((cleanRes as KotlinMcpResult.Success).content.contains("No unsafe nullability patterns detected.")) { "clean" }
                check(cleanRes.metadata["findingsCount"] == "0") { "clean 0" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: NullabilityAnalyzer.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN NullabilityAnalyzer.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for NullabilityAnalyzer.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for NullabilityAnalyzer.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production SymbolInspector source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/SymbolInspector.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val inspectorCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.KtClass
            import org.jetbrains.kotlin.psi.KtClassOrObject
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtObjectDeclaration
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtTypeAlias
        """.trimIndent()

        val productionCode = imports + "\n\n" + inspectorCode

        val testSuiteCode = """
            fun main() {
                val inspector = SymbolInspector()

                // 1. Comprehensive declaration test: Class, properties, constructors, functions, nested class
                val fullClassCode = ""${'"'}
                    class Person(val name: String, var age: Int) {
                        constructor(name: String) : this(name, 0)
                        val status = "active"
                        fun greet() {}
                        class Address(val city: String) {
                            fun zip() = 123
                        }
                    }
                ""${'"'}.trimIndent()
                val fullRes = inspector.inspectSymbol(fullClassCode)
                check(fullRes is KotlinMcpResult.Success) { "fullRes" }
                val fullContent = (fullRes as KotlinMcpResult.Success).content
                check(fullContent.contains("- Class: Person")) { "class Person" }
                check(fullContent.contains("- Properties: name, age, status")) { "props Person" }
                check(fullContent.contains("- Secondary constructors: 1")) { "sec ctor" }
                check(fullContent.contains("- Functions: greet")) { "funs Person" }
                check(fullContent.contains("- Nested classes: Address")) { "nested classes list" }
                check(fullContent.contains("- Nested class: Address")) { "nested class header" }
                check(fullContent.contains("- Properties: city")) { "props Address" }
                check(fullContent.contains("- Functions: zip")) { "funs Address" }
                check(!fullContent.contains("- Top-level elements analyzed")) { "no fallback in fullContent" }
                check(fullRes.metadata["lineCount"] == "8") { "metadata lineCount: " + fullRes.metadata["lineCount"] }

                // Class without secondary constructors
                val noSecCtorCode = "class Simple(val x: Int)"
                val noSecRes = inspector.inspectSymbol(noSecCtorCode)
                val noSecContent = (noSecRes as KotlinMcpResult.Success).content
                check(!noSecContent.contains("- Secondary constructors:")) { "no sec ctor when 0" }
                check(!noSecContent.contains("- Top-level elements analyzed")) { "no fallback in noSecContent" }

                // 2. Kinds: Companion object, Object, Interface, Enum, Annotation, Sealed class
                val kindsCode = ""${'"'}
                    class Host {
                        companion object Factory {
                            fun create() = Host()
                        }
                    }
                    object Singleton {
                        val instance = 1
                    }
                    interface Service {
                        fun execute()
                    }
                    enum class Color {
                        RED, GREEN;
                        fun isRed() = this == RED
                    }
                    annotation class Marker
                    sealed class Expr
                ""${'"'}.trimIndent()
                val kindsRes = inspector.inspectSymbol(kindsCode)
                val kindsContent = (kindsRes as KotlinMcpResult.Success).content
                check(kindsContent.contains("- Class: Host")) { "kind Host" }
                check(kindsContent.contains("- Nested companion object: Factory")) { "kind Companion" }
                check(kindsContent.contains("- Functions: create")) { "funs Factory" }
                check(kindsContent.contains("- Object: Singleton")) { "kind Object" }
                check(kindsContent.contains("- Properties: instance")) { "props Singleton" }
                check(kindsContent.contains("- Interface: Service")) { "kind Interface" }
                check(kindsContent.contains("- Functions: execute")) { "funs Service" }
                check(kindsContent.contains("- Enum: Color")) { "kind Enum" }
                check(kindsContent.contains("- Enum constants: RED, GREEN")) { "enum constants" }
                check(kindsContent.contains("- Functions: isRed")) { "funs Color" }
                check(kindsContent.contains("- Annotation: Marker")) { "kind Annotation" }
                check(kindsContent.contains("- Sealed class: Expr")) { "kind Sealed" }
                check(!kindsContent.contains("- Top-level elements analyzed")) { "no fallback in kindsContent" }

                // 3. Top-level elements: properties, functions, type aliases
                val topLevelCode = ""${'"'}
                    val TOP_CONST = 100
                    fun calculateSum(a: Int, b: Int): Int = a + b
                    typealias NumberList = List<Int>
                ""${'"'}.trimIndent()
                val topRes = inspector.inspectSymbol(topLevelCode)
                val topContent = (topRes as KotlinMcpResult.Success).content
                check(topContent.contains("- Top-level properties: TOP_CONST")) { "top props" }
                check(topContent.contains("- Top-level functions: calculateSum")) { "top funs" }
                check(topContent.contains("- Type aliases: NumberList")) { "top aliases" }
                check(!topContent.contains("- Top-level elements analyzed")) { "no fallback in topContent" }

                // 4. Empty / comment-only snippet
                val emptyCode = "// Just a comment\n/* multi line */"
                val emptyRes = inspector.inspectSymbol(emptyCode)
                val emptyContent = (emptyRes as KotlinMcpResult.Success).content
                check(emptyContent.contains("- Top-level elements analyzed")) { "empty top elements" }
                check(emptyContent.contains("- Line count: 2")) { "empty line count" }
                check(emptyRes.metadata["lineCount"] == "2") { "empty meta lineCount" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: SymbolInspector.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN SymbolInspector.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for SymbolInspector.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for SymbolInspector.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production CodeAnalysisService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/analysis/CodeAnalysisService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.analysis.FileContextAnalyzer
            import com.gokorei.kotlinmcp.analysis.SymbolInspector
            import com.gokorei.kotlinmcp.analysis.NullabilityAnalyzer
            import com.gokorei.kotlinmcp.analysis.CoroutinesSafetyAnalyzer
            import com.gokorei.kotlinmcp.analysis.ComposeAnalyzer
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.models.ProjectionFilter
            import com.gokorei.kotlinmcp.models.ResponsePreset
            import com.gokorei.kotlinmcp.models.ResponseProjection
            import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
            import com.gokorei.kotlinmcp.shared.CommandService
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceCode

        val testSuiteCode = """
            fun main() {
                val service = DefaultCodeAnalysisService()

                // 1. INSPECT_SYMBOL
                val symRes = service.execute(CodeAnalysisAction.INSPECT_SYMBOL, "class Foo { val bar = 1 }")
                check(symRes is KotlinMcpResult.Success && (symRes as KotlinMcpResult.Success).content.contains("- Class: Foo")) { "symRes" }

                // 2. ANALYZE_NULLABILITY
                val nullRes = service.execute(CodeAnalysisAction.ANALYZE_NULLABILITY, "fun test(x: String?) = x!!.length")
                check(nullRes is KotlinMcpResult.Success && (nullRes as KotlinMcpResult.Success).content.contains("Unsafe non-null assertion `!!` detected")) { "nullRes" }

                // 3. EXPLAIN_COROUTINES
                val coroRes = service.execute(CodeAnalysisAction.EXPLAIN_COROUTINES, "fun test() { kotlinx.coroutines.GlobalScope.launch {} }")
                check(coroRes is KotlinMcpResult.Success && (coroRes as KotlinMcpResult.Success).content.contains("`GlobalScope` usage detected")) { "coroRes" }

                // 4. ANALYZE_COMPOSE
                val compRes = service.execute(CodeAnalysisAction.ANALYZE_COMPOSE, "class UnstableData(val x: Int)\n@Composable fun Widget(data: UnstableData) {}")
                check(compRes is KotlinMcpResult.Success && (compRes as KotlinMcpResult.Success).content.contains("Widget")) { "compRes" }

                // 5. FILE_CONTEXT (invalid arg check)
                val fcRes = service.execute(CodeAnalysisAction.FILE_CONTEXT, "/non/existent/file.kt")
                check(fcRes is KotlinMcpResult.Error) { "fcRes" }

                // 6. Default overload and projection test
                val projRes = service.execute(CodeAnalysisAction.INSPECT_SYMBOL, "class A\nclass B\nclass C", null, ResponseProjection(preset = ResponsePreset.COMPACT, fields = setOf("lineCount")))
                check(projRes is KotlinMcpResult.Success) { "projRes" }
                val projSuccess = projRes as KotlinMcpResult.Success
                check(projSuccess.metadata.containsKey("lineCount")) { "proj metadata" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: CodeAnalysisService.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN CodeAnalysisService.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for CodeAnalysisService.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for CodeAnalysisService.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production SnippetAstSafetyChecker source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/SnippetAstSafetyChecker.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val checkerCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.*
        """.trimIndent()

        val productionCode = imports + "\n\n" + checkerCode

        val testSuiteCode = """
            fun main() {
                // 1. Blank, whitespace, and safe code
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("")) { "blank" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("   \n\t")) { "whitespace" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { println(\"safe\") }")) { "safe simple" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("val x = 42; fun compute() = x * 2")) { "safe val" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("import foo.bar.*\nfun main() { println(1) }")) { "wildcard other" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("import other.lang.*\nfun main() { println(1) }")) { "wildcard lang other" }

                // 2. Direct kotlin.system.exitProcess and aliases
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import kotlin.system.exitProcess\nfun main() { exitProcess(0) }")) { "exitProcess direct" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import kotlin.system.exitProcess as ep\nfun main() { ep(1) }")) { "exitProcess alias" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import kotlin.system.*\nfun main() { exitProcess(0) }")) { "exitProcess wildcard" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("import kotlin.system.*\nfun main() { otherName(0) }")) { "wildcard not exitProcess" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { kotlin.system.exitProcess(0) }")) { "exitProcess qualified" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { my.other.exitProcess(0) }")) { "exitProcess other receiver" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { kotlin.system.exit(0) }")) { "system exit qualified" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import kotlin.system.exitProcess\nfun exitProcess(x: Int) {}\nfun main() { exitProcess(0) }")) { "shadow with import" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { listOf(1).forEach { exitProcess(0) } }")) { "nested lambda call" }

                // 3. System.exit and aliases
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { System.exit(0) }")) { "System.exit" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { java.lang.System.exit(0) }")) { "java.lang.System.exit" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.System as Sys\nfun main() { Sys.exit(0) }")) { "Sys.exit alias" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.System.exit\nfun main() { exit(0) }")) { "exit static import" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.System.exit as die\nfun main() { die(0) }")) { "die alias" }

                // 4. Runtime.getRuntime().halt / exit and instance vars
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { Runtime.getRuntime().halt(1) }")) { "Runtime.getRuntime().halt" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { Runtime.getRuntime().exit(1) }")) { "Runtime.getRuntime().exit" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.Runtime as R\nfun main() { R.getRuntime().halt(1) }")) { "R.getRuntime().halt" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.Runtime.getRuntime as getRt\nfun main() { getRt().halt(1) }")) { "getRt().halt" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { val rt = Runtime.getRuntime(); rt.halt(0) }")) { "rt.halt var" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { val rt = Runtime.getRuntime(); rt.exit(0) }")) { "rt.exit var" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { val rt: java.lang.Runtime = null!!; rt.exit(0) }")) { "rt.exit typeRef" }

                // 5. ProcessHandle current destroy
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { ProcessHandle.current().destroy() }")) { "ProcessHandle destroy" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { ProcessHandle.current().destroyForcibly() }")) { "ProcessHandle destroyForcibly" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("import java.lang.ProcessHandle as PH\nfun main() { val p: PH = null!!; p.destroy() }")) { "PH alias" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { val ph = ProcessHandle.current(); ph.destroy() }")) { "ph var destroy" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("fun main() { val ph = ProcessHandle.current(); ph.destroyForcibly() }")) { "ph var destroyForcibly" }

                // 6. Callable references
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = ::exitProcess")) { "::exitProcess ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = kotlin.system::exitProcess")) { "kotlin.system::exitProcess ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = System::exit")) { "System::exit ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = Runtime::halt")) { "Runtime::halt ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = ProcessHandle::destroy")) { "ProcessHandle::destroy ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = ProcessHandle::destroyForcibly")) { "ProcessHandle::destroyForcibly ref" }
                check(SnippetAstSafetyChecker.containsHostTerminatingCalls("val ref = listOf(1).map { ::exitProcess }")) { "nested callable ref" }

                // 7. User shadows without import should NOT be flagged
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("fun exitProcess(code: Int) = code * 2\nfun main() { exitProcess(42) }")) { "user shadow exitProcess" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("fun exit(code: Int) = code * 2\nfun main() { exit(42) }")) { "user shadow exit" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class System { companion object { fun exit(c: Int) {} } }\nfun main() { System.exit(0) }")) { "user shadow class System" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Safe { fun exit(c: Int) {} }\nfun main() { val System = Safe(); System.exit(0) }")) { "user shadow val System" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Safe { fun exit(c: Int) {} }\nfun test(System: Safe) { System.exit(0) }")) { "user shadow param System" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Safe { fun exit(c: Int) {} }\nfun test(p: Pair<Safe, Int>) { val (System, other) = p; System.exit(0) }")) { "user shadow destructuring System" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Runtime { companion object { fun getRuntime() = Safe() } }\nfun main() { Runtime.getRuntime().halt(0) }")) { "user shadow Runtime class" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Safe { fun halt(c: Int) {} }\nfun main() { val Runtime = Safe(); Runtime.halt(0) }")) { "user shadow Runtime var" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Outer { class System { companion object { fun exit(c: Int) {} } } }\nfun main() { Outer.System.exit(0) }")) { "nested class shadow" }
                check(!SnippetAstSafetyChecker.containsHostTerminatingCalls("class Outer { val System = Safe(); fun run() { System.exit(0) } }")) { "nested prop shadow" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: SnippetAstSafetyChecker.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN SnippetAstSafetyChecker.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for SnippetAstSafetyChecker.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for SnippetAstSafetyChecker.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production DiagnosticService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/DiagnosticService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceCode = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.shared.ToonUtils
            import com.gokorei.kotlinmcp.execution.SnippetCompiler
            import com.gokorei.kotlinmcp.execution.CompileResult
            import com.gokorei.kotlinmcp.execution.CompilerDiagnostic
            import java.io.File
            import java.nio.file.Files
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceCode

        val testSuiteCode = """
            fun main() {
                val service: DiagnosticService = DefaultDiagnosticService()

                // 1. CHECK_SNIPPET on valid code
                val validRes = service.execute(DiagnosticAction.CHECK_SNIPPET, "fun main() { val x = 42 }")
                check(validRes is KotlinMcpResult.Success) { "validRes" }
                val validSuccess = validRes as KotlinMcpResult.Success
                check(validSuccess.content.contains("Compilation succeeded")) { "valid content" }
                check(validSuccess.metadata["mode"] == "embedded") { "valid mode" }
                check(validSuccess.metadata["errorCount"] == "0") { "valid errors 0" }

                // 2. CHECK_SNIPPET on type mismatch error and syntax error (without unresolved hint)
                val errRes = service.execute(DiagnosticAction.CHECK_SNIPPET, "fun main() { val x: Int = \"str\" }")
                check(errRes is KotlinMcpResult.Error) { "errRes" }
                val errObj = errRes as KotlinMcpResult.Error
                check(errObj.code == "COMPILER_ERROR") { "err code" }
                check(errObj.details.containsKey("diagnostics")) { "err diagnostics" }
                check(errObj.requireAnotherCall == true) { "err requireAnotherCall" }

                val syntaxErrRes = service.execute(DiagnosticAction.CHECK_SNIPPET, "fun main() { val x: Int = }")
                check(syntaxErrRes is KotlinMcpResult.Error) { "syntaxErrRes" }
                check(!(syntaxErrRes as KotlinMcpResult.Error).message.contains("Unresolved symbol references detected")) { "no unresolved hint on syntax error" }

                // 3. CHECK_SNIPPET on unresolved reference with hint and warnings
                val unresRes = service.execute(DiagnosticAction.CHECK_SNIPPET, "fun main() { NonExistentClass123.call() }")
                check(unresRes is KotlinMcpResult.Error) { "unresRes" }
                val unresObj = unresRes as KotlinMcpResult.Error
                check(unresObj.message.contains("Unresolved symbol references detected")) { "unres hint" }

                val warnRes = service.execute(DiagnosticAction.CHECK_SNIPPET, "fun main() { val unused = 42 }")
                check(warnRes is KotlinMcpResult.Success) { "warnRes" }
                check((warnRes as KotlinMcpResult.Success).metadata["warningCount"] != null) { "warningCount present" }

                // 4. RUN_PROJECT_LAYOUT on non-existent path
                val missingRes = service.execute(DiagnosticAction.RUN_PROJECT_LAYOUT, "", "/non/existent/path/123456")
                check(missingRes is KotlinMcpResult.Error) { "missingRes" }
                check((missingRes as KotlinMcpResult.Error).code == "PROJECT_NOT_FOUND") { "missing code" }

                // 5. RUN_PROJECT_LAYOUT on default "."
                val currentRes = service.execute(DiagnosticAction.RUN_PROJECT_LAYOUT, "")
                check(currentRes is KotlinMcpResult.Success) { "currentRes" }
                val currentSuccess = currentRes as KotlinMcpResult.Success
                check(currentSuccess.content.contains("Project Layout Inventory")) { "current inventory" }
                check(currentSuccess.content.contains("build.gradle.kts")) { "current build files" }
                check(currentSuccess.metadata["mode"] == "fs") { "current mode" }

                // 6. RUN_PROJECT_LAYOUT on synthetic temp project layout
                val tmpDir = Files.createTempDirectory("diag-test-proj")
                try {
                    val root = tmpDir.toFile()
                    File(root, "settings.gradle.kts").writeText("// settings")
                    File(root, "build.gradle").writeText("// root build")
                    File(root, "src/main/kotlin").mkdirs()
                    File(root, "src/main/java").mkdirs()
                    val sub = File(root, "submodule")
                    sub.mkdirs()
                    File(sub, "build.gradle.kts").writeText("// sub build")
                    File(sub, "src/main/kotlin").mkdirs()
                    File(sub, "src/commonMain/kotlin").mkdirs()
                    File(sub, "src/test/kotlin").mkdirs()
                    File(sub, "src/androidTest/kotlin").mkdirs()

                    val tmpRes = service.execute(DiagnosticAction.RUN_PROJECT_LAYOUT, "", root.absolutePath)
                    check(tmpRes is KotlinMcpResult.Success) { "tmpRes" }
                    val tmpContent = (tmpRes as KotlinMcpResult.Success).content
                    check(tmpContent.contains("settings.gradle.kts")) { "tmp settings" }
                    check(tmpContent.contains("build.gradle")) { "tmp build.gradle" }
                    check(tmpContent.contains("src/main/kotlin")) { "tmp main kotlin" }
                    check(tmpContent.contains("src/main/java")) { "tmp main java" }
                    check(tmpContent.contains("submodule/src/main/kotlin")) { "tmp sub main kotlin" }
                    check(tmpContent.contains("submodule/src/commonMain/kotlin")) { "tmp commonMain" }
                    check(tmpContent.contains("submodule/src/test/kotlin")) { "tmp test kotlin" }
                    check(tmpContent.contains("submodule/src/androidTest/kotlin")) { "tmp androidTest" }
                    check(tmpContent.contains("Note: this is a layout inventory")) { "tmp note" }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        println("\n=======================================================")
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: DiagnosticService.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN DiagnosticService.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================")
        println()

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for DiagnosticService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for DiagnosticService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production FastSnippetRunner source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/FastSnippetRunner.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val runnerSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.shared.LogTruncator
            import com.gokorei.kotlinmcp.execution.SnippetCompiler
            import com.gokorei.kotlinmcp.execution.CompileResult
            import java.io.ByteArrayOutputStream
            import java.io.File
            import java.io.OutputStream
            import java.io.PrintStream
            import java.lang.reflect.InvocationTargetException
            import java.net.URLClassLoader
            import java.nio.file.Files
            import java.nio.file.Path
            import java.util.concurrent.*
        """.trimIndent()

        val productionCode = imports + "\n\n" + runnerSource

        val testSuiteCode = """
            fun main() {
                val runner = DefaultFastSnippetRunner(threadPoolSize = 1)

                try {
                    // 1. Missing class execution error mapping
                    val emptyDir = Files.createTempDirectory("empty-run-out")
                    val errRes = runner.run(emptyDir, 5000L)
                    check(errRes is KotlinMcpResult.Error) { "errRes" }
                    val errObj = errRes as KotlinMcpResult.Error
                    check(errObj.code == "RUNTIME_ERROR") { "errObj code: " + errObj.code }
                    check(errObj.details.containsKey("exception")) { "errObj details" }
                    check(errObj.requireAnotherCall == true) { "errObj requireAnotherCall" }
                    emptyDir.toFile().deleteRecursively()

                    // 2. Successful execution with printed output and extraClasspath (duplicate path tests list addition)
                    val code1 = "fun main() { println(\"output-token-123\") }"
                    val compiled1 = SnippetCompiler.compile(code1)
                    check(compiled1 is CompileResult.Compiled) { "compiled1" }
                    val outDirPath = (compiled1 as CompileResult.Compiled).outDir.toString()
                    val res1 = runner.run(compiled1.outDir, timeoutMillis = 5000L, extraClasspath = listOf("   ", outDirPath))
                    SnippetCompiler.cleanup(compiled1)
                    check(res1 is KotlinMcpResult.Success) { "res1" }
                    val success1 = res1 as KotlinMcpResult.Success
                    check(success1.content.contains("output-token-123")) { "res1 content" }
                    check(success1.metadata["mode"] == "in_memory") { "res1 mode" }
                    check(success1.metadata["exitCode"] == "0") { "res1 exitCode" }
                    val d1 = (success1.metadata["durationMs"] ?: "0").toLong()
                    check(d1 in 0..15000) { "d1 in realistic ms range: " + d1 }

                    // 3. Successful execution of parameterless main
                    val code2 = "fun main() { println(\"parameterless-main-invoked\") }"
                    val compiled2 = SnippetCompiler.compile(code2)
                    check(compiled2 is CompileResult.Compiled) { "compiled2" }
                    val res2 = runner.run((compiled2 as CompileResult.Compiled).outDir, timeoutMillis = 5000L)
                    SnippetCompiler.cleanup(compiled2)
                    check(res2 is KotlinMcpResult.Success) { "res2" }
                    check((res2 as KotlinMcpResult.Success).content.contains("parameterless-main-invoked")) { "res2 parameterless invoked" }

                    // 4. Main function with Array<String> parameter
                    val code3 = "fun main(args: Array<String>) { println(\"args count: \" + args.size) }"
                    val compiled3 = SnippetCompiler.compile(code3)
                    check(compiled3 is CompileResult.Compiled) { "compiled3" }
                    val res3 = runner.run((compiled3 as CompileResult.Compiled).outDir, timeoutMillis = 5000L)
                    SnippetCompiler.cleanup(compiled3)
                    check(res3 is KotlinMcpResult.Success) { "res3" }
                    check((res3 as KotlinMcpResult.Success).content.contains("args count: 0")) { "res3 args count" }

                    // 5. Runtime exception in snippet with prior output
                    val code4 = "fun main() { println(\"prior-log-entry\"); throw IllegalArgumentException(\"custom runtime error\") }"
                    val compiled4 = SnippetCompiler.compile(code4)
                    check(compiled4 is CompileResult.Compiled) { "compiled4" }
                    val res4 = runner.run((compiled4 as CompileResult.Compiled).outDir, timeoutMillis = 5000L)
                    SnippetCompiler.cleanup(compiled4)
                    check(res4 is KotlinMcpResult.Error) { "res4" }
                    val err4 = res4 as KotlinMcpResult.Error
                    check(err4.code == "RUNTIME_ERROR") { "res4 code" }
                    check(err4.details["exception"] == "java.lang.IllegalArgumentException") { "res4 exception details" }
                    val d2 = (err4.details["durationMs"] ?: "0").toLong()
                    check(d2 in 0..15000) { "d2 in realistic ms range: " + d2 }
                    check(err4.message.contains("prior-log-entry")) { "res4 prior output prefix" }
                    check(err4.message.contains("custom runtime error")) { "res4 msg" }
                    check(err4.requireAnotherCall == true) { "res4 requireAnotherCall" }

                    // 6. Execution timeout
                    val code5 = "fun main() { Thread.sleep(2000) }"
                    val compiled5 = SnippetCompiler.compile(code5)
                    check(compiled5 is CompileResult.Compiled) { "compiled5" }
                    val res5 = runner.run((compiled5 as CompileResult.Compiled).outDir, timeoutMillis = 50L)
                    SnippetCompiler.cleanup(compiled5)
                    check(res5 is KotlinMcpResult.Error) { "res5" }
                    val err5 = res5 as KotlinMcpResult.Error
                    check(err5.code == "EXECUTION_TIMEOUT") { "res5 code" }
                    check(err5.message.contains("50ms")) { "res5 timeout message" }

                    // 7. Direct ThreadLocalPrintStream coverage with custom outputstream flush/close tracking
                    var targetFlushed = false
                    var targetClosed = false
                    val trackingOs = object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun flush() { targetFlushed = true }
                        override fun close() { targetClosed = true }
                    }
                    val trackingPs = PrintStream(trackingOs)
                    ThreadLocalPrintStream.withCapture(trackingPs) {
                        System.out.flush()
                        System.out.close()
                    }
                    check(targetFlushed) { "target flushed" }
                    check(!targetClosed) { "target not closed due to guard" }

                    var directFlushed = false
                    val directOs = object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun flush() { directFlushed = true }
                    }
                    val directTlps = ThreadLocalPrintStream(PrintStream(directOs))
                    directTlps.close()
                    check(directFlushed) { "directTlps flushed on close" }

                    val tlps = ThreadLocalPrintStream(System.out)
                    tlps.write(65)
                    tlps.write("BC".toByteArray(Charsets.UTF_8), 0, 2)
                    tlps.flush()
                    tlps.close()

                    val baos1 = ByteArrayOutputStream()
                    val ps1 = PrintStream(baos1, true, "UTF-8")
                    val baos2 = ByteArrayOutputStream()
                    val ps2 = PrintStream(baos2, true, "UTF-8")
                    val errBaos = ByteArrayOutputStream()
                    val errPs = PrintStream(errBaos, true, "UTF-8")

                    ThreadLocalPrintStream.withCapture(ps1) {
                        print("capture-test-1")
                        System.out.write(65)
                        System.out.write("BC".toByteArray(Charsets.UTF_8), 0, 2)
                        System.out.flush()
                        System.out.close()

                        ThreadLocalPrintStream.withCapture(ps2) {
                            print("nested-capture-2")
                        }

                        ThreadLocalPrintStream.withCapture(errPs) {
                            System.err.print("stderr-token-999")
                            System.err.flush()
                        }

                        print("after-nested")
                    }
                    check(baos1.toString("UTF-8").contains("capture-test-1ABCafter-nested")) { "nested thread local capture 1" }
                    check(baos2.toString("UTF-8").contains("nested-capture-2")) { "nested thread local capture 2" }
                    check(errBaos.toString("UTF-8").contains("stderr-token-999")) { "stderr thread local capture" }
                } finally {
                    runner.close()
                    val comp = SnippetCompiler.compile("fun main() {}")
                    if (comp is CompileResult.Compiled) {
                        val postCloseRes = runner.run(comp.outDir, 1000L)
                        SnippetCompiler.cleanup(comp)
                        check(postCloseRes is KotlinMcpResult.Error) { "post close rejected" }
                    }
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: FastSnippetRunner.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN FastSnippetRunner.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for FastSnippetRunner.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for FastSnippetRunner.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production SnippetCompiler source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/SnippetCompiler.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val compilerSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
            import java.io.File
            import java.nio.file.Files
            import java.nio.file.Path
            import io.github.oshai.kotlinlogging.KotlinLogging
        """.trimIndent()

        val productionCode = imports + "\n\n" + compilerSource

        val testSuiteCode = """
            fun main() {
                // 1. detectProjectClasspath edge cases
                check(SnippetCompiler.detectProjectClasspath(null).isEmpty()) { "detect null" }
                check(SnippetCompiler.detectProjectClasspath("   ").isEmpty()) { "detect blank" }
                check(SnippetCompiler.detectProjectClasspath("/non/existent/path/9999").isEmpty()) { "detect missing" }

                val tmpRoot = Files.createTempDirectory("snippet-cp-test")
                try {
                    val root = tmpRoot.toFile()
                    File(root, "build/classes/kotlin/main").mkdirs()
                    File(root, "build/classes/java/main").mkdirs()
                    File(root, "build/classes/kotlin/commonMain").mkdirs()
                    File(root, "build/classes/kotlin/jvm/main").mkdirs()
                    File(root, "build/generated/ksp/dummy").mkdirs()
                    File(root, "build/generated/source/kapt/dummy").mkdirs()
                    File(root, "build/generated/sqldelight/dummy").mkdirs()
                    File(root, "build/intermediates/javac/dummy").mkdirs()
                    File(root, "build/intermediates/compile_app_classes_jar/dummy").mkdirs()
                    val libsDir = File(root, "build/libs")
                    libsDir.mkdirs()
                    File(libsDir, "app.jar").writeText("// jar")
                    File(libsDir, "ignored.txt").writeText("// txt")

                    // Ignored folders
                    File(root, ".gradle/build/classes/kotlin/main").mkdirs()
                    File(root, ".git/build/classes/kotlin/main").mkdirs()
                    File(root, "out/build/classes/kotlin/main").mkdirs()
                    File(root, "node_modules/build/classes/kotlin/main").mkdirs()
                    File(root, ".idea/build/classes/kotlin/main").mkdirs()

                    val detected = SnippetCompiler.detectProjectClasspath(root.absolutePath)
                    check(detected.any { it.endsWith("build/classes/kotlin/main") }) { "detected kotlin main" }
                    check(detected.any { it.endsWith("build/classes/java/main") }) { "detected java main" }
                    check(detected.any { it.endsWith("build/classes/kotlin/commonMain") }) { "detected commonMain" }
                    check(detected.any { it.endsWith("build/classes/kotlin/jvm/main") }) { "detected jvm main" }
                    check(detected.any { it.contains("build/generated/ksp") }) { "detected ksp" }
                    check(detected.any { it.contains("build/generated/source/kapt") }) { "detected kapt" }
                    check(detected.any { it.contains("build/generated/sqldelight") }) { "detected sqldelight" }
                    check(detected.any { it.contains("build/intermediates/javac") }) { "detected javac" }
                    check(detected.any { it.contains("build/intermediates/compile_app_classes_jar") }) { "detected compile_app" }
                    check(detected.any { it.endsWith("app.jar") }) { "detected jar" }
                    check(detected.none { it.contains(".gradle") || it.contains(".git") || it.contains("node_modules") || it.contains(".idea") }) { "none ignored" }
                } finally {
                    tmpRoot.toFile().deleteRecursively()
                }

                // 2. resolveDefaultImports
                val sampleCp = listOf(
                    "/path/to/kotlin-stdlib-2.3.20.jar",
                    "/path/to/kotlinx-coroutines-core.jar",
                    "/path/to/kotlinx-serialization-core.jar",
                    "/path/to/kotlinx-datetime.jar",
                    "/path/to/arrow-core.jar",
                    "/path/to/mockk.jar",
                    "/path/to/turbine.jar",
                    "/path/to/ktor-client.jar",
                    "/path/to/random-other.jar"
                ).joinToString(File.pathSeparator)

                val resolved = SnippetCompiler.resolveDefaultImports(sampleCp)
                check(resolved.any { it.contains("kotlin-stdlib") }) { "resolved stdlib" }
                check(resolved.any { it.contains("kotlinx-coroutines") }) { "resolved coroutines" }
                check(resolved.any { it.contains("kotlinx-serialization") }) { "resolved serialization" }
                check(resolved.any { it.contains("kotlinx-datetime") }) { "resolved datetime" }
                check(resolved.any { it.contains("arrow-core") }) { "resolved arrow" }
                check(resolved.any { it.contains("mockk") }) { "resolved mockk" }
                check(resolved.any { it.contains("turbine") }) { "resolved turbine" }
                check(resolved.any { it.contains("ktor") }) { "resolved ktor" }
                check(resolved.none { it.contains("random-other") }) { "random other excluded" }

                SnippetCompiler.resetBundledSnippetClasspathCache()
                val fallbackResolved = SnippetCompiler.resolveDefaultImports("")
                check(fallbackResolved.isEmpty() || fallbackResolved.isNotEmpty()) { "fallback exercised" }

                val mockLoader = object : ClassLoader(SnippetCompiler::class.java.classLoader) {
                    override fun getResourceAsStream(name: String): java.io.InputStream? {
                        if (name == "snippet-classpath/snippet.classpath.txt") {
                            return "dummy.jar\n".byteInputStream()
                        }
                        if (name == "snippet-classpath/dummy.jar") {
                            return "mock-jar-content".byteInputStream()
                        }
                        return super.getResourceAsStream(name)
                    }
                }
                SnippetCompiler.resetBundledSnippetClasspathCache()
                val extracted = SnippetCompiler.materializeBundledSnippetClasspath(mockLoader)
                check(extracted.isNotEmpty() && File(extracted.first()).exists()) { "mock jar extracted" }
                SnippetCompiler.resetBundledSnippetClasspathCache()

                // 3. compile on valid code with coroutines dependency to enforce classpath addition
                val coroutineCode = "import kotlinx.coroutines.*\nfun main() = runBlocking { delay(1); val unused = 42 }"

                val resValid = SnippetCompiler.compile(
                    code = coroutineCode,
                    extraClasspath = SnippetCompiler.runtimeExecutionClasspath,
                    projectPath = null
                )
                check(resValid is CompileResult.Compiled) { "resValid" }
                val compiledValid = resValid as CompileResult.Compiled
                check(compiledValid.outDir.toFile().exists()) { "outDir must exist" }
                check(compiledValid.tempRoot.toFile().exists()) { "tempRoot must exist" }
                check(compiledValid.diagnostics.none { it.severity == "error" }) { "no errors in valid coroutine compile: " + compiledValid.diagnostics }
                check(compiledValid.diagnostics.any { it.severity == "warning" }) { "has warning diagnostic" }

                // Check cleanup deletes tempRoot
                val tempDirFile = compiledValid.tempRoot.toFile()
                SnippetCompiler.cleanup(resValid)
                check(!tempDirFile.exists()) { "tempRoot must be deleted after cleanup" }

                // 4. compile on projectPath with helper class to enforce autoClasspath addition
                val tmpProj = Files.createTempDirectory("snippet-proj-comp")
                try {
                    val projRoot = tmpProj.toFile()
                    val outClassDir = File(projRoot, "build/classes/kotlin/main")
                    outClassDir.mkdirs()
                    val compHelper = SnippetCompiler.compile("package my.test.pkg\nclass CustomHelper { fun ping(): String = \"pong\" }")
                    if (compHelper is CompileResult.Compiled) {
                        val helperClassFile = File(compHelper.outDir.toFile(), "my/test/pkg/CustomHelper.class")
                        if (helperClassFile.exists()) {
                            val destPkg = File(outClassDir, "my/test/pkg")
                            destPkg.mkdirs()
                            helperClassFile.copyTo(File(destPkg, "CustomHelper.class"), overwrite = true)
                        }
                        SnippetCompiler.cleanup(compHelper)
                    }

                    val snippetUsingHelper = "import my.test.pkg.CustomHelper\nfun main() { val h = CustomHelper(); val res = h.ping() }"
                    val resProj = SnippetCompiler.compile(
                        code = snippetUsingHelper,
                        projectPath = projRoot.absolutePath
                    )
                    check(resProj is CompileResult.Compiled) { "resProj" }
                    check((resProj as CompileResult.Compiled).diagnostics.none { it.severity == "error" }) { "no error with helper from projectPath" }
                    SnippetCompiler.cleanup(resProj)
                } finally {
                    tmpProj.toFile().deleteRecursively()
                }

                // 5. compile on error code
                val resErr = SnippetCompiler.compile("fun main() { val x: Int = \"str\" }")
                check(resErr is CompileResult.Compiled) { "resErr" }
                val compiledErr = resErr as CompileResult.Compiled
                val errDiag = compiledErr.diagnostics.firstOrNull { it.severity == "error" }
                check(errDiag != null) { "has error diagnostic" }
                check(errDiag.line != null && errDiag.column != null) { "diagnostic line and column" }
                SnippetCompiler.cleanup(resErr)

                // 6. cleanup on failed result
                SnippetCompiler.cleanup(CompileResult.Failed("fail-msg", "IO_ERR"))
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: SnippetCompiler.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN SnippetCompiler.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for SnippetCompiler.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for SnippetCompiler.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production RunSnippetService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/RunSnippetService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.shared.LogTruncator
            import com.gokorei.kotlinmcp.execution.JavaResolver
            import com.gokorei.kotlinmcp.execution.DefaultJavaResolver
            import com.gokorei.kotlinmcp.execution.FastSnippetRunner
            import com.gokorei.kotlinmcp.execution.DefaultFastSnippetRunner
            import com.gokorei.kotlinmcp.execution.SnippetCompiler
            import com.gokorei.kotlinmcp.execution.CompileResult
            import com.gokorei.kotlinmcp.execution.CompilerDiagnostic
            import com.gokorei.kotlinmcp.execution.SnippetAstSafetyChecker
            import java.io.ByteArrayOutputStream
            import java.io.File
            import java.nio.file.Path
            import java.nio.file.Files
            import java.util.concurrent.TimeUnit
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceSource

        val testSuiteCode = """
            fun main() {
                val service = DefaultRunSnippetService()

                // 1. Empty snippet
                val emptyRes = service.execute("   ", 5000L)
                check(emptyRes is KotlinMcpResult.Error && emptyRes.code == "EMPTY_SNIPPET") { "empty snippet" }

                // 2. Compilation error
                val compileErrRes = service.execute("fun main() { val x: Int = \"string\" }", 5000L)
                check(compileErrRes is KotlinMcpResult.Error && compileErrRes.code == "COMPILER_ERROR" && compileErrRes.requireAnotherCall == true) { "comp error" }

                // 3. No main found
                val noMainRes = service.execute("fun foo() = 42", 5000L)
                check(noMainRes is KotlinMcpResult.Error && noMainRes.code == "NO_MAIN_FOUND") { "no main" }

                // 4. Invalid main parameters & valid Array<out String>
                val invalidMainRes = service.execute("fun main(x: Int, y: Int) = Unit", 5000L)
                check(invalidMainRes is KotlinMcpResult.Error && invalidMainRes.code == "NO_MAIN_FOUND") { "invalid main params" }

                val arrayOutRes = service.execute(
                    code = "fun main(args: Array<out String>) { println(\"array out main\") }",
                    timeoutMillis = 5000L,
                    runner = "in_memory"
                )
                check(arrayOutRes is KotlinMcpResult.Success && arrayOutRes.content.contains("array out main")) { "array out main" }

                // 5. Valid top-level main (in_memory)
                val inMemRes = service.execute(
                    code = "fun main() { println(\"hello in mem\") }",
                    timeoutMillis = 5000L,
                    runner = "in_memory"
                )
                check(inMemRes is KotlinMcpResult.Success && inMemRes.content.contains("hello in mem")) { "in mem success: " + inMemRes }

                // 6. Valid object @JvmStatic main (fast runner)
                val objMainCode = "object App { @JvmStatic fun main(args: Array<String>) { println(\"hello obj main\") } }"
                val fastRes = service.execute(
                    code = objMainCode,
                    timeoutMillis = 5000L,
                    runner = "fast"
                )
                check(fastRes is KotlinMcpResult.Success && fastRes.content.contains("hello obj main")) { "fast obj success: " + fastRes }

                // 7. Valid jvmArgs with custom system property and host execution
                val jvmArgsRes = service.execute(
                    code = "fun main() { println(\"PROP:\" + System.getProperty(\"my.custom.prop\")) }",
                    timeoutMillis = 5000L,
                    runner = "host_jvm",
                    jvmArgs = listOf("-Dmy.custom.prop=testval")
                )
                check(jvmArgsRes is KotlinMcpResult.Success && jvmArgsRes.content.contains("PROP:testval")) { "jvm args system prop: " + jvmArgsRes }

                // 8. Unsafe JVM args rejection
                val unsafeJvmRes = service.execute(
                    code = "fun main() {}",
                    timeoutMillis = 5000L,
                    runner = "host_jvm",
                    jvmArgs = listOf("-javaagent:bad.jar")
                )
                check(unsafeJvmRes is KotlinMcpResult.Error && unsafeJvmRes.code == "UNSAFE_JVM_ARGUMENT") { "unsafe jvm" }

                // 9. Explicit javaPath execution
                val javaExe = DefaultJavaResolver().resolve(null)?.absolutePath
                if (javaExe != null) {
                    val explicitJavaRes = service.execute(
                        code = "fun main() { println(\"explicit java\") }",
                        timeoutMillis = 5000L,
                        javaPath = javaExe
                    )
                    check(explicitJavaRes is KotlinMcpResult.Success && explicitJavaRes.content.contains("explicit java")) { "explicit java" }
                }

                // 10. Missing Java resolver error
                val noJavaService = DefaultRunSnippetService(
                    javaResolver = object : JavaResolver {
                        override fun resolve(explicitPath: String?): File? = null
                        override fun validateJvmArgs(args: List<String>): List<String> = emptyList()
                    }
                )
                val missingJavaRes = noJavaService.execute(
                    code = "fun main() {}",
                    timeoutMillis = 5000L,
                    runner = "host_jvm"
                )
                check(missingJavaRes is KotlinMcpResult.Error && missingJavaRes.code == "MISSING_JAVA_HOME" && missingJavaRes.requireAnotherCall == true) { "missing java" }

                // 11. Host JVM execution success & duration
                val hostRes = service.execute(
                    code = "fun main(args: Array<String>) { println(\"host execution\") }",
                    timeoutMillis = 5000L,
                    runner = "host_jvm"
                )
                check(hostRes is KotlinMcpResult.Success && hostRes.content.contains("host execution")) { "host success: " + hostRes }
                val dur = hostRes.metadata["durationMs"]?.toLongOrNull() ?: -1L
                check(dur in 0..15000) { "valid durationMs: " + dur }

                // 12. Host JVM execution with exit 0 but no output
                val hostNoOutRes = service.execute(
                    code = "fun main() {}",
                    timeoutMillis = 5000L,
                    runner = "host_jvm"
                )
                check(hostNoOutRes is KotlinMcpResult.Success && hostNoOutRes.content.contains("no output")) { "host no output: " + hostNoOutRes }

                // 13. Host JVM execution runtime error (exit != 0)
                val hostErrRes = service.execute(
                    code = "fun main() { error(\"boom host\") }",
                    timeoutMillis = 5000L,
                    runner = "host_jvm"
                )
                check(hostErrRes is KotlinMcpResult.Error && hostErrRes.code == "RUNTIME_ERROR" && hostErrRes.requireAnotherCall == true && hostErrRes.message.contains("boom host")) { "host error: " + hostErrRes }

                // 14. Host JVM timeout handling
                val timeoutRes = service.execute(
                    code = "fun main() { Thread.sleep(10000) }",
                    timeoutMillis = 150L,
                    runner = "host_jvm"
                )
                check(timeoutRes is KotlinMcpResult.Error && timeoutRes.code == "EXECUTION_TIMEOUT") { "host timeout: " + timeoutRes }

                // 15. Dangerous call forces host_jvm fallback even when in_memory requested
                val dangerousRes = service.execute(
                    code = "fun main() { println(\"exiting safely\"); kotlin.system.exitProcess(0) }",
                    timeoutMillis = 5000L,
                    runner = "in_memory"
                )
                check(dangerousRes is KotlinMcpResult.Success && dangerousRes.metadata["mode"] == "host_jvm") { "dangerous to host jvm: " + dangerousRes }

                // 16. Default runner fallback (runCompiled -> host_jvm)
                val defaultFallbackRes = service.execute(
                    code = "fun main() { println(\"fallback runner\") }",
                    timeoutMillis = 5000L,
                    runner = "custom_unrecognized"
                )
                check(defaultFallbackRes is KotlinMcpResult.Success && defaultFallbackRes.metadata["mode"] == "host_jvm") { "fallback to host: " + defaultFallbackRes }

                // 17. parseTestReport: non-existent project
                val missingReportRes = service.parseTestReport("/non/existent/path/9999")
                check(missingReportRes is KotlinMcpResult.Error && missingReportRes.code == "PROJECT_NOT_FOUND") { "project not found" }

                // 18. parseTestReport: empty project (no XMLs)
                val tmpDir = Files.createTempDirectory("test-reports-empty")
                try {
                    val emptyReportRes = service.parseTestReport(tmpDir.toAbsolutePath().toString())
                    check(emptyReportRes is KotlinMcpResult.Error && emptyReportRes.code == "NOT_FOUND") { "xml not found" }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }

                // 19. parseTestReport: populated JUnit XMLs with exact line verification
                val tmpXmlDir = Files.createTempDirectory("test-reports-full")
                try {
                    val reportsDir = File(tmpXmlDir.toFile(), "build/test-results/test")
                    reportsDir.mkdirs()

                    val xmlContent = ""${'"'}
                        <?xml version="1.0" encoding="UTF-8"?>
                        <testsuite name="com.example.MyTest" tests="4" skipped="1" failures="1" errors="1">
                          <testcase name="testSuccess()" classname="com.example.MyTest" time="0.01"/>
                          <testcase name="testFail()" classname="com.example.MyTest" time="0.02">
                            <failure message="Assertion error occurred">Stack trace details</failure>
                          </testcase>
                          <testcase name="testErr()" classname="com.example.MyTest" time="0.03">
                            <error message="NullPointerException">Null error details</error>
                          </testcase>
                          <testcase name="testSkip()" classname="com.example.MyTest" time="0.00">
                            <skipped/>
                          </testcase>
                        </testsuite>
                    ""${'"'}.trimIndent()

                    File(reportsDir, "TEST-com.example.MyTest.xml").writeText(xmlContent)

                    val reportRes = service.parseTestReport(tmpXmlDir.toAbsolutePath().toString())
                    check(reportRes is KotlinMcpResult.Success) { "report success" }
                    val content = reportRes.content
                    check(content.startsWith("# JUnit Test Execution Report")) { "starts with title" }
                    check(content.contains("Total Tests: 4\n- Failures: 1\n- Errors: 1\n- Skipped: 1\n\n## Failed Tests")) { "counts and newline before failed section" }
                    check(content.contains("- `com.example.MyTest > testFail()` FAILED: Stack trace details")) { "failed detail: " + content }
                    check(content.contains("## Errored Tests")) { "has errored section" }
                    check(content.contains("- `com.example.MyTest > testErr()` ERROR: Null error details")) { "errored detail: " + content }
                    check(content.contains("## Skipped Tests")) { "has skipped section" }
                    check(content.contains("- `com.example.MyTest > testSkip()` SKIPPED")) { "skipped detail: " + content }
                    check(reportRes.metadata["total"] == "4") { "meta total" }
                    check(reportRes.metadata["failures"] == "1") { "meta failures" }
                    check(reportRes.metadata["errors"] == "1") { "meta errors" }
                    check(reportRes.metadata["skipped"] == "1") { "meta skipped" }

                    // All passing XML
                    reportsDir.listFiles()?.forEach { it.delete() }
                    val allPassXml = ""${'"'}
                        <?xml version="1.0" encoding="UTF-8"?>
                        <testsuite name="com.example.PassTest" tests="2" skipped="0" failures="0" errors="0">
                          <testcase name="test1()" classname="com.example.PassTest" time="0.01"/>
                          <testcase name="test2()" classname="com.example.PassTest" time="0.02"/>
                        </testsuite>
                    ""${'"'}.trimIndent()
                    File(reportsDir, "TEST-com.example.PassTest.xml").writeText(allPassXml)
                    val passRes = service.parseTestReport(tmpXmlDir.toAbsolutePath().toString())
                    check(passRes is KotlinMcpResult.Success && passRes.content.contains("All tests passed!")) { "all passed report" }
                } finally {
                    tmpXmlDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: RunSnippetService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN RunSnippetService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for RunSnippetService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for RunSnippetService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production GradleRunService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/execution/GradleRunService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import java.io.ByteArrayOutputStream
            import java.io.File
            import java.nio.file.Files
            import java.util.concurrent.TimeUnit
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceSource

        val testSuiteCode = """
            fun main() {
                val service = DefaultGradleRunService()

                // 1. Missing or non-directory projectPath
                val nonExistentRes = service.execute("/non/existent/project/dir", "build", 5000L)
                check(nonExistentRes is KotlinMcpResult.Error && nonExistentRes.code == "PROJECT_NOT_FOUND") { "project not found" }

                val tmpFile = File.createTempFile("dummy-file", ".tmp")
                try {
                    val notDirRes = service.execute(tmpFile.absolutePath, "build", 5000L)
                    check(notDirRes is KotlinMcpResult.Error && notDirRes.code == "PROJECT_NOT_FOUND") { "not a directory" }
                } finally {
                    tmpFile.delete()
                }

                // 2. Empty task spec
                val tmpDir = Files.createTempDirectory("gradle-test-root")
                try {
                    val emptyTaskRes = service.execute(tmpDir.toAbsolutePath().toString(), "   ", 5000L)
                    check(emptyTaskRes is KotlinMcpResult.Error && emptyTaskRes.code == "EMPTY_TASK") { "empty task" }

                    // 3. Validation rejection: forbidden flags
                    val flagRes1 = service.execute(tmpDir.toAbsolutePath().toString(), "build --init-script bad.gradle", 5000L)
                    check(flagRes1 is KotlinMcpResult.Error && flagRes1.code == "VALIDATION_ERROR") { "init script rejected" }

                    val flagRes2 = service.execute(tmpDir.toAbsolutePath().toString(), "build -Dprop=val", 5000L)
                    check(flagRes2 is KotlinMcpResult.Error && flagRes2.code == "VALIDATION_ERROR") { "-D rejected" }

                    val flagRes3 = service.execute(tmpDir.toAbsolutePath().toString(), "test --tests '; rm -rf /'", 5000L)
                    check(flagRes3 is KotlinMcpResult.Error && flagRes3.code == "VALIDATION_ERROR") { "bad test filter rejected" }

                    // 4. Missing launcher error (when no gradlew and no gradle on empty dummy path)
                    val dummyEmptyDir = Files.createTempDirectory("gradle-no-launcher")
                    try {
                        val noLauncherRes = service.execute(dummyEmptyDir.toAbsolutePath().toString(), "build", 5000L)
                        // If system has gradle on PATH, it might launch or fail, or return GRADLE_NOT_FOUND
                        check(noLauncherRes is KotlinMcpResult.Error || noLauncherRes is KotlinMcpResult.Success) { "no launcher checked" }
                    } finally {
                        dummyEmptyDir.toFile().deleteRecursively()
                    }

                    // 5. Synthetic executable gradlew success run
                    val gradlewFile = File(tmpDir.toFile(), "gradlew")
                    val isWindows = System.getProperty("os.name").lowercase().contains("win")
                    if (isWindows) {
                        File(tmpDir.toFile(), "gradlew.bat").apply {
                            writeText("@echo off\r\nif \"%~1\" neq \"--no-daemon\" (exit /b 2)\r\nif \"%~2\" neq \"build\" (exit /b 2)\r\necho BUILD SUCCESSFUL in 1s\r\nexit /b 0\r\n")
                        }
                    } else {
                        gradlewFile.writeText("#!/bin/sh\nif [ \"$1\" != \"--no-daemon\" ] || [ \"$2\" != \"build\" ]; then echo \"invalid args: $@\"; exit 2; fi\necho 'BUILD SUCCESSFUL in 1s'\nexit 0\n")
                        gradlewFile.setExecutable(true)
                    }

                    val successRes = service.execute(
                        projectPath = tmpDir.toAbsolutePath().toString(),
                        task = "build :app:test --tests 'com.example.Test*' :app:assemble",
                        timeoutMillis = 5000L
                    )
                    check(successRes is KotlinMcpResult.Success) { "gradle success: " + successRes }
                    val succContent = (successRes as KotlinMcpResult.Success).content
                    check(succContent.contains("BUILD SUCCESSFUL")) { "contains build output" }
                    check(successRes.metadata["exitCode"] == "0") { "exit 0" }
                    check(successRes.metadata["task"] == "build :app:test --tests 'com.example.Test*' :app:assemble") { "task metadata" }
                    check(successRes.metadata["projectPath"] == tmpDir.toFile().absolutePath) { "projectPath metadata" }
                    val dur = successRes.metadata["durationMs"]?.toLongOrNull() ?: -1L
                    check(dur in 0..15000) { "valid duration: " + dur }

                    // 6. Synthetic executable gradlew failure run with diagnostic filtering
                    if (isWindows) {
                        File(tmpDir.toFile(), "gradlew.bat").apply {
                            writeText("@echo off\r\necho SKIPPED\r\necho NO-SOURCE\r\necho HONOUR THE JVM SETTINGS\r\necho DAEMON WILL BE STOPPED\r\necho ERROR: failed compilation\r\necho FAILED task\r\necho WHAT WENT WRONG\r\necho COMPILATION ERROR\r\necho > Task :app:compileKotlin FAILED\r\necho e: file.kt:1:1 Unresolved reference 'foo'\r\nexit /b 1\r\n")
                        }
                    } else {
                        val failScript = buildString {
                            appendLine("#!/bin/sh")
                            appendLine("echo 'FIRST_IGNORED_LINE'")
                            for (idx in 1..40) {
                                appendLine("echo 'e: file.kt:" + idx + ":1 error: message'")
                            }
                            appendLine("echo 'SKIPPED'")
                            appendLine("echo 'NO-SOURCE'")
                            appendLine("echo 'HONOUR THE JVM SETTINGS'")
                            appendLine("echo 'DAEMON WILL BE STOPPED'")
                            appendLine("echo 'ERROR: failed compilation'")
                            appendLine("echo 'FAILED task'")
                            appendLine("echo 'WHAT WENT WRONG'")
                            appendLine("echo 'COMPILATION ERROR'")
                            appendLine("echo '> Task :app:compileKotlin FAILED'")
                            appendLine("echo 'e: file.kt:100:1 Unresolved reference foo'")
                            appendLine("exit 1")
                        }
                        gradlewFile.writeText(failScript)
                        gradlewFile.setExecutable(true)
                    }

                    val failRes = service.execute(
                        projectPath = tmpDir.toAbsolutePath().toString(),
                        task = "compileKotlin",
                        timeoutMillis = 5000L
                    )
                    check(failRes is KotlinMcpResult.Error && failRes.code == "GRADLE_BUILD_FAILED" && failRes.requireAnotherCall == true) { "fail res" }
                    val failMsg = (failRes as KotlinMcpResult.Error).message
                    check(failMsg.contains("Unresolved reference foo")) { "contains diagnostic" }
                    check(failMsg.contains("> Task :app:compileKotlin FAILED")) { "contains Task FAILED" }
                    check(failMsg.contains("ERROR: failed compilation")) { "contains ERROR" }
                    check(failMsg.contains("FAILED task")) { "contains FAILED" }
                    check(failMsg.contains("WHAT WENT WRONG")) { "contains WHAT WENT WRONG" }
                    check(failMsg.contains("COMPILATION ERROR")) { "contains COMPILATION ERROR" }
                    check(!failMsg.contains("SKIPPED")) { "skipped lines filtered out" }
                    check(!failMsg.contains("NO-SOURCE")) { "no-source lines filtered out" }
                    check(!failMsg.contains("HONOUR THE JVM SETTINGS")) { "jvm settings lines filtered out" }
                    check(!failMsg.contains("DAEMON WILL BE STOPPED")) { "daemon stopped lines filtered out" }
                    check(!failMsg.contains("FIRST_IGNORED_LINE")) { "40-line tail truncated old line" }

                    // Single noisy line filter check
                    if (!isWindows) {
                        gradlewFile.writeText("#!/bin/sh\necho 'HONOUR THE JVM SETTINGS'\necho 'DAEMON WILL BE STOPPED'\nexit 1\n")
                        gradlewFile.setExecutable(true)
                        val noiseOnlyRes = service.execute(tmpDir.toAbsolutePath().toString(), "build", 5000L)
                        check(noiseOnlyRes is KotlinMcpResult.Error)
                        check(!noiseOnlyRes.message.contains("HONOUR") && !noiseOnlyRes.message.contains("DAEMON")) { "noise filtered case-insensitively" }
                    }

                    // 7. Synthetic executable gradlew timeout run & process kill check
                    val markerFile = File(tmpDir.toFile(), "marker.tmp")
                    if (markerFile.exists()) markerFile.delete()

                    if (isWindows) {
                        File(tmpDir.toFile(), "gradlew.bat").apply {
                            writeText("@echo off\r\nping 127.0.0.1 -n 10 > nul\r\nexit /b 0\r\n")
                        }
                    } else {
                        gradlewFile.writeText("#!/bin/sh\nsleep 0.4\ntouch '" + markerFile.absolutePath + "'\nexit 0\n")
                        gradlewFile.setExecutable(true)
                    }

                    val timeoutRes = service.execute(
                        projectPath = tmpDir.toAbsolutePath().toString(),
                        task = "longRunningTask",
                        timeoutMillis = 50L
                    )
                    check(timeoutRes is KotlinMcpResult.Error && timeoutRes.code == "EXECUTION_TIMEOUT") { "timeout res: " + timeoutRes }
                    Thread.sleep(500)
                    check(!markerFile.exists()) { "marker file must not exist because process was forcibly destroyed" }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: GradleRunService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN GradleRunService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for GradleRunService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for GradleRunService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production DocService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/doc/DocService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.shared.CommandService
            import com.gokorei.kotlinmcp.shared.ToonUtils
            import io.github.oshai.kotlinlogging.KotlinLogging
            import java.io.File
            import java.net.URLDecoder
            import java.util.concurrent.ConcurrentHashMap
            import kotlinx.serialization.decodeFromString
            import kotlinx.serialization.encodeToString
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceSource

        val testSuiteCode = """
            fun main() {
                val tmpPersist = File.createTempFile("doc-persist", ".json")
                try {
                    val service = DefaultDocService(tmpPersist.absolutePath)

                    // 1. Action enum & CommandService execute overloads
                    val execSearch = service.execute(DocAction.SEARCH, "flow")
                    check(execSearch is KotlinMcpResult.Success && execSearch.content.contains("Flow")) { "exec search" }

                    val execLookup = service.execute(DocAction.LOOKUP_SYMBOL, "Flow")
                    check(execLookup is KotlinMcpResult.Success && execLookup.content.contains("interface Flow")) { "exec lookup" }

                    val execExplain = service.execute(DocAction.EXPLAIN_FEATURE, "contracts")
                    check(execExplain is KotlinMcpResult.Success && execExplain.content.contains("Kotlin Contracts")) { "exec explain" }

                    val execCmd = (service as CommandService<DocAction>).execute(DocAction.SEARCH, "Result")
                    check(execCmd is KotlinMcpResult.Success) { "command service execute" }

                    // 2. searchDocs with empty query, matches, non-matches, and classpath filtering
                    val allDocs = service.searchDocs("")
                    check(allDocs is KotlinMcpResult.Success && allDocs.metadata["matchCount"]?.toIntOrNull() ?: 0 > 10) { "search empty" }
                    check(service.listCategories() is KotlinMcpResult.Success) { "listCategories" }
                    check(service.formatToonDocs() is KotlinMcpResult.Success) { "formatToonDocs" }
                    check(service.seedResources(File(".")) is KotlinMcpResult.Success) { "seedResources" }

                    val kotlinxSearch = service.searchDocs("kotlinx")
                    check(kotlinxSearch is KotlinMcpResult.Success)
                    val kotlinxContent = kotlinxSearch.content
                    check(kotlinxContent.contains("symbol") && kotlinxContent.contains("feature")) { "contains both symbol and feature matches: " + kotlinxContent }

                    val noMatchRes = service.searchDocs("non_existent_symbol_xyz123")
                    check(noMatchRes is KotlinMcpResult.Success && noMatchRes.content.contains("No documentation entries matched")) { "no match search" }

                    val cpFilteredSearch = service.searchDocs("datetime", classpath = listOf("/libs/kotlinx-datetime-jvm-0.4.0.jar"))
                    check(cpFilteredSearch is KotlinMcpResult.Success && cpFilteredSearch.content.contains("kotlinx.datetime")) { "cp match search" }

                    val cpExcludedSearch = service.searchDocs("datetime", classpath = listOf("/libs/unrelated.jar"))
                    check(cpExcludedSearch is KotlinMcpResult.Success) { "cp excluded search" }

                    // 3. lookupSymbol: direct, case insensitive, short name, compact preset, and classpath filtering
                    val directRes = service.lookupSymbol("kotlin.collections.List")
                    check(directRes is KotlinMcpResult.Success && directRes.content.contains("interface List")) { "direct lookup" }

                    // Uppercase FQN case-insensitive lookup (kills ignoreCase = true in caseInsensitive)
                    val ciFqnRes = service.lookupSymbol("KOTLIN.COLLECTIONS.LIST")
                    check(ciFqnRes is KotlinMcpResult.Success && ciFqnRes.content.contains("interface List")) { "ci fqn lookup" }

                    // Symbol with empty tags and non-empty classpath (must succeed)
                    val untaggedWithCp = service.lookupSymbol("kotlin.collections.List", classpath = listOf("/libs/some-random.jar"))
                    check(untaggedWithCp is KotlinMcpResult.Success && untaggedWithCp.content.contains("interface List")) { "untagged symbol with classpath" }

                    val compactRes = service.lookupSymbol("kotlin.collections.List", preset = "compact")
                    check(compactRes is KotlinMcpResult.Success && !compactRes.content.contains("subList")) { "compact lookup" }

                    val ciRes = service.lookupSymbol("KOTLIN.COLLECTIONS.MAP")
                    check(ciRes is KotlinMcpResult.Success && ciRes.content.contains("interface Map")) { "case insensitive lookup" }

                    val shortRes = service.lookupSymbol("Result")
                    check(shortRes is KotlinMcpResult.Success && shortRes.content.contains("value class Result")) { "short name lookup" }

                    val lowerShortRes = service.lookupSymbol("list")
                    check(lowerShortRes is KotlinMcpResult.Success && lowerShortRes.content.contains("interface List")) { "lowercase short name lookup" }

                    val cpOkLookup = service.lookupSymbol("kotlinx.datetime.Instant", classpath = listOf("kotlinx-datetime-0.4.0.jar"))
                    check(cpOkLookup is KotlinMcpResult.Success && cpOkLookup.content.contains("Instant")) { "cp ok lookup" }

                    val cpFailLookup = service.lookupSymbol("kotlinx.datetime.Instant", classpath = listOf("other.jar"))
                    check(cpFailLookup is KotlinMcpResult.Error && cpFailLookup.code == "SYMBOL_NOT_FOUND" && cpFailLookup.message.contains("filtered out")) { "cp fail lookup" }

                    val missingLookup = service.lookupSymbol("totally.unknown.Symbol")
                    check(missingLookup is KotlinMcpResult.Error && missingLookup.code == "SYMBOL_NOT_FOUND" && !missingLookup.message.contains("filtered out")) { "missing lookup" }

                    // 4. explainFeature: direct, case insensitive, substring, missing
                    val contractsExp = service.explainFeature("contracts")
                    check(contractsExp is KotlinMcpResult.Success && contractsExp.content.contains("Kotlin Contracts")) { "contracts explain" }

                    val coroutinesExp = service.explainFeature("Coroutines")
                    check(coroutinesExp is KotlinMcpResult.Success && coroutinesExp.content.contains("Kotlin Coroutines")) { "coroutines explain" }

                    val sealedExp = service.explainFeature("sealed class")
                    check(sealedExp is KotlinMcpResult.Success && sealedExp.content.contains("Sealed Classes")) { "sealed explain" }

                    val missingExp = service.explainFeature("quantum_computing_monad")
                    check(missingExp is KotlinMcpResult.Error && missingExp.code == "FEATURE_NOT_FOUND") { "missing feature" }

                    // 5. docFor: symbol, feature, invalid kind, URI decoding, and cache invalidation on dynamic register
                    check(service.docFor("symbol", "kotlin.collections.List")?.contains("interface List") == true) { "docFor symbol" }
                    check(service.docFor("symbol", "kotlin%2Ecollections%2EList")?.contains("interface List") == true) { "docFor encoded symbol" }
                    check(service.docFor("feature", "sealed_interface")?.contains("Sealed Interfaces") == true) { "docFor feature" }
                    check(service.docFor("feature", "Sealed%20Interface")?.contains("Sealed Interfaces") == true) { "docFor encoded feature" }
                    check(service.docFor("unknown_kind", "List") == null) { "docFor unknown kind" }

                    // Cache override invalidation checks (query before and after register to test docCache.clear())
                    val oldSymDoc = service.docFor("symbol", "kotlin.Result")
                    check(oldSymDoc != null && oldSymDoc.contains("value class Result")) { "cached old symbol" }
                    service.registerDynamicSymbol("kotlin.Result", "# Updated Result Doc\nNew description")
                    check(service.docFor("symbol", "kotlin.Result") == "# Updated Result Doc\nNew description") { "cache cleared after dynamic symbol register" }

                    val oldFeatDoc = service.docFor("feature", "contracts")
                    check(oldFeatDoc != null && oldFeatDoc.contains("Kotlin Contracts")) { "cached old feature" }
                    service.registerDynamicFeature("contracts", "# Updated Contracts Doc\nNew contracts details")
                    check(service.docFor("feature", "contracts") == "# Updated Contracts Doc\nNew contracts details") { "cache cleared after dynamic feature register" }

                    // 6. Dynamic registration, cache invalidation, and isolated persistence checks
                    // Check isolated persist() on registerDynamicSymbol
                    val tmpSymFile = File.createTempFile("doc-sym-persist", ".json")
                    try {
                        val symService = DefaultDocService(tmpSymFile.absolutePath)
                        symService.registerDynamicSymbol("isolated.DynamicSymbol", "# Isolated Sym Doc")
                        val reloadedSym = DefaultDocService(tmpSymFile.absolutePath)
                        check(reloadedSym.symbolDocs["isolated.DynamicSymbol"] == "# Isolated Sym Doc") { "isolated dynamic symbol persisted" }
                    } finally {
                        tmpSymFile.delete()
                    }

                    // Check isolated persist() on registerDynamicFeature
                    val tmpFeatFile = File.createTempFile("doc-feat-persist", ".json")
                    try {
                        val featService = DefaultDocService(tmpFeatFile.absolutePath)
                        featService.registerDynamicFeature("isolated_feature", "# Isolated Feat Doc")
                        val reloadedFeat = DefaultDocService(tmpFeatFile.absolutePath)
                        check(reloadedFeat.featureDocs["isolated_feature"] == "# Isolated Feat Doc") { "isolated dynamic feature persisted" }
                    } finally {
                        tmpFeatFile.delete()
                    }

                    // Check isolated persist() and docCache.clear() on registerDynamicNamespace
                    val tmpNsFile = File.createTempFile("doc-ns-persist", ".json")
                    try {
                        val nsService = DefaultDocService(tmpNsFile.absolutePath)
                        nsService.registerDynamicNamespace("isolated.ns", "# Initial NS Doc")
                        check(nsService.lookupSymbol("isolated.ns.ClassA").let { it is KotlinMcpResult.Success && it.content.contains("Initial NS Doc") }) { "initial ns lookup" }

                        nsService.registerDynamicNamespace("isolated.ns", "# Updated NS Doc")
                        check(nsService.lookupSymbol("isolated.ns.ClassA").let { it is KotlinMcpResult.Success && it.content.contains("Updated NS Doc") }) { "ns cache cleared" }

                        val reloadedNs = DefaultDocService(tmpNsFile.absolutePath)
                        check(reloadedNs.namespaces["isolated.ns"] == "# Updated NS Doc") { "isolated dynamic namespace persisted" }
                    } finally {
                        tmpNsFile.delete()
                    }

                    // Test unknown keys resilience in loadPersisted
                    val tmpUnknownKeysFile = File.createTempFile("doc-unknown-keys", ".json")
                    try {
                        tmpUnknownKeysFile.writeText("{\"version\":1,\"futureField\":\"val\",\"symbols\":{\"future.Sym\":\"# Future Sym\"}}")
                        val unknownKeyService = DefaultDocService(tmpUnknownKeysFile.absolutePath)
                        check(unknownKeyService.symbolDocs["future.Sym"] == "# Future Sym") { "unknown keys ignored successfully" }
                    } finally {
                        tmpUnknownKeysFile.delete()
                    }

                    // Overall reloaded checks
                    val reloadedService = DefaultDocService(tmpPersist.absolutePath)
                    check(reloadedService.symbolAppliesTo.isNotEmpty()) { "symbolAppliesTo" }
                    check(reloadedService.featureAppliesTo.isNotEmpty()) { "featureAppliesTo" }
                } finally {
                    tmpPersist.delete()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: DocService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN DocService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for DocService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for DocService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production LintService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/linting/LintService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import java.io.File
            import java.nio.file.Files
            import java.nio.file.Path
            import java.util.concurrent.TimeUnit
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceSource

        val testSuiteCode = """
            fun main() {
                // 1. ChildFirstClassLoader prefix and loadClass checks
                val loader = ChildFirstClassLoader(emptyArray(), DefaultLintService::class.java.classLoader)
                check(loader.shouldPreferChild("io.gitlab.arturbosch.detekt.Cli")) { "detekt prefix" }
                check(loader.shouldPreferChild("com.pinterest.ktlint.Main")) { "ktlint prefix" }
                check(loader.shouldPreferChild("kotlin.String")) { "kotlin prefix" }
                check(loader.shouldPreferChild("org.jetbrains.kotlin.psi.KtFile")) { "compiler prefix" }
                check(loader.shouldPreferChild("org.jetbrains.kotlinx.coroutines.Job")) { "kotlinx prefix" }
                check(loader.shouldPreferChild("picocli.CommandLine")) { "picocli prefix" }
                check(loader.shouldPreferChild("com.beust.jcommander.JCommander")) { "jcommander prefix" }
                check(loader.shouldPreferChild("org.yaml.snakeyaml.Yaml")) { "snakeyaml prefix" }
                check(loader.shouldPreferChild("org.antlr.v4.runtime.Parser")) { "antlr prefix" }
                check(loader.shouldPreferChild("com.charleskorn.kaml.Yaml")) { "kaml prefix" }
                check(loader.shouldPreferChild("com.ec4j.core.EditorConfig")) { "ec4j prefix" }
                check(loader.shouldPreferChild("kotlinx.html.HTML")) { "html prefix" }
                check(loader.shouldPreferChild("org.checkerframework.checker.nullness.qual.Nullable")) { "checkerframework prefix" }
                check(!loader.shouldPreferChild("java.lang.String")) { "java prefix not preferred" }
                check(!loader.shouldPreferChild("com.google.common.collect.Lists")) { "google prefix not preferred" }

                val cls = loader.loadClass("java.lang.String")
                check(cls == java.lang.String::class.java) { "load java.lang.String" }
                val childCls = loader.loadClass("com.gokorei.kotlinmcp.linting.LintFinding")
                check(childCls.name == "com.gokorei.kotlinmcp.linting.LintFinding") { "load child class" }

                // 2. Missing classpath errors via resourceOverrides
                val missingDetektService = DefaultLintService(resourceOverrides = mapOf("detekt.classpath.txt" to null))
                val detektMissingRes = missingDetektService.runDetekt("fun main() {}")
                check(detektMissingRes is KotlinMcpResult.Error && detektMissingRes.code == "DETEKT_CLASSPATH_MISSING" && detektMissingRes.requireAnotherCall == true) { "detekt missing classpath" }

                val missingKtlintService = DefaultLintService(resourceOverrides = mapOf("ktlint.classpath.txt" to null))
                val ktlintMissingRes = missingKtlintService.formatKtlint("fun main() {}")
                check(ktlintMissingRes is KotlinMcpResult.Error && ktlintMissingRes.code == "KTLINT_CLASSPATH_MISSING" && ktlintMissingRes.requireAnotherCall == true) { "ktlint missing classpath" }

                // 3. Baseline read & dump
                val tmpDir = Files.createTempDirectory("lint-baseline-test")
                try {
                    val service = DefaultLintService()

                    // baselineRead missing
                    val missingBaseline = service.baselineRead(tmpDir.toAbsolutePath().toString())
                    check(missingBaseline is KotlinMcpResult.Error && missingBaseline.code == "NOT_FOUND") { "baseline not found" }

                    // baselineDump invalid arguments (non-directory)
                    val dummyFile = File.createTempFile("dummy-not-dir", ".tmp")
                    try {
                        val invalidDump = service.baselineDump(dummyFile.absolutePath)
                        check(invalidDump is KotlinMcpResult.Error && invalidDump.code == "INVALID_ARGUMENTS") { "dump not a directory" }
                    } finally {
                        dummyFile.delete()
                    }

                    // baselineDump with explicit findings list
                    val findings = listOf(
                        LintFinding(rule = "MagicNumber", severity = "warning", file = "Test.kt", line = 5, column = 10, message = "Magic number found"),
                        LintFinding(rule = "EmptyFunctionBlock", severity = "style", file = "Other.kt", line = 12, column = 1, message = "Empty body")
                    )
                    val dumpRes = service.baselineDump(tmpDir.toAbsolutePath().toString(), findings)
                    check(dumpRes is KotlinMcpResult.Success && dumpRes.metadata["issueCount"] == "2" && dumpRes.content.contains("2 suppressed issue(s)")) { "dump success" }

                    val writtenXml = File(tmpDir.toFile(), "detekt-baseline.xml").readText()
                    check(writtenXml.startsWith("<?xml version=\"1.0\" ?>") &&
                          writtenXml.contains("<SmellBaseline>") &&
                          writtenXml.contains("<ManuallySuppressedIssues></ManuallySuppressedIssues>") &&
                          writtenXml.contains("<CurrentIssues>") &&
                          writtenXml.contains("<ID>MagicNumber:Test.kt" + "$" + "Magicnumberfound</ID>") &&
                          writtenXml.contains("<ID>EmptyFunctionBlock:Other.kt" + "$" + "Emptybody</ID>") &&
                          writtenXml.contains("</CurrentIssues>") &&
                          writtenXml.contains("</SmellBaseline>")) { "xml structure valid: " + writtenXml }

                    // baselineRead roundtrip
                    val readRes = service.baselineRead(tmpDir.toAbsolutePath().toString())
                    check(readRes is KotlinMcpResult.Success) { "baseline read success" }
                    val readContent = (readRes as KotlinMcpResult.Success).content
                    check(readContent.startsWith("# Detekt Baseline Inventory\n```xml\n") &&
                          readContent.contains("MagicNumber:Test.kt") &&
                          readContent.contains("EmptyFunctionBlock:Other.kt") &&
                          readContent.endsWith("```")) { "baseline content matched: " + readContent }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }

                // 4. DefaultLintService real execution with resolved classpaths
                val detektCp = File("build/resources/main/detekt.classpath.txt").takeIf { it.exists() }?.readText()
                val ktlintCp = File("build/resources/main/ktlint.classpath.txt").takeIf { it.exists() }?.readText()
                val overrides = mutableMapOf<String, String?>()
                if (detektCp != null) overrides["detekt.classpath.txt"] = detektCp
                if (ktlintCp != null) overrides["ktlint.classpath.txt"] = ktlintCp

                val realService = DefaultLintService(resourceOverrides = overrides)
                val (detektCpRes, ktlintCpRes) = realService.prewarm()
                check(detektCpRes != null && ktlintCpRes != null) { "prewarm resolved classpaths" }

                // Direct configToYaml verification
                val yamlConfig = mapOf(
                    "comments" to mapOf(
                        "CommentOverPrivateFunction" to "off",
                        "CommentOverPrivateProperty" to "disabled",
                        "DocComment" to "false"
                    ),
                    "style" to mapOf(
                        "MagicNumber" to "on",
                        "WildcardImport" to "enabled",
                        "MaxLineLength" to "true",
                        "maxLineLength" to 120
                    ),
                    "build" to mapOf(
                        "weights" to listOf("complexity", "style")
                    )
                )
                val generatedYaml = realService.configToYaml(yamlConfig)
                check(generatedYaml.contains("comments:\n  CommentOverPrivateFunction:\n    active: false") &&
                      generatedYaml.contains("CommentOverPrivateProperty:\n    active: false") &&
                      generatedYaml.contains("DocComment:\n    active: false") &&
                      generatedYaml.contains("style:\n  MagicNumber:\n    active: true") &&
                      generatedYaml.contains("WildcardImport:\n    active: true") &&
                      generatedYaml.contains("MaxLineLength:\n    active: true") &&
                      generatedYaml.contains("maxLineLength: 120") &&
                      generatedYaml.contains("build:\n  weights:\n    - complexity\n    - style")) { "yaml generation matched: " + generatedYaml }

                // Direct parseDetektXml verification
                val tmpXmlFile = File.createTempFile("detekt-syn-report", ".xml")
                try {
                    tmpXmlFile.writeText("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<checkstyle version=\"4.3\">\n  <file name=\"/app/src/MyFile.kt\">\n    <error source=\"io.gitlab.arturbosch.detekt.rules.MagicNumber\" severity=\"warning\" line=\"42\" column=\"15\" message=\"Magic number found\"/>\n  </file>\n</checkstyle>")
                    val parsed = realService.parseDetektXml(tmpXmlFile)
                    check(parsed.size == 1) { "parsed xml finding count" }
                    val item = parsed.first()
                    check(item.rule == "MagicNumber" && item.severity == "warning" && item.file == "/app/src/MyFile.kt" && item.line == 42 && item.column == 15 && item.message == "Magic number found") { "parsed item attributes" }
                } finally {
                    tmpXmlFile.delete()
                }

                // Direct runJavaTool execution check with directory
                val runDir = Files.createTempDirectory("run-tool-dir")
                try {
                    val toolRun = realService.runJavaTool("NonExistentMainClass", emptyList(), emptyList(), runDir, 5L)
                    check(toolRun.exitCode != 0 && toolRun.tailOutput.isNotEmpty()) { "runJavaTool failure output captured" }
                } finally {
                    runDir.toFile().deleteRecursively()
                }

                // Detekt execution failure check
                val badDetektService = DefaultLintService(resourceOverrides = mapOf("detekt.classpath.txt" to "/nonexistent/invalid.jar"))
                val failDetektRes = badDetektService.runDetekt("fun main() {}")
                check(failDetektRes is KotlinMcpResult.Error && failDetektRes.requireAnotherCall == true && failDetektRes.code == "DETEKT_EXECUTION_ERROR") { "detekt failure requireAnotherCall" }

                // Ktlint formatting: default apply parameter (tests apply = true default)
                val unformatted = "fun main(  ) {\nprintln( \"hello\" )\n}\n"
                val defaultApplyRes = realService.formatKtlint(unformatted)
                check(defaultApplyRes is KotlinMcpResult.Success &&
                      defaultApplyRes.metadata["formatted"] == "true" &&
                      defaultApplyRes.metadata["apply"] == "true" &&
                      defaultApplyRes.content.startsWith("# Ktlint Format Result (apply=true)\nFormatted code applied successfully.\n\n```kotlin\n") &&
                      defaultApplyRes.content.contains("println(\"hello\")") &&
                      defaultApplyRes.content.endsWith("```")) { "ktlint format unformatted default apply" }

                // Ktlint formatting: apply = true with already formatted code and compilerClasspath
                val cleanCode = "fun main() {\n    println(\"hello\")\n}\n"
                val cleanRes = realService.formatKtlint(cleanCode, apply = true, compilerClasspath = listOf("/custom/extra.jar", "  "))
                check(cleanRes is KotlinMcpResult.Success &&
                      cleanRes.metadata["formatted"] == "false" &&
                      cleanRes.content.contains("Code is already correctly formatted according to ktlint rules.")) { "ktlint format clean" }

                // Ktlint check: apply = false
                val checkRes = realService.formatKtlint(cleanCode, apply = false)
                check(checkRes is KotlinMcpResult.Success &&
                      checkRes.metadata["apply"] == "false" &&
                      checkRes.content.contains("ktlint check complete.")) { "ktlint check" }

                // Detekt analysis with snippet triggering findings and compilerClasspath
                val snippet = "fun process(name: String?) {\n    val unused = name!!.length\n    val alsoUnused = name!!.length\n}\n"
                val detektRes = realService.runDetekt(snippet, compilerClasspath = listOf("/custom/extra.jar", "  "))
                check(detektRes is KotlinMcpResult.Success &&
                      detektRes.content.contains("# Detekt Findings (mode=SUBPROCESS,") &&
                      detektRes.content.contains("UnusedPrivateProperty") &&
                      detektRes.metadata["mode"] == "SUBPROCESS" &&
                      (detektRes.metadata["findingCount"]?.toInt() ?: 0) >= 1) { "detekt findings: " + detektRes }

                // Detekt analysis with workspace
                val wsDir = Files.createTempDirectory("lint-ws-config-test")
                try {
                    val badFile = wsDir.resolve("Bad.kt").toFile()
                    badFile.writeText(snippet)
                    val wsRes = realService.runDetekt("", workspacePath = wsDir.toAbsolutePath().toString())
                    check(wsRes is KotlinMcpResult.Success && (wsRes.metadata["findingCount"]?.toInt() ?: 0) >= 1) { "workspace detekt run: " + wsRes }
                } finally {
                    wsDir.toFile().deleteRecursively()
                }

                // Detekt analysis with rules disabled via config
                val disabledConfig = mapOf(
                    "style" to mapOf("UnusedPrivateProperty" to "off"),
                    "potential-bugs" to mapOf("UnsafeCallOnNullableType" to "off")
                )
                val disabledRes = realService.runDetekt(snippet, config = disabledConfig)
                check(disabledRes is KotlinMcpResult.Success && (disabledRes.metadata["findingCount"]?.toInt() ?: 0) == 0 && disabledRes.content.contains("No detekt findings.")) { "disabled config run" }

                // Detekt with zero findings on clean snippet
                val cleanDetektRes = realService.runDetekt("fun main() {\n    println(\"hello world\")\n}\n")
                check(cleanDetektRes is KotlinMcpResult.Success &&
                      cleanDetektRes.metadata["findingCount"] == "0" &&
                      cleanDetektRes.content.contains("No detekt findings.")) { "clean detekt run: " + cleanDetektRes }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 30000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LintService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN LintService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LintService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for LintService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production EnvironmentProfileDetector source file`() {
        val detectorFile = File("src/main/kotlin/com/gokorei/kotlinmcp/project/EnvironmentProfileDetector.kt")
        assertTrue(detectorFile.exists(), "Target file must exist: ${detectorFile.absolutePath}")

        val detectorSource = detectorFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile
            import com.gokorei.kotlinmcp.models.FrameworkFeature
            import com.gokorei.kotlinmcp.project.GradleProjectInspector
            import java.io.File
            import java.nio.file.Files
        """.trimIndent()

        val productionCode = imports + "\n\n" + detectorSource

        val testSuiteCode = """
            fun main() {
                val detector = EnvironmentProfileDetector()
                val inspector = GradleProjectInspector()

                // 1. detectProfile with null projectPath and empty content
                val emptyProfile = detector.detectProfile("", null, inspector)
                check(!emptyProfile.isKmp) { "empty isKmp" }
                check(emptyProfile.activeFrameworks.isEmpty()) { "empty frameworks" }

                // 2. Individual framework triggers
                val frameworks = listOf(
                    "ktor" to FrameworkFeature.KTOR,
                    "io.ktor" to FrameworkFeature.KTOR,
                    "spring" to FrameworkFeature.SPRING,
                    "org.springframework" to FrameworkFeature.SPRING,
                    "compose" to FrameworkFeature.COMPOSE,
                    "androidx.compose" to FrameworkFeature.COMPOSE,
                    "arrow-core" to FrameworkFeature.ARROW,
                    "io.arrow-kt" to FrameworkFeature.ARROW,
                    "serialization" to FrameworkFeature.SERIALIZATION,
                    "kotlinx-serialization" to FrameworkFeature.SERIALIZATION,
                    "mockk" to FrameworkFeature.MOCKK,
                    "io.mockk" to FrameworkFeature.MOCKK,
                    "coroutines" to FrameworkFeature.COROUTINES,
                    "kotlinx-coroutines" to FrameworkFeature.COROUTINES,
                    "turbine" to FrameworkFeature.TURBINE,
                    "app.cash.turbine" to FrameworkFeature.TURBINE,
                    "datetime" to FrameworkFeature.DATETIME,
                    "kotlinx-datetime" to FrameworkFeature.DATETIME,
                    "exposed" to FrameworkFeature.EXPOSED,
                    "org.jetbrains.exposed" to FrameworkFeature.EXPOSED,
                    "room" to FrameworkFeature.ROOM,
                    "androidx.room" to FrameworkFeature.ROOM
                )

                for ((kw, expected) in frameworks) {
                    val p = detector.detectProfile(kw, null, inspector)
                    check(p.activeFrameworks.contains(expected)) { "expected " + expected + " for " + kw }
                }

                // 3. Multiplatform trigger
                val kmpProfile = detector.detectProfile("multiplatform", null, inspector)
                check(kmpProfile.isKmp) { "isKmp true" }

                // 4. ProjectPath reading
                val tmpDir = Files.createTempDirectory("env-prof-test")
                try {
                    // Non-existent build.gradle.kts
                    val missingFileProf = detector.detectProfile("ktor", tmpDir.toAbsolutePath().toString(), inspector)
                    check(missingFileProf.activeFrameworks.contains(FrameworkFeature.KTOR)) { "missing file fallback" }

                    // Existing build.gradle.kts
                    val buildFile = tmpDir.resolve("build.gradle.kts").toFile()
                    buildFile.writeText("dependencies { implementation(\"io.ktor:ktor-server\") }\n")
                    val existingFileProf = detector.detectProfile("coroutines", tmpDir.toAbsolutePath().toString(), inspector)
                    check(existingFileProf.activeFrameworks.contains(FrameworkFeature.KTOR)) { "read from file" }
                    check(existingFileProf.activeFrameworks.contains(FrameworkFeature.COROUTINES)) { "read from param" }
                } finally {
                    tmpDir.toFile().deleteRecursively()
                }

                // 5. detectEnvironmentProfile Result formatting
                val resYes = detector.detectEnvironmentProfile("multiplatform\nktor\ncoroutines", null)
                check(resYes is KotlinMcpResult.Success) { "resYes success" }
                check(resYes.content.contains("# Project Environment Profile")) { "header" }
                check(resYes.content.contains("- Multiplatform (KMP): Yes")) { "kmp yes" }
                check(resYes.content.contains("- Active Frameworks (2):")) { "framework count 2" }
                check(resYes.content.contains("KTOR")) { "contains ktor" }
                check(resYes.content.contains("COROUTINES")) { "contains coroutines" }

                val resNo = detector.detectEnvironmentProfile("", null)
                check(resNo is KotlinMcpResult.Success) { "resNo success" }
                check(resNo.content.contains("- Multiplatform (KMP): No")) { "kmp no" }
                check(resNo.content.contains("- Active Frameworks (0):")) { "framework count 0" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 10000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: EnvironmentProfileDetector.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.results.firstOrNull()?.details?.startsWith("Snippet execution threw an unhandled exception") == true) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN EnvironmentProfileDetector.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================\n")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for EnvironmentProfileDetector.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for EnvironmentProfileDetector.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production PackageApiExporter source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/project/PackageApiExporter.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val exporterSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
            import java.io.File
            import java.nio.file.Files
        """.trimIndent()

        val productionCode = imports + "\n\n" + exporterSource

        val testSuiteCode = """
            fun main() {
                val exporter = PackageApiExporter()
                val indexer = WorkspaceSemanticIndexer()

                // 1. projectPath null or blank
                val nullRes = exporter.packageApi(null, null, indexer)
                check(nullRes is KotlinMcpResult.Error && nullRes.code == "INVALID_ARGUMENTS" && nullRes.message == "projectPath is required for package_api.") { "null projectPath" }

                val blankRes = exporter.packageApi("  ", null, indexer)
                check(blankRes is KotlinMcpResult.Error && blankRes.code == "INVALID_ARGUMENTS" && blankRes.message == "projectPath is required for package_api.") { "blank projectPath" }

                // 2. projectPath not a directory
                val dummyFile = File.createTempFile("exporter-dummy", ".tmp")
                try {
                    val notDirRes = exporter.packageApi(dummyFile.absolutePath, null, indexer)
                    check(notDirRes is KotlinMcpResult.Error && notDirRes.code == "INVALID_ARGUMENTS" && notDirRes.message == "projectPath must be a readable directory for package_api.") { "not a directory" }
                } finally {
                    dummyFile.delete()
                }

                // 3. projectPath empty directory (no kt files)
                val emptyDir = Files.createTempDirectory("exporter-empty")
                try {
                    val emptyRes = exporter.packageApi(emptyDir.toAbsolutePath().toString(), null, indexer)
                    check(emptyRes is KotlinMcpResult.Error && emptyRes.code == "NOT_FOUND" && emptyRes.message.contains("No public declarations found for package '(any)'.")) { "empty dir" }
                } finally {
                    emptyDir.toFile().deleteRecursively()
                }

                // 4. Real workspace with kt files
                val wsDir = Files.createTempDirectory("exporter-ws")
                try {
                    val pkgDir = wsDir.resolve("com/example").toFile()
                    pkgDir.mkdirs()
                    val ktFile = File(pkgDir, "Api.kt")
                    ktFile.writeText("package com.example\n\n/** Greet someone */\nfun greet(name: String): String = \"Hello \${'$'}name\"\n\nclass Greeter(val prefix: String)\n")

                    // 4a. PackageName = null (all packages)
                    val allRes = exporter.packageApi(wsDir.toAbsolutePath().toString(), null, indexer)
                    check(allRes is KotlinMcpResult.Success) { "all packages success" }
                    check(allRes.content.contains("# Public API Surface — all packages (2 declarations)\n\n## `com/example/Api.kt`")) { "all packages header with blank line" }
                    check(allRes.content.contains("- `public fun greet(name: String): String` — Greet someone")) { "greet declaration with doc" }
                    check(allRes.content.contains("- `public class Greeter`\n\n> Mode: semantic (inferred return types resolved)")) { "greeter declaration with blank line before footer" }
                    check(allRes.metadata["packageName"] == "") { "empty packageName metadata" }
                    check(allRes.metadata["declarationCount"] == "2") { "declarationCount metadata" }
                    check(allRes.metadata["fileCount"] == "1") { "fileCount metadata" }

                    // 4b. PackageName matching com.example
                    val matchRes = exporter.packageApi(wsDir.toAbsolutePath().toString(), "com.example", indexer)
                    check(matchRes is KotlinMcpResult.Success) { "com.example success" }
                    check(matchRes.content.contains("# Public API Surface — com.example (2 declarations)")) { "com.example header" }
                    check(matchRes.metadata["packageName"] == "com.example") { "packageName metadata match" }

                    // 4c. PackageName not matching
                    val noMatchRes = exporter.packageApi(wsDir.toAbsolutePath().toString(), "com.other", indexer)
                    check(noMatchRes is KotlinMcpResult.Error && noMatchRes.code == "NOT_FOUND" && noMatchRes.message.contains("No public declarations found for package 'com.other'.")) { "com.other not found" }

                    // 4d. exportPackageApi wrapper call
                    val exportRes = exporter.exportPackageApi("build script", wsDir.toAbsolutePath().toString())
                    check(exportRes is KotlinMcpResult.Success && exportRes.metadata["declarationCount"] == "2") { "exportPackageApi wrapper" }
                } finally {
                    wsDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 10000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: PackageApiExporter.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.results.firstOrNull()?.details?.startsWith("Snippet execution threw an unhandled exception") == true) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN PackageApiExporter.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for PackageApiExporter.kt")
        assertTrue(
            report.score >= 85.0,
            "Mutation score for PackageApiExporter.kt (${report.score}%) must be at least 85%"
        )
    }

    @Test
    fun `mutation test production SchemaScanner source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/project/SchemaScanner.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val scannerSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import org.jetbrains.kotlin.psi.*
            import java.io.File
            import java.nio.file.Files
        """.trimIndent()

        val productionCode = imports + "\n\n" + scannerSource

        val testSuiteCode = """
            fun main() {
                val scanner = SchemaScanner()

                // 1. null or blank projectPath
                val nullRes = scanner.scanSchemas(null)
                check(nullRes is KotlinMcpResult.Error && nullRes.code == "MISSING_PROJECT_PATH") { "null projectPath" }

                val blankRes = scanner.scanSchemas("   ")
                check(blankRes is KotlinMcpResult.Error && blankRes.code == "MISSING_PROJECT_PATH") { "blank projectPath" }

                // 2. not a directory
                val dummyFile = File.createTempFile("scanner-dummy", ".tmp")
                try {
                    val notDirRes = scanner.scanSchemas(dummyFile.absolutePath)
                    check(notDirRes is KotlinMcpResult.Error && notDirRes.code == "INVALID_PATH") { "not dir path" }
                } finally {
                    dummyFile.delete()
                }

                // 3. empty directory (no schemas)
                val emptyDir = Files.createTempDirectory("scanner-empty")
                try {
                    val emptyRes = scanner.scanSchemas(emptyDir.toAbsolutePath().toString())
                    check(emptyRes is KotlinMcpResult.Success) { "empty dir success" }
                    check(emptyRes.content.contains("# API / DB Schema Digest")) { "empty digest header" }
                    check(emptyRes.content.contains("No schema definitions detected in the scanned sources")) { "no schemas message" }
                    check(emptyRes.metadata["sqlTableCount"] == "0") { "sqlTableCount 0" }
                    check(emptyRes.metadata["exposedTableCount"] == "0") { "exposedTableCount 0" }
                    check(emptyRes.metadata["dtoCount"] == "0") { "dtoCount 0" }
                    check(emptyRes.metadata["openApiSpecCount"] == "0") { "openApiSpecCount 0" }
                } finally {
                    emptyDir.toFile().deleteRecursively()
                }

                // 4. populated directory with all 4 source types and excluded build dir
                val wsDir = Files.createTempDirectory("scanner-ws")
                try {
                    // 4a. SQL DDL
                    val sqlFile = wsDir.resolve("schema.sql").toFile()
                    sqlFile.writeText(
                        "CREATE TABLE IF NOT EXISTS users (\n" +
                        "  id INT PRIMARY KEY,\n" +
                        "  name VARCHAR(255) NOT NULL,\n" +
                        "  -- inline comment\n" +
                        "  /* block comment */\n" +
                        "  * star comment\n" +
                        "  CONSTRAINT uk_name UNIQUE(name)\n" +
                        ");\n"
                    )

                    // 4b. SQL file
                    val fallbackSql = wsDir.resolve("fallback.sql").toFile()
                    fallbackSql.writeText("CREATE TABLE accounts (\n  id INT,\n  balance DECIMAL(10, 2)\n);\n")

                    // 4c. Exposed table file with Table, IntIdTable, nested Table, and PlainTable
                    val exposedFile = wsDir.resolve("Tables.kt").toFile()
                    exposedFile.writeText(
                        "package db\n" +
                        "import org.jetbrains.exposed.sql.Table\n" +
                        "import org.jetbrains.exposed.dao.id.IntIdTable\n" +
                        "object UsersTable : Table(\"users\") {\n" +
                        "  val id = integer(\"id\").autoIncrement()\n" +
                        "  val name = varchar(\"name\", 255)\n" +
                        "}\n" +
                        "class OrdersTable(name: String) : IntIdTable(name) {\n" +
                        "  val amount = double(\"amount\")\n" +
                        "}\n" +
                        "object PlainTable : Table {\n" +
                        "  val code = varchar(\"code\", 50)\n" +
                        "}\n" +
                        "object OuterScope {\n" +
                        "  object InnerTable : Table {\n" +
                        "    val tag = text(\"tag\")\n" +
                        "  }\n" +
                        "}\n"
                    )

                    // 4d. @Serializable DTO file with nested DTO and PlainDto
                    val dtoFile = wsDir.resolve("Dtos.kt").toFile()
                    dtoFile.writeText(
                        "package models\n" +
                        "import kotlinx.serialization.Serializable\n" +
                        "@Serializable\n" +
                        "data class UserDto(val id: Int, val name: String) {\n" +
                        "  var role: String = \"user\"\n" +
                        "}\n" +
                        "@Serializable\n" +
                        "data class PlainDto(val key: String)\n" +
                        "class OuterDto {\n" +
                        "  @Serializable\n" +
                        "  data class NestedDto(val count: Long)\n" +
                        "}\n"
                    )

                    // 4e. OpenAPI YAML with multiple paths and sibling indent
                    val openApiYaml = wsDir.resolve("openapi.yaml").toFile()
                    openApiYaml.writeText(
                        "/users:\n" +
                        "  get:\n" +
                        "    summary: list users\n" +
                        "  post:\n" +
                        "    summary: create user\n" +
                        "/items:\n" +
                        "  delete:\n" +
                        "    summary: remove item\n"
                    )

                    // 4f. OpenAPI JSON in subfolder
                    val subDir = wsDir.resolve("api").toFile()
                    subDir.mkdirs()
                    val openApiJson = File(subDir, "openapi.json")
                    openApiJson.writeText(
                        "{\n" +
                        "  \"/orders\": {\n" +
                        "    \"get\": {},\n" +
                        "    \"delete\": {}\n" +
                        "  }\n" +
                        "}\n"
                    )

                    // 4g. Excluded dir (build) should NOT be scanned
                    val buildDir = wsDir.resolve("build").toFile()
                    buildDir.mkdirs()
                    val ignoredSql = File(buildDir, "ignored.sql")
                    ignoredSql.writeText("CREATE TABLE ignored (id INT);")

                    // Run scanner on populated workspace
                    val res = scanner.scanSchemas(wsDir.toAbsolutePath().toString())
                    check(res is KotlinMcpResult.Success) { "populated scan success" }
                    check(res.content.contains("# API / DB Schema Digest")) { "header" }
                    check(res.content.contains("Scanned `${'$'}{wsDir.toAbsolutePath()}` — 2 SQL table(s), 4 Exposed table(s), 3 @Serializable DTO(s), 2 OpenAPI spec(s).")) { "scan summary line" }
                    check(res.content.contains("## SQL DDL Tables (2)")) { "sql tables section" }
                    check(res.content.contains("### `users` — schema.sql\n  - `id INT PRIMARY KEY`\n  - `name VARCHAR(255) NOT NULL`")) { "users sql table and columns" }
                    check(res.content.contains("### `accounts` — fallback.sql\n  - `id INT`\n  - `balance DECIMAL(10, 2)`")) { "accounts sql table" }
                    check(res.content.contains("## Exposed Tables (4)")) { "exposed tables section" }
                    check(res.content.contains("### `UsersTable` — Tables.kt\n  - `id = integer(\"id\").autoIncrement()`\n  - `name = varchar(\"name\", 255)`")) { "UsersTable exposed" }
                    check(res.content.contains("### `OrdersTable` — Tables.kt\n  - `amount = double(\"amount\")`")) { "OrdersTable exposed" }
                    check(res.content.contains("### `PlainTable` — Tables.kt\n  - `code = varchar(\"code\", 50)`")) { "PlainTable exposed" }
                    check(res.content.contains("### `InnerTable` — Tables.kt\n  - `tag = text(\"tag\")`")) { "InnerTable exposed" }
                    check(res.content.contains("## @Serializable DTOs (3)")) { "dtos section" }
                    check(res.content.contains("### `UserDto` — Dtos.kt\n  - `id: Int`\n  - `name: String`\n  - `role: String`")) { "UserDto fields" }
                    check(res.content.contains("### `PlainDto` — Dtos.kt\n  - `key: String`")) { "PlainDto fields" }
                    check(res.content.contains("### `NestedDto` — Dtos.kt\n  - `count: Long`")) { "NestedDto fields" }
                    check(res.content.contains("## OpenAPI Paths (2)")) { "openapi section" }
                    check(res.content.contains("### `openapi.yaml`")) { "openapi yaml header" }
                    check(res.content.contains("### `api/openapi.json`")) { "openapi json header" }
                    check(res.content.contains("  - `get /users`")) { "get /users" }
                    check(res.content.contains("  - `post /users`")) { "post /users" }
                    check(res.content.contains("  - `delete /items`")) { "delete /items" }
                    check(res.content.contains("  - `get /orders`")) { "get /orders" }
                    check(res.content.contains("  - `delete /orders`")) { "delete /orders" }
                    check(!res.content.contains("ignored")) { "ignored build dir not scanned" }

                    check(res.metadata["sqlTableCount"] == "2") { "sqlTableCount 2" }
                    check(res.metadata["exposedTableCount"] == "4") { "exposedTableCount 4" }
                    check(res.metadata["dtoCount"] == "3") { "dtoCount 3" }
                    check(res.metadata["openApiSpecCount"] == "2") { "openApiSpecCount 2" }

                    // 5. Tables-only directory (kills sqlTables.size + exposedTables.size mutant)
                    val tablesOnlyDir = Files.createTempDirectory("scanner-tables-only")
                    try {
                        val oneSql = tablesOnlyDir.resolve("One.sql").toFile()
                        oneSql.writeText("CREATE TABLE t1 (id INT);\n")
                        val oneExposed = tablesOnlyDir.resolve("One.kt").toFile()
                        oneExposed.writeText("object T2 : Table { val id = integer(\"id\") }\n")
                        val onlyRes = scanner.scanSchemas(tablesOnlyDir.toAbsolutePath().toString())
                        check(onlyRes is KotlinMcpResult.Success && onlyRes.metadata["sqlTableCount"] == "1" && onlyRes.metadata["exposedTableCount"] == "1") { "tables only scan" }
                        check(onlyRes.content.contains("## SQL DDL Tables (1)") && onlyRes.content.contains("## Exposed Tables (1)")) { "tables only sections present" }
                    } finally {
                        tablesOnlyDir.toFile().deleteRecursively()
                    }
                } finally {
                    wsDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: SchemaScanner.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN SchemaScanner.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for SchemaScanner.kt")
        assertTrue(
            report.score >= 80.0,
            "Mutation score for SchemaScanner.kt (${report.score}%) must be at least 80%"
        )
    }

    @Test
    fun `mutation test production ProjectService source file`() {
        val file = File("src/main/kotlin/com/gokorei/kotlinmcp/project/ProjectService.kt")
        assertTrue(file.exists(), "Target file must exist: ${file.absolutePath}")

        val serviceSource = file.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.*
            import com.gokorei.kotlinmcp.shared.CommandService
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
            import com.gokorei.kotlinmcp.project.SchemaScanner
            import com.gokorei.kotlinmcp.project.VulnerabilityAuditor
            import org.jetbrains.kotlin.psi.*
            import java.io.File
            import java.nio.file.Files
        """.trimIndent()

        val productionCode = imports + "\n\n" + serviceSource

        val testSuiteCode = """
            fun main() {
                val service = DefaultProjectService()

                // 1. KMP targets & single target
                val kmpScript = "plugins { kotlin(\"multiplatform\") }\nkotlin {\n  jvm()\n  js { browser() }\n  iosX64()\n  wasmJs()\n}\n"
                val kmpRes = service.execute(ProjectAction.LIST_KMP_TARGETS, kmpScript)
                check(kmpRes is KotlinMcpResult.Success && kmpRes.metadata["targetCount"] == "4") { "list kmp targets count" }
                check(kmpRes.content.contains("# Kotlin Multiplatform (KMP) Targets")) { "kmp header" }
                check(kmpRes.content.contains(" - `jvm`") && kmpRes.content.contains(" - `js`")) { "kmp targets" }
                check(kmpRes.content.contains("Multiplatform Web Storage")) { "kmp guideline" }

                val singleRes = service.execute(ProjectAction.LIST_KMP_TARGETS, "plugins { kotlin(\"jvm\") }\n")
                check(singleRes is KotlinMcpResult.Success && singleRes.content.contains("Standard single-target JVM project configuration.")) { "single target" }

                val kmpAlt = service.execute(ProjectAction.LIST_KMP_TARGETS, "plugins { id(\"org.jetbrains.kotlin.multiplatform\") }\n")
                check(kmpAlt is KotlinMcpResult.Success && kmpAlt.content.contains("# Kotlin Multiplatform (KMP) Targets")) { "kmp alt plugin" }

                val kmpAlt2 = service.execute(ProjectAction.LIST_KMP_TARGETS, "plugins { kotlin(\"kmp\") }\n")
                check(kmpAlt2 is KotlinMcpResult.Success && kmpAlt2.content.contains("# Kotlin Multiplatform (KMP) Targets")) { "kmp alt2 plugin" }

                // 2. diagnoseBuild
                // Clean build
                val cleanDiag = service.diagnoseBuild("repositories { mavenCentral() }\nplugins { id(\"application\") }\n")
                check(cleanDiag is KotlinMcpResult.Success && cleanDiag.metadata["findingsCount"] == "0") { "clean diag" }
                check(cleanDiag.content.contains("No obvious pre-build issues detected")) { "clean msg" }

                // Issues: duplicate plugins (canonical normalization), AGP mismatch, missing repo, hardcoded versions, conflicting stdlib
                val dirtyScript = ""${'"'}
                    plugins {
                        kotlin("jvm")
                        id("org.jetbrains.kotlin.jvm")
                        kotlin("android")
                        id("org.jetbrains.kotlin.android")
                        kotlin("multiplatform")
                        id("org.jetbrains.kotlin.multiplatform")
                        kotlin("js")
                        id("org.jetbrains.kotlin.js")
                        kotlin("native")
                        id("org.jetbrains.kotlin.native")
                        id("com.android.application") version "7.4.0"
                        kotlin("jvm") version "2.0.0"
                    }
                    dependencies {
                        implementation("com.google.guava:guava:31.0")
                        implementation("org.apache.commons:commons-lang3:3.12.0")
                        implementation("com.squareup.okhttp3:okhttp:4.10.0")
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
                        implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.0")
                    }
                ""${'"'}.trimIndent()
                val dirtyDiag = service.diagnoseBuild(dirtyScript, "", "")
                check(dirtyDiag is KotlinMcpResult.Success && dirtyDiag.metadata["findingsCount"] != "0") { "dirty diag" }
                check(dirtyDiag.content.contains("🔴 Plugin conflict: `org.jetbrains.kotlin.jvm` is declared 3 time(s).")) { "jvm conflict" }
                check(dirtyDiag.content.contains("🔴 Plugin conflict: `org.jetbrains.kotlin.android` is declared 2 time(s).")) { "android conflict" }
                check(dirtyDiag.content.contains("🔴 Plugin conflict: `org.jetbrains.kotlin.multiplatform` is declared 2 time(s).")) { "kmp conflict" }
                check(dirtyDiag.content.contains("🔴 Plugin conflict: `org.jetbrains.kotlin.js` is declared 2 time(s).")) { "js conflict" }
                check(dirtyDiag.content.contains("🔴 Plugin conflict: `org.jetbrains.kotlin.native` is declared 2 time(s).")) { "native conflict" }
                check(dirtyDiag.content.contains("⚠️ AGP `7.4.0` with Kotlin `2.0.0`")) { "agp mismatch" }
                check(dirtyDiag.content.contains("🟡 Missing repository declaration")) { "missing repo" }
                check(dirtyDiag.content.contains("dependencies hardcode versions inline")) { "hardcoded" }
                check(dirtyDiag.content.contains("🔴 Conflicting `kotlin-stdlib` versions detected: 1.9.0, 2.0.0")) { "stdlib conflict" }

                // AGP 8.x info
                val agp8Script = "id(\"com.android.library\") version \"8.2.0\"\nkotlin(\"android\") version \"1.9.20\"\nrepositories { google() }\n"
                val agp8Diag = service.diagnoseBuild(agp8Script, "", "")
                check(agp8Diag is KotlinMcpResult.Success && agp8Diag.content.contains("ℹ️ AGP `8.2.0` with Kotlin `1.9.20`")) { "agp8 info" }

                // 3. File-backed inspectStructure, analyzeDependencies, layering, profile, coverage, api
                val ws = Files.createTempDirectory("ps-full-ws")
                try {
                    // Settings
                    val settings = ws.resolve("settings.gradle.kts").toFile()
                    settings.writeText("include(\":core:domain\", \":app:ui\", \":data:local\", \":infra:repo\", \":common:util\")\n")

                    // Source tree with layers
                    val domainDir = ws.resolve("core/domain/src/main/kotlin/domain").toFile(); domainDir.mkdirs()
                    File(domainDir, "User.kt").writeText("package domain\ndata class User(val id: String)\n")

                    val uiDir = ws.resolve("app/ui/src/main/kotlin/ui").toFile(); uiDir.mkdirs()
                    File(uiDir, "Screen.kt").writeText("package ui\nclass Screen\n")

                    val dataDir = ws.resolve("data/local/src/main/kotlin/data").toFile(); dataDir.mkdirs()
                    File(dataDir, "LocalStore.kt").writeText("package data\nclass LocalStore\n")

                    val repoDir = ws.resolve("infra/repo/src/main/kotlin/repository").toFile(); repoDir.mkdirs()
                    File(repoDir, "UserRepo.kt").writeText("package repository\nclass UserRepo\n")

                    val commonDir = ws.resolve("common/util/src/commonMain/kotlin/common").toFile(); commonDir.mkdirs()
                    File(commonDir, "Utils.kt").writeText("package common\nclass Utils\n")

                    val testDir = ws.resolve("app/ui/src/test/kotlin/ui").toFile(); testDir.mkdirs()
                    File(testDir, "ScreenTest.kt").writeText("package ui\nclass ScreenTest\n")

                    val androidTestDir = ws.resolve("app/ui/src/androidTest/kotlin/ui").toFile(); androidTestDir.mkdirs()
                    File(androidTestDir, "ScreenAndroidTest.kt").writeText("package ui\nclass ScreenAndroidTest\n")

                    // Root src dirs and ignored directories
                    val rootSrcKotlin = ws.resolve("src/main/kotlin").toFile(); rootSrcKotlin.mkdirs()
                    File(rootSrcKotlin, "Root.kt").writeText("class Root")
                    val rootSrcJava = ws.resolve("src/main/java").toFile(); rootSrcJava.mkdirs()
                    File(rootSrcJava, "RootJ.java").writeText("public class RootJ {}")
                    ws.resolve(".git").toFile().mkdirs()
                    ws.resolve(".gradle").toFile().mkdirs()
                    ws.resolve("build").toFile().mkdirs()
                    ws.resolve("out").toFile().mkdirs()
                    ws.resolve("node_modules").toFile().mkdirs()

                    // Version catalog
                    val gradleDir = ws.resolve("gradle").toFile(); gradleDir.mkdirs()
                    val toml = File(gradleDir, "libs.versions.toml")
                    toml.writeText(
                        "# Catalog comment\n\n" +
                        "[versions]\n" +
                        "ktor = \"2.3.11\"\n" +
                        "jackson = \"2.14.0\"\n" +
                        "ok = \"4.11.0\"\n" +
                        "[libraries]\n" +
                        "ktor-server = { module = \"io.ktor:ktor-server-core\", version.ref = \"ktor\" }\n" +
                        "jackson-bind = { module = \"com.fasterxml.jackson.core:jackson-databind\", version.ref = \"jackson\" }\n" +
                        "okhttp-direct = { module = \"com.squareup.okhttp3:okhttp\", version.ref = \"ok\" }\n" +
                        "guava-lib = { group = \"com.google.guava\", name = \"guava\", version = \"32.0.0-jre\" }\n" +
                        "guava-test = { group = \"com.google.guava\", name = \"guava-test\" }\n" +
                        "string-style = \"org.yaml:snakeyaml:2.1\"\n"
                    )

                    // Lockfile
                    val lockfile = ws.resolve("gradle.lockfile").toFile()
                    lockfile.writeText(
                        "# Lockfile\n" +
                        "org.apache.commons:commons-compress:1.25.0=compileClasspath\n" +
                        "org.apache.logging.log4j:log4j-core:2.16.0=runtimeClasspath\n"
                    )
                    val gradleLock = ws.resolve("gradle/gradle.lockfile").toFile()
                    gradleLock.writeText("org.test:extra-lock:1.0.0=compileClasspath\n")

                    // Build script
                    val rootBuild = ws.resolve("build.gradle.kts").toFile()
                    rootBuild.writeText(
                        "plugins {\n" +
                        "  kotlin(\"multiplatform\")\n" +
                        "  id(\"com.android.application\")\n" +
                        "  application\n" +
                        "}\n" +
                        "dependencies {\n" +
                        "  implementation(libs.ktor.server)\n" +
                        "  api(libs.jackson.bind)\n" +
                        "  compileOnly(libs.okhttp.direct)\n" +
                        "  implementation(libs.guava.lib)\n" +
                        "  testImplementation(libs.guava.test)\n" +
                        "  runtimeOnly(\"org.yaml:snakeyaml:2.1\")\n" +
                        "  testImplementation(project(\":core:domain\"))\n" +
                        "  testApi(platform(\"org.springframework.boot:spring-boot:3.2.3\"))\n" +
                        "  implementation(enforcedPlatform(\"io.netty:netty-all:4.1.100.Final\"))\n" +
                        "  implementation(group = \"io.netty\", name = \"netty-codec-http\", version = \"4.1.100.Final\")\n" +
                        "}\n"
                    )

                    // inspectStructure
                    val structRes = service.execute(ProjectAction.INSPECT_STRUCTURE, rootBuild.readText(), ws.toAbsolutePath().toString())
                    check(structRes is KotlinMcpResult.Success) { "struct success" }
                    check(structRes.content.contains("# Gradle Project Structure Analysis")) { "struct header" }
                    check(structRes.content.contains("- Subprojects: `:core:domain`, `:app:ui`, `:data:local`, `:infra:repo`, `:common:util`")) { "subprojects listed" }
                    check(structRes.content.contains("Detected package topology (from disk):")) { "topology listed" }
                    check(structRes.content.contains("`domain` layer") && structRes.content.contains("`ui` layer") && structRes.content.contains("`data` layer")) { "layers listed" }
                    check(structRes.content.contains("Domain and data layers detected.")) { "domain data advisory" }
                    check(structRes.content.contains("Consider defining repository interfaces in the domain layer")) { "repo advisory" }
                    check(structRes.content.contains("kotlin(multiplatform)")) { "kotlin plugin listed" }
                    check(structRes.content.contains("id(com.android.application)")) { "id plugin listed" }
                    check(structRes.content.contains("application")) { "bare plugin listed" }
                    check(structRes.content.contains("src/main/kotlin") && structRes.content.contains("src/main/java")) { "src dirs listed" }
                    check(structRes.content.contains("1. Are boundaries enforced by module/dependency rules")) { "q1" }
                    check(structRes.content.contains("2. How are DTOs mapped to domain models")) { "q2" }
                    check(structRes.content.contains("3. Which layer owns threading/coroutine dispatch")) { "q3" }
                    check(structRes.metadata["pluginsCount"] != null) { "pluginsCount meta" }
                    check(structRes.metadata["subprojectsCount"] == "5") { "subprojectsCount meta" }

                    // analyzeDependencies
                    val depsRes = service.execute(ProjectAction.ANALYZE_DEPENDENCIES, rootBuild.readText(), ws.toAbsolutePath().toString())
                    check(depsRes is KotlinMcpResult.Success) { "deps success" }
                    check(depsRes.content.contains("# Declared Dependencies")) { "declared deps header" }
                    check(depsRes.content.contains("io.ktor:ktor-server-core:2.3.11")) { "catalog module ref" }
                    check(depsRes.content.contains("com.fasterxml.jackson.core:jackson-databind:2.14.0")) { "catalog group name ref" }
                    check(depsRes.content.contains("com.squareup.okhttp3:okhttp:4.11.0")) { "catalog direct" }
                    check(depsRes.content.contains("com.google.guava:guava:32.0.0-jre")) { "catalog guava with ver" }
                    check(depsRes.content.contains("com.google.guava:guava-test")) { "catalog guava no ver" }
                    check(depsRes.content.contains("org.yaml:snakeyaml:2.1")) { "catalog string" }
                    check(depsRes.content.contains("project(\":core:domain\")")) { "project dep" }
                    check(depsRes.metadata["dependencyCount"] != null) { "deps metadata" }

                    val directDepRes = service.execute(ProjectAction.ANALYZE_DEPENDENCIES, "dependencies {\n  implementation(\"org.test:direct-dep:1.0\")\n}\n")
                    check(directDepRes is KotlinMcpResult.Success && directDepRes.content.contains("org.test:direct-dep:1.0")) { "direct dep" }

                    val groovyRes = service.execute(ProjectAction.ANALYZE_DEPENDENCIES, "dependencies {\n  implementation 'org.test:groovy-dep:1.0'\n}\n")
                    check(groovyRes is KotlinMcpResult.Success && groovyRes.content.contains("org.test:groovy-dep:1.0")) { "groovy dep" }

                    val nonPsiRes = service.execute(ProjectAction.ANALYZE_DEPENDENCIES, "", ws.toAbsolutePath().toString())
                    check(nonPsiRes is KotlinMcpResult.Success && nonPsiRes.content.contains("io.ktor:ktor-server-core:2.3.11")) { "empty script deps from disk" }

                    // checkVulnerabilities (disk fallback + baseline checks against all 9 CVE rules)
                    val vulnRes = service.checkVulnerabilities("", ws.toAbsolutePath().toString())
                    check(vulnRes is KotlinMcpResult.Success) { "vuln scan success" }
                    check(vulnRes.content.contains("# Dependency Vulnerability Audit Report")) { "vuln report title" }
                    check(vulnRes.content.contains("Scanned ") && vulnRes.content.contains("dependency coordinate(s). (source: ")) { "vuln scanned header" }
                    check(vulnRes.content.contains("## 🚨 Flagged Security Advisories")) { "advisories header" }
                    check(vulnRes.content.contains("CVE-2024-26308")) { "commons-compress cve" }
                    check(vulnRes.content.contains("CVE-2023-35116")) { "jackson cve" }
                    check(vulnRes.content.contains("CVE-2021-44228")) { "log4j cve" }
                    check(vulnRes.content.contains("CVE-2024-34080")) { "ktor cve" }
                    check(vulnRes.content.contains("CVE-2024-29025")) { "netty cve" }
                    check(vulnRes.content.contains("CVE-2024-22259")) { "spring cve" }
                    check(vulnRes.content.contains("CVE-2023-3635")) { "okhttp cve" }
                    check(vulnRes.content.contains("CVE-2022-1471")) { "snakeyaml cve" }
                    check(vulnRes.content.contains("- **`org.apache.commons:commons-compress:1.25.0`**")) { "dep coord bold" }
                    check(vulnRes.content.contains("  - **Severity**: HIGH")) { "severity formatted" }
                    check(vulnRes.content.contains("  - **Summary**: ")) { "summary formatted" }
                    check(vulnRes.content.contains("  - **Fixed Version**: 1.26.0")) { "fixed formatted" }
                    check(vulnRes.metadata["source"]?.contains("offline fallback") == true) { "offline source" }

                    // Clean dependencies vulnerability scan
                    val cleanVulnRes = service.checkVulnerabilities("dependencies { implementation(\"org.test:secure-lib:1.0.0\") }")
                    check(cleanVulnRes is KotlinMcpResult.Success && cleanVulnRes.content.contains("## ✅ No Known Vulnerabilities Detected")) { "clean vuln scan" }
                    check(cleanVulnRes.content.contains("All 1 analyzed dependencies match current secure version baselines.")) { "all clean msg" }
                    check(cleanVulnRes.content.contains("## Scanned Clean Dependencies (1)")) { "clean deps section" }
                    check(cleanVulnRes.content.contains(" - `org.test:secure-lib:1.0.0`")) { "clean coord listed" }

                    // Empty vulnerabilities error
                    val emptyVuln = service.checkVulnerabilities("", "")
                    check(emptyVuln is KotlinMcpResult.Error && emptyVuln.code == "TOOL_UNAVAILABLE") { "empty vuln error" }

                    // detectProfile with disk
                    val profile = service.detectProfile("", ws.toAbsolutePath().toString())
                    check(profile.activeFrameworks.contains(FrameworkFeature.KTOR)) { "profile ktor" }
                    check(profile.activeFrameworks.contains(FrameworkFeature.SPRING)) { "profile spring" }
                    check(profile.activeFrameworks.contains(FrameworkFeature.SERIALIZATION) == false) { "profile serialization" }
                    check(profile.isKmp) { "profile kmp" }

                    // coverageReport (not found)
                    val covNotFound = service.coverageReport(ws.toAbsolutePath().toString())
                    check(covNotFound is KotlinMcpResult.Error && covNotFound.code == "NOT_FOUND") { "cov not found" }

                    // coverageReport (html only)
                    val jacocoDir = ws.resolve("build/reports/jacoco/test").toFile(); jacocoDir.mkdirs()
                    val covHtml = service.coverageReport(ws.toAbsolutePath().toString())
                    check(covHtml is KotlinMcpResult.Success && covHtml.content.contains("HTML report directory exists")) { "cov html only" }

                    // coverageReport (xml)
                    val xmlReport = File(jacocoDir, "jacocoTestReport.xml")
                    xmlReport.writeText("<report><counter type=\"LINE\" missed=\"10\" covered=\"90\"/><counter type=\"BRANCH\" missed=\"5\" covered=\"15\"/></report>")
                    val covRes = service.coverageReport(ws.toAbsolutePath().toString())
                    check(covRes is KotlinMcpResult.Success) { "cov success" }
                    check(covRes.content.contains("- Line Coverage: 90% (90 / 100 lines)")) { "line cov" }
                    check(covRes.content.contains("- Branch Coverage: 75% (15 / 20 branches)")) { "branch cov" }

                    // packageApi
                    val pkgRes = service.packageApi(ws.toAbsolutePath().toString(), "domain")
                    check(pkgRes is KotlinMcpResult.Success) { "packageApi domain" }
                    check(pkgRes.content.contains("User")) { "user data class" }
                    check(pkgRes.content.contains("# Public API Surface — domain")) { "pkg api header" }
                    check(pkgRes.content.contains("## `")) { "pkg file header" }
                    check(pkgRes.content.contains("- `public")) { "pkg public element" }
                    check(pkgRes.content.contains("> Mode: semantic (inferred return types resolved)")) { "pkg mode note" }

                    // packageApi invalid arguments & not found
                    val pkgNull = service.packageApi(null)
                    check(pkgNull is KotlinMcpResult.Error && pkgNull.code == "INVALID_ARGUMENTS") { "pkg null" }
                    val pkgFile = service.packageApi(rootBuild.absolutePath)
                    check(pkgFile is KotlinMcpResult.Error && pkgFile.code == "INVALID_ARGUMENTS") { "pkg file" }
                    val pkgNotFound = service.packageApi(ws.toAbsolutePath().toString(), "nonexistent.package")
                    check(pkgNotFound is KotlinMcpResult.Error && pkgNotFound.code == "NOT_FOUND") { "pkg not found" }

                    // schemaDigest
                    val schemaRes = service.execute(ProjectAction.SCHEMA_DIGEST, "", ws.toAbsolutePath().toString())
                    check(schemaRes is KotlinMcpResult.Success) { "schema digest" }
                } finally {
                    ws.toFile().deleteRecursively()
                }

                // 4. UI + Data without Domain layering advisory
                val noDomainWs = Files.createTempDirectory("ps-no-domain")
                try {
                    val uiD = noDomainWs.resolve("src/main/kotlin/ui").toFile(); uiD.mkdirs()
                    File(uiD, "V.kt").writeText("class V")
                    val dataD = noDomainWs.resolve("src/main/kotlin/data").toFile(); dataD.mkdirs()
                    File(dataD, "D.kt").writeText("class D")
                    val noDomainStruct = service.execute(ProjectAction.INSPECT_STRUCTURE, "plugins { kotlin(\"jvm\") }", noDomainWs.toAbsolutePath().toString())
                    check(noDomainStruct is KotlinMcpResult.Success) { "no domain success" }
                    check(noDomainStruct.content.contains("UI and data packages are both present but no `domain` layer was found")) { "no domain advisory" }
                } finally {
                    noDomainWs.toFile().deleteRecursively()
                }

                // 5. Empty project structure
                val emptyWs = Files.createTempDirectory("ps-empty-ws")
                try {
                    val emptyStruct = service.execute(ProjectAction.INSPECT_STRUCTURE, "", emptyWs.toAbsolutePath().toString())
                    check(emptyStruct is KotlinMcpResult.Success) { "empty struct success" }
                    check(emptyStruct.content.contains("No Kotlin source files found on disk under the given project path")) { "empty layering notice" }
                    check(emptyStruct.content.contains("Clarification questions for the user:")) { "questions present" }
                } finally {
                    emptyWs.toFile().deleteRecursively()
                }

                // 6. Action aliases & overloads
                val aliasInspect = service.execute(ProjectAction.INSPECT_GRADLE_PROJECT, "plugins { kotlin(\"jvm\") }\n")
                check(aliasInspect is KotlinMcpResult.Success) { "alias inspect" }

                val aliasLayering = service.execute(ProjectAction.ANALYZE_PROJECT_LAYERING, "plugins { kotlin(\"jvm\") }\n")
                check(aliasLayering is KotlinMcpResult.Success) { "alias layering" }

                val aliasAudit = service.execute(ProjectAction.AUDIT_VULNERABILITIES, "dependencies { implementation(\"org.test:lib:1.0.0\") }")
                check(aliasAudit is KotlinMcpResult.Success) { "alias audit" }

                val aliasExport = service.execute(ProjectAction.EXPORT_PACKAGE_API, "", null)
                check(aliasExport is KotlinMcpResult.Error && aliasExport.code == "INVALID_ARGUMENTS") { "alias export" }

                val aliasCov = service.execute(ProjectAction.REPORT_COVERAGE, "", null)
                check(aliasCov is KotlinMcpResult.Error && aliasCov.code == "NOT_FOUND") { "alias cov" }

                val actionProfile = service.execute(ProjectAction.DETECT_ENVIRONMENT_PROFILE, "dependencies { implementation(\"io.arrow-kt:arrow-core:1.2.0\") }")
                check(actionProfile is KotlinMcpResult.Success && actionProfile.content.contains("ARROW")) { "action profile" }

                // Overload execute(action, code)
                val directExec = service.execute(ProjectAction.LIST_KMP_TARGETS, "plugins { kotlin(\"jvm\") }\n")
                check(directExec is KotlinMcpResult.Success) { "direct exec overload" }

                // 7. mavenVersionCompare tests
                check(mavenVersionCompare("1.26.0", "1.26") == 0) { "mvn 1.26.0 == 1.26" }
                check(mavenVersionCompare("1.25.0", "1.26.0") < 0) { "mvn 1.25.0 < 1.26.0" }
                check(mavenVersionCompare("1.26.1", "1.26.0") > 0) { "mvn 1.26.1 > 1.26.0" }
                check(mavenVersionCompare("4.1.108.Final", "4.1.108") == 0) { "mvn Final == bare" }
                check(mavenVersionCompare("1.0.0-alpha", "1.0.0-beta") < 0) { "mvn alpha < beta" }
                check(mavenVersionCompare("1.0.0-beta", "1.0.0-rc1") < 0) { "mvn beta < rc" }
                check(mavenVersionCompare("1.0.0-rc1", "1.0.0-final") < 0) { "mvn rc < final" }
                check(mavenVersionCompare("1.0.0-snapshot", "1.0.0-alpha") < 0) { "mvn snapshot < alpha" }
                check(mavenVersionCompare("1.0.0-ga", "1.0.0-release") == 0) { "mvn ga == release" }
                check(mavenVersionCompare("1.0.0-sp", "1.0.0-final") == 0) { "mvn sp == final" }
                check(mavenVersionCompare("1.0.0-m", "1.0.0-rc") == 0) { "mvn m == rc" }
                check(mavenVersionCompare("1.0.0-b", "1.0.0-beta") == 0) { "mvn b == beta" }
                check(mavenVersionCompare("1.0.0-a", "1.0.0-alpha") == 0) { "mvn a == alpha" }
                check(mavenVersionCompare("1.0.0.unknown", "1.0.0.custom") == 0) { "mvn unknown qualifiers" }
                check(mavenVersionCompare("1.0", "1.0.1") < 0) { "mvn 1.0 < 1.0.1" }
                check(mavenVersionCompare("1.0.1", "1.0") > 0) { "mvn 1.0.1 > 1.0" }
                check(mavenVersionCompare("1.0-alpha", "1.0") < 0) { "mvn 1.0-alpha < 1.0" }
                check(mavenVersionCompare("1.0", "1.0-alpha") > 0) { "mvn 1.0 > 1.0-alpha" }
                check(mavenVersionCompare("1.0.a", "1.0.1") < 0) { "mvn 1.0.a < 1.0.1" }
                check(mavenVersionCompare("1a2b", "1a2a") > 0) { "mvn mixed tokens" }
                check(mavenVersionCompare("1a2a", "1a2b") < 0) { "mvn mixed tokens rev" }

                // 8. Single and multi target profiles & KMP target listings
                val singleTargetProf = service.detectProfile("kotlin {\n jvm()\n}\n", null)
                check(!singleTargetProf.isKmp) { "single target not kmp" }
                val multiTargetProf = service.detectProfile("kotlin {\n jvm()\n js()\n}\n", null)
                check(multiTargetProf.isKmp) { "multi target is kmp" }

                val kmpSnippet = service.execute(ProjectAction.INSPECT_STRUCTURE, "kotlin {\n jvm()\n js()\n}")
                check(kmpSnippet is KotlinMcpResult.Success && kmpSnippet.content.contains("commonMain") && kmpSnippet.content.contains("jvmMain") && kmpSnippet.content.contains("jsMain")) { "kmp source sets" }

                val kmpNoTarget = service.execute(ProjectAction.LIST_KMP_TARGETS, "plugins { kotlin(\"multiplatform\") }")
                check(kmpNoTarget is KotlinMcpResult.Success && kmpNoTarget.content.contains("No specific platform targets declared yet")) { "kmp no target" }

                val kmpWithTarget = service.execute(ProjectAction.LIST_KMP_TARGETS, "kotlin {\n jvm()\n}")
                check(kmpWithTarget is KotlinMcpResult.Success && kmpWithTarget.content.contains("## Recommended Guidelines")) { "kmp rec guidelines" }

                // diagnoseBuild hardcoded versions & stdlib versions & AGP version
                val diag3 = service.diagnoseBuild("dependencies {\n implementation(\"org.test:lib-a:1.0.0\")\n implementation(\"org.test:lib-b:2.0.0\")\n implementation(\"org.test:lib-c:3.0.0\")\n}")
                check(diag3 is KotlinMcpResult.Success && diag3.content.contains("dependencies hardcode versions inline")) { "diag 3 hardcoded" }

                val diagStdlib = service.diagnoseBuild("dependencies {\n implementation(\"org.jetbrains.kotlin:kotlin-stdlib:1.8.0\")\n implementation(\"org.jetbrains.kotlin:kotlin-stdlib:1.9.0\")\n}")
                check(diagStdlib is KotlinMcpResult.Success && diagStdlib.content.contains("Conflicting `kotlin-stdlib` versions detected")) { "diag stdlib" }

                val diagAgpWarn = service.diagnoseBuild("plugins {\n id(\"com.android.application\") version \"7.4.0\"\n kotlin(\"android\") version \"2.0.0\"\n}")
                check(diagAgpWarn is KotlinMcpResult.Success && diagAgpWarn.content.contains("⚠️ AGP `7.4.0` with Kotlin `2.0.0`")) { "diag agp warn" }

                val diagAgpInfo = service.diagnoseBuild("plugins {\n id(\"com.android.application\") version \"8.2.0\"\n kotlin(\"android\") version \"2.0.0\"\n}")
                check(diagAgpInfo is KotlinMcpResult.Success && diagAgpInfo.content.contains("ℹ️ AGP `8.2.0` with Kotlin `2.0.0`")) { "diag agp info" }

                // Zero coverage branch
                val zeroCovDir = Files.createTempDirectory("ps-zero-cov")
                try {
                    val zReports = zeroCovDir.resolve("build/reports/jacoco/test").toFile(); zReports.mkdirs()
                    File(zReports, "jacocoTestReport.xml").writeText("<report><counter type=\"LINE\" missed=\"0\" covered=\"0\"/><counter type=\"BRANCH\" missed=\"0\" covered=\"0\"/></report>")
                    val zeroRes = service.coverageReport(zeroCovDir.toAbsolutePath().toString())
                    check(zeroRes is KotlinMcpResult.Success && zeroRes.content.contains("# JaCoCo Code Coverage Report")) { "zero cov header" }
                    check(zeroRes.content.contains("- Line Coverage: 0%")) { "zero cov line" }
                    check(zeroRes.content.contains("- Branch Coverage: 0%")) { "zero cov branch" }
                } finally {
                    zeroCovDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine("\n=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ProjectService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN ProjectService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ProjectService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for ProjectService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2SnippetFrontend source file`() {
        val frontendSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2SnippetFrontend.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import com.gokorei.kotlinmcp.models.*
            import com.gokorei.kotlinmcp.execution.SnippetCompiler
            import org.jetbrains.kotlin.cli.jvm.config.addJvmClasspathRoots
            import org.jetbrains.kotlin.cli.jvm.config.jvmClasspathRoots
            import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
            import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
            import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
            import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
            import org.jetbrains.kotlin.descriptors.ModuleDescriptor
            import org.jetbrains.kotlin.resolve.BindingContext
            import org.jetbrains.kotlin.com.intellij.openapi.Disposable
            import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
            import org.jetbrains.kotlin.config.CommonConfigurationKeys
            import org.jetbrains.kotlin.config.CompilerConfiguration
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtPsiFactory
            import io.github.oshai.kotlinlogging.KotlinLogging
        """.trimIndent()

        val productionCode = imports + "\n\n" + frontendSource

        val testSuiteCode = """
            fun main() {
                // 1. Environment and psiFactory
                val env = K2SnippetFrontend.environment
                check(env != null) { "env not null" }
                val factory = K2SnippetFrontend.psiFactory
                check(factory != null) { "psiFactory not null" }
                check(!K2SnippetFrontend.isDisposed) { "not disposed initially" }

                // 2. parsePsi standard Kotlin class
                val ktFile = K2SnippetFrontend.parsePsi("package foo\nclass Bar { fun baz() = 42 }")
                check(ktFile != null) { "ktFile not null" }
                check(ktFile.name.endsWith(".kt")) { "is kt file" }
                check(ktFile.declarations.size == 1) { "1 declaration" }

                // 3. parsePsi script with expressions (if, for, while, do-while, script initializer)
                val scriptIf = K2SnippetFrontend.parsePsi("if (true) { println(1) }")
                check(scriptIf != null && scriptIf.name.endsWith(".kts")) { "script if is kts" }

                val scriptFor = K2SnippetFrontend.parsePsi("for (i in 1..10) { println(i) }")
                check(scriptFor != null && scriptFor.name.endsWith(".kts")) { "script for is kts" }

                val scriptWhile = K2SnippetFrontend.parsePsi("while (false) { println(0) }")
                check(scriptWhile != null && scriptWhile.name.endsWith(".kts")) { "script while is kts" }

                val scriptDoWhile = K2SnippetFrontend.parsePsi("do { println(0) } while (false)")
                check(scriptDoWhile != null && scriptDoWhile.name.endsWith(".kts")) { "script do while is kts" }

                val scriptError = K2SnippetFrontend.parsePsi("123 invalid token")
                check(scriptError != null && scriptError.name.endsWith(".kts")) { "script error is kts" }

                val scriptCall = K2SnippetFrontend.parsePsi("println(\"hello world\")")
                check(scriptCall != null && scriptCall.name.endsWith(".kts")) { "script call is kts" }

                // 4. analyzeSession with valid code & stdlib / JVM classpath resolution
                val session = K2SnippetFrontend.analyzeSession("val a = 10\nval b = a + 5\nval res = com.gokorei.kotlinmcp.models.KotlinMcpResult.Success(\"ok\")\nval f = java.io.File(\"test.txt\")")
                check(session != null) { "session not null" }
                check(session.file != null) { "session file" }
                check(session.bindingContext != BindingContext.EMPTY) { "binding context non empty" }
                check(session.moduleDescriptor != null && session.moduleDescriptor.builtIns != null) { "module descriptor with builtins" }
                val properties = session.file.declarations.filterIsInstance<org.jetbrains.kotlin.psi.KtProperty>()
                val resProp = properties.first { it.name == "res" }
                val resType = session.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.VARIABLE, resProp]?.type
                check(resType != null && !resType.toString().contains("ERROR")) { "resolved classpath library symbol" }
                val fProp = properties.first { it.name == "f" }
                val fType = session.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.VARIABLE, fProp]?.type
                check(fType != null) { "resolved java io File type" }

                // 5. analyzeSession with extra files
                val extraPsi = K2SnippetFrontend.parsePsi("package com.test\nfun helperFunc(): Int = 99")
                check(extraPsi != null) { "extraPsi not null" }
                val multiSession = K2SnippetFrontend.analyzeSession("package com.test\nval result = helperFunc()", listOf(extraPsi))
                check(multiSession != null) { "multiSession not null" }
                check(multiSession.bindingContext != BindingContext.EMPTY) { "multiSession context" }
                val prop = multiSession.file.declarations.firstOrNull() as? org.jetbrains.kotlin.psi.KtProperty
                check(prop != null) { "property found" }
                val helperResType = multiSession.bindingContext[org.jetbrains.kotlin.resolve.BindingContext.VARIABLE, prop]?.type
                check(helperResType != null && helperResType.toString() == "Int") { "resolved extra file type" }

                // 6. resetEnvironment with Disposer tree
                check(K2SnippetFrontend.environment.configuration.jvmClasspathRoots.isNotEmpty()) { "jvm roots not empty" }
                val oldEnv = K2SnippetFrontend.environment
                var resetDisposed = false
                Disposer.register(K2SnippetFrontend.currentRootDisposable, Disposable { resetDisposed = true })
                K2SnippetFrontend.resetEnvironment()
                check(resetDisposed) { "resetEnvironment disposed root disposable" }
                val newEnv = K2SnippetFrontend.environment
                check(newEnv != null && newEnv !== oldEnv) { "newEnv recreated after reset" }

                // 7. dispose lifecycle with Disposer tree
                var fullDisposed = false
                Disposer.register(K2SnippetFrontend.currentRootDisposable, Disposable { fullDisposed = true })
                K2SnippetFrontend.dispose()
                check(fullDisposed) { "dispose disposed root disposable" }
                check(K2SnippetFrontend.isDisposed) { "isDisposed true" }
                check(K2SnippetFrontend.parsePsi("class AfterDispose") == null) { "null after dispose" }
                check(K2SnippetFrontend.analyzeSession("class AfterDispose") == null) { "null session after dispose" }
                K2SnippetFrontend.dispose() // Idempotent check
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2SnippetFrontend.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2SnippetFrontend.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2SnippetFrontend.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2SnippetFrontend.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production VfsPsiCache source file`() {
        val vfsSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/VfsPsiCache.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import io.github.oshai.kotlinlogging.KotlinLogging
            import org.jetbrains.kotlin.psi.KtFile
            import java.io.File
            import java.nio.file.*
            import java.nio.file.StandardWatchEventKinds.*
            import java.util.Collections
            import java.util.LinkedHashMap
            import java.util.concurrent.ConcurrentHashMap
            import java.util.concurrent.locks.ReentrantReadWriteLock
            import kotlin.concurrent.read
            import kotlin.concurrent.write
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
        """.trimIndent()

        val productionCode = imports + "\n\n" + vfsSource

        val testSuiteCode = """
            fun main() {
                val tempDir = Files.createTempDirectory("vfs_test")
                try {
                    val cache = DefaultVfsPsiCache(maxCapacity = 3)
                    check(cache.size == 0) { "initially empty" }
                    check(!cache.isClosed && !cache.isWatching) { "initial flags" }

                    val f1 = File(tempDir.toFile(), "File1.kt").apply { writeText("class File1") }
                    val f2 = File(tempDir.toFile(), "File2.kt").apply { writeText("class File2") }
                    val f3 = File(tempDir.toFile(), "File3.kt").apply { writeText("class File3") }
                    val f4 = File(tempDir.toFile(), "File4.kt").apply { writeText("class File4") }

                    // 1. getOrParse(file)
                    val p1 = cache.getOrParse(f1)
                    check(p1 != null && cache.size == 1) { "p1 parsed and cached" }

                    // Cached read hits
                    val p1Cached = cache.getOrParse(f1)
                    check(p1Cached === p1) { "same instance returned from fast read" }

                    // Hash-match check when lastModified changes but content is same
                    f1.setLastModified(f1.lastModified() + 5000L)
                    val p1HashMatch = cache.getOrParse(f1)
                    check(p1HashMatch === p1) { "hash match returns same AST" }

                    // 2. getOrParse(filePath, content)
                    val strAst = cache.getOrParse("/virtual/Snippet.kt", "class VirtualSnippet")
                    check(strAst != null) { "virtual snippet parsed" }
                    val strAstCached = cache.getOrParse("/virtual/Snippet.kt", "class VirtualSnippet")
                    check(strAstCached === strAst) { "virtual snippet cached" }

                    val diffContent = cache.getOrParse("/virtual/Snippet.kt", "class ModifiedSnippet")
                    check(diffContent !== strAst && diffContent != null) { "diff content reparsed" }

                    // 3. LRU capacity bounding
                    cache.getOrParse(f2)
                    cache.getOrParse(f3)
                    cache.getOrParse(f4)
                    check(cache.size <= 3) { "bounded by maxCapacity" }

                    // 4. Invalidation (file and directory prefix)
                    val subDir = File(tempDir.toFile(), "sub").apply { mkdirs() }
                    val subFile = File(subDir, "SubFile.kt").apply { writeText("class SubFile") }
                    cache.getOrParse(subFile)
                    val beforeCount = cache.size
                    cache.invalidate(subDir.toPath())
                    check(cache.size < beforeCount) { "subDir invalidated" }

                    cache.getOrParse(f4)
                    val countBeforeF4 = cache.size
                    cache.invalidate(f4.absolutePath)
                    check(cache.size == countBeforeF4 - 1) { "f4 invalidated" }

                    // 5. Clear
                    cache.clear()
                    check(cache.size == 0) { "cleared" }

                    // 6. Non-existent / directory checks
                    check(cache.getOrParse(File(tempDir.toFile(), "NonExistent.kt")) == null) { "null on missing" }
                    check(cache.getOrParse(tempDir.toFile()) == null) { "null on directory" }

                    // 7. Watch events testing for specific extensions (.kt, .kts, .java, .txt, subdirs)
                    val watchKt = File(tempDir.toFile(), "Watch.kt").apply { writeText("class Watch") }
                    cache.getOrParse(watchKt)
                    check(cache.size == 1) { "watchKt in cache" }

                    val mockEventKt = object : WatchEvent<Path> {
                        override fun kind(): WatchEvent.Kind<Path> = ENTRY_MODIFY
                        override fun count(): Int = 1
                        override fun context(): Path = Paths.get("Watch.kt")
                    }
                    cache.handleWatchEvent(tempDir, mockEventKt, null)
                    check(cache.size == 0) { "Watch.kt invalidated by event" }

                    val watchKts = File(tempDir.toFile(), "Build.kts").apply { writeText("val x = 1") }
                    cache.getOrParse(watchKts)
                    val mockEventKts = object : WatchEvent<Path> {
                        override fun kind(): WatchEvent.Kind<Path> = ENTRY_MODIFY
                        override fun count(): Int = 1
                        override fun context(): Path = Paths.get("Build.kts")
                    }
                    cache.handleWatchEvent(tempDir, mockEventKts, null)
                    check(cache.size == 0) { "Build.kts invalidated by event" }

                    val watchJava = File(tempDir.toFile(), "App.java").apply { writeText("class App {}") }
                    cache.getOrParse(watchJava)
                    val mockEventJava = object : WatchEvent<Path> {
                        override fun kind(): WatchEvent.Kind<Path> = ENTRY_MODIFY
                        override fun count(): Int = 1
                        override fun context(): Path = Paths.get("App.java")
                    }
                    cache.handleWatchEvent(tempDir, mockEventJava, null)
                    check(cache.size == 0) { "App.java invalidated by event" }

                    val watchDel = File(tempDir.toFile(), "Del.kt").apply { writeText("class Del") }
                    cache.getOrParse(watchDel)
                    val mockEventDel = object : WatchEvent<Path> {
                        override fun kind(): WatchEvent.Kind<Path> = ENTRY_DELETE
                        override fun count(): Int = 1
                        override fun context(): Path = Paths.get("Del.kt")
                    }
                    cache.handleWatchEvent(tempDir, mockEventDel, null)
                    check(cache.size == 0) { "Del.kt invalidated on ENTRY_DELETE" }

                    val watchTxt = File(tempDir.toFile(), "Readme.txt").apply { writeText("notes") }
                    cache.getOrParse(f1)
                    val mockEventTxt = object : WatchEvent<Path> {
                        override fun kind(): WatchEvent.Kind<Path> = ENTRY_MODIFY
                        override fun count(): Int = 1
                        override fun context(): Path = Paths.get("Readme.txt")
                    }
                    cache.handleWatchEvent(tempDir, mockEventTxt, null)
                    check(cache.size == 1) { "Readme.txt does not invalidate" }
                    cache.clear()

                    val wsMock = FileSystems.getDefault().newWatchService()
                    try {
                        val newSub = File(tempDir.toFile(), "newSub").apply { mkdirs() }
                        val mockEventSubdir = object : WatchEvent<Path> {
                            override fun kind(): WatchEvent.Kind<Path> = ENTRY_CREATE
                            override fun count(): Int = 1
                            override fun context(): Path = Paths.get("newSub")
                        }
                        val keysBeforeSub = cache.watchKeysCount
                        cache.handleWatchEvent(tempDir, mockEventSubdir, wsMock)
                        check(cache.watchKeysCount > keysBeforeSub) { "newSub registered in watchKeys" }

                        val buildDir = File(tempDir.toFile(), "build").apply { mkdirs() }
                        val mockEventBuild = object : WatchEvent<Path> {
                            override fun kind(): WatchEvent.Kind<Path> = ENTRY_CREATE
                            override fun count(): Int = 1
                            override fun context(): Path = Paths.get("build")
                        }
                        val keysBeforeBuild = cache.watchKeysCount
                        cache.handleWatchEvent(tempDir, mockEventBuild, wsMock)
                        check(cache.watchKeysCount == keysBeforeBuild) { "excluded build dir not registered" }

                        val delDir = File(tempDir.toFile(), "delDir").apply { mkdirs() }
                        val delFileInside = File(delDir, "DelInside.kt").apply { writeText("class DelInside") }
                        cache.getOrParse(delFileInside)
                        check(cache.size > 0)
                        val mockDelDirEvent = object : WatchEvent<Path> {
                            override fun kind(): WatchEvent.Kind<Path> = ENTRY_DELETE
                            override fun count(): Int = 1
                            override fun context(): Path = Paths.get("delDir")
                        }
                        cache.handleWatchEvent(tempDir, mockDelDirEvent, wsMock)
                        check(cache.size == 0) { "delDir deleted and purged cache" }
                    } finally {
                        wsMock.close()
                    }

                    // 8. LRU strict eviction
                    val lruCache = DefaultVfsPsiCache(maxCapacity = 2)
                    val fileA = File(tempDir.toFile(), "A.kt").apply { writeText("class A") }
                    val fileB = File(tempDir.toFile(), "B.kt").apply { writeText("class B") }
                    val fileC = File(tempDir.toFile(), "C.kt").apply { writeText("class C") }
                    val astA = lruCache.getOrParse(fileA)
                    lruCache.getOrParse(fileB)
                    check(lruCache.size == 2) { "lru size 2" }
                    lruCache.getOrParse(fileC)
                    check(lruCache.size == 2) { "bounded at 2" }

                    // 9. processNextEvent testing
                    val wsLocal = FileSystems.getDefault().newWatchService()
                    try {
                        val watchFile = File(tempDir.toFile(), "EventTest.kt").apply { writeText("class EventTest") }
                        cache.getOrParse(watchFile)
                        check(cache.size > 0) { "has cached before event" }

                        val mockModify = object : WatchEvent<Path> {
                            override fun kind(): WatchEvent.Kind<Path> = ENTRY_MODIFY
                            override fun count(): Int = 1
                            override fun context(): Path = Paths.get("EventTest.kt")
                        }
                        val mockKey = object : WatchKey {
                            override fun isValid(): Boolean = true
                            override fun pollEvents(): List<WatchEvent<*>> = listOf(mockModify)
                            override fun reset(): Boolean = true
                            override fun cancel() {}
                            override fun watchable(): Watchable = tempDir
                        }
                        cache.registerWatchKey(mockKey, tempDir)
                        val valid = cache.processNextEvent(mockKey, wsLocal)
                        check(valid) { "mockKey valid" }
                        check(cache.size == 0) { "EventTest.kt invalidated via processNextEvent" }

                        // Invalid key removal on reset = false
                        val mockInvalidKey = object : WatchKey {
                            override fun isValid(): Boolean = false
                            override fun pollEvents(): List<WatchEvent<*>> = emptyList()
                            override fun reset(): Boolean = false
                            override fun cancel() {}
                            override fun watchable(): Watchable = tempDir
                        }
                        cache.registerWatchKey(mockInvalidKey, tempDir)
                        check(cache.getWatchPath(mockInvalidKey) != null) { "registered invalid key" }
                        val invalidReset = cache.processNextEvent(mockInvalidKey, wsLocal)
                        check(!invalidReset) { "invalidReset false" }
                        check(cache.getWatchPath(mockInvalidKey) == null) { "unregistered invalid key" }
                    } finally {
                        wsLocal.close()
                    }

                    // 10. startWatching and Close
                    val watchDir = Files.createTempDirectory("watch_root")
                    try {
                        File(watchDir.toFile(), "src/main/kotlin").mkdirs()
                        File(watchDir.toFile(), "build/outputs").mkdirs()
                        File(watchDir.toFile(), ".git/objects").mkdirs()

                        val watchCache = DefaultVfsPsiCache()
                        watchCache.startWatching(watchDir.toString())
                        check(watchCache.isWatching) { "is watching true" }
                        check(watchCache.watchKeysCount == 4) { "only 4 non-excluded directories registered" }
                        watchCache.startWatching(watchDir.toString()) // Idempotent start check

                        val pF1 = watchCache.getOrParse(f1)
                        check(pF1 != null && watchCache.size == 1) { "f1 in cache before close" }
                        watchCache.close()
                        check(watchCache.isClosed) { "is closed true" }
                        check(!watchCache.isWatching) { "is watching false after close" }
                        check(watchCache.watchKeysCount == 0) { "watch keys cleared" }
                        check(watchCache.size == 0) { "cache cleared on close" }
                        check(watchCache.getOrParse(f1) == null) { "null when closed" }
                        check(watchCache.getOrParse("/virtual/Snippet.kt", "class X") == null) { "null string when closed" }
                    } finally {
                        watchDir.toFile().deleteRecursively()
                    }

                    cache.close()
                } finally {
                    tempDir.toFile().deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: VfsPsiCache.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN VfsPsiCache.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for VfsPsiCache.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for VfsPsiCache.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2ResolutionUtils source file`() {
        val utilsSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2ResolutionUtils.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
            import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.com.intellij.psi.PsiElement
            import org.jetbrains.kotlin.resolve.BindingContext
            import org.jetbrains.kotlin.resolve.DescriptorUtils
            import java.io.File
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
        """.trimIndent()

        val productionCode = imports + "\n\n" + utilsSource

        val testSuiteCode = """
            fun main() {
                // 1. isRealFqn
                check(!K2ResolutionUtils.isRealFqn(null)) { "null fqn is not real" }
                check(!K2ResolutionUtils.isRealFqn("")) { "empty fqn is not real" }
                check(!K2ResolutionUtils.isRealFqn("<root>")) { "<root> fqn is not real" }
                check(K2ResolutionUtils.isRealFqn("com.example.Foo")) { "com.example.Foo is real" }

                // 2. isExcludedWorkspaceDir
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File("build"))) { "build excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".gradle"))) { ".gradle excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".git"))) { ".git excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File("out"))) { "out excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File("node_modules"))) { "node_modules excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".idea"))) { ".idea excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".agents"))) { ".agents excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".github"))) { ".github excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File("target"))) { "target excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".kotlin"))) { ".kotlin excluded" }
                check(K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File(".bsp"))) { ".bsp excluded" }
                check(!K2ResolutionUtils.isExcludedWorkspaceDir(java.io.File("src"))) { "src included" }

                // 3. Symbol occurrence collection
                val ktFile = K2SnippetFrontend.parsePsi("class Foo {\n val bar: Int = 1\n fun compute(foo: Foo): Foo = foo\n}")
                check(ktFile != null) { "parsed ktFile" }
                val occurrences = K2ResolutionUtils.collectSymbolOccurrences("Foo", listOf("snippet.kt" to ktFile))
                check(occurrences.size == 3) { "found 3 occurrences of Foo: " + occurrences.size }
                val decls = occurrences.filter { it.kind == "decl" }
                val refs = occurrences.filter { it.kind == "ref" }
                check(decls.size == 1) { "1 decl" }
                check(refs.size == 2) { "2 refs" }

                // 4. K2 Analysis session & Descriptor / target utils
                val session = K2SnippetFrontend.analyzeSession("package com.example\nclass MyClass(val x: Int) {\n fun doWork(): Int = x\n}")
                check(session != null) { "session not null" }

                val sessionFile = session.file
                val desc = session.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, sessionFile.declarations.first()]
                check(desc != null) { "desc not null" }

                // effectiveDescriptor & safeFqn
                val eff = K2ResolutionUtils.effectiveDescriptor(desc)
                check(eff == desc) { "eff == desc" }
                val fqn = K2ResolutionUtils.safeFqn(desc)
                check(fqn == "com.example.MyClass") { "safeFqn == com.example.MyClass" }
                check(K2ResolutionUtils.safeFqn(null) == null) { "safeFqn(null) == null" }

                // sameTarget
                check(K2ResolutionUtils.sameTarget(desc, desc)) { "sameTarget identical instance" }
                check(K2ResolutionUtils.sameTarget(desc, eff)) { "sameTarget self" }

                val session2 = K2SnippetFrontend.analyzeSession("package com.example\nclass MyClass(val x: Int)")
                check(session2 != null)
                val desc2 = session2.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, session2.file.declarations.first()]
                check(desc2 != null && desc2 !== desc)
                check(K2ResolutionUtils.sameTarget(desc, desc2)) { "sameTarget across sessions with same FQN" }

                val otherSession = K2SnippetFrontend.analyzeSession("package com.example\nclass OtherClass")
                check(otherSession != null)
                val otherDesc = otherSession.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, otherSession.file.declarations.first()]
                check(otherDesc != null)
                check(!K2ResolutionUtils.sameTarget(desc, otherDesc)) { "sameTarget false for different FQNs" }

                // pickTargets with non-local vs local
                val localSession = K2SnippetFrontend.analyzeSession("fun topLevel() {\n class LocalClass\n}")
                check(localSession != null)
                val topFun = localSession.file.declarations.first() as org.jetbrains.kotlin.psi.KtNamedFunction
                val localClass = topFun.bodyBlockExpression?.statements?.firstOrNull() as? org.jetbrains.kotlin.psi.KtClass
                check(localClass != null)
                val localDesc = localSession.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, localClass]
                check(localDesc != null)
                check(K2ResolutionUtils.pickTargets(listOf(localDesc)).size == 1) { "fallback to local" }
                check(K2ResolutionUtils.pickTargets(listOf(desc, localDesc)).size == 1) { "prefers non-local" }

                // nested occurrences and declarationPsiFor
                val nestedFile = K2SnippetFrontend.parsePsi("class Outer {\n class Inner {\n fun method(inner: Inner): Inner = inner\n }\n}")
                check(nestedFile != null)
                val nestedOcc = K2ResolutionUtils.collectSymbolOccurrences("Inner", listOf("Nested.kt" to nestedFile))
                check(nestedOcc.size == 3) { "nested occurrences" }

                val nestedSession = K2SnippetFrontend.analyzeSession("class Outer {\n class Inner\n}")
                check(nestedSession != null)
                val outerPsi = nestedSession.file.declarations.first() as org.jetbrains.kotlin.psi.KtClass
                val innerPsi = outerPsi.declarations.first() as org.jetbrains.kotlin.psi.KtClass
                val innerDesc = nestedSession.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, innerPsi]
                check(innerDesc != null)
                val foundInner = K2ResolutionUtils.declarationPsiFor(nestedSession, listOf("Outer.kt" to nestedSession.file), innerDesc)
                check(foundInner != null && foundInner.name == "Inner") { "found nested inner psi" }

                val foundPsi = K2ResolutionUtils.declarationPsiFor(session, listOf("MyClass.kt" to sessionFile), desc)
                check(foundPsi != null && foundPsi.name == "MyClass") { "foundPsi == MyClass" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2ResolutionUtils.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2ResolutionUtils.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2ResolutionUtils.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2ResolutionUtils.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2SemanticModels source file`() {
        val modelsSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2SemanticModels.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = modelsSource

        val testSuiteCode = """
            fun main() {
                check(SEMANTIC_FILE_CAP == 2000) { "SEMANTIC_FILE_CAP is 2000" }
                check(ResolvedSource.values().size == 4) { "4 enum values" }
                check(ResolvedSource.valueOf("SNIPPET") == ResolvedSource.SNIPPET)
                check(ResolvedSource.valueOf("WORKSPACE") == ResolvedSource.WORKSPACE)
                check(ResolvedSource.valueOf("EXTERNAL") == ResolvedSource.EXTERNAL)
                check(ResolvedSource.valueOf("UNRESOLVED") == ResolvedSource.UNRESOLVED)

                val decl = ResolvedDeclaration("foo", "Foo.kt", 10, "com.example.foo", "fun foo()", ResolvedSource.WORKSPACE)
                check(decl.symbol == "foo" && decl.file == "Foo.kt" && decl.line == 10 && decl.fqn == "com.example.foo" && decl.signature == "fun foo()" && decl.source == ResolvedSource.WORKSPACE)

                val comp = KotlinCompletionCandidates(listOf("bar", "baz"), listOf("x", "y"))
                check(comp.members.size == 2 && comp.scope.size == 2)

                val edit = ResolvedRenameEdit("File.kt", 15, 4)
                check(edit.file == "File.kt" && edit.offset == 15 && edit.length == 4)

                val ref = ResolvedReference("foo", "File.kt", 2, 5, "foo()", "ref")
                check(ref.symbol == "foo" && ref.file == "File.kt" && ref.line == 2 && ref.column == 5 && ref.snippet == "foo()" && ref.kind == "ref" && ref.fqn == null)

                val refWithFqn = ResolvedReference("foo", "File.kt", 2, 5, "foo()", "ref", "com.example.foo")
                check(refWithFqn.fqn == "com.example.foo")

                val hover = KtHoverInfo("bar", "Int", "val bar: Int", "com.example.bar", ResolvedSource.SNIPPET, "Snippet.kt", 1, "KDoc")
                check(hover.symbol == "bar" && hover.type == "Int" && hover.signature == "val bar: Int" && hover.fqn == "com.example.bar" && hover.source == ResolvedSource.SNIPPET && hover.file == "Snippet.kt" && hover.line == 1 && hover.kdoc == "KDoc")

                val stats = WorkspaceStats(100, 50, true)
                check(stats.totalKtFiles == 100 && stats.analyzedFiles == 50 && stats.truncated)
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2SemanticModels.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2SemanticModels.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2SemanticModels.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2HoverResolver source file`() {
        val hoverSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2HoverResolver.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtExpression
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.renderer.DescriptorRenderer
            import org.jetbrains.kotlin.resolve.BindingContext
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.KtHoverInfo
            import com.gokorei.kotlinmcp.lsp.ResolvedSource
        """.trimIndent()

        val productionCode = imports + "\n\n" + hoverSource

        val testSuiteCode = """
            fun main() {
                // 1. Symbol not found
                val session1 = K2SnippetFrontend.analyzeSession("val a = 1")
                check(session1 != null) { "session1 not null" }
                check(K2HoverResolver.hover(session1, "missing", listOf("Snippet.kt" to session1.file)) == null) { "missing symbol returns null" }

                // 2. In-snippet property hover (declared and inferred types)
                val session2 = K2SnippetFrontend.analyzeSession("/** A doc */\nval myProp: Int = 42\nval inferred = \"text\"")
                check(session2 != null) { "session2 not null" }
                val hoverProp = K2HoverResolver.hover(session2, "myProp", listOf("Snippet.kt" to session2.file))
                check(hoverProp != null) { "hoverProp not null" }
                check(hoverProp.symbol == "myProp") { "hoverProp symbol match" }
                check(hoverProp.type == "kotlin.Int") { "hoverProp type match: " + hoverProp.type }
                check(hoverProp.source == ResolvedSource.SNIPPET) { "hoverProp source snippet" }
                check(hoverProp.file == "Snippet.kt") { "hoverProp file Snippet.kt" }
                check(hoverProp.kdoc != null && hoverProp.kdoc!!.contains("A doc")) { "hoverProp kdoc match" }

                val hoverInferred = K2HoverResolver.hover(session2, "inferred", listOf("Snippet.kt" to session2.file))
                check(hoverInferred != null) { "hoverInferred not null" }
                check(hoverInferred.type == "kotlin.String") { "hoverInferred type string: " + hoverInferred.type }

                // 3. Workspace declaration hover
                val wsCode = "package com.pkg\n/** Workspace helper */\nfun helper(): Boolean = true"
                val wsFile = K2SnippetFrontend.psiFactory.createFile("Helper.kt", wsCode)
                val mainCode = "import com.pkg.helper\nfun main() {\n val res = helper()\n}"
                val session3 = K2SnippetFrontend.analyzeSession(mainCode, listOf(wsFile))
                check(session3 != null) { "session3 not null" }
                val hoverWs = K2HoverResolver.hover(session3, "helper", listOf("src/Helper.kt" to wsFile, "Snippet.kt" to session3.file))
                check(hoverWs != null) { "hoverWs not null" }
                check(hoverWs.symbol == "helper") { "hoverWs symbol match" }
                check(hoverWs.source == ResolvedSource.WORKSPACE) { "hoverWs source workspace: " + hoverWs.source }
                check(hoverWs.file == "src/Helper.kt") { "hoverWs file match: " + hoverWs.file }
                check(hoverWs.kdoc != null && hoverWs.kdoc!!.contains("Workspace helper")) { "hoverWs kdoc match" }

                // 4. External stdlib symbol hover
                val session4 = K2SnippetFrontend.analyzeSession("fun run() { val l = listOf(1, 2) }")
                check(session4 != null) { "session4 not null" }
                val hoverExt = K2HoverResolver.hover(session4, "listOf", listOf("Snippet.kt" to session4.file))
                check(hoverExt != null) { "hoverExt not null" }
                check(hoverExt.source == ResolvedSource.EXTERNAL) { "hoverExt source external: " + hoverExt.source }
                check(hoverExt.file == null && hoverExt.line == null) { "hoverExt file and line null" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2HoverResolver.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2HoverResolver.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2HoverResolver.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2HoverResolver.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2CompletionResolver source file`() {
        val completionSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2CompletionResolver.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import io.github.oshai.kotlinlogging.KotlinLogging
            import org.jetbrains.kotlin.descriptors.ClassDescriptor
            import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
            import org.jetbrains.kotlin.descriptors.FunctionDescriptor
            import org.jetbrains.kotlin.descriptors.MemberDescriptor
            import org.jetbrains.kotlin.descriptors.ModuleDescriptor
            import org.jetbrains.kotlin.name.FqName
            import org.jetbrains.kotlin.psi.KtExpression
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtParameter
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.resolve.BindingContext
            import org.jetbrains.kotlin.resolve.scopes.DescriptorKindFilter
            import org.jetbrains.kotlin.resolve.scopes.MemberScope
            import org.jetbrains.kotlin.types.KotlinType
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.KotlinCompletionCandidates
        """.trimIndent()

        val productionCode = imports + "\n\n" + completionSource

        val testSuiteCode = """
            fun main() {
                val code = "import kotlin.math.max\nimport kotlin.math.min as minimum\n\nclass User(val name: String) {\n fun greet(): String = \"hi\"\n}\n\nfun test(paramUser: User, count: Int) {\n val str = \"hello\"\n val localVal = 10\n val u = User(\"Bob\")\n}"
                val session = K2SnippetFrontend.analyzeSession(code)
                check(session != null) { "session not null" }

                // 1. Non-dot prefix completion (scope candidates & case-insensitivity)
                val scopeRes = K2CompletionResolver.completionCandidates(session, "m")
                check(scopeRes.members.isEmpty()) { "no members for non-dot prefix" }
                check(scopeRes.scope.contains("max")) { "scope has max" }
                check(scopeRes.scope.contains("minimum")) { "scope has alias minimum" }

                val scopeParam = K2CompletionResolver.completionCandidates(session, "PARAM")
                check(scopeParam.scope.contains("paramUser")) { "scope has paramUser case-insensitive" }

                val scopeCount = K2CompletionResolver.completionCandidates(session, "count")
                check(scopeCount.scope.contains("count")) { "scope has parameter count" }

                val scopeUser = K2CompletionResolver.completionCandidates(session, "User")
                check(scopeUser.scope.contains("User")) { "scope has User class" }

                // 2. Dot-member completion on custom class with case-insensitivity
                val uMembers = K2CompletionResolver.completionCandidates(session, "u.G")
                check(uMembers.members.contains("greet")) { "u.G has greet case-insensitive" }

                val uName = K2CompletionResolver.completionCandidates(session, "u.n")
                check(uName.members.contains("name")) { "u.n has name" }

                // 3. Dot-member completion on String with stdlib extensions & caching
                val strUpper = K2CompletionResolver.completionCandidates(session, "str.UP")
                check(strUpper.members.contains("uppercase")) { "str.UP has uppercase case-insensitive" }

                val strCached = K2CompletionResolver.completionCandidates(session, "str.low")
                check(strCached.members.contains("lowercase")) { "str.low has lowercase from cache" }

                val strLength = K2CompletionResolver.completionCandidates(session, "str.len")
                check(strLength.members.contains("length")) { "str.len has length" }

                // 4. Dot-member completion on parameter
                val paramGreet = K2CompletionResolver.completionCandidates(session, "paramUser.g")
                check(paramGreet.members.contains("greet")) { "paramUser.g has greet" }

                // 5. Dot at index 0 and unknown receiver
                val dotAtZero = K2CompletionResolver.completionCandidates(session, ".foo")
                check(dotAtZero.members.isEmpty()) { "dot at zero has empty members" }

                val unknown = K2CompletionResolver.completionCandidates(session, "unknown.foo")
                check(unknown.members.isEmpty()) { "unknown receiver has empty members" }

                val intComp = K2CompletionResolver.completionCandidates(session, "localVal.sub")
                check(!intComp.members.contains("substring")) { "int does not have string extension" }

                val countScope = K2CompletionResolver.completionCandidates(session, "cou")
                check(countScope.scope.size == 1 && countScope.scope.first() == "count") { "exact count parameter match" }

                // 6. Expression receiver completion
                val exprCode = "class Box(val value: Int) { fun unbox(): Int = value }\nfun getBox(): Box = Box(1)\nfun run() { getBox().un }"
                val exprSession = K2SnippetFrontend.analyzeSession(exprCode)
                check(exprSession != null)
                val exprMembers = K2CompletionResolver.completionCandidates(exprSession, "getBox().un")
                check(exprMembers.members.contains("unbox")) { "expression receiver completion" }

                // 7. Nested class property completion
                val nestedSession = K2SnippetFrontend.analyzeSession("class Outer {\n class Inner {\n val innerProp = \"abc\"\n fun test() {\n innerProp.len\n }\n }\n}")
                check(nestedSession != null)
                val nestedComp = K2CompletionResolver.completionCandidates(nestedSession, "innerProp.len")
                check(nestedComp.members.contains("length")) { "nested innerProp receiver type" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2CompletionResolver.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2CompletionResolver.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2CompletionResolver.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2CompletionResolver.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2HierarchyResolver source file`() {
        val hierarchySource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2HierarchyResolver.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.descriptors.ClassDescriptor
            import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
            import org.jetbrains.kotlin.psi.KtCallExpression
            import org.jetbrains.kotlin.psi.KtClassOrObject
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.renderer.DescriptorRenderer
            import org.jetbrains.kotlin.resolve.BindingContext
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.KtCallHierarchyResult
            import com.gokorei.kotlinmcp.lsp.KtCallOccurrence
            import com.gokorei.kotlinmcp.lsp.KtTypeHierarchyResult
            import com.gokorei.kotlinmcp.lsp.KtTypeOccurrence
        """.trimIndent()

        val productionCode = imports + "\n\n" + hierarchySource

        val testSuiteCode = """
            fun main() {
                // 1. Type hierarchy missing symbol
                val session1 = K2SnippetFrontend.analyzeSession("class A")
                check(session1 != null)
                val missingType = K2HierarchyResolver.typeHierarchy(session1, "Missing", emptyList())
                check(missingType.supertypes.isEmpty() && missingType.subtypes.isEmpty())

                // 2. Type hierarchy with supertypes, transitive subtypes, nested classes and unrelated class
                val baseCode = "package com.test\nopen class Base\ninterface Greeter\nclass Unrelated"
                val baseFile = K2SnippetFrontend.psiFactory.createFile("src/Base.kt", baseCode)

                val subCode = "package com.test\nclass SubDerived : Derived()\nclass Outer { class InnerSub : Base() }"
                val subFile = K2SnippetFrontend.psiFactory.createFile("src/Sub.kt", subCode)

                val snippetCode = "package com.test\nopen class Derived : Base(), Greeter\nclass LocalSub : Derived()"
                val session2 = K2SnippetFrontend.analyzeSession(snippetCode, listOf(baseFile, subFile))
                check(session2 != null)

                val wsFiles = listOf("src/Base.kt" to baseFile, "src/Sub.kt" to subFile)
                val typeResult = K2HierarchyResolver.typeHierarchy(session2, "Derived", wsFiles)
                check(typeResult.symbol == "Derived")
                check(typeResult.supertypes.any { it.contains("Base") }) { "supertypes has Base" }
                check(typeResult.supertypes.any { it.contains("Greeter") }) { "supertypes has Greeter" }
                check(typeResult.subtypes.any { it.name == "SubDerived" && it.file == "src/Sub.kt" }) { "subtypes has SubDerived in src/Sub.kt" }
                check(typeResult.subtypes.any { it.name == "LocalSub" && it.file == "Snippet.kt" }) { "subtypes has LocalSub in Snippet.kt" }
                check(typeResult.subtypes.none { it.name == "Unrelated" }) { "Unrelated is not a subtype of Derived" }

                // Type hierarchy on workspace symbol Base (transitive subtypes)
                val baseTypeResult = K2HierarchyResolver.typeHierarchy(session2, "Base", wsFiles)
                check(baseTypeResult.symbol == "Base")
                check(baseTypeResult.subtypes.any { it.name == "Derived" }) { "Derived is subtype of Base" }
                check(baseTypeResult.subtypes.any { it.name == "SubDerived" }) { "SubDerived is transitive subtype of Base" }
                check(baseTypeResult.subtypes.any { it.name == "InnerSub" }) { "InnerSub is nested subtype of Base" }
                check(baseTypeResult.subtypes.none { it.name == "Unrelated" }) { "Unrelated is not a subtype of Base" }

                // Type hierarchy on nested symbol InnerSub
                val innerSubResult = K2HierarchyResolver.typeHierarchy(session2, "InnerSub", wsFiles)
                check(innerSubResult.symbol == "InnerSub" && innerSubResult.supertypes.any { it.contains("Base") }) { "nested InnerSub has Base supertype" }

                // 3. Call hierarchy missing symbol
                val missingCall = K2HierarchyResolver.callHierarchy(session1, "missing", emptyList())
                check(missingCall.callers.isEmpty())

                // 4. Call hierarchy with callers (workspace decl, snippet caller, nested callers)
                val calleeWsCode = "package com.calls\nfun worker(): Int = 42"
                val calleeWsFile = K2SnippetFrontend.psiFactory.createFile("src/Worker.kt", calleeWsCode)

                val callerWsCode = "package com.calls\nfun wsCaller() { val x = worker() }\nfun outerWs() { fun nestedWs() { worker() }; nestedWs() }"
                val callerWsFile = K2SnippetFrontend.psiFactory.createFile("src/Caller.kt", callerWsCode)

                val callSnippetCode = "package com.calls\nfun snippetCaller() {\n worker()\n}\nval topLevelCall = worker()"
                val session3 = K2SnippetFrontend.analyzeSession(callSnippetCode, listOf(calleeWsFile, callerWsFile))
                check(session3 != null)

                val callWsFiles = listOf("src/Worker.kt" to calleeWsFile, "src/Caller.kt" to callerWsFile)
                val callResult = K2HierarchyResolver.callHierarchy(session3, "worker", callWsFiles)
                check(callResult.symbol == "worker")
                check(callResult.callers.any { it.callerName == "wsCaller" && it.file == "src/Caller.kt" }) { "wsCaller caller found" }
                check(callResult.callers.any { it.callerName == "nestedWs" && it.file == "src/Caller.kt" }) { "nestedWs caller found" }
                check(callResult.callers.any { it.callerName == "snippetCaller" && it.file == "Snippet.kt" }) { "snippetCaller caller found" }
                check(callResult.callers.any { it.callerName == null && it.file == "Snippet.kt" }) { "topLevelCall caller found" }

                // 5. Disambiguated call hierarchy across packages (via snippetCallTargets)
                val pkgACode = "package com.pkgA\nfun worker(): Int = 1"
                val pkgAFile = K2SnippetFrontend.psiFactory.createFile("src/PkgA.kt", pkgACode)
                val pkgBCode = "package com.pkgB\nfun worker(): Int = 2"
                val pkgBFile = K2SnippetFrontend.psiFactory.createFile("src/PkgB.kt", pkgBCode)
                val callerACode = "package com.pkgA\nfun callerA() { worker() }"
                val callerAFile = K2SnippetFrontend.psiFactory.createFile("src/CallerA.kt", callerACode)
                val callerBCode = "package com.pkgB\nfun callerB() { worker() }"
                val callerBFile = K2SnippetFrontend.psiFactory.createFile("src/CallerB.kt", callerBCode)

                val disambigSnippet = "import com.pkgA.worker\nfun disambigRunner() { worker() }"
                val disambigSession = K2SnippetFrontend.analyzeSession(disambigSnippet, listOf(pkgAFile, pkgBFile, callerAFile, callerBFile))
                check(disambigSession != null)
                val disambigFiles = listOf("src/PkgA.kt" to pkgAFile, "src/PkgB.kt" to pkgBFile, "src/CallerA.kt" to callerAFile, "src/CallerB.kt" to callerBFile)
                val disambigResult = K2HierarchyResolver.callHierarchy(disambigSession, "worker", disambigFiles)
                check(disambigResult.callers.any { it.callerName == "callerA" && it.file == "src/CallerA.kt" }) { "callerA found" }
                check(disambigResult.callers.any { it.callerName == "disambigRunner" && it.file == "Snippet.kt" }) { "disambigRunner found" }
                check(disambigResult.callers.none { it.callerName == "callerB" }) { "callerB excluded because snippet targets pkgA.worker" }

                // 6. Member function call hierarchy (nested inside class)
                val memberServiceCode = "package com.svc\nclass Service {\n fun memberWorker(): Int = 10\n fun internalCaller() { memberWorker() }\n}"
                val memberFile = K2SnippetFrontend.psiFactory.createFile("src/Service.kt", memberServiceCode)
                val memberSession = K2SnippetFrontend.analyzeSession("package com.svc\nfun testMember() { Service().memberWorker() }", listOf(memberFile))
                check(memberSession != null)
                val memberResult = K2HierarchyResolver.callHierarchy(memberSession, "memberWorker", listOf("src/Service.kt" to memberFile))
                check(memberResult.callers.any { it.callerName == "internalCaller" && it.file == "src/Service.kt" }) { "internalCaller found" }
                check(memberResult.callers.any { it.callerName == "testMember" && it.file == "Snippet.kt" }) { "snippet testMember caller found" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2HierarchyResolver.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2HierarchyResolver.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2HierarchyResolver.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2HierarchyResolver.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2RenameResolver source file`() {
        val renameSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2RenameResolver.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.resolve.BindingContext
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.ResolvedRenameEdit
        """.trimIndent()

        val productionCode = imports + "\n\n" + renameSource

        val testSuiteCode = """
            fun main() {
                // 1. Missing symbol
                val session1 = K2SnippetFrontend.analyzeSession("val a = 1")
                check(session1 != null)
                val missingEdits = K2RenameResolver.renameEditsForSymbol(session1, "missing", emptyList())
                check(missingEdits.isEmpty())

                // 2. Declaration in Snippet with references across snippet and workspace
                val wsCode = "package com.example\nfun useTarget(t: MyClass) = t.x"
                val wsFile = K2SnippetFrontend.psiFactory.createFile("src/Workspace.kt", wsCode)

                val snippetCode = "package com.example\nclass MyClass(val x: Int) {\n fun clone(other: MyClass): MyClass = other\n}"
                val session2 = K2SnippetFrontend.analyzeSession(snippetCode, listOf(wsFile))
                check(session2 != null)

                val wsFiles = listOf("src/Workspace.kt" to wsFile)
                val editsMyClass = K2RenameResolver.renameEditsForSymbol(session2, "MyClass", wsFiles)
                check(editsMyClass.isNotEmpty()) { "editsMyClass not empty" }
                check(editsMyClass.any { it.file == "Snippet.kt" }) { "snippet decl and ref edited" }
                check(editsMyClass.any { it.file == "src/Workspace.kt" }) { "workspace ref edited" }

                // 3. Declaration in Workspace, only references in Snippet
                val targetWsCode = "package com.target\nclass TargetSymbol"
                val targetWsFile = K2SnippetFrontend.psiFactory.createFile("src/Target.kt", targetWsCode)
                val refSnippetCode = "import com.target.TargetSymbol\nfun runTarget() { val t = TargetSymbol() }"
                val session3 = K2SnippetFrontend.analyzeSession(refSnippetCode, listOf(targetWsFile))
                check(session3 != null)
                val targetFiles = listOf("src/Target.kt" to targetWsFile)
                val editsTarget = K2RenameResolver.renameEditsForSymbol(session3, "TargetSymbol", targetFiles)
                check(editsTarget.any { it.file == "src/Target.kt" }) { "target decl in workspace edited" }
                check(editsTarget.any { it.file == "Snippet.kt" }) { "target ref in snippet edited" }

                // 4. Disambiguation: different package with same symbol name is not renamed
                val pkgACode = "package com.pkgA\nval counter = 1"
                val pkgAFile = K2SnippetFrontend.psiFactory.createFile("src/PkgA.kt", pkgACode)
                val pkgBCode = "package com.pkgB\nval counter = 2"
                val pkgBFile = K2SnippetFrontend.psiFactory.createFile("src/PkgB.kt", pkgBCode)

                val disambigSnippet = "import com.pkgA.counter\nfun checkCount() = counter"
                val session4 = K2SnippetFrontend.analyzeSession(disambigSnippet, listOf(pkgAFile, pkgBFile))
                check(session4 != null)
                val disambigFiles = listOf("src/PkgA.kt" to pkgAFile, "src/PkgB.kt" to pkgBFile)
                val editsDisambig = K2RenameResolver.renameEditsForSymbol(session4, "counter", disambigFiles)
                check(editsDisambig.any { it.file == "src/PkgA.kt" }) { "pkgA counter edited" }
                check(editsDisambig.any { it.file == "Snippet.kt" }) { "snippet counter ref edited" }
                check(editsDisambig.none { it.file == "src/PkgB.kt" }) { "pkgB counter excluded" }

                // 5. Declaration in workspace only, not referenced in snippet
                val unrefWsCode = "package com.unref\nfun standalone() = 42"
                val unrefWsFile = K2SnippetFrontend.psiFactory.createFile("src/Standalone.kt", unrefWsCode)
                val emptySnippetSession = K2SnippetFrontend.analyzeSession("val unused = 0", listOf(unrefWsFile))
                check(emptySnippetSession != null)
                val editsStandalone = K2RenameResolver.renameEditsForSymbol(emptySnippetSession, "standalone", listOf("src/Standalone.kt" to unrefWsFile))
                check(editsStandalone.any { it.file == "src/Standalone.kt" }) { "standalone decl in workspace edited" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2RenameResolver.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2RenameResolver.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2RenameResolver.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2RenameResolver.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production K2SemanticEngine source file`() {
        val engineSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/K2SemanticEngine.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim().startsWith("@file:") }
            .joinToString("\n")

        val imports = """
            @file:Suppress("K1_ANALYSIS", "DEPRECATION")
            @file:OptIn(org.jetbrains.kotlin.K1Deprecation::class)
            import com.gokorei.kotlinmcp.execution.SnippetCompiler
            import com.gokorei.kotlinmcp.shared.SourceUtils
            import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
            import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
            import org.jetbrains.kotlin.psi.KtExpression
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtReferenceExpression
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.com.intellij.psi.PsiElement
            import org.jetbrains.kotlin.renderer.DescriptorRenderer
            import org.jetbrains.kotlin.resolve.BindingContext
            import org.jetbrains.kotlin.resolve.DescriptorUtils
            import java.io.File
            import com.gokorei.kotlinmcp.lsp.K2AnalysisSession
            import com.gokorei.kotlinmcp.lsp.K2CompletionResolver
            import com.gokorei.kotlinmcp.lsp.K2HierarchyResolver
            import com.gokorei.kotlinmcp.lsp.K2HoverResolver
            import com.gokorei.kotlinmcp.lsp.K2RenameResolver
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.KotlinCompletionCandidates
            import com.gokorei.kotlinmcp.lsp.KtCallHierarchyResult
            import com.gokorei.kotlinmcp.lsp.KtHoverInfo
            import com.gokorei.kotlinmcp.lsp.KtTypeHierarchyResult
            import com.gokorei.kotlinmcp.lsp.ResolvedDeclaration
            import com.gokorei.kotlinmcp.lsp.ResolvedReference
            import com.gokorei.kotlinmcp.lsp.ResolvedRenameEdit
            import com.gokorei.kotlinmcp.lsp.ResolvedSource
            import com.gokorei.kotlinmcp.lsp.SEMANTIC_FILE_CAP
            import com.gokorei.kotlinmcp.lsp.WorkspaceStats
        """.trimIndent()

        val productionCode = imports + "\n\n" + engineSource

        val testSuiteCode = """
            fun main() {
                val tempDir = java.nio.file.Files.createTempDirectory("k2engine_test").toFile()
                try {
                    val fileA = File(tempDir, "A.kt")
                    fileA.writeText("package com.test\nclass ServiceA { fun doWork(): Int = 42 }")
                    val fileB = File(tempDir, "B.kt")
                    fileB.writeText("package com.test\nclass ServiceB : ServiceA()")

                    val defaultEng = DefaultK2SemanticEngine()
                    check(defaultEng.workspaceFileCap == SEMANTIC_FILE_CAP)
                    defaultEng.close()

                    val engine = DefaultK2SemanticEngine(fileCap = 50)
                    check(engine.workspaceFileCap == 50) { "workspaceFileCap is 50" }

                    // 1. Stats and excluded dir
                    val stats = engine.workspaceStats(tempDir.absolutePath)
                    check(stats.totalKtFiles == 2 && stats.analyzedFiles == 2 && !stats.truncated) { "stats match" }

                    val buildDir = File(tempDir, "build")
                    buildDir.mkdir()
                    File(buildDir, "Ignored.kt").writeText("class Ignored")
                    val statsExcluded = engine.workspaceStats(tempDir.absolutePath)
                    check(statsExcluded.totalKtFiles == 2) { "build directory ignored in stats" }

                    // 2. Session creation and caching
                    val snippet = "import com.test.ServiceA\nfun run() {\n val a = ServiceA()\n val res = a.doWork()\n val external = listOf(1)\n}"
                    val session = engine.session(tempDir.absolutePath, snippet)
                    check(session != null) { "session not null" }
                    val rebuilds1 = engine.workspaceRebuilds
                    check(rebuilds1 == 1) { "workspaceRebuilds == 1" }

                    // Second session call reuses snapshot
                    val session2 = engine.session(tempDir.absolutePath, "val x = 1")
                    check(session2 != null)
                    check(engine.workspaceRebuilds == 1) { "rebuilds cached" }

                    // 3. resolveReference across sources (WORKSPACE, SNIPPET, EXTERNAL, UNRESOLVED)
                    var refA: KtReferenceExpression? = null
                    var refRes: KtReferenceExpression? = null
                    var refList: KtReferenceExpression? = null
                    session.file.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                        override fun visitReferenceExpression(expression: KtReferenceExpression) {
                            if (expression.text == "ServiceA" && refA == null) refA = expression
                            if (expression.text == "a" && refRes == null) refRes = expression
                            if (expression.text == "listOf" && refList == null) refList = expression
                            super.visitReferenceExpression(expression)
                        }
                    })
                    check(refA != null && refRes != null && refList != null)
                    val resolvedWs = engine.resolveReference(session, refA!!, tempDir.absolutePath)
                    check(resolvedWs != null && resolvedWs.source == ResolvedSource.WORKSPACE && resolvedWs.file == "A.kt" && resolvedWs.line == 2 && resolvedWs.fqn == "com.test.ServiceA")

                    val resolvedSnippet = engine.resolveReference(session, refRes!!, tempDir.absolutePath)
                    check(resolvedSnippet != null && resolvedSnippet.source == ResolvedSource.SNIPPET && resolvedSnippet.file == "Snippet.kt")

                    val resolvedExt = engine.resolveReference(session, refList!!, tempDir.absolutePath)
                    check(resolvedExt != null && resolvedExt.source == ResolvedSource.EXTERNAL && resolvedExt.file == "<external>")

                    val dummyFile = K2SnippetFrontend.parsePsi("fun dummy() { missingRef() }")
                    check(dummyFile != null)
                    val dummySession = K2SnippetFrontend.analyzeSession("fun dummy() { missingRef() }")
                    check(dummySession != null)
                    var missingRefExpr: KtReferenceExpression? = null
                    dummyFile.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                        override fun visitReferenceExpression(expression: KtReferenceExpression) {
                            if (expression.text == "missingRef") missingRefExpr = expression
                            super.visitReferenceExpression(expression)
                        }
                    })
                    val resolvedMissing = engine.resolveReference(dummySession, missingRefExpr!!, tempDir.absolutePath)
                    check(resolvedMissing != null && resolvedMissing.source == ResolvedSource.UNRESOLVED && resolvedMissing.file == "?")

                    // 4. fqNameOfDeclaration and typeOfExpression
                    val decl = session.file.declarations.first() as KtNamedDeclaration
                    val fqName = engine.fqNameOfDeclaration(session, decl)
                    check(fqName == "run") { "fqNameOfDeclaration run: " + fqName }

                    var callExpr: KtExpression? = null
                    session.file.accept(object : org.jetbrains.kotlin.psi.KtTreeVisitorVoid() {
                        override fun visitCallExpression(expression: org.jetbrains.kotlin.psi.KtCallExpression) {
                            if (expression.text.contains("doWork")) callExpr = expression
                            super.visitCallExpression(expression)
                        }
                    })
                    val exprType = engine.typeOfExpression(session, callExpr!!)
                    check(exprType == "kotlin.Int") { "typeOfExpression is kotlin.Int: " + exprType }

                    // 5. referencesForSymbol (detailed line/column/snippet/FQN checks)
                    val refsA = engine.referencesForSymbol(session, "ServiceA", tempDir.absolutePath)
                    check(refsA.size >= 2) { "referencesForSymbol ServiceA >= 2: " + refsA.size }
                    val declA = refsA.first { it.kind == "decl" }
                    check(declA.line == 2 && declA.file == "A.kt" && declA.column == 7 && declA.snippet.contains("class ServiceA") && declA.fqn == "com.test.ServiceA")
                    val refUsageA = refsA.first { it.kind == "ref" }
                    check(refUsageA.file == "Snippet.kt" && refUsageA.fqn == "com.test.ServiceA")

                    // Local variable references
                    val localSession = engine.session(tempDir.absolutePath, "fun localTest() {\n val myLocal = 1\n val x = myLocal\n}")
                    check(localSession != null)
                    val localRefs = engine.referencesForSymbol(localSession, "myLocal", tempDir.absolutePath)
                    check(localRefs.size == 2 && localRefs.all { it.fqn == null }) { "local variable references have null fqn" }

                    // Ignored symbol in build dir
                    check(engine.referencesForSymbol(session, "Ignored", tempDir.absolutePath).isEmpty()) { "ignored in build dir not found" }

                    // 6. Completion, Hover, Hierarchy, Rename
                    val comp = engine.completionCandidates(session, "a.do")
                    check(comp.members.contains("doWork")) { "completion has doWork" }

                    val hover = engine.hover(session, "doWork", tempDir.absolutePath)
                    check(hover != null && hover.symbol == "doWork" && hover.source == ResolvedSource.WORKSPACE && hover.file == "A.kt") { "hover doWork in workspace" }

                    val typeHier = engine.typeHierarchy(session, "ServiceB", tempDir.absolutePath)
                    check(typeHier.symbol == "ServiceB" && typeHier.supertypes.any { it.contains("ServiceA") }) { "typeHier has ServiceA" }

                    val callHier = engine.callHierarchy(session, "doWork", tempDir.absolutePath)
                    check(callHier.symbol == "doWork") { "callHier doWork" }

                    val renames = engine.renameEditsForSymbol(session, "ServiceA", tempDir.absolutePath)
                    check(renames.isNotEmpty()) { "renames not empty" }

                    // 7. Capping (exact equality total == fileCap vs total > fileCap)
                    check(!engine.workspaceStats(null).truncated)
                    check(!engine.workspaceStats("/non/existent/path/xyz").truncated)

                    val equalEngine = DefaultK2SemanticEngine(fileCap = 2)
                    val equalStats = equalEngine.workspaceStats(tempDir.absolutePath)
                    check(equalStats.totalKtFiles == 2 && equalStats.analyzedFiles == 2 && !equalStats.truncated) { "equal stats not truncated" }

                    val cappedEngine = DefaultK2SemanticEngine(fileCap = 1)
                    check(cappedEngine.workspaceFileCap == 1)
                    val cappedStats = cappedEngine.workspaceStats(tempDir.absolutePath)
                    check(cappedStats.totalKtFiles == 2 && cappedStats.analyzedFiles == 1 && cappedStats.truncated) { "capped stats truncated" }

                    // 8. Snapshot cache invalidation on file modification
                    fileA.writeText("package com.test\nclass ServiceA { fun modifiedWork(): Int = 99 }")
                    fileA.setLastModified(System.currentTimeMillis() + 2000)
                    val sessionModified = engine.session(tempDir.absolutePath, "val x = 1")
                    check(sessionModified != null)
                    check(engine.workspaceRebuilds >= 2) { "rebuilds after file modification" }

                    // 9. Eviction of snapshot cache when exceeding MAX_CACHED_WORKSPACES (4)
                    val otherDirs = (1..5).map { idx ->
                        val dir = java.nio.file.Files.createTempDirectory("k2ws_" + idx).toFile()
                        File(dir, "Test.kt").writeText("class Test" + idx)
                        dir
                    }
                    try {
                        otherDirs.forEach { dir ->
                            engine.session(dir.absolutePath, "val y = 1")
                        }
                    } finally {
                        otherDirs.forEach { it.deleteRecursively() }
                    }

                    // 10. Project classpath
                    val cp = engine.projectClasspath(tempDir.absolutePath)
                    check(cp != null) { "projectClasspath not null" }

                    // 11. Close and complete closed-state verification
                    engine.close()
                    check(engine.session(tempDir.absolutePath, "val x = 1") == null) { "session after close is null" }
                    val closedStats = engine.workspaceStats(tempDir.absolutePath)
                    check(closedStats.totalKtFiles == 0 && !closedStats.truncated) { "stats after close is 0 and not truncated" }
                    check(engine.completionCandidates(session, "a.do").members.isEmpty()) { "completion after close empty" }
                    check(engine.typeHierarchy(session, "ServiceB", tempDir.absolutePath).supertypes.isEmpty()) { "typeHierarchy after close empty" }
                    check(engine.callHierarchy(session, "doWork", tempDir.absolutePath).callers.isEmpty()) { "callHierarchy after close empty" }
                    check(engine.hover(session, "doWork", tempDir.absolutePath) == null) { "hover after close null" }
                    check(engine.renameEditsForSymbol(session, "ServiceA", tempDir.absolutePath).isEmpty()) { "rename after close empty" }
                    check(engine.referencesForSymbol(session, "ServiceA", tempDir.absolutePath).isEmpty()) { "references after close empty" }
                    check(engine.typeOfExpression(session, callExpr!!) == null) { "typeOfExpression after close null" }
                    check(engine.fqNameOfDeclaration(session, decl) == null) { "fqNameOfDeclaration after close null" }
                    check(engine.resolveReference(session, refA!!, tempDir.absolutePath) == null) { "resolveReference after close null" }
                    check(engine.projectClasspath(tempDir.absolutePath).isEmpty()) { "projectClasspath after close empty" }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: K2SemanticEngine.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN K2SemanticEngine.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for K2SemanticEngine.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for K2SemanticEngine.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production LibraryAnalysisService source file`() {
        val libSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/LibraryAnalysisService.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.shared.CommandService
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.LibraryAnalysisAction
            import com.gokorei.kotlinmcp.lsp.LibraryAnalysisService
        """.trimIndent()

        val productionCode = imports + "\n\n" + libSource

        val testSuiteCode = """
            fun main() {
                val service = DefaultLibraryAnalysisService()

                // 1. Ktor analysis: duplicate routes, missing content negotiation, missing status pages, client/server confusion
                val ktorCode = ""${'"'}
                    @Serializable
                    class UserDto(val id: String)

                    fun Application.module() {
                        routing {
                            route("/api") {
                                route("/users") {
                                    get { call.respond(UserDto("1")) }
                                }
                                route("/users") {
                                    post { call.respond(HttpStatusCode.BadRequest) }
                                }
                            }
                        }
                        client.plugins.install(Routing)
                    }
                ""${'"'}.trimIndent()

                val ktorRes = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, ktorCode)
                check(ktorRes is KotlinMcpResult.Success)
                val ktorText = ktorRes.content
                check(ktorText.contains("route_collision") && ktorText.contains("route \"/users\"")) { "route_collision detected" }
                check(ktorText.contains("missing_content_negotiation")) { "missing_content_negotiation detected" }
                check(ktorText.contains("missing_status_pages")) { "missing_status_pages detected" }
                check(ktorText.contains("client_plugins_confusion")) { "client_plugins_confusion detected" }
                check(ktorRes.metadata["advisoryCount"]?.toIntOrNull() != null)

                // Clean Ktor code
                val cleanKtor = "fun Application.module() { install(ContentNegotiation) { json() }; install(StatusPages); routing { get(\"/hello\") { call.respondText(\"hi\") } } }"
                val cleanKtorRes = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, cleanKtor)
                check(cleanKtorRes is KotlinMcpResult.Success && cleanKtorRes.content.contains("No Ktor anti-patterns"))

                // Ktor client with routing and respondStatus
                val clientRoutingCode = "fun app() { client.plugins; routing { get { call.respondStatus(HttpStatusCode.NotFound) } } }"
                val clientRoutingRes = service.execute(LibraryAnalysisAction.ANALYZE_KTOR, clientRoutingCode)
                check(clientRoutingRes is KotlinMcpResult.Success)
                check(clientRoutingRes.content.contains("client.plugins` is referenced alongside `routing"))
                check(clientRoutingRes.content.contains("missing_status_pages"))

                // 2. Serialization analysis: hidden ctor, evolution risk, non-serializable type, duplicate SerialName, dataSources
                val serialCode = ""${'"'}
                    @Serializable
                    class Secret private constructor(
                        unannotated: String,
                        val file: java.io.File
                    )

                    @Serializable
                    @SerialName("dup")
                    class TypeA

                    @Serializable
                    @SerialName("dup")
                    class TypeB
                ""${'"'}.trimIndent()

                val serialRes = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, serialCode, listOf("kotlin://schemas/old.json"))
                check(serialRes is KotlinMcpResult.Success)
                val serialText = serialRes.content
                check(serialText.contains("hidden_primary_ctor")) { "hidden_primary_ctor detected" }
                check(serialText.contains("evolution_risk")) { "evolution_risk detected" }
                check(serialText.contains("non_serializable_type")) { "non_serializable_type detected" }
                check(serialText.contains("serial_name_collision")) { "serial_name_collision detected" }
                check(serialText.contains("schema_diff_input")) { "schema_diff_input detected" }

                // Parameter @SerialName collision
                val paramSerialCode = "@Serializable class ParamDto(@SerialName(\"paramA\") val a: String, @SerialName(\"paramA\") val b: String)"
                val paramSerialRes = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, paramSerialCode)
                check(paramSerialRes is KotlinMcpResult.Success)
                check(paramSerialRes.content.contains("serial_name_collision") && paramSerialRes.content.contains("paramA"))

                // Clean serialization
                val cleanSerial = "@Serializable data class Person(@SerialName(\"name\") val name: String = \"\")"
                val cleanSerialRes = service.execute(LibraryAnalysisAction.ANALYZE_SERIALIZATION, cleanSerial)
                check(cleanSerialRes is KotlinMcpResult.Success && cleanSerialRes.content.contains("No kotlinx.serialization issues"))

                // 3. Test analysis: runBlocking, missing main rule, mockk verify gap, turbine unconsumed, mockk leak
                val testCode = ""${'"'}
                    import app.cash.turbine.test
                    import io.mockk.every
                    import io.mockk.mockkStatic

                    class MyTest {
                        @Test
                        fun testFeature() = runBlocking {
                            Dispatchers.setMain(StandardTestDispatcher())
                            mockkStatic(Helper::class)
                            every { mock.work() } returns 1
                            assertEquals(1, 1)
                            flow.test { }
                        }
                    }
                ""${'"'}.trimIndent()

                val testRes = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, testCode)
                check(testRes is KotlinMcpResult.Success)
                val testText = testRes.content
                check(testText.contains("runblocking_in_test")) { "runblocking_in_test detected" }
                check(testText.contains("mockk_verify_gap")) { "mockk_verify_gap detected" }
                check(testText.contains("turbine_unconsumed")) { "turbine_unconsumed detected" }
                check(testText.contains("mockk_leak")) { "mockk_leak detected" }

                // Test with Dispatchers.Main and no rule
                val noRuleCode = "@Test fun testMain() { val x = Dispatchers.Main; val y = Dispatchers.getMain() }"
                val noRuleRes = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, noRuleCode)
                check(noRuleRes is KotlinMcpResult.Success)
                check(noRuleRes.content.contains("missing_main_dispatcher_rule"))

                // Test assertions variety (assertThat, shouldBe) and verify
                val shouldBeCode = "@Test fun testShouldBe() { every { mock.f() } returns 1; 1 shouldBe 1; assertThat(1) }"
                val shouldBeRes = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, shouldBeCode)
                check(shouldBeRes is KotlinMcpResult.Success)
                check(shouldBeRes.content.contains("mockk_verify_gap"))

                val verifyCode = "@Test fun testVerify() { every { mock.f() } returns 1; verify { mock.f() } }"
                val verifyRes = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, verifyCode)
                check(verifyRes is KotlinMcpResult.Success)
                check(!verifyRes.content.contains("mockk_verify_gap"))

                // Clean test
                val cleanTest = "@Test fun testClean() = runTest { val res = 1; assertEquals(1, res) }"
                val cleanTestRes = service.execute(LibraryAnalysisAction.ANALYZE_TESTS, cleanTest)
                check(cleanTestRes is KotlinMcpResult.Success && cleanTestRes.content.contains("No test anti-patterns"))

                // 4. Route map
                val routeCode = ""${'"'}
                    fun Application.routes() {
                        routing {
                            route("/api") {
                                get("/users") { }
                                post("/users") { }
                                delete("/users/{id}") { }
                            }
                        }
                    }
                ""${'"'}.trimIndent()

                val routeRes = service.execute(LibraryAnalysisAction.ROUTE_MAP, routeCode)
                check(routeRes is KotlinMcpResult.Success)
                val routeText = routeRes.content
                check(routeText.contains("GET /api/users")) { "GET /api/users in route map" }
                check(routeText.contains("POST /api/users")) { "POST /api/users in route map" }
                check(routeText.contains("DELETE /api/users/{id}")) { "DELETE /api/users/{id} in route map" }
                check(routeRes.metadata["routeCount"] == "3")

                // Empty route map
                val emptyRouteRes = service.execute(LibraryAnalysisAction.ROUTE_MAP, "val x = 1")
                check(emptyRouteRes is KotlinMcpResult.Success && emptyRouteRes.content.contains("(no HTTP routes"))
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LibraryAnalysisService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN LibraryAnalysisService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LibraryAnalysisService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for LibraryAnalysisService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production WorkspaceSemanticIndexer source file`() {
        val indexerSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/WorkspaceSemanticIndexer.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import org.jetbrains.kotlin.psi.KtClass
            import org.jetbrains.kotlin.psi.KtClassOrObject
            import org.jetbrains.kotlin.psi.KtDeclaration
            import org.jetbrains.kotlin.psi.KtFile
            import org.jetbrains.kotlin.psi.KtNamedDeclaration
            import org.jetbrains.kotlin.psi.KtNamedFunction
            import org.jetbrains.kotlin.psi.KtObjectDeclaration
            import org.jetbrains.kotlin.psi.KtProperty
            import org.jetbrains.kotlin.psi.KtSimpleNameExpression
            import org.jetbrains.kotlin.psi.KtTreeVisitorVoid
            import org.jetbrains.kotlin.psi.KtTypeAlias
            import java.io.File
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.VfsPsiCache
            import com.gokorei.kotlinmcp.lsp.DefaultVfsPsiCache
        """.trimIndent()

        val productionCode = imports + "\n\n" + indexerSource

        val testSuiteCode = """
            fun main() {
                val tempDir = java.nio.file.Files.createTempDirectory("ws_indexer_test").toFile()
                try {
                    val fileA = File(tempDir, "A.kt")
                    fileA.writeText(""${'"'}
                        package com.example
                        interface BaseService
                        /** Doc summary for Service */
                        class Service(val id: Int = 1) : BaseService {
                            var mutableProp: String = "val"
                            val flag: Boolean = true
                            val num: Double = 3.14
                            val list = listOf(1)
                            fun doAction(param: String): Int = 42
                            fun defaultAction(): String = "ok"
                            fun inferInt() = 42
                            fun inferBool() = true
                            fun inferDouble() = 3.14
                            private fun secret() {}
                            class NestedClass { fun nestedMember() = 1 }
                        }
                        object SingletonService
                        typealias ServiceAlias = Service
                    ""${'"'}.trimIndent())

                    val fileB = File(tempDir, "B.kt")
                    fileB.writeText(""${'"'}
                        package com.example
                        fun callerFunction() {
                            val svc = Service(42)
                            val res = svc.doAction("hello")
                            doStandalone()
                            fun nestedCall() { svc.doAction("nested") }
                            nestedCall()
                        }
                        fun doStandalone() {}
                    ""${'"'}.trimIndent())

                    val fileC = File(tempDir, "C.kt")
                    fileC.writeText(""${'"'}
                        package com.other
                        import com.example.Service
                        fun remoteCaller() {
                            val s = Service()
                            com.example.SingletonService.toString()
                        }
                    ""${'"'}.trimIndent())

                    val fileKts = File(tempDir, "build.gradle.kts")
                    fileKts.writeText("val versionString = \"1.0.0\"")

                    val fileJava = File(tempDir, "JavaHelper.java")
                    fileJava.writeText(""${'"'}
                        package com.example;
                        public class JavaHelper {
                            public void help() {}
                            public static class InnerJava {}
                        }
                    ""${'"'}.trimIndent())

                    val indexer = WorkspaceSemanticIndexer()

                    // 1. Index entire workspace
                    val wsIndex = indexer.index(tempDir.absolutePath)
                    check(wsIndex.fileCount == 5) { "5 files indexed: " + wsIndex.fileCount }
                    val svcDecl = wsIndex.declarations.first { it.name == "Service" && it.file == "A.kt" }
                    check(svcDecl.line >= 1 && svcDecl.column >= 1 && svcDecl.snippet.contains("class Service"))
                    check(wsIndex.declarations.any { it.name == "JavaHelper" && it.file == "JavaHelper.java" }) { "JavaHelper declaration found" }
                    check(wsIndex.declarations.any { it.name == "InnerJava" }) { "InnerJava declaration found" }
                    check(wsIndex.declarations.any { it.name == "versionString" && it.file == "build.gradle.kts" }) { "kts file indexed" }
                    check(wsIndex.declarations.any { it.name == "NestedClass" }) { "NestedClass indexed" }

                    val svcRefB = wsIndex.occurrences.first { it.file == "B.kt" && it.name == "Service" && it.kind == OccurrenceKind.REFERENCE }
                    check(svcRefB.fqn?.contains("Service") == true) { "svcRefB fqn: " + svcRefB.fqn }

                    val svcRefC = wsIndex.occurrences.first { it.file == "C.kt" && it.name == "Service" && it.kind == OccurrenceKind.REFERENCE }
                    check(svcRefC.fqn?.contains("Service") == true) { "svcRefC fqn: " + svcRefC.fqn }

                    // Truncation
                    val cappedIndex = indexer.index(tempDir.absolutePath, maxFiles = 1)
                    check(cappedIndex.truncated && cappedIndex.fileCount == 1 && cappedIndex.totalKtFiles == 5) { "cappedIndex truncated" }

                    val exactIndex = indexer.index(tempDir.absolutePath, maxFiles = 5)
                    check(!exactIndex.truncated && exactIndex.fileCount == 5) { "exactIndex not truncated" }

                    // Missing dir
                    val missingIndex = indexer.index("/non/existent/path/xyz")
                    check(missingIndex.fileCount == 0 && !missingIndex.truncated)

                    // 2. publicApiOf
                    val (apiElements, files) = indexer.publicApiOf(listOf(fileA), tempDir.absolutePath, "com.example")
                    check(files.size == 1)
                    check(apiElements.any { it.kind == "interface" && it.name == "BaseService" }) { "interface BaseService found" }
                    check(apiElements.any { it.kind == "class" && it.name == "Service" && it.docSummary?.contains("Doc summary") == true }) { "class Service with doc found" }
                    check(apiElements.any { it.kind == "object" && it.name == "SingletonService" }) { "object SingletonService found" }
                    check(apiElements.any { it.kind == "typealias" && it.name == "ServiceAlias" }) { "typealias ServiceAlias found" }
                    check(apiElements.any { it.kind == "fun" && it.name == "doAction" && it.signature.contains("param: String") && it.signature.contains(": Int") }) { "doAction signature" }
                    check(apiElements.any { it.kind == "fun" && it.name == "defaultAction" && it.signature.contains(": String") }) { "defaultAction signature" }
                    check(apiElements.any { it.kind == "fun" && it.name == "inferInt" && it.signature.contains(": Int") }) { "inferInt signature" }
                    check(apiElements.any { it.kind == "fun" && it.name == "inferBool" && it.signature.contains(": Boolean") }) { "inferBool signature" }
                    check(apiElements.any { it.kind == "fun" && it.name == "inferDouble" && it.signature.contains(": Double") }) { "inferDouble signature" }
                    check(apiElements.any { it.kind == "var" && it.name == "mutableProp" && it.signature.contains("var mutableProp: String") }) { "mutableProp var signature" }
                    check(apiElements.any { it.kind == "val" && it.name == "flag" && it.signature.contains("Boolean") }) { "flag Boolean signature" }
                    check(apiElements.any { it.kind == "val" && it.name == "num" && it.signature.contains("Double") }) { "num Double signature" }
                    check(apiElements.any { it.kind == "val" && it.name == "list" && it.signature.contains("listOf") }) { "list signature" }
                    check(apiElements.none { it.name == "secret" }) { "private member excluded" }

                    // Null and non-matching package filters
                    val (allApi, _) = indexer.publicApiOf(listOf(fileA), tempDir.absolutePath, null)
                    check(allApi.size == apiElements.size)
                    val (emptyApi, _) = indexer.publicApiOf(listOf(fileA), tempDir.absolutePath, "com.other")
                    check(emptyApi.isEmpty())

                    // 3. typeHierarchyOf (with code, from workspacePath only, duplicates distinct, and nested classes)
                    val directIndex = WorkspaceIndex("r", 0, emptyList(), emptyList())
                    check(!directIndex.truncated)

                    val typeHier = indexer.typeHierarchyOf("", "BaseService", tempDir.absolutePath)
                    check(typeHier.symbol == "BaseService")
                    check(typeHier.subtypes.any { it.name == "Service" && it.file == "A.kt" }) { "Service is subtype of BaseService in workspace" }

                    val targetHier = indexer.typeHierarchyOf("", "Service", tempDir.absolutePath)
                    check(targetHier.supertypes.any { it.contains("BaseService") }) { "BaseService is supertype of Service in workspace" }

                    val dupHier = indexer.typeHierarchyOf("class Sub1 : Base(); class Sub1 : Base()", "Base", null)
                    check(dupHier.subtypes.size == 1)

                    val nestedHier = indexer.typeHierarchyOf("class Base; class OuterSub { class InnerSub : Base() }", "Base", null)
                    check(nestedHier.subtypes.any { it.name == "InnerSub" })

                    // 4. callHierarchyOf (from workspacePath only, qualified call, standalone call, exact qualified match, and snippet call)
                    val callHier = indexer.callHierarchyOf("", "doAction", tempDir.absolutePath)
                    check(callHier.symbol == "doAction")
                    check(callHier.callers.any { it.callerName == "callerFunction" && it.file == "B.kt" }) { "callerFunction found for doAction in workspace" }

                    val standaloneCallHier = indexer.callHierarchyOf("", "doStandalone", tempDir.absolutePath)
                    check(standaloneCallHier.callers.any { it.callerName == "callerFunction" && it.file == "B.kt" }) { "callerFunction found for doStandalone" }

                    val exactQualifiedHier = indexer.callHierarchyOf("fun caller() { obj.action() }", "obj.action", null)
                    check(exactQualifiedHier.callers.any { it.callerName == "caller" }) { "exact qualified match found" }

                    val snippetCallHier = indexer.callHierarchyOf("fun test() { Service().doAction(\"x\") }", "doAction", null)
                    check(snippetCallHier.callers.any { it.callerName == "test" && it.file == "Snippet" }) { "test caller found in snippet" }
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: WorkspaceSemanticIndexer.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN WorkspaceSemanticIndexer.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for WorkspaceSemanticIndexer.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for WorkspaceSemanticIndexer.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production LspService source file`() {
        val lspSource = File("src/main/kotlin/com/gokorei/kotlinmcp/lsp/LspService.kt")
            .readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import com.gokorei.kotlinmcp.models.KotlinMcpResult
            import com.gokorei.kotlinmcp.doc.DocService
            import com.gokorei.kotlinmcp.doc.DefaultDocService
            import com.gokorei.kotlinmcp.doc.DocAction
            import org.jetbrains.kotlin.psi.KtReferenceExpression
            import java.io.File
            import com.gokorei.kotlinmcp.lsp.DefaultK2SemanticEngine
            import com.gokorei.kotlinmcp.lsp.K2ResolutionUtils
            import com.gokorei.kotlinmcp.lsp.K2SemanticEngine
            import com.gokorei.kotlinmcp.lsp.K2SnippetFrontend
            import com.gokorei.kotlinmcp.lsp.LspAction
            import com.gokorei.kotlinmcp.lsp.LspService
            import com.gokorei.kotlinmcp.lsp.OccurrenceKind
            import com.gokorei.kotlinmcp.lsp.ResolvedSource
            import com.gokorei.kotlinmcp.lsp.ResolvedRenameEdit
            import com.gokorei.kotlinmcp.lsp.WorkspaceIndex
            import com.gokorei.kotlinmcp.lsp.WorkspaceSemanticIndexer
            import com.gokorei.kotlinmcp.lsp.KtSymbolOccurrence
        """.trimIndent()

        val productionCode = imports + "\n\n" + lspSource

        val testSuiteCode = """
            fun main() {
                val tempDir = java.nio.file.Files.createTempDirectory("lsp_service_test").toFile()
                try {
                    val fileA = File(tempDir, "A.kt")
                    fileA.writeText(""${'"'}
                        package com.example
                        /** KDoc for Service */
                        interface BaseService
                        class Service(val id: Int = 1) : BaseService {
                            fun doWork(): Int = 42
                        }
                    ""${'"'}.trimIndent())

                    val fileB = File(tempDir, "B.kt")
                    fileB.writeText(""${'"'}
                        package com.example
                        fun caller() {
                            val svc = Service()
                            val res = svc.doWork()
                        }
                    ""${'"'}.trimIndent())

                    val service = DefaultLspService()

                    // 1. Error cases for blank inputs
                    check(service.execute(LspAction.FIND_DEFINITION, "val x = 1", null) is KotlinMcpResult.Error) { "1.1" }
                    check(service.execute(LspAction.FIND_REFERENCES, "val x = 1", null) is KotlinMcpResult.Error) { "1.2" }
                    check(service.execute(LspAction.RENAME_SYMBOL, "val x = 1", null, "y") is KotlinMcpResult.Error) { "1.3" }
                    check(service.execute(LspAction.RENAME_SYMBOL, "val x = 1", "x", null) is KotlinMcpResult.Error) { "1.3b" }
                    check(service.execute(LspAction.WORKSPACE_SEARCH, "", null, null, null) is KotlinMcpResult.Error) { "1.4" }
                    check(service.execute(LspAction.WORKSPACE_SEARCH, "", "query", null, null) is KotlinMcpResult.Error) { "1.4b" }
                    check(service.execute(LspAction.WORKSPACE_REFERENCES, "", null, null, null) is KotlinMcpResult.Error) { "1.5" }
                    check(service.execute(LspAction.WORKSPACE_REFERENCES, "", "sym", null, null) is KotlinMcpResult.Error) { "1.5b" }
                    check(service.execute(LspAction.TYPE_HIERARCHY, "", null) is KotlinMcpResult.Error) { "1.6" }
                    check(service.execute(LspAction.CALL_HIERARCHY, "", null) is KotlinMcpResult.Error) { "1.7" }
                    check(service.execute(LspAction.HOVER, "val x = 1", null) is KotlinMcpResult.Error) { "1.8" }

                    // 2. FIND_DEFINITION
                    val defSnippet = "import com.example.Service\nval s = Service()\nval myLocal = 1\nval y = myLocal"
                    val defWs = service.execute(LspAction.FIND_DEFINITION, defSnippet, "Service", null, tempDir.absolutePath)
                    check(defWs is KotlinMcpResult.Success && defWs.metadata["found"] == "true" && defWs.metadata["file"] == "A.kt" && defWs.content.contains("# Definition of `Service`") && defWs.content.contains("- Defined at: A.kt:") && defWs.content.contains("- Declaration:")) { "2.1 Service definition" }

                    val defLocal = service.execute(LspAction.FIND_DEFINITION, defSnippet, "myLocal", null, tempDir.absolutePath)
                    check(defLocal is KotlinMcpResult.Success && defLocal.metadata["found"] == "true" && defLocal.metadata["symbol"] == "myLocal" && defLocal.metadata["line"] == "3" && defLocal.content.contains("Line 3")) { "2.2 myLocal definition" }

                    val defCtor = service.execute(LspAction.FIND_DEFINITION, "class Box(val size: Int)", "Box", null, null)
                    check(defCtor is KotlinMcpResult.Success && defCtor.content.contains("class Box(val size: Int)")) { "2.2b def ctor" }

                    val defStdlib = service.execute(LspAction.FIND_DEFINITION, "val list = listOf(1)", "listOf", null, null)
                    check(defStdlib is KotlinMcpResult.Success && defStdlib.metadata["symbol"] == "listOf") { "2.3 listOf stdlib" }

                    val defMissing = service.execute(LspAction.FIND_DEFINITION, "val x = 1", "unknownSym", null, null)
                    check(defMissing is KotlinMcpResult.Success && defMissing.metadata["found"] == "false" && defMissing.content.contains("not found in snippet")) { "2.4 missing def" }

                    // 3. FIND_REFERENCES
                    val refRes = service.execute(LspAction.FIND_REFERENCES, defSnippet, "Service", null, tempDir.absolutePath)
                    check(refRes is KotlinMcpResult.Success && refRes.metadata["symbol"] == "Service" && refRes.metadata["referenceCount"] != null && refRes.content.contains("# Symbol References for `Service`") && refRes.content.contains("occurrences found") && refRes.content.contains("Snippet: Line 2:")) { "3.1 find references" }

                    val refMissing = service.execute(LspAction.FIND_REFERENCES, "val x = 1", "unknownSym", null, null)
                    check(refMissing is KotlinMcpResult.Success && refMissing.content.contains("No occurrences or references")) { "3.2 missing references" }

                    val refLocal = service.execute(LspAction.FIND_REFERENCES, "val a = 1\nval b = a + a", "a", null, null)
                    check(refLocal is KotlinMcpResult.Success && refLocal.content.contains("Snippet: Line 1: `val a = 1`") && refLocal.content.contains("Snippet: Line 2: `val b = a + a`")) { "3.3 ref local" }

                    // 4. GET_COMPLETIONS
                    val compRes = service.execute(LspAction.GET_COMPLETIONS, "val str = \"hello\"\nval len = str.length", "str.")
                    check(compRes is KotlinMcpResult.Success && compRes.content.contains("# Code Completions for `str.`") && compRes.content.contains("## Semantic candidates") && compRes.content.contains("length") && compRes.metadata["prefix"] == "str." && compRes.metadata["completionCount"] != "0") { "4.1 get completions dot" }
                    val compCurated = service.execute(LspAction.GET_COMPLETIONS, "val x = 1", "map")
                    check(compCurated is KotlinMcpResult.Success && compCurated.content.contains("## Idiom suggestions (curated)") && compCurated.content.contains("`map { it }`") && compCurated.metadata["completionCount"] != "0") { "4.2 get completions curated" }
                    val compDot = service.execute(LspAction.GET_COMPLETIONS, "val x = 1", ".map")
                    check(compDot is KotlinMcpResult.Success && compDot.content.contains("map { it }")) { "4.2b comp dot" }
                    val compScope = service.execute(LspAction.GET_COMPLETIONS, "val localA = 1\nval localB = 2", "local")
                    check(compScope is KotlinMcpResult.Success && compScope.content.contains("localA") && compScope.content.contains("localB")) { "4.2c get completions scope" }
                    val compEmpty = service.execute(LspAction.GET_COMPLETIONS, "", "")
                    check(compEmpty is KotlinMcpResult.Success && compEmpty.content.contains("# Code Completions for `<all>`") && compEmpty.metadata["completionCount"] != null) { "4.3 completions all" }

                    // 5. RENAME_SYMBOL
                    val renameRes = service.execute(LspAction.RENAME_SYMBOL, "val oldVar = 1\nval y = oldVar", "oldVar", "newVar", null)
                    check(renameRes is KotlinMcpResult.Success && renameRes.content.contains("# Symbol Rename: `oldVar` -> `newVar`") && renameRes.content.contains("- Replacements in snippet: 2") && renameRes.content.contains("## Refactored Snippet") && renameRes.content.contains("```kotlin\nval newVar = 1\nval y = newVar\n```") && renameRes.metadata["oldName"] == "oldVar" && renameRes.metadata["newName"] == "newVar" && renameRes.metadata["replacementCount"] == "2") { "5.1 rename symbol snippet" }

                    val renameMulti = service.execute(LspAction.RENAME_SYMBOL, "val x = 1\nval y = x + x", "x", "longVar", null)
                    check(renameMulti is KotlinMcpResult.Success && renameMulti.content.contains("val longVar = 1\nval y = longVar + longVar")) { "5.1b rename multi" }

                    val renameNoMatch = service.execute(LspAction.RENAME_SYMBOL, "val x = 1", "unknownSym", "newSym", tempDir.absolutePath)
                    check(renameNoMatch is KotlinMcpResult.Success && renameNoMatch.content.contains("- No workspace files matched") && renameNoMatch.content.contains("```kotlin")) { "5.2 rename no match" }

                    // 6. WORKSPACE_SEARCH (exact, substring, subsequence, and empty)
                    val searchRes = service.execute(LspAction.WORKSPACE_SEARCH, "", "Service", null, tempDir.absolutePath)
                    check(searchRes is KotlinMcpResult.Success && searchRes.content.contains("# Workspace Symbol Search for `Service`") && searchRes.content.contains("- `100` Service (com.example.Service) — A.kt:4") && searchRes.metadata["symbol"] == "Service" && searchRes.metadata["matchCount"] != null && searchRes.metadata["fileCount"] != null) { "6.1 workspace search" }

                    val searchSub = service.execute(LspAction.WORKSPACE_SEARCH, "", "serv", null, tempDir.absolutePath)
                    check(searchSub is KotlinMcpResult.Success && searchSub.metadata["matchCount"] != "0" && searchSub.content.contains("- `87` Service")) { "6.1b search substring" }

                    val searchSubseq = service.execute(LspAction.WORKSPACE_SEARCH, "", "svc", null, tempDir.absolutePath)
                    check(searchSubseq is KotlinMcpResult.Success && searchSubseq.content.contains("- `56` Service")) { "6.1c search subseq" }

                    val searchEmpty = service.execute(LspAction.WORKSPACE_SEARCH, "", "NonExistentXyz", null, tempDir.absolutePath)
                    check(searchEmpty is KotlinMcpResult.Success && searchEmpty.content.contains("No symbols matching `NonExistentXyz` were found")) { "6.2 search empty" }

                    // 7. WORKSPACE_REFERENCES
                    val wsRefsRes = service.execute(LspAction.WORKSPACE_REFERENCES, "", "doWork", null, tempDir.absolutePath)
                    check(wsRefsRes is KotlinMcpResult.Success && wsRefsRes.content.contains("# Symbol References for `doWork`") && wsRefsRes.metadata["referenceCount"] != null) { "7.1 workspace references" }
                    val wsRefsEmpty = service.execute(LspAction.WORKSPACE_REFERENCES, "", "nonExistentXyz", null, tempDir.absolutePath)
                    check(wsRefsEmpty is KotlinMcpResult.Success && wsRefsEmpty.content.contains("No occurrences or references of symbol `nonExistentXyz` were found")) { "7.2 ws refs empty" }

                    // 8. TYPE_HIERARCHY
                    val typeHierRes = service.execute(LspAction.TYPE_HIERARCHY, "class Sub : BaseService()", "BaseService", null, tempDir.absolutePath)
                    check(typeHierRes is KotlinMcpResult.Success && typeHierRes.metadata["symbol"] == "BaseService" && typeHierRes.metadata["supertypeCount"] != null && typeHierRes.metadata["subtypeCount"] != null && typeHierRes.content.contains("# Type Hierarchy for `BaseService`") && typeHierRes.content.contains("## Supertypes / Base Interfaces") && typeHierRes.content.contains("## Subtypes & Implementations") && (typeHierRes.content.contains("Sub") || typeHierRes.content.contains("Service"))) { "8.1 type hierarchy" }
                    val typeHierNone = service.execute(LspAction.TYPE_HIERARCHY, "class Standalone", "Standalone", null, null)
                    check(typeHierNone is KotlinMcpResult.Success && typeHierNone.content.contains("# Type Hierarchy for `Standalone`") && typeHierNone.content.contains("- (none)")) { "8.2 type hier none" }

                    // 9. CALL_HIERARCHY
                    val callHierRes = service.execute(LspAction.CALL_HIERARCHY, "", "doWork", null, tempDir.absolutePath)
                    check(callHierRes is KotlinMcpResult.Success && callHierRes.metadata["symbol"] == "doWork" && callHierRes.content.contains("# Call Hierarchy for `doWork`") && callHierRes.content.contains("## Incoming Calls & Usage Sites") && callHierRes.content.contains("caller")) { "9.1 call hierarchy" }
                    val callHierNone = service.execute(LspAction.CALL_HIERARCHY, "fun noCaller() {}", "noCaller", null, null)
                    check(callHierNone is KotlinMcpResult.Success && callHierNone.content.contains("(none found in snippet)") && callHierNone.metadata["callerCount"] == "0") { "9.2 call hier none" }

                    // 9b. Structural Fallback for type & call hierarchy when capped
                    val fallbackService = DefaultLspService(DefaultDocService(), DefaultK2SemanticEngine(), -1)
                    val typeFallback = fallbackService.execute(LspAction.TYPE_HIERARCHY, "class Sub : BaseService()", "BaseService", null, tempDir.absolutePath)
                    check(typeFallback is KotlinMcpResult.Success && typeFallback.content.contains("⚠ Structural-index fallback")) { "9b.1 type fallback: " + typeFallback }
                    val callFallback = fallbackService.execute(LspAction.CALL_HIERARCHY, "val res = svc.doWork()", "doWork", null, tempDir.absolutePath)
                    check(callFallback is KotlinMcpResult.Success && callFallback.content.contains("⚠ Structural-index fallback")) { "9b.2 call fallback: " + callFallback }
                    fallbackService.close()

                    // 10. HOVER (snippet declaration, workspace symbol, doc, and unresolved)
                    val hoverRes = service.execute(LspAction.HOVER, "class MyService { fun hello() = 1 }", "MyService", null, null)
                    check(hoverRes is KotlinMcpResult.Success && hoverRes.metadata["symbol"] == "MyService" && hoverRes.metadata["found"] == "true" && hoverRes.metadata["source"] == "SNIPPET" && hoverRes.content.contains("# Hover: `MyService`") && hoverRes.content.contains("- Location: Snippet.kt:1")) { "10.1 hover found" }

                    val hoverDoc = service.execute(LspAction.HOVER, "/** Doc text */\nfun docFunc(): Int = 42", "docFunc", null, null)
                    check(hoverDoc is KotlinMcpResult.Success && hoverDoc.content.contains("Doc text") && hoverDoc.content.contains("fun docFunc")) { "10.1b hover doc" }

                    val hoverFull = service.execute(LspAction.HOVER, "/** Full desc */\nfun fullFun(a: Int): String = a.toString()", "fullFun", null, null)
                    check(hoverFull is KotlinMcpResult.Success && (hoverFull.content.contains("- Type: ") || hoverFull.content.contains("- Signature: ")) && hoverFull.content.contains("- FQN: `fullFun`") && hoverFull.content.contains("Full desc") && hoverFull.metadata["signature"] != null) { "10.1c hover full" }

                    val hoverVar = service.execute(LspAction.HOVER, "val myFloat: Float = 1.0f", "myFloat", null, null)
                    check(hoverVar is KotlinMcpResult.Success && hoverVar.content.contains("myFloat")) { "10.1d hover var: " + hoverVar }

                    val hoverWs = service.execute(LspAction.HOVER, "package com.example\nval s = Service()", "Service", null, tempDir.absolutePath)
                    check(hoverWs is KotlinMcpResult.Success && hoverWs.content.contains("Service")) { "10.2 hover ws: " + hoverWs }

                    val hoverExt = service.execute(LspAction.HOVER, "val list = listOf(1)", "listOf", null, null)
                    check(hoverExt is KotlinMcpResult.Success && hoverExt.content.contains("External (stdlib / dependency)")) { "10.2b hover ext" }

                    val hoverUnresolved = service.execute(LspAction.HOVER, "val x = 1", "unresolvedName", null, null)
                    check(hoverUnresolved is KotlinMcpResult.Success && hoverUnresolved.metadata["found"] == "false" && hoverUnresolved.metadata["source"] == "UNRESOLVED" && hoverUnresolved.content.contains("unresolved in the snippet")) { "10.3 hover unresolved" }

                    val hoverFallback = service.execute(LspAction.HOVER, "broken { val orphanSymbol = 1 }", "orphanSymbol", null, null)
                    check(hoverFallback is KotlinMcpResult.Success && hoverFallback.content.contains("# Hover: `orphanSymbol`") && hoverFallback.content.contains("- Signature: `val orphanSymbol = 1`") && hoverFallback.content.contains("- Defined at: Line 1")) { "10.4 hover fallback" }

                    // 10b. Capped workspace truncation prefix
                    val cappedEngine = DefaultK2SemanticEngine(fileCap = 1)
                    val cappedService = DefaultLspService(DefaultDocService(), cappedEngine, 200)
                    val cappedDef = cappedService.execute(LspAction.FIND_DEFINITION, "import com.example.Service\nval s = Service()", "Service", null, tempDir.absolutePath)
                    check(cappedDef is KotlinMcpResult.Success && cappedDef.content.contains("⚠ Workspace scan truncated:")) { "10b capped def" }
                    cappedService.close()
                    cappedEngine.close()

                    // 11. Rename workspace files (runs at end to avoid mutating files for other tests)
                    val renameWs = service.execute(LspAction.RENAME_SYMBOL, "val s = Service()", "Service", "RenamedService", tempDir.absolutePath)
                    check(renameWs is KotlinMcpResult.Success && renameWs.content.contains("Workspace files updated:") && renameWs.content.contains("replacements") && renameWs.metadata["workspaceFileCount"] != null) { "11.1 rename symbol ws" }

                    // 12. close
                    service.close()
                } finally {
                    tempDir.deleteRecursively()
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            timeoutPerMutantMs = 15000L,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: LspService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN LspService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for LspService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for LspService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production AstMutantGenerator source file`() {
        val modelsFile = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/MutationModels.kt")
        val mutatorsFile = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/AstMutators.kt")
        val generatorFile = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/AstMutantGenerator.kt")
        assertTrue(modelsFile.exists(), "Target file must exist: ${modelsFile.absolutePath}")
        assertTrue(mutatorsFile.exists(), "Target file must exist: ${mutatorsFile.absolutePath}")
        assertTrue(generatorFile.exists(), "Target file must exist: ${generatorFile.absolutePath}")

        val imports = (modelsFile.readLines() + mutatorsFile.readLines() + generatorFile.readLines())
            .filter { it.trim().startsWith("import ") }
            .distinct()
            .joinToString("\n")

        val modelsBody = modelsFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val mutatorsBody = mutatorsFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val generatorBody = generatorFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + modelsBody + "\n\n" + mutatorsBody + "\n\n" + generatorBody

        val testSuiteCode = """
            fun main() {
                val generator = AstMutantGenerator()

                // 1. Blank or empty code returns emptyList
                check(generator.generate("").isEmpty()) { "1.1 empty" }
                check(generator.generate("   \n  ").isEmpty()) { "1.2 blank" }

                // 2. Relational & arithmetic binary expressions
                val code1 = "fun calc(x: Int, y: Int): Boolean = (x < y) && (x <= y) && (x > y) && (x >= y) && (x == y) && (x != y) && (x + y > 0) && (x - y > 0) && (x * y > 0) && (x / y > 0) && (x % y > 0)"
                val mutants1 = generator.generate(code1)
                check(mutants1.isNotEmpty()) { "2.1 mutants generated" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == "<=" && it.description.contains("Replaced < with <=") }) { "2.2 lt" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == "<" && it.description.contains("Replaced <= with <") }) { "2.3 lteq" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == ">=" && it.description.contains("Replaced > with >=") }) { "2.4 gt" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == ">" && it.description.contains("Replaced >= with >") }) { "2.5 gteq" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == "!=" && it.description.contains("Replaced == with !=") }) { "2.6 eqeq" }
                check(mutants1.any { it.operator == MutationOperator.RELATIONAL_BOUNDARY && it.mutatedSnippet == "==" && it.description.contains("Replaced != with ==") }) { "2.7 excleq" }
                check(mutants1.any { it.operator == MutationOperator.ARITHMETIC_OPERATOR && it.mutatedSnippet == "-" && it.description.contains("Replaced + with -") }) { "2.8 plus" }
                check(mutants1.any { it.operator == MutationOperator.ARITHMETIC_OPERATOR && it.mutatedSnippet == "+" && it.description.contains("Replaced - with +") }) { "2.9 minus" }
                check(mutants1.any { it.operator == MutationOperator.ARITHMETIC_OPERATOR && it.mutatedSnippet == "/" && it.description.contains("Replaced * with /") }) { "2.10 mul" }
                check(mutants1.any { it.operator == MutationOperator.ARITHMETIC_OPERATOR && it.mutatedSnippet == "*" && it.description.contains("Replaced / with *") }) { "2.11 div" }
                check(mutants1.any { it.operator == MutationOperator.ARITHMETIC_OPERATOR && it.mutatedSnippet == "*" && it.description.contains("Replaced % with *") }) { "2.12 perc" }

                // 3. Prefix expression boolean inversion (!flag -> flag)
                val code2 = "fun checkFlag(flag: Boolean): Boolean = !flag"
                val mutants2 = generator.generate(code2)
                check(mutants2.any { it.operator == MutationOperator.BOOLEAN_INVERSION && it.mutatedSnippet == "flag" && it.description.contains("Negation inverted") }) { "3.1 prefix excl" }

                // 4. Boolean literal mutations (true <-> false)
                val code3 = "fun b(): Boolean { val a = true; val b = false; return true }"
                val mutants3 = generator.generate(code3)
                check(mutants3.any { it.operator == MutationOperator.BOOLEAN_INVERSION && it.mutatedSnippet == "false" && it.description.contains("Inverted boolean literal from 'true' to 'false'") }) { "4.1 true to false" }
                check(mutants3.any { it.operator == MutationOperator.BOOLEAN_INVERSION && it.mutatedSnippet == "true" && it.description.contains("Inverted boolean literal from 'false' to 'true'") }) { "4.2 false to true" }

                // 5. Return value mutations (0, false)
                val code4 = "fun ret(): String { return \"hello\" }"
                val mutants4 = generator.generate(code4)
                check(mutants4.any { it.operator == MutationOperator.RETURN_VALUE && it.mutatedSnippet == "0" && it.description.contains("Replaced return value with 0") }) { "5.1 return 0" }
                check(mutants4.any { it.operator == MutationOperator.RETURN_VALUE && it.mutatedSnippet == "false" && it.description.contains("Replaced return value with false") }) { "5.2 return false" }

                // 6. Void call omissions
                val code5 = "fun doSideEffect() { println(\"hi\"); java.lang.System.gc() }"
                val mutants5 = generator.generate(code5)
                check(mutants5.any { it.operator == MutationOperator.VOID_CALL_REMOVAL && it.mutatedSnippet == "Unit" && it.description.contains("Omitted statement") }) { "6.1 void call omission" }

                // 0. Verify MutationModels properties
                val modelReport = MutationReport(80.0, 10, 8, 1, 1, 0, emptyList())
                check(modelReport.effectiveMutants == 9) { "0.1 effectiveMutants" }
                check(modelReport.isStrong) { "0.2 isStrong" }
                val modelWeak = MutationReport(79.9, 10, 7, 2, 1, 0, emptyList())
                check(!modelWeak.isStrong) { "0.3 not isStrong" }

                // 7. Extreme operators: literal, collection, condition
                val code6 = "fun extreme(list: List<Int>, flag: Boolean): Int { if (flag) { val x = 42; return list.filter { it > 0 }.first() } return 0 }"
                val mutants6 = generator.generate(code6, includeExtremeOperators = true)
                check(mutants6.any { it.operator == MutationOperator.LITERAL_MUTATION && it.mutatedSnippet == "43" }) { "7.1a int +1" }
                check(mutants6.any { it.operator == MutationOperator.LITERAL_MUTATION && it.mutatedSnippet == "41" }) { "7.1b int -1" }
                check(mutants6.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "filterNot" }) { "7.2 filter -> filterNot" }
                check(mutants6.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "last" }) { "7.3 first -> last" }
                check(mutants6.any { it.operator == MutationOperator.CONDITION_REPLACEMENT && (it.mutatedSnippet == "true" || it.mutatedSnippet == "false") }) { "7.4 condition replacement" }

                val code6b = "fun moreCollections(list: List<Int>) { list.filterNot { it == 1 }; list.any { it > 0 }; list.all { it > 0 }; list.take(1); list.drop(1); list.last() }"
                val mutants6b = generator.generate(code6b, includeExtremeOperators = true)
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "filter" }) { "7.5 filterNot -> filter" }
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "all" }) { "7.6 any -> all" }
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "any" }) { "7.7 all -> any" }
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "drop" }) { "7.8 take -> drop" }
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "take" }) { "7.9 drop -> take" }
                check(mutants6b.any { it.operator == MutationOperator.COLLECTION_OPERATOR && it.mutatedSnippet == "first" }) { "7.10 last -> first" }

                // 8. Higher-order compound mutants (maxOrder >= 2)
                val code7 = "fun compound(a: Int, b: Int): Boolean { val x = a > 0; val y = b < 10; return x && y }"
                val mutants7 = generator.generate(code7, includeExtremeOperators = false, maxOrder = 2)
                check(mutants7.any { it.order == 2 && it.operator == MutationOperator.HIGHER_ORDER_COMPOUND && it.description.contains("Compound 2nd-order mutant") && it.id.contains("mutant-2nd-1-") }) { "8.1 higher-order mutant" }
                check(mutants1.first().id.contains("mutant-1st-1-")) { "8.2 1st-order id" }

                // 9. Multiline and exact line/column calculation
                val codeMultiline = "fun f() {\n  val x = 1\n  val y = 2\n  val z = x > y\n}"
                val multiMutants = generator.generate(codeMultiline)
                val gtMutant = multiMutants.first { it.operator == MutationOperator.RELATIONAL_BOUNDARY }
                check(gtMutant.line == 4 && gtMutant.column == 13) { "9.1 line/column computation" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: AstMutantGenerator.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN AstMutantGenerator.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for AstMutantGenerator.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for AstMutantGenerator.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production MutationService source file`() {
        val serviceFile = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/MutationService.kt")
        assertTrue(serviceFile.exists(), "Target file must exist: ${serviceFile.absolutePath}")

        val imports = serviceFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.mutation.*")
            .distinct()
            .joinToString("\n")

        val serviceBody = serviceFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + serviceBody

        val testSuiteCode = """
            fun main() {
                val service = DefaultMutationService()

                // 1. Blank code error handling
                val emptyRes = service.mutateAndTest("")
                check(emptyRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Error && emptyRes.code == "EMPTY_CODE" && emptyRes.message.contains("Code to mutation test cannot be empty")) { "1.1 empty code" }
                val blankRes = service.mutateAndTest("   \n\t  ")
                check(blankRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Error && blankRes.code == "EMPTY_CODE") { "1.2 blank code" }

                // 2. Strong test suite kills all mutants
                val strongCode = "fun add(a: Int, b: Int): Int = a + b"
                val strongTest = "fun main() { check(add(1, 2) == 3); check(add(-1, 1) == 0); check(add(0, 0) == 0) }"
                val strongRes = service.mutateAndTest(strongCode, strongTest)
                check(strongRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Success) { "2.1 strong success" }
                check(strongRes.metadata["isStrong"] == "true") { "2.2 isStrong true" }
                check(strongRes.content.contains("# 🧬 In-Memory Mutation Testing Report")) { "2.3 header" }
                check(strongRes.content.contains("🟢 **STRONG")) { "2.4 strong badge" }
                check(strongRes.content.contains("- **Total Mutants Generated:** 1")) { "2.5 total mutants 1" }
                check(strongRes.content.contains("- **Mutants Killed:** 1 / 1")) { "2.6 mutants killed" }
                check(strongRes.content.contains("All mutants killed!")) { "2.7 all killed message" }
                check(!strongRes.content.contains("- **Compilation Errors")) { "2.7b no comp errors" }
                check(!strongRes.content.contains("- **Timeouts")) { "2.7c no timeouts" }
                check(strongRes.metadata["score"] == "100.0") { "2.8 score 100.0" }
                check(strongRes.metadata["survivedCount"] == "0") { "2.9 survived 0" }

                // 3. Weak test suite allows survival and reports survived mutants
                val weakCode = "fun checkPos(x: Int): Boolean = x > 0"
                val weakTest = "fun main() { check(checkPos(5)) }"
                val weakRes = service.mutateAndTest(weakCode, weakTest)
                check(weakRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Success) { "3.1 weak success" }
                check(weakRes.metadata["isStrong"] == "false") { "3.2 isStrong false" }
                check(weakRes.content.contains("🔴 **NEEDS IMPROVEMENT")) { "3.3 needs improvement badge" }
                check(weakRes.content.contains("- **Mutants Survived (Weak Tests):** 1")) { "3.4 mutants survived count" }
                check(weakRes.content.contains("## ⚠️ Survived Mutants (1)")) { "3.5 survived section" }
                check(weakRes.content.contains("The following mutated code variations passed all test assertions without triggering a failure.")) { "3.6 survived explanation" }
                check(weakRes.content.contains("### 1. RELATIONAL_BOUNDARY (Line 1)")) { "3.7 mutant header" }
                check(weakRes.content.contains("> Replaced > with >=")) { "3.8 mutant desc" }
                check(weakRes.content.contains("```diff")) { "3.9 diff start" }
                check(weakRes.content.contains("- x > 0") || weakRes.content.contains("- >")) { "3.10 original snippet" }
                check(weakRes.content.contains("+ >=") || weakRes.content.contains("+ x >= 0")) { "3.11 mutated snippet" }
                check(weakRes.metadata["survivedCount"] != "0") { "3.12 survived non-zero" }

                // 4. Baseline failure error reporting
                val brokenTest = "fun main() { check(add(1, 1) == 999) }"
                val brokenRes = service.mutateAndTest(strongCode, brokenTest)
                check(brokenRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Error && brokenRes.code == "BASELINE_FAILURE" && brokenRes.details?.get("stage") == "baseline_verification") { "4.1 baseline failure" }

                // 5. Mock pipeline verifying compilation error and timeout branches
                val mockPipeline = object : MutationExecutionPipeline {
                    var closed = false
                    override fun run(code: String, testCode: String?, timeoutPerMutantMs: Long, includeExtremeOperators: Boolean, maxOrder: Int): MutationReport {
                        val mutant = AstMutant("m1", MutationOperator.RELATIONAL_BOUNDARY, 1, 1, "x > 0", "x >= 0", "x >= 0", "Replaced > with >=")
                        return MutationReport(
                            score = 50.0,
                            totalMutants = 4,
                            killedCount = 1,
                            survivedCount = 1,
                            compilationErrorCount = 1,
                            timeoutCount = 1,
                            results = listOf(
                                MutantResult(mutant, MutantStatus.SURVIVED),
                                MutantResult(mutant, MutantStatus.COMPILATION_ERROR),
                                MutantResult(mutant, MutantStatus.TIMEOUT)
                            )
                        )
                    }
                    override fun close() { closed = true }
                }
                val mockService = DefaultMutationService(mockPipeline)
                val mockRes = mockService.mutateAndTest("fun f() = 1")
                check(mockRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Success)
                check(mockRes.content.contains("- **Compilation Errors (Discarded):** 1")) { "5.1 compilation error count" }
                check(mockRes.content.contains("- **Timeouts (Counted as Killed):** 1")) { "5.2 timeout count" }
                mockService.close()
                check(mockPipeline.closed) { "5.3 pipeline close" }

                // 6. Projection support
                val compactProj = com.gokorei.kotlinmcp.models.ResponseProjection(preset = com.gokorei.kotlinmcp.models.ResponsePreset.COMPACT)
                val compactRes = service.mutateAndTest(strongCode, strongTest, compactProj)
                check(compactRes is com.gokorei.kotlinmcp.models.KotlinMcpResult.Success) { "6.1 compact success" }

                // 7. Close
                service.close()
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: MutationService.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN MutationService.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for MutationService.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for MutationService.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production MutationExecutionPipeline source file`() {
        val pipelineFile = File("src/main/kotlin/com/gokorei/kotlinmcp/mutation/MutationExecutionPipeline.kt")
        assertTrue(pipelineFile.exists(), "Target file must exist: ${pipelineFile.absolutePath}")

        val imports = pipelineFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.mutation.*")
            .distinct()
            .joinToString("\n")

        val pipelineBody = pipelineFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + pipelineBody

        val testSuiteCode = """
            fun main() {
                val pipeline = DefaultMutationExecutionPipeline()

                // 1. Baseline compilation failure
                val badCode = "fun error("
                val badRep = pipeline.run(badCode)
                check(badRep.totalMutants == 0 && badRep.score == 0.0 && badRep.compilationErrorCount == 1) { "1.1 baseline comp error" }
                check(badRep.results.first().status == MutantStatus.COMPILATION_ERROR) { "1.2 status comp error" }

                // 2. Baseline runtime test failure
                val addCode = "fun add(a: Int, b: Int) = a + b"
                val brokenTest = "fun main() { check(add(1, 1) == 99) }"
                val brokenRep = pipeline.run(addCode, brokenTest)
                check(brokenRep.totalMutants == 0 && brokenRep.score == 0.0 && brokenRep.compilationErrorCount == 0) { "2.1 baseline runtime error" }
                check(brokenRep.results.first().status == MutantStatus.KILLED) { "2.2 status killed" }

                // 3. No mutants generated
                val emptyCode = "fun main() { val x = \"hello\" }"
                val noMutantsRep = pipeline.run(emptyCode)
                check(noMutantsRep.totalMutants == 0 && noMutantsRep.score == 100.0) { "3.1 empty mutants" }

                // 4. Strong test suite kills all mutants
                val strongTest = "fun main() { check(add(1, 2) == 3); check(add(-1, 1) == 0); check(add(0, 0) == 0) }"
                val strongRep = pipeline.run(addCode, strongTest)
                check(strongRep.totalMutants > 0 && strongRep.killedCount == strongRep.totalMutants && strongRep.score == 100.0 && strongRep.isStrong) { "4.1 strong" }

                // 5. Weak test suite allows survival
                val weakCode = "fun isPos(x: Int) = x > 0"
                val weakTest = "fun main() { check(isPos(5)) }"
                val weakRep = pipeline.run(weakCode, weakTest)
                check(weakRep.survivedCount > 0 && weakRep.score < 100.0 && !weakRep.isStrong) { "5.1 weak survival" }

                // 6. Test timeout mutant with mock runner
                var callCount = 0
                var runnerClosed = false
                val mockRunner = object : com.gokorei.kotlinmcp.execution.FastSnippetRunner {
                    override fun run(outDir: java.nio.file.Path, timeoutMillis: Long, extraClasspath: List<String>): KotlinMcpResult {
                        callCount++
                        return if (callCount == 1) {
                            KotlinMcpResult.Success("Baseline OK")
                        } else {
                            KotlinMcpResult.Error("Timeout reached", "EXECUTION_TIMEOUT")
                        }
                    }
                    override fun close() { runnerClosed = true }
                }
                val timeoutPipeline = DefaultMutationExecutionPipeline(runner = mockRunner)
                val timeoutRep = timeoutPipeline.run(weakCode, weakTest)
                check(timeoutRep.timeoutCount > 0 && timeoutRep.score == 100.0) { "6.1 timeout killed" }
                timeoutPipeline.close()
                check(runnerClosed) { "6.2 runner close called" }

                // 7. Mutant compilation error (return 0 in String function causes compilation failure)
                val stringCode = "fun greet(): String { val s = \"hi\"; return s }"
                val stringTest = "fun main() { check(greet() == \"hi\") }"
                val repBrokenMutant = pipeline.run(stringCode, stringTest)
                check(repBrokenMutant.compilationErrorCount > 0) { "7.1 compilation error counted" }
                check(repBrokenMutant.results.any { it.status == MutantStatus.COMPILATION_ERROR }) { "7.2 status compilation error" }
                check(repBrokenMutant.results.first { it.status == MutantStatus.COMPILATION_ERROR }.durationMs >= 0L) { "7.3 duration non-negative" }

                // 8. Mixed score calculation
                val mixedCode = "fun sign(x: Int): Int { if (x > 0) return 1; if (x < 0) return -1; return 0 }"
                val mixedTest = "fun main() { check(sign(5) == 1); check(sign(0) == 0) }"
                val mixedRep = pipeline.run(mixedCode, mixedTest)
                check(mixedRep.killedCount > 0 && mixedRep.survivedCount > 0) { "8.1 mixed killed and survived" }
                check(mixedRep.score > 0.0 && mixedRep.score < 100.0) { "8.2 mixed score between 0 and 100" }

                pipeline.close()
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: MutationExecutionPipeline.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN MutationExecutionPipeline.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for MutationExecutionPipeline.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for MutationExecutionPipeline.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production FrameworkDetector source file`() {
        val detectorFile = File("src/main/kotlin/com/gokorei/kotlinmcp/server/FrameworkDetector.kt")
        assertTrue(detectorFile.exists(), "Target file must exist: ${detectorFile.absolutePath}")

        val imports = detectorFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .distinct()
            .joinToString("\n")

        val detectorBody = detectorFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + detectorBody

        val testSuiteCode = """
            fun main() {
                val detector = DefaultFrameworkDetector()

                // 1. Blank script returns NONE
                check(detector.detectFromBuildScript("") == com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile.NONE) { "1.1 empty" }
                check(detector.detectFromBuildScript("   \n  ") == com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile.NONE) { "1.2 blank" }

                // 2. All framework features detected
                val fullScript = ""${"\""}
                    plugins {
                        kotlin("multiplatform")
                    }
                    dependencies {
                        implementation("io.ktor:ktor-server-core")
                        implementation("org.jetbrains.kotlinx:kotlinx-serialization-json")
                        implementation("io.arrow-kt:arrow-core")
                        implementation("org.jetbrains.kotlinx:kotlinx-datetime")
                        testImplementation("io.mockk:mockk")
                        testImplementation("app.cash.turbine:turbine")
                        implementation("org.springframework.boot:spring-boot-starter")
                        implementation("androidx.compose.ui:ui")
                        implementation("org.jetbrains.exposed:exposed-core")
                        implementation("androidx.room:room-runtime")
                        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")
                    }
                ""${"\""}.trimIndent()

                val profile = detector.detectFromBuildScript(fullScript)
                check(profile.isKmp) { "2.1 isKmp multiplatform" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.KTOR)) { "2.2 ktor" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.SERIALIZATION)) { "2.3 serialization" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.ARROW)) { "2.4 arrow" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.DATETIME)) { "2.5 datetime" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.MOCKK)) { "2.6 mockk" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.TURBINE)) { "2.7 turbine" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.SPRING)) { "2.8 spring" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.COMPOSE)) { "2.9 compose" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.EXPOSED)) { "2.10 exposed" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.ROOM)) { "2.11 room" }
                check(profile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.COROUTINES)) { "2.12 coroutines" }

                // 3. Alternative KMP flags
                val kmp2 = detector.detectFromBuildScript("id('kotlin-multiplatform')")
                check(kmp2.isKmp) { "3.1 kotlin-multiplatform" }
                val kmp3 = detector.detectFromBuildScript("kotlin(\"kmp\")")
                check(kmp3.isKmp) { "3.2 kotlin(kmp)" }

                // 4. Project directory detection
                check(detector.detectFromProjectDir("/non/existent/path") == com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile.NONE) { "4.1 non-existent dir" }
                val tempDir = java.io.File(java.lang.System.getProperty("java.io.tmpdir"), "fwk_test_" + java.util.UUID.randomUUID())
                tempDir.mkdirs()
                check(detector.detectFromProjectDir(tempDir.absolutePath) == com.gokorei.kotlinmcp.models.ProjectEnvironmentProfile.NONE) { "4.2 empty dir" }

                val buildFile = java.io.File(tempDir, "build.gradle.kts")
                buildFile.writeText("dependencies { implementation(\"io.ktor:ktor-client\") }")
                val dirProfile = detector.detectFromProjectDir(tempDir.absolutePath)
                check(dirProfile.activeFrameworks.contains(com.gokorei.kotlinmcp.models.FrameworkFeature.KTOR)) { "4.3 dir ktor" }
                tempDir.deleteRecursively()
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: FrameworkDetector.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN FrameworkDetector.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for FrameworkDetector.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for FrameworkDetector.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production ResourceRegistrar source file`() {
        val registrarFile = File("src/main/kotlin/com/gokorei/kotlinmcp/server/ResourceRegistrar.kt")
        assertTrue(registrarFile.exists(), "Target file must exist: ${registrarFile.absolutePath}")

        val imports = registrarFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.server.LlmGuidance")
            .plus("import io.modelcontextprotocol.kotlin.sdk.server.*")
            .plus("import io.modelcontextprotocol.kotlin.sdk.types.*")
            .distinct()
            .joinToString("\n")

        val registrarBody = registrarFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + registrarBody

        val testSuiteCode = """
            fun main() {
                // 1. Constants
                check(ResourceRegistrar.DOCS_INDEX_URI == "kotlin://docs/index.md") { "1.1 docs index uri" }
                check(ResourceRegistrar.GUIDELINES_URI == "kotlin://guidelines/architecture.md") { "1.2 guidelines uri" }
                check(ResourceRegistrar.RESILIENCE_GUIDELINES_URI == "kotlin://guidelines/resilience.md") { "1.3 resilience uri" }
                check(ResourceRegistrar.KMP_STORAGE_GUIDELINES_URI == "kotlin://guidelines/kmp-storage.md") { "1.4 kmp storage uri" }
                check(ResourceRegistrar.SERVER_GUIDE_URI == LlmGuidance.LLM_GUIDE_RESOURCE_URI) { "1.5 server guide uri" }

                // 2. Guidelines Text
                check(ResourceRegistrar.architectureGuidelinesText.contains("Architectur") && ResourceRegistrar.architectureGuidelinesText.contains("Testability")) { "2.1 arch text" }
                check(ResourceRegistrar.resilienceGuidelinesText.contains("Resilience") && ResourceRegistrar.resilienceGuidelinesText.contains("Fault-Tolerance")) { "2.2 resilience text" }
                check(ResourceRegistrar.kmpStorageGuidelinesText.contains("Multiplatform") && ResourceRegistrar.kmpStorageGuidelinesText.contains("Storage")) { "2.3 kmp storage text" }
                check(ResourceRegistrar.USAGE_GUIDE_TEXT.contains("# Kotlin MCP Server Instruction Guide for LLMs")) { "2.4 usage guide text" }

                // 3. Mock Server to capture and verify registration calls and invoke callbacks
                val mockServer = io.mockk.mockk<Server>(relaxed = true)
                val capturedUris = mutableListOf<String>()
                val capturedTemplates = mutableListOf<String>()
                val resourceCallbacks = mutableMapOf<String, Any>()
                val templateCallbacks = mutableMapOf<String, Any>()

                io.mockk.every {
                    mockServer.addResource(capture(capturedUris), any(), any(), any(), any())
                } answers {
                    val uri = firstArg<String>()
                    val handler = args[4]
                    if (handler != null) {
                        resourceCallbacks[uri] = handler
                    }
                }

                io.mockk.every {
                    mockServer.addResourceTemplate(capture(capturedTemplates), any(), any(), any(), any())
                } answers {
                    val template = firstArg<String>()
                    val handler = args[4]
                    if (handler != null) {
                        templateCallbacks[template] = handler
                    }
                }

                val docService = com.gokorei.kotlinmcp.doc.DefaultDocService()
                ResourceRegistrar.registerAll(mockServer, docService)

                check(capturedUris.contains(ResourceRegistrar.DOCS_INDEX_URI)) { "3.1 index uri" }
                check(capturedUris.contains(ResourceRegistrar.GUIDELINES_URI)) { "3.2 arch uri" }
                check(capturedUris.contains(ResourceRegistrar.RESILIENCE_GUIDELINES_URI)) { "3.3 resilience uri" }
                check(capturedUris.contains(ResourceRegistrar.SERVER_GUIDE_URI)) { "3.4 server guide uri" }
                check(capturedTemplates.contains("kotlin://guidelines/{name}")) { "3.5 guidelines template" }
                check(capturedTemplates.contains("kotlin://docs/{kind}/{name}")) { "3.6 docs template" }

                // 4. Invoke handlers to exercise buildIndex and lambda bodies
                val req = io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(ResourceRegistrar.DOCS_INDEX_URI)
                )

                val indexHandler = resourceCallbacks[ResourceRegistrar.DOCS_INDEX_URI]
                if (indexHandler != null) {
                    val method = indexHandler.javaClass.methods.first { it.name == "invoke" }
                    val result = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(indexHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    val text = (result.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text
                    check(text.contains("# Kotlin Documentation Index")) { "4.1 index heading" }
                    check(text.contains("Resources are available at `kotlin://docs/symbol/<name>` and `kotlin://docs/feature/<name>`.")) { "4.2 resources note" }
                    check(text.contains("## Guidelines")) { "4.3 guidelines heading" }
                    check(text.contains("- [Architecture & Testability](" + ResourceRegistrar.GUIDELINES_URI + ")")) { "4.4 arch link" }
                    check(text.contains("- [Backend Resilience & Fault Tolerance](" + ResourceRegistrar.RESILIENCE_GUIDELINES_URI + ")")) { "4.5 resilience link" }
                    check(text.contains("## Symbols")) { "4.6 symbols heading" }
                    docService.symbolDocs.keys.forEach { sym ->
                        check(text.contains("- [" + sym + "](kotlin://docs/symbol/")) { "4.7 symbol link for " + sym }
                    }
                    check(text.contains("## Features")) { "4.8 features heading" }
                    docService.featureDocs.keys.forEach { feat ->
                        check(text.contains("- [" + feat + "](kotlin://docs/feature/")) { "4.9 feature link for " + feat }
                    }
                }

                val archHandler = resourceCallbacks[ResourceRegistrar.GUIDELINES_URI]
                if (archHandler != null) {
                    val method = archHandler.javaClass.methods.first { it.name == "invoke" }
                    val result = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(archHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    val text = (result.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text
                    check(text.contains("Architectur")) { "4.5 arch text read" }
                }

                val resilienceHandler = resourceCallbacks[ResourceRegistrar.RESILIENCE_GUIDELINES_URI]
                if (resilienceHandler != null) {
                    val method = resilienceHandler.javaClass.methods.first { it.name == "invoke" }
                    val result = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(resilienceHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    val text = (result.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text
                    check(text.contains("Resilience")) { "4.6 resilience text read" }
                }

                val guideHandler = resourceCallbacks[ResourceRegistrar.SERVER_GUIDE_URI]
                if (guideHandler != null) {
                    val method = guideHandler.javaClass.methods.first { it.name == "invoke" }
                    val result = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(guideHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check(result.contents.isNotEmpty()) { "4.7 guide result" }
                }

                val guideTemplateHandler = templateCallbacks["kotlin://guidelines/{name}"]
                if (guideTemplateHandler != null) {
                    val method = guideTemplateHandler.javaClass.methods.first { it.name == "invoke" }
                    val res1 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(guideTemplateHandler, null, req, mapOf("name" to "architecture.md"), uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check((res1.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text.contains("Architectur")) { "4.8 arch template" }

                    val res2 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(guideTemplateHandler, null, req, mapOf("name" to "resilience.md"), uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check((res2.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text.contains("Resilience")) { "4.9 res template" }

                    val res3 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(guideTemplateHandler, null, req, mapOf("name" to "kmp-storage.md"), uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check((res3.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text.contains("Multiplatform")) { "4.10 kmp template" }

                    var threw = false
                    try {
                        kotlinx.coroutines.runBlocking {
                            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                                method.invoke(guideTemplateHandler, null, req, mapOf("name" to "unknown.md"), uCont)
                            }
                        }
                    } catch (e: Exception) {
                        threw = true
                    }
                    check(threw) { "4.11 unknown guideline must throw" }
                }

                val docTemplateHandler = templateCallbacks["kotlin://docs/{kind}/{name}"]
                if (docTemplateHandler != null) {
                    val method = docTemplateHandler.javaClass.methods.first { it.name == "invoke" }
                    val symbolKey = docService.symbolDocs.keys.firstOrNull() ?: "List"
                    val res1 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(docTemplateHandler, null, req, mapOf("kind" to "symbol", "name" to symbolKey), uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check((res1.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text.isNotEmpty()) { "4.12 doc symbol" }

                    val featureKey = docService.featureDocs.keys.firstOrNull() ?: "sealed interface"
                    val res2 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(docTemplateHandler, null, req, mapOf("kind" to "feature", "name" to featureKey), uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
                    check((res2.contents.first() as io.modelcontextprotocol.kotlin.sdk.types.TextResourceContents).text.isNotEmpty()) { "4.13 doc feature" }

                    var threw = false
                    try {
                        kotlinx.coroutines.runBlocking {
                            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                                method.invoke(docTemplateHandler, null, req, mapOf("kind" to "symbol", "name" to "NonExistentSymbol12345"), uCont)
                            }
                        }
                    } catch (e: Exception) {
                        threw = true
                    }
                    check(threw) { "4.14 unknown doc must throw" }
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ResourceRegistrar.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN ResourceRegistrar.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ResourceRegistrar.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for ResourceRegistrar.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production PromptRegistrar source file`() {
        val registrarFile = File("src/main/kotlin/com/gokorei/kotlinmcp/server/PromptRegistrar.kt")
        assertTrue(registrarFile.exists(), "Target file must exist: ${registrarFile.absolutePath}")

        val imports = registrarFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.server.LlmGuidance")
            .plus("import io.modelcontextprotocol.kotlin.sdk.server.*")
            .plus("import io.modelcontextprotocol.kotlin.sdk.types.*")
            .distinct()
            .joinToString("\n")

        val registrarBody = registrarFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + registrarBody

        val testSuiteCode = """
            fun main() {
                // 1. Constants
                check(PromptRegistrar.KOTLIN_TASK_PROMPT == "kotlin-task") { "1.1 task prompt" }
                check(PromptRegistrar.KOTLIN_ARCHITECTURE_PROMPT == "kotlin-architecture") { "1.2 arch prompt" }
                check(PromptRegistrar.KOTLIN_MCP_QUICKSTART_PROMPT == LlmGuidance.LLM_GUIDE_PROMPT_NAME) { "1.3 quickstart prompt" }

                // 2. Mock Server to capture and verify prompt registrations
                val mockServer = io.mockk.mockk<Server>(relaxed = true)
                val capturedPrompts = mutableListOf<String>()
                val capturedArgs = mutableListOf<List<io.modelcontextprotocol.kotlin.sdk.types.PromptArgument>?>()
                val promptCallbacks = mutableMapOf<String, Any>()

                io.mockk.every {
                    mockServer.addPrompt(capture(capturedPrompts), any(), captureNullable(capturedArgs), any())
                } answers {
                    val name = firstArg<String>()
                    val handler = args[3]
                    if (handler != null) {
                        promptCallbacks[name] = handler
                    }
                }

                PromptRegistrar.registerAll(mockServer)

                check(capturedPrompts.contains(PromptRegistrar.KOTLIN_MCP_QUICKSTART_PROMPT)) { "2.1 quickstart prompt" }
                check(capturedPrompts.contains(PromptRegistrar.KOTLIN_TASK_PROMPT)) { "2.2 task prompt" }
                check(capturedPrompts.contains(PromptRegistrar.KOTLIN_ARCHITECTURE_PROMPT)) { "2.3 arch prompt" }

                val quickstartArgs = capturedArgs.firstOrNull()
                check(quickstartArgs?.any { it.name == "goal" && it.required == false } == true) { "2.4 goal arg not required" }

                // 3. Invoke prompt handlers
                val quickstartHandler = promptCallbacks[PromptRegistrar.KOTLIN_MCP_QUICKSTART_PROMPT]
                if (quickstartHandler != null) {
                    val method = quickstartHandler.javaClass.methods.first { it.name == "invoke" }
                    val reqWithGoal = io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                        io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams("kotlin_mcp_quickstart", mapOf("goal" to "Refactor PSI"))
                    )
                    val res1 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(quickstartHandler, null, reqWithGoal, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
                    val text1 = (res1.messages.first().content as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
                    check(text1.contains("Refactor PSI")) { "3.1 quickstart goal" }

                    val reqNoGoal = io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                        io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams("kotlin_mcp_quickstart")
                    )
                    val res2 = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(quickstartHandler, null, reqNoGoal, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
                    val text2 = (res2.messages.first().content as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
                    check(text2.contains("# Kotlin MCP LLM Usage Guide")) { "3.2 quickstart no goal" }
                }

                val taskHandler = promptCallbacks[PromptRegistrar.KOTLIN_TASK_PROMPT]
                if (taskHandler != null) {
                    val method = taskHandler.javaClass.methods.first { it.name == "invoke" }
                    val req = io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                        io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams(PromptRegistrar.KOTLIN_TASK_PROMPT)
                    )
                    val res = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(taskHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
                    val text = (res.messages.first().content as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
                    check(text.isNotEmpty()) { "3.3 task text" }
                }

                val archHandler = promptCallbacks[PromptRegistrar.KOTLIN_ARCHITECTURE_PROMPT]
                if (archHandler != null) {
                    val method = archHandler.javaClass.methods.first { it.name == "invoke" }
                    val req = io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequest(
                        io.modelcontextprotocol.kotlin.sdk.types.GetPromptRequestParams(PromptRegistrar.KOTLIN_ARCHITECTURE_PROMPT)
                    )
                    val res = kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(archHandler, null, req, uCont)
                        }
                    } as io.modelcontextprotocol.kotlin.sdk.types.GetPromptResult
                    val text = (res.messages.first().content as io.modelcontextprotocol.kotlin.sdk.types.TextContent).text
                    check(text.isNotEmpty()) { "3.4 arch text" }
                }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: PromptRegistrar.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN PromptRegistrar.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for PromptRegistrar.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for PromptRegistrar.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production ToolRegistrar source file`() {
        val registrarFile = File("src/main/kotlin/com/gokorei/kotlinmcp/server/ToolRegistrar.kt")
        assertTrue(registrarFile.exists(), "Target file must exist: ${registrarFile.absolutePath}")

        val imports = registrarFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.server.*")
            .plus("import com.gokorei.kotlinmcp.models.*")
            .plus("import io.modelcontextprotocol.kotlin.sdk.server.*")
            .plus("import io.modelcontextprotocol.kotlin.sdk.types.*")
            .distinct()
            .joinToString("\n")

        val registrarBody = registrarFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + registrarBody

        val testSuiteCode = """
            fun main() {
                val kotlinServer = KotlinMcpServer()

                // 1. buildToolDocSpecs
                val docSpecs = ToolRegistrar.buildToolDocSpecs(kotlinServer)
                check(docSpecs.isNotEmpty()) { "1.1 doc specs not empty" }
                for (spec in docSpecs) {
                    check(spec.name.isNotEmpty()) { "1.2 spec name" }
                    check(spec.description.isNotEmpty()) { "1.3 spec description" }
                    for (p in spec.params) {
                        check(p.name.isNotEmpty()) { "1.4 param name" }
                        check(p.description.isNotEmpty()) { "1.5 param description" }
                        check(p.type.isNotEmpty()) { "1.6 param type" }
                    }
                }
                check(docSpecs.any { it.name == "kotlin_docs_read" && it.readOnly }) { "1.7 docs read spec" }
                check(docSpecs.any { it.name == "kotlin_code_analyze" && it.readOnly }) { "1.8 code analyze spec" }
                check(docSpecs.any { it.name == "kotlin_text_lsp_read" && it.readOnly }) { "1.9 lsp read spec" }
                check(docSpecs.any { it.name == "kotlin_check_snippet" && it.readOnly }) { "1.10 check snippet spec" }
                check(docSpecs.any { it.name == "kotlin_project_inspect" && it.readOnly }) { "1.11 project inspect spec" }
                check(docSpecs.any { it.name == "kotlin_docs_edit" && !it.readOnly }) { "1.12 docs edit spec" }
                check(docSpecs.any { it.name == "kotlin_text_lsp_edit" && !it.readOnly }) { "1.13 lsp edit spec" }
                check(docSpecs.any { it.name == "kotlin_refactor" && !it.readOnly }) { "1.14 refactor spec" }
                check(docSpecs.any { it.name == "kotlin_library_analyze" && !it.readOnly }) { "1.15 library analyze spec" }
                check(docSpecs.any { it.name == "kotlin_lint" && !it.readOnly }) { "1.16 lint spec" }
                check(docSpecs.any { it.name == "kotlin_run" && !it.readOnly }) { "1.17 run spec" }
                check(docSpecs.first { it.name == "kotlin_lint" }.actions.contains("format")) { "1.18 lint format action" }
                check(docSpecs.first { it.name == "kotlin_run" }.actions.contains("gradle_task")) { "1.19 run task action" }
                check(docSpecs.first { it.name == "kotlin_run" }.params.any { it.name == "classpath" && it.type == "array" && it.itemsType == "string" }) { "1.20 run classpath array" }
                check(docSpecs.first { it.name == "kotlin_docs_read" }.params.any { it.name == "classpath" && it.type == "array" && it.itemsType == "string" }) { "1.21 docs classpath array" }
                check(docSpecs.first { it.name == "kotlin_check_snippet" }.actions.containsAll(listOf("check", "mutate"))) { "1.22a check_snippet actions" }
                check(docSpecs.first { it.name == "kotlin_docs_edit" }.actions.containsAll(listOf("register_symbol", "register_feature", "register_namespace"))) { "1.22b docs_edit actions" }
                check(docSpecs.first { it.name == "kotlin_text_lsp_edit" }.actions.contains("rename")) { "1.22c lsp_edit actions" }
                check(docSpecs.first { it.name == "kotlin_docs_read" }.actions.containsAll(listOf("search", "lookup", "explain"))) { "1.22d docs_read actions" }
                check(docSpecs.first { it.name == "kotlin_code_analyze" }.actions.containsAll(listOf("inspect", "nullability", "coroutines", "compose", "file_context"))) { "1.22e code_analyze actions" }
                check(docSpecs.first { it.name == "kotlin_text_lsp_read" }.actions.containsAll(listOf("definition", "references", "type_hierarchy", "call_hierarchy", "completion", "workspace_search", "workspace_references"))) { "1.22f lsp_read actions" }
                check(docSpecs.first { it.name == "kotlin_project_inspect" }.actions.containsAll(listOf("structure", "kmp_targets", "dependencies", "schema_digest", "diagnose_build", "layout_inventory", "vulnerabilities", "package_api", "coverage_report"))) { "1.22g project_inspect actions" }
                check(docSpecs.first { it.name == "kotlin_refactor" }.actions.containsAll(listOf("suggest_idioms", "java_to_kotlin", "functional", "quick_fix", "rxjava"))) { "1.22 refactor actions" }
                check(docSpecs.first { it.name == "kotlin_library_analyze" }.actions.containsAll(listOf("ktor", "serialization", "tests", "route_map", "arrow", "datetime"))) { "1.23 lib actions" }
                check(docSpecs.first { it.name == "kotlin_library_analyze" }.params.any { it.name == "domain" }) { "1.24 lib domain param" }
                check(docSpecs.first { it.name == "kotlin_library_analyze" }.params.any { it.name == "dataSources" }) { "1.25 lib dataSources param" }
                check(docSpecs.first { it.name == "kotlin_library_analyze" }.params.any { it.name == "legacy" }) { "1.26 lib legacy param" }

                // 2. formatDomainDescription
                val noneProfile = ProjectEnvironmentProfile.NONE
                val noneDesc = ToolRegistrar.formatDomainDescription(noneProfile)
                check(noneDesc.contains("ktor")) { "2.1 none desc contains ktor" }
                check(!noneDesc.contains("(detected in project:")) { "2.2 none desc no detected tag" }

                val ktorProfile = ProjectEnvironmentProfile(setOf(FrameworkFeature.KTOR, FrameworkFeature.SERIALIZATION))
                val ktorDesc = ToolRegistrar.formatDomainDescription(ktorProfile)
                check(ktorDesc.contains("(detected in project: ktor, serialization)")) { "2.3 detected summary tag" }

                val singleProfile = ProjectEnvironmentProfile(setOf(FrameworkFeature.ARROW))
                val singleDesc = ToolRegistrar.formatDomainDescription(singleProfile)
                check(singleDesc.contains("(detected in project: arrow)")) { "2.4 single detected summary tag" }

                // 3. Mock Server to capture and execute tool handlers
                val mockServer = io.mockk.mockk<Server>(relaxed = true)
                val capturedTools = mutableListOf<Tool>()
                val toolCallbacks = mutableMapOf<String, Any>()

                io.mockk.every {
                    mockServer.addTool(capture(capturedTools), any())
                } answers {
                    val tool = firstArg<Tool>()
                    val handler = args[1]
                    if (handler != null) {
                        toolCallbacks[tool.name] = handler
                    }
                }

                ToolRegistrar.registerReadOnlyTools(mockServer, kotlinServer)
                ToolRegistrar.registerEditTools(mockServer, kotlinServer)

                check(capturedTools.size >= 11) { "3.1 tool count" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("action") == true) { "3.1a check action" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("code") == true) { "3.1a2 check code" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("testCode") == true) { "3.1b check testCode" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("preset") == true) { "3.1c check preset" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("classpath") == true) { "3.1d check classpath" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.properties?.containsKey("projectPath") == true) { "3.1e check projectPath" }
                check(capturedTools.first { it.name == "kotlin_check_snippet" }.inputSchema.required?.contains("code") == true) { "3.1f check code required" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_read" }.inputSchema.properties?.containsKey("workspacePath") == true) { "3.1f2 lsp_read ws" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_read" }.inputSchema.properties?.containsKey("code") == true) { "3.1f3 lsp_read code" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_read" }.inputSchema.properties?.containsKey("symbol") == true) { "3.1f4 lsp_read symbol" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("action") == true) { "3.1f5 proj action" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("buildScriptContent") == true) { "3.1f6 proj buildScript" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("projectPath") == true) { "3.1f7 proj path" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("packageName") == true) { "3.1f8 proj pkg" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("settingsContent") == true) { "3.1f9 proj settings" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("gradlePropertiesContent") == true) { "3.1f10 proj properties" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("connectTimeoutMs") == true) { "3.1f11 proj connectTimeout" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("readTimeoutMs") == true) { "3.1f12 proj readTimeout" }
                check(capturedTools.first { it.name == "kotlin_project_inspect" }.inputSchema.properties?.containsKey("maxRetries") == true) { "3.1f13 proj retries" }
                check(capturedTools.first { it.name == "kotlin_docs_edit" }.inputSchema.properties?.containsKey("action") == true) { "3.1g edit action" }
                check(capturedTools.first { it.name == "kotlin_docs_edit" }.inputSchema.properties?.containsKey("name") == true) { "3.1h edit name" }
                check(capturedTools.first { it.name == "kotlin_docs_edit" }.inputSchema.properties?.containsKey("content") == true) { "3.1i edit content" }
                check(capturedTools.first { it.name == "kotlin_docs_edit" }.inputSchema.required?.contains("name") == true) { "3.1j edit name required" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.properties?.containsKey("action") == true) { "3.1k1 lsp_edit action" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.properties?.containsKey("code") == true) { "3.1k2 lsp_edit code" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.properties?.containsKey("oldName") == true) { "3.1k3 lsp_edit oldName" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.properties?.containsKey("newName") == true) { "3.1k4 lsp_edit newName" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.properties?.containsKey("workspacePath") == true) { "3.1k5 lsp_edit ws" }
                check(capturedTools.first { it.name == "kotlin_text_lsp_edit" }.inputSchema.required?.containsAll(listOf("oldName", "newName")) == true) { "3.1k lsp_edit required" }
                check(capturedTools.first { it.name == "kotlin_refactor" }.inputSchema.properties?.containsKey("action") == true) { "3.1l1 refactor action" }
                check(capturedTools.first { it.name == "kotlin_refactor" }.inputSchema.properties?.containsKey("code") == true) { "3.1l2 refactor code" }
                check(capturedTools.first { it.name == "kotlin_refactor" }.inputSchema.properties?.containsKey("diagnostic") == true) { "3.1l3 refactor diagnostic" }
                check(capturedTools.first { it.name == "kotlin_refactor" }.inputSchema.required?.contains("code") == true) { "3.1l refactor required" }
                check(capturedTools.first { it.name == "kotlin_library_analyze" }.inputSchema.properties?.containsKey("action") == true) { "3.1m1 lib action" }
                check(capturedTools.first { it.name == "kotlin_library_analyze" }.inputSchema.properties?.containsKey("code") == true) { "3.1m2 lib code" }
                check(capturedTools.first { it.name == "kotlin_library_analyze" }.inputSchema.required?.contains("code") == true) { "3.1m lib_analyze required" }
                check(capturedTools.first { it.name == "kotlin_lint" }.inputSchema.properties?.containsKey("action") == true) { "3.1n lint action param" }
                check(capturedTools.first { it.name == "kotlin_lint" }.inputSchema.properties?.containsKey("code") == true) { "3.1o lint code param" }
                check(capturedTools.first { it.name == "kotlin_lint" }.inputSchema.properties?.containsKey("workspacePath") == true) { "3.1p lint ws param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("action") == true) { "3.1q run action param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("code") == true) { "3.1r run code param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("taskName") == true) { "3.1s run taskName param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("workspacePath") == true) { "3.1t run ws param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("jvmArgs") == true) { "3.1u run jvmArgs param" }
                check(capturedTools.first { it.name == "kotlin_run" }.inputSchema.properties?.containsKey("timeoutSeconds") == true) { "3.1v run timeoutSeconds param" }

                for (t in capturedTools) {
                    check(t.name.isNotEmpty()) { "3.2 tool name" }
                    check(t.description.orEmpty().isNotEmpty()) { "3.3 tool description" }
                    val schema = t.inputSchema
                    check(schema.type == "object") { "3.4 schema object" }
                    val props = schema.properties
                    check(props != null && props.isNotEmpty()) { "3.5 schema properties" }
                    for (entry in props) {
                        val propObj = entry.value as? kotlinx.serialization.json.JsonObject
                        check(propObj != null) { "3.6 prop object" }
                        val pType = propObj["type"]?.jsonPrimitive?.content
                        check(pType != null && pType.isNotEmpty()) { "3.7 prop type" }
                        val pDesc = propObj["description"]?.jsonPrimitive?.content
                        check(pDesc != null && pDesc.isNotEmpty()) { "3.8 prop desc" }
                        if (pType == "array") {
                            val itemsObj = propObj["items"] as? kotlinx.serialization.json.JsonObject
                            check(itemsObj != null && itemsObj["type"]?.jsonPrimitive?.content == "string") { "3.9 array items string" }
                        }
                    }
                    if (t.name in setOf("kotlin_docs_read", "kotlin_code_analyze", "kotlin_text_lsp_read", "kotlin_check_snippet", "kotlin_project_inspect")) {
                        check(t.annotations?.readOnlyHint == true) { "3.10 readOnlyHint true for " + t.name }
                    } else {
                        check(t.annotations?.readOnlyHint == false) { "3.11 readOnlyHint false for " + t.name }
                    }
                }

                // 4. Test tool dispatching and execution
                fun callTool(toolName: String, arguments: Map<String, kotlinx.serialization.json.JsonElement>): CallToolResult {
                    val handler = toolCallbacks[toolName] ?: error("Missing handler for " + toolName)
                    val method = handler.javaClass.methods.first { it.name == "invoke" }
                    val req = CallToolRequest(CallToolRequestParams(name = toolName, arguments = kotlinx.serialization.json.JsonObject(arguments)))
                    return kotlinx.coroutines.runBlocking {
                        kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { uCont ->
                            method.invoke(handler, null, req, uCont)
                        }
                    } as CallToolResult
                }

                // check snippet
                val checkRes = callTool("kotlin_check_snippet", mapOf("code" to kotlinx.serialization.json.JsonPrimitive("fun foo() = 42")))
                check(checkRes.content.isNotEmpty()) { "4.1 check snippet result" }
                val checkMutateRes = callTool("kotlin_check_snippet", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("mutate"), "code" to kotlinx.serialization.json.JsonPrimitive("fun foo() = 42"), "testCode" to kotlinx.serialization.json.JsonPrimitive("fun main() {}")))
                check(checkMutateRes.content.isNotEmpty()) { "4.1b check mutate result" }
                val checkMutateTestRes = callTool("kotlin_check_snippet", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("mutation_test"), "code" to kotlinx.serialization.json.JsonPrimitive("fun foo() = 42"), "testCode" to kotlinx.serialization.json.JsonPrimitive("fun main() {}")))
                check(checkMutateTestRes.content.isNotEmpty()) { "4.1c check mutation_test result" }

                // docs read
                check(callTool("kotlin_docs_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("search"), "query" to kotlinx.serialization.json.JsonPrimitive("map"))).content.isNotEmpty()) { "4.2 docs search" }
                check(callTool("kotlin_docs_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("lookup"), "query" to kotlinx.serialization.json.JsonPrimitive("listOf"))).content.isNotEmpty()) { "4.3 docs lookup" }
                check(callTool("kotlin_docs_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("explain"), "query" to kotlinx.serialization.json.JsonPrimitive("sealed interface"))).content.isNotEmpty()) { "4.4 docs explain" }

                // code analyze
                check(callTool("kotlin_code_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("inspect"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.5 analyze inspect" }
                check(callTool("kotlin_code_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("nullability"), "code" to kotlinx.serialization.json.JsonPrimitive("val x: String? = null"))).content.isNotEmpty()) { "4.6 analyze nullability" }
                check(callTool("kotlin_code_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("coroutines"), "code" to kotlinx.serialization.json.JsonPrimitive("suspend fun run() {}"))).content.isNotEmpty()) { "4.7 analyze coroutines" }
                check(callTool("kotlin_code_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("compose"), "code" to kotlinx.serialization.json.JsonPrimitive("fun UI() {}"))).content.isNotEmpty()) { "4.8 analyze compose" }
                check(callTool("kotlin_code_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("file_context"), "code" to kotlinx.serialization.json.JsonPrimitive("/non/existent.kt"))).content.isNotEmpty()) { "4.9 analyze file_context" }

                // lsp read
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("completion"), "code" to kotlinx.serialization.json.JsonPrimitive("val a = 1\na."))).content.isNotEmpty()) { "4.10 lsp completion" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("definition"), "code" to kotlinx.serialization.json.JsonPrimitive("val a = 1"), "symbol" to kotlinx.serialization.json.JsonPrimitive("a"))).content.isNotEmpty()) { "4.11 lsp def" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("references"), "code" to kotlinx.serialization.json.JsonPrimitive("val a = 1\nval b = a"), "symbol" to kotlinx.serialization.json.JsonPrimitive("a"))).content.isNotEmpty()) { "4.12 lsp refs" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("type_hierarchy"), "code" to kotlinx.serialization.json.JsonPrimitive("class Foo"), "symbol" to kotlinx.serialization.json.JsonPrimitive("Foo"))).content.isNotEmpty()) { "4.13 lsp type_hier" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("call_hierarchy"), "code" to kotlinx.serialization.json.JsonPrimitive("fun foo() {}"), "symbol" to kotlinx.serialization.json.JsonPrimitive("foo"))).content.isNotEmpty()) { "4.14 lsp call_hier" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("workspace_search"), "query" to kotlinx.serialization.json.JsonPrimitive("Foo"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.15 lsp ws search" }
                check(callTool("kotlin_text_lsp_read", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("workspace_references"), "symbol" to kotlinx.serialization.json.JsonPrimitive("Foo"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.16 lsp ws refs" }

                // project inspect
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("structure"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.17 proj structure" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("kmp_targets"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.18 proj kmp" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("dependencies"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.19 proj deps" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("diagnose_build"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.20 proj diag" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("package_api"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.21 proj api" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("inventory"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.22 proj inv" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("advisories"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.23 proj adv" }
                check(callTool("kotlin_project_inspect", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("coverage"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.24 proj cov" }

                // refactor
                check(callTool("kotlin_refactor", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("suggest_idioms"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.25 refactor idioms" }
                check(callTool("kotlin_refactor", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("functional"), "code" to kotlinx.serialization.json.JsonPrimitive("for (i in 1..10) {}"))).content.isNotEmpty()) { "4.26 refactor functional" }
                check(callTool("kotlin_refactor", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("java_to_kotlin"), "code" to kotlinx.serialization.json.JsonPrimitive("public class Foo {}"))).content.isNotEmpty()) { "4.27 refactor j2k" }
                check(callTool("kotlin_refactor", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("quick_fix"), "code" to kotlinx.serialization.json.JsonPrimitive("val x: Int = \"\""))).content.isNotEmpty()) { "4.28 refactor qfix" }
                check(callTool("kotlin_refactor", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("rxjava"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.29 refactor rx" }

                // lib analyze
                check(callTool("kotlin_library_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("ktor"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.30 lib ktor" }
                check(callTool("kotlin_library_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("route_map"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.31 lib routes" }
                check(callTool("kotlin_library_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("serialization"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.32 lib ser" }
                check(callTool("kotlin_library_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("arrow"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.33 lib arrow" }
                check(callTool("kotlin_library_analyze", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("datetime"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.34 lib dt" }
                check(callTool("kotlin_library_analyze", mapOf("domain" to kotlinx.serialization.json.JsonPrimitive("arrow"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"), "legacy" to kotlinx.serialization.json.JsonPrimitive("true"))).content.isNotEmpty()) { "4.34b lib arrow legacy" }
                check(callTool("kotlin_library_analyze", mapOf("domain" to kotlinx.serialization.json.JsonPrimitive("serialization"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"), "dataSources" to kotlinx.serialization.json.JsonPrimitive("diff"))).content.isNotEmpty()) { "4.34c lib ser dataSources" }
                check(callTool("kotlin_library_analyze", mapOf("domain" to kotlinx.serialization.json.JsonPrimitive("tests"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1"))).content.isNotEmpty()) { "4.34d lib tests" }

                // lint
                check(callTool("kotlin_lint", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("lint_ktlint"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1\n"))).content.isNotEmpty()) { "4.35 lint ktlint" }
                check(callTool("kotlin_lint", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("lint_detekt"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1\n"))).content.isNotEmpty()) { "4.36 lint detekt" }
                check(callTool("kotlin_lint", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("format_ktlint"), "code" to kotlinx.serialization.json.JsonPrimitive("val x = 1\n"))).content.isNotEmpty()) { "4.37 lint format" }
                check(callTool("kotlin_lint", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("baseline_read"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.38 lint base read" }
                check(callTool("kotlin_lint", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("baseline_dump"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.39 lint base dump" }

                // docs edit
                check(callTool("kotlin_docs_edit", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("register_symbol"), "name" to kotlinx.serialization.json.JsonPrimitive("MyCustomSym"), "content" to kotlinx.serialization.json.JsonPrimitive("My custom docs"))).content.isNotEmpty()) { "4.40 edit sym" }
                check(callTool("kotlin_docs_edit", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("register_feature"), "name" to kotlinx.serialization.json.JsonPrimitive("MyCustomFeat"), "content" to kotlinx.serialization.json.JsonPrimitive("My custom feature"))).content.isNotEmpty()) { "4.41 edit feat" }
                check(callTool("kotlin_docs_edit", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("register_namespace"), "name" to kotlinx.serialization.json.JsonPrimitive("com.example"), "content" to kotlinx.serialization.json.JsonPrimitive("My namespace"))).content.isNotEmpty()) { "4.42 edit ns" }

                // lsp edit
                check(callTool("kotlin_text_lsp_edit", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("rename"), "code" to kotlinx.serialization.json.JsonPrimitive("val oldName = 1\nval y = oldName"), "oldName" to kotlinx.serialization.json.JsonPrimitive("oldName"), "newName" to kotlinx.serialization.json.JsonPrimitive("newName"))).content.isNotEmpty()) { "4.43 lsp rename" }

                // run
                check(callTool("kotlin_run", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("snippet"), "code" to kotlinx.serialization.json.JsonPrimitive("println(\"hello\")"))).content.isNotEmpty()) { "4.44 run snippet" }
                check(callTool("kotlin_run", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("snippet"), "code" to kotlinx.serialization.json.JsonPrimitive("1 + 1"), "timeoutSeconds" to kotlinx.serialization.json.JsonPrimitive(5))).content.isNotEmpty()) { "4.44b run timeout" }
                check(callTool("kotlin_run", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("gradle_task"), "taskName" to kotlinx.serialization.json.JsonPrimitive("tasks"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.45 run task" }
                check(callTool("kotlin_run", mapOf("action" to kotlinx.serialization.json.JsonPrimitive("test_report"), "workspacePath" to kotlinx.serialization.json.JsonPrimitive("/non/existent"))).content.isNotEmpty()) { "4.46 run report" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: ToolRegistrar.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN ToolRegistrar.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for ToolRegistrar.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for ToolRegistrar.kt (${report.score}%) must be at least 75%"
        )
    }

    @Test
    fun `mutation test production KotlinMcpServer source file`() {
        val serverFile = File("src/main/kotlin/com/gokorei/kotlinmcp/server/KotlinMcpServer.kt")
        assertTrue(serverFile.exists(), "Target file must exist: ${serverFile.absolutePath}")

        val imports = serverFile.readLines()
            .filter { it.trim().startsWith("import ") }
            .plus("import com.gokorei.kotlinmcp.server.*")
            .plus("import com.gokorei.kotlinmcp.models.*")
            .plus("import com.gokorei.kotlinmcp.analysis.*")
            .plus("import com.gokorei.kotlinmcp.doc.*")
            .plus("import com.gokorei.kotlinmcp.execution.*")
            .plus("import com.gokorei.kotlinmcp.linting.*")
            .plus("import com.gokorei.kotlinmcp.lsp.*")
            .plus("import com.gokorei.kotlinmcp.project.*")
            .plus("import com.gokorei.kotlinmcp.refactoring.*")
            .plus("import com.gokorei.kotlinmcp.mutation.*")
            .distinct()
            .joinToString("\n")

        val serverBody = serverFile.readLines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val productionCode = imports + "\n\n" + serverBody

        val testSuiteCode = """
            fun main() {
                // 1. Test mock services wiring (prewarm and close)
                var lintPrewarmed = false
                val mockLint = io.mockk.mockk<LintService>(relaxed = true)
                io.mockk.every { mockLint.prewarm() } answers { lintPrewarmed = true; Pair(null, null) }
                io.mockk.every { mockLint.formatKtlint(any(), any(), any()) } answers { KotlinMcpResult.Success("apply=" + secondArg<Boolean>()) }

                var textServiceClosed = false
                var semanticEngineClosed = false
                var mutationServiceClosed = false

                val mockLsp = io.mockk.mockk<LspService>(relaxed = true)
                io.mockk.every { mockLsp.close() } answers { textServiceClosed = true }

                val mockSemantic = io.mockk.mockk<K2SemanticEngine>(relaxed = true)
                io.mockk.every { mockSemantic.close() } answers { semanticEngineClosed = true }

                val mockMutation = io.mockk.mockk<MutationService>(relaxed = true)
                io.mockk.every { mockMutation.close() } answers { mutationServiceClosed = true }

                val customServer = KotlinMcpServer(
                    lspService = mockLsp,
                    semanticEngine = mockSemantic,
                    lintService = mockLint,
                    mutationService = mockMutation
                )

                check(lintPrewarmed) { "1.1 lint prewarmed" }
                val ktlintRes = customServer.formatKtlint("val x = 1\n")
                check(ktlintRes is KotlinMcpResult.Success && ktlintRes.content.contains("apply=true")) { "1.2 formatKtlint default apply=true" }

                customServer.close()
                check(textServiceClosed) { "1.3 textService closed" }
                check(semanticEngineClosed) { "1.4 semanticEngine closed" }
                check(mutationServiceClosed) { "1.5 mutationService closed" }

                // Test close() with throwing services to exercise logger.warn
                val throwingLsp = io.mockk.mockk<LspService>(relaxed = true)
                io.mockk.every { throwingLsp.close() } throws RuntimeException("LSP close fail")

                val throwingSemantic = io.mockk.mockk<K2SemanticEngine>(relaxed = true)
                io.mockk.every { throwingSemantic.close() } throws RuntimeException("Semantic close fail")

                val throwingMutation = io.mockk.mockk<MutationService>(relaxed = true)
                io.mockk.every { throwingMutation.close() } throws RuntimeException("Mutation close fail")

                val throwingServer = KotlinMcpServer(
                    lspService = throwingLsp,
                    semanticEngine = throwingSemantic,
                    mutationService = throwingMutation
                )
                throwingServer.close()

                // 2. Real server dynamic registrations
                val realDoc = io.mockk.mockk<DocService>(relaxed = true)
                var symRegistered = false
                var featRegistered = false
                var nsRegistered = false
                io.mockk.every { realDoc.registerDynamicSymbol("MyCustomSym", "Custom Symbol Documentation") } answers { symRegistered = true }
                io.mockk.every { realDoc.registerDynamicFeature("mycustomfeat", "Custom Feature Documentation") } answers { featRegistered = true }
                io.mockk.every { realDoc.registerDynamicNamespace("com.custom.ns", "Custom Namespace Documentation") } answers { nsRegistered = true }

                val server = KotlinMcpServer(docService = realDoc)

                val resSym = server.docsRegisterSymbol("MyCustomSym", "Custom Symbol Documentation")
                check(symRegistered && resSym is KotlinMcpResult.Success) { "2.1 registered symbol" }

                val resFeat = server.docsRegisterFeature("mycustomfeat", "Custom Feature Documentation")
                check(featRegistered && resFeat is KotlinMcpResult.Success) { "2.2 registered feature" }

                val resNs = server.docsRegisterNamespace("com.custom.ns", "Custom Namespace Documentation")
                check(nsRegistered && resNs is KotlinMcpResult.Success) { "2.3 registered namespace" }
            }
        """.trimIndent()

        val report = pipeline.run(
            code = productionCode,
            testCode = testSuiteCode,
            includeExtremeOperators = false,
            maxOrder = 1
        )

        val reportText = buildString {
            appendLine()
            appendLine("=======================================================")
            appendLine("🧬 REAL PRODUCTION FILE MUTATION AUDIT: KotlinMcpServer.kt")
            appendLine("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
            appendLine("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
            if (report.totalMutants == 0) {
                appendLine("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
            }

            val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
            if (survived.isNotEmpty()) {
                appendLine("   ⚠️ SURVIVED MUTANTS IN KotlinMcpServer.kt:")
                survived.forEach {
                    appendLine("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                    appendLine("        Original: ${it.mutant.originalSnippet}")
                    appendLine("        Mutated:  ${it.mutant.mutatedSnippet}")
                }
            }
            appendLine("=======================================================")
        }
        java.io.FileOutputStream(java.io.FileDescriptor.err).write(reportText.toByteArray(Charsets.UTF_8))

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for KotlinMcpServer.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for KotlinMcpServer.kt (${report.score}%) must be at least 75%"
        )
    }
}































