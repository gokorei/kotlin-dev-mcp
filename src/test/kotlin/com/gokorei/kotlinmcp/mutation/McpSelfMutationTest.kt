package com.gokorei.kotlinmcp.mutation

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@Tag("hardening")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpSelfMutationTest {

    private val pipeline = DefaultMutationExecutionPipeline()

    @AfterAll
    fun tearDown() {
        pipeline.close()
    }

    @Test
    fun `mutation tests internal SnippetAstSafetyChecker detection logic`() {
        val targetCode = """
            fun isDangerousCall(receiver: String, selector: String): Boolean {
                if (selector == "exit" && (receiver == "System" || receiver == "java.lang.System")) {
                    return true
                }
                if (selector == "halt" && receiver.endsWith("getRuntime()")) {
                    return true
                }
                if (selector == "destroy" && receiver.startsWith("ProcessHandle.current()")) {
                    return true
                }
                return false
            }
        """.trimIndent()

        val strongTestSuite = """
            fun main() {
                check(isDangerousCall("System", "exit")) { "System.exit must be dangerous" }
                check(isDangerousCall("java.lang.System", "exit")) { "java.lang.System.exit must be dangerous" }
                check(!isDangerousCall("MySystem", "exit")) { "MySystem.exit is safe" }
                check(!isDangerousCall("System", "gc")) { "System.gc is safe" }
                check(isDangerousCall("Runtime.getRuntime()", "halt")) { "Runtime halt is dangerous" }
                check(!isDangerousCall("Runtime.getRuntime()", "availableProcessors")) { "processors is safe" }
                check(!isDangerousCall("Other", "halt")) { "other halt is safe" }
                check(isDangerousCall("ProcessHandle.current()", "destroy")) { "ProcessHandle destroy is dangerous" }
                check(!isDangerousCall("ProcessHandle.current()", "pid")) { "pid is safe" }
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, strongTestSuite)

        println("SnippetAstSafetyChecker mutation test score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed)")
        report.results.filter { it.status == MutantStatus.SURVIVED }.forEach {
            println("SURVIVED mutant at line ${it.mutant.line}: ${it.mutant.description}\n  Diff: - ${it.mutant.originalSnippet} | + ${it.mutant.mutatedSnippet}")
        }

        assertTrue(report.totalMutants >= 5, "Expected multiple mutants on safety check branching")
        assertTrue(report.effectiveMutants > 0, "Must have executable mutants")
        assertEquals(0, report.survivedCount, "All security guard mutants must be killed by strict test suite")
        assertEquals(100.0, report.score)
        assertTrue(report.isStrong)
    }

    @Test
    fun `mutation tests internal ProjectionFilter metadata pruner logic`() {
        val targetCode = """
            fun shouldPruneKey(key: String, preset: String): Boolean {
                if (preset != "COMPACT") return false
                return key == "raw" || key == "rawAst" || key == "debug"
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(shouldPruneKey("raw", "COMPACT"))
                check(shouldPruneKey("rawAst", "COMPACT"))
                check(shouldPruneKey("debug", "COMPACT"))
                check(!shouldPruneKey("symbol", "COMPACT"))
                check(!shouldPruneKey("raw", "FULL"))
                check(!shouldPruneKey("debug", "SUMMARY"))
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, testSuite)

        println("ProjectionFilter mutation test score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed)")
        assertTrue(report.totalMutants >= 3)
        assertTrue(report.effectiveMutants > 0)
        assertEquals(0, report.survivedCount, "All projection filter mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation tests internal ToonUtils table encoder formatting logic`() {
        val targetCode = """
            fun encodeRow(values: List<String>): String {
                return values.joinToString("|") { it.replace("|", "/") }
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(encodeRow(listOf("a", "b", "c")) == "a|b|c") { "standard row" }
                check(encodeRow(listOf("a|1", "b", "c")) == "a/1|b|c") { "escaped delimiter in cell" }
                check(encodeRow(emptyList()) == "") { "empty list" }
                check(encodeRow(listOf("only")) == "only") { "single element" }
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, testSuite)

        println("ToonUtils mutation test score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed)")
        assertTrue(report.totalMutants >= 2)
        assertTrue(report.effectiveMutants > 0)
        assertEquals(0, report.survivedCount, "All table encoder mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation tests ResponsePreset fromString parser`() {
        val targetCode = """
            fun parsePreset(value: String?): String {
                val normalized = value?.trim()?.lowercase()
                return when (normalized) {
                    "compact" -> "COMPACT"
                    "summary" -> "SUMMARY"
                    else -> "FULL"
                }
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(parsePreset("compact") == "COMPACT")
                check(parsePreset("  COMPACT  ") == "COMPACT")
                check(parsePreset("summary") == "SUMMARY")
                check(parsePreset("SUMMARY") == "SUMMARY")
                check(parsePreset(null) == "FULL")
                check(parsePreset("") == "FULL")
                check(parsePreset("other") == "FULL")
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, testSuite)

        println("ResponsePreset mutation test score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed)")
        assertTrue(report.totalMutants >= 2)
        assertTrue(report.effectiveMutants > 0)
        assertEquals(0, report.survivedCount, "All preset parser mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation tests Compose state-in-loop analysis rule`() {
        val targetCode = """
            fun isUnrememberedState(hasRemember: Boolean, createsState: Boolean): Boolean {
                if (createsState && !hasRemember) {
                    return true
                }
                return false
            }
        """.trimIndent()

        val testSuite = """
            fun main() {
                check(isUnrememberedState(hasRemember = false, createsState = true)) { "unremembered state" }
                check(!isUnrememberedState(hasRemember = true, createsState = true)) { "remembered state" }
                check(!isUnrememberedState(hasRemember = false, createsState = false)) { "no state creation" }
                check(!isUnrememberedState(hasRemember = true, createsState = false)) { "remember without state" }
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, testSuite)

        println("Compose rule mutation test score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed)")
        assertTrue(report.totalMutants >= 3)
        assertTrue(report.effectiveMutants > 0)
        assertEquals(0, report.survivedCount, "All Compose rule mutants must be killed")
        assertEquals(100.0, report.score)
    }

    @Test
    fun `mutation testing detects missing boundary assertion in index offset calculation`() {
        // Target: offset boundary checker
        val targetCode = """
            fun isWithinBounds(index: Int, size: Int): Boolean {
                if (index < 0 || index >= size) {
                    return false
                }
                return true
            }
        """.trimIndent()

        // Weak test suite: tests index = 2 for size = 10, but completely misses index = 0 and index = size (boundary conditions)
        val incompleteTestSuite = """
            fun main() {
                check(isWithinBounds(2, 10)) { "middle element" }
                check(!isWithinBounds(-5, 10)) { "negative element" }
                check(!isWithinBounds(15, 10)) { "well above size" }
            }
        """.trimIndent()

        val report = pipeline.run(targetCode, incompleteTestSuite)

        println("Incomplete suite score: ${report.score}% (${report.killedCount}/${report.effectiveMutants} killed, ${report.survivedCount} survived)")
        val survived = report.results.filter { it.status == MutantStatus.SURVIVED }
        survived.forEach {
            println("  ⚠️ CAUGHT SURVIVED MUTANT: Line ${it.mutant.line} [${it.mutant.operator}] - ${it.mutant.description}")
            println("     Diff: - ${it.mutant.originalSnippet} | + ${it.mutant.mutatedSnippet}")
        }

        assertTrue(report.survivedCount > 0, "Mutation testing must detect surviving boundary mutants on weak test suites")
        assertTrue(report.score < 100.0)
        assertFalse(report.isStrong)
    }
}
