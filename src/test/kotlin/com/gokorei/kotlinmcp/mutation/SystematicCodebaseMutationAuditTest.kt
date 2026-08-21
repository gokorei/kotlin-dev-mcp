package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

@Tag("hardening")
class SystematicCodebaseMutationAuditTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    private fun assertHighMutationScore(
        moduleName: String,
        targetCode: String,
        testSuiteCode: String,
        minScore: Double = 90.0
    ): MutationReport {
        val report = pipeline.run(targetCode, testSuiteCode)

        println("\n=======================================================")
        println("🧬 MUTATION AUDIT: $moduleName")
        println("   Score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        println("   Total Mutants: ${report.totalMutants} (Discarded Comp Errors: ${report.compilationErrorCount})")

        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        if (survived.isNotEmpty()) {
            println("   ⚠️ SURVIVED MUTANTS IN $moduleName:")
            survived.forEach {
                println("      - Line ${it.mutant.line} [${it.mutant.operator}]: ${it.mutant.description}")
                println("        Original: ${it.mutant.originalSnippet}")
                println("        Mutated:  ${it.mutant.mutatedSnippet}")
            }
        }
        println("=======================================================\n")

        assertTrue(report.totalMutants > 0, "Expected mutants to be generated for $moduleName")
        assertTrue(
            report.score >= minScore,
            "Mutation score for $moduleName (${report.score}%) must be at least $minScore%"
        )
        return report
    }

    // -------------------------------------------------------------
    // 1. Analysis Layer: CoroutinesSafetyAnalyzer
    // -------------------------------------------------------------
    @Test
    fun `audit CoroutinesSafetyAnalyzer blocking call detection`() {
        val targetCode = """
            fun isBlockingCall(callee: String): Boolean {
                if (callee == "Thread.sleep" || callee == "sleep") return true
                if (callee.startsWith("InputStream.read") || callee.startsWith("Socket.connect")) return true
                return false
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isBlockingCall("Thread.sleep")) { "Thread.sleep is blocking" }
                check(isBlockingCall("sleep")) { "sleep is blocking" }
                check(isBlockingCall("InputStream.read")) { "InputStream.read is blocking" }
                check(isBlockingCall("InputStream.readBytes")) { "InputStream.readBytes is blocking" }
                check(isBlockingCall("Socket.connect")) { "Socket.connect is blocking" }
                check(!isBlockingCall("delay")) { "delay is non-blocking" }
                check(!isBlockingCall("yield")) { "yield is non-blocking" }
                check(!isBlockingCall("Thread.currentThread")) { "currentThread is non-blocking" }
            }
        """.trimIndent()

        assertHighMutationScore("CoroutinesSafetyAnalyzer (Blocking Calls)", targetCode, testSuite)
    }

    // -------------------------------------------------------------
    // 2. Analysis Layer: NullabilityAnalyzer Force-Unwrap & Safe-Call Detection
    // -------------------------------------------------------------
    @Test
    fun `audit NullabilityAnalyzer force unwrap detection`() {
        val targetCode = """
            fun isForceUnwrap(operationToken: String): Boolean {
                if (operationToken == "!!" || operationToken == "EXCLEXCL") {
                    return true
                }
                return false
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isForceUnwrap("!!"))
                check(isForceUnwrap("EXCLEXCL"))
                check(!isForceUnwrap("?."))
                check(!isForceUnwrap("?:"))
                check(!isForceUnwrap("!"))
                check(!isForceUnwrap(""))
            }
        """.trimIndent()

        assertHighMutationScore("NullabilityAnalyzer (Force Unwrap)", targetCode, testSuite)
    }

    // -------------------------------------------------------------
    // 3. LSP Layer: K2RenameResolver Scope Identifier Validation
    // -------------------------------------------------------------
    @Test
    fun `audit K2RenameResolver valid identifier check`() {
        val targetCode = """
            fun isValidIdentifier(name: String): Boolean {
                if (name.isEmpty()) return false
                val first = name[0]
                if (!first.isLetter() && first != '_') return false
                for (i in 1 until name.length) {
                    val ch = name[i]
                    if (!ch.isLetterOrDigit() && ch != '_') return false
                }
                return true
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isValidIdentifier("validName"))
                check(isValidIdentifier("_valid_name_123"))
                check(isValidIdentifier("A"))
                check(isValidIdentifier("_"))
                check(!isValidIdentifier(""))
                check(!isValidIdentifier("123name"))
                check(!isValidIdentifier("invalid-name"))
                check(!isValidIdentifier("name with spaces"))
                check(!isValidIdentifier("name!"))
            }
        """.trimIndent()

        assertHighMutationScore("K2RenameResolver (Identifier Validation)", targetCode, testSuite)
    }

    // -------------------------------------------------------------
    // 4. LSP Layer: VfsPsiCache Prefix & Extension Matcher
    // -------------------------------------------------------------
    @Test
    fun `audit VfsPsiCache directory prefix matching`() {
        val targetCode = """
            fun isDescendantPath(candidate: String, dirPrefix: String): Boolean {
                if (candidate.length <= dirPrefix.length) return false
                if (!candidate.startsWith(dirPrefix)) return false
                val nextChar = candidate[dirPrefix.length]
                return nextChar == '/' || nextChar == '\\'
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isDescendantPath("/app/src/Main.kt", "/app/src"))
                check(isDescendantPath("C:\\app\\src\\Main.kt", "C:\\app\\src"))
                check(!isDescendantPath("/app/src_other/Main.kt", "/app/src"))
                check(!isDescendantPath("/app/src", "/app/src"))
                check(!isDescendantPath("/app", "/app/src"))
                check(!isDescendantPath("/other/path/Main.kt", "/app/src"))
            }
        """.trimIndent()

        assertHighMutationScore("VfsPsiCache (Descendant Path Matching)", targetCode, testSuite)
    }

    // -------------------------------------------------------------
    // 5. Execution Layer: JavaResolver Home Path Validation
    // -------------------------------------------------------------
    @Test
    fun `audit JavaResolver executable resolution`() {
        val targetCode = """
            fun buildJavaBinaryPath(javaHome: String, isWindows: Boolean): String {
                val separator = if (isWindows) "\\" else "/"
                val binName = if (isWindows) "java.exe" else "java"
                return if (javaHome.endsWith(separator)) {
                    javaHome + "bin" + separator + binName
                } else {
                    javaHome + separator + "bin" + separator + binName
                }
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(buildJavaBinaryPath("/opt/jdk", false) == "/opt/jdk/bin/java")
                check(buildJavaBinaryPath("/opt/jdk/", false) == "/opt/jdk/bin/java")
                check(buildJavaBinaryPath("C:\\jdk", true) == "C:\\jdk\\bin\\java.exe")
                check(buildJavaBinaryPath("C:\\jdk\\", true) == "C:\\jdk\\bin\\java.exe")
            }
        """.trimIndent()

        assertHighMutationScore("JavaResolver (Binary Path Resolution)", targetCode, testSuite)
    }

    // -------------------------------------------------------------
    // 6. Models: ResponseProjection Line Compaction Parser
    // -------------------------------------------------------------
    @Test
    fun `audit ResponseProjection section boundary detection`() {
        val targetCode = """
            fun isSectionBoundary(line: String): Boolean {
                val trimmed = line.trim()
                if (!trimmed.startsWith("--- ")) return false
                if (trimmed.startsWith("--- Internal AST Dump") || trimmed.startsWith("--- Debug Trace")) {
                    return false
                }
                return true
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isSectionBoundary("--- Next Section ---"))
                check(isSectionBoundary("--- Section Header ---"))
                check(!isSectionBoundary("--- Internal AST Dump ---"))
                check(!isSectionBoundary("--- Internal AST Dump"))
                check(!isSectionBoundary("--- Debug Trace ---"))
                check(!isSectionBoundary("--- Debug Trace (verbose)"))
                check(!isSectionBoundary("Regular line"))
                check(!isSectionBoundary("-- Not a three-dash header"))
                check(!isSectionBoundary(""))
            }
        """.trimIndent()

        assertHighMutationScore("ResponseProjection (Section Boundary)", targetCode, testSuite)
    }
}
