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
            report.score >= 85.0,
            "Mutation score for SourceUtils.kt (${report.score}%) must be at least 85%"
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
        val resultFile = File("src/main/kotlin/com/gokorei/kotlinmcp/models/KotlinMcpResult.kt")
        val auditorFile = File("src/main/kotlin/com/gokorei/kotlinmcp/project/VulnerabilityAuditor.kt")
        assertTrue(resultFile.exists(), "Target file must exist: ${resultFile.absolutePath}")
        assertTrue(auditorFile.exists(), "Target file must exist: ${auditorFile.absolutePath}")

        val resultSource = resultFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .filterNot { it.trim() == "@Serializable" }
            .joinToString("\n")

        val auditorSource = auditorFile.readText()
            .lines()
            .filterNot { it.trim().startsWith("package ") }
            .filterNot { it.trim().startsWith("import ") }
            .joinToString("\n")

        val imports = """
            import java.io.File
            import kotlinx.serialization.json.Json
            import kotlinx.serialization.json.JsonArray
            import kotlinx.serialization.json.JsonObject
            import kotlinx.serialization.json.JsonPrimitive
        """.trimIndent()

        val productionCode = imports + "\n\n" + resultSource + "\n\n" + auditorSource

        val testSuiteCode = """
            fun main() {
                val auditor = VulnerabilityAuditor()

                // Result envelope verification
                val testErr = KotlinMcpResult.Error("msg", "CODE", mapOf("k" to "v"), true)
                check(testErr.isError && !testErr.isSuccess) { "testErr error flags" }
                val errText = testErr.toFormattedText()
                check(errText.contains("Error [CODE]: msg") && errText.contains(" - k: v") && errText.contains("requireAnotherCall: true")) { "err formatted text" }
                val defaultErr = KotlinMcpResult.Error("msg")
                check(!defaultErr.requireAnotherCall) { "default requireAnotherCall false" }

                val testSucc = KotlinMcpResult.Success("ok", mapOf("x" to "y"), true)
                check(testSucc.isSuccess && !testSucc.isError) { "testSucc success flags" }
                check(testSucc.toFormattedText().contains("ok\n\n--- Metadata ---\nx: y\n\nrequireAnotherCall: true")) { "testSucc text" }
                val defaultSucc = KotlinMcpResult.Success("ok")
                check(!defaultSucc.requireAnotherCall) { "default requireAnotherCall false" }

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

                // 2. Unparseable build script error
                val emptyResult = auditor.checkVulnerabilities("", null)
                check(emptyResult is KotlinMcpResult.Error) { "empty script must be Error" }
                check(emptyResult.code == "TOOL_UNAVAILABLE") { "empty code TOOL_UNAVAILABLE" }
                check(emptyResult.details["parsedCoordinateCount"] == "0") { "0 parsed coordinates" }

                // 3. Mixed scan (Vulnerable Log4j + Clean OkHttp in single project)
                val mixedScript = ""${'"'}
                    dependencies {
                        implementation("org.apache.logging.log4j:log4j-core:2.14.0")
                        api("com.squareup.okhttp3:okhttp:4.12.0")
                        kapt(platform("org.yaml:snakeyaml:1.30"))
                    }
                ""${'"'}.trimIndent()
                val mixedResult = auditor.checkVulnerabilities(mixedScript, null)
                check(mixedResult is KotlinMcpResult.Success) { "mixed result must be Success" }
                check(mixedResult.metadata["source"] == "local-baseline (offline fallback)") { "offline fallback source" }
                check(mixedResult.metadata["scannedCoordinateCount"] == "3") { "3 coordinates scanned" }
                check(mixedResult.metadata["advisoryCount"] == "2") { "2 advisories found" }
                val mContent = mixedResult.content
                check(mContent.contains("Scanned 3 dependency coordinate(s). (source: local-baseline (offline fallback))")) { "scan summary" }
                check(mContent.contains("## 🚨 Flagged Security Advisories (2)")) { "flagged section" }
                check(mContent.contains("CVE-2021-44228")) { "log4shell cve" }
                check(mContent.contains("CVE-2022-1471")) { "snakeyaml cve" }
                check(mContent.contains("## Scanned Clean Dependencies (1)")) { "clean count" }
                check(mContent.contains(" - `com.squareup.okhttp3:okhttp:4.12.0`")) { "clean okhttp" }

                // 4. File-based scan with projectPath (libs.versions.toml + lockfile + plugins)
                val tmpDir = java.io.File.createTempFile("audit_proj_", "")
                tmpDir.delete()
                tmpDir.mkdirs()

                val gradleDir = java.io.File(tmpDir, "gradle")
                gradleDir.mkdirs()
                val libsFile = java.io.File(gradleDir, "libs.versions.toml")
                libsFile.writeText(""${'"'}
                    [versions]
                    jackson = "2.14.0"
                    [libraries]
                    jackson-databind = { module = "com.fasterxml.jackson.core:jackson-databind", version.ref = "jackson" }
                ""${'"'}.trimIndent())

                val lockFile = java.io.File(tmpDir, "gradle.lockfile")
                lockFile.writeText("io.netty:netty-codec-http:4.1.100.Final=classpath\n")

                val bgFile = java.io.File(tmpDir, "build.gradle.kts")
                bgFile.writeText(""${'"'}
                    plugins {
                        id("org.jetbrains.kotlin.jvm") version "1.9.20"
                        kotlin("multiplatform") version "1.9.20"
                    }
                    dependencies {
                        implementation(libs.jackson.databind)
                    }
                ""${'"'}.trimIndent())

                val projectResult = auditor.checkVulnerabilities("", tmpDir.path)
                check(projectResult is KotlinMcpResult.Success) { "projectResult must be Success" }
                val pContent = projectResult.content
                check(pContent.contains("CVE-2023-35116") || pContent.contains("CVE-2024-29025")) { "project vulnerabilities caught" }

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
        println("🧬 REAL PRODUCTION FILE MUTATION AUDIT: VulnerabilityAuditor.kt")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")
        if (report.totalMutants == 0) {
            println("   BASELINE ERROR: ${report.results.firstOrNull()?.details}")
        }

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN VulnerabilityAuditor.kt:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for VulnerabilityAuditor.kt")
        assertTrue(
            report.score >= 75.0,
            "Mutation score for VulnerabilityAuditor.kt (${report.score}%) must be at least 75% (offline baseline mode)"
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
}













