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
}




